from __future__ import annotations

import math
import os
import threading
from collections import Counter, defaultdict
from dataclasses import dataclass
from typing import Iterable

try:
    import numpy as np
except Exception:  # pragma: no cover - numpy is optional at import time
    np = None  # type: ignore[assignment]

try:
    from common.embeddings import cosine_similarity, embed_text, tokenize
except ModuleNotFoundError:  # pragma: no cover
    from pipeline.common.embeddings import cosine_similarity, embed_text, tokenize


DEFAULT_MODEL_NAME = "intfloat/multilingual-e5-small"


@dataclass(slots=True)
class RankedText:
    index: int
    score: float
    dense_score: float
    bm25_score: float
    technical_score: float


class LocalTextRetriever:
    def __init__(
        self,
        *,
        item_ids: list[str],
        texts: list[str],
        fallback_embeddings: list[list[float]] | None = None,
    ) -> None:
        self.item_ids = item_ids
        self.texts = texts
        self.fallback_embeddings = fallback_embeddings or []
        self._tokens = [tokenize(text) for text in texts]
        self._technical_sets = [_technical_tokens(tokens) for tokens in self._tokens]
        self._build_bm25_index()
        self._dense_backend = _dense_backend()
        self.dense_backend_name = self._dense_backend.name
        self._dense_passages = self._dense_backend.encode_passages(texts, self.fallback_embeddings)

    @property
    def retriever_name(self) -> str:
        return f"bm25-dense-local:{self.dense_backend_name}"

    def rank(self, query_text: str, *, top_k: int) -> list[RankedText]:
        if not self.texts or top_k <= 0:
            return []
        query_tokens = tokenize(query_text)
        bm25_scores = self._bm25_scores(query_tokens)
        dense_scores = self._dense_backend.score_query(
            query_text,
            self._dense_passages,
            self.fallback_embeddings,
        )
        technical_scores = self._technical_scores(query_tokens)
        bm25_norm = _max_normalize(bm25_scores)
        dense_norm = [_normalize_dense(score) for score in dense_scores]
        dense_weight, bm25_weight, technical_weight = _weights(self._dense_backend.real_dense)

        ranked: list[RankedText] = []
        for index in range(len(self.texts)):
            combined = (
                dense_weight * dense_norm[index]
                + bm25_weight * bm25_norm[index]
                + technical_weight * technical_scores[index]
            )
            ranked.append(
                RankedText(
                    index=index,
                    score=max(-1.0, min(1.0, (combined * 2.0) - 1.0)),
                    dense_score=dense_scores[index],
                    bm25_score=bm25_scores[index],
                    technical_score=technical_scores[index],
                )
            )
        ranked.sort(key=lambda item: item.score, reverse=True)
        return ranked[: min(top_k, len(ranked))]

    def _build_bm25_index(self) -> None:
        self._doc_lengths = [len(tokens) for tokens in self._tokens]
        self._avg_doc_length = (
            sum(self._doc_lengths) / max(1, len(self._doc_lengths))
        )
        self._inverted: dict[str, list[tuple[int, int]]] = defaultdict(list)
        document_frequency: Counter[str] = Counter()
        for index, tokens in enumerate(self._tokens):
            counts = Counter(tokens)
            for token, count in counts.items():
                self._inverted[token].append((index, count))
                document_frequency[token] += 1
        document_count = max(1, len(self._tokens))
        self._idf = {
            token: math.log(1.0 + ((document_count - df + 0.5) / (df + 0.5)))
            for token, df in document_frequency.items()
        }

    def _bm25_scores(self, query_tokens: list[str]) -> list[float]:
        scores = [0.0] * len(self.texts)
        if not query_tokens:
            return scores
        k1 = _float_env("QUERY_FORGE_LOCAL_BM25_K1", 1.2)
        b = _float_env("QUERY_FORGE_LOCAL_BM25_B", 0.75)
        avgdl = max(1e-9, self._avg_doc_length)
        for token in set(query_tokens):
            postings = self._inverted.get(token)
            if not postings:
                continue
            idf = self._idf.get(token, 0.0)
            for index, term_frequency in postings:
                doc_len = self._doc_lengths[index]
                denominator = term_frequency + k1 * (1.0 - b + b * (doc_len / avgdl))
                if denominator <= 0.0:
                    continue
                scores[index] += idf * ((term_frequency * (k1 + 1.0)) / denominator)
        return scores

    def _technical_scores(self, query_tokens: list[str]) -> list[float]:
        query_technical = _technical_tokens(query_tokens)
        if not query_technical:
            return [0.0] * len(self.texts)
        scores: list[float] = []
        for technical_set in self._technical_sets:
            if not technical_set:
                scores.append(0.0)
                continue
            overlap = query_technical & technical_set
            if not overlap:
                scores.append(0.0)
                continue
            scores.append(len(overlap) / math.sqrt(len(query_technical) * len(technical_set)))
        return scores


