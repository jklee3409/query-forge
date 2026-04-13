from __future__ import annotations

from abc import ABC, abstractmethod
import hashlib
import json
import logging
import os
import random
import re
import threading
import time
from collections import deque
from contextlib import contextmanager
from dataclasses import asdict, dataclass, replace
from typing import Any

import requests


JSON_BLOCK_PATTERN = re.compile(r"\{.*\}", re.DOTALL)
RETRY_IN_SECONDS_PATTERN = re.compile(r"try again in\s*([0-9]+(?:\.[0-9]+)?)s", re.IGNORECASE)
CODE_BLOCK_JSON_PATTERN = re.compile(r"```(?:json)?\s*(.*?)\s*```", re.IGNORECASE | re.DOTALL)

LOGGER = logging.getLogger(__name__)
RETRYABLE_HTTP_STATUS = {429, 500, 502, 503, 504}
SUPPORTED_PROVIDERS = {"gemini-native", "gemini", "groq", "openai", "mock"}
_WARNED_COMPAT_MODELS: set[str] = set()


class _SharedRateLimiter:
    def __init__(
        self,
        *,
        min_interval_seconds: float,
        tokens_per_minute: int,
        requests_per_day: int,
    ) -> None:
        self.min_interval_seconds = max(0.0, float(min_interval_seconds))
        self.tokens_per_minute = max(0, int(tokens_per_minute))
        self.requests_per_day = max(0, int(requests_per_day))
        self._last_call_at = 0.0
        self._token_events: deque[tuple[float, int]] = deque()
        self._request_events: deque[float] = deque()
        self._lock = threading.Lock()

    def acquire(self, estimated_tokens: int) -> None:
        with self._lock:
            while True:
                now = time.monotonic()
                self._evict_expired(now)

                wait_interval = self.min_interval_seconds - (now - self._last_call_at)
                wait_tokens = self._wait_for_tokens(now, estimated_tokens)
                wait_requests_per_day = self._wait_for_requests_per_day(now)
                wait_seconds = max(wait_interval, wait_tokens, wait_requests_per_day, 0.0)

                if wait_seconds <= 0.0:
                    now = time.monotonic()
                    self._evict_expired(now)
                    self._last_call_at = now
                    if self.tokens_per_minute > 0 and estimated_tokens > 0:
                        self._token_events.append((now, estimated_tokens))
                    if self.requests_per_day > 0:
                        self._request_events.append(now)
                    return
                time.sleep(wait_seconds)

    def _wait_for_tokens(self, now: float, estimated_tokens: int) -> float:
        if self.tokens_per_minute <= 0 or estimated_tokens <= 0:
            return 0.0
        used_tokens = sum(tokens for _timestamp, tokens in self._token_events)
        overflow = (used_tokens + estimated_tokens) - self.tokens_per_minute
        if overflow <= 0:
            return 0.0
        removable = 0
        for timestamp, tokens in self._token_events:
            removable += tokens
            if removable >= overflow:
                return max(0.0, (timestamp + 60.0) - now)
        oldest = self._token_events[0][0] if self._token_events else now
        return max(0.0, (oldest + 60.0) - now)

    def _wait_for_requests_per_day(self, now: float) -> float:
        if self.requests_per_day <= 0:
            return 0.0
        used_requests = len(self._request_events)
        overflow = (used_requests + 1) - self.requests_per_day
        if overflow <= 0:
            return 0.0
        for index, timestamp in enumerate(self._request_events):
            if (index + 1) >= overflow:
                return max(0.0, (timestamp + 86400.0) - now)
        oldest = self._request_events[0] if self._request_events else now
        return max(0.0, (oldest + 86400.0) - now)

    def _evict_expired(self, now: float) -> None:
        while self._token_events and (now - self._token_events[0][0]) >= 60.0:
            self._token_events.popleft()
        while self._request_events and (now - self._request_events[0]) >= 86400.0:
            self._request_events.popleft()


class _SharedConcurrencyLimiter:
    def __init__(self, limit: int) -> None:
        self.limit = max(1, int(limit))
        self._semaphore = threading.BoundedSemaphore(self.limit)

    @contextmanager
    def slot(self) -> Any:
        self._semaphore.acquire()
        try:
            yield
        finally:
            self._semaphore.release()


_RATE_LIMITERS: dict[str, _SharedRateLimiter] = {}
_RATE_LIMITERS_LOCK = threading.Lock()
_CONCURRENCY_LIMITERS: dict[str, _SharedConcurrencyLimiter] = {}
_CONCURRENCY_LIMITERS_LOCK = threading.Lock()


class _RetryableLlmError(RuntimeError):
    pass


class _SchemaValidationError(RuntimeError):
    pass


class _MalformedLlmResponseError(RuntimeError):
    pass


@dataclass(slots=True)
class LlmStageConfig:
    provider: str
    base_url: str
    api_key: str
    model: str
    temperature: float
    max_tokens: int
    timeout_seconds: float
    min_interval_seconds: float
    tokens_per_minute: int
    requests_per_day: int
    chars_per_token: float
    max_retries: int
    backoff_initial_seconds: float
    backoff_max_seconds: float
    backoff_multiplier: float
    backoff_jitter_ratio: float
    fallback_models: tuple[str, ...]
    thinking_budget: int | None
    concurrency_limit: int


@dataclass(frozen=True, slots=True)
class ModelCapability:
    supportsStructuredOutput: bool
    supportsResponseSchema: bool
    supportsThinkingBudget: bool
    supportsJsonMode: bool
    supportsStreaming: bool


GEMINI_NATIVE_CAPABILITY = ModelCapability(
    supportsStructuredOutput=True,
    supportsResponseSchema=True,
    supportsThinkingBudget=True,
    supportsJsonMode=True,
    supportsStreaming=False,
)
OPENAI_COMPATIBILITY_CAPABILITY = ModelCapability(
    supportsStructuredOutput=False,
    supportsResponseSchema=False,
    supportsThinkingBudget=False,
    supportsJsonMode=True,
    supportsStreaming=False,
)
MOCK_CAPABILITY = ModelCapability(
    supportsStructuredOutput=False,
    supportsResponseSchema=False,
    supportsThinkingBudget=False,
    supportsJsonMode=False,
    supportsStreaming=False,
)


