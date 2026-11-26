"""OCR 테스트 공용 가짜 도구(가짜 post·환경 변수·사진 폴더·실행기 불러오기). 테스트 파일이 아니다."""

import importlib.util
import json
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "ocr" / "run_methods.py"

ENV = {
    "GMS_KEY": "gms-key", "GMS_BASE_URL": "https://gms.example/v1", "GMS_MODEL": "gpt-test",
    "CLOVA_OCR_URL": "https://clova.example/general", "CLOVA_OCR_SECRET": "clova-secret",
}
ITEMS_JSON = json.dumps({"items": [{"name": "베이컨 1kg", "unit": "팩", "qty": 3, "price": 11500, "amount": 34500}]},
                        ensure_ascii=False)


def _chat_ok(content=ITEMS_JSON):
    return 200, json.dumps({"choices": [{"message": {"role": "assistant", "content": content}}]}, ensure_ascii=False)


class FakePost:
    """가짜 post: 호출을 적어 두고, 주소에 따라 미리 정한 응답을 차례로 돌려준다(마지막 응답은 계속 반복)."""

    def __init__(self, gms=None, clova=None):
        self.calls = []
        self.gms = list(gms or [])
        self.clova = list(clova or [])

    def __call__(self, url, headers, data):
        body = json.loads(data.decode("utf-8"))
        self.calls.append((url, headers, body))
        queue = self.clova if url.startswith("https://clova") else self.gms
        r = queue.pop(0) if len(queue) > 1 else queue[0]
        if isinstance(r, Exception):
            raise r
        return r


def _load_script():
    spec = importlib.util.spec_from_file_location("run_methods", SCRIPT)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _images(tmp_path, n_per_sub=1, subs=("a", "b")):
    imgs = tmp_path / "imgs"
    for sub in subs:
        (imgs / sub).mkdir(parents=True)
        for k in range(1, n_per_sub + 1):
            (imgs / sub / f"synth_{k:03d}.jpg").write_bytes(b"jpg-" + sub.encode())
    (imgs / subs[-1] / "note.txt").write_text("not an image")
    return imgs