class _DenseBackend:
    name = "hash-embedding-v1"
    real_dense = False

    def encode_passages(
        self,
        texts: list[str],
        fallback_embeddings: list[list[float]],
    ) -> object:
        if len(fallback_embeddings) == len(texts):
            return fallback_embeddings
        return [embed_text(text) for text in texts]

    def score_query(
        self,
        query_text: str,
        dense_passages: object,
        fallback_embeddings: list[list[float]],
    ) -> list[float]:
        query_embedding = embed_text(query_text)
        passage_embeddings = dense_passages if isinstance(dense_passages, list) else fallback_embeddings
        return [cosine_similarity(query_embedding, embedding) for embedding in passage_embeddings]


class _SentenceTransformerBackend(_DenseBackend):
    real_dense = True

    def __init__(self, model: object, model_name: str, batch_size: int) -> None:
        self.model = model
        self.model_name = model_name
        self.batch_size = batch_size
        self.name = f"sentence-transformers:{model_name}"

    def encode_passages(
        self,
        texts: list[str],
        fallback_embeddings: list[list[float]],
    ) -> object:
        if np is None:
            return super().encode_passages(texts, fallback_embeddings)
        return self.model.encode(  # type: ignore[attr-defined]
            [_passage_text(text) for text in texts],
            batch_size=self.batch_size,
            show_progress_bar=False,
            normalize_embeddings=True,
        )

    def score_query(
        self,
        query_text: str,
        dense_passages: object,
        fallback_embeddings: list[list[float]],
    ) -> list[float]:
        if np is None:
            return super().score_query(query_text, dense_passages, fallback_embeddings)
        query_embedding = self.model.encode(  # type: ignore[attr-defined]
            [_query_text(query_text)],
            batch_size=1,
            show_progress_bar=False,
            normalize_embeddings=True,
        )
        passage_matrix = dense_passages
        if not hasattr(passage_matrix, "dot"):
            return super().score_query(query_text, dense_passages, fallback_embeddings)
        scores = passage_matrix.dot(query_embedding[0])
        return [float(score) for score in scores]


_MODEL_LOCK = threading.Lock()
_MODEL_BACKEND: _DenseBackend | None = None
_RETRIEVER_LOCK = threading.Lock()
_RETRIEVER_CACHE: dict[tuple[str, tuple[str, ...], str], LocalTextRetriever] = {}
_WARNED_BACKEND_FALLBACK = False


def get_local_text_retriever(
    *,
    namespace: str,
    item_ids: Iterable[str],
    texts: Iterable[str],
    fallback_embeddings: Iterable[list[float]] | None = None,
) -> LocalTextRetriever:
    ids = [str(item_id) for item_id in item_ids]
    text_values = [str(text) for text in texts]
    embedding_values = list(fallback_embeddings or [])
    signature = _config_signature()
    cache_key = (namespace, tuple(ids), signature)
    with _RETRIEVER_LOCK:
        cached = _RETRIEVER_CACHE.get(cache_key)
        if cached is not None:
            return cached
    retriever = LocalTextRetriever(
        item_ids=ids,
        texts=text_values,
        fallback_embeddings=embedding_values if len(embedding_values) == len(text_values) else None,
    )
    with _RETRIEVER_LOCK:
        _RETRIEVER_CACHE[cache_key] = retriever
    return retriever


def local_retriever_name() -> str:
    return _dense_backend().name


def _dense_backend() -> _DenseBackend:
    global _MODEL_BACKEND
    with _MODEL_LOCK:
        if _MODEL_BACKEND is not None:
            return _MODEL_BACKEND
        _MODEL_BACKEND = _load_dense_backend()
        return _MODEL_BACKEND


