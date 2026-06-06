from __future__ import annotations

import json
import re
from typing import Any, Iterable


class JSONParseError(ValueError):
    """Raised when model output cannot be parsed as JSON."""


_CODE_FENCE_RE = re.compile(r"```(?:json|JSON|markdown|md|text)?\s*([\s\S]*?)\s*```", re.MULTILINE)


def strip_code_fence(text: str) -> str:
    if not text:
        return ""
    match = _CODE_FENCE_RE.search(text.strip())
    if match:
        return match.group(1).strip()
    return text.strip()


def remove_trailing_commas(text: str) -> str:
    return re.sub(r",\s*([}\]])", r"\1", text)


def normalize_model_json_text(text: str) -> str:
    normalized = strip_code_fence(text)
    normalized = normalized.replace("\ufeff", "").strip()
    normalized = remove_trailing_commas(normalized)
    return normalized


def extract_json_text(text: str) -> str:
    if not text:
        raise JSONParseError("Empty text cannot be parsed as JSON.")

    candidate = normalize_model_json_text(text)

    if candidate.startswith("{") or candidate.startswith("["):
        return candidate

    start_positions = [index for index, char in enumerate(candidate) if char in "{["]

    for start in start_positions:
        stack: list[str] = []
        in_string = False
        escape = False

        for pos in range(start, len(candidate)):
            char = candidate[pos]

            if escape:
                escape = False
                continue

            if char == "\\":
                escape = True
                continue

            if char == '"':
                in_string = not in_string
                continue

            if in_string:
                continue

            if char in "{[":
                stack.append(char)
            elif char in "}]":
                if not stack:
                    break
                opening = stack.pop()
                if opening == "{" and char != "}":
                    break
                if opening == "[" and char != "]":
                    break
                if not stack:
                    return candidate[start : pos + 1]

    raise JSONParseError("No balanced JSON object or array found in model output.")


def loads_json_relaxed(text: str) -> Any:
    json_text = extract_json_text(text)
    json_text = normalize_model_json_text(json_text)

    try:
        return json.loads(json_text)
    except json.JSONDecodeError as first_error:
        repaired = remove_trailing_commas(json_text)
        repaired = repaired.replace("“", '"').replace("”", '"')
        repaired = repaired.replace("‘", "'").replace("’", "'")
        try:
            return json.loads(repaired)
        except json.JSONDecodeError as second_error:
            raise JSONParseError(
                f"Failed to parse JSON. First error: {first_error}. "
                f"Second error after repair: {second_error}. Raw text: {text[:500]}"
            ) from second_error


def coerce_json_object(value: Any, *, name: str = "value") -> dict[str, Any]:
    if not isinstance(value, dict):
        raise JSONParseError(f"{name} must be a JSON object, got {type(value).__name__}.")
    return value


def coerce_json_array(value: Any, *, name: str = "value") -> list[Any]:
    if not isinstance(value, list):
        raise JSONParseError(f"{name} must be a JSON array, got {type(value).__name__}.")
    return value


def json_dumps_cn(value: Any, *, indent: int = 2) -> str:
    return json.dumps(value, ensure_ascii=False, indent=indent)


def ensure_keys(data: dict[str, Any], required_keys: Iterable[str]) -> list[str]:
    return [key for key in required_keys if key not in data]


def compact_for_prompt(value: Any, max_chars: int = 6000) -> str:
    text = json_dumps_cn(value, indent=2)
    if len(text) <= max_chars:
        return text
    return text[:max_chars] + "\n...已截断..."
