from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Any

import requests

try:
    from eval.runtime import RetrievalCandidate
except ModuleNotFoundError:  # pragma: no cover
    from pipeline.eval.runtime import RetrievalCandidate


JAVA_RETRIEVAL_ENDPOINT_PATH = "/api/rag/eval/retrieval"
JAVA_RETRIEVAL_SUPPORTED_FORCED_MODES = frozenset(
    {
        "raw_only",
        "selective_rewrite",
        "anchor_aware_rewrite",
        "strategy_router",
    }
)
JAVA_RETRIEVAL_AGENTIC_MODE = "agentic_multi_query"
JAVA_RETRIEVAL_BLOCKED_FORCED_MODES = frozenset({JAVA_RETRIEVAL_AGENTIC_MODE})
OFFICIAL_RETRIEVAL_EVAL_BACKEND = "java"
RETRIEVAL_EVAL_BACKEND_JAVA = "java"
RETRIEVAL_EVAL_BACKEND_LEGACY = "legacy"
RETRIEVAL_EVAL_BACKEND_KEYS = (
    "retrieval_eval_backend",
    "official_eval_backend",
    "eval_retrieval_backend",
)


class JavaRetrievalClientError(RuntimeError):
    def __init__(
        self,
        *,
        code: str,
        detail: str,
        status_code: int | None = None,
        problem_detail: dict[str, Any] | None = None,
    ) -> None:
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.detail = detail
        self.status_code = status_code
        self.problem_detail = problem_detail or {}


@dataclass(slots=True)
class JavaRetrievalEvalSettings:
    enabled: bool
    base_url: str
    timeout_seconds: float
    domain_id: str | None
    include_trace: bool
    include_scores: bool
    include_metadata: bool
    forced_mode: str | None = None


@dataclass(slots=True)
class JavaRetrievalEvalResult:
    domain_id: str | None
    query: str | None
    final_query: str | None
    forced_mode: str | None
    selected_mode: str | None
    retrieved_chunk_ids: list[str]
    retrieved_docs: list[dict[str, Any]]
    warnings: list[str]
    raw_response: dict[str, Any]

    def to_retrieval_candidates(self) -> list[RetrievalCandidate]:
        docs_by_chunk: dict[str, list[dict[str, Any]]] = {}
        first_doc_by_chunk: dict[str, dict[str, Any]] = {}
        for doc in self.retrieved_docs:
            chunk_id = str(doc.get("chunkId") or doc.get("chunk_id") or "").strip()
            if not chunk_id:
                continue
            docs_by_chunk.setdefault(chunk_id, []).append(doc)
            first_doc_by_chunk.setdefault(chunk_id, doc)

        candidates: list[RetrievalCandidate] = []
        for chunk_id in self.retrieved_chunk_ids:
            queued_docs = docs_by_chunk.get(chunk_id) or []
            doc = queued_docs.pop(0) if queued_docs else first_doc_by_chunk.get(chunk_id, {})
            candidates.append(
                RetrievalCandidate(
                    chunk_id=chunk_id,
                    document_id=str(doc.get("documentId") or doc.get("document_id") or ""),
                    score=_float_or_default(doc.get("score"), 0.0),
                    text=str(doc.get("contentPreview") or doc.get("text") or ""),
                )
            )
        return candidates


class JavaRetrievalEvalClient:
    def __init__(
        self,
        base_url: str,
        *,
        timeout_seconds: float = 10.0,
        session: Any | None = None,
    ) -> None:
        normalized_base_url = str(base_url or "").strip().rstrip("/")
        if not normalized_base_url:
            raise JavaRetrievalClientError(
                code="java_backend_base_url_required",
                detail="java_backend_base_url is required when Java retrieval eval is enabled",
            )
        self.base_url = normalized_base_url
        self.timeout_seconds = max(0.1, float(timeout_seconds))
        self.session = session or requests

    @property
    def endpoint_url(self) -> str:
        return f"{self.base_url}{JAVA_RETRIEVAL_ENDPOINT_PATH}"

    def retrieve(
        self,
        *,
        domain_id: str,
        query: str,
        forced_mode: str = "strategy_router",
        top_k: int | None = None,
        include_trace: bool | None = None,
        include_scores: bool | None = None,
        include_metadata: bool | None = None,
    ) -> JavaRetrievalEvalResult:
        payload = build_retrieval_eval_payload(
            domain_id=domain_id,
            query=query,
            forced_mode=forced_mode,
            top_k=top_k,
            include_trace=include_trace,
            include_scores=include_scores,
            include_metadata=include_metadata,
        )
        try:
            response = self.session.post(
                self.endpoint_url,
                json=payload,
                timeout=self.timeout_seconds,
            )
        except (requests.Timeout, requests.ConnectionError) as exc:
            raise JavaRetrievalClientError(
                code="java_backend_unavailable",
                detail=str(exc),
            ) from exc
        except requests.RequestException as exc:
            raise JavaRetrievalClientError(
                code="java_backend_request_failed",
                detail=str(exc),
            ) from exc

        if int(getattr(response, "status_code", 0) or 0) >= 400:
            _raise_problem_detail(response)

        try:
            body = response.json()
        except ValueError as exc:
            raise JavaRetrievalClientError(
                code="invalid_java_response",
                detail="Java retrieval eval response was not valid JSON",
                status_code=int(getattr(response, "status_code", 0) or 0) or None,
            ) from exc

        return parse_retrieval_eval_response(body)


def build_retrieval_eval_payload(
    *,
    domain_id: str,
    query: str,
    forced_mode: str = "strategy_router",
    top_k: int | None = None,
    include_trace: bool | None = None,
    include_scores: bool | None = None,
    include_metadata: bool | None = None,
) -> dict[str, Any]:
    normalized_domain_id = str(domain_id or "").strip()
    if not normalized_domain_id:
        raise JavaRetrievalClientError(
            code="domainId_required",
            detail="domainId is required for Java retrieval eval",
        )
    normalized_query = str(query or "").strip()
    if not normalized_query:
        raise JavaRetrievalClientError(
            code="query_required",
            detail="query is required for Java retrieval eval",
        )

    normalized_mode = normalize_forced_mode(forced_mode)
    if normalized_mode == JAVA_RETRIEVAL_AGENTIC_MODE:
        raise JavaRetrievalClientError(
            code="unsupported_agentic_eval",
            detail="agentic_multi_query is not supported by the Java retrieval eval client in Phase 9A",
        )
    if normalized_mode not in JAVA_RETRIEVAL_SUPPORTED_FORCED_MODES:
        raise JavaRetrievalClientError(
            code="unsupported_forced_mode",
            detail=f"forcedMode is not supported for Java retrieval eval: {normalized_mode}",
        )

    payload: dict[str, Any] = {
        "domainId": normalized_domain_id,
        "query": normalized_query,
        "forcedMode": normalized_mode,
        "persistPolicy": "NONE",
        "answerGeneration": False,
    }
    if top_k is not None:
        parsed_top_k = int(top_k)
        if parsed_top_k <= 0:
            raise JavaRetrievalClientError(
                code="invalid_top_k",
                detail="topK must be positive when provided",
            )
        payload["topK"] = parsed_top_k
    if include_trace is not None:
        payload["includeTrace"] = bool(include_trace)
    if include_scores is not None:
        payload["includeScores"] = bool(include_scores)
    if include_metadata is not None:
        payload["includeMetadata"] = bool(include_metadata)
    return payload


def parse_retrieval_eval_response(payload: Any) -> JavaRetrievalEvalResult:
    if not isinstance(payload, dict):
        raise JavaRetrievalClientError(
            code="invalid_java_response",
            detail="Java retrieval eval response must be a JSON object",
        )
    if "retrievedChunkIds" not in payload:
        raise JavaRetrievalClientError(
            code="missing_retrieved_chunk_ids",
            detail="Java retrieval eval response did not include retrievedChunkIds",
        )
    retrieved_chunk_ids_payload = payload.get("retrievedChunkIds")
    if not isinstance(retrieved_chunk_ids_payload, list):
        raise JavaRetrievalClientError(
            code="invalid_retrieved_chunk_ids",
            detail="Java retrieval eval response retrievedChunkIds must be a list",
        )
    retrieved_chunk_ids = [
        str(chunk_id).strip()
        for chunk_id in retrieved_chunk_ids_payload
        if str(chunk_id or "").strip()
    ]
    retrieved_docs_payload = payload.get("retrievedDocs")
    retrieved_docs = [
        dict(doc)
        for doc in retrieved_docs_payload
        if isinstance(doc, dict)
    ] if isinstance(retrieved_docs_payload, list) else []
    warnings_payload = payload.get("warnings")
    warnings = [
        str(warning)
        for warning in warnings_payload
        if str(warning).strip()
    ] if isinstance(warnings_payload, list) else []

    return JavaRetrievalEvalResult(
        domain_id=str(payload.get("domainId")) if payload.get("domainId") is not None else None,
        query=str(payload.get("query")) if payload.get("query") is not None else None,
        final_query=str(payload.get("finalQuery")) if payload.get("finalQuery") is not None else None,
        forced_mode=str(payload.get("forcedMode")) if payload.get("forcedMode") is not None else None,
        selected_mode=str(payload.get("selectedMode")) if payload.get("selectedMode") is not None else None,
        retrieved_chunk_ids=retrieved_chunk_ids,
        retrieved_docs=retrieved_docs,
        warnings=warnings,
        raw_response=dict(payload),
    )


def normalize_forced_mode(value: Any) -> str:
    return str(value or "strategy_router").strip().lower().replace("-", "_") or "strategy_router"


def normalize_retrieval_eval_backend(value: Any) -> str:
    normalized = str(value or "").strip().lower().replace("-", "_")
    if normalized in {"java", "java_backend", "java_retrieval", "java_retrieval_eval"}:
        return RETRIEVAL_EVAL_BACKEND_JAVA
    if normalized in {"legacy", "python", "python_legacy", "python_legacy_eval"}:
        return RETRIEVAL_EVAL_BACKEND_LEGACY
    raise JavaRetrievalClientError(
        code="unsupported_retrieval_eval_backend",
        detail=(
            "retrieval eval backend must be one of "
            f"{RETRIEVAL_EVAL_BACKEND_JAVA}, {RETRIEVAL_EVAL_BACKEND_LEGACY}; "
            f"got {normalized or '<empty>'}"
        ),
    )


def retrieval_eval_backend_policy(raw_config: dict[str, Any] | None) -> str:
    source = raw_config or {}
    for key in RETRIEVAL_EVAL_BACKEND_KEYS:
        if key not in source:
            continue
        value = source.get(key)
        if value is None or not str(value).strip():
            continue
        return normalize_retrieval_eval_backend(value)
    if _legacy_java_backend_opt_in_enabled(source):
        return RETRIEVAL_EVAL_BACKEND_JAVA
    return RETRIEVAL_EVAL_BACKEND_LEGACY


def java_backend_enabled(raw_config: dict[str, Any] | None) -> bool:
    return retrieval_eval_backend_policy(raw_config) == RETRIEVAL_EVAL_BACKEND_JAVA


def _legacy_java_backend_opt_in_enabled(source: dict[str, Any]) -> bool:
    return _bool_from_config(
        source,
        keys=(
            "use_java_backend",
            "java_backend_enabled",
            "retrieval_eval_java_backend_enabled",
            "java_retrieval_eval_enabled",
        ),
        default=False,
    )


def java_retrieval_settings_from_config(raw_config: dict[str, Any] | None) -> JavaRetrievalEvalSettings | None:
    source = raw_config or {}
    if not java_backend_enabled(source):
        return None
    base_url = _str_from_config(
        source,
        keys=("java_backend_base_url", "java_eval_base_url", "java_retrieval_eval_base_url"),
        default=os.getenv("QUERY_FORGE_JAVA_BACKEND_BASE_URL") or "http://localhost:8080",
    )
    domain_id = _str_from_config(
        source,
        keys=("java_backend_domain_id", "java_domain_id", "domain_id", "domainId"),
        default=os.getenv("QUERY_FORGE_JAVA_EVAL_DOMAIN_ID") or "",
    )
    forced_mode = _str_from_config(
        source,
        keys=("java_forced_mode", "java_retrieval_forced_mode"),
        default="",
    )
    return JavaRetrievalEvalSettings(
        enabled=True,
        base_url=base_url,
        timeout_seconds=_float_from_config(
            source,
            keys=("java_backend_timeout_seconds", "java_eval_timeout_seconds"),
            default=10.0,
        ),
        domain_id=domain_id or None,
        include_trace=_bool_from_config(
            source,
            keys=("java_include_trace", "java_backend_include_trace"),
            default=False,
        ),
        include_scores=_bool_from_config(
            source,
            keys=("java_include_scores", "java_backend_include_scores"),
            default=True,
        ),
        include_metadata=_bool_from_config(
            source,
            keys=("java_include_metadata", "java_backend_include_metadata"),
            default=False,
        ),
        forced_mode=normalize_forced_mode(forced_mode) if forced_mode else None,
    )


def build_java_retrieval_client_from_config(
    raw_config: dict[str, Any] | None,
    *,
    session: Any | None = None,
) -> JavaRetrievalEvalClient | None:
    settings = java_retrieval_settings_from_config(raw_config)
    if settings is None:
        return None
    return JavaRetrievalEvalClient(
        settings.base_url,
        timeout_seconds=settings.timeout_seconds,
        session=session,
    )


def _raise_problem_detail(response: Any) -> None:
    status_code = int(getattr(response, "status_code", 0) or 0) or None
    try:
        body = response.json()
    except ValueError:
        body = None
    if isinstance(body, dict):
        code = str(body.get("code") or f"java_backend_http_{status_code}")
        detail = str(body.get("detail") or body.get("title") or getattr(response, "text", "") or "Java backend error")
        raise JavaRetrievalClientError(
            code=code,
            detail=detail,
            status_code=status_code,
            problem_detail=body,
        )
    raise JavaRetrievalClientError(
        code=f"java_backend_http_{status_code}",
        detail=str(getattr(response, "text", "") or "Java backend error"),
        status_code=status_code,
    )


def _bool_from_config(source: dict[str, Any], *, keys: tuple[str, ...], default: bool) -> bool:
    for key in keys:
        if key not in source:
            continue
        value = source.get(key)
        if isinstance(value, bool):
            return value
        normalized = str(value or "").strip().lower()
        if normalized in {"true", "1", "yes", "y", "on"}:
            return True
        if normalized in {"false", "0", "no", "n", "off"}:
            return False
    return default


def _float_from_config(source: dict[str, Any], *, keys: tuple[str, ...], default: float) -> float:
    for key in keys:
        if key not in source:
            continue
        try:
            return max(0.1, float(source.get(key)))
        except (TypeError, ValueError):
            return default
    return default


def _str_from_config(source: dict[str, Any], *, keys: tuple[str, ...], default: str) -> str:
    for key in keys:
        value = source.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    return str(default or "").strip()


def _float_or_default(value: Any, default: float) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default
