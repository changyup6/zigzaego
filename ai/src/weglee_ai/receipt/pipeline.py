"""영수증 한 장: 정형화(normalize) → 매칭(match). 폴더 실행 · 이어 하기 · dry-run. 지라 S15P31A308-110.

사전은 비어 있다고 보고(평가 지표 §3.2) 정형화한 줄을 전부 매칭에 보낸다. 장마다 LLM 2번(품목 0줄이면 1번).
폴더 실행의 사진 찾기 · 이어 하기 판정 · 원자적 쓰기 · 연속 실패 멈춤은 109 `ocr.runner` 것을 쓴다.
"""

import base64
import csv
import hashlib
import json
import time
from pathlib import Path

from weglee_ai.llm import USAGE_KEYS, DailyQuotaError, chat
from weglee_ai.ocr import runner as ocr_runner
from weglee_ai.ocr.http import ApiError

from .catalog import catalog_json
from .parse import ParseError, parse_match, parse_normalize
from .prompt import load_prompt

GMS_VARS = ["GMS_KEY", "GMS_BASE_URL", "GMS_MODEL"]
DRY_ENV = {"GMS_KEY": "dry-run", "GMS_BASE_URL": "https://dry.gms", "GMS_MODEL": "dry-run"}
PHOTO_MARK = "(사진 첨부)"
EXTERNAL_SOURCE = "claude-vision"  # --normalized-dir 정형화 출처 기본값(Claude 가 사진을 직접 읽어 만든 것, 10/26)
SOURCE_FILE = "_source.txt"  # 바깥 정형화 폴더의 출처 이름(첫 줄) · 정형화 모델(둘째 줄, 없으면 출처 이름). 파일 없으면 EXTERNAL_SOURCE


def load_prompts() -> dict:
    return {"normalize": load_prompt("normalize"), "match": load_prompt("match")}


def _chat(prompt, user_content, env, post, sleep):
    messages = [{"role": "system", "content": prompt.system}, {"role": "user", "content": user_content}]
    return chat(messages, prompt.response_format(), base_url=env["GMS_BASE_URL"], key=env["GMS_KEY"],
                model=env["GMS_MODEL"], post=post, sleep=sleep)


def normalize_content(prompt, *, image_bytes: bytes | None = None, fmt: str = "jpg", ocr_text: str | None = None):
    """방식 A(OCR 글자) 면 글자만, 방식 B(사진) 면 지시 글 + data: URL 사진."""
    if ocr_text is not None:
        return prompt.user(ocr_text=ocr_text)
    mime = "image/png" if fmt == "png" else "image/jpeg"
    url = f"data:{mime};base64," + base64.b64encode(image_bytes).decode("ascii")
    return [{"type": "text", "text": prompt.user(ocr_text=PHOTO_MARK)},
            {"type": "image_url", "image_url": {"url": url}}]


def match_lines_json(items: list[dict]) -> str:
    return json.dumps([{"line": k, "name": it["name"], "unit": it["unit"], "qty": it["qty"], "price": it["price"]}
                       for k, it in enumerate(items, 1)], ensure_ascii=False)


def norm_cache_key(prompt, *, image_bytes: bytes | None = None, fmt: str = "jpg", ocr_text: str | None = None) -> str:
    """정형화 입력(프롬프트 시스템 · 사용자 틀 · 스키마 · 사진 또는 OCR 글자)의 sha256. 하나라도 바뀌면 저장본을 버린다."""
    h = hashlib.sha256()
    for part in (prompt.system, prompt.user_template, json.dumps(prompt.schema, ensure_ascii=False, sort_keys=True),
                 fmt, "ocr" if ocr_text is not None else "image"):
        h.update(part.encode("utf-8") + b"\0")
    h.update(ocr_text.encode("utf-8") if ocr_text is not None else (image_bytes or b""))
    return h.hexdigest()


