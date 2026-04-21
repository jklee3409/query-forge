from __future__ import annotations

import math
import os
import threading
from collections import Counter, defaultdict
from dataclasses import dataclass
import hashlib
import json
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
RETRIEVAL_MODE_BM25_ONLY = "bm25_only"
RETRIEVAL_MODE_DENSE_ONLY = "dense_only"
RETRIEVAL_MODE_HYBRID = "hybrid"
DEFAULT_CANDIDATE_POOL_K = 50
DEFAULT_DENSE_WEIGHT = 0.58
DEFAULT_BM25_WEIGHT = 0.34
DEFAULT_TECHNICAL_WEIGHT = 0.08
VALID_RETRIEVAL_MODES = {
    RETRIEVAL_MODE_BM25_ONLY,
    RETRIEVAL_MODE_DENSE_ONLY,
    RETRIEVAL_MODE_HYBRID,
}


@dataclass(frozen=True, slots=True)
class RetrieverConfig:
    mode: str = RETRIEVAL_MODE_HYBRID
    dense_embedding_model: str = DEFAULT_MODEL_NAME
    dense_embedding_required: bool = True
    dense_fallback_enabled: bool = False
    dense_embedding_device: str = "cpu"
    dense_embedding_batch_size: int = 32
    dense_weight: float = DEFAULT_DENSE_WEIGHT
    bm25_weight: float = DEFAULT_BM25_WEIGHT
    technical_weight: float = DEFAULT_TECHNICAL_WEIGHT
    bm25_k1: float = 1.2
    bm25_b: float = 0.75
    candidate_pool_k: int = DEFAULT_CANDIDATE_POOL_K
    rerank_enabled: bool = True

    @property
    def requires_dense(self) -> bool:
        return self.mode in {RETRIEVAL_MODE_DENSE_ONLY, RETRIEVAL_MODE_HYBRID}

    def fusion_weights(self) -> tuple[float, float, float]:
        return self.dense_weight, self.bm25_weight, self.technical_weight

    def to_metadata(self) -> dict[str, object]:
        return {
            "retriever_mode": self.mode,
            "dense_embedding_model": self.dense_embedding_model,
            "dense_embedding_required": self.dense_embedding_required,
            "dense_fallback_enabled": self.dense_fallback_enabled,
            "dense_embedding_device": self.dense_embedding_device,
            "dense_embedding_batch_size": self.dense_embedding_batch_size,
            "retriever_fusion_weights": {
                "dense": self.dense_weight,
                "bm25": self.bm25_weight,
                "technical": self.technical_weight,
            },
            "bm25_k1": self.bm25_k1,
            "bm25_b": self.bm25_b,
            "retriever_candidate_pool_k": self.candidate_pool_k,
            "rerank_enabled": self.rerank_enabled,
        }

    def cache_signature(self) -> str:
        return json.dumps(self.to_metadata(), ensure_ascii=False, sort_keys=True)


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
        retriever_config: RetrieverConfig | None = None,
    ) -> None:
        self.config = retriever_config or build_retriever_config({})
        self.item_ids = item_ids
        self.texts = texts
        self.fallback_embeddings = fallback_embeddings or []
        self._tokens = [tokenize(text) for text in texts]
        self._technical_sets = [_technical_tokens(tokens) for tokens in self._tokens]
        self._build_bm25_index()
        self._dense_backend = _dense_backend(self.config) if self.config.requires_dense else None
        self.dense_backend_name = self._dense_backend.name if self._dense_backend is not None else "none"
        self._dense_passages = (
            self._dense_backend.encode_passages(texts, self.fallback_embeddings)
            if self._dense_backend is not None
            else []
        )

    @property
    def retriever_name(self) -> str:
        return local_retriever_name(self.config, dense_backend_name=self.dense_backend_name)

    def rank(self, query_text: str, *, top_k: int) -> list[RankedText]:
        if not self.texts or top_k <= 0:
            return []
        query_tokens = tokenize(query_text)
        bm25_scores = self._bm25_scores(query_tokens)
        dense_scores = (
            self._dense_backend.score_query(
                query_text,
                self._dense_passages,
                self.fallback_embeddings,
            )
            if self._dense_backend is not None
            else [0.0] * len(self.texts)
        )
        technical_scores = self._technical_scores(query_tokens)
        bm25_norm = _max_normalize(bm25_scores)
        dense_norm = [_normalize_dense(score) for score in dense_scores]
        dense_weight, bm25_weight, technical_weight = self.config.fusion_weights()

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
        k1 = self.config.bm25_k1
        b = self.config.bm25_b
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
            [_passage_text(text, self.model_name) for text in texts],
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
            [_query_text(query_text, self.model_name)],
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
_MODEL_BACKENDS: dict[str, _DenseBackend] = {}
_RETRIEVER_LOCK = threading.Lock()
_RETRIEVER_CACHE: dict[tuple[str, tuple[str, ...], str, str], LocalTextRetriever] = {}
_WARNED_BACKEND_FALLBACK = False


