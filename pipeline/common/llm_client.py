from __future__ import annotations

import json
import os
import re
import time
from dataclasses import dataclass
from typing import Any

import requests


JSON_BLOCK_PATTERN = re.compile(r"\{.*\}", re.DOTALL)


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


class LlmClient:
    def __init__(self, config: LlmStageConfig) -> None:
        self.config = config
        self._last_call_at = 0.0

    def chat_json(self, *, system_prompt: str, user_prompt: str) -> dict[str, Any]:
        if self.config.provider == "mock":
            return {"mock": True}
        payload = {
            "model": self.config.model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "temperature": self.config.temperature,
            "max_tokens": self.config.max_tokens,
            "response_format": {"type": "json_object"},
        }
        max_attempts = int(os.getenv("QUERY_FORGE_LLM_MAX_RETRIES") or "6")
        max_attempts = max(1, min(max_attempts, 12))
        last_message = ""
        for attempt in range(max_attempts):
            self._throttle()
            try:
                response = requests.post(
                    f"{self.config.base_url.rstrip('/')}/chat/completions",
                    headers={
                        "Authorization": f"Bearer {self.config.api_key}",
                        "Content-Type": "application/json",
                    },
                    json=payload,
                    timeout=self.config.timeout_seconds,
                )
            except requests.RequestException:
                if attempt + 1 >= max_attempts:
                    raise
                time.sleep(min(30.0, 1.5 * (2 ** attempt)))
                continue

            if response.status_code in {429, 500, 502, 503, 504}:
                if attempt + 1 >= max_attempts:
                    response.raise_for_status()
                retry_after_raw = response.headers.get("Retry-After")
                retry_after = 0.0
                if retry_after_raw:
                    try:
                        retry_after = float(retry_after_raw)
                    except ValueError:
                        retry_after = 0.0
                backoff = min(45.0, 2.0 * (2 ** attempt))
                time.sleep(max(retry_after, backoff))
                continue

            response.raise_for_status()
            body = response.json()
            message = (
                body.get("choices", [{}])[0]
                .get("message", {})
                .get("content", "")
            )
            last_message = str(message or "")
            try:
                parsed = _parse_json_text(message)
            except Exception:
                if attempt + 1 >= max_attempts:
                    break
                time.sleep(min(20.0, 1.0 * (2 ** attempt)))
                continue
            if not isinstance(parsed, dict):
                if attempt + 1 >= max_attempts:
                    break
                time.sleep(min(20.0, 1.0 * (2 ** attempt)))
                continue
            return parsed

        fallback_text = (last_message or "").strip()
        if fallback_text:
            return {"raw_text": fallback_text}
        raise RuntimeError("LLM request failed after retries.")

    def _throttle(self) -> None:
        wait_seconds = self.config.min_interval_seconds - (time.monotonic() - self._last_call_at)
        if wait_seconds > 0:
            time.sleep(wait_seconds)
        self._last_call_at = time.monotonic()


def _pick(raw: dict[str, Any], *keys: str) -> Any:
    for key in keys:
        if key in raw and raw[key] is not None:
            return raw[key]
    return None


def load_stage_config(
    *,
    stage: str,
    raw_config: dict[str, Any] | None = None,
) -> LlmStageConfig:
    raw_config = raw_config or {}
    llm_block = raw_config.get("llm") if isinstance(raw_config.get("llm"), dict) else {}
    env_stage = stage.upper()
    provider = str(
        _pick(
            raw_config,
            f"llm_{stage}_provider",
            "llm_provider",
        )
        or _pick(llm_block, f"{stage}_provider", "provider")
        or os.getenv(f"QUERY_FORGE_LLM_{env_stage}_PROVIDER")
        or os.getenv("QUERY_FORGE_LLM_PROVIDER")
        or "groq"
    ).strip().lower()

    default_base_url = "https://api.groq.com/openai/v1" if provider == "groq" else "https://api.openai.com/v1"
    base_url = str(
        _pick(raw_config, f"llm_{stage}_base_url", "llm_base_url")
        or _pick(llm_block, f"{stage}_base_url", "base_url")
        or os.getenv(f"QUERY_FORGE_LLM_{env_stage}_BASE_URL")
        or os.getenv("QUERY_FORGE_LLM_BASE_URL")
        or default_base_url
    ).strip()

    default_model = "llama-3.1-8b-instant" if provider == "groq" else "gpt-4o-mini"
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
        or os.getenv("GROQ_API_KEY")
        or os.getenv("OPENAI_API_KEY")
        or ""
    ).strip()

    if provider != "mock" and not api_key:
        raise RuntimeError(
            f"LLM API key is required for provider={provider}. "
            "Set QUERY_FORGE_LLM_API_KEY or GROQ_API_KEY."
        )

    temperature = float(
        _pick(raw_config, f"llm_{stage}_temperature", "llm_temperature")
        or _pick(llm_block, f"{stage}_temperature", "temperature")
        or os.getenv(f"QUERY_FORGE_LLM_{env_stage}_TEMPERATURE")
        or os.getenv("QUERY_FORGE_LLM_TEMPERATURE")
        or 0.2
    )
    max_tokens = int(
        _pick(raw_config, f"llm_{stage}_max_tokens", "llm_max_tokens")
        or _pick(llm_block, f"{stage}_max_tokens", "max_tokens")
        or os.getenv(f"QUERY_FORGE_LLM_{env_stage}_MAX_TOKENS")
        or os.getenv("QUERY_FORGE_LLM_MAX_TOKENS")
        or 512
    )
    timeout_seconds = float(
        _pick(raw_config, f"llm_{stage}_timeout_seconds", "llm_timeout_seconds")
        or _pick(llm_block, f"{stage}_timeout_seconds", "timeout_seconds")
        or os.getenv(f"QUERY_FORGE_LLM_{env_stage}_TIMEOUT_SECONDS")
        or os.getenv("QUERY_FORGE_LLM_TIMEOUT_SECONDS")
        or 45.0
    )
    requests_per_minute = float(
        _pick(raw_config, f"llm_{stage}_rpm", "llm_rpm")
        or _pick(llm_block, f"{stage}_rpm", "rpm")
        or os.getenv(f"QUERY_FORGE_LLM_{env_stage}_RPM")
        or os.getenv("QUERY_FORGE_LLM_RPM")
        or 20.0
    )
    min_interval = 0.0 if requests_per_minute <= 0 else 60.0 / requests_per_minute
    return LlmStageConfig(
        provider=provider,
        base_url=base_url,
        api_key=api_key,
        model=model,
        temperature=temperature,
        max_tokens=max_tokens,
        timeout_seconds=timeout_seconds,
        min_interval_seconds=min_interval,
    )


def _parse_json_text(text: str) -> dict[str, Any] | list[Any]:
    stripped = (text or "").strip()
    if not stripped:
        raise ValueError("LLM returned empty response.")
    try:
        return json.loads(stripped)
    except json.JSONDecodeError:
        matched = JSON_BLOCK_PATTERN.search(stripped)
        if not matched:
            raise
        return json.loads(matched.group(0))
