"""상권 분기 추정매출(OA-15572) · 상권 영역(OA-15560): `python -m weglee_ai.seoulevent.collect_district`

교내망에서도 되는 파일 다운로드를 쓴다. 매출 파일은 연도별 zip(2021~2025), 영역은 SHP zip 의 DBF(중심점 · EPSG:5181).
"""

import io
import struct
import zipfile
from collections.abc import Callable
from pathlib import Path

import pandas as pd

from weglee_ai.seoulevent import config, seoul_file

INF_SEQ = 3
SALES_INF_ID = "OA-15572"
SALES_FILE_SEQ = {2021: 47, 2022: 48, 2023: 49, 2024: 50, 2025: 51}
AREAS_INF_ID, AREAS_SEQ = "OA-15560", 5

TEXT_COLUMNS = ["STDR_YYQU_CD", "TRDAR_SE_CD", "TRDAR_SE_CD_NM", "TRDAR_CD", "TRDAR_CD_NM", "SVC_INDUTY_CD", "SVC_INDUTY_CD_NM"]


def _column_map() -> dict[str, str]:
    """파일의 한글 헤더 → API 필드명. 파일 순서 그대로."""
    days = [("월요일", "MON"), ("화요일", "TUES"), ("수요일", "WED"), ("목요일", "THUR"), ("금요일", "FRI"), ("토요일", "SAT"), ("일요일", "SUN")]
    slots = [("00", "06"), ("06", "11"), ("11", "14"), ("14", "17"), ("17", "21"), ("21", "24")]
    ages = [("10", "10"), ("20", "20"), ("30", "30"), ("40", "40"), ("50", "50"), ("60_이상", "60_ABOVE")]
    m = dict(zip(
        ["기준_년분기_코드", "상권_구분_코드", "상권_구분_코드_명", "상권_코드", "상권_코드_명", "서비스_업종_코드", "서비스_업종_코드_명"],
        TEXT_COLUMNS,
    ))
    m |= {"당월_매출_금액": "THSMON_SELNG_AMT", "당월_매출_건수": "THSMON_SELNG_CO",
          "주중_매출_금액": "MDWK_SELNG_AMT", "주말_매출_금액": "WKEND_SELNG_AMT"}
    m |= {f"{ko}_매출_금액": f"{en}_SELNG_AMT" for ko, en in days}
    m |= {f"시간대_{a}~{b}_매출_금액": f"TMZON_{a}_{b}_SELNG_AMT" for a, b in slots}
    m |= {"남성_매출_금액": "ML_SELNG_AMT", "여성_매출_금액": "FML_SELNG_AMT"}
    m |= {f"연령대_{ko}_매출_금액": f"AGRDE_{en}_SELNG_AMT" for ko, en in ages}
    m |= {"주중_매출_건수": "MDWK_SELNG_CO", "주말_매출_건수": "WKEND_SELNG_CO"}
    m |= {f"{ko}_매출_건수": f"{en}_SELNG_CO" for ko, en in days}
    # 원본 헤더가 '시간대_건수~06_매출_건수' 처럼 시작 시각 자리에 '건수' 가 들어가 있다
    m |= {f"시간대_건수~{b}_매출_건수": f"TMZON_{a}_{b}_SELNG_CO" for a, b in slots}
    m |= {"남성_매출_건수": "ML_SELNG_CO", "여성_매출_건수": "FML_SELNG_CO"}
    m |= {f"연령대_{ko}_매출_건수": f"AGRDE_{en}_SELNG_CO" for ko, en in ages}
    return m


COLUMN_MAP = _column_map()

DBF_MAP = {"TRDAR_SE_C": "TRDAR_SE_CD", "TRDAR_SE_1": "TRDAR_SE_CD_NM", "TRDAR_CD": "TRDAR_CD", "TRDAR_CD_N": "TRDAR_CD_NM",
           "XCNTS_VALU": "x", "YDNTS_VALU": "y", "SIGNGU_CD": "SIGNGU_CD", "SIGNGU_CD_": "SIGNGU_CD_NM",
           "ADSTRD_CD": "ADSTRD_CD", "ADSTRD_CD_": "ADSTRD_CD_NM", "RELM_AR": "RELM_AR"}
AREA_COLUMNS = list(DBF_MAP.values())


