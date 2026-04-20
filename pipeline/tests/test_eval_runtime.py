from __future__ import annotations

import os
import unittest
from unittest.mock import patch

from pipeline.common.cohere_reranker import CohereRerankConfig, CohereReranker
from pipeline.common.embeddings import embed_text
from pipeline.common import local_retriever
from pipeline.common.local_retriever import get_local_text_retriever
from pipeline.eval import runtime
from pipeline.eval.runtime import ChunkItem, MemoryItem


class EvalRuntimeRewriteTests(unittest.TestCase):
    def tearDown(self) -> None:
        local_retriever._MODEL_BACKEND = None
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
            local_retriever._MODEL_BACKEND = None
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
            )

            ranked = retriever.rank("jee jndi-lookup resource-ref", top_k=2)

            self.assertEqual(ranked[0].index, 0)
            self.assertGreater(ranked[0].bm25_score, ranked[1].bm25_score)
            self.assertIn("hash-embedding-v1", retriever.retriever_name)

    def test_selective_rewrite_uses_candidate_memory_affinity(self) -> None:
        original_builder = runtime.build_rewrite_candidates
        runtime.build_rewrite_candidates = lambda *args, **kwargs: [
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
            )

            self.assertTrue(outcome.rewrite_applied)
            self.assertEqual(outcome.rewrite_reason, "delta_above_threshold")
            self.assertGreater(outcome.best_candidate_confidence, outcome.raw_confidence)
            self.assertEqual(retrieval[0].chunk_id, "expected")
            self.assertGreater(outcome.candidates[0]["memory_similarity_delta"], 0.0)
        finally:
            runtime.build_rewrite_candidates = original_builder


if __name__ == "__main__":
    unittest.main()
