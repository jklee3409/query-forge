from __future__ import annotations

import json
import os
import unittest
from unittest.mock import patch

from pipeline.common.cohere_reranker import CohereRerankConfig, CohereReranker
from pipeline.common.embeddings import embed_text
from pipeline.common import local_retriever
from pipeline.common.local_retriever import build_retriever_config, get_local_text_retriever
from pipeline.eval import runtime
from pipeline.eval.runtime import ChunkItem, MemoryItem


class EvalRuntimeRewriteTests(unittest.TestCase):
    def tearDown(self) -> None:
        local_retriever._MODEL_BACKENDS.clear()
        local_retriever._RETRIEVER_CACHE.clear()

    def test_unavailable_cohere_reranker_returns_no_synthetic_scores(self) -> None:
        reranker = CohereReranker(
            CohereRerankConfig(
                enabled=True,
                api_key="",
                model="rerank-v3.5",
                base_url="https://api.cohere.com",
                timeout_seconds=1.0,
                min_interval_seconds=0.0,
                max_retries=1,
            )
        )

        self.assertEqual(
            reranker.rerank(query="spring security", documents=["a", "b"], top_n=2),
            [],
        )

    def test_local_retriever_uses_bm25_for_technical_exact_match(self) -> None:
        with patch.dict(os.environ, {"QUERY_FORGE_LOCAL_REAL_EMBEDDINGS_ENABLED": "false"}, clear=False):
            local_retriever._MODEL_BACKENDS.clear()
            local_retriever._RETRIEVER_CACHE.clear()
            retriever = get_local_text_retriever(
                namespace="test",
                item_ids=["jndi", "mvc"],
                texts=[
                    "JNDI jee jndi-lookup resource-ref lookup-on-startup setting",
                    "MockMvc web request session setup and controller test",
                ],
                fallback_embeddings=[
                    embed_text("JNDI jee jndi-lookup resource-ref lookup-on-startup setting"),
                    embed_text("MockMvc web request session setup and controller test"),
                ],
                retriever_config=build_retriever_config({"dense_fallback_enabled": True}),
            )

            ranked = retriever.rank("jee jndi-lookup resource-ref", top_k=2)

            self.assertEqual(ranked[0].index, 0)
            self.assertGreater(ranked[0].bm25_score, ranked[1].bm25_score)
            self.assertIn("hash-embedding-v1", retriever.retriever_name)

    def test_selective_rewrite_uses_candidate_memory_affinity(self) -> None:
        original_builder = runtime.build_rewrite_candidates_v2
        runtime.build_rewrite_candidates_v2 = lambda *args, **kwargs: [
            {
                "label": "memory_anchored",
                "query": "Spring Security OAuth2AccessTokenResponseHttpMessageConverter 사용 예시",
            }
        ]
        try:
            chunks = [
                ChunkItem(
                    chunk_id="expected",
                    document_id="doc-security",
                    text="Spring Security OAuth2AccessTokenResponseHttpMessageConverter 사용 예시와 설정 방법",
                    embedding=embed_text(
                        "Spring Security OAuth2AccessTokenResponseHttpMessageConverter 사용 예시와 설정 방법"
                    ),
                ),
                ChunkItem(
                    chunk_id="noise",
                    document_id="doc-noise",
                    text="MockMvc 세션 테스트 설정",
                    embedding=embed_text("MockMvc 세션 테스트 설정"),
                ),
            ]
            memories = [
                MemoryItem(
                    memory_id="memory-1",
                    query_text="Spring Security OAuth2AccessTokenResponseHttpMessageConverter 사용 예시",
                    target_doc_id="doc-security",
                    target_chunk_ids=["expected"],
                    gating_preset="full_gating",
                    generation_strategy="A",
                    source_gate_run_id="gate-run",
                    embedding=embed_text("Spring Security OAuth2AccessTokenResponseHttpMessageConverter 사용 예시"),
                )
            ]

            outcome, retrieval = runtime.run_selective_rewrite(
                raw_query="OAuth2AccessTokenResponseHttpMessageConverter 방법은 같이 쓰는 예시?",
                query_language="ko",
                session_context={},
                chunks=chunks,
                memories=memories,
                memory_top_n_value=5,
                candidate_count=1,
                threshold=0.01,
                retrieval_top_k=2,
                preset_filter="full_gating",
                source_gate_run_id="gate-run",
                strategy_filters=["A"],
                retriever_config=build_retriever_config({"dense_fallback_enabled": True}),
            )

            self.assertTrue(outcome.rewrite_applied)
            self.assertEqual(outcome.rewrite_reason, "delta_above_threshold")
            self.assertGreater(outcome.best_candidate_confidence, outcome.raw_confidence)
            self.assertEqual(retrieval[0].chunk_id, "expected")
            self.assertGreater(outcome.candidates[0]["memory_similarity_delta"], 0.0)
        finally:
            runtime.build_rewrite_candidates_v2 = original_builder

    def test_memory_top_n_exposes_anchor_metadata(self) -> None:
        memories = [
            MemoryItem(
                memory_id="memory-anchor-1",
                query_text="Spring Security DigestAuthenticationFilter 설정 방법",
                target_doc_id="doc-security",
                target_chunk_ids=["chk-1"],
                gating_preset="full_gating",
                generation_strategy="A",
                source_gate_run_id="gate-run",
                embedding=embed_text("Spring Security DigestAuthenticationFilter 설정 방법"),
                product="spring-security",
                glossary_terms=["DigestAuthenticationFilter", "spring.security.filter.order"],
            )
        ]

        ranked = runtime.memory_top_n(
            "DigestAuthenticationFilter 같이 쓸 때 포인트?",
            memories,
            top_n=1,
            preset_filter="full_gating",
            source_gate_run_id="gate-run",
            strategy_filters=["A"],
            retriever_config=build_retriever_config({"dense_fallback_enabled": True}),
        )

        self.assertEqual(len(ranked), 1)
        self.assertEqual(ranked[0]["product"], "spring-security")
        self.assertIn("DigestAuthenticationFilter", ranked[0]["glossary_terms"])

    def test_rewrite_payload_contains_anchor_candidates(self) -> None:
        class _FakeRewriteClient:
            def __init__(self) -> None:
                self.last_user_prompt = ""

            def chat_json(self, **kwargs):
                self.last_user_prompt = str(kwargs.get("user_prompt") or "")
                return {"candidates": [{"label": "explicit_standalone", "query": "DigestAuthenticationFilter 설정 포인트"}]}

        fake_client = _FakeRewriteClient()
        memory_rows = [
            {
                "memory_id": "memory-anchor-1",
                "query_text": "Spring Security DigestAuthenticationFilter 설정 방법",
                "target_doc_id": "doc-security",
                "target_chunk_ids": ["chk-1"],
                "generation_strategy": "A",
                "product": "spring-security",
                "glossary_terms": ["DigestAuthenticationFilter", "spring.security.filter.order"],
                "similarity": 0.9,
            }
        ]

        with patch.object(runtime, "_REWRITE_PROMPT_TEXT", "rewrite prompt for test"), patch.object(
            runtime,
            "_rewrite_client",
            return_value=fake_client,
        ):
            candidates = runtime.build_rewrite_candidates_v2(
                "Security DigestAuthenticationFilter 같이 쓸때 포인트?",
                memory_rows,
                session_context={},
                candidate_count=1,
                query_language="ko",
            )

        self.assertEqual(len(candidates), 1)
        payload = json.loads(fake_client.last_user_prompt)
        anchor_candidates = payload.get("anchor_candidates") or []
        anchor_terms = payload.get("anchor_terms") or []
        self.assertGreaterEqual(len(anchor_candidates), 1)
        self.assertIn("DigestAuthenticationFilter", anchor_terms)
        self.assertTrue(any(item.get("source") == "memory_glossary" for item in anchor_candidates))

    def test_rewrite_payload_skips_anchor_when_disabled(self) -> None:
        class _FakeRewriteClient:
            def __init__(self) -> None:
                self.last_user_prompt = ""

            def chat_json(self, **kwargs):
                self.last_user_prompt = str(kwargs.get("user_prompt") or "")
                return {"candidates": [{"label": "explicit_standalone", "query": "DigestAuthenticationFilter 설정 포인트"}]}

        fake_client = _FakeRewriteClient()
        memory_rows = [
            {
                "memory_id": "memory-anchor-1",
                "query_text": "Spring Security DigestAuthenticationFilter 설정 방법",
                "target_doc_id": "doc-security",
                "target_chunk_ids": ["chk-1"],
                "generation_strategy": "A",
                "product": "spring-security",
                "glossary_terms": ["DigestAuthenticationFilter", "spring.security.filter.order"],
                "similarity": 0.9,
            }
        ]

        with patch.object(runtime, "_REWRITE_PROMPT_TEXT", "rewrite prompt for test"), patch.object(
            runtime,
            "_rewrite_client",
            return_value=fake_client,
        ):
            candidates = runtime.build_rewrite_candidates_v2(
                "Security DigestAuthenticationFilter 같이 쓸때 포인트?",
                memory_rows,
                session_context={},
                candidate_count=1,
                query_language="ko",
                rewrite_anchor_injection_enabled=False,
            )

        self.assertEqual(len(candidates), 1)
        payload = json.loads(fake_client.last_user_prompt)
        self.assertNotIn("anchor_candidates", payload)
        self.assertNotIn("anchor_terms", payload)


if __name__ == "__main__":
    unittest.main()