def download_all(data_dir: Path, download: Callable = seoul_file.download, client=None) -> tuple[list[Path], Path]:
    zip_dir = data_dir / "raw" / "district_sales" / "zip"
    sales = [download(SALES_INF_ID, seq, INF_SEQ, zip_dir / f"{year}.zip", client=client) for year, seq in SALES_FILE_SEQ.items()]
    areas = download(AREAS_INF_ID, AREAS_SEQ, INF_SEQ, data_dir / "raw" / "district_areas" / "areas.zip", client=client)
    return sales, areas


def _read_text(raw: bytes) -> str:
    for enc in ("utf-8-sig", "cp949"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    raise ValueError("CSV 인코딩을 알 수 없음 (utf-8 · cp949 아님)")


def _typed_sales(df: pd.DataFrame) -> pd.DataFrame:
    for col in df.columns:
        if col not in TEXT_COLUMNS:
            df[col] = pd.to_numeric(df[col]).astype("int64")
    return df


def load_sales_files(zip_dir: Path) -> pd.DataFrame:
    frames = []
    for zp in sorted(zip_dir.glob("*.zip")):
        with zipfile.ZipFile(zp) as z:
            for info in z.infolist():
                if not info.filename.lower().endswith(".csv"):
                    continue
                df = pd.read_csv(io.StringIO(_read_text(z.read(info))), dtype=str)
                unknown = [c for c in df.columns if c not in COLUMN_MAP]
                if unknown:  # 원본 형식이 바뀌면 조용히 틀린 표를 만들지 않고 멈춘다
                    raise ValueError(f"{zp.name}: 모르는 컬럼 {unknown}")
                frames.append(df.rename(columns=COLUMN_MAP))
    return _typed_sales(pd.concat(frames, ignore_index=True))


def fetch_sales_quarter(yyqu: str, fetch_all: Callable[..., list[dict]]) -> pd.DataFrame:
    """파일에 아직 없는 최신 분기를 API 로 보충 (교내망 X)."""
    df = pd.DataFrame(fetch_all("VwsmTrdarSelngQq", yyqu), dtype=str)
    return _typed_sales(df[[c for c in COLUMN_MAP.values() if c in df.columns]])


def _read_dbf(raw: bytes, encoding: str) -> pd.DataFrame:
    n, header_len, rec_len = struct.unpack("<IHH", raw[4:12])
    fields, pos = [], 32
    while raw[pos] != 0x0D:
        name = raw[pos:pos + 11].split(b"\0")[0].decode("ascii")
        fields.append((name, chr(raw[pos + 11]), raw[pos + 16]))
        pos += 32
    rows = []
    for i in range(n):
        rec = raw[header_len + i * rec_len: header_len + (i + 1) * rec_len]
        if rec[:1] == b"*":  # 삭제 표시된 레코드
            continue
        off, row = 1, {}
        for name, _typ, size in fields:
            row[name] = rec[off:off + size].decode(encoding).strip()
            off += size
        rows.append(row)
    return pd.DataFrame(rows, columns=[f[0] for f in fields])


def load_areas(shp_zip: Path) -> pd.DataFrame:
    with zipfile.ZipFile(shp_zip) as z:
        names = {i.filename.rsplit(".", 1)[-1].lower(): i for i in z.infolist()}
        encoding = z.read(names["cpg"]).decode().strip() if "cpg" in names else "cp949"
        df = _read_dbf(z.read(names["dbf"]), encoding).rename(columns=DBF_MAP)
    df[["x", "y", "RELM_AR"]] = df[["x", "y", "RELM_AR"]].astype(float)
    return df[AREA_COLUMNS]


def areas_from_api(rows: list[dict]) -> pd.DataFrame:
    """SHP 를 못 받을 때 TbgisTrdarRelm API 행으로 같은 표를 만든다 (교내망 X)."""
    df = pd.DataFrame(rows, dtype=str).rename(columns={"XCNTS_VALUE": "x", "YDNTS_VALUE": "y"})
    df[["x", "y", "RELM_AR"]] = df[["x", "y", "RELM_AR"]].astype(float)
    return df[AREA_COLUMNS]


def main() -> None:
    cfg = config.load()
    sales_zips, areas_zip = download_all(cfg.data_dir)
    sales = load_sales_files(sales_zips[0].parent)
    areas = load_areas(areas_zip)
    out = cfg.data_dir / "raw"
    sales.to_parquet(out / "district_sales" / "sales.parquet", index=False)
    areas.to_parquet(out / "district_areas" / "areas.parquet", index=False)

    per_q = sales.groupby("STDR_YYQU_CD").size()
    orphan = set(sales["TRDAR_CD"]) - set(areas["TRDAR_CD"])


if __name__ == "__main__":
    main()
