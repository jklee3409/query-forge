from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import requests

try:
    from common.llm_client import (
        LlmStageConfig,
        _extract_gemini_text,
        _parse_json_text,
        _to_gemini_schema,
        _validate_against_schema,
    )
except ModuleNotFoundError:  # pragma: no cover - direct module execution fallback
    from pipeline.common.llm_client import (
        LlmStageConfig,
        _extract_gemini_text,
        _parse_json_text,
        _to_gemini_schema,
        _validate_against_schema,
    )


LOGGER = logging.getLogger(__name__)

TERMINAL_STATES = {
    "JOB_STATE_SUCCEEDED",
    "JOB_STATE_FAILED",
    "JOB_STATE_CANCELLED",
    "JOB_STATE_EXPIRED",
    "BATCH_STATE_SUCCEEDED",
    "BATCH_STATE_FAILED",
    "BATCH_STATE_CANCELLED",
    "BATCH_STATE_EXPIRED",
}
SUCCESS_STATES = {"JOB_STATE_SUCCEEDED", "BATCH_STATE_SUCCEEDED"}
FAILED_STATES = TERMINAL_STATES - SUCCESS_STATES


@dataclass(frozen=True, slots=True)
class GeminiBatchRequestItem:
    key: str
    request: dict[str, Any]
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class GeminiBatchJob:
    name: str
    state: str
    raw: dict[str, Any]
    batch_stats: dict[str, int] = field(default_factory=dict)
    responses_file: str | None = None
    inlined_responses: list[dict[str, Any]] = field(default_factory=list)

    @property
    def is_terminal(self) -> bool:
        return self.state in TERMINAL_STATES

    @property
    def succeeded(self) -> bool:
        return self.state in SUCCESS_STATES


@dataclass(frozen=True, slots=True)
class GeminiBatchResult:
    key: str
    metadata: dict[str, Any]
    response: dict[str, Any] | None
    error: dict[str, Any] | None
    raw: dict[str, Any]
    usage_metadata: dict[str, Any] = field(default_factory=dict)

    @property
    def succeeded(self) -> bool:
        return self.response is not None and self.error is None


class GeminiBatchExecutionError(RuntimeError):
    def __init__(
        self,
        message: str,
        *,
        job: GeminiBatchJob | None = None,
        failures: list[dict[str, Any]] | None = None,
    ) -> None:
        super().__init__(message)
        self.job = job
        self.failures = failures or []


def build_gemini_generate_content_request(
    config: LlmStageConfig,
    *,
    system_prompt: str,
    user_prompt: str,
    response_schema: dict[str, Any] | None,
) -> dict[str, Any]:
    generation_config: dict[str, Any] = {
        "temperature": config.temperature,
        "maxOutputTokens": config.max_tokens,
        "responseMimeType": "application/json",
        "responseSchema": _to_gemini_schema(
            response_schema
            or {
                "type": "object",
                "additionalProperties": True,
            }
        ),
    }
    if config.thinking_budget is not None:
        generation_config["thinkingConfig"] = {"thinkingBudget": config.thinking_budget}
    return {
        "systemInstruction": {"parts": [{"text": system_prompt}]},
        "contents": [{"role": "user", "parts": [{"text": user_prompt}]}],
        "generationConfig": generation_config,
    }


def parse_gemini_json_response(
    response_body: dict[str, Any],
    *,
    response_schema: dict[str, Any] | None,
) -> dict[str, Any]:
    text = _extract_gemini_text(response_body)
    parsed = _parse_json_text(
        text,
        allow_markdown_fallback=True,
        allow_object_extraction=response_schema is None,
    )
    if not isinstance(parsed, dict):
        raise ValueError("Gemini batch JSON response must be an object.")
    _validate_against_schema(parsed, response_schema)
    return parsed


def usage_metadata_from_response(response_body: dict[str, Any] | None) -> dict[str, Any]:
    if not isinstance(response_body, dict):
        return {}
    usage = response_body.get("usageMetadata")
    if not isinstance(usage, dict):
        return {}
    normalized: dict[str, Any] = {}
    for key, value in usage.items():
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            normalized[key] = int(value)
        else:
            normalized[key] = value
    return normalized


