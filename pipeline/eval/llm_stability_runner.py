from __future__ import annotations

import argparse
import csv
import json
import logging
import os
import random
import re
import statistics
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, replace
from datetime import datetime
from pathlib import Path
from typing import Any
from unittest.mock import patch

import requests

try:
    from common.llm_client import LlmClient, LlmStageConfig, load_stage_config
except ModuleNotFoundError:  # pragma: no cover
    from pipeline.common.llm_client import LlmClient, LlmStageConfig, load_stage_config


LOGGER = logging.getLogger(__name__)
LLM_LOGGER_NAME = "pipeline.common.llm_client"
DEFAULT_OUTPUT_ROOT = Path("data/eval/llm_stability")

ANCHOR_TERMS = [
    "@configurationproperties",
    "@enableconfigurationproperties",
    "@transactional",
    "@bean",
    "@controller",
    "@restcontroller",
    "@configuration",
    "auto-configuration",
    "configuration property",
    "actuator",
    "health endpoint",
    "binding",
    "constructorbinding",
    "securityfilterchain",
    "mockmvc",
    "webmvctest",
    "datasource",
    "transactionmanager",
    "configurationproperties",
]

GENERIC_PATTERNS = [
    r"^.*뭐.*$",
    r"^.*무엇.*$",
    r"^.*설명해.*$",
    r"^.*알려줘.*$",
    r"^.*왜 안돼.*$",
]


@dataclass(slots=True)
class StrategySpec:
    strategy: str
    prompt_path: Path
    response_schema: dict[str, Any]
    required_keys: tuple[str, ...]
    eval_keys: tuple[str, ...]


@dataclass(slots=True)
class RequestCase:
    case_id: str
    phase: str
    provider_label: str
    strategy: str
    query_type: str
    answerability_type: str


class TraceLogCollector(logging.Handler):
    def __init__(self) -> None:
        super().__init__()
        self._lock = threading.Lock()
        self._by_trace: dict[str, list[str]] = {}

    def emit(self, record: logging.LogRecord) -> None:
        message = record.getMessage()
        matched = re.search(r"trace_id=([^\s]+)", message)
        if not matched:
            return
        trace_id = matched.group(1)
        with self._lock:
            self._by_trace.setdefault(trace_id, []).append(message)

    def take(self, trace_id: str) -> list[str]:
        with self._lock:
            return list(self._by_trace.get(trace_id, []))


class _FakeResponse:
    def __init__(
        self,
        *,
        status_code: int,
        body: dict[str, Any] | None = None,
        text: str | None = None,
        headers: dict[str, str] | None = None,
    ) -> None:
        self.status_code = status_code
        self._body = body
        self.headers = headers or {}
        if text is None and body is not None:
            text = json.dumps(body, ensure_ascii=False)
        self.text = text or ""
        self.content = self.text.encode("utf-8") if self.text else b""

    def json(self) -> dict[str, Any]:
        if self._body is None:
            raise ValueError("not json")
        return self._body

    def raise_for_status(self) -> None:
        if self.status_code >= 400:
            raise requests.HTTPError(f"HTTP {self.status_code}", response=self)


def _load_env_file(path: Path) -> None:
    if not path.exists():
        return
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if key:
            os.environ.setdefault(key, value.strip())