def get_local_text_retriever(
    *,
    namespace: str,
    item_ids: Iterable[str],
    texts: Iterable[str],
    fallback_embeddings: Iterable[list[float]] | None = None,
    retriever_config: RetrieverConfig | None = None,
) -> LocalTextRetriever:
    config = retriever_config or build_retriever_config({})
    ids = [str(item_id) for item_id in item_ids]
    text_values = [str(text) for text in texts]
    embedding_values = list(fallback_embeddings or [])
    cache_key = (namespace, tuple(ids), _texts_signature(text_values), config.cache_signature())
    with _RETRIEVER_LOCK:
        cached = _RETRIEVER_CACHE.get(cache_key)
        if cached is not None:
            return cached
    retriever = LocalTextRetriever(
        item_ids=ids,
        texts=text_values,
        fallback_embeddings=embedding_values if len(embedding_values) == len(text_values) else None,
        retriever_config=config,
    )
    with _RETRIEVER_LOCK:
        _RETRIEVER_CACHE[cache_key] = retriever
    return retriever


def local_retriever_name(
    retriever_config: RetrieverConfig | None = None,
    *,
    dense_backend_name: str | None = None,
) -> str:
    config = retriever_config or build_retriever_config({})
    if config.mode == RETRIEVAL_MODE_BM25_ONLY:
        return f"local:{config.mode}"
    backend_name = dense_backend_name or _dense_backend(config).name
    return f"local:{config.mode}:{backend_name}"


def _dense_backend(config: RetrieverConfig) -> _DenseBackend:
    signature = _dense_backend_signature(config)
    with _MODEL_LOCK:
        cached = _MODEL_BACKENDS.get(signature)
        if cached is not None:
            return cached
        backend = _load_dense_backend(config)
        _MODEL_BACKENDS[signature] = backend
        return backend


def _load_dense_backend(config: RetrieverConfig) -> _DenseBackend:
    global _WARNED_BACKEND_FALLBACK
    enabled = _bool_env("QUERY_FORGE_LOCAL_REAL_EMBEDDINGS_ENABLED", True)
    if not enabled:
        if config.dense_embedding_required and not config.dense_fallback_enabled:
            raise RuntimeError(
                "real dense embeddings are disabled but retriever mode requires dense embeddings. "
                "Set retriever_mode=bm25_only or enable dense_fallback_enabled explicitly."
            )
        return _DenseBackend()
    try:
        from sentence_transformers import SentenceTransformer  # type: ignore
    except Exception as exception:
        if config.dense_embedding_required and not config.dense_fallback_enabled:
            raise RuntimeError(
                "sentence-transformers is required for dense retrieval but is unavailable. "
                "Install sentence-transformers/torch or set retriever_mode=bm25_only."
            ) from exception
        if not _WARNED_BACKEND_FALLBACK:
            _WARNED_BACKEND_FALLBACK = True
            print(
                "[retrieval] sentence-transformers unavailable; "
                "using BM25 + hash embedding fallback."
            )
        return _DenseBackend()
    if np is None:
        if config.dense_embedding_required and not config.dense_fallback_enabled:
            raise RuntimeError("numpy is required for sentence-transformers dense retrieval.")
        return _DenseBackend()
    model_name = config.dense_embedding_model
    batch_size = config.dense_embedding_batch_size
    try:
        model = SentenceTransformer(model_name, device=config.dense_embedding_device)
        return _SentenceTransformerBackend(model, model_name, batch_size)
    except Exception as exception:
        if config.dense_embedding_required and not config.dense_fallback_enabled:
            raise RuntimeError(
                f"failed to load dense embedding model {model_name!r}. "
                "Use retriever_mode=bm25_only or enable dense_fallback_enabled explicitly for hash fallback."
            ) from exception
        if not _WARNED_BACKEND_FALLBACK:
            _WARNED_BACKEND_FALLBACK = True
            print(
                "[retrieval] failed to load sentence-transformers model "
                f"{model_name}: {exception}; using BM25 + hash embedding fallback."
            )
        return _DenseBackend()