class LLMClient(ABC):
    @property
    @abstractmethod
    def provider_type(self) -> str:
        raise NotImplementedError

    @property
    @abstractmethod
    def capability(self) -> ModelCapability:
        raise NotImplementedError

    @abstractmethod
    def invoke_json(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        response_schema: dict[str, Any] | None,
    ) -> tuple[requests.Response, str, bool]:
        raise NotImplementedError


class OpenAICompatibleClient(LLMClient):
    def __init__(self, config: LlmStageConfig) -> None:
        self._config = config

    @property
    def provider_type(self) -> str:
        return "openai"

    @property
    def capability(self) -> ModelCapability:
        return OPENAI_COMPATIBILITY_CAPABILITY

    def invoke_json(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        response_schema: dict[str, Any] | None,
    ) -> tuple[requests.Response, str, bool]:
        del response_schema
        payload: dict[str, Any] = {
            "model": self._config.model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "temperature": self._config.temperature,
            "max_tokens": self._config.max_tokens,
            "response_format": {"type": "json_object"},
        }
        concurrency_limiter = _resolve_concurrency_limiter(self._config)
        with concurrency_limiter.slot():
            response = requests.post(
                f"{self._config.base_url.rstrip('/')}/chat/completions",
                headers={
                    "Authorization": f"Bearer {self._config.api_key}",
                    "Content-Type": "application/json",
                },
                json=payload,
                timeout=self._config.timeout_seconds,
            )
        if (
            self._config.provider in {"gemini", "gemini-native"}
            and response.status_code == 400
            and "response_format" in payload
        ):
            payload.pop("response_format", None)
            with concurrency_limiter.slot():
                response = requests.post(
                    f"{self._config.base_url.rstrip('/')}/chat/completions",
                    headers={
                        "Authorization": f"Bearer {self._config.api_key}",
                        "Content-Type": "application/json",
                    },
                    json=payload,
                    timeout=self._config.timeout_seconds,
                )
            if self._config.model not in _WARNED_COMPAT_MODELS:
                LOGGER.warning(
                    "gemini_openai_compat_mode provider=%s model=%s base_url=%s supportsResponseSchema=%s supportsThinkingBudget=%s",
                    self._config.provider,
                    self._config.model,
                    self._config.base_url,
                    self.capability.supportsResponseSchema,
                    self.capability.supportsThinkingBudget,
                )
                _WARNED_COMPAT_MODELS.add(self._config.model)

        if response.status_code >= 400:
            return response, response.text or "", False
        body = _safe_json_body(response)
        text = _extract_openai_compatible_text(body)
        return response, text, False


class GeminiNativeClient(LLMClient):
    def __init__(self, config: LlmStageConfig) -> None:
        self._config = config

    @property
    def provider_type(self) -> str:
        return "gemini-native"

    @property
    def capability(self) -> ModelCapability:
        return GEMINI_NATIVE_CAPABILITY

    def invoke_json(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        response_schema: dict[str, Any] | None,
    ) -> tuple[requests.Response, str, bool]:
        generation_config: dict[str, Any] = {
            "temperature": self._config.temperature,
            "maxOutputTokens": self._config.max_tokens,
            "responseMimeType": "application/json",
            "responseSchema": _to_gemini_schema(
                response_schema
                or {
                    "type": "object",
                    "additionalProperties": True,
                }
            ),
        }
        if self._config.thinking_budget is not None:
            generation_config["thinkingConfig"] = {"thinkingBudget": self._config.thinking_budget}
        payload: dict[str, Any] = {
            "systemInstruction": {"parts": [{"text": system_prompt}]},
            "contents": [{"role": "user", "parts": [{"text": user_prompt}]}],
            "generationConfig": generation_config,
        }
        endpoint = f"{self._config.base_url.rstrip('/')}/models/{self._config.model}:generateContent"
        concurrency_limiter = _resolve_concurrency_limiter(self._config)
        with concurrency_limiter.slot():
            response = requests.post(
                endpoint,
                params={"key": self._config.api_key},
                headers={"Content-Type": "application/json"},
                json=payload,
                timeout=self._config.timeout_seconds,
            )
        if response.status_code >= 400:
            return response, response.text or "", True
        body = _safe_json_body(response)
        text = _extract_gemini_text(body)
        return response, text, True