def _load_dense_backend() -> _DenseBackend:
    global _WARNED_BACKEND_FALLBACK
    enabled = _bool_env("QUERY_FORGE_LOCAL_REAL_EMBEDDINGS_ENABLED", True)
    if not enabled:
        return _DenseBackend()
    try:
        from sentence_transformers import SentenceTransformer  # type: ignore
    except Exception:
        if not _WARNED_BACKEND_FALLBACK:
            _WARNED_BACKEND_FALLBACK = True
            print(
                "[retrieval] sentence-transformers unavailable; "
                "using BM25 + hash embedding fallback."
            )
        return _DenseBackend()
    model_name = os.getenv("QUERY_FORGE_LOCAL_EMBEDDING_MODEL", DEFAULT_MODEL_NAME).strip() or DEFAULT_MODEL_NAME
    batch_size = max(1, _int_env("QUERY_FORGE_LOCAL_EMBEDDING_BATCH_SIZE", 32))
    try:
        model = SentenceTransformer(model_name, device=os.getenv("QUERY_FORGE_LOCAL_EMBEDDING_DEVICE", "cpu"))
        return _SentenceTransformerBackend(model, model_name, batch_size)
    except Exception as exception:
        if not _WARNED_BACKEND_FALLBACK:
            _WARNED_BACKEND_FALLBACK = True
            print(
                "[retrieval] failed to load sentence-transformers model "
                f"{model_name}: {exception}; using BM25 + hash embedding fallback."
            )
        return _DenseBackend()


def _technical_tokens(tokens: Iterable[str]) -> set[str]:
    return {
        token
        for token in tokens
        if any(char.isascii() and char.isalnum() for char in token)
    }


def _max_normalize(values: list[float]) -> list[float]:
    if not values:
        return []
    max_value = max(values)
    if max_value <= 0.0:
        return [0.0 for _ in values]
    return [max(0.0, value / max_value) for value in values]


def _normalize_dense(score: float) -> float:
    return max(0.0, min(1.0, (float(score) + 1.0) / 2.0))


def _weights(real_dense: bool) -> tuple[float, float, float]:
    if real_dense:
        dense_default, bm25_default, technical_default = 0.58, 0.34, 0.08
    else:
        dense_default, bm25_default, technical_default = 0.25, 0.65, 0.10
    dense = _float_env("QUERY_FORGE_LOCAL_DENSE_WEIGHT", dense_default)
    bm25 = _float_env("QUERY_FORGE_LOCAL_BM25_WEIGHT", bm25_default)
    technical = _float_env("QUERY_FORGE_LOCAL_TECHNICAL_WEIGHT", technical_default)
    total = dense + bm25 + technical
    if total <= 0.0:
        return dense_default, bm25_default, technical_default
    return dense / total, bm25 / total, technical / total


def _passage_text(text: str) -> str:
    model_name = os.getenv("QUERY_FORGE_LOCAL_EMBEDDING_MODEL", DEFAULT_MODEL_NAME)
    if "e5" in model_name.lower():
        return f"passage: {text}"
    return text


def _query_text(text: str) -> str:
    model_name = os.getenv("QUERY_FORGE_LOCAL_EMBEDDING_MODEL", DEFAULT_MODEL_NAME)
    if "e5" in model_name.lower():
        return f"query: {text}"
    return text


def _config_signature() -> str:
    keys = [
        "QUERY_FORGE_LOCAL_REAL_EMBEDDINGS_ENABLED",
        "QUERY_FORGE_LOCAL_EMBEDDING_MODEL",
        "QUERY_FORGE_LOCAL_EMBEDDING_BATCH_SIZE",
        "QUERY_FORGE_LOCAL_EMBEDDING_DEVICE",
        "QUERY_FORGE_LOCAL_DENSE_WEIGHT",
        "QUERY_FORGE_LOCAL_BM25_WEIGHT",
        "QUERY_FORGE_LOCAL_TECHNICAL_WEIGHT",
        "QUERY_FORGE_LOCAL_BM25_K1",
        "QUERY_FORGE_LOCAL_BM25_B",
    ]
    return "|".join(f"{key}={os.getenv(key, '')}" for key in keys)


def _bool_env(key: str, default: bool) -> bool:
    value = os.getenv(key)
    if value is None or not value.strip():
        return default
    return value.strip().lower() not in {"0", "false", "no", "off"}


def _float_env(key: str, default: float) -> float:
    raw = os.getenv(key)
    if raw is None or not raw.strip():
        return default
    try:
        return float(raw)
    except ValueError:
        return default


def _int_env(key: str, default: int) -> int:
    raw = os.getenv(key)
    if raw is None or not raw.strip():
        return default
    try:
        return int(raw)
    except ValueError:
        return default
