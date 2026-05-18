from __future__ import annotations

import json
import unittest

from pipeline.common.gemini_batch import (
    GeminiBatchAdapter,
    GeminiBatchRequestItem,
    build_gemini_generate_content_request,
    iter_jsonl_objects,
    parse_batch_result,
    parse_gemini_json_response,
)
from pipeline.common.llm_client import LlmStageConfig


class _FakeResponse:
    def __init__(
        self,
        *,
        status_code: int = 200,
        body: dict | None = None,
        text: str | None = None,
        headers: dict[str, str] | None = None,
    ) -> None:
        self.status_code = status_code
        self._body = body
        self.headers = headers or {}
        if text is None and body is not None:
            text = json.dumps(body, ensure_ascii=False)
        self.text = text or ""
        self.content = self.text.encode("utf-8")

    def json(self) -> dict:
        if self._body is None:
            raise ValueError("not json")
        return self._body

    def raise_for_status(self) -> None:
        if self.status_code >= 400:
            raise RuntimeError(f"HTTP {self.status_code}")


class _FakeSession:
    def __init__(self) -> None:
        self.posts: list[dict] = []
        self.gets: list[dict] = []

    def post(self, url, **kwargs):
        self.posts.append({"url": url, **kwargs})
        return _FakeResponse(body={"name": "batches/1", "state": "BATCH_STATE_PENDING"})

    def get(self, url, **kwargs):
        self.gets.append({"url": url, **kwargs})
        return _FakeResponse(
            body={
                "name": "batches/1",
                "state": "BATCH_STATE_SUCCEEDED",
                "batchStats": {
                    "requestCount": "1",
                    "successfulRequestCount": "1",
                    "failedRequestCount": "0",
                },
                "output": {
                    "inlinedResponses": {
                        "inlinedResponses": [
                            {
                                "metadata": {"key": "item-1", "chunk_id": "chunk-1"},
                                "response": _gemini_response({"translated_chunk_ko": "translation"}, total_tokens=33),
                            }
                        ]
                    }
                },
            }
        )


def _config() -> LlmStageConfig:
    return LlmStageConfig(
        provider="gemini-native",
        base_url="https://generativelanguage.googleapis.com/v1beta",
        api_key="test-key",
        model="gemini-2.5-flash-lite",
        temperature=0.2,
        max_tokens=128,
        timeout_seconds=5.0,
        min_interval_seconds=0.0,
        tokens_per_minute=0,
        requests_per_day=0,
        chars_per_token=2.2,
        max_retries=0,
        backoff_initial_seconds=0.1,
        backoff_max_seconds=0.2,
        backoff_multiplier=2.0,
        backoff_jitter_ratio=0.0,
        fallback_models=(),
        thinking_budget=0,
        concurrency_limit=1,
    )


def _gemini_response(payload: dict, *, total_tokens: int = 10) -> dict:
    return {
        "candidates": [
            {
                "finishReason": "STOP",
                "content": {"parts": [{"text": json.dumps(payload, ensure_ascii=False)}]},
            }
        ],
        "usageMetadata": {
            "promptTokenCount": 3,
            "candidatesTokenCount": total_tokens - 3,
            "totalTokenCount": total_tokens,
        },
    }


class GeminiBatchAdapterTest(unittest.TestCase):
    def test_build_generate_content_request_uses_structured_json_config(self) -> None:
        request = build_gemini_generate_content_request(
            _config(),
            system_prompt="system",
            user_prompt='{"x": 1}',
            response_schema={
                "type": "object",
                "required": ["translated_chunk_ko"],
                "properties": {"translated_chunk_ko": {"type": "string"}},
            },
        )

        self.assertEqual(request["generationConfig"]["responseMimeType"], "application/json")
        self.assertEqual(request["generationConfig"]["maxOutputTokens"], 128)
        self.assertIn("responseSchema", request["generationConfig"])

    def test_inline_submit_poll_and_fetch_maps_result_by_metadata_key(self) -> None:
        session = _FakeSession()
        adapter = GeminiBatchAdapter(
            base_url="https://generativelanguage.googleapis.com/v1beta",
            api_key="test-key",
            session=session,
        )
        item = GeminiBatchRequestItem(
            key="item-1",
            request={"contents": [{"parts": [{"text": "translate"}]}]},
            metadata={"chunk_id": "chunk-1"},
        )

        submitted = adapter.submit_inline(model="gemini-2.5-flash-lite", items=[item], display_name="test")
        final_job = adapter.poll_job(name=submitted.name, poll_interval_seconds=0.1, timeout_seconds=1)
        results = adapter.fetch_results(job=final_job, expected_items=[item])

        self.assertEqual(final_job.batch_stats["request_count"], 1)
        self.assertEqual(results[0].key, "item-1")
        self.assertEqual(results[0].usage_metadata["totalTokenCount"], 33)
        request_payload = session.posts[0]["json"]
        inline_item = request_payload["batch"]["input_config"]["requests"]["requests"][0]
        self.assertEqual(inline_item["metadata"]["key"], "item-1")

    def test_jsonl_result_parser_handles_success_and_error_rows(self) -> None:
        rows = iter_jsonl_objects(
            "\n".join(
                [
                    json.dumps({"key": "ok", "response": _gemini_response({"query_ko": "question"})}),
                    json.dumps({"key": "bad", "error": {"code": 13, "message": "internal"}}),
                ]
            )
        )

        ok = parse_batch_result(rows[0])
        bad = parse_batch_result(rows[1])

        self.assertTrue(ok.succeeded)
        self.assertFalse(bad.succeeded)
        self.assertEqual(bad.error["message"], "internal")

    def test_parse_gemini_json_response_validates_schema(self) -> None:
        parsed = parse_gemini_json_response(
            _gemini_response({"query_ko": "question"}),
            response_schema={
                "type": "object",
                "required": ["query_ko"],
                "properties": {"query_ko": {"type": "string"}},
            },
        )

        self.assertEqual(parsed["query_ko"], "question")


if __name__ == "__main__":
    unittest.main()
