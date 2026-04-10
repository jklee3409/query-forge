from __future__ import annotations

import os
import threading
import time
from dataclasses import dataclass
from typing import Any

import requests


@dataclass(slots=True)
class CohereRerankConfig:
    enabled: bool
    api_key: str
    model: str
    base_url: str
    timeout_seconds: float
    min_interval_seconds: float
    max_retries: int


class CohereReranker:
    def __init__(self, config: CohereRerankConfig) -> None:
        self.config = config
        self._last_call_at = 0.0
        self._lock = threading.Lock()
        self._warned_unavailable = False

    @property
    def available(self) -> bool:
        return bool(self.config.enabled and self.config.api_key)

    def rerank(
        self,
        *,
        query: str,
        documents: list[str],
        top_n: int,
    ) -> list[tuple[int, float]]:
        limited_top_n = max(1, min(top_n, len(documents)))
        if not documents:
            return []
        if not self.available:
            if not self._warned_unavailable:
                self._warned_unavailable = True
                print("[rerank] Cohere API key missing or disabled; fallback to local ranking.")
            return [(index, 1.0 - (index / max(1, len(documents)))) for index in range(limited_top_n)]

        payload = {
            "model": self.config.model,
            "query": query,
            "documents": documents,
            "top_n": limited_top_n,
        }
        for attempt in range(max(1, self.config.max_retries)):
            self._throttle()
            try:
                response = requests.post(
                    f"{self.config.base_url.rstrip('/')}/v2/rerank",
                    headers={
                        "Authorization": f"Bearer {self.config.api_key}",
                        "Content-Type": "application/json",
                    },
                    json=payload,
                    timeout=self.config.timeout_seconds,
                )
            except requests.RequestException:
                if attempt + 1 >= self.config.max_retries:
                    return [(index, 1.0 - (index / max(1, len(documents)))) for index in range(limited_top_n)]
                time.sleep(min(20.0, 1.5 * (2 ** attempt)))
                continue

            if response.status_code in {429, 500, 502, 503, 504}:
                if attempt + 1 >= self.config.max_retries:
                    return [(index, 1.0 - (index / max(1, len(documents)))) for index in range(limited_top_n)]
                retry_after = 0.0
                retry_after_raw = response.headers.get("Retry-After")
                if retry_after_raw:
                    try:
                        retry_after = float(retry_after_raw)
                    except ValueError:
                        retry_after = 0.0
                time.sleep(max(retry_after, min(30.0, 2.0 * (2 ** attempt))))
                continue

            if response.status_code >= 400:
                return [(index, 1.0 - (index / max(1, len(documents)))) for index in range(limited_top_n)]

            body = response.json() if response.content else {}
            rows = body.get("results") if isinstance(body, dict) else []
            if not isinstance(rows, list):
                return [(index, 1.0 - (index / max(1, len(documents)))) for index in range(limited_top_n)]
            reranked: list[tuple[int, float]] = []
            for row in rows:
                if not isinstance(row, dict):
                    continue
                index = row.get("index")
                relevance = row.get("relevance_score")
                if not isinstance(index, int):
                    continue
                score = float(relevance) if relevance is not None else 0.0
                reranked.append((index, score))
            if reranked:
                return reranked[:limited_top_n]
            return [(index, 1.0 - (index / max(1, len(documents)))) for index in range(limited_top_n)]
        return [(index, 1.0 - (index / max(1, len(documents)))) for index in range(limited_top_n)]

    def _throttle(self) -> None:
        with self._lock:
            wait_seconds = self.config.min_interval_seconds - (time.monotonic() - self._last_call_at)
            if wait_seconds > 0:
                time.sleep(wait_seconds)
            self._last_call_at = time.monotonic()


def load_cohere_rerank_config(raw_config: dict[str, Any] | None = None) -> CohereRerankConfig:
    raw_config = raw_config or {}
    enabled = bool(raw_config.get("cohere_rerank_enabled", True))
    api_key = str(
        raw_config.get("cohere_api_key")
        or raw_config.get("cohere_rerank_api_key")
        or os.getenv("QUERY_FORGE_COHERE_API_KEY")
        or os.getenv("COHERE_API_KEY")
        or ""
    ).strip()
    model = str(
        raw_config.get("cohere_rerank_model")
        or os.getenv("QUERY_FORGE_COHERE_RERANK_MODEL")
        or "rerank-v3.5"
    ).strip()
    base_url = str(
        raw_config.get("cohere_base_url")
        or os.getenv("QUERY_FORGE_COHERE_BASE_URL")
        or "https://api.cohere.com"
    ).strip()
    timeout_seconds = float(
        raw_config.get("cohere_rerank_timeout_seconds")
        or os.getenv("QUERY_FORGE_COHERE_TIMEOUT_SECONDS")
        or 30.0
    )
    rpm = float(
        raw_config.get("cohere_rerank_rpm")
        or os.getenv("QUERY_FORGE_COHERE_RERANK_RPM")
        or 60.0
    )
    min_interval_seconds = 0.0 if rpm <= 0 else 60.0 / rpm
    max_retries = int(
        raw_config.get("cohere_rerank_max_retries")
        or os.getenv("QUERY_FORGE_COHERE_RERANK_MAX_RETRIES")
        or 4
    )
    return CohereRerankConfig(
        enabled=enabled,
        api_key=api_key,
        model=model,
        base_url=base_url,
        timeout_seconds=timeout_seconds,
        min_interval_seconds=min_interval_seconds,
        max_retries=max(1, min(max_retries, 10)),
    )
