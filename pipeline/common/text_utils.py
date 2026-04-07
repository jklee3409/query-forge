from __future__ import annotations

import re
from collections import Counter


SENTENCE_SPLIT_PATTERN = re.compile(r"(?<=[.!?])\s+")
KOREAN_PATTERN = re.compile(r"[가-힣]")
SPECIAL_PATTERN = re.compile(r"[^A-Za-z0-9가-힣\s]")
TOKEN_PATTERN = re.compile(r"[A-Za-z0-9_./-]+|[가-힣]+")

COMMON_TRANSLATIONS = {
    "spring": "스프링",
    "security": "시큐리티",
    "framework": "프레임워크",
    "configuration": "설정",
    "bean": "빈",
    "context": "컨텍스트",
    "query": "질의",
    "document": "문서",
    "method": "방법",
    "how": "어떻게",
    "what": "무엇",
    "why": "왜",
    "error": "오류",
    "version": "버전",
}


def split_sentences(text: str) -> list[str]:
    if not text:
        return []
    return [sentence.strip() for sentence in SENTENCE_SPLIT_PATTERN.split(text) if sentence.strip()]


def extract_extractive_summary(text: str, *, max_sentences: int = 2) -> str:
    sentences = split_sentences(text)
    if not sentences:
        return (text or "").strip()[:240]
    return " ".join(sentences[:max_sentences]).strip()


def naive_translate_to_korean(text: str) -> str:
    translated = text
    for source, target in COMMON_TRANSLATIONS.items():
        translated = re.sub(
            rf"\b{re.escape(source)}\b",
            target,
            translated,
            flags=re.IGNORECASE,
        )
    return translated


def token_count(text: str) -> int:
    return len(TOKEN_PATTERN.findall(text or ""))


def korean_ratio(text: str) -> float:
    if not text:
        return 0.0
    korean_count = len(KOREAN_PATTERN.findall(text))
    plain_count = len([char for char in text if not char.isspace()])
    if plain_count == 0:
        return 0.0
    return korean_count / plain_count


def special_char_ratio(text: str) -> float:
    if not text:
        return 0.0
    special_count = len(SPECIAL_PATTERN.findall(text))
    plain_count = len([char for char in text if not char.isspace()])
    if plain_count == 0:
        return 0.0
    return special_count / plain_count


def copy_ratio(query_text: str, reference_text: str, *, ngram: int = 4) -> float:
    query_tokens = (query_text or "").split()
    ref_tokens = (reference_text or "").split()
    if len(query_tokens) < ngram or len(ref_tokens) < ngram:
        return 0.0

    def _ngrams(tokens: list[str]) -> Counter[str]:
        sequences = [" ".join(tokens[index : index + ngram]) for index in range(len(tokens) - ngram + 1)]
        return Counter(sequences)

    query_ngrams = _ngrams(query_tokens)
    ref_ngrams = _ngrams(ref_tokens)
    overlap = sum(min(count, ref_ngrams.get(key, 0)) for key, count in query_ngrams.items())
    total = sum(query_ngrams.values())
    if total == 0:
        return 0.0
    return overlap / total