def _read_norm_cache(path: Path | None, schema: dict, model: str, key: str | None = None):
    """저장해 둔 정형화 결과 {"doc", "usage", "model", "key"} 를 읽는다.

    깨졌거나 모양이 어긋나면(정형화 스키마 · usage 가 정수 사전), 지금 모델과 다른 모델이 만든 것이면,
    또는 key(norm_cache_key — 프롬프트 · 스키마 · 사진)가 다르면 그 파일을 지우고 None (다시 정형화한다).
    """
    if path is None or not path.is_file():
        return None
    try:
        c = json.loads(path.read_text(encoding="utf-8-sig"))
        usage = c["usage"]
        ok = (isinstance(usage, dict) and c.get("model") == model and (key is None or c.get("key") == key)
              and all(isinstance(usage.get(k, 0), int) and not isinstance(usage.get(k, 0), bool)
                      for k in USAGE_KEYS))
        if ok:
            c["doc"] = parse_normalize(json.dumps(c["doc"], ensure_ascii=False), schema)
            return c
    except (ValueError, OSError, KeyError, TypeError, AttributeError):  # ParseError 는 ValueError
        pass
    try:
        path.unlink(missing_ok=True)
    except OSError:
        pass
    return None


def read_external_norm(path: Path, schema: dict) -> dict:
    """바깥에서 만든 정형화 결과 {"supplier", "date", "items"} 를 정형화 스키마 · 날짜 규칙으로 검증해 읽는다.

    어긋나면 ParseError(파일 경로를 붙여) — 그 장은 실패로 남기고 정형화 호출로 되돌아가지 않는다.
    """
    try:
        text = Path(path).read_text(encoding="utf-8-sig")
        return parse_normalize(text, schema)
    except (OSError, ValueError) as e:  # ParseError 는 ValueError
        raise ParseError(f"바깥 정형화 {path} 를 쓸 수 없다: {e}") from e


def external_sources(images, normalized_dirs) -> dict:
    """바깥 정형화 폴더 여러 개 → {사진 상대 경로: (정형화 JSON 경로, 출처 이름, 정형화 모델)}.

    출처 이름은 폴더의 SOURCE_FILE 첫 줄(없으면 EXTERNAL_SOURCE), 정형화 모델은 둘째 줄(없으면 출처 이름 —
    claude-vision 처럼 출처가 곧 모델인 경우). 그 파일이 비었거나 같은 장이 두 폴더에 있으면 ValueError
    (어느 정형화 · 출처인지 모르니 API 를 부르기 전에 멈춘다)."""
    if normalized_dirs is None:
        return {}
    if isinstance(normalized_dirs, (str, Path)):
        normalized_dirs = [normalized_dirs]
    images = Path(images)
    found = {}
    for d in map(Path, normalized_dirs):
        sf = d / SOURCE_FILE
        source = model = EXTERNAL_SOURCE
        if sf.is_file():
            lines = [ln.strip() for ln in sf.read_text(encoding="utf-8-sig").strip().splitlines()]
            source = lines[0] if lines else ""
            if not source:
                raise ValueError(f"출처 파일이 비었다: {sf}")
            model = lines[1] if len(lines) > 1 and lines[1] else source
        for img in ocr_runner.find_images(images):
            rel = img.relative_to(images)
            ext = (d / rel).with_suffix(".json")
            if not ext.is_file():
                continue
            if rel in found:
                raise ValueError(f"같은 장의 바깥 정형화가 두 폴더에 있다: {found[rel][0]} · {ext}")
            found[rel] = (ext, source, model)
    return found