class LlmClient:
    def __init__(self, config: LlmStageConfig) -> None:
        self.config = config

    def capability(self) -> ModelCapability:
        return _build_provider_client(self.config).capability

    def chat_json(
        self,
        *,
        system_prompt: str,
        user_prompt: str,
        response_schema: dict[str, Any] | None = None,
        request_purpose: str = "unspecified",
        trace_id: str | None = None,
    ) -> dict[str, Any]:
        if self.config.provider == "mock":
            return {
                "mock": True,
                "_llm_meta": {
                    "provider": "mock",
                    "model": self.config.model,
                    "fallback_used": False,
                    "request_purpose": request_purpose,
                    "trace_id": trace_id,
                },
            }

        targets = [self.config, *self._resolve_fallback_targets()]
        last_exception: Exception | None = None

        for index, target in enumerate(targets):
            fallback_used = index > 0
            try:
                parsed, attempts_used, capability, structured_output_used, provider_type = self._chat_json_with_target(
                    config=target,
                    system_prompt=system_prompt,
                    user_prompt=user_prompt,
                    response_schema=response_schema,
                    request_purpose=request_purpose,
                    trace_id=trace_id,
                )
                parsed["_llm_meta"] = {
                    "provider": target.provider,
                    "provider_type": provider_type,
                    "model": target.model,
                    "fallback_used": fallback_used,
                    "structured_output_used": structured_output_used,
                    "thinking_budget": target.thinking_budget if capability.supportsThinkingBudget else None,
                    "retry_count": max(0, attempts_used - 1),
                    "capability": asdict(capability),
                    "request_purpose": request_purpose,
                    "trace_id": trace_id,
                }
                if fallback_used:
                    LOGGER.warning(
                        "llm_fallback_success purpose=%s trace_id=%s provider=%s provider_type=%s model=%s structured_output=%s thinking_budget=%s retries=%s",
                        request_purpose,
                        trace_id,
                        target.provider,
                        provider_type,
                        target.model,
                        structured_output_used,
                        target.thinking_budget if capability.supportsThinkingBudget else None,
                        max(0, attempts_used - 1),
                    )
                return parsed
            except requests.HTTPError as exc:
                status = exc.response.status_code if exc.response is not None else None
                if status is not None and 400 <= status < 500 and status != 429:
                    LOGGER.error(
                        "llm_client_error_no_retry status=%s purpose=%s trace_id=%s provider=%s provider_type=%s model=%s",
                        status,
                        request_purpose,
                        trace_id,
                        target.provider,
                        _runtime_provider_type(target),
                        target.model,
                    )
                    raise
                last_exception = exc
            except _RetryableLlmError as exc:
                last_exception = exc
                LOGGER.warning(
                    "llm_target_failed_retryable purpose=%s trace_id=%s provider=%s provider_type=%s model=%s fallback_used=%s reason=%s",
                    request_purpose,
                    trace_id,
                    target.provider,
                    _runtime_provider_type(target),
                    target.model,
                    fallback_used,
                    str(exc),
                )
            except Exception as exc:  # noqa: BLE001
                last_exception = exc
                LOGGER.exception(
                    "llm_target_failed_unexpected purpose=%s trace_id=%s provider=%s provider_type=%s model=%s",
                    request_purpose,
                    trace_id,
                    target.provider,
                    _runtime_provider_type(target),
                    target.model,
                )

        if last_exception:
            raise RuntimeError("LLM request failed for all configured models.") from last_exception
        raise RuntimeError("LLM request failed for unknown reason.")

    def _chat_json_with_target(
        self,
        *,
        config: LlmStageConfig,
        system_prompt: str,
        user_prompt: str,
        response_schema: dict[str, Any] | None,
        request_purpose: str,
        trace_id: str | None,
    ) -> tuple[dict[str, Any], int, ModelCapability, bool, str]:
        provider_client = _build_provider_client(config)
        capability = provider_client.capability
        provider_type = provider_client.provider_type
        use_structured_output = bool(response_schema) and capability.supportsStructuredOutput
        if response_schema and not capability.supportsResponseSchema:
            LOGGER.info(
                "llm_schema_fallback_to_prompt_json provider=%s provider_type=%s model=%s purpose=%s trace_id=%s",
                config.provider,
                provider_type,
                config.model,
                request_purpose,
                trace_id,
            )
        LOGGER.info(
            "llm_request_start provider=%s provider_type=%s model=%s structured_output=%s thinking_budget=%s purpose=%s trace_id=%s",
            config.provider,
            provider_type,
            config.model,
            use_structured_output,
            config.thinking_budget if capability.supportsThinkingBudget else None,
            request_purpose,
            trace_id,
        )
        max_attempts = max(1, int(config.max_retries) + 1)
        estimated_tokens = _estimate_request_tokens(
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            max_tokens=config.max_tokens,
            chars_per_token=config.chars_per_token,
        )
        prompt_fingerprint = _prompt_fingerprint(system_prompt, user_prompt)
        retryable_failure: Exception | None = None

        for attempt in range(max_attempts):
            self._throttle(config=config, estimated_tokens=estimated_tokens)
            response = None
            try:
                response, response_text, structured_output_used = provider_client.invoke_json(
                    system_prompt=system_prompt,
                    user_prompt=user_prompt,
                    response_schema=response_schema,
                )
            except _MalformedLlmResponseError as exc:
                retryable_failure = exc
                if attempt + 1 >= max_attempts:
                    break
                self._sleep_with_retry_log(
                    config=config,
                    provider_type=provider_type,
                    attempt=attempt,
                    max_attempts=max_attempts,
                    reason=f"missing_content_{type(exc).__name__}",
                    request_purpose=request_purpose,
                    trace_id=trace_id,
                    prompt_fingerprint=prompt_fingerprint,
                )
                continue
            except requests.Timeout as exc:
                retryable_failure = exc
                if attempt + 1 >= max_attempts:
                    break
                self._sleep_with_retry_log(
                    config=config,
                    provider_type=provider_type,
                    attempt=attempt,
                    max_attempts=max_attempts,
                    reason="timeout",
                    request_purpose=request_purpose,
                    trace_id=trace_id,
                    prompt_fingerprint=prompt_fingerprint,
                )
                continue
            except requests.RequestException as exc:
                retryable_failure = exc
                if attempt + 1 >= max_attempts:
                    break
                self._sleep_with_retry_log(
                    config=config,
                    provider_type=provider_type,
                    attempt=attempt,
                    max_attempts=max_attempts,
                    reason=type(exc).__name__,
                    request_purpose=request_purpose,
                    trace_id=trace_id,
                    prompt_fingerprint=prompt_fingerprint,
                )
                continue

            status_code = int(response.status_code)
            if status_code in RETRYABLE_HTTP_STATUS:
                retryable_failure = requests.HTTPError(
                    f"Retryable HTTP status {status_code}",
                    response=response,
                )
                if attempt + 1 >= max_attempts:
                    break
                retry_after = _parse_retry_after(response=response)
                self._sleep_with_retry_log(
                    config=config,
                    provider_type=provider_type,
                    attempt=attempt,
                    max_attempts=max_attempts,
                    reason=f"http_{status_code}",
                    request_purpose=request_purpose,
                    trace_id=trace_id,
                    prompt_fingerprint=prompt_fingerprint,
                    min_wait=retry_after,
                )
                continue

            try:
                response.raise_for_status()
                parsed = _parse_json_text(
                    response_text,
                    allow_markdown_fallback=(
                        attempt + 1 >= max_attempts and (response_schema is None or not structured_output_used)
                    ),
                )
                _validate_against_schema(parsed, response_schema)
            except requests.HTTPError as exc:
                status = exc.response.status_code if exc.response is not None else None
                if status is not None and 400 <= status < 500 and status != 429:
                    LOGGER.error(
                        "llm_http_non_retryable status=%s purpose=%s trace_id=%s provider=%s provider_type=%s model=%s prompt=%s",
                        status,
                        request_purpose,
                        trace_id,
                        config.provider,
                        provider_type,
                        config.model,
                        prompt_fingerprint,
                    )
                    raise
                retryable_failure = exc
                if attempt + 1 >= max_attempts:
                    break
                self._sleep_with_retry_log(
                    config=config,
                    provider_type=provider_type,
                    attempt=attempt,
                    max_attempts=max_attempts,
                    reason=f"http_{status}",
                    request_purpose=request_purpose,
                    trace_id=trace_id,
                    prompt_fingerprint=prompt_fingerprint,
                )
                continue
            except (_SchemaValidationError, _MalformedLlmResponseError, ValueError, json.JSONDecodeError) as exc:
                retryable_failure = exc
                if attempt + 1 >= max_attempts:
                    break
                self._sleep_with_retry_log(
                    config=config,
                    provider_type=provider_type,
                    attempt=attempt,
                    max_attempts=max_attempts,
                    reason=f"parse_{type(exc).__name__}",
                    request_purpose=request_purpose,
                    trace_id=trace_id,
                    prompt_fingerprint=prompt_fingerprint,
                )
                continue

            if not isinstance(parsed, dict):
                retryable_failure = _MalformedLlmResponseError("LLM JSON response must be object.")
                if attempt + 1 >= max_attempts:
                    break
                self._sleep_with_retry_log(
                    config=config,
                    provider_type=provider_type,
                    attempt=attempt,
                    max_attempts=max_attempts,
                    reason="json_not_object",
                    request_purpose=request_purpose,
                    trace_id=trace_id,
                    prompt_fingerprint=prompt_fingerprint,
                )
                continue
            return parsed, attempt + 1, capability, structured_output_used, provider_type

        if retryable_failure:
            raise _RetryableLlmError("LLM request failed after retry budget.") from retryable_failure
        raise _RetryableLlmError("LLM request failed without captured exception.")

    def _sleep_with_retry_log(
        self,
        *,
        config: LlmStageConfig,
        provider_type: str,
        attempt: int,
        max_attempts: int,
        reason: str,
        request_purpose: str,
        trace_id: str | None,
        prompt_fingerprint: str,
        min_wait: float = 0.0,
    ) -> None:
        wait = max(min_wait, _compute_backoff_seconds(config=config, attempt=attempt))
        LOGGER.warning(
            "llm_retry reason=%s purpose=%s trace_id=%s provider=%s provider_type=%s model=%s attempt=%s/%s wait=%.3fs prompt=%s",
            reason,
            request_purpose,
            trace_id,
            config.provider,
            provider_type,
            config.model,
            attempt + 1,
            max_attempts,
            wait,
            prompt_fingerprint,
        )
        time.sleep(wait)

    def _throttle(self, *, config: LlmStageConfig, estimated_tokens: int) -> None:
        _resolve_rate_limiter(config).acquire(estimated_tokens)

    def _resolve_fallback_targets(self) -> list[LlmStageConfig]:
        targets: list[LlmStageConfig] = []
        seen: set[tuple[str, str, str]] = set()
        for raw_spec in self.config.fallback_models:
            provider, model = _parse_provider_model_spec(raw_spec, default_provider=self.config.provider)
            if not provider or not model or provider not in SUPPORTED_PROVIDERS:
                continue
            if provider == self.config.provider and model == self.config.model:
                continue
            if provider == self.config.provider:
                base_url = self.config.base_url
                api_key = self.config.api_key
            else:
                base_url = _resolve_base_url_for_provider(provider)
                api_key = _resolve_api_key_for_provider(provider)
            if not api_key:
                LOGGER.warning(
                    "llm_fallback_skipped_no_api_key provider=%s model=%s",
                    provider,
                    model,
                )
                continue
            key = (provider, base_url, model)
            if key in seen:
                continue
            seen.add(key)
            targets.append(
                replace(
                    self.config,
                    provider=provider,
                    base_url=base_url,
                    api_key=api_key,
                    model=model,
                    fallback_models=(),
                )
            )
        return targets


