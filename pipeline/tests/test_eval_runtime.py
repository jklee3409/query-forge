from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from pipeline.common.experiment_config import load_experiment_config
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

    def _single_retrieval(self, query_text: str, *, score: float, chunk_id: str) -> list[runtime.RetrievalCandidate]:
        return [
            runtime.RetrievalCandidate(
                chunk_id=chunk_id,
                document_id=f"doc-{chunk_id}",
                score=score,
                text=query_text,
            )
        ]

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
                rewrite_adoption_policy={
                    "thresholds": {
                        "min_improvement": 0.0,
                        "preservation_floor": 0.0,
                    }
                },
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
                "glossary_terms": [
                    "DigestAuthenticationFilter",
                    "spring.security.filter.order",
                    "수 있습니다",
                    "지원합니다",
                ],
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
                rewrite_terminology_hints_max_count=3,
            )

        self.assertEqual(len(candidates), 1)
        payload = json.loads(fake_client.last_user_prompt)
        anchor_candidates = payload.get("anchor_candidates") or []
        anchor_terms = payload.get("anchor_terms") or []
        terminology_hints = payload.get("terminology_hints") or {}
        terminology_terms = terminology_hints.get("terms") or []
        self.assertGreaterEqual(len(anchor_candidates), 1)
        self.assertIn("DigestAuthenticationFilter", anchor_terms)
        self.assertNotIn("수 있습니다", anchor_terms)
        self.assertNotIn("지원합니다", anchor_terms)
        self.assertTrue(any(item.get("source") == "memory_glossary" for item in anchor_candidates))
        self.assertIn("DigestAuthenticationFilter", terminology_terms)
        self.assertNotIn("수 있습니다", terminology_terms)
        self.assertNotIn("지원합니다", terminology_terms)
        self.assertLessEqual(len(terminology_terms), 3)

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
        self.assertNotIn("terminology_hints", payload)

    def test_rewrite_failure_policy_fail_run_raises(self) -> None:
        class _FailingRewriteClient:
            def chat_json(self, **kwargs):
                raise RuntimeError("rewrite llm failed")

        runtime_stats: dict[str, int] = {}
        with patch.object(runtime, "_REWRITE_PROMPT_TEXT", "rewrite prompt for test"), patch.object(
            runtime,
            "_rewrite_client",
            return_value=_FailingRewriteClient(),
        ):
            with self.assertRaisesRegex(RuntimeError, "rewrite llm failed"):
                runtime.build_rewrite_candidates_v2(
                    "DigestAuthenticationFilter ?ㅼ젙 諛⑸쾿",
                    [],
                    session_context={},
                    candidate_count=2,
                    query_language="ko",
                    rewrite_failure_policy="fail_run",
                    rewrite_runtime_stats=runtime_stats,
                )

        self.assertEqual(runtime_stats.get("llm_attempted_count"), 1)
        self.assertEqual(runtime_stats.get("llm_failure_count"), 1)
        self.assertEqual(runtime_stats.get("heuristic_fallback_count", 0), 0)

    def test_rewrite_failure_policy_skip_to_raw_returns_empty_candidates(self) -> None:
        class _FailingRewriteClient:
            def chat_json(self, **kwargs):
                raise RuntimeError("rewrite llm failed")

        runtime_stats: dict[str, int] = {}
        with patch.object(runtime, "_REWRITE_PROMPT_TEXT", "rewrite prompt for test"), patch.object(
            runtime,
            "_rewrite_client",
            return_value=_FailingRewriteClient(),
        ):
            candidates = runtime.build_rewrite_candidates_v2(
                "DigestAuthenticationFilter ?ㅼ젙 諛⑸쾿",
                [],
                session_context={},
                candidate_count=2,
                query_language="ko",
                rewrite_failure_policy="skip_to_raw",
                rewrite_runtime_stats=runtime_stats,
            )

        self.assertEqual(candidates, [])
        self.assertEqual(runtime_stats.get("llm_attempted_count"), 1)
        self.assertEqual(runtime_stats.get("llm_failure_count"), 1)
        self.assertEqual(runtime_stats.get("heuristic_fallback_count", 0), 0)

    def test_rewrite_failure_policy_heuristic_fallback_uses_heuristic_candidates(self) -> None:
        class _FailingRewriteClient:
            def chat_json(self, **kwargs):
                raise RuntimeError("rewrite llm failed")

        heuristic_rows = [{"label": "heuristic", "query": "DigestAuthenticationFilter troubleshooting"}]
        runtime_stats: dict[str, int] = {}
        with patch.object(runtime, "_REWRITE_PROMPT_TEXT", "rewrite prompt for test"), patch.object(
            runtime,
            "_rewrite_client",
            return_value=_FailingRewriteClient(),
        ), patch.object(
            runtime,
            "_heuristic_rewrite_candidates_v2",
            return_value=heuristic_rows,
        ):
            candidates = runtime.build_rewrite_candidates_v2(
                "DigestAuthenticationFilter ?ㅼ젙 諛⑸쾿",
                [],
                session_context={},
                candidate_count=2,
                query_language="ko",
                rewrite_failure_policy="heuristic_fallback",
                rewrite_runtime_stats=runtime_stats,
            )

        self.assertEqual(candidates, heuristic_rows)
        self.assertEqual(runtime_stats.get("llm_attempted_count"), 1)
        self.assertEqual(runtime_stats.get("llm_failure_count"), 1)
        self.assertEqual(runtime_stats.get("heuristic_fallback_count"), 1)

    def test_selective_rewrite_rejects_candidate_with_terminology_loss(self) -> None:
        raw_query = "DigestAuthenticationFilter 설정 방법"
        candidate_query = "security filter configuration guide"

        def fake_retrieve(query_text: str, *args, **kwargs):
            if query_text == raw_query:
                return self._single_retrieval(query_text, score=0.20, chunk_id="raw")
            return self._single_retrieval(query_text, score=0.85, chunk_id="cand")

        def fake_memory(query_text: str, *args, **kwargs):
            return [
                {
                    "memory_id": "m1",
                    "query_text": query_text,
                    "similarity": 0.0,
                    "glossary_terms": ["DigestAuthenticationFilter"],
                }
            ]

        with patch.object(runtime, "build_rewrite_candidates_v2", return_value=[{"label": "c1", "query": candidate_query}]), patch.object(
            runtime,
            "retrieve_top_k",
            side_effect=fake_retrieve,
        ), patch.object(runtime, "memory_top_n", side_effect=fake_memory):
            outcome, _ = runtime.run_selective_rewrite(
                raw_query=raw_query,
                query_language="ko",
                query_category="definition",
                session_context={},
                chunks=[],
                memories=[],
                memory_top_n_value=3,
                candidate_count=1,
                threshold=0.01,
                retrieval_top_k=3,
                retriever_config=build_retriever_config({"dense_fallback_enabled": True}),
            )

        self.assertFalse(outcome.rewrite_applied)
        self.assertEqual(outcome.rewrite_reason, "preservation_below_floor")
        self.assertEqual(outcome.candidates[0]["rejection_reason"], "preservation_below_floor")
        self.assertLess(outcome.candidates[0]["terminology_preservation_score"], outcome.candidates[0]["preservation_floor"])

    def test_selective_rewrite_rejects_candidate_with_insufficient_gain(self) -> None:
        raw_query = "DigestAuthenticationFilter 설정 방법"
        candidate_query = "DigestAuthenticationFilter 설정 포인트"

        def fake_retrieve(query_text: str, *args, **kwargs):
            if query_text == raw_query:
                return self._single_retrieval(query_text, score=0.20, chunk_id="same")
            return self._single_retrieval(query_text, score=0.24, chunk_id="same")

        def fake_memory(query_text: str, *args, **kwargs):
            return [
                {
                    "memory_id": "m1",
                    "query_text": query_text,
                    "similarity": 0.0,
                    "glossary_terms": ["DigestAuthenticationFilter"],
                }
            ]

        with patch.object(runtime, "build_rewrite_candidates_v2", return_value=[{"label": "c1", "query": candidate_query}]), patch.object(
            runtime,
            "retrieve_top_k",
            side_effect=fake_retrieve,
        ), patch.object(runtime, "memory_top_n", side_effect=fake_memory):
            outcome, _ = runtime.run_selective_rewrite(
                raw_query=raw_query,
                query_language="ko",
                query_category="definition",
                session_context={},
                chunks=[],
                memories=[],
                memory_top_n_value=3,
                candidate_count=1,
                threshold=0.08,
                retrieval_top_k=3,
                retriever_config=build_retriever_config({"dense_fallback_enabled": True}),
            )

        self.assertFalse(outcome.rewrite_applied)
        self.assertEqual(outcome.rewrite_reason, "delta_below_threshold")
        self.assertEqual(outcome.candidates[0]["rejection_reason"], "delta_below_threshold")
        self.assertLess(outcome.best_candidate_confidence - outcome.raw_confidence, 0.08)

    def test_selective_rewrite_accepts_candidate_with_gain_and_preserved_anchor(self) -> None:
        raw_query = "DigestAuthenticationFilter 설정 방법"
        candidate_query = "DigestAuthenticationFilter security config"

        def fake_retrieve(query_text: str, *args, **kwargs):
            if query_text == raw_query:
                return self._single_retrieval(query_text, score=0.20, chunk_id="raw")
            return self._single_retrieval(query_text, score=0.80, chunk_id="cand")

        def fake_memory(query_text: str, *args, **kwargs):
            return [
                {
                    "memory_id": "m1",
                    "query_text": query_text,
                    "similarity": 0.0,
                    "glossary_terms": ["DigestAuthenticationFilter"],
                }
            ]

        with patch.object(runtime, "build_rewrite_candidates_v2", return_value=[{"label": "c1", "query": candidate_query}]), patch.object(
            runtime,
            "retrieve_top_k",
            side_effect=fake_retrieve,
        ), patch.object(runtime, "memory_top_n", side_effect=fake_memory):
            outcome, retrieval = runtime.run_selective_rewrite(
                raw_query=raw_query,
                query_language="ko",
                query_category="definition",
                session_context={},
                chunks=[],
                memories=[],
                memory_top_n_value=3,
                candidate_count=1,
                threshold=0.01,
                retrieval_top_k=3,
                retriever_config=build_retriever_config({"dense_fallback_enabled": True}),
            )

        self.assertTrue(outcome.rewrite_applied)
        self.assertEqual(outcome.rewrite_reason, "delta_above_threshold")
        self.assertEqual(retrieval[0].chunk_id, "cand")
        self.assertGreater(outcome.candidates[0]["retrieval_gain_score"], 0.0)
        self.assertGreaterEqual(outcome.candidates[0]["terminology_preservation_score"], outcome.candidates[0]["preservation_floor"])

    def test_selective_rewrite_applies_category_aware_threshold_for_short_user(self) -> None:
        raw_query = "DigestAuthenticationFilter 설정 방법"
        candidate_query = "DigestAuthenticationFilter security setup"
        policy = {
            "thresholds": {
                "min_improvement": 0.01,
                "preservation_floor": 0.70,
            },
            "category_overrides": {
                "short_user": {
                    "thresholds": {
                        "min_improvement": 0.20,
                    }
                }
            },
        }

        def fake_retrieve(query_text: str, *args, **kwargs):
            if query_text == raw_query:
                return self._single_retrieval(query_text, score=0.20, chunk_id="raw")
            return self._single_retrieval(query_text, score=0.35, chunk_id="cand")

        def fake_memory(query_text: str, *args, **kwargs):
            return [
                {
                    "memory_id": "m1",
                    "query_text": query_text,
                    "similarity": 0.0,
                    "glossary_terms": ["DigestAuthenticationFilter"],
                }
            ]

        with patch.object(runtime, "build_rewrite_candidates_v2", return_value=[{"label": "c1", "query": candidate_query}]), patch.object(
            runtime,
            "retrieve_top_k",
            side_effect=fake_retrieve,
        ), patch.object(runtime, "memory_top_n", side_effect=fake_memory):
            general_outcome, _ = runtime.run_selective_rewrite(
                raw_query=raw_query,
                query_language="ko",
                query_category="definition",
                session_context={},
                chunks=[],
                memories=[],
                memory_top_n_value=3,
                candidate_count=1,
                threshold=0.01,
                retrieval_top_k=3,
                rewrite_adoption_policy=policy,
                retriever_config=build_retriever_config({"dense_fallback_enabled": True}),
            )
            short_user_outcome, _ = runtime.run_selective_rewrite(
                raw_query=raw_query,
                query_language="ko",
                query_category="short_user",
                session_context={},
                chunks=[],
                memories=[],
                memory_top_n_value=3,
                candidate_count=1,
                threshold=0.01,
                retrieval_top_k=3,
                rewrite_adoption_policy=policy,
                retriever_config=build_retriever_config({"dense_fallback_enabled": True}),
            )

        self.assertTrue(general_outcome.rewrite_applied)
        self.assertFalse(short_user_outcome.rewrite_applied)
        self.assertEqual(short_user_outcome.rewrite_reason, "delta_below_threshold")

    def test_yaml_rewrite_policy_override_controls_threshold(self) -> None:
        raw_query = "DigestAuthenticationFilter 설정 방법"
        candidate_query = "DigestAuthenticationFilter security setup"

        config_text = """\
experiment_key: phase2_policy_test
rewrite_candidate_count: 1
rewrite_threshold: 0.01
rewrite_adoption_policy:
  thresholds:
    min_improvement: 0.20
"""
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "phase2_policy_test.yaml").write_text(config_text, encoding="utf-8")
            config = load_experiment_config("phase2_policy_test", experiment_root=root)

        def fake_retrieve(query_text: str, *args, **kwargs):
            if query_text == raw_query:
                return self._single_retrieval(query_text, score=0.20, chunk_id="raw")
            return self._single_retrieval(query_text, score=0.40, chunk_id="cand")

        def fake_memory(query_text: str, *args, **kwargs):
            return [
                {
                    "memory_id": "m1",
                    "query_text": query_text,
                    "similarity": 0.0,
                    "glossary_terms": ["DigestAuthenticationFilter"],
                }
            ]

        with patch.object(runtime, "build_rewrite_candidates_v2", return_value=[{"label": "c1", "query": candidate_query}]), patch.object(
            runtime,
            "retrieve_top_k",
            side_effect=fake_retrieve,
        ), patch.object(runtime, "memory_top_n", side_effect=fake_memory):
            outcome, _ = runtime.run_selective_rewrite(
                raw_query=raw_query,
                query_language="ko",
                query_category="definition",
                session_context={},
                chunks=[],
                memories=[],
                memory_top_n_value=3,
                candidate_count=config.rewrite_candidate_count,
                threshold=config.rewrite_threshold,
                retrieval_top_k=3,
                rewrite_adoption_policy=config.rewrite_adoption_policy,
                retriever_config=build_retriever_config({"dense_fallback_enabled": True}),
            )

        self.assertEqual(config.rewrite_adoption_policy["thresholds"]["min_improvement"], 0.2)
        self.assertFalse(outcome.rewrite_applied)
        self.assertEqual(outcome.rewrite_reason, "delta_below_threshold")


if __name__ == "__main__":
    unittest.main()
