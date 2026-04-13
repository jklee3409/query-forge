from __future__ import annotations

import json
import os
import unittest
from unittest.mock import patch

import requests

from pipeline.common import llm_client


class _FakeResponse:
    def __init__(
        self,
        *,
        status_code: int,
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
        self.content = self.text.encode("utf-8") if self.text else b""

    def json(self) -> dict:
        if self._body is None:
            raise ValueError("body is not json")
        return self._body

    def raise_for_status(self) -> None:
        if self.status_code >= 400:
            raise requests.HTTPError(f"HTTP {self.status_code}", response=self)


def _openai_success(payload: dict) -> _FakeResponse:
    return _FakeResponse(
        status_code=200,
        body={"choices": [{"message": {"content": json.dumps(payload, ensure_ascii=False)}}]},
    )


def _gemini_success(payload: dict) -> _FakeResponse:
    return _FakeResponse(
        status_code=200,
        body={"candidates": [{"content": {"parts": [{"text": json.dumps(payload, ensure_ascii=False)}]}}]},
    )


def _config(
    *,
    provider: str = "openai",
    model: str = "gpt-4o-mini",
    base_url: str = "https://api.openai.com/v1",
    api_key: str = "test-key",
    max_retries: int = 0,
    fallback_models: tuple[str, ...] = (),
    thinking_budget: int | None = 0,
) -> llm_client.LlmStageConfig:
    return llm_client.LlmStageConfig(
        provider=provider,
        base_url=base_url,
        api_key=api_key,
        model=model,
        temperature=0.2,
        max_tokens=128,
        timeout_seconds=5.0,
        min_interval_seconds=0.0,
        tokens_per_minute=0,
        requests_per_day=0,
        chars_per_token=2.2,
        max_retries=max_retries,
        backoff_initial_seconds=0.1,
        backoff_max_seconds=0.2,
        backoff_multiplier=2.0,
        backoff_jitter_ratio=0.0,
        fallback_models=fallback_models,
        thinking_budget=thinking_budget,
        concurrency_limit=2,
    )


class LlmClientTest(unittest.TestCase):
    @patch("pipeline.common.llm_client.requests.post")
    def test_normal_json_response(self, mock_post) -> None:
        mock_post.return_value = _openai_success({"query_ko": "빈 생성 테스트는 어떻게 하나요?"})
        client = llm_client.LlmClient(_config())
        schema = {
            "type": "object",
            "required": ["query_ko"],
            "properties": {"query_ko": {"type": "string"}},
            "additionalProperties": True,
        }

        response = client.chat_json(
            system_prompt="system",
            user_prompt='{"input":"x"}',
            response_schema=schema,
            request_purpose="test",
            trace_id="trace-1",
        )

        self.assertEqual(response["query_ko"], "빈 생성 테스트는 어떻게 하나요?")
        self.assertFalse(response["_llm_meta"]["fallback_used"])
        call_json = mock_post.call_args.kwargs["json"]
        self.assertEqual(call_json.get("response_format", {}).get("type"), "json_object")

    @patch("pipeline.common.llm_client.requests.post")
    def test_json_field_missing(self, mock_post) -> None:
        mock_post.return_value = _openai_success({"wrong_field": "value"})
        client = llm_client.LlmClient(_config(max_retries=0))
        schema = {
            "type": "object",
            "required": ["query_ko"],
            "properties": {"query_ko": {"type": "string"}},
            "additionalProperties": True,
        }

        with self.assertRaises(RuntimeError):
            client.chat_json(system_prompt="s", user_prompt="u", response_schema=schema)

    @patch("pipeline.common.llm_client.requests.post")
    def test_empty_response(self, mock_post) -> None:
        mock_post.return_value = _FakeResponse(
            status_code=200,
            body={"choices": [{"message": {"content": ""}}]},
        )
        client = llm_client.LlmClient(_config(max_retries=0))

        with self.assertRaises(RuntimeError):
            client.chat_json(system_prompt="s", user_prompt="u")

    @patch("pipeline.common.llm_client.time.sleep", return_value=None)
    @patch("pipeline.common.llm_client.requests.post")
    def test_retry_503_then_success(self, mock_post, _mock_sleep) -> None:
        mock_post.side_effect = [
            _FakeResponse(status_code=503, text="UNAVAILABLE"),
            _openai_success({"query_ko": "정상"}),
        ]
        client = llm_client.LlmClient(_config(max_retries=1))
        schema = {
            "type": "object",
            "required": ["query_ko"],
            "properties": {"query_ko": {"type": "string"}},
            "additionalProperties": True,
        }

        response = client.chat_json(system_prompt="s", user_prompt="u", response_schema=schema)

        self.assertEqual(response["query_ko"], "정상")
        self.assertEqual(mock_post.call_count, 2)

    @patch("pipeline.common.llm_client.time.sleep", return_value=None)
    @patch("pipeline.common.llm_client.requests.post")
    def test_retry_503_exhausted_then_fallback_success(self, mock_post, _mock_sleep) -> None:
        mock_post.side_effect = [
            _FakeResponse(status_code=503, text="UNAVAILABLE"),
            _FakeResponse(status_code=503, text="UNAVAILABLE"),
            _openai_success({"query_ko": "fallback 성공"}),
        ]
        with patch.dict(os.environ, {"OPENAI_API_KEY": "openai-key"}, clear=False):
            client = llm_client.LlmClient(
                _config(
                    provider="gemini",
                    model="gemini-2.5-flash",
                    base_url="https://generativelanguage.googleapis.com/v1beta",
                    api_key="gemini-key",
                    max_retries=1,
                    fallback_models=("openai:gpt-4o-mini",),
                )
            )
            schema = {
                "type": "object",
                "required": ["query_ko"],
                "properties": {"query_ko": {"type": "string"}},
                "additionalProperties": True,
            }

            response = client.chat_json(system_prompt="s", user_prompt="u", response_schema=schema)

        self.assertEqual(response["query_ko"], "fallback 성공")
        self.assertTrue(response["_llm_meta"]["fallback_used"])
        self.assertEqual(response["_llm_meta"]["provider"], "openai")

    @patch("pipeline.common.llm_client.requests.post")
    def test_http_400_fail_fast(self, mock_post) -> None:
        mock_post.return_value = _FakeResponse(status_code=400, text="bad request")
        client = llm_client.LlmClient(_config(max_retries=4))

        with self.assertRaises(requests.HTTPError):
            client.chat_json(system_prompt="s", user_prompt="u")
        self.assertEqual(mock_post.call_count, 1)

    @patch("pipeline.common.llm_client.requests.post")
    def test_malformed_json_fallback_parse_failure(self, mock_post) -> None:
        mock_post.return_value = _FakeResponse(
            status_code=200,
            body={"choices": [{"message": {"content": "```json\n{bad json}\n```"}}]},
        )
        client = llm_client.LlmClient(_config(max_retries=0))

        with self.assertRaises(RuntimeError):
            client.chat_json(system_prompt="s", user_prompt="u")

    @patch("pipeline.common.llm_client.requests.post")
    def test_structured_output_mapping_with_gemini_native(self, mock_post) -> None:
        mock_post.return_value = _gemini_success({"query_ko": "구조화 출력"})
        client = llm_client.LlmClient(
            _config(
                provider="gemini",
                model="gemini-2.5-flash",
                base_url="https://generativelanguage.googleapis.com/v1beta",
                api_key="gemini-key",
                max_retries=0,
                thinking_budget=0,
            )
        )
        schema = {
            "type": "object",
            "required": ["query_ko"],
            "properties": {"query_ko": {"type": "string"}},
            "additionalProperties": True,
        }

        response = client.chat_json(
            system_prompt="s",
            user_prompt="u",
            response_schema=schema,
            request_purpose="structured_output_test",
            trace_id="trace-structured",
        )

        self.assertEqual(response["query_ko"], "구조화 출력")
        call_json = mock_post.call_args.kwargs["json"]
        generation_config = call_json["generationConfig"]
        self.assertEqual(generation_config.get("responseMimeType"), "application/json")
        self.assertIn("responseSchema", generation_config)
        self.assertNotIn("additionalProperties", json.dumps(generation_config["responseSchema"]))
        self.assertEqual(generation_config.get("thinkingConfig", {}).get("thinkingBudget"), 0)

    @patch("pipeline.common.llm_client.requests.post")
    def test_gemini_malformed_response_parts_missing(self, mock_post) -> None:
        mock_post.return_value = _FakeResponse(
            status_code=200,
            body={"candidates": [{"content": {}}]},
        )
        client = llm_client.LlmClient(
            _config(
                provider="gemini-native",
                model="gemini-2.5-flash",
                base_url="https://generativelanguage.googleapis.com/v1beta",
                api_key="gemini-key",
                max_retries=0,
            )
        )

        with self.assertRaises(RuntimeError):
            client.chat_json(system_prompt="s", user_prompt="u")

    def test_capability_exposed(self) -> None:
        gemini_client = llm_client.LlmClient(
            _config(
                provider="gemini-native",
                model="gemini-2.5-flash",
                base_url="https://generativelanguage.googleapis.com/v1beta",
                api_key="gemini-key",
            )
        )
        openai_client = llm_client.LlmClient(_config(provider="openai"))
        gemini_capability = gemini_client.capability()
        openai_capability = openai_client.capability()

        self.assertTrue(gemini_capability.supportsStructuredOutput)
        self.assertTrue(gemini_capability.supportsThinkingBudget)
        self.assertFalse(openai_capability.supportsResponseSchema)
        self.assertTrue(openai_capability.supportsJsonMode)


if __name__ == "__main__":
    unittest.main()
