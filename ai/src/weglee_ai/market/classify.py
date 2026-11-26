"""식자재몰 상품명 → 품목군 라벨 LLM 배치 (S15P31A308-229).

기준: docs/plans/2026-11-03_식자재몰_품목군_분류_기준.md 4절(라벨) · 9절(배치).
- 상품명 N개와 품목군 표를 한 요청에 보내고 json_schema 로 응답을 받는다(weglee_ai.llm.chat 과 같은 OpenAI 호환 호출)
- 응답은 검증한다: 입력 순서 · 이름 그대로, 코드는 표에 있는 것, 코드가 있으면 form 은 RAW · PREPARED
- 한 묶음이 깨지면 반으로 나눠 다시 보내고, 하나짜리도 깨지면 그 상품은 UNKNOWN(이유 "응답 오류")
네트워크는 주입받은 chat 함수만 쓴다(테스트 · dry-run 은 fake_chat).
"""
from __future__ import annotations

import json
import re
from collections.abc import Callable, Iterable, Iterator
from dataclasses import asdict, dataclass, fields
from pathlib import Path

from weglee_ai.llm import ParseError, extract_json
from weglee_ai.llm import chat as llm_chat
from weglee_ai.market.taxonomy import Taxonomy
from weglee_ai.ocr.http import ApiError
from weglee_ai.receipt.prompt import Prompt
from weglee_ai.receipt.prompt import load_prompt as _load_prompt

# 프롬프트 · 품목군 표 · 모델이 바뀌면 올린다 — 다른 버전 라벨은 다시 분류한다.
# v2: 표에 있는 조미료 · 기름 · 장류는 원물(간장 · 식용유가 가공품으로 빠지던 문제)
LABEL_VERSION = "v2"
PROMPTS_DIR = Path(__file__).resolve().parents[3] / "prompts" / "market"
FORMS = ("RAW", "PREPARED", "PROCESSED", "NONFOOD", "UNKNOWN")
UNITS = ("G", "ML", "EA")
BATCH_SIZE = 40
MIN_CONFIDENCE = 0.6  # 이 아래면 집계에서 UNKNOWN 취급(기준 문서 10절). 라벨 자체는 그대로 둔다

ChatFn = Callable[[list, dict], tuple[str, dict]]  # (messages, response_format) → (본문, usage)


@dataclass(frozen=True)
class Label:
    product_name: str
    item_group_code: str | None
    form: str
    qty: float | None
    qty_unit: str | None
    confidence: float
    reason: str
    label_version: str = LABEL_VERSION

    def as_row(self) -> dict:
        return asdict(self)


LABEL_COLUMNS = [f.name for f in fields(Label)]


def load_prompt(name: str = "item_group") -> Prompt:
    """receipt/prompt 의 로더 그대로(## system · ## user 나누기, 같은 이름 .schema.json)."""
    return _load_prompt(name, PROMPTS_DIR)


def response_format(prompt: Prompt) -> dict:
    return prompt.response_format()


def build_messages(prompt: Prompt, names: list[str], taxonomy: Taxonomy) -> list[dict]:
    products = "\n".join(f"{i + 1}. {n}" for i, n in enumerate(names))
    return [{"role": "system", "content": prompt.system.replace("{{groups}}", taxonomy.prompt_table())},
            {"role": "user", "content": prompt.user(products=products)}]


def _num(v) -> float | None:
    if v is None or isinstance(v, bool):
        return None
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return f if f > 0 else None


def parse_labels(content: str, names: list[str], taxonomy: Taxonomy, version: str = LABEL_VERSION) -> list[Label]:
    """응답 본문 → Label 목록(입력 순서). 모양이 어긋나면 ValueError."""
    try:
        doc = extract_json(content)
        items = doc["items"]
    except (ParseError, KeyError, TypeError) as e:
        raise ValueError(f"응답이 JSON {{items: [...]}} 이 아니다: {content[:200]}") from e
    if not isinstance(items, list) or len(items) != len(names):
        raise ValueError(f"응답 항목 수가 입력과 다르다: {len(items) if isinstance(items, list) else '?'} != {len(names)}")
    out = []
    for name, it in zip(names, items, strict=False):
        if not isinstance(it, dict):
            raise ValueError("응답 항목이 객체가 아니다")
        got = str(it.get("name") or "")
        if got.strip() != name.strip():
            raise ValueError(f"응답 상품명이 입력과 다르다: {got!r} != {name!r}")
        code = it.get("item_group_code")
        code = str(code).strip().upper() if code not in (None, "") else None
        form = str(it.get("form") or "UNKNOWN").upper()
        if form not in FORMS:
            raise ValueError(f"form 값이 이상하다: {form}")
        if code is not None and taxonomy.get(code) is None:
            code, form = None, "UNKNOWN"  # 표에 없는 코드를 지어내면 모르는 것으로
        if code is not None and form not in ("RAW", "PREPARED"):
            code = None  # 가공품 · 비식품에는 코드가 없다
        if code is None and form in ("RAW", "PREPARED"):
            form = "UNKNOWN"  # 원물이라면서 품목군이 없으면 표 밖 식품 — 집계에 안 쓴다
        unit = it.get("qty_unit")
        unit = str(unit).upper() if unit else None
        qty = _num(it.get("qty"))
        if unit not in UNITS or qty is None:
            qty, unit = None, None
        conf = _num(it.get("confidence")) or 0.0
        out.append(Label(name, code, form, qty, unit, min(conf, 1.0), str(it.get("reason") or "").strip()[:80], version))
    return out