class GeminiBatchAdapter:
    def __init__(
        self,
        *,
        base_url: str,
        api_key: str,
        timeout_seconds: float = 60.0,
        session: Any | None = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.timeout_seconds = float(timeout_seconds)
        self.session = session or requests

    def submit_inline(
        self,
        *,
        model: str,
        items: list[GeminiBatchRequestItem],
        display_name: str,
    ) -> GeminiBatchJob:
        payload = {
            "batch": {
                "display_name": display_name,
                "input_config": {
                    "requests": {
                        "requests": [
                            {
                                "request": item.request,
                                "metadata": {"key": item.key, **item.metadata},
                            }
                            for item in items
                        ]
                    }
                },
            }
        }
        return self._submit_batch(model=model, payload=payload)

    def submit_jsonl_file(
        self,
        *,
        model: str,
        file_name: str,
        display_name: str,
    ) -> GeminiBatchJob:
        payload = {
            "batch": {
                "display_name": display_name,
                "input_config": {
                    "file_name": file_name,
                },
            }
        }
        return self._submit_batch(model=model, payload=payload)

    def submit_jsonl(
        self,
        *,
        model: str,
        items: list[GeminiBatchRequestItem],
        display_name: str,
        jsonl_path: Path,
    ) -> GeminiBatchJob:
        self.write_jsonl(items=items, jsonl_path=jsonl_path)
        uploaded_file_name = self.upload_jsonl_file(jsonl_path=jsonl_path, display_name=display_name)
        return self.submit_jsonl_file(
            model=model,
            file_name=uploaded_file_name,
            display_name=display_name,
        )

    def write_jsonl(self, *, items: list[GeminiBatchRequestItem], jsonl_path: Path) -> None:
        jsonl_path.parent.mkdir(parents=True, exist_ok=True)
        with jsonl_path.open("w", encoding="utf-8", newline="\n") as handle:
            for item in items:
                handle.write(
                    json.dumps(
                        {
                            "key": item.key,
                            "request": item.request,
                        },
                        ensure_ascii=False,
                        separators=(",", ":"),
                    )
                    + "\n"
                )

    def upload_jsonl_file(self, *, jsonl_path: Path, display_name: str) -> str:
        data = jsonl_path.read_bytes()
        start_response = self.session.post(
            f"{self._api_origin()}/upload/v1beta/files",
            headers={
                "x-goog-api-key": self.api_key,
                "X-Goog-Upload-Protocol": "resumable",
                "X-Goog-Upload-Command": "start",
                "X-Goog-Upload-Header-Content-Length": str(len(data)),
                "X-Goog-Upload-Header-Content-Type": "application/jsonl",
                "Content-Type": "application/json",
            },
            json={"file": {"display_name": display_name}},
            timeout=self.timeout_seconds,
        )
        start_response.raise_for_status()
        upload_url = _header_lookup(start_response.headers, "x-goog-upload-url")
        if not upload_url:
            raise GeminiBatchExecutionError("Gemini File API did not return an upload URL.")
        upload_response = self.session.post(
            upload_url,
            headers={
                "Content-Length": str(len(data)),
                "X-Goog-Upload-Offset": "0",
                "X-Goog-Upload-Command": "upload, finalize",
            },
            data=data,
            timeout=self.timeout_seconds,
        )
        upload_response.raise_for_status()
        body = _safe_json(upload_response)
        file_body = body.get("file") if isinstance(body.get("file"), dict) else {}
        file_name = str(file_body.get("name") or "").strip()
        if not file_name:
            raise GeminiBatchExecutionError("Gemini File API upload response did not include file.name.")
        return file_name

    def get_job(self, *, name: str) -> GeminiBatchJob:
        response = self.session.get(
            f"{self.base_url}/{name.lstrip('/')}",
            headers={"x-goog-api-key": self.api_key, "Content-Type": "application/json"},
            timeout=self.timeout_seconds,
        )
        response.raise_for_status()
        return parse_batch_job(_safe_json(response))

    def poll_job(
        self,
        *,
        name: str,
        poll_interval_seconds: float,
        timeout_seconds: float,
    ) -> GeminiBatchJob:
        deadline = time.monotonic() + max(1.0, float(timeout_seconds))
        poll_interval = max(0.1, float(poll_interval_seconds))
        while True:
            job = self.get_job(name=name)
            if job.is_terminal:
                return job
            if time.monotonic() >= deadline:
                raise GeminiBatchExecutionError(
                    f"Gemini batch job timed out before completion: {name}",
                    job=job,
                )
            time.sleep(poll_interval)

    def fetch_results(
        self,
        *,
        job: GeminiBatchJob,
        expected_items: list[GeminiBatchRequestItem],
    ) -> list[GeminiBatchResult]:
        expected_by_index = list(expected_items)
        if job.inlined_responses:
            return [
                parse_batch_result(raw, fallback_key=expected_by_index[index].key if index < len(expected_by_index) else "")
                for index, raw in enumerate(job.inlined_responses)
            ]
        if job.responses_file:
            content = self.download_file(file_name=job.responses_file)
            return [
                parse_batch_result(raw, fallback_key=expected_by_index[index].key if index < len(expected_by_index) else "")
                for index, raw in enumerate(iter_jsonl_objects(content))
            ]
        return []

    def download_file(self, *, file_name: str) -> str:
        response = self.session.get(
            f"{self._api_origin()}/download/v1beta/{file_name.lstrip('/')}:download",
            params={"alt": "media"},
            headers={"x-goog-api-key": self.api_key},
            timeout=self.timeout_seconds,
        )
        response.raise_for_status()
        return response.content.decode("utf-8")

    def _submit_batch(self, *, model: str, payload: dict[str, Any]) -> GeminiBatchJob:
        normalized_model = model.removeprefix("models/")
        response = self.session.post(
            f"{self.base_url}/models/{normalized_model}:batchGenerateContent",
            headers={"x-goog-api-key": self.api_key, "Content-Type": "application/json"},
            json=payload,
            timeout=self.timeout_seconds,
        )
        response.raise_for_status()
        return parse_batch_job(_safe_json(response))

    def _api_origin(self) -> str:
        for suffix in ("/v1beta", "/v1"):
            if self.base_url.endswith(suffix):
                return self.base_url[: -len(suffix)]
        return self.base_url


def parse_batch_job(raw: dict[str, Any]) -> GeminiBatchJob:
    name = str(raw.get("name") or raw.get("batchName") or raw.get("batch_name") or "").strip()
    metadata = raw.get("metadata") if isinstance(raw.get("metadata"), dict) else {}
    if not name:
        name = str(metadata.get("name") or metadata.get("batch") or "").strip()
    state = _extract_state(raw, metadata)
    output = _extract_output(raw, metadata)
    stats = _extract_batch_stats(raw, metadata)
    return GeminiBatchJob(
        name=name,
        state=state,
        raw=raw,
        batch_stats=stats,
        responses_file=_extract_responses_file(output),
        inlined_responses=_extract_inlined_responses(output),
    )


def parse_batch_result(raw: dict[str, Any], *, fallback_key: str = "") -> GeminiBatchResult:
    metadata = raw.get("metadata") if isinstance(raw.get("metadata"), dict) else {}
    key = str(raw.get("key") or metadata.get("key") or fallback_key or "").strip()
    response = raw.get("response") if isinstance(raw.get("response"), dict) else None
    if response is None and isinstance(raw.get("output"), dict):
        output = raw["output"]
        response = output.get("response") if isinstance(output.get("response"), dict) else None
    error = raw.get("error") if isinstance(raw.get("error"), dict) else None
    if error is None and isinstance(raw.get("output"), dict):
        output = raw["output"]
        error = output.get("error") if isinstance(output.get("error"), dict) else None
    if response is None and error is None and ("candidates" in raw or "promptFeedback" in raw):
        response = raw
    return GeminiBatchResult(
        key=key,
        metadata=metadata,
        response=response,
        error=error,
        raw=raw,
        usage_metadata=usage_metadata_from_response(response),
    )


def iter_jsonl_objects(content: str) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for line in str(content or "").splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        parsed = json.loads(stripped)
        if isinstance(parsed, dict):
            rows.append(parsed)
    return rows


def _safe_json(response: Any) -> dict[str, Any]:
    try:
        parsed = response.json()
    except ValueError:
        return {}
    return parsed if isinstance(parsed, dict) else {}


def _header_lookup(headers: Any, name: str) -> str | None:
    target = name.lower()
    for key, value in dict(headers or {}).items():
        if str(key).lower() == target:
            return str(value).strip()
    return None


def _extract_state(raw: dict[str, Any], metadata: dict[str, Any]) -> str:
    state = str(raw.get("state") or metadata.get("state") or "").strip()
    if state:
        return state
    done = raw.get("done")
    if done is True:
        return "JOB_STATE_FAILED" if raw.get("error") else "JOB_STATE_SUCCEEDED"
    return "JOB_STATE_PENDING"


def _extract_output(raw: dict[str, Any], metadata: dict[str, Any]) -> dict[str, Any]:
    for key in ("output", "dest", "destination"):
        value = raw.get(key)
        if isinstance(value, dict):
            return value
    metadata_output = metadata.get("output") or metadata.get("dest")
    return metadata_output if isinstance(metadata_output, dict) else {}


def _extract_batch_stats(raw: dict[str, Any], metadata: dict[str, Any]) -> dict[str, int]:
    stats = raw.get("batchStats") or raw.get("batch_stats") or metadata.get("batchStats") or metadata.get("batch_stats")
    if not isinstance(stats, dict):
        return {}
    mapping = {
        "request_count": stats.get("requestCount") or stats.get("request_count"),
        "successful_request_count": stats.get("successfulRequestCount") or stats.get("successful_request_count"),
        "failed_request_count": stats.get("failedRequestCount") or stats.get("failed_request_count"),
        "pending_request_count": stats.get("pendingRequestCount") or stats.get("pending_request_count"),
    }
    normalized: dict[str, int] = {}
    for key, value in mapping.items():
        try:
            normalized[key] = int(value)
        except (TypeError, ValueError):
            continue
    return normalized


def _extract_responses_file(output: dict[str, Any]) -> str | None:
    value = (
        output.get("responsesFile")
        or output.get("responses_file")
        or output.get("fileName")
        or output.get("file_name")
    )
    text = str(value or "").strip()
    return text or None


def _extract_inlined_responses(output: dict[str, Any]) -> list[dict[str, Any]]:
    value = output.get("inlinedResponses") or output.get("inlined_responses")
    if isinstance(value, list):
        return [item for item in value if isinstance(item, dict)]
    if isinstance(value, dict):
        nested = value.get("inlinedResponses") or value.get("inlined_responses")
        if isinstance(nested, list):
            return [item for item in nested if isinstance(item, dict)]
    return []
