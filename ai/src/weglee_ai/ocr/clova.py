"""CLOVA General OCR 한 장 → 글자 덩어리. 지라 S15P31A308-109.

표 밖 글자는 lineBreak 기준 줄, 표는 (행, 열) 격자에 놓아 행 단위 탭 구분 줄로 만든다.
"""

import base64
import json
import time
import uuid

from .http import ApiError, is_timeout, post_json


def _center(poly) -> tuple[float, float] | None:
    vs = (poly or {}).get("vertices") or []
    if not vs:
        return None
    return sum(v.get("x", 0) for v in vs) / len(vs), sum(v.get("y", 0) for v in vs) / len(vs)


def _inside(pt, poly) -> bool:
    vs = (poly or {}).get("vertices") or []
    if pt is None or not vs:
        return False
    xs, ys = [v.get("x", 0) for v in vs], [v.get("y", 0) for v in vs]
    return min(xs) <= pt[0] <= max(xs) and min(ys) <= pt[1] <= max(ys)


def _cell_text(cell) -> str:
    return " ".join(w.get("inferText", "") for ln in cell.get("cellTextLines") or [] for w in ln.get("cellWords") or [])


def _table_lines(table: dict) -> list[str]:
    """표 칸을 (rowIndex, columnIndex) 격자에 놓아 행마다 열 수를 맞춘다. 합친 칸(rowSpan·columnSpan)은 첫 칸에 글자, 나머지는 빈칸."""
    cells = table.get("cells") or []
    if not cells:
        return []
    n_rows = max(c.get("rowIndex", 0) + max(c.get("rowSpan") or 1, 1) for c in cells)
    n_cols = max(c.get("columnIndex", 0) + max(c.get("columnSpan") or 1, 1) for c in cells)
    grid = [[""] * n_cols for _ in range(n_rows)]
    for c in cells:
        grid[c.get("rowIndex", 0)][c.get("columnIndex", 0)] = _cell_text(c)
    return ["\t".join(row) for row in grid if any(row)]


def clova_text(resp: dict) -> str:
    """CLOVA 응답 → 글자 덩어리. 표 밖 글자는 lineBreak 기준 줄, 표는 행 단위 탭 구분 줄.

    images[].inferResult 가 SUCCESS 가 아니면(칸이 없으면 성공으로 본다) ApiError.
    """
    if not isinstance(resp, dict):
        raise ApiError(f"CLOVA 응답 모양이 이상하다: {str(resp)[:200]}")
    lines = []
    for img in resp.get("images") or []:
        result = img.get("inferResult", "SUCCESS")
        if result != "SUCCESS":
            raise ApiError(f"CLOVA inferResult {result}: {img.get('message', '')}")
        tables = img.get("tables") or []
        boxes = [t.get("boundingPoly") for t in tables if t.get("boundingPoly")]
        cur = []
        for f in img.get("fields") or []:
            if any(_inside(_center(f.get("boundingPoly")), b) for b in boxes):
                continue  # 표 안 글자는 아래 표 줄로 나온다
            cur.append(f.get("inferText", ""))
            if f.get("lineBreak"):
                lines.append(" ".join(cur))
                cur = []
        if cur:
            lines.append(" ".join(cur))
        for t in tables:
            lines.extend(_table_lines(t))
    return "\n".join(lines)


def clova_general(image_bytes: bytes, fmt: str, *, url: str, secret: str, post=None) -> str:
    """CLOVA General OCR 한 장 → 글자 덩어리(str)."""
    body = {
        "version": "V2",
        "requestId": str(uuid.uuid4()),
        "timestamp": int(time.time() * 1000),
        "enableTableDetection": True,
        "images": [{"format": fmt, "name": "page", "data": base64.b64encode(image_bytes).decode("ascii")}],
    }
    try:
        status, text = post_json(post, url, {"X-OCR-SECRET": secret}, body)
    except OSError as e:
        raise ApiError(f"CLOVA {'시간 초과' if is_timeout(e) else '연결 오류'}: {e}") from e
    if status != 200:
        raise ApiError(f"CLOVA {status}: {text[:200]}")
    try:
        resp = json.loads(text)
    except ValueError as e:
        raise ApiError(f"CLOVA 응답이 JSON 이 아니다: {text[:200]}") from e
    out = clova_text(resp)
    if not out.strip():
        raise ApiError("CLOVA 글자 없음")  # 빈 글자를 LLM 에 넘기지 않는다
    return out
