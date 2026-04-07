from __future__ import annotations

import math
import re
import hashlib
from typing import Iterable


DEFAULT_DIMENSION = 3072
TOKEN_PATTERN = re.compile(r"[A-Za-z0-9_./-]+|[가-힣]+")


def tokenize(text: str) -> list[str]:
    return [token.lower() for token in TOKEN_PATTERN.findall(text or "")]


def embed_text(text: str, *, dim: int = DEFAULT_DIMENSION) -> list[float]:
    vector = [0.0] * dim
    tokens = tokenize(text)
    if not tokens:
        return vector

    for token in tokens:
        digest = hashlib.sha1(token.encode("utf-8")).digest()
        hashed = int.from_bytes(digest[:8], "big", signed=False)
        index = hashed % dim
        sign = 1.0 if (hashed % 2) == 0 else -1.0
        vector[index] += sign

    norm = math.sqrt(sum(value * value for value in vector))
    if norm <= 0:
        return vector
    return [value / norm for value in vector]


def cosine_similarity(left: Iterable[float], right: Iterable[float]) -> float:
    left_values = list(left)
    right_values = list(right)
    if len(left_values) != len(right_values) or not left_values:
        return 0.0
    dot = 0.0
    left_norm = 0.0
    right_norm = 0.0
    for left_value, right_value in zip(left_values, right_values):
        dot += left_value * right_value
        left_norm += left_value * left_value
        right_norm += right_value * right_value
    if left_norm <= 0 or right_norm <= 0:
        return 0.0
    return dot / math.sqrt(left_norm * right_norm)


def embedding_to_halfvec_literal(values: Iterable[float]) -> str:
    return "[" + ",".join(f"{value:.6f}" for value in values) + "]"
