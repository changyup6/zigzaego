"""서울 열린데이터광장 파일 다운로드 (datafile.seoul.go.kr). 교내망에서도 된다."""

import re
from pathlib import Path

import httpx

from weglee_ai.seoulevent._http import send

URL = "https://datafile.seoul.go.kr/bigfile/iot/inf/nio_download.do?&useCache=false"
PAGE = "https://data.seoul.go.kr/dataList/{inf_id}/S/1/datasetView.do"


class DownloadError(Exception):
    pass


SHEET_URL = "https://datafile.seoul.go.kr/bigfile/iot/sheet/csv/download.do"


def download_sheet(inf_id: str, dest: Path, order_by: str = "", client: httpx.Client | None = None,
                   force: bool = False) -> Path:
    """첨부 파일이 없는 시트형 데이터셋을 CSV 로 (페이지의 '내려받기(CSV)' 와 같은 요청). 교내망 가능 · 키 불필요."""
    if dest.exists() and not force:
        return dest
    client = client or httpx.Client(timeout=120)
    form = {"srvType": "S", "infId": inf_id, "serviceKind": "1", "pageNo": "1", "gridTotalCnt": "",
            "ssUserId": "SAMPLE_VIEW", "strWhere": "", "strOrderby": order_by, "filterCol": "필터선택", "txtFilter": ""}
    resp = send(client, "POST", SHEET_URL, data=form)
    if "text/html" in resp.headers.get("content-type", ""):
        raise DownloadError(f"{inf_id}: 시트 CSV 대신 HTML 응답")
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(resp.content)
    return dest


def list_files(inf_id: str, client: httpx.Client | None = None) -> tuple[int, dict[str, int]]:
    """데이터셋 페이지에서 첨부 파일 목록을 읽는다 → (infSeq, {파일명: seq}). 달마다 늘어나는 파일용."""
    client = client or httpx.Client(timeout=30)
    html = send(client, "GET", PAGE.format(inf_id=inf_id)).text
    form = re.search(r'name="frmFile".*?name="infSeq" value="(\d+)"', html, re.S)
    if not form:
        raise DownloadError(f"{inf_id}: 파일 목록이 없는 데이터셋")
    files = {title: int(seq) for seq, title in re.findall(r"downloadFile\('(\d+)'\);\" title=\"([^\"]+)\"", html)}
    return int(form.group(1)), files


def download(
    inf_id: str,
    seq: int,
    inf_seq: int,
    dest: Path,
    client: httpx.Client | None = None,
    force: bool = False,
) -> Path:
    if dest.exists() and not force:
        return dest
    client = client or httpx.Client(timeout=120)
    resp = send(client, "POST", URL, data={"infId": inf_id, "seq": str(seq), "infSeq": str(inf_seq)})
    if "text/html" in resp.headers.get("content-type", ""):
        raise DownloadError(f"{inf_id} seq={seq}: 파일 대신 HTML 응답")
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    tmp.write_bytes(resp.content)
    tmp.replace(dest)
    return dest