def merge_labels(existing: dict[str, dict], labels: Iterable[Label]) -> dict[str, dict]:
    """상품명 → 라벨 행 사전에 새 라벨을 **이름 기준으로 교체**해 넣는다. 기존 라벨은 새 라벨이 실제로 올 때만 바뀐다 —
    중간에 멈춰도 아직 다시 분류하지 못한 상품명의 이전 라벨이 사라지지 않는다."""
    for label in labels:
        existing[label.product_name] = label.as_row()
    return existing


def unknown(name: str, reason: str, version: str = LABEL_VERSION) -> Label:
    return Label(name, None, "UNKNOWN", None, None, 0.0, reason, version)


def batches(names: Iterable[str], size: int = BATCH_SIZE) -> Iterator[list[str]]:
    buf: list[str] = []
    for n in names:
        buf.append(n)
        if len(buf) == size:
            yield buf
            buf = []
    if buf:
        yield buf


def _add(total: dict, usage: dict) -> None:
    for k, v in (usage or {}).items():
        total[k] = total.get(k, 0) + int(v or 0)


def classify(names: Iterable[str], chat: ChatFn, taxonomy: Taxonomy, prompt: Prompt | None = None, *,
             batch_size: int = BATCH_SIZE, version: str = LABEL_VERSION) -> Iterator[tuple[list[Label], dict]]:
    """묶음마다 (라벨들, usage) 를 낸다. usage 에는 requests · retries 도 센다.

    응답 모양 오류(ValueError)는 묶음을 반으로 나눠 다시 보낸다. 하나짜리도 깨지면 UNKNOWN.
    API 오류(ApiError · DailyQuotaError)는 그대로 올린다 — 부르는 쪽이 저장하고 멈춘다.
    """
    prompt = prompt or load_prompt()
    fmt = response_format(prompt)

    def run(batch: list[str], usage: dict) -> list[Label]:
        content, u = chat(build_messages(prompt, batch, taxonomy), fmt)
        _add(usage, u)
        usage["requests"] = usage.get("requests", 0) + 1
        try:
            return parse_labels(content, batch, taxonomy, version)
        except ValueError as e:
            usage["retries"] = usage.get("retries", 0) + 1
            if len(batch) == 1:
                return [unknown(batch[0], f"응답 오류: {str(e)[:40]}", version)]
            mid = len(batch) // 2
            return run(batch[:mid], usage) + run(batch[mid:], usage)

    for batch in batches(names, batch_size):
        usage: dict = {}
        yield run(batch, usage), usage


def chat_with_temperature_fallback(base_url: str, key: str, model: str, chat=llm_chat) -> ChatFn:
    """GMS 호출 함수. temperature 는 **모델이 받으면 0**(재현성), **받지 않는 모델(gpt-5 계열)은 보내지 않는다**.
    모델 이름으로 가르지 않고 처음 한 번 0 으로 보내 본다 — 400 에 `temperature` 미지원이 오면 그 요청을 temperature 없이 다시 보내고
    그 뒤로는 계속 생략한다. 운영에서 모델이 바뀌어도 코드를 고칠 일이 없다(기준 문서 9절)."""
    state: dict = {"temperature": 0}

    def f(messages: list, fmt: dict) -> tuple[str, dict]:
        try:
            return chat(messages, fmt, base_url=base_url, key=key, model=model, temperature=state["temperature"])
        except ApiError as e:
            if state["temperature"] is not None and unsupported_temperature(str(e)):
                state["temperature"] = None
                return chat(messages, fmt, base_url=base_url, key=key, model=model, temperature=None)
            raise
    return f


def unsupported_temperature(error: str) -> bool:
    """OpenAI 호환 400: {"param":"temperature","code":"unsupported_value"} 또는 'temperature' does not support …."""
    return "400" in error and "temperature" in error and ("unsupported" in error or "does not support" in error)


_LINE = re.compile(r"^\d+\. (.*)$", re.M)


def fake_chat(taxonomy: Taxonomy) -> ChatFn:
    """dry-run · 테스트용: LLM 없이 첫 후보 품목군을 RAW 로 돌려준다(확신 0.5, 이유 'dry-run'). 비용 · 흐름 점검에만 쓴다."""

    def fake(messages: list, _fmt: dict) -> tuple[str, dict]:
        names = _LINE.findall(messages[-1]["content"])
        items = []
        for n in names:
            codes = taxonomy.candidate_codes(n)
            items.append({"name": n, "item_group_code": codes[0] if codes else None, "form": "RAW" if codes else "UNKNOWN",
                          "qty": None, "qty_unit": None, "confidence": 0.5, "reason": "dry-run"})
        body = json.dumps({"items": items}, ensure_ascii=False)
        return body, {"prompt_tokens": len(messages[0]["content"]) // 2 + len(messages[1]["content"]) // 2,
                      "completion_tokens": len(body) // 2, "total_tokens": 0}

    return fake