def _pick(raw: dict[str, Any], *keys: str) -> Any:
    for key in keys:
        if key in raw and raw[key] is not None:
            return raw[key]
    return None


def _normalize_provider_name(raw_provider: str) -> str:
    normalized = str(raw_provider or "").strip().lower().replace("_", "-")
    if normalized in {"gemini-native", "gemini"}:
        return normalized
    if normalized in {"openai-compatible", "openai"}:
        return "openai"
    if normalized in {"groq", "mock"}:
        return normalized
    return normalized or "openai"


def _provider_env_suffix(provider: str) -> str:
    return re.sub(r"[^A-Z0-9]", "_", provider.upper())


def _runtime_provider_type(config: LlmStageConfig) -> str:
    provider = _normalize_provider_name(config.provider)
    if provider == "gemini-native":
        return "gemini-native"
    if provider == "gemini":
        if "/openai" not in config.base_url.rstrip("/"):
            return "gemini-native"
        return "openai"
    return "openai"


def _build_provider_client(config: LlmStageConfig) -> LLMClient:
    provider_type = _runtime_provider_type(config)
    if provider_type == "gemini-native":
        return GeminiNativeClient(config)
    return OpenAICompatibleClient(config)


def load_stage_config(
    *,
    stage: str,
    raw_config: dict[str, Any] | None = None,
) -> LlmStageConfig:
    raw_config = raw_config or {}
    llm_block = raw_config.get("llm") if isinstance(raw_config.get("llm"), dict) else {}
    env_stage = stage.upper()
    provider = _normalize_provider_name(
        str(
            _pick(
                raw_config,
                f"llm_{stage}_provider",
                "llm_provider",
            )
            or _pick(llm_block, f"{stage}_provider", "provider")
            or os.getenv(f"QUERY_FORGE_LLM_{env_stage}_PROVIDER")
            or os.getenv("QUERY_FORGE_LLM_PROVIDER")
            or "gemini-native"
        )
    )
    is_gemini_family = provider in {"gemini", "gemini-native"}

    default_base_url = _default_base_url(provider)
    base_url = str(
        _pick(raw_config, f"llm_{stage}_base_url", "llm_base_url")
        or _pick(llm_block, f"{stage}_base_url", "base_url")
        or os.getenv(f"QUERY_FORGE_LLM_{env_stage}_BASE_URL")
        or os.getenv("QUERY_FORGE_LLM_BASE_URL")
        or default_base_url
    ).strip()

    default_model = _default_model(provider)
    model = str(
        _pick(raw_config, f"llm_{stage}_model", "llm_model")
        or _pick(llm_block, f"{stage}_model", "model")
        or os.getenv(f"QUERY_FORGE_LLM_{env_stage}_MODEL")
        or os.getenv("QUERY_FORGE_LLM_MODEL")
        or default_model
    ).strip()

    api_key = str(
        _pick(raw_config, f"llm_{stage}_api_key", "llm_api_key")
        or _pick(llm_block, f"{stage}_api_key", "api_key")
        or os.getenv(f"QUERY_FORGE_LLM_{env_stage}_API_KEY")
        or os.getenv("QUERY_FORGE_LLM_API_KEY")
        or _resolve_api_key_for_provider(provider)
        or ""
    ).strip()

    if provider != "mock" and not api_key:
        raise RuntimeError(
            f"LLM API key is required for provider={provider}. "
            "Set QUERY_FORGE_LLM_API_KEY (or GEMINI_API_KEY / GROQ_API_KEY / OPENAI_API_KEY)."
        )

    temperature = float(
        _pick(raw_config, f"llm_{stage}_temperature", "llm_temperature")
        or _pick(llm_block, f"{stage}_temperature", "temperature")
        or os.getenv(f"QUERY_FORGE_LLM_{env_stage}_TEMPERATURE")
        or os.getenv("QUERY_FORGE_LLM_TEMPERATURE")
        or 0.2
    )
    default_max_output_tokens = 384 if is_gemini_family else 512
    max_tokens = int(
        _pick(raw_config, f"llm_{stage}_max_output_tokens", f"llm_{stage}_max_tokens", "llm_max_output_tokens", "llm_max_tokens")
        or _pick(llm_block, f"{stage}_max_output_tokens", f"{stage}_max_tokens", "max_output_tokens", "max_tokens")
        or os.getenv(f"QUERY_FORGE_LLM_{env_stage}_MAX_OUTPUT_TOKENS")
        or os.getenv(f"QUERY_FORGE_LLM_{env_stage}_MAX_TOKENS")
        or os.getenv("QUERY_FORGE_LLM_MAX_OUTPUT_TOKENS")
        or os.getenv("QUERY_FORGE_LLM_MAX_TOKENS")
        or default_max_output_tokens
    )
    timeout_seconds = float(
        _pick(raw_config, f"llm_{stage}_timeout_seconds", "llm_timeout_seconds")
        or _pick(llm_block, f"{stage}_timeout_seconds", "timeout_seconds")
        or os.getenv(f"QUERY_FORGE_LLM_{env_stage}_TIMEOUT_SECONDS")
        or os.getenv("QUERY_FORGE_LLM_TIMEOUT_SECONDS")
        or 45.0
    )
    default_rpm = 300.0 if is_gemini_family else 20.0
    requests_per_minute = float(
        _pick(raw_config, f"llm_{stage}_rpm", "llm_rpm")
        or _pick(llm_block, f"{stage}_rpm", "rpm")
        or os.getenv(f"QUERY_FORGE_LLM_{env_stage}_RPM")
        or os.getenv("QUERY_FORGE_LLM_RPM")
        or default_rpm
    )
    min_interval = 0.0 if requests_per_minute <= 0 else 60.0 / requests_per_minute
    if is_gemini_family:
        default_tpm = 1_000_000
    elif provider == "groq":
        default_tpm = 6000
    else:
        default_tpm = 0
    tokens_per_minute = int(
        _pick(raw_config, f"llm_{stage}_tpm", "llm_tpm")
        or _pick(llm_block, f"{stage}_tpm", "tpm")
        or os.getenv(f"QUERY_FORGE_LLM_{env_stage}_TPM")
        or os.getenv("QUERY_FORGE_LLM_TPM")
        or default_tpm
    )
    default_rpd = 10_000 if is_gemini_family else 0
    requests_per_day = int(
        _pick(raw_config, f"llm_{stage}_rpd", "llm_rpd")
        or _pick(llm_block, f"{stage}_rpd", "rpd")
        or os.getenv(f"QUERY_FORGE_LLM_{env_stage}_RPD")
        or os.getenv("QUERY_FORGE_LLM_RPD")
        or default_rpd
    )
    chars_per_token = float(
        _pick(raw_config, f"llm_{stage}_chars_per_token", "llm_chars_per_token")
        or _pick(llm_block, f"{stage}_chars_per_token", "chars_per_token")
        or os.getenv(f"QUERY_FORGE_LLM_{env_stage}_CHARS_PER_TOKEN")
        or os.getenv("QUERY_FORGE_LLM_CHARS_PER_TOKEN")
        or 2.2
    )
    max_retries = int(
        _pick(raw_config, f"llm_{stage}_max_retries", "llm_max_retries")
        or _pick(llm_block, f"{stage}_max_retries", "max_retries")
        or os.getenv(f"QUERY_FORGE_LLM_{env_stage}_MAX_RETRIES")
        or os.getenv("QUERY_FORGE_LLM_MAX_RETRIES")
        or 4
    )
    max_retries = max(0, min(max_retries, 10))
    backoff_initial_seconds = float(
        _pick(raw_config, f"llm_{stage}_backoff_initial_seconds", "llm_backoff_initial_seconds")
        or _pick(llm_block, f"{stage}_backoff_initial_seconds", "backoff_initial_seconds")
        or os.getenv(f"QUERY_FORGE_LLM_{env_stage}_BACKOFF_INITIAL_SECONDS")
        or os.getenv("QUERY_FORGE_LLM_BACKOFF_INITIAL_SECONDS")
        or 0.75
    )
    backoff_max_seconds = float(
        _pick(raw_config, f"llm_{stage}_backoff_max_seconds", "llm_backoff_max_seconds")
        or _pick(llm_block, f"{stage}_backoff_max_seconds", "backoff_max_seconds")
        or os.getenv(f"QUERY_FORGE_LLM_{env_stage}_BACKOFF_MAX_SECONDS")
        or os.getenv("QUERY_FORGE_LLM_BACKOFF_MAX_SECONDS")
        or 12.0
    )
    backoff_multiplier = float(
        _pick(raw_config, f"llm_{stage}_backoff_multiplier", "llm_backoff_multiplier")
        or _pick(llm_block, f"{stage}_backoff_multiplier", "backoff_multiplier")
        or os.getenv(f"QUERY_FORGE_LLM_{env_stage}_BACKOFF_MULTIPLIER")
        or os.getenv("QUERY_FORGE_LLM_BACKOFF_MULTIPLIER")
        or 2.0
    )
    backoff_jitter_ratio = float(
        _pick(raw_config, f"llm_{stage}_backoff_jitter_ratio", "llm_backoff_jitter_ratio")
        or _pick(llm_block, f"{stage}_backoff_jitter_ratio", "backoff_jitter_ratio")
        or os.getenv(f"QUERY_FORGE_LLM_{env_stage}_BACKOFF_JITTER_RATIO")
        or os.getenv("QUERY_FORGE_LLM_BACKOFF_JITTER_RATIO")
        or 0.35
    )
    fallback_models_raw = (
        _pick(raw_config, f"llm_{stage}_fallback_models", "llm_fallback_models")
        or _pick(llm_block, f"{stage}_fallback_models", "fallback_models")
        or os.getenv(f"QUERY_FORGE_LLM_{env_stage}_FALLBACK_MODELS")
        or os.getenv("QUERY_FORGE_LLM_FALLBACK_MODELS")
        or ""
    )
    fallback_models = _parse_fallback_models(fallback_models_raw)
    if not fallback_models:
        fallback_models = _default_fallback_models(provider, model)
    thinking_budget_raw = (
        _pick(raw_config, f"llm_{stage}_thinking_budget", "llm_thinking_budget")
        or _pick(llm_block, f"{stage}_thinking_budget", "thinking_budget")
        or os.getenv(f"QUERY_FORGE_LLM_{env_stage}_THINKING_BUDGET")
        or os.getenv("QUERY_FORGE_LLM_THINKING_BUDGET")
    )
    if thinking_budget_raw is None or str(thinking_budget_raw).strip() == "":
        thinking_budget = 0 if is_gemini_family else None
    else:
        thinking_budget = int(thinking_budget_raw)
    concurrency_limit = int(
        _pick(raw_config, f"llm_{stage}_concurrency_limit", "llm_concurrency_limit")
        or _pick(llm_block, f"{stage}_concurrency_limit", "concurrency_limit")
        or os.getenv(f"QUERY_FORGE_LLM_{env_stage}_CONCURRENCY_LIMIT")
        or os.getenv("QUERY_FORGE_LLM_CONCURRENCY_LIMIT")
        or (4 if is_gemini_family else 2)
    )
    concurrency_limit = max(1, min(concurrency_limit, 64))

    return LlmStageConfig(
        provider=provider,
        base_url=base_url,
        api_key=api_key,
        model=model,
        temperature=temperature,
        max_tokens=max_tokens,
        timeout_seconds=timeout_seconds,
        min_interval_seconds=min_interval,
        tokens_per_minute=tokens_per_minute,
        requests_per_day=requests_per_day,
        chars_per_token=chars_per_token,
        max_retries=max_retries,
        backoff_initial_seconds=max(0.05, backoff_initial_seconds),
        backoff_max_seconds=max(0.1, backoff_max_seconds),
        backoff_multiplier=max(1.1, backoff_multiplier),
        backoff_jitter_ratio=max(0.0, min(backoff_jitter_ratio, 2.0)),
        fallback_models=fallback_models,
        thinking_budget=thinking_budget,
        concurrency_limit=concurrency_limit,
    )