def process(env: dict, catalog: list[dict], *, image_bytes: bytes | None = None, fmt: str = "jpg",
            ocr_text: str | None = None, prompts: dict | None = None, post=None, sleep=time.sleep,
            norm_cache: Path | None = None, external_norm: Path | None = None,
            external_source: str = EXTERNAL_SOURCE, external_model: str | None = None) -> dict:
    """한 장 → {"supplier", "date", "items": [정형화 칸 + 매칭 칸], "usage", "calls", "model"}. 실패는 ApiError/ParseError.

    norm_cache 를 주면 정형화 결과를 매칭 전에 그 파일에 저장하고, 다음에 같은 파일이 있으면 정형화를 건너뛰고 매칭만 부른다
    (하루 한도로 매칭에서 멈춘 장을 다시 정형화하느라 호출을 쓰지 않게).
    external_norm 을 주면 그 파일(바깥에서 만든 정형화, 출처 external_source)을 검증해 쓰고 정형화 호출 · 저장본은 건너뛴다.
    """
    prompts = prompts or load_prompts()
    pn, pm = prompts["normalize"], prompts["match"]
    cached, calls = None, 1
    if external_norm is not None:  # 정형화 호출 0번 · 저장본(_normalize)을 읽지도 쓰지도 않는다
        norm, u1, calls = read_external_norm(external_norm, pn.schema), {k: 0 for k in USAGE_KEYS}, 0
    else:
        key = norm_cache_key(pn, image_bytes=image_bytes, fmt=fmt, ocr_text=ocr_text) if norm_cache is not None else None
        cached = _read_norm_cache(norm_cache, pn.schema, env["GMS_MODEL"], key)
        if cached is not None:
            norm, u1 = cached["doc"], {k: int(cached["usage"].get(k) or 0) for k in USAGE_KEYS}
        else:
            content, u1 = _chat(pn, normalize_content(pn, image_bytes=image_bytes, fmt=fmt, ocr_text=ocr_text),
                                env, post, sleep)
            norm = parse_normalize(content, pn.schema)
            if norm_cache is not None:
                ocr_runner._write_atomic(norm_cache, json.dumps(
                    {"doc": norm, "usage": u1, "model": env["GMS_MODEL"], "key": key}, ensure_ascii=False, indent=1))
    usage = dict(u1)
    items = norm["items"]
    if items:
        user = pm.user(catalog_json=catalog_json(catalog), lines_json=match_lines_json(items))
        content, u2 = _chat(pm, user, env, post, sleep)
        calls += 1
        usage = {k: usage[k] + u2[k] for k in USAGE_KEYS}
        matched = parse_match(content, pm.schema, len(items), [c["name"] for c in catalog],
                              units={c["name"]: c["unit"] for c in catalog})
        items = [it | m for it, m in zip(items, matched, strict=False)]
    doc = {"supplier": norm["supplier"], "date": norm["date"], "items": items, "usage": usage, "calls": calls,
           "model": env["GMS_MODEL"], "normalize_model": cached["model"] if cached else env["GMS_MODEL"],
           "match_model": env["GMS_MODEL"]}
    if external_norm is not None:
        doc["normalize_source"] = external_source  # calls · usage 는 매칭만
        doc["normalize_model"] = external_model or external_source  # gemini-reuse 는 실제 정형화 모델(gemini-2.5-flash)
    if cached is not None:
        doc["normalize_cached"] = True  # 정형화는 앞선 실행에서 했다(calls · usage 에는 그 호출도 들어 있다)
    return doc


# ---- dry-run 가짜 응답 ----

DRY_NORMALIZE = {"supplier": "dry-run 가짜", "date": None,
                 "items": [{"name": "가짜 품목", "unit": "개", "qty": 1, "price": 1000, "amount": 1000}]}
DRY_MATCH = {"items": [{"line": 1, "ingredient": None, "is_new_periodic": False, "pack": None, "confidence": 0,
                        "reason": "dry-run"}]}


