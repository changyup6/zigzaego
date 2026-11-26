"""OCR 폴더 실행: 환경 변수·.env·사진 찾기·이어 하기·원자적 쓰기·dry-run. 지라 S15P31A308-109."""

import csv
import json
import os
import re
import sys
import time
from pathlib import Path

from .http import ApiError
from .methods import METHODS, method_a, method_b

GMS_VARS = ["GMS_KEY", "GMS_BASE_URL", "GMS_MODEL"]
CLOVA_VARS = ["CLOVA_OCR_URL", "CLOVA_OCR_SECRET"]
IMAGE_EXTS = (".jpg", ".jpeg", ".png")
CLOVA_INTERVAL = 1.0  # 설계 4.2절 권장 1 tps
MAX_CONSECUTIVE_FAILS = 5  # 연속 이만큼 API 실패면 멈춘다(키가 틀렸을 때 다 부르지 않게)


def safe_print(msg: str) -> None:
    """콘솔(cp949 등)이 못 쓰는 글자는 '?' 로 바꿔 찍는다 — API 오류 본문 때문에 실행이 멈추지 않게."""
    try:
        print(msg)
    except UnicodeEncodeError:
        enc = getattr(sys.stdout, "encoding", None) or "ascii"
        print(msg.encode(enc, errors="replace").decode(enc))


# ---- 환경 변수 ----

def load_env(environ: dict, env_file: Path) -> dict:
    """환경 변수 + .env(KEY=VALUE). 이미 있는 환경 변수가 우선(빈 값·공백만이면 없는 것으로 보고 .env 값).

    따옴표 없는 값은 ' #' 뒤를 줄 끝 주석으로 버린다. 따옴표로 감싼 값은 따옴표 안만 쓴다(뒤 주석 무시).
    """
    env = dict(environ)
    p = Path(env_file)
    if not p.is_file():
        return env
    for line in p.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        if line.startswith("export "):
            line = line[len("export "):]
        k, v = line.split("=", 1)
        k, v = k.strip(), v.strip()
        if v[:1] in ("'", '"') and v.find(v[0], 1) > 0:
            v = v[1:v.find(v[0], 1)]
        else:
            v = re.split(r"\s#", v, maxsplit=1)[0].strip()
        if not str(env.get(k) or "").strip():  # 빈(공백만) 환경 변수는 없는 것으로 본다
            env[k] = v
    return env


def missing_env(method: str, env: dict) -> list[str]:
    need = GMS_VARS + (CLOVA_VARS if method == "clova_llm" else [])
    return [k for k in need if not str(env.get(k) or "").strip()]


# ---- dry-run 가짜 응답 ----

DRY_ITEMS = {"items": [{"name": "가짜 품목", "unit": "개", "qty": 1, "price": 1000, "amount": 1000}]}
DRY_ENV = {k: "dry-run" for k in GMS_VARS + CLOVA_VARS} | {"GMS_BASE_URL": "https://dry.gms", "CLOVA_OCR_URL": "https://dry.clova"}


def dry_run_post(url: str, headers: dict, data: bytes) -> tuple[int, str]:
    """네트워크 대신 고정 응답(정답과 무관한 품목 1줄)."""
    if url.startswith(DRY_ENV["CLOVA_OCR_URL"]):
        return 200, json.dumps({"images": [{"fields": [{"inferText": "가짜 품목 1 1000", "lineBreak": True}]}]},
                               ensure_ascii=False)
    content = json.dumps(DRY_ITEMS, ensure_ascii=False)
    return 200, json.dumps({"choices": [{"message": {"role": "assistant", "content": content}}]}, ensure_ascii=False)


# ---- 폴더 실행 ----

def find_images(folder: Path) -> list[Path]:
    """하위 폴더까지 jpg·jpeg·png (상대 경로 순)."""
    folder = Path(folder)
    return sorted((p for p in folder.rglob("*") if p.is_file() and p.suffix.lower() in IMAGE_EXTS),
                  key=lambda p: p.relative_to(folder).as_posix())