def _load_prompt(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _strategy_specs(prompt_root: Path) -> dict[str, StrategySpec]:
    return {
        "A": StrategySpec(
            strategy="A",
            prompt_path=prompt_root / "query_generation" / "gen_a_v1.md",
            response_schema={
                "type": "object",
                "required": ["query_en", "query_ko", "query_type", "answerability_type"],
                "properties": {
                    "query_en": {"type": "string"},
                    "query_ko": {"type": "string"},
                    "query_type": {"type": "string"},
                    "answerability_type": {"type": "string"},
                },
            },
            required_keys=("query_en", "query_ko", "query_type", "answerability_type"),
            eval_keys=("query_ko",),
        ),
        "B": StrategySpec(
            strategy="B",
            prompt_path=prompt_root / "query_generation" / "gen_b_v1.md",
            response_schema={
                "type": "object",
                "required": ["translated_chunk_ko", "summary_ko", "query_ko", "query_type", "answerability_type"],
                "properties": {
                    "translated_chunk_ko": {"type": "string"},
                    "summary_ko": {"type": "string"},
                    "query_ko": {"type": "string"},
                    "query_type": {"type": "string"},
                    "answerability_type": {"type": "string"},
                },
            },
            required_keys=("translated_chunk_ko", "summary_ko", "query_ko", "query_type", "answerability_type"),
            eval_keys=("query_ko",),
        ),
        "C": StrategySpec(
            strategy="C",
            prompt_path=prompt_root / "query_generation" / "gen_c_v1.md",
            response_schema={
                "type": "object",
                "required": ["query_ko", "query_type", "answerability_type", "style_note"],
                "properties": {
                    "query_ko": {"type": "string"},
                    "query_type": {"type": "string"},
                    "answerability_type": {"type": "string"},
                    "style_note": {"type": "string"},
                },
            },
            required_keys=("query_ko", "query_type", "answerability_type", "style_note"),
            eval_keys=("query_ko",),
        ),
        "D": StrategySpec(
            strategy="D",
            prompt_path=prompt_root / "query_generation" / "gen_d_v1.md",
            response_schema={
                "type": "object",
                "required": ["query_ko", "query_code_mixed", "query_type", "answerability_type"],
                "properties": {
                    "query_ko": {"type": "string"},
                    "query_code_mixed": {"type": "string"},
                    "query_type": {"type": "string"},
                    "answerability_type": {"type": "string"},
                },
            },
            required_keys=("query_ko", "query_code_mixed", "query_type", "answerability_type"),
            eval_keys=("query_ko", "query_code_mixed"),
        ),
    }


def _sample_payload(strategy: str, query_type: str, answerability_type: str) -> dict[str, Any]:
    chunk_text = (
        "Spring Boot supports @ConfigurationProperties for binding externalized configuration to typed beans. "
        "When validation is enabled and binding fails, startup can fail with clear diagnostics. "
        "You can expose actuator endpoints for health/info and tune security access rules."
    )
    summary_en = (
        "Use @ConfigurationProperties for type-safe config binding, validate inputs, and inspect runtime state with actuator."
    )
    summary_ko = (
        "@ConfigurationProperties로 설정 값을 타입 안전하게 바인딩하고, validation 실패 시 원인을 확인하며, actuator로 상태를 점검한다."
    )
    translated_ko = (
        "Spring Boot는 @ConfigurationProperties를 통해 외부 설정을 타입이 있는 Bean에 바인딩한다. "
        "validation이 켜져 있으면 바인딩 실패 시 시작 단계에서 오류를 확인할 수 있다. "
        "actuator endpoint로 health/info를 노출하고 보안 접근 정책을 조정할 수 있다."
    )
    return {
        "strategy": strategy,
        "chunk_id": "chk_eval_sample_1",
        "document_id": "doc_eval_sample_1",
        "title": "Configuration Binding and Actuator",
        "product": "Spring Boot",
        "version": "3.x",
        "original_chunk_en": chunk_text,
        "chunk_text_en": chunk_text,
        "extractive_summary_en": summary_en,
        "extractive_summary_ko": summary_ko,
        "translated_chunk_ko": translated_ko,
        "summary_ko": summary_ko,
        "glossary_terms_keep_english": [
            "@ConfigurationProperties",
            "actuator",
            "configuration binding",
            "validation",
            "SecurityFilterChain",
        ],
        "query_type": query_type,
        "answerability_type": answerability_type,
        "target_chunk_ids": ["chk_eval_sample_1", "chk_eval_sample_2"],
    }


def _contains_korean(text: str) -> bool:
    return bool(re.search(r"[가-힣]", text or ""))


def _contains_english_token(text: str) -> bool:
    return bool(re.search(r"[A-Za-z]{2,}", text or ""))


def _anchor_present(text: str) -> bool:
    lowered = (text or "").lower()
    return any(term in lowered for term in ANCHOR_TERMS)


def _is_generic(text: str) -> bool:
    normalized = (text or "").strip()
    if len(normalized) < 10:
        return True
    if _anchor_present(normalized):
        return False
    for pattern in GENERIC_PATTERNS:
        if re.match(pattern, normalized):
            return True
    return len(normalized) < 20


def _style_match(query_type: str, text: str) -> bool:
    lowered = (text or "").lower()
    if query_type == "definition":
        return any(token in lowered for token in ("무엇", "뜻", "개념", "의미"))
    if query_type == "reason":
        return any(token in lowered for token in ("왜", "원인", "이유"))
    if query_type == "procedure":
        return any(token in lowered for token in ("어떻게", "방법", "절차", "설정", "적용"))
    if query_type == "comparison":
        return any(token in lowered for token in ("차이", "비교", "vs", "대신"))
    if query_type == "short_user":
        return 8 <= len(text or "") <= 45
    if query_type == "code_mixed":
        return _contains_korean(text) and _contains_english_token(text)
    if query_type == "follow_up":
        return any(token in lowered for token in ("그럼", "그러면", "이 경우", "그 상태"))
    return True


def _build_case_matrix(
    *,
    phase: str,
    provider_label: str,
    count: int,
    strategies: list[str],
    query_types: list[str],
    answerability_types: list[str],
    seed: int,
) -> list[RequestCase]:
    rng = random.Random(seed)
    cases: list[RequestCase] = []
    for index in range(count):
        strategy = rng.choice(strategies)
        query_type = rng.choice(query_types)
        answerability_type = answerability_types[index % len(answerability_types)]
        cases.append(
            RequestCase(
                case_id=f"{phase}-{provider_label}-{index + 1:03d}",
                phase=phase,
                provider_label=provider_label,
                strategy=strategy,
                query_type=query_type,
                answerability_type=answerability_type,
            )
        )
    return cases


def _extract_retry_signals(logs: list[str]) -> dict[str, Any]:
    retry_reasons: list[str] = []
    had_503 = False
    fallback_logged = False
    for line in logs:
        if "llm_retry reason=" in line:
            matched = re.search(r"reason=([^\s]+)", line)
            if matched:
                reason = matched.group(1)
                retry_reasons.append(reason)
                if reason == "http_503":
                    had_503 = True
        if "llm_fallback_success" in line:
            fallback_logged = True
    return {
        "retry_events": len(retry_reasons),
        "retry_reasons": retry_reasons,
        "had_503": had_503,
        "fallback_logged": fallback_logged,
    }


def _validate_required(spec: StrategySpec, payload: dict[str, Any]) -> bool:
    for key in spec.required_keys:
        value = payload.get(key)
        if not isinstance(value, str) or not value.strip():
            return False
    return True


def _collect_texts(spec: StrategySpec, payload: dict[str, Any]) -> list[str]:
    rows: list[str] = []
    for key in spec.eval_keys:
        value = payload.get(key)
        if isinstance(value, str) and value.strip():
            rows.append(value.strip())
    return rows


def _classify_error(exc: Exception, *, status_code: int | None, response_text: str) -> str:
    if status_code == 503:
        return "http_503"
    if status_code == 429:
        return "http_429"
    if status_code is not None and 500 <= status_code <= 599:
        return "http_5xx"
    if status_code is not None and 400 <= status_code <= 499:
        return "http_4xx"

    combined = f"{type(exc).__name__}:{exc}:{response_text}".lower()
    if "jsondecodeerror" in combined or "parse_" in combined:
        return "malformed_json"
    if "schema" in combined:
        return "schema_mismatch"
    if "empty" in combined or "parts missing" in combined or "content missing" in combined:
        return "empty_response"
    if "timeout" in combined:
        return "timeout"
    return "unknown_error"


def _compute_length_distribution(lengths: list[float]) -> dict[str, int]:
    distribution = {"1_20": 0, "21_40": 0, "41_80": 0, "81_plus": 0}
    for value in lengths:
        if value <= 20:
            distribution["1_20"] += 1
        elif value <= 40:
            distribution["21_40"] += 1
        elif value <= 80:
            distribution["41_80"] += 1
        else:
            distribution["81_plus"] += 1
    return distribution


def _error_distribution(rows: list[dict[str, Any]]) -> dict[str, int]:
    counter: dict[str, int] = {}
    for row in rows:
        key = str(row.get("error_category") or "unknown_error")
        counter[key] = counter.get(key, 0) + 1
    return counter


def _gemini_native_validation(rows: list[dict[str, Any]]) -> dict[str, Any]:
    native_rows = [row for row in rows if row["provider_label"] == "gemini-native"]
    success_rows = [row for row in native_rows if row["success"]]
    provider_ok = all(row.get("provider_type") == "gemini-native" for row in success_rows) if success_rows else False
    structured_ok = all(bool(row.get("structured_output_used")) for row in success_rows) if success_rows else False
    parse_ok = all(bool(row.get("required_ok")) for row in success_rows) if success_rows else False
    return {
        "total": len(native_rows),
        "success": len(success_rows),
        "provider_is_gemini_native": provider_ok,
        "structured_output_used": structured_ok,
        "schema_parse_ok": parse_ok,
        "structured_output_true_rate_pct": _ratio(
            sum(1 for row in success_rows if row.get("structured_output_used")),
            len(success_rows),
        ),
    }


def _call_case(
    *,
    case: RequestCase,
    client: LlmClient,
    spec: StrategySpec,
    prompt_text: str,
    collector: TraceLogCollector,
) -> dict[str, Any]:
    trace_id = f"eval:{case.case_id}"
    started_at = time.perf_counter()
    payload = _sample_payload(case.strategy, case.query_type, case.answerability_type)
    try:
        response = client.chat_json(
            system_prompt=prompt_text,
            user_prompt=json.dumps(payload, ensure_ascii=False),
            response_schema=spec.response_schema,
            request_purpose=f"stability_eval_{case.phase}",
            trace_id=trace_id,
        )
        elapsed_ms = (time.perf_counter() - started_at) * 1000.0
        meta = response.get("_llm_meta") if isinstance(response.get("_llm_meta"), dict) else {}
        required_ok = _validate_required(spec, response)
        echo_ok = (
            str(response.get("query_type") or "") == case.query_type
            and str(response.get("answerability_type") or "") == case.answerability_type
        )
        texts = _collect_texts(spec, response)
        representative = texts[0] if texts else ""
        avg_len = statistics.mean([len(item) for item in texts]) if texts else 0.0
        signals = _extract_retry_signals(collector.take(trace_id))
        return {
            "case_id": case.case_id,
            "phase": case.phase,
            "provider_label": case.provider_label,
            "strategy": case.strategy,
            "query_type_input": case.query_type,
            "answerability_type_input": case.answerability_type,
            "success": True,
            "latency_ms": round(elapsed_ms, 3),
            "http_status": 200,
            "error_type": "",
            "error_category": "",
            "error_message": "",
            "error_body_preview": "",
            "required_ok": required_ok,
            "echo_ok": echo_ok,
            "query_length_avg": round(avg_len, 2),
            "anchor_present": any(_anchor_present(item) for item in texts),
            "generic_question": _is_generic(representative),
            "style_match": _style_match(case.query_type, representative),
            "query_preview": representative[:200],
            "provider": meta.get("provider"),
            "provider_type": meta.get("provider_type"),
            "structured_output_used": bool(meta.get("structured_output_used")),
            "thinking_budget": meta.get("thinking_budget"),
            "retry_count_meta": int(meta.get("retry_count") or 0),
            "fallback_used": bool(meta.get("fallback_used")),
            "capability": meta.get("capability") if isinstance(meta.get("capability"), dict) else {},
            "retry_events_log": signals["retry_events"],
            "had_503_log": signals["had_503"],
            "fallback_logged": signals["fallback_logged"],
        }
    except Exception as exc:  # noqa: BLE001
        elapsed_ms = (time.perf_counter() - started_at) * 1000.0
        signals = _extract_retry_signals(collector.take(trace_id))
        status_code: int | None = None
        response_text = ""
        if isinstance(exc, requests.HTTPError) and exc.response is not None:
            status_code = int(exc.response.status_code)
            response_text = (exc.response.text or "")[:500]
        error_category = _classify_error(exc, status_code=status_code, response_text=response_text)
        if error_category == "unknown_error" and signals["retry_reasons"]:
            last_reason = signals["retry_reasons"][-1]
            if last_reason.startswith("http_"):
                error_category = last_reason
            elif "SchemaValidationError" in last_reason:
                error_category = "schema_mismatch"
            elif "JSONDecodeError" in last_reason:
                error_category = "malformed_json"
            elif "missing_content" in last_reason:
                error_category = "empty_response"
        return {
            "case_id": case.case_id,
            "phase": case.phase,
            "provider_label": case.provider_label,
            "strategy": case.strategy,
            "query_type_input": case.query_type,
            "answerability_type_input": case.answerability_type,
            "success": False,
            "latency_ms": round(elapsed_ms, 3),
            "http_status": status_code or 0,
            "error_type": type(exc).__name__,
            "error_category": error_category,
            "error_message": str(exc)[:500],
            "error_body_preview": response_text,
            "required_ok": False,
            "echo_ok": False,
            "query_length_avg": 0.0,
            "anchor_present": False,
            "generic_question": False,
            "style_match": False,
            "query_preview": "",
            "provider": "",
            "provider_type": "",
            "structured_output_used": False,
            "thinking_budget": None,
            "retry_count_meta": 0,
            "fallback_used": False,
            "capability": {},
            "retry_events_log": signals["retry_events"],
            "had_503_log": signals["had_503"],
            "fallback_logged": signals["fallback_logged"],
        }


def _execute_cases(
    *,
    cases: list[RequestCase],
    client: LlmClient,
    specs: dict[str, StrategySpec],
    prompts: dict[str, str],
    collector: TraceLogCollector,
    concurrency: int,
) -> list[dict[str, Any]]:
    if concurrency <= 1:
        return [
            _call_case(
                case=case,
                client=client,
                spec=specs[case.strategy],
                prompt_text=prompts[case.strategy],
                collector=collector,
            )
            for case in cases
        ]
    rows: list[dict[str, Any]] = []
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        futures = [
            pool.submit(
                _call_case,
                case=case,
                client=client,
                spec=specs[case.strategy],
                prompt_text=prompts[case.strategy],
                collector=collector,
            )
            for case in cases
        ]
        for future in as_completed(futures):
            rows.append(future.result())
    rows.sort(key=lambda item: item["case_id"])
    return rows


def _ratio(numerator: int, denominator: int) -> float:
    if denominator <= 0:
        return 0.0
    return round((numerator / denominator) * 100.0, 2)


def _aggregate(rows: list[dict[str, Any]]) -> dict[str, Any]:
    total = len(rows)
    success_rows = [row for row in rows if row["success"]]
    failure_rows = [row for row in rows if not row["success"]]
    parse_failures = [row for row in failure_rows if row["error_category"] in {"malformed_json", "schema_mismatch"}]
    empty_like = [row for row in failure_rows if row["error_category"] == "empty_response"]
    had_503 = [row for row in rows if row["had_503_log"] or row.get("http_status") == 503]
    retries = [row["retry_events_log"] for row in rows]
    retry_nonzero = [row for row in rows if row["retry_events_log"] > 0]
    retry_success = [row for row in retry_nonzero if row["success"]]
    fallback_rows = [row for row in rows if row["fallback_used"] or row["fallback_logged"]]
    lengths = [row["query_length_avg"] for row in success_rows if row["query_length_avg"] > 0]
    error_dist = _error_distribution(failure_rows)

    query_type_counter: dict[str, int] = {}
    answerability: dict[str, list[bool]] = {}
    providers: dict[str, int] = {}
    provider_types: dict[str, int] = {}
    for row in rows:
        qt = row["query_type_input"]
        query_type_counter[qt] = query_type_counter.get(qt, 0) + 1
        at = row["answerability_type_input"]
        answerability.setdefault(at, []).append(bool(row["success"]))
        if row["success"]:
            provider = str(row["provider"] or "unknown")
            providers[provider] = providers.get(provider, 0) + 1
            provider_type = str(row["provider_type"] or "unknown")
            provider_types[provider_type] = provider_types.get(provider_type, 0) + 1

    return {
        "total_requests": total,
        "success_count": len(success_rows),
        "failure_count": len(failure_rows),
        "json_success_rate_pct": _ratio(len(success_rows), total),
        "malformed_json_rate_pct": _ratio(len(parse_failures), total),
        "empty_response_rate_pct": _ratio(len(empty_like), total),
        "http_503_rate_pct": _ratio(len(had_503), total),
        "retry_avg_count": round(statistics.mean(retries), 3) if retries else 0.0,
        "retry_success_rate_pct": _ratio(len(retry_success), len(retry_nonzero)),
        "fallback_rate_pct": _ratio(len(fallback_rows), total),
        "query_length_avg": round(statistics.mean(lengths), 2) if lengths else 0.0,
        "query_length_min": min(lengths) if lengths else 0.0,
        "query_length_max": max(lengths) if lengths else 0.0,
        "query_length_distribution": _compute_length_distribution(lengths),
        "anchor_presence_rate_pct": _ratio(sum(1 for row in success_rows if row["anchor_present"]), len(success_rows)),
        "generic_question_rate_pct": _ratio(sum(1 for row in success_rows if row["generic_question"]), len(success_rows)),
        "query_type_distribution": query_type_counter,
        "answerability_success_rate_pct": {
            key: _ratio(sum(1 for item in values if item), len(values))
            for key, values in answerability.items()
        },
        "error_distribution": error_dist,
        "provider_distribution": providers,
        "provider_type_distribution": provider_types,
        "structured_output_true_rate_pct": _ratio(
            sum(1 for row in success_rows if row["structured_output_used"]),
            len(success_rows),
        ),
    }


def _strategy_comparison(rows: list[dict[str, Any]]) -> dict[str, Any]:
    grouped: dict[str, list[dict[str, Any]]] = {}
    for row in rows:
        grouped.setdefault(row["strategy"], []).append(row)
    summary: dict[str, Any] = {}
    for strategy, items in sorted(grouped.items()):
        success = [row for row in items if row["success"]]
        lengths = [row["query_length_avg"] for row in success if row["query_length_avg"] > 0]
        previews = [row["query_preview"] for row in success if row["query_preview"]]
        quality_scores: list[int] = []
        for row in success:
            score = 0
            if row["anchor_present"]:
                score += 1
            if not row["generic_question"]:
                score += 1
            if 12 <= row["query_length_avg"] <= 120:
                score += 1
            if row["echo_ok"]:
                score += 1
            if row["style_match"]:
                score += 1
            quality_scores.append(score)
        summary[strategy] = {
            "success_rate_pct": _ratio(len(success), len(items)),
            "avg_query_length": round(statistics.mean(lengths), 2) if lengths else 0.0,
            "anchor_rate_pct": _ratio(sum(1 for row in success if row["anchor_present"]), len(success)),
            "generic_rate_pct": _ratio(sum(1 for row in success if row["generic_question"]), len(success)),
            "style_match_rate_pct": _ratio(sum(1 for row in success if row["style_match"]), len(success)),
            "unique_query_ratio_pct": _ratio(len(set(previews)), len(previews)),
            "avg_quality_score_0_to_5": round(statistics.mean(quality_scores), 3) if quality_scores else 0.0,
        }
    return summary


def _provider_comparison(rows: list[dict[str, Any]]) -> dict[str, Any]:
    grouped: dict[str, list[dict[str, Any]]] = {}
    for row in rows:
        grouped.setdefault(row["provider_label"], []).append(row)
    summary: dict[str, Any] = {}
    for provider_label, items in grouped.items():
        success = [row for row in items if row["success"]]
        lengths = [row["query_length_avg"] for row in success if row["query_length_avg"] > 0]
        summary[provider_label] = {
            "success_rate_pct": _ratio(len(success), len(items)),
            "json_success_rate_pct": _ratio(len(success), len(items)),
            "structured_output_true_rate_pct": _ratio(
                sum(1 for row in success if row["structured_output_used"]),
                len(success),
            ),
            "avg_query_length": round(statistics.mean(lengths), 2) if lengths else 0.0,
            "anchor_rate_pct": _ratio(sum(1 for row in success if row["anchor_present"]), len(success)),
            "generic_rate_pct": _ratio(sum(1 for row in success if row["generic_question"]), len(success)),
            "fallback_rate_pct": _ratio(sum(1 for row in items if row["fallback_used"]), len(items)),
            "http_503_rate_pct": _ratio(
                sum(1 for row in items if row["had_503_log"] or row.get("http_status") == 503),
                len(items),
            ),
        }
    return summary


def _run_mock_fallback_probe(base_config: LlmStageConfig, spec: StrategySpec, prompt_text: str) -> dict[str, Any]:
    config = LlmStageConfig(
        provider="gemini-native",
        base_url="https://generativelanguage.googleapis.com/v1beta",
        api_key="mock-gemini-key",
        model=base_config.model,
        temperature=base_config.temperature,
        max_tokens=base_config.max_tokens,
        timeout_seconds=base_config.timeout_seconds,
        min_interval_seconds=base_config.min_interval_seconds,
        tokens_per_minute=base_config.tokens_per_minute,
        requests_per_day=base_config.requests_per_day,
        chars_per_token=base_config.chars_per_token,
        max_retries=1,
        backoff_initial_seconds=0.01,
        backoff_max_seconds=0.02,
        backoff_multiplier=2.0,
        backoff_jitter_ratio=0.0,
        fallback_models=("openai:gpt-4o-mini",),
        thinking_budget=0,
        concurrency_limit=2,
    )
    state = {"primary_calls": 0, "fallback_calls": 0}

    def _fake_post(url: str, *args: Any, **kwargs: Any) -> _FakeResponse:
        del args, kwargs
        if url.endswith(":generateContent"):
            state["primary_calls"] += 1
            return _FakeResponse(status_code=503, text="UNAVAILABLE")
        if url.endswith("/chat/completions"):
            state["fallback_calls"] += 1
            body = {
                "query_en": "How to diagnose Spring Boot configuration binding failures with actuator?",
                "query_ko": "Spring Boot에서 configuration binding 실패를 actuator로 어떻게 진단하나요?",
                "query_type": "procedure",
                "answerability_type": "single",
            }
            return _FakeResponse(status_code=200, body={"choices": [{"message": {"content": json.dumps(body, ensure_ascii=False)}}]})
        return _FakeResponse(status_code=500, text="unknown endpoint")

    client = LlmClient(config)
    payload = _sample_payload("A", "procedure", "single")
    with patch.dict(os.environ, {"OPENAI_API_KEY": "mock-openai-key"}, clear=False):
        with patch("pipeline.common.llm_client.requests.post", side_effect=_fake_post):
            response = client.chat_json(
                system_prompt=prompt_text,
                user_prompt=json.dumps(payload, ensure_ascii=False),
                response_schema=spec.response_schema,
                request_purpose="stability_mock_fallback_probe",
                trace_id="eval:mock-fallback",
            )
    meta = response.get("_llm_meta") if isinstance(response.get("_llm_meta"), dict) else {}
    return {
        "mock_primary_calls": state["primary_calls"],
        "mock_fallback_calls": state["fallback_calls"],
        "mock_fallback_used": bool(meta.get("fallback_used")),
        "mock_provider_type": meta.get("provider_type"),
        "mock_structured_output_used": bool(meta.get("structured_output_used")),
        "mock_success": True,
    }


def _write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def _write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as sink:
        for row in rows:
            sink.write(json.dumps(row, ensure_ascii=False) + "\n")


def _write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    if not rows:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = list(rows[0].keys())
    with path.open("w", encoding="utf-8", newline="") as sink:
        writer = csv.DictWriter(sink, fieldnames=fieldnames)
        writer.writeheader()
        for row in rows:
            writer.writerow(row)


def _render_report_legacy(summary: dict[str, Any]) -> str:
    reliability = summary["overall"]["reliability"]
    api_stability = summary["overall"]["api_stability"]
    provider_cmp = summary["provider_comparison"]
    strategy_cmp = summary["strategy_comparison"]
    lines = [
        "# LLM 운영 안정성 검증 리포트",
        "",
        "## 1. 전체 요약",
        f"- Gemini native 적용 후 개선 여부: {summary['improved']} ({summary['improved_reason']})",
        "",
        "## 2. 안정성",
        f"- JSON success rate: {reliability['json_success_rate_pct']}%",
        f"- malformed rate: {reliability['malformed_json_rate_pct']}%",
        f"- empty response rate: {reliability['empty_response_rate_pct']}%",
        "",
        "## 3. API 안정성",
        f"- 503 rate: {api_stability['http_503_rate_pct']}%",
        f"- retry 성공률: {api_stability['retry_success_rate_pct']}%",
        f"- fallback 발생률: {api_stability['fallback_rate_pct']}%",
        "",
        "## 4. provider 비교",
    ]
    for provider, metrics in provider_cmp.items():
        lines.append(
            f"- {provider}: success={metrics['success_rate_pct']}%, structured={metrics['structured_output_true_rate_pct']}%, "
            f"anchor={metrics['anchor_rate_pct']}%, generic={metrics['generic_rate_pct']}%, 503={metrics['http_503_rate_pct']}%"
        )
    lines.extend(["", "## 5. 전략 비교"])
    for strategy, metrics in strategy_cmp.items():
        lines.append(
            f"- {strategy}: quality_score={metrics['avg_quality_score_0_to_5']}, anchor={metrics['anchor_rate_pct']}%, "
            f"generic={metrics['generic_rate_pct']}%, unique={metrics['unique_query_ratio_pct']}%"
        )
    lines.extend(["", "## 6. 주요 문제"])
    for issue in summary["issues"]:
        lines.append(f"- {issue}")
    lines.extend(["", "## 7. 개선 제안"])
    for item in summary["recommendations"]:
        lines.append(f"- {item}")
    return "\n".join(lines)


def _render_report(summary: dict[str, Any]) -> str:
    reliability = summary["overall"]["reliability"]
    api_stability = summary["overall"]["api_stability"]
    gemini_validation = summary["gemini_native_validation"]
    provider_cmp = summary["provider_comparison"]
    strategy_cmp = summary["strategy_comparison"]
    quality = summary["overall"]["quality"]
    llm_meta = summary["overall"]["llm_meta"]
    lines = [
        "# LLM 운영 안정성 검증 리포트",
        "",
        "## 1. 전체 요약",
        f"- Gemini native 적용 후 개선 여부: {summary['improved']} ({summary['improved_reason']})",
        "",
        "## 2. 안정성",
        f"- JSON success rate: {reliability['json_success_rate_pct']}%",
        f"- malformed rate: {reliability['malformed_json_rate_pct']}%",
        f"- empty response rate: {reliability['empty_response_rate_pct']}%",
        f"- error distribution: {reliability['error_distribution']}",
        "",
        "## 3. API 안정성",
        f"- 503 rate: {api_stability['http_503_rate_pct']}%",
        f"- retry avg count: {api_stability['retry_avg_count']}",
        f"- retry 성공률: {api_stability['retry_success_rate_pct']}%",
        f"- fallback 발생률: {api_stability['fallback_rate_pct']}%",
        "",
        "## 4. Gemini Native 검증",
        f"- provider == gemini-native: {gemini_validation['provider_is_gemini_native']}",
        f"- structured_output_used == true: {gemini_validation['structured_output_used']}",
        f"- schema parse ok: {gemini_validation['schema_parse_ok']}",
        f"- structured_output true rate: {gemini_validation['structured_output_true_rate_pct']}%",
        "",
        "## 5. Provider 비교",
    ]
    for provider, metrics in provider_cmp.items():
        lines.append(
            f"- {provider}: success={metrics['success_rate_pct']}%, structured={metrics['structured_output_true_rate_pct']}%, "
            f"anchor={metrics['anchor_rate_pct']}%, generic={metrics['generic_rate_pct']}%, 503={metrics['http_503_rate_pct']}%"
        )
    lines.extend(
        [
            "",
            "## 6. 전략 비교",
            f"- query length distribution: {quality['query_length_distribution']}",
            f"- query_type distribution: {quality['query_type_distribution']}",
            f"- answerability success: {quality['answerability_success_rate_pct']}",
            f"- llm meta provider distribution: {llm_meta['provider_distribution']}",
        ]
    )
    for strategy, metrics in strategy_cmp.items():
        lines.append(
            f"- {strategy}: quality_score={metrics['avg_quality_score_0_to_5']}, anchor={metrics['anchor_rate_pct']}%, "
            f"generic={metrics['generic_rate_pct']}%, unique={metrics['unique_query_ratio_pct']}%"
        )
    lines.extend(["", "## 7. 주요 문제"])
    for issue in summary["issues"]:
        lines.append(f"- {issue}")
    lines.extend(["", "## 8. 개선 제안"])
    for item in summary["recommendations"]:
        lines.append(f"- {item}")
    return "\n".join(lines)


def run(args: argparse.Namespace) -> dict[str, Any]:
    _load_env_file(Path(".env"))
    prompt_root = Path(args.prompt_root)
    output_dir = Path(args.output_dir) if args.output_dir else DEFAULT_OUTPUT_ROOT / datetime.now().strftime("%Y%m%d_%H%M%S")
    output_dir.mkdir(parents=True, exist_ok=True)

    specs = _strategy_specs(prompt_root)
    prompts = {key: _load_prompt(spec.prompt_path) for key, spec in specs.items()}
    collector = TraceLogCollector()
    llm_logger = logging.getLogger(LLM_LOGGER_NAME)
    previous_propagate = llm_logger.propagate
    llm_logger.addHandler(collector)
    llm_logger.setLevel(logging.INFO)
    llm_logger.propagate = False

    base_config = load_stage_config(stage="query", raw_config={})
    native_config = replace(base_config, provider="gemini-native")
    if "gemini-native" in os.getenv("QUERY_FORGE_LLM_PROVIDER", "").lower():
        native_config = base_config
    compat_provider = args.compat_provider.strip().lower()
    openai_compat_config = replace(
        base_config,
        provider=compat_provider,
        model=args.compat_model.strip(),
        base_url=args.compat_base_url.strip(),
        fallback_models=(),
    )

    native_client = LlmClient(native_config)
    compat_client = LlmClient(openai_compat_config)

    single_cases = [
        RequestCase(
            case_id=f"single-gemini-native-{strategy.lower()}",
            phase="single",
            provider_label="gemini-native",
            strategy=strategy,
            query_type="procedure",
            answerability_type="single",
        )
        for strategy in ("A", "B", "C", "D")
    ]
    batch_cases = _build_case_matrix(
        phase="batch",
        provider_label="gemini-native",
        count=args.batch_size,
        strategies=["A", "B", "C", "D"],
        query_types=["definition", "reason", "procedure", "comparison", "short_user", "code_mixed", "follow_up"],
        answerability_types=["single", "near", "far"],
        seed=17,
    )
    load_cases = _build_case_matrix(
        phase="load",
        provider_label="gemini-native",
        count=args.load_size,
        strategies=["A", "B", "C", "D"],
        query_types=["reason", "procedure", "comparison", "follow_up"],
        answerability_types=["single", "near", "far"],
        seed=23,
    )
    compare_native_cases = _build_case_matrix(
        phase="provider_compare",
        provider_label="gemini-native",
        count=args.provider_compare_size,
        strategies=["A", "B", "C", "D"],
        query_types=["definition", "reason", "procedure", "comparison"],
        answerability_types=["single", "near", "far"],
        seed=29,
    )
    compare_compat_cases = [
        RequestCase(
            case_id=item.case_id.replace("gemini-native", "openai-compatible"),
            phase=item.phase,
            provider_label="openai-compatible",
            strategy=item.strategy,
            query_type=item.query_type,
            answerability_type=item.answerability_type,
        )
        for item in compare_native_cases
    ]

    rows: list[dict[str, Any]] = []
    mock_probe: dict[str, Any] = {
        "mock_primary_calls": 0,
        "mock_fallback_calls": 0,
        "mock_fallback_used": False,
        "mock_provider_type": "",
        "mock_structured_output_used": False,
        "mock_success": False,
    }
    try:
        rows.extend(_execute_cases(cases=single_cases, client=native_client, specs=specs, prompts=prompts, collector=collector, concurrency=1))
        rows.extend(_execute_cases(cases=batch_cases, client=native_client, specs=specs, prompts=prompts, collector=collector, concurrency=1))
        rows.extend(
            _execute_cases(
                cases=load_cases,
                client=native_client,
                specs=specs,
                prompts=prompts,
                collector=collector,
                concurrency=args.load_concurrency,
            )
        )
        rows.extend(_execute_cases(cases=compare_native_cases, client=native_client, specs=specs, prompts=prompts, collector=collector, concurrency=1))
        rows.extend(_execute_cases(cases=compare_compat_cases, client=compat_client, specs=specs, prompts=prompts, collector=collector, concurrency=1))
        mock_probe = _run_mock_fallback_probe(base_config, specs["A"], prompts["A"])
    finally:
        llm_logger.removeHandler(collector)
        llm_logger.propagate = previous_propagate

    overall = _aggregate(rows)
    provider_comparison = _provider_comparison([row for row in rows if row["phase"] == "provider_compare"])
    strategy_comparison = _strategy_comparison([row for row in rows if row["phase"] in {"single", "batch", "load"}])

    gemini_validation = _gemini_native_validation([row for row in rows if row["phase"] in {"single", "batch", "load"}])
    issues: list[str] = []
    if overall["json_success_rate_pct"] < 95.0:
        issues.append("JSON success rate가 목표(95%) 미만입니다.")
    if overall["http_503_rate_pct"] > 5.0:
        issues.append("503 발생률이 높습니다. RPM/동시성 조정이 필요합니다.")
    if not gemini_validation["structured_output_used"]:
        issues.append("Gemini native structured output 사용이 기대치보다 낮습니다.")
    if not gemini_validation["provider_is_gemini_native"]:
        issues.append("Gemini 요청의 provider 라우팅이 gemini-native로 고정되지 않았습니다.")
    if not mock_probe.get("mock_fallback_used"):
        issues.append("mock fallback probe에서 fallback 사용을 확인하지 못했습니다.")
    if not issues:
        issues.append("치명적인 안정성 이슈는 발견되지 않았습니다.")

    recommendations = [
        "1) Gemini responseSchema에서 미지원 키(additionalProperties 등)를 제거해 400 오류 차단",
        "2) 503 급증 구간에서 concurrency_limit을 단계적으로 낮추는 자동 제어 추가",
        "3) OpenAI-compatible 비교군의 API 키/모델을 별도 설정해 비교 신뢰도 확보",
    ]

    improved = "YES" if (
        gemini_validation["structured_output_used"]
        and gemini_validation["provider_is_gemini_native"]
        and overall["json_success_rate_pct"] >= 95.0
    ) else "NO"
    improved_reason = (
        "Gemini native structured output 및 JSON 안정성 기준 충족"
        if improved == "YES"
        else "핵심 지표 일부가 운영 기준에 미달"
    )

    summary = {
        "generated_at": datetime.now().isoformat(),
        "config": {
            "batch_size": args.batch_size,
            "load_size": args.load_size,
            "load_concurrency": args.load_concurrency,
            "provider_compare_size": args.provider_compare_size,
            "compat_provider": args.compat_provider,
            "compat_model": args.compat_model,
            "compat_base_url": args.compat_base_url,
        },
        "overall": {
            "reliability": {
                "json_success_rate_pct": overall["json_success_rate_pct"],
                "malformed_json_rate_pct": overall["malformed_json_rate_pct"],
                "empty_response_rate_pct": overall["empty_response_rate_pct"],
                "error_distribution": overall["error_distribution"],
            },
            "api_stability": {
                "http_503_rate_pct": overall["http_503_rate_pct"],
                "retry_avg_count": overall["retry_avg_count"],
                "retry_success_rate_pct": overall["retry_success_rate_pct"],
                "fallback_rate_pct": overall["fallback_rate_pct"],
            },
            "quality": {
                "query_length_avg": overall["query_length_avg"],
                "query_length_min": overall["query_length_min"],
                "query_length_max": overall["query_length_max"],
                "query_length_distribution": overall["query_length_distribution"],
                "anchor_presence_rate_pct": overall["anchor_presence_rate_pct"],
                "generic_question_rate_pct": overall["generic_question_rate_pct"],
                "query_type_distribution": overall["query_type_distribution"],
                "answerability_success_rate_pct": overall["answerability_success_rate_pct"],
            },
            "llm_meta": {
                "provider_distribution": overall["provider_distribution"],
                "provider_type_distribution": overall["provider_type_distribution"],
                "structured_output_true_rate_pct": overall["structured_output_true_rate_pct"],
            },
        },
        "gemini_native_validation": gemini_validation,
        "mock_fallback_probe": mock_probe,
        "provider_comparison": provider_comparison,
        "strategy_comparison": strategy_comparison,
        "improved": improved,
        "improved_reason": improved_reason,
        "issues": issues,
        "recommendations": recommendations,
    }

    report_text = _render_report(summary)
    _write_json(output_dir / "summary.json", summary)
    _write_jsonl(output_dir / "results.jsonl", rows)
    _write_csv(output_dir / "results.csv", rows)
    (output_dir / "report.md").write_text(report_text, encoding="utf-8")
    print(report_text)
    print(f"\n[artifact] {output_dir}")
    return summary


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="LLM stability and quality evaluation runner")
    parser.add_argument("--prompt-root", default="configs/prompts", help="prompt root")
    parser.add_argument("--output-dir", default="", help="artifact output directory")
    parser.add_argument("--batch-size", type=int, default=60, help="batch request count")
    parser.add_argument("--load-size", type=int, default=20, help="load request count")
    parser.add_argument("--load-concurrency", type=int, default=20, help="load concurrency")
    parser.add_argument("--provider-compare-size", type=int, default=24, help="provider comparison sample count")
    parser.add_argument("--compat-provider", default="gemini", help="comparison provider for OpenAI-compatible path")
    parser.add_argument(
        "--compat-model",
        default=os.getenv("QUERY_FORGE_LLM_COMPARE_MODEL", "gemini-2.5-flash"),
        help="comparison model for OpenAI-compatible path",
    )
    parser.add_argument(
        "--compat-base-url",
        default=os.getenv("QUERY_FORGE_LLM_OPENAI_COMPAT_BASE_URL", "https://generativelanguage.googleapis.com/v1beta/openai"),
        help="comparison base URL for OpenAI-compatible path",
    )
    return parser.parse_args()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    run(parse_args())