def build_retriever_config(raw_config: dict[str, object] | None = None) -> RetrieverConfig:
    raw = raw_config or {}
    section_value = raw.get("retriever_config") if isinstance(raw, dict) else None
    section = section_value if isinstance(section_value, dict) else {}

    def lookup(*keys: str, default: object = None) -> object:
        for container in (section, raw):
            if not isinstance(container, dict):
                continue
            for key in keys:
                if key in container and container[key] is not None:
                    return container[key]
        return default

    mode = _normalize_retrieval_mode(
        str(
            lookup(
                "retriever_mode",
                "mode",
                default=os.getenv("QUERY_FORGE_RETRIEVER_MODE") or RETRIEVAL_MODE_HYBRID,
            )
        )
    )
    dense_required_default = mode in {RETRIEVAL_MODE_DENSE_ONLY, RETRIEVAL_MODE_HYBRID}
    dense_required = _bool_value(
        lookup("dense_embedding_required", default=os.getenv("QUERY_FORGE_LOCAL_DENSE_REQUIRED")),
        dense_required_default,
    )
    if mode == RETRIEVAL_MODE_BM25_ONLY:
        dense_required = False
    fallback_enabled = _bool_value(
        lookup("dense_fallback_enabled", default=os.getenv("QUERY_FORGE_LOCAL_DENSE_FALLBACK_ENABLED")),
        False,
    )

    fusion_value = lookup("retriever_fusion_weights", "fusion_weights", default={})
    fusion_weights = fusion_value if isinstance(fusion_value, dict) else {}
    dense_weight_raw = _first_present(
        fusion_weights.get("dense"),
        fusion_weights.get("dense_weight"),
        lookup("dense_weight", default=os.getenv("QUERY_FORGE_LOCAL_DENSE_WEIGHT")),
    )
    bm25_weight_raw = _first_present(
        fusion_weights.get("bm25"),
        fusion_weights.get("bm25_weight"),
        lookup("bm25_weight", default=os.getenv("QUERY_FORGE_LOCAL_BM25_WEIGHT")),
    )
    technical_weight_raw = _first_present(
        fusion_weights.get("technical"),
        fusion_weights.get("technical_weight"),
        lookup("technical_weight", default=os.getenv("QUERY_FORGE_LOCAL_TECHNICAL_WEIGHT")),
    )
    dense_weight, bm25_weight, technical_weight = _normalize_weights(
        mode=mode,
        dense=_float_value(dense_weight_raw, DEFAULT_DENSE_WEIGHT),
        bm25=_float_value(bm25_weight_raw, DEFAULT_BM25_WEIGHT),
        technical=_float_value(technical_weight_raw, DEFAULT_TECHNICAL_WEIGHT),
    )

    candidate_pool_default = max(1, _int_env("QUERY_FORGE_RERANK_CANDIDATE_K", DEFAULT_CANDIDATE_POOL_K))
    candidate_pool_k = _clamp_int(
        _int_value(
            lookup("retriever_candidate_pool_k", "candidate_pool_k", default=candidate_pool_default),
            candidate_pool_default,
        ),
        1,
        1000,
    )

    return RetrieverConfig(
        mode=mode,
        dense_embedding_model=str(
            lookup(
                "dense_embedding_model",
                default=os.getenv("QUERY_FORGE_LOCAL_EMBEDDING_MODEL") or DEFAULT_MODEL_NAME,
            )
        ).strip()
        or DEFAULT_MODEL_NAME,
        dense_embedding_required=dense_required,
        dense_fallback_enabled=fallback_enabled,
        dense_embedding_device=str(
            lookup(
                "dense_embedding_device",
                default=os.getenv("QUERY_FORGE_LOCAL_EMBEDDING_DEVICE") or "cpu",
            )
        ).strip()
        or "cpu",
        dense_embedding_batch_size=_clamp_int(
            _int_value(
                lookup(
                    "dense_embedding_batch_size",
                    default=os.getenv("QUERY_FORGE_LOCAL_EMBEDDING_BATCH_SIZE") or 32,
                ),
                32,
            ),
            1,
            512,
        ),
        dense_weight=dense_weight,
        bm25_weight=bm25_weight,
        technical_weight=technical_weight,
        bm25_k1=max(0.01, _float_value(lookup("bm25_k1", default=os.getenv("QUERY_FORGE_LOCAL_BM25_K1")), 1.2)),
        bm25_b=max(0.0, min(1.0, _float_value(lookup("bm25_b", default=os.getenv("QUERY_FORGE_LOCAL_BM25_B")), 0.75))),
        candidate_pool_k=candidate_pool_k,
        rerank_enabled=_bool_value(
            lookup("rerank_enabled", default=os.getenv("QUERY_FORGE_RERANK_ENABLED")),
            True,
        ),
    )


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