def _parse_json_text(
    text: str,
    *,
    allow_markdown_fallback: bool,
) -> dict[str, Any] | list[Any]:
    stripped = (text or "").strip()
    if not stripped:
        raise _MalformedLlmResponseError("LLM returned empty response.")
    try:
        return json.loads(stripped)
    except json.JSONDecodeError:
        if not allow_markdown_fallback:
            raise
        code_block_match = CODE_BLOCK_JSON_PATTERN.search(stripped)
        if code_block_match:
            inner = (code_block_match.group(1) or "").strip()
            if inner:
                try:
                    return json.loads(inner)
                except json.JSONDecodeError:
                    pass
        balanced = _extract_first_balanced_object(stripped)
        if balanced:
            return json.loads(balanced)
        matched = JSON_BLOCK_PATTERN.search(stripped)
        if matched:
            return json.loads(matched.group(0))
        raise


def _estimate_request_tokens(
    *,
    system_prompt: str,
    user_prompt: str,
    max_tokens: int,
    chars_per_token: float,
) -> int:
    ratio = chars_per_token if chars_per_token > 0 else 2.2
    prompt_chars = len(system_prompt or "") + len(user_prompt or "")
    prompt_tokens = max(1, int(prompt_chars / ratio))
    overhead_tokens = 48
    output_tokens = max(0, int(max_tokens))
    return prompt_tokens + output_tokens + overhead_tokens


