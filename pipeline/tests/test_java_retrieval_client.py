from __future__ import annotations

import unittest

import requests

from pipeline.eval import retrieval_eval
from pipeline.eval.java_retrieval_client import (
    JavaRetrievalClientError,
    JavaRetrievalEvalClient,
    JavaRetrievalEvalSettings,
    build_retrieval_eval_payload,
    parse_retrieval_eval_response,
)
from pipeline.eval.runtime import EvalSample, RetrievalCandidate


class _FakeResponse:
    def __init__(self, *, status_code: int, body: dict | None = None, text: str = "") -> None:
        self.status_code = status_code
        self._body = body
        self.text = text

    def json(self) -> dict:
        if self._body is None:
            raise ValueError("not json")
        return self._body


class _RecordingSession:
    def __init__(self, response: _FakeResponse | None = None, error: Exception | None = None) -> None:
        self.response = response
        self.error = error
        self.calls: list[dict] = []

    def post(self, url: str, *, json: dict, timeout: float) -> _FakeResponse:
        self.calls.append({"url": url, "json": json, "timeout": timeout})
        if self.error is not None:
            raise self.error
        if self.response is None:
            raise AssertionError("response is required")
        return self.response


class _FailingJavaClient:
    def retrieve(self, **_kwargs):
        raise AssertionError("Java client should not be called")


class JavaRetrievalClientContractTests(unittest.TestCase):
    def test_request_payload_forces_no_write_and_no_answer_generation(self) -> None:
        payload = build_retrieval_eval_payload(
            domain_id="11111111-1111-1111-1111-111111111111",
            query="FilterChainProxy order",
            forced_mode="raw-only",
            top_k=3,
            include_trace=False,
            include_scores=True,
            include_metadata=False,
        )

        self.assertEqual(payload["persistPolicy"], "NONE")
        self.assertFalse(payload["answerGeneration"])
        self.assertEqual(payload["forcedMode"], "raw_only")
        self.assertEqual(payload["topK"], 3)
        self.assertFalse(payload["includeTrace"])
        self.assertTrue(payload["includeScores"])
        self.assertFalse(payload["includeMetadata"])

    def test_agentic_mode_is_rejected_before_http_request(self) -> None:
        session = _RecordingSession(
            _FakeResponse(
                status_code=200,
                body={"retrievedChunkIds": []},
            )
        )
        client = JavaRetrievalEvalClient("http://localhost:8080", session=session)

        with self.assertRaises(JavaRetrievalClientError) as context:
            client.retrieve(
                domain_id="11111111-1111-1111-1111-111111111111",
                query="agentic query",
                forced_mode="agentic_multi_query",
            )

        self.assertEqual(context.exception.code, "unsupported_agentic_eval")
        self.assertEqual(session.calls, [])

    def test_response_maps_retrieved_chunk_ids_preserving_order_and_duplicates(self) -> None:
        result = parse_retrieval_eval_response(
            {
                "domainId": "11111111-1111-1111-1111-111111111111",
                "query": "FilterChainProxy order",
                "finalQuery": "FilterChainProxy order",
                "forcedMode": "raw_only",
                "selectedMode": "raw_only",
                "retrievedChunkIds": ["chunk-2", "chunk-1", "chunk-2"],
                "retrievedDocs": [
                    {
                        "chunkId": "chunk-2",
                        "documentId": "doc-2",
                        "contentPreview": "Second preview",
                        "score": 0.82,
                        "rank": 1,
                    },
                    {
                        "chunkId": "chunk-1",
                        "documentId": "doc-1",
                        "contentPreview": "First preview",
                        "score": 0.91,
                        "rank": 2,
                    },
                ],
                "warnings": [],
            }
        )

        candidates = result.to_retrieval_candidates()

        self.assertEqual([item.chunk_id for item in candidates], ["chunk-2", "chunk-1", "chunk-2"])
        self.assertEqual([item.document_id for item in candidates], ["doc-2", "doc-1", "doc-2"])
        self.assertEqual(candidates[0].score, 0.82)

    def test_missing_retrieved_chunk_ids_is_clear_client_error(self) -> None:
        with self.assertRaises(JavaRetrievalClientError) as context:
            parse_retrieval_eval_response({"retrievedDocs": []})

        self.assertEqual(context.exception.code, "missing_retrieved_chunk_ids")

    def test_problem_detail_response_maps_to_client_error(self) -> None:
        response = _FakeResponse(
            status_code=400,
            body={
                "title": "Retrieval eval request rejected",
                "status": 400,
                "detail": "unsupported_persist_policy: retrieval eval supports only persistPolicy=NONE",
                "code": "unsupported_persist_policy",
            },
        )
        client = JavaRetrievalEvalClient("http://localhost:8080", session=_RecordingSession(response))

        with self.assertRaises(JavaRetrievalClientError) as context:
            client.retrieve(
                domain_id="11111111-1111-1111-1111-111111111111",
                query="FilterChainProxy order",
                forced_mode="raw_only",
            )

        self.assertEqual(context.exception.code, "unsupported_persist_policy")
        self.assertEqual(context.exception.status_code, 400)
        self.assertIn("retrieval eval supports only persistPolicy=NONE", context.exception.detail)

    def test_backend_unavailable_maps_to_clear_client_error(self) -> None:
        client = JavaRetrievalEvalClient(
            "http://localhost:8080",
            session=_RecordingSession(error=requests.ConnectionError("connection refused")),
        )

        with self.assertRaises(JavaRetrievalClientError) as context:
            client.retrieve(
                domain_id="11111111-1111-1111-1111-111111111111",
                query="FilterChainProxy order",
                forced_mode="raw_only",
            )

        self.assertEqual(context.exception.code, "java_backend_unavailable")
        self.assertIn("connection refused", context.exception.detail)