def _output_ok(dest: Path, *, dry_run: bool = False) -> bool:
    """이미 쓴 출력이 JSON 으로 읽히고 items 가 목록이면 True (비었거나 반쪽이면 다시 처리한다).

    실제 실행(dry_run=False)에서는 dry-run 가짜 결과("dry_run": true)도 처리 안 된 것으로 본다.
    dry-run 실행은 실제 결과를 건드리지 않고 건너뛴다.
    """
    if not dest.is_file():
        return False
    try:
        doc = json.loads(dest.read_text(encoding="utf-8-sig"))
    except (ValueError, OSError):
        return False
    if not (isinstance(doc, dict) and isinstance(doc.get("items"), list)):
        return False
    return dry_run or not doc.get("dry_run")


def _write_atomic(dest: Path, text: str) -> None:
    """같은 폴더 임시 파일에 다 쓴 뒤 os.replace — 중간에 끊겨도 반쪽 출력이 남지 않는다."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_name(dest.name + ".tmp")
    try:
        tmp.write_text(text, encoding="utf-8")
        os.replace(tmp, dest)
    finally:
        try:
            if tmp.exists():
                tmp.unlink()
        except OSError:
            pass  # 정리는 시도만 한다(원래 오류를 가리지 않게)


def run_folder(images: Path, out: Path, method: str, env: dict, *, limit: int | None = None, post=None,
               sleep=time.sleep, clock=time.monotonic, clova_interval: float = CLOVA_INTERVAL, log=safe_print,
               dry_run: bool = False) -> dict:
    """폴더의 사진을 한 장씩 돌려 출력 폴더의 같은 하위 경로에 <이름>.json 으로 쓴다.

    이미 있는 출력은 건너뛴다(실제 실행은 dry-run 가짜 결과를 다시 처리한다). API·쓰기 실패 장은 JSON 을 쓰지 않고
    _run.csv 에만 남긴다. 연속 5장 실패면 멈춘다. dry_run 이면 출력에 "dry_run": true 를 붙인다.
    """
    if method not in METHODS:
        raise ValueError(f"method 는 {METHODS} 중 하나: {method}")
    images, out = Path(images), Path(out)
    run = method_a if method == "clova_llm" else method_b
    files = find_images(images)[:limit] if limit else find_images(images)
    out.mkdir(parents=True, exist_ok=True)
    log_path = out / "_run.csv"
    new_log = not log_path.exists()
    stats = dict(total=len(files), done=0, skipped=0, failed=0, stopped=False)
    last_clova = None
    fails_in_row = 0
    with log_path.open("a", encoding="utf-8-sig" if new_log else "utf-8", newline="") as fh:
        w = csv.writer(fh)
        if new_log:
            w.writerow(["image", "method", "seconds", "ok", "error"])
        for img in files:
            rel = img.relative_to(images)
            dest = (out / rel).with_suffix(".json")
            if _output_ok(dest, dry_run=dry_run):
                stats["skipped"] += 1
                continue
            if method == "clova_llm" and clova_interval > 0:
                if last_clova is not None:
                    wait = clova_interval - (clock() - last_clova)
                    if wait > 0:
                        sleep(wait)
                last_clova = clock()
            fmt = "png" if img.suffix.lower() == ".png" else "jpg"
            t0 = time.perf_counter()
            try:
                doc = run(img.read_bytes(), fmt, env, post=post, sleep=sleep)
                if dry_run:
                    doc["dry_run"] = True  # 가짜 결과 표시 — 실제 실행이 다시 처리하고 채점기가 경고한다
                _write_atomic(dest, json.dumps(doc, ensure_ascii=False, indent=1))
            except Exception as e:  # 예상 못 한 오류·쓰기 실패도 그 장 실패로(KeyboardInterrupt 는 통과)
                secs = time.perf_counter() - t0
                msg = str(e) if isinstance(e, ApiError) else f"{type(e).__name__}: {e}"
                w.writerow([rel.as_posix(), method, f"{secs:.2f}", 0, msg])
                fh.flush()
                stats["failed"] += 1
                fails_in_row += 1
                log(f"실패 {rel.as_posix()}: {e}")
                if fails_in_row >= MAX_CONSECUTIVE_FAILS:
                    stats["stopped"] = True
                    log(f"연속 {MAX_CONSECUTIVE_FAILS}장 API 실패 - 멈춘다")
                    break
                continue
            secs = time.perf_counter() - t0
            fails_in_row = 0
            w.writerow([rel.as_posix(), method, f"{secs:.2f}", 1, doc.get("error", "")])
            fh.flush()
            stats["done"] += 1
    return stats
