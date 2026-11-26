import struct
import zipfile
from pathlib import Path

import pytest

from weglee_ai.seoulevent import collect_district as cd

KO_HEADER = list(cd.COLUMN_MAP)  # 실제 파일 55열과 같은 순서


def _row(yyqu: str, trdar: str, induty: str, amt: int) -> list[str]:
    vals = {"기준_년분기_코드": yyqu, "상권_구분_코드": "A", "상권_구분_코드_명": "골목상권", "상권_코드": trdar,
            "상권_코드_명": "배화여자대학교", "서비스_업종_코드": induty, "서비스_업종_코드_명": "한식음식점",
            "당월_매출_금액": str(amt)}
    return [vals.get(h, "0") for h in KO_HEADER]


def _csv(rows: list[list[str]], header=KO_HEADER) -> str:
    return "\n".join(",".join(f'"{v}"' for v in r) for r in [header, *rows]) + "\n"


def _zip(path: Path, files: dict[str, bytes]) -> Path:
    with zipfile.ZipFile(path, "w") as z:
        for name, data in files.items():
            z.writestr(name, data)
    return path


def test_load_sales_files_merges_zips_and_encodings(tmp_path):
    _zip(tmp_path / "2023.zip", {"a.csv": _csv([_row("20231", "3110008", "CS100001", 100)]).encode("cp949")})
    _zip(tmp_path / "2024.zip", {"b.csv": _csv([_row("20241", "3110008", "CS100001", 130)]).encode("utf-8-sig")})
    df = cd.load_sales_files(tmp_path)
    assert len(df) == 2
    assert df["STDR_YYQU_CD"].tolist() == ["20231", "20241"]
    assert df["TRDAR_CD"].iloc[0] == "3110008"
    assert df["SVC_INDUTY_CD_NM"].iloc[0] == "한식음식점"
    assert df["THSMON_SELNG_AMT"].tolist() == [100, 130]
    assert str(df["THSMON_SELNG_AMT"].dtype) == "int64"


def test_column_map_covers_odd_time_count_headers():
    assert cd.COLUMN_MAP["시간대_건수~06_매출_건수"] == "TMZON_00_06_SELNG_CO"
    assert cd.COLUMN_MAP["시간대_17~21_매출_금액"] == "TMZON_17_21_SELNG_AMT"
    assert cd.COLUMN_MAP["연령대_60_이상_매출_금액"] == "AGRDE_60_ABOVE_SELNG_AMT"
    assert len(cd.COLUMN_MAP) == 55


def test_unknown_column_fails_loudly(tmp_path):
    header = [*KO_HEADER[:-1], "새_컬럼"]
    _zip(tmp_path / "x.zip", {"a.csv": _csv([_row("20231", "1", "CS100001", 1)], header).encode("cp949")})
    with pytest.raises(ValueError, match="새_컬럼"):
        cd.load_sales_files(tmp_path)


def _dbf(fields: list[tuple[str, str, int]], records: list[list[str]]) -> bytes:
    header_len = 32 + 32 * len(fields) + 1
    rec_len = 1 + sum(f[2] for f in fields)
    out = bytearray(struct.pack("<BBBBIHH20x", 3, 126, 1, 1, len(records), header_len, rec_len))
    for name, typ, size in fields:
        out += struct.pack("<11sc4xBB14x", name.encode(), typ.encode(), size, 0)
    out += b"\r"
    for rec in records:
        out += b" " + b"".join(v.encode("utf-8").ljust(size)[:size] for v, (_, _, size) in zip(rec, fields))
    return bytes(out + b"\x1a")


DBF_FIELDS = [("TRDAR_SE_C", "C", 1), ("TRDAR_SE_1", "C", 12), ("TRDAR_CD", "C", 10), ("TRDAR_CD_N", "C", 60),
              ("XCNTS_VALU", "N", 20), ("YDNTS_VALU", "N", 20), ("SIGNGU_CD", "C", 5), ("SIGNGU_CD_", "C", 40),
              ("ADSTRD_CD", "C", 8), ("ADSTRD_CD_", "C", 40), ("RELM_AR", "N", 20)]


def test_load_areas_reads_dbf_centroids(tmp_path):
    dbf = _dbf(DBF_FIELDS, [["A", "골목상권", "3110008", "배화여자대학교(박노수미술관)", "197093", "453418",
                             "11110", "종로구", "11110515", "청운효자동", "149264"]])
    z = _zip(tmp_path / "areas.zip", {"영역.dbf": dbf, "영역.cpg": b"UTF-8"})
    df = cd.load_areas(z)
    row = df.iloc[0]
    assert row["TRDAR_CD"] == "3110008"
    assert row["TRDAR_CD_NM"] == "배화여자대학교(박노수미술관)"
    assert (row["x"], row["y"]) == (197093.0, 453418.0)
    assert row["SIGNGU_CD_NM"] == "종로구" and row["ADSTRD_CD"] == "11110515"


def test_areas_from_api_rows_same_columns():
    rows = [{"TRDAR_SE_CD": "A", "TRDAR_SE_CD_NM": "골목상권", "TRDAR_CD": "3110008", "TRDAR_CD_NM": "배화",
             "XCNTS_VALUE": "197093", "YDNTS_VALUE": "453418", "SIGNGU_CD": "11110", "SIGNGU_CD_NM": "종로구",
             "ADSTRD_CD": "11110515", "ADSTRD_CD_NM": "청운효자동", "RELM_AR": "149264"}]
    df = cd.areas_from_api(rows)
    assert (df.iloc[0]["x"], df.iloc[0]["y"]) == (197093.0, 453418.0)
    assert set(cd.AREA_COLUMNS) <= set(df.columns)


def test_download_all_requests_known_files(tmp_path):
    calls = []

    def fake_download(inf_id, seq, inf_seq, dest, client=None):
        calls.append((inf_id, seq, inf_seq, dest.name))
        return dest

    cd.download_all(tmp_path, download=fake_download)
    assert ("OA-15560", 5, 3, "areas.zip") in calls
    assert [c[:3] for c in calls if c[0] == "OA-15572"] == [("OA-15572", s, 3) for s in (47, 48, 49, 50, 51)]