def _extract_retry_after_from_body(body_text: str) -> float:
    if not body_text:
        return 0.0
    matched = RETRY_IN_SECONDS_PATTERN.search(body_text)
    if not matched:
        return 0.0
    try:
        return float(matched.group(1))
    except ValueError:
        return 0.0


def _parse_retry_after(*, response: requests.Response) -> float:
    retry_after = 0.0
    retry_after_raw = response.headers.get("Retry-After")
    if retry_after_raw:
        try:
            retry_after = float(retry_after_raw)
        except ValueError:
            retry_after = 0.0
    return max(retry_after, _extract_retry_after_from_body(response.text))


def _resolve_rate_limiter(config: LlmStageConfig) -> _SharedRateLimiter:
    key = (
        f"{config.provider}|{config.base_url.rstrip('/')}|{config.model}|"
        f"{config.min_interval_seconds:.6f}|{config.tokens_per_minute}|{config.requests_per_day}"
    )
    with _RATE_LIMITERS_LOCK:
        limiter = _RATE_LIMITERS.get(key)
        if limiter is None:
            limiter = _SharedRateLimiter(
                min_interval_seconds=config.min_interval_seconds,
                tokens_per_minute=config.tokens_per_minute,
                requests_per_day=config.requests_per_day,
            )
            _RATE_LIMITERS[key] = limiter
        return limiter


