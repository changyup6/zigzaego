"""영수증 테스트 공용 가짜 도구(가짜 post·재료표·사진 폴더·스크립트 불러오기). 테스트 파일이 아니다."""

import importlib.util
import json
from pathlib import Path

AI_DIR = Path(__file__).resolve().parents[2]
RUN_SCRIPT = AI_DIR / "scripts" / "receipt" / "run.py"
EVAL_SCRIPT = AI_DIR / "scripts" / "receipt" / "evaluate.py"

ENV = {"GMS_KEY": "gms-key", "GMS_BASE_URL": "https://gms.example/v1", "GMS_MODEL": "gemini-test"}

CATALOG_CSV = (
    "name,aliases,kind,unit,storage_type,per_serving,confidence,shelf_life_days\n"
    "대파,쪽파;실파,BOM,G,FRESH,10,0.8,7\n"
    "새우,칵테일새우;흰다리새우;깐새우;생새우,BOM,EA,FROZEN,5,0.5,180\n"
    "식용유,카놀라유;콩기름;튀김기름,BOM,ML,AMBIENT,20,0.8,365\n"
)

NORMALIZE_DOC = {"supplier": "가짜마트", "date": "2026-10-19", "items": [
    {"name": "대파 1단", "unit": "단", "qty": 2, "price": 3000, "amount": 6000},
    {"name": "냉동새우 1kg", "unit": "팩", "qty": 1, "price": 15000, "amount": 15000},
]}
MATCH_DOC = {"items": [
    {"line": 1, "ingredient": "대파", "is_new_periodic": False, "pack": {"qty": 1000, "unit": "g"},
     "confidence": 0.9, "reason": "대파 1단 약 1kg"},
    {"line": 2, "ingredient": "새우", "is_new_periodic": False, "pack": {"qty": 35, "unit": "ea"},
     "confidence": 0.8, "reason": "1kg 약 35마리"},
]}
USAGE_N = {"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120}
USAGE_M = {"prompt_tokens": 300, "completion_tokens": 50, "total_tokens": 350}


def chat_ok(doc, usage=None):
    content = doc if isinstance(doc, str) else json.dumps(doc, ensure_ascii=False)
    body = {"choices": [{"message": {"role": "assistant", "content": content}}]}
    if usage is not None:
        body["usage"] = usage
    return 200, json.dumps(body, ensure_ascii=False)


class FakePost:
    """가짜 post: 응답 형식 이름(normalize·match)에 따라 미리 정한 응답을 차례로 돌려준다(마지막 응답은 반복)."""

    def __init__(self, normalize=None, match=None):
        self.calls = []
        self.queues = {"normalize": list(normalize or [chat_ok(NORMALIZE_DOC, USAGE_N)]),
                       "match": list(match or [chat_ok(MATCH_DOC, USAGE_M)])}

    def __call__(self, url, headers, data):
        body = json.loads(data.decode("utf-8"))
        self.calls.append((url, headers, body))
        q = self.queues[body["response_format"]["json_schema"]["name"]]
        r = q.pop(0) if len(q) > 1 else q[0]
        if isinstance(r, Exception):
            raise r
        return r


def write_catalog(tmp_path) -> Path:
    p = tmp_path / "catalog.csv"
    p.write_text(CATALOG_CSV, encoding="utf-8")
    return p


def make_images(tmp_path, n=2) -> Path:
    imgs = tmp_path / "imgs"
    imgs.mkdir()
    for k in range(1, n + 1):
        (imgs / f"r_{k:02d}.jpg").write_bytes(b"jpg-" + bytes([k]))
    return imgs


def load_script(path: Path):
    spec = importlib.util.spec_from_file_location(path.stem + "_receipt_script", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod
