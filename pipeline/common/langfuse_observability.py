from __future__ import annotations

import hashlib
import json
import logging
import os
import threading
import time
from collections import deque
from contextlib import contextmanager
from dataclasses import dataclass
from typing import Any

try:
    from langfuse import get_client
except Exception:  # pragma: no cover - optional dependency
    get_client = None


LOGGER = logging.getLogger(__name__)

EVENT_SCHEMA_VERSION = "qf.langfuse.llm.v1"
TRACE_NAME = "query-forge.llm"
DEFAULT_SUCCESS_SAMPLE_BY_PURPOSE: dict[str, float] = {
    "generate_query": 0.01,
    "summary_extraction_en": 0.02,
    "summary_extraction_ko": 0.03,
    "translate_chunk_en_to_ko": 0.03,
    "quality_gating_self_eval": 0.15,
    "selective_rewrite": 0.30,
    "generate_query_retry": 1.00,
}
SCORE_MODE_VALUES = {"off", "errors", "sampled_all"}


def _read_bool(name: str, default: bool) -> bool:
    raw = str(os.getenv(name) or "").strip().lower()
    if not raw:
        return default
    return raw in {"1", "true", "t", "yes", "y", "on"}


def _read_float(name: str, default: float) -> float:
    raw = str(os.getenv(name) or "").strip()
    if not raw:
        return default
    try:
        parsed = float(raw)
    except ValueError:
        return default
    return max(0.0, min(parsed, 1.0))


def _read_int(name: str, default: int, *, min_value: int = 0, max_value: int = 10_000_000) -> int:
    raw = str(os.getenv(name) or "").strip()
    if not raw:
        return default
    try:
        parsed = int(raw)
    except ValueError:
        return default
    return max(min_value, min(parsed, max_value))


def _parse_csv(raw_value: str | None) -> set[str]:
    if not raw_value:
        return set()
    return {item.strip() for item in str(raw_value).split(",") if item.strip()}


def _parse_rate_map(raw_value: str | None) -> dict[str, float]:
    if not raw_value:
        return {}
    parsed: dict[str, float] = {}
    for token in str(raw_value).split(","):
        item = token.strip()
        if not item or "=" not in item:
            continue
        key, value = item.split("=", 1)
        key = key.strip()
        if not key:
            continue
        try:
            parsed_value = float(value.strip())
        except ValueError:
            continue
        parsed[key] = max(0.0, min(parsed_value, 1.0))
    return parsed


def _truncate(value: str, *, limit: int) -> str:
    if limit <= 0:
        return ""
    if len(value) <= limit:
        return value
    return value[: limit - 3] + "..."


def _short_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]


def _safe_json_preview(payload: Any, *, max_chars: int) -> str:
    try:
        serialized = json.dumps(payload, ensure_ascii=False, sort_keys=True)
    except Exception:
        serialized = str(payload)
    return _truncate(serialized, limit=max_chars)


def _purpose_stage(request_purpose: str) -> str:
    purpose = (request_purpose or "").strip().lower()
    if purpose.startswith(("generate_query", "summary_extraction_", "translate_chunk_")):
        return "generate-queries"
    if purpose.startswith("quality_gating_"):
        return "gate-queries"
    if "rewrite" in purpose:
        return "eval-retrieval/eval-answer"
    if purpose.startswith("stability_eval_") or purpose.startswith("stability_mock_"):
        return "llm-stability-runner"
    return "unknown"


def _deterministic_sample(*, seed: str, rate: float) -> bool:
    if rate >= 1.0:
        return True
    if rate <= 0.0:
        return False
    digest = hashlib.sha256(seed.encode("utf-8")).hexdigest()[:8]
    ratio = int(digest, 16) / 0xFFFFFFFF
    return ratio < rate


@dataclass(slots=True)
class LlmTraceRecord:
    request_purpose: str
    trace_id: str | None
    provider: str
    provider_type: str
    model: str
    fallback_used: bool
    structured_output_used: bool
    retry_count: int
    attempts_used: int
    prompt_fingerprint: str
    estimated_tokens: int
    response_schema_hash: str | None
    response_schema_present: bool
    system_prompt: str
    user_prompt: str


class LangfuseLlmObserver:
    def __init__(self) -> None:
        self.enabled = _read_bool("QUERY_FORGE_LANGFUSE_ENABLED", False)
        self.success_default_rate = _read_float("QUERY_FORGE_LANGFUSE_SUCCESS_SAMPLE_RATE", 0.03)
        self.error_default_rate = _read_float("QUERY_FORGE_LANGFUSE_ERROR_SAMPLE_RATE", 1.0)
        self.success_rate_by_purpose = dict(DEFAULT_SUCCESS_SAMPLE_BY_PURPOSE)
        self.success_rate_by_purpose.update(
            _parse_rate_map(os.getenv("QUERY_FORGE_LANGFUSE_SUCCESS_SAMPLE_BY_PURPOSE"))
        )
        self.error_rate_by_purpose = _parse_rate_map(
            os.getenv("QUERY_FORGE_LANGFUSE_ERROR_SAMPLE_BY_PURPOSE")
        )
        self.purpose_allowlist = _parse_csv(os.getenv("QUERY_FORGE_LANGFUSE_PURPOSE_ALLOWLIST"))
        self.capture_system_prompt = _read_bool("QUERY_FORGE_LANGFUSE_CAPTURE_SYSTEM_PROMPT", False)
        self.capture_user_prompt = _read_bool("QUERY_FORGE_LANGFUSE_CAPTURE_USER_PROMPT", True)
        self.max_prompt_chars = _read_int("QUERY_FORGE_LANGFUSE_MAX_PROMPT_CHARS", 1200, min_value=80, max_value=10000)
        self.max_output_chars = _read_int("QUERY_FORGE_LANGFUSE_MAX_OUTPUT_CHARS", 1200, min_value=80, max_value=20000)
        self.max_events_per_minute = _read_int("QUERY_FORGE_LANGFUSE_MAX_EVENTS_PER_MINUTE", 120, min_value=1, max_value=20000)
        self.max_events_per_day = _read_int("QUERY_FORGE_LANGFUSE_MAX_EVENTS_PER_DAY", 30000, min_value=10, max_value=500000)
        self.pipeline_user_id = str(
            os.getenv("QUERY_FORGE_LANGFUSE_USER_ID") or "query-forge-pipeline"
        ).strip()
        self.score_mode = str(os.getenv("QUERY_FORGE_LANGFUSE_SCORE_MODE") or "errors").strip().lower()
        if self.score_mode not in SCORE_MODE_VALUES:
            self.score_mode = "errors"
        self._events_minute: deque[float] = deque()
        self._events_day: deque[float] = deque()
        self._quota_lock = threading.Lock()
        self._client = None
        self._warned_init_failure = False

        if os.getenv("LANGFUSE_HOST") and not os.getenv("LANGFUSE_BASE_URL"):
            os.environ["LANGFUSE_BASE_URL"] = str(os.getenv("LANGFUSE_HOST") or "").strip()

        if not self.enabled:
            return
        if get_client is None:
            LOGGER.warning(
                "langfuse_disabled reason=missing_dependency package=langfuse "
                "hint='pip install langfuse'"
            )
            self.enabled = False
            return
        try:
            self._client = get_client()
        except Exception:  # noqa: BLE001
            self.enabled = False
            self._warned_init_failure = True
            LOGGER.exception("langfuse_disabled reason=client_init_failed")

    def log_success(
        self,
        *,
        record: LlmTraceRecord,
        response_payload: dict[str, Any],
        usage_details: dict[str, int],
        latency_ms: int,
    ) -> None:
        self._log(
            status="success",
            record=record,
            response_payload=response_payload,
            usage_details=usage_details,
            latency_ms=latency_ms,
            http_status=None,
            error_type=None,
            error_message=None,
        )

    def log_failure(
        self,
        *,
        record: LlmTraceRecord,
        latency_ms: int,
        http_status: int | None,
        error_type: str,
        error_message: str,
    ) -> None:
        self._log(
            status="error",
            record=record,
            response_payload={},
            usage_details={},
            latency_ms=latency_ms,
            http_status=http_status,
            error_type=error_type,
            error_message=error_message,
        )

    def _log(
        self,
        *,
        status: str,
        record: LlmTraceRecord,
        response_payload: dict[str, Any],
        usage_details: dict[str, int],
        latency_ms: int,
        http_status: int | None,
        error_type: str | None,
        error_message: str | None,
    ) -> None:
        if not self.enabled or self._client is None:
            return
        if self.purpose_allowlist and record.request_purpose not in self.purpose_allowlist:
            return
        rate = self._effective_rate(record.request_purpose, status=status)
        sample_key = "|".join(
            [
                status,
                record.request_purpose,
                record.trace_id or record.prompt_fingerprint,
                record.provider,
                record.model,
                record.prompt_fingerprint,
            ]
        )
        if not _deterministic_sample(seed=sample_key, rate=rate):
            return
        if not self._take_quota_slot():
            return

        metadata = self._build_metadata(
            record=record,
            status=status,
            latency_ms=latency_ms,
            usage_details=usage_details,
            http_status=http_status,
            error_type=error_type,
            error_message=error_message,
            sample_rate=rate,
        )
        tags = self._build_tags(record=record, status=status)
        request_input = self._build_input(record=record)
        request_output = self._build_output(
            status=status,
            response_payload=response_payload,
            error_type=error_type,
            error_message=error_message,
            usage_details=usage_details,
        )

        observation = None
        trace_id = None
        observation_id = None
        try:
            with self._propagate(tags=tags, metadata=self._trace_metadata(record=record, status=status)):
                with self._client.start_as_current_observation(
                    name=f"llm.{record.request_purpose}",
                    as_type="generation",
                    model=record.model,
                    input=request_input,
                    output=request_output,
                    metadata=metadata,
                    usage_details=usage_details or None,
                ) as observation:
                    trace_id = getattr(observation, "trace_id", None)
                    observation_id = getattr(observation, "id", None)
        except Exception:  # noqa: BLE001
            LOGGER.exception(
                "langfuse_log_failed purpose=%s trace_id=%s status=%s",
                record.request_purpose,
                record.trace_id,
                status,
            )
            return

        self._create_scores(
            status=status,
            record=record,
            trace_id=trace_id,
            observation_id=observation_id,
            latency_ms=latency_ms,
            http_status=http_status,
        )

    def _effective_rate(self, request_purpose: str, *, status: str) -> float:
        purpose = request_purpose or "unspecified"
        if status == "error":
            return self.error_rate_by_purpose.get(purpose, self.error_default_rate)
        return self.success_rate_by_purpose.get(purpose, self.success_default_rate)

    def _build_input(self, *, record: LlmTraceRecord) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "prompt_fingerprint": record.prompt_fingerprint,
            "system_prompt_chars": len(record.system_prompt),
            "user_prompt_chars": len(record.user_prompt),
            "estimated_tokens": record.estimated_tokens,
            "response_schema_present": record.response_schema_present,
            "response_schema_hash": record.response_schema_hash,
        }
        if self.capture_system_prompt:
            payload["system_prompt_preview"] = _truncate(record.system_prompt, limit=self.max_prompt_chars)
        if self.capture_user_prompt:
            payload["user_prompt_preview"] = _truncate(record.user_prompt, limit=self.max_prompt_chars)
        return payload

    def _build_output(
        self,
        *,
        status: str,
        response_payload: dict[str, Any],
        error_type: str | None,
        error_message: str | None,
        usage_details: dict[str, int],
    ) -> dict[str, Any]:
        if status == "error":
            return {
                "status": "error",
                "error_type": error_type,
                "error_message": _truncate(error_message or "", limit=self.max_output_chars),
                "usage_details": usage_details,
            }
        response_copy = dict(response_payload)
        response_copy.pop("_llm_meta", None)
        return {
            "status": "success",
            "response_keys": sorted(response_copy.keys())[:32],
            "response_preview": _safe_json_preview(response_copy, max_chars=self.max_output_chars),
            "usage_details": usage_details,
        }

    def _build_tags(self, *, record: LlmTraceRecord, status: str) -> list[str]:
        tags = [
            "query-forge",
            "pipeline",
            "llm",
            f"purpose:{record.request_purpose}",
            f"provider:{record.provider}",
            f"provider_type:{record.provider_type}",
            f"stage:{_purpose_stage(record.request_purpose)}",
            f"status:{status}",
        ]
        if record.fallback_used:
            tags.append("fallback:true")
        if record.structured_output_used:
            tags.append("structured_output:true")
        return tags

    def _trace_metadata(self, *, record: LlmTraceRecord, status: str) -> dict[str, Any]:
        return {
            "schema_version": EVENT_SCHEMA_VERSION,
            "component": "pipeline.common.llm_client",
            "request_purpose": record.request_purpose,
            "trace_id": record.trace_id,
            "stage": _purpose_stage(record.request_purpose),
            "status": status,
        }

    def _build_metadata(
        self,
        *,
        record: LlmTraceRecord,
        status: str,
        latency_ms: int,
        usage_details: dict[str, int],
        http_status: int | None,
        error_type: str | None,
        error_message: str | None,
        sample_rate: float,
    ) -> dict[str, Any]:
        return {
            "schema_version": EVENT_SCHEMA_VERSION,
            "component": "pipeline.common.llm_client",
            "request_purpose": record.request_purpose,
            "stage": _purpose_stage(record.request_purpose),
            "trace_id": record.trace_id,
            "provider": record.provider,
            "provider_type": record.provider_type,
            "model": record.model,
            "status": status,
            "fallback_used": record.fallback_used,
            "structured_output_used": record.structured_output_used,
            "retry_count": record.retry_count,
            "attempts_used": record.attempts_used,
            "latency_ms": latency_ms,
            "estimated_tokens": record.estimated_tokens,
            "usage_details": usage_details,
            "response_schema_present": record.response_schema_present,
            "response_schema_hash": record.response_schema_hash,
            "prompt_fingerprint": record.prompt_fingerprint,
            "http_status": http_status,
            "error_type": error_type,
            "error_message": _truncate(error_message or "", limit=512),
            "sample_rate": sample_rate,
        }

    def _take_quota_slot(self) -> bool:
        now = time.monotonic()
        with self._quota_lock:
            while self._events_minute and (now - self._events_minute[0]) >= 60.0:
                self._events_minute.popleft()
            while self._events_day and (now - self._events_day[0]) >= 86400.0:
                self._events_day.popleft()
            if len(self._events_minute) >= self.max_events_per_minute:
                return False
            if len(self._events_day) >= self.max_events_per_day:
                return False
            self._events_minute.append(now)
            self._events_day.append(now)
            return True

    @contextmanager
    def _propagate(self, *, tags: list[str], metadata: dict[str, Any]):
        if self._client is None:
            yield
            return
        propagate = getattr(self._client, "propagate_attributes", None)
        if not callable(propagate):
            yield
            return
        payload: dict[str, Any] = {
            "trace_name": TRACE_NAME,
            "metadata": metadata,
            "tags": tags,
        }
        if self.pipeline_user_id:
            payload["user_id"] = self.pipeline_user_id
        try:
            with propagate(**payload):
                yield
            return
        except Exception:  # noqa: BLE001
            LOGGER.debug("langfuse_propagate_fallback metadata_only", exc_info=True)
        yield

    def _create_scores(
        self,
        *,
        status: str,
        record: LlmTraceRecord,
        trace_id: str | None,
        observation_id: str | None,
        latency_ms: int,
        http_status: int | None,
    ) -> None:
        if self._client is None:
            return
        if self.score_mode == "off":
            return
        if self.score_mode == "errors" and status != "error":
            return
        if not trace_id:
            return
        create_score = getattr(self._client, "create_score", None)
        if not callable(create_score):
            return
        try:
            create_score(
                name="llm_request_success",
                value=1 if status == "success" else 0,
                trace_id=trace_id,
                observation_id=observation_id,
                data_type="BOOLEAN",
                metadata={
                    "schema_version": EVENT_SCHEMA_VERSION,
                    "request_purpose": record.request_purpose,
                    "provider": record.provider,
                    "model": record.model,
                },
            )
            create_score(
                name="llm_latency_ms",
                value=float(latency_ms),
                trace_id=trace_id,
                observation_id=observation_id,
                data_type="NUMERIC",
                metadata={
                    "schema_version": EVENT_SCHEMA_VERSION,
                    "request_purpose": record.request_purpose,
                    "http_status": http_status,
                },
            )
        except Exception:  # noqa: BLE001
            LOGGER.debug(
                "langfuse_score_emit_failed purpose=%s trace_id=%s",
                record.request_purpose,
                record.trace_id,
                exc_info=True,
            )


_OBSERVER: LangfuseLlmObserver | None = None
_OBSERVER_LOCK = threading.Lock()


def get_langfuse_llm_observer() -> LangfuseLlmObserver:
    global _OBSERVER
    with _OBSERVER_LOCK:
        if _OBSERVER is None:
            _OBSERVER = LangfuseLlmObserver()
        return _OBSERVER