def _resolve_concurrency_limiter(config: LlmStageConfig) -> _SharedConcurrencyLimiter:
    key = (
        f"{config.provider}|{config.base_url.rstrip('/')}|{config.model}|{config.concurrency_limit}"
    )
    with _CONCURRENCY_LIMITERS_LOCK:
        limiter = _CONCURRENCY_LIMITERS.get(key)
        if limiter is None:
            limiter = _SharedConcurrencyLimiter(config.concurrency_limit)
            _CONCURRENCY_LIMITERS[key] = limiter
        return limiter


def _default_base_url(provider: str) -> str:
    normalized = _normalize_provider_name(provider)
    if normalized in {"gemini", "gemini-native"}:
        return "https://generativelanguage.googleapis.com/v1beta"
    if normalized == "groq":
        return "https://api.groq.com/openai/v1"
    return "https://api.openai.com/v1"


def _resolve_base_url_for_provider(provider: str) -> str:
    normalized = _normalize_provider_name(provider)
    env_suffix = _provider_env_suffix(normalized)
    return (
        os.getenv(f"QUERY_FORGE_LLM_{env_suffix}_BASE_URL")
        or _default_base_url(normalized)
    ).strip()


def _default_model(provider: str) -> str:
    normalized = _normalize_provider_name(provider)
    if normalized in {"gemini", "gemini-native"}:
        return "gemini-2.5-flash-lite"
    if normalized == "groq":
        return "llama-3.1-8b-instant"
    return "gpt-4o-mini"


def _default_fallback_models(provider: str, model: str) -> tuple[str, ...]:
    normalized = _normalize_provider_name(provider)
    normalized_model = (model or "").strip().lower()
    if normalized in {"gemini", "gemini-native"} and normalized_model == "gemini-2.5-flash-lite":
        return ("gemini-2.5-flash",)
    return ()


def _resolve_api_key_for_provider(provider: str) -> str:
    normalized = _normalize_provider_name(provider)
    env_suffix = _provider_env_suffix(normalized)
    if normalized in {"gemini", "gemini-native"}:
        return str(
            os.getenv(f"QUERY_FORGE_LLM_{env_suffix}_API_KEY")
            or os.getenv("QUERY_FORGE_LLM_GEMINI_API_KEY")
            or os.getenv("GEMINI_API_KEY")
            or ""
        ).strip()
    if normalized == "groq":
        return str(
            os.getenv(f"QUERY_FORGE_LLM_{env_suffix}_API_KEY")
            or os.getenv("GROQ_API_KEY")
            or ""
        ).strip()
    if normalized == "openai":
        return str(
            os.getenv(f"QUERY_FORGE_LLM_{env_suffix}_API_KEY")
            or os.getenv("OPENAI_API_KEY")
            or ""
        ).strip()
    return ""


def _parse_fallback_models(raw_value: Any) -> tuple[str, ...]:
    if isinstance(raw_value, (list, tuple, set)):
        parts = [str(item).strip() for item in raw_value if str(item).strip()]
    elif isinstance(raw_value, str):
        parts = [item.strip() for item in raw_value.split(",") if item.strip()]
    else:
        parts = []
    deduped: list[str] = []
    seen: set[str] = set()
    for item in parts:
        lowered = item.lower()
        if lowered in seen:
            continue
        seen.add(lowered)
        deduped.append(item)
    return tuple(deduped)


def _compute_backoff_seconds(*, config: LlmStageConfig, attempt: int) -> float:
    base = float(config.backoff_initial_seconds) * (float(config.backoff_multiplier) ** max(0, attempt))
    capped = min(float(config.backoff_max_seconds), max(0.05, base))
    jitter_max = capped * max(0.0, float(config.backoff_jitter_ratio))
    jitter = random.uniform(0.0, jitter_max) if jitter_max > 0.0 else 0.0
    return capped + jitter


def _safe_json_body(response: requests.Response) -> dict[str, Any]:
    if not response.content:
        return {}
    try:
        parsed = response.json()
    except ValueError:
        return {}
    return parsed if isinstance(parsed, dict) else {}


def _extract_openai_compatible_text(body: dict[str, Any]) -> str:
    choices = body.get("choices")
    if not isinstance(choices, list) or not choices:
        raise _MalformedLlmResponseError("choices missing in OpenAI-compatible response.")
    first = choices[0] if isinstance(choices[0], dict) else {}
    message = first.get("message")
    if not isinstance(message, dict):
        raise _MalformedLlmResponseError("message missing in OpenAI-compatible response.")
    content = message.get("content")
    if isinstance(content, str) and content.strip():
        return content.strip()
    if isinstance(content, list):
        parts: list[str] = []
        for item in content:
            if isinstance(item, dict):
                text = item.get("text")
                if isinstance(text, str) and text.strip():
                    parts.append(text.strip())
        merged = "\n".join(parts).strip()
        if merged:
            return merged
    raise _MalformedLlmResponseError("OpenAI-compatible content was empty or missing.")


