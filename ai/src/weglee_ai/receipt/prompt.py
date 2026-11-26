"""영수증 프롬프트 로더. 지라 S15P31A308-110.

백엔드 `OnboardingPrompt`(develop) 와 같은 규칙: CRLF → LF, 줄 머리의 `## system` · `## user` 제목으로 나누고
앞뒤 공백을 자른다. 같은 이름 `.schema.json` 이 응답 스키마(strict)다.
"""

import json
import re
from pathlib import Path
from typing import NamedTuple

PROMPTS_DIR = Path(__file__).resolve().parents[3] / "prompts" / "receipt"
SYSTEM_HEADING = "## system"
USER_HEADING = "## user"
PLACEHOLDER = re.compile(r"\{\{(\w+)\}\}")


def _heading_at(md: str, heading: str) -> int:
    if md.startswith(heading + "\n"):
        return 0
    i = md.find("\n" + heading + "\n")
    return -1 if i < 0 else i + 1


def split_sections(markdown: str) -> tuple[str, str]:
    """프롬프트 md → (시스템 지시, 사용자 틀). 제목이 없거나 순서가 바뀌면 ValueError."""
    md = markdown.replace("\r\n", "\n")
    if not md.endswith("\n"):
        md += "\n"
    s, u = _heading_at(md, SYSTEM_HEADING), _heading_at(md, USER_HEADING)
    if s < 0 or u < 0 or u < s:
        raise ValueError("프롬프트에 ## system · ## user 제목이 없다(또는 순서가 바뀌었다)")
    system, user = md[s + len(SYSTEM_HEADING):u].strip(), md[u + len(USER_HEADING):].strip()
    if not system or not user:
        raise ValueError("프롬프트의 ## system 또는 ## user 본문이 비었다")
    return system, user


class Prompt(NamedTuple):
    name: str
    system: str
    user_template: str
    schema: dict

    def placeholders(self) -> set[str]:
        return set(PLACEHOLDER.findall(self.user_template))

    def user(self, **values: str) -> str:
        """{{key}} 자리를 채운 사용자 글. 모르는 자리 이름은 KeyError, 채우지 않은 자리가 남으면 ValueError."""
        names = self.placeholders()
        unknown = set(values) - names
        if unknown:
            raise KeyError(f"{self.name} 프롬프트에 없는 자리: {sorted(unknown)}")
        missing = names - set(values)
        if missing:
            raise ValueError(f"{self.name} 프롬프트 자리를 다 채우지 않았다: {sorted(missing)}")
        text = self.user_template
        for k, v in values.items():
            text = text.replace("{{" + k + "}}", v)
        return text

    def response_format(self) -> dict:
        return {"type": "json_schema", "json_schema": {"name": self.name, "strict": True, "schema": self.schema}}


def load_prompt(name: str, prompts_dir: Path = PROMPTS_DIR) -> Prompt:
    d = Path(prompts_dir)
    system, user = split_sections((d / f"{name}.md").read_text(encoding="utf-8"))
    schema = json.loads((d / f"{name}.schema.json").read_text(encoding="utf-8"))
    return Prompt(name, system, user, schema)


def strict_schema_problems(schema, path: str = "$") -> list[str]:
    """OpenAI 호환 strict 스키마 규칙 위반 목록(빈 목록 = 통과).

    object 는 additionalProperties false 이고 properties 의 칸이 전부 required 여야 한다. 하위 스키마까지 본다.
    """
    probs = []
    if not isinstance(schema, dict):
        return [f"{path}: 스키마가 객체가 아니다"]
    types = schema.get("type")
    types = types if isinstance(types, list) else [types]
    if "anyOf" in schema:
        for k, sub in enumerate(schema["anyOf"]):
            probs += strict_schema_problems(sub, f"{path}.anyOf[{k}]")
    elif not any(types):
        probs.append(f"{path}: type 이 없다")
    if "object" in types:
        props = schema.get("properties")
        if not isinstance(props, dict) or not props:
            probs.append(f"{path}: object 에 properties 가 없다")
            props = {}
        if schema.get("additionalProperties") is not False:
            probs.append(f"{path}: additionalProperties 가 false 가 아니다")
        if sorted(schema.get("required") or []) != sorted(props):
            probs.append(f"{path}: required 가 properties 칸 전부와 같지 않다")
        for k, sub in props.items():
            probs += strict_schema_problems(sub, f"{path}.{k}")
    if "array" in types:
        if "items" not in schema:
            probs.append(f"{path}: array 에 items 가 없다")
        else:
            probs += strict_schema_problems(schema["items"], f"{path}[]")
    return probs
