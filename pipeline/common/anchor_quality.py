from __future__ import annotations

import re

TECHNICAL_TOKEN_RE = re.compile(r"[@A-Za-z0-9_./:$-]+")
HANGUL_RE = re.compile(r"[가-힣]")
ALPHA_RE = re.compile(r"[A-Za-z]")
DIGIT_RE = re.compile(r"\d")

EN_ANCHOR_STOPWORDS = {
    "spring",
    "security",
    "framework",
    "example",
    "examples",
    "config",
    "configuration",
}

KO_FUNCTIONAL_PHRASES = {
    "부탁드립니다",
    "제거되었습니다",
    "수 있습니다",
    "지원합니다",
    "됩니다",
}

EN_FUNCTIONAL_PHRASES = {
    "can be",
    "supported",
    "supports",
    "removed",
    "deprecated",
    "available",
}

KO_FUNCTIONAL_SUFFIXES = (
    "합니다",
    "됩니다",
    "있습니다",
    "없습니다",
    "지원합니다",
    "제공합니다",
    "사용합니다",
    "가능합니다",
    "권장합니다",
    "제거되었습니다",
    "추가되었습니다",
    "변경되었습니다",
    "부탁드립니다",
)


def normalize_anchor_text(text: str) -> str:
    return str(text or "").strip().strip(".,;:!?()[]{}<>\"'`")


def guess_language(text: str) -> str:
    value = str(text or "")
    if not value:
        return "en"
    hangul_count = len(HANGUL_RE.findall(value))
    alpha_count = len(ALPHA_RE.findall(value))
    if hangul_count > alpha_count:
        return "ko"
    return "en"


def has_technical_marker(token: str) -> bool:
    value = normalize_anchor_text(token)
    if len(value) < 3:
        return False
    if value.startswith("@"):
        return True
    if any(separator in value for separator in (".", "_", "-", "/", ":")):
        return True
    has_alpha = bool(ALPHA_RE.search(value))
    has_digit = bool(DIGIT_RE.search(value))
    if has_alpha and has_digit:
        return True
    if any(char.isupper() for char in value) and any(char.islower() for char in value):
        return True
    return False


def is_functional_phrase(text: str, language_hint: str | None = None) -> bool:
    value = normalize_anchor_text(text)
    if not value:
        return True
    language = (language_hint or guess_language(value)).lower()
    lower = value.casefold()
    if language == "ko":
        if value in KO_FUNCTIONAL_PHRASES:
            return True
        if "수 있습니다" in value:
            return True
        if not has_technical_marker(value) and any(value.endswith(suffix) for suffix in KO_FUNCTIONAL_SUFFIXES):
            return True
        return False
    if lower in EN_FUNCTIONAL_PHRASES:
        return True
    if not has_technical_marker(value) and lower in EN_ANCHOR_STOPWORDS:
        return True
    return False


def is_valid_anchor_phrase(
    text: str,
    *,
    language_hint: str | None = None,
    min_length: int = 3,
    max_length: int = 120,
) -> bool:
    value = normalize_anchor_text(text)
    if not value:
        return False
    if len(value) < min_length or len(value) > max_length:
        return False
    if value.count(".") > 6:
        return False
    if is_functional_phrase(value, language_hint=language_hint):
        return False
    return True


def extract_technical_tokens(
    text: str,
    *,
    language_hint: str | None = None,
    max_items: int = 8,
) -> list[str]:
    value = str(text or "")
    if not value:
        return []
    collected: list[str] = []
    seen: set[str] = set()
    language = (language_hint or guess_language(value)).lower()
    for raw_token in TECHNICAL_TOKEN_RE.findall(value):
        token = normalize_anchor_text(raw_token)
        if not token:
            continue
        lowered = token.casefold()
        if lowered in EN_ANCHOR_STOPWORDS:
            continue
        if lowered in seen:
            continue
        if not has_technical_marker(token):
            continue
        if not is_valid_anchor_phrase(token, language_hint=language):
            continue
        seen.add(lowered)
        collected.append(token)
        if len(collected) >= max_items:
            break
    return collected