def _extract_gemini_text(body: dict[str, Any]) -> str:
    candidates = body.get("candidates")
    if not isinstance(candidates, list) or not candidates:
        raise _MalformedLlmResponseError("Gemini candidates missing.")
    first = candidates[0] if isinstance(candidates[0], dict) else {}
    content = first.get("content")
    if not isinstance(content, dict):
        raise _MalformedLlmResponseError("Gemini content missing.")
    parts = content.get("parts")
    if not isinstance(parts, list) or not parts:
        raise _MalformedLlmResponseError("Gemini content.parts missing.")
    texts: list[str] = []
    for part in parts:
        if isinstance(part, dict):
            text = part.get("text")
            if isinstance(text, str) and text.strip():
                texts.append(text.strip())
    merged = "\n".join(texts).strip()
    if not merged:
        raise _MalformedLlmResponseError("Gemini response text empty.")
    return merged


def _extract_first_balanced_object(text: str) -> str | None:
    start = text.find("{")
    if start < 0:
        return None
    depth = 0
    in_string = False
    escaping = False
    for index in range(start, len(text)):
        char = text[index]
        if in_string:
            if escaping:
                escaping = False
            elif char == "\\":
                escaping = True
            elif char == "\"":
                in_string = False
            continue
        if char == "\"":
            in_string = True
            continue
        if char == "{":
            depth += 1
            continue
        if char == "}":
            depth -= 1
            if depth == 0:
                return text[start : index + 1]
    return None


def _parse_provider_model_spec(spec: str, *, default_provider: str) -> tuple[str, str]:
    raw = str(spec or "").strip()
    if not raw:
        return "", ""
    normalized_default = _normalize_provider_name(default_provider)
    if ":" not in raw:
        return normalized_default, raw
    prefix, remainder = raw.split(":", 1)
    provider = _normalize_provider_name(prefix)
    model = remainder.strip()
    if provider in SUPPORTED_PROVIDERS and model:
        return provider, model
    return normalized_default, raw


def _validate_against_schema(payload: Any, schema: dict[str, Any] | None) -> None:
    if schema is None:
        return
    errors = _validate_json_schema(payload, schema, path="$")
    if errors:
        raise _SchemaValidationError("; ".join(errors[:5]))


def _validate_json_schema(value: Any, schema: dict[str, Any], *, path: str) -> list[str]:
    schema_type = schema.get("type")
    if isinstance(schema_type, list):
        schema_type = next((item for item in schema_type if item != "null"), schema_type[0] if schema_type else None)
    if value is None:
        if schema.get("nullable") or schema_type in {"null", None}:
            return []
        return [f"{path}: null is not allowed"]

    errors: list[str] = []
    normalized_type = str(schema_type).lower() if isinstance(schema_type, str) else ""
    if normalized_type == "object":
        if not isinstance(value, dict):
            return [f"{path}: expected object but got {type(value).__name__}"]
        properties = schema.get("properties") if isinstance(schema.get("properties"), dict) else {}
        required = schema.get("required") if isinstance(schema.get("required"), list) else []
        for key in required:
            if key not in value:
                errors.append(f"{path}.{key}: required field missing")
        for key, child_schema in properties.items():
            if key not in value or not isinstance(child_schema, dict):
                continue
            errors.extend(_validate_json_schema(value[key], child_schema, path=f"{path}.{key}"))
        if schema.get("additionalProperties") is False:
            allowed = set(properties.keys())
            for key in value.keys():
                if key not in allowed:
                    errors.append(f"{path}.{key}: additional property not allowed")
        return errors

    if normalized_type == "array":
        if not isinstance(value, list):
            return [f"{path}: expected array but got {type(value).__name__}"]
        min_items = schema.get("minItems")
        if isinstance(min_items, int) and len(value) < min_items:
            errors.append(f"{path}: must contain at least {min_items} items")
        item_schema = schema.get("items")
        if isinstance(item_schema, dict):
            for index, item in enumerate(value):
                errors.extend(_validate_json_schema(item, item_schema, path=f"{path}[{index}]"))
        return errors

    if normalized_type == "string":
        if not isinstance(value, str):
            return [f"{path}: expected string but got {type(value).__name__}"]
    elif normalized_type == "integer":
        if not isinstance(value, int) or isinstance(value, bool):
            return [f"{path}: expected integer but got {type(value).__name__}"]
    elif normalized_type == "number":
        if not isinstance(value, (int, float)) or isinstance(value, bool):
            return [f"{path}: expected number but got {type(value).__name__}"]
    elif normalized_type == "boolean":
        if not isinstance(value, bool):
            return [f"{path}: expected boolean but got {type(value).__name__}"]

    enum_values = schema.get("enum")
    if isinstance(enum_values, list) and enum_values and value not in enum_values:
        return [f"{path}: value '{value}' is not in enum"]
    return errors


def _to_gemini_schema(schema: dict[str, Any]) -> dict[str, Any]:
    mapped: dict[str, Any] = {}
    type_value = schema.get("type")
    if isinstance(type_value, list):
        type_value = next((item for item in type_value if item != "null"), type_value[0] if type_value else None)
    if isinstance(type_value, str) and type_value:
        mapped["type"] = type_value.upper()
    for key in ("description", "format", "enum", "nullable", "required"):
        if key in schema:
            mapped[key] = schema[key]
    if isinstance(schema.get("properties"), dict):
        mapped["properties"] = {
            name: _to_gemini_schema(prop)
            for name, prop in schema["properties"].items()
            if isinstance(prop, dict)
        }
    if isinstance(schema.get("items"), dict):
        mapped["items"] = _to_gemini_schema(schema["items"])
    return mapped


def _prompt_fingerprint(system_prompt: str, user_prompt: str) -> str:
    merged = f"{system_prompt}\n{user_prompt}"
    digest = hashlib.sha256(merged.encode("utf-8")).hexdigest()[:12]
    return f"sha256:{digest}:len={len(merged)}"