class RetrievalEvalJavaAdapterTests(unittest.TestCase):
    def test_java_backend_disabled_uses_legacy_raw_retrieval_without_client_call(self) -> None:
        class Config:
            raw = {}
            retrieval_top_k = 2

        sample = _sample()
        raw_retrieval = [
            RetrievalCandidate(chunk_id="chunk-1", document_id="doc-1", score=1.0, text="legacy")
        ]

        metrics, rewrite_info, retrieval = retrieval_eval._evaluate_mode(
            mode="raw_only",
            sample=sample,
            chunks=[],
            memories=[],
            config=Config(),
            memory_strategy_filters=[],
            source_gating_run_id=None,
            comparison_source_runs={},
            retrieval_adapter=None,
            multi_source_anchor_index=None,
            raw_retrieval=raw_retrieval,
            java_client=_FailingJavaClient(),
            java_settings=None,
        )

        self.assertEqual(metrics["hit@5"], 1.0)
        self.assertEqual(retrieval, raw_retrieval)
        self.assertFalse(rewrite_info["rewrite_applied"])

    def test_java_backend_enabled_maps_response_to_metric_input(self) -> None:
        class Config:
            raw = {}
            retrieval_top_k = 2

        class FakeJavaClient:
            def __init__(self) -> None:
                self.calls: list[dict] = []

            def retrieve(self, **kwargs):
                self.calls.append(kwargs)
                return parse_retrieval_eval_response(
                    {
                        "domainId": kwargs["domain_id"],
                        "query": kwargs["query"],
                        "finalQuery": kwargs["query"],
                        "forcedMode": kwargs["forced_mode"],
                        "selectedMode": "raw_only",
                        "retrievedChunkIds": ["chunk-2", "chunk-1"],
                        "retrievedDocs": [
                            {"chunkId": "chunk-2", "documentId": "doc-2", "score": 0.8},
                            {"chunkId": "chunk-1", "documentId": "doc-1", "score": 0.7},
                        ],
                        "llmCallCount": {
                            "rewriteCalls": 0,
                            "plannerCalls": 0,
                            "answerCalls": 0,
                            "totalCalls": 0,
                        },
                    }
                )

        java_client = FakeJavaClient()
        java_settings = JavaRetrievalEvalSettings(
            enabled=True,
            base_url="http://localhost:8080",
            timeout_seconds=1.0,
            domain_id="11111111-1111-1111-1111-111111111111",
            include_trace=False,
            include_scores=True,
            include_metadata=False,
        )

        metrics, rewrite_info, retrieval = retrieval_eval._evaluate_mode(
            mode="raw_only",
            sample=_sample(),
            chunks=[],
            memories=[],
            config=Config(),
            memory_strategy_filters=[],
            source_gating_run_id=None,
            comparison_source_runs={},
            retrieval_adapter=None,
            multi_source_anchor_index=None,
            raw_retrieval=[],
            java_client=java_client,
            java_settings=java_settings,
        )

        self.assertEqual(java_client.calls[0]["forced_mode"], "raw_only")
        self.assertEqual([item.chunk_id for item in retrieval], ["chunk-2", "chunk-1"])
        self.assertEqual(metrics["hit@5"], 1.0)
        self.assertTrue(rewrite_info["java_backend_enabled"])


def _sample() -> EvalSample:
    return EvalSample(
        sample_id="sample-1",
        split="test",
        query_text="FilterChainProxy order",
        query_language="en",
        dialog_context={},
        expected_doc_ids=["doc-1"],
        expected_chunk_ids=["chunk-1"],
        expected_answer_key_points=[],
        query_category="definition",
        single_or_multi_chunk=None,
    )


if __name__ == "__main__":
    unittest.main()