def dry_run_post(url: str, headers: dict, data: bytes) -> tuple[int, str]:
    """네트워크 대신 고정 응답(정답과 무관한 품목 1줄)."""
    name = json.loads(data.decode("utf-8"))["response_format"]["json_schema"]["name"]
    doc = DRY_NORMALIZE if name == "normalize" else DRY_MATCH
    body = {"choices": [{"message": {"role": "assistant", "content": json.dumps(doc, ensure_ascii=False)}}],
            "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}}
    return 200, json.dumps(body, ensure_ascii=False)


# ---- 폴더 실행 ----

def run_folder(images, out, env: dict, catalog: list[dict], *, limit: int | None = None, post=None,
               sleep=time.sleep, log=ocr_runner.safe_print, dry_run: bool = False,
               normalized_dir=None) -> dict:
    """사진마다 process → 출력 폴더 같은 하위 경로 <이름>.json. 이미 있는 출력은 건너뛴다(실제 실행은 dry-run 결과를 다시 처리).

    limit = 이번 실행에서 새로 시도할 장 수(이미 끝나 건너뛴 장은 세지 않는다).
    정형화 결과는 매칭 전에 출력폴더/_normalize/<상대 경로>.json 에 저장하고, 장이 끝나면 지운다.
    실패 장은 JSON 을 쓰지 않고 _run.csv 에만 남긴다. 연속 5장 실패면 멈춘다. 하루 한도 429 는 그 자리에서 멈춘다.
    normalized_dir(폴더 하나 또는 목록)을 주면 그 폴더의 같은 상대 경로 <이름>.json(바깥 정형화)이 있는 장은 정형화를 부르지 않고
    매칭만 부른다(출력 normalize_source = 폴더의 출처 이름, 로그 한 줄). 없는 장은 평소대로. 출처 · 겹침은 external_sources."""
    images, out = Path(images), Path(out)
    files = ocr_runner.find_images(images)
    prompts = load_prompts()
    out.mkdir(parents=True, exist_ok=True)
    log_path = out / "_run.csv"
    new_log = not log_path.exists()
    stats = dict(total=len(files), done=0, skipped=0, failed=0, stopped=False, quota=False, calls=0, tokens=0,
                 external=0, sources={})
    externals = external_sources(images, normalized_dir)
    fails_in_row = attempted = 0
    with log_path.open("a", encoding="utf-8-sig" if new_log else "utf-8", newline="") as fh:
        w = csv.writer(fh)
        if new_log:
            w.writerow(["image", "seconds", "ok", "calls", "prompt_tokens", "completion_tokens", "error"])
        for img in files:
            rel = img.relative_to(images)
            dest = (out / rel).with_suffix(".json")
            if ocr_runner._output_ok(dest, dry_run=dry_run):
                stats["skipped"] += 1
                continue
            if limit and attempted >= limit:
                break
            attempted += 1
            # dry-run 은 실제 실행의 정형화 저장 파일을 읽지도 쓰지도 지우지도 않는다
            cache = None if dry_run else (out / "_normalize" / rel).with_suffix(".json")
            fmt = "png" if img.suffix.lower() == ".png" else "jpg"
            ext, source, nmodel = externals.get(rel, (None, EXTERNAL_SOURCE, None))
            if ext is not None:
                log(f"정형화 출처 {source}: {rel.as_posix()} <- {ext} (매칭만 부른다)")
            t0 = time.perf_counter()
            try:
                doc = process(env, catalog, image_bytes=img.read_bytes(), fmt=fmt, prompts=prompts, post=post,
                              sleep=sleep, norm_cache=cache, external_norm=ext,
                              external_source=source, external_model=nmodel)
                doc["seconds"] = round(time.perf_counter() - t0, 2)
                if dry_run:
                    doc["dry_run"] = True
                ocr_runner._write_atomic(dest, json.dumps(doc, ensure_ascii=False, indent=1))
                if cache is not None:
                    try:
                        cache.unlink(missing_ok=True)
                    except OSError:
                        pass  # 남아도 다음 실행은 출력이 있어 건너뛴다
            except DailyQuotaError as e:
                w.writerow([rel.as_posix(), f"{time.perf_counter() - t0:.2f}", 0, "", "", "", str(e)])
                fh.flush()
                stats.update(failed=stats["failed"] + 1, stopped=True, quota=True)
                log(f"하루 한도 소진 - 멈춘다({rel.as_posix()}). 정형화를 마친 장은 다음 실행에서 매칭만 부른다")
                break
            except Exception as e:  # 그 장 실패로(KeyboardInterrupt 는 통과)
                msg = str(e) if isinstance(e, ApiError) else f"{type(e).__name__}: {e}"
                w.writerow([rel.as_posix(), f"{time.perf_counter() - t0:.2f}", 0, "", "", "", msg])
                fh.flush()
                stats["failed"] += 1
                fails_in_row += 1
                log(f"실패 {rel.as_posix()}: {msg}")
                if fails_in_row >= ocr_runner.MAX_CONSECUTIVE_FAILS:
                    stats["stopped"] = True
                    log(f"연속 {ocr_runner.MAX_CONSECUTIVE_FAILS}장 실패 - 멈춘다")
                    break
                continue
            fails_in_row = 0
            u = doc["usage"]
            w.writerow([rel.as_posix(), f"{doc['seconds']:.2f}", 1, doc["calls"], u["prompt_tokens"],
                        u["completion_tokens"], ""])
            fh.flush()
            stats["done"] += 1
            if ext is not None:
                stats["external"] += 1
                stats["sources"][source] = stats["sources"].get(source, 0) + 1
            stats["calls"] += doc["calls"] - (1 if doc.get("normalize_cached") else 0)  # 이번 실행에서 부른 횟수
            stats["tokens"] += u["total_tokens"]
    return stats