def _passage_text(text: str, model_name: str = DEFAULT_MODEL_NAME) -> str:
    if "e5" in model_name.lower():
        return f"passage: {text}"
    return text


def _query_text(text: str, model_name: str = DEFAULT_MODEL_NAME) -> str:
    if "e5" in model_name.lower():
        return f"query: {text}"
    return text


def _dense_backend_signature(config: RetrieverConfig) -> str:
    return "|".join(
        [
            config.dense_embedding_model,
            config.dense_embedding_device,
            str(config.dense_embedding_batch_size),
            str(config.dense_embedding_required),
            str(config.dense_fallback_enabled),
            str(_bool_env("QUERY_FORGE_LOCAL_REAL_EMBEDDINGS_ENABLED", True)),
        ]
    )


def _texts_signature(texts: list[str]) -> str:
    digest = hashlib.sha1()
    for text in texts:
        digest.update(text.encode("utf-8", errors="replace"))
        digest.update(b"\0")
    return digest.hexdigest()


def _normalize_retrieval_mode(value: str) -> str:
    normalized = (value or "").strip().lower().replace("-", "_")
    aliases = {
        "bm25": RETRIEVAL_MODE_BM25_ONLY,
        "bm25_only": RETRIEVAL_MODE_BM25_ONLY,
        "dense": RETRIEVAL_MODE_DENSE_ONLY,
        "dense_only": RETRIEVAL_MODE_DENSE_ONLY,
        "hybrid": RETRIEVAL_MODE_HYBRID,
    }
    mode = aliases.get(normalized, normalized)
    if mode not in VALID_RETRIEVAL_MODES:
        raise ValueError(f"unsupported retriever_mode: {value}")
    return mode


def _normalize_weights(
    *,
    mode: str,
    dense: float,
    bm25: float,
    technical: float,
) -> tuple[float, float, float]:
    if mode == RETRIEVAL_MODE_BM25_ONLY:
        return 0.0, 1.0, 0.0
    if mode == RETRIEVAL_MODE_DENSE_ONLY:
        return 1.0, 0.0, 0.0
    values = [max(0.0, dense), max(0.0, bm25), max(0.0, technical)]
    total = sum(values)
    if total <= 0.0:
        return DEFAULT_DENSE_WEIGHT, DEFAULT_BM25_WEIGHT, DEFAULT_TECHNICAL_WEIGHT
    return values[0] / total, values[1] / total, values[2] / total


def _first_present(*values: object) -> object:
    for value in values:
        if value is not None:
            return value
    return None


def _bool_value(value: object, default: bool) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    text = str(value).strip().lower()
    if not text:
        return default
    return text not in {"0", "false", "no", "off"}


def _float_value(value: object, default: float) -> float:
    if value is None:
        return default
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def _int_value(value: object, default: int) -> int:
    if value is None:
        return default
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _clamp_int(value: int, minimum: int, maximum: int) -> int:
    return max(minimum, min(maximum, value))


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
