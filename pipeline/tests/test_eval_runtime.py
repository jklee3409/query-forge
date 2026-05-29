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
from pipeline.common.local_retriever import build_canonical_lexical_text, build_retriever_config, get_local_text_retriever
from pipeline.eval import runtime
from pipeline.eval.runtime import ChunkItem, MemoryItem


class EvalRuntimeRewriteTests(unittest.TestCase):
    def tearDown(self) -> None:
        local_retriever._MODEL_BACKENDS.clear()
        local_retriever._RETRIEVER_CACHE.clear()
        runtime._REWRITE_PROMPT_TEXTS.clear()
        runtime._REWRITE_PROMPT_TEXT = None

    def _single_retrieval(self, query_text: str, *, score: float, chunk_id: str) -> list[runtime.RetrievalCandidate]:
        return [
            runtime.RetrievalCandidate(
                chunk_id=chunk_id,
                document_id=f"doc-{chunk_id}",
                score=score,
                text=query_text,
            )
        ]

    def _transactional_canonical_payload(self) -> dict[str, object]:
        def anchor(alias: str, *, language: str, term_type: str, normalized_alias: str | None = None) -> dict[str, object]:
            return {
                "input_alias": alias,
                "display_alias": alias,
                "normalized_alias": normalized_alias or alias.casefold(),
                "alias_language": language,
                "resolution_status": "mapped" if alias != "@Transactional" else "self_fallback",
                "mapping_id": None if alias == "@Transactional" else f"mapping-{len(alias)}",
                "canonical_term_id": "term-transactional",
                "canonical_form": "@Transactional",
                "canonical_normalized_form": "@transactional",
                "term_type": term_type,
                "confidence": 1.0,
                "review_status": "approved",
                "used_for_scoring": True,
                "source_field": "glossary_terms",
            }

        return {
            "schema_version": "canonical-anchor-runtime-v1",
            "mapping_version": "anchor-map-v1",
            "normalization_version": "anchor-normalize-v1",
            "source_context": {"kind": "memory_entry", "source_id": "m-canonical"},
            "canonical_terms": ["@Transactional"],
            "canonical_term_ids": ["term-transactional"],
            "unresolved_aliases": [],
            "anchors": [
                anchor("@Transactional", language="en", term_type="annotation", normalized_alias="@transactional"),
                anchor("transaction readonly", language="en", term_type="concept"),
                anchor("transactional read only", language="en", term_type="concept"),
                anchor("트랜잭션 읽기 전용", language="ko", term_type="concept", normalized_alias="트랜잭션읽기전용"),
                {
                    "input_alias": "transaction read-only candidate",
                    "display_alias": "transaction read-only candidate",
                    "normalized_alias": "transaction read only candidate",
                    "alias_language": "en",
                    "resolution_status": "miss",
                    "canonical_term_id": None,
                    "canonical_form": None,
                    "term_type": "concept",
                    "used_for_scoring": False,
                },
            ],
        }

    def _canonical_payload_with_review_noise(self) -> dict[str, object]:
        def anchor(
            *,
            display_alias: str,
            canonical_form: str | None,
            resolution_status: str,
            review_status: str | None,
            used_for_scoring: bool,
            canonical_term_id: str | None = "term-test",
            pending_candidates: list[dict[str, object]] | None = None,
        ) -> dict[str, object]:
            payload: dict[str, object] = {
                "input_alias": display_alias,
                "display_alias": display_alias,
                "normalized_alias": display_alias.casefold(),
                "alias_language": "en",
                "resolution_status": resolution_status,
                "mapping_id": None,
                "canonical_term_id": canonical_term_id,
                "canonical_form": canonical_form,
                "canonical_normalized_form": canonical_form.casefold() if canonical_form else None,
                "term_type": "concept",
                "confidence": 1.0,
                "review_status": review_status,
                "used_for_scoring": used_for_scoring,
                "source_field": "glossary_terms",
            }
            if pending_candidates is not None:
                payload["pending_candidates"] = pending_candidates
            return payload

        return {
            "schema_version": "canonical-anchor-runtime-v1",
            "mapping_version": "anchor-map-v1",
            "normalization_version": "anchor-normalize-v1",
            "anchors": [
                anchor(
                    display_alias="transaction readonly",
                    canonical_form="@Transactional",
                    resolution_status="mapped",
                    review_status="approved",
                    used_for_scoring=True,
                    canonical_term_id="term-transactional",
                ),
                anchor(
                    display_alias="SelfFallbackAnchor",
                    canonical_form="SelfFallbackAnchor",
                    resolution_status="self_fallback",
                    review_status=None,
                    used_for_scoring=True,
                    canonical_term_id="term-self",
                ),
                anchor(
                    display_alias="ReviewOnlyAnchor",
                    canonical_form="ReviewOnlyAnchor",
                    resolution_status="mapped",
                    review_status="pending",
                    used_for_scoring=True,
                    canonical_term_id="term-review",
                ),
                anchor(
                    display_alias="AmbiguousAnchor",
                    canonical_form="AmbiguousAnchor",
                    resolution_status="ambiguous",
                    review_status="approved",
                    used_for_scoring=True,
                    canonical_term_id="term-ambiguous",
                ),
                anchor(
                    display_alias="UnresolvedAnchor",
                    canonical_form=None,
                    resolution_status="miss",
                    review_status=None,
                    used_for_scoring=False,
                    canonical_term_id=None,
                ),
                anchor(
                    display_alias="PendingCandidateAnchor",
                    canonical_form="PendingCandidateAnchor",
                    resolution_status="mapped",
                    review_status="approved",
                    used_for_scoring=True,
                    canonical_term_id="term-pending",
                    pending_candidates=[{"canonical_form": "OtherAnchor"}],
                ),
            ],
        }

    def test_rewrite_prompt_text_prefers_v3_for_korean_and_language_specific_prompt_for_english(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            prompt_root = Path(temp_dir)
            rewrite_dir = prompt_root / "rewrite"
            rewrite_dir.mkdir(parents=True)
            (rewrite_dir / "selective_rewrite_v3.md").write_text("ko v3 rewrite prompt", encoding="utf-8")
            (rewrite_dir / "selective_rewrite_v2.md").write_text("ko rewrite prompt", encoding="utf-8")
            (rewrite_dir / "selective_rewrite_en_v1.md").write_text("en rewrite prompt", encoding="utf-8")

            with patch.dict(os.environ, {"PROMPT_ROOT": str(prompt_root)}):
                runtime._REWRITE_PROMPT_TEXTS.clear()
                self.assertEqual(runtime._rewrite_prompt_text(query_language="ko"), "ko v3 rewrite prompt")
                self.assertEqual(runtime._rewrite_prompt_text(query_language="en"), "en rewrite prompt")

    def test_rewrite_prompt_text_uses_detailed_intent_profile_prompt(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            prompt_root = Path(temp_dir)
            rewrite_dir = prompt_root / "rewrite"
            rewrite_dir.mkdir(parents=True)
            (rewrite_dir / "selective_rewrite_detailed_intent_v1.md").write_text(
                "detailed intent rewrite prompt",
                encoding="utf-8",
            )
            (rewrite_dir / "selective_rewrite_v3.md").write_text("ko v3 rewrite prompt", encoding="utf-8")

            with patch.dict(os.environ, {"PROMPT_ROOT": str(prompt_root)}):
                runtime._REWRITE_PROMPT_TEXTS.clear()
                self.assertEqual(
                    runtime._rewrite_prompt_text(
                        query_language="ko",
                        rewrite_query_profile="detailed_intent",
                    ),
                    "detailed intent rewrite prompt",
                )
                self.assertEqual(runtime._rewrite_prompt_text(query_language="ko"), "ko v3 rewrite prompt")

    def test_rewrite_prompt_text_falls_back_to_v2_when_v3_missing(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            prompt_root = Path(temp_dir)
            rewrite_dir = prompt_root / "rewrite"
            rewrite_dir.mkdir(parents=True)
            (rewrite_dir / "selective_rewrite_v2.md").write_text("ko rewrite prompt", encoding="utf-8")

            with patch.dict(os.environ, {"PROMPT_ROOT": str(prompt_root)}):
                runtime._REWRITE_PROMPT_TEXTS.clear()
                self.assertEqual(runtime._rewrite_prompt_text(query_language="ko"), "ko rewrite prompt")

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

    def test_local_retriever_uses_canonical_lexical_text_without_replacing_dense_query(self) -> None:
        payload = self._transactional_canonical_payload()
        raw_query = "transaction readonly setup"
        lexical_query = build_canonical_lexical_text(raw_query, [payload], match_text=raw_query)
        config = build_retriever_config(
            {
                "retriever_mode": "hybrid",
                "dense_fallback_enabled": True,
                "rerank_enabled": False,
                "retriever_fusion_weights": {"dense": 0.0, "bm25": 0.7, "technical": 0.3},
            }
        )

        with patch.dict(os.environ, {"QUERY_FORGE_LOCAL_REAL_EMBEDDINGS_ENABLED": "false"}, clear=False):
            local_retriever._MODEL_BACKENDS.clear()
            local_retriever._RETRIEVER_CACHE.clear()
            retriever = get_local_text_retriever(
                namespace="canonical-test",
                item_ids=["canonical", "plain"],
                texts=[
                    "Spring transaction annotation guide @Transactional",
                    "CacheControl header max-age overview",
                ],
                lexical_texts=[
                    build_canonical_lexical_text(
                        "Spring transaction annotation guide @Transactional",
                        payload,
                    ),
                    "CacheControl header max-age overview",
                ],
                fallback_embeddings=[
                    embed_text("Spring transaction annotation guide @Transactional"),
                    embed_text("CacheControl header max-age overview"),
                ],
                retriever_config=config,
            )

            captured_dense_queries: list[str] = []

            def fake_score(self, query_text, dense_passages, fallback_embeddings):
                captured_dense_queries.append(query_text)
                return [0.0, 0.0]

            with patch.object(local_retriever._DenseBackend, "score_query", fake_score):
                ranked = retriever.rank(
                    raw_query,
                    top_k=2,
                    lexical_query_text=lexical_query,
                )

        self.assertEqual(captured_dense_queries, [raw_query])
        self.assertEqual(ranked[0].index, 0)
        self.assertGreater(ranked[0].bm25_score, 0.0)
        self.assertGreater(ranked[0].technical_score, 0.0)

    def test_memory_top_n_uses_canonical_alias_expansion_when_metadata_exists(self) -> None:
        payload = self._transactional_canonical_payload()
        config = build_retriever_config(
            {
                "retriever_mode": "hybrid",
                "dense_fallback_enabled": True,
                "rerank_enabled": False,
                "retriever_fusion_weights": {"dense": 0.0, "bm25": 0.7, "technical": 0.3},
            }
        )
        memories = [
            MemoryItem(
                memory_id="m-canonical",
                query_text="Spring annotation guide @Transactional",
                target_doc_id="doc-tx",
                target_chunk_ids=["chunk-tx"],
                gating_preset="full_gating",
                generation_strategy="A",
                source_gate_run_id="gate-1",
                embedding=embed_text("Spring annotation guide @Transactional"),
                canonical_anchors=payload,
            ),
            MemoryItem(
                memory_id="m-plain",
                query_text="transaction manager timeout overview",
                target_doc_id="doc-other",
                target_chunk_ids=["chunk-other"],
                gating_preset="full_gating",
                generation_strategy="A",
                source_gate_run_id="gate-1",
                embedding=embed_text("transaction manager timeout overview"),
            ),
        ]

        with patch.dict(os.environ, {"QUERY_FORGE_LOCAL_REAL_EMBEDDINGS_ENABLED": "false"}, clear=False):
            local_retriever._MODEL_BACKENDS.clear()
            local_retriever._RETRIEVER_CACHE.clear()
            without_metadata = runtime.memory_top_n(
                "transaction readonly setup",
                [
                    MemoryItem(
                        memory_id="m-canonical",
                        query_text="Spring annotation guide @Transactional",
                        target_doc_id="doc-tx",
                        target_chunk_ids=["chunk-tx"],
                        gating_preset="full_gating",
                        generation_strategy="A",
                        source_gate_run_id="gate-1",
                        embedding=embed_text("Spring annotation guide @Transactional"),
                    ),
                    memories[1],
                ],
                top_n=2,
                retriever_config=config,
            )
            with_metadata = runtime.memory_top_n(
                "transaction readonly setup",
                memories,
                top_n=2,
                retriever_config=config,
            )

        self.assertEqual(without_metadata[0]["memory_id"], "m-plain")
        self.assertEqual(with_metadata[0]["memory_id"], "m-canonical")
        self.assertGreater(with_metadata[0]["bm25_score"], 0.0)
        self.assertGreater(with_metadata[0]["technical_token_overlap"], 0.0)

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
                self.user_prompts: list[str] = []

            def chat_json(self, **kwargs):
                user_prompt = str(kwargs.get("user_prompt") or "")
                self.user_prompts.append(user_prompt)
                payload = json.loads(user_prompt)
                if payload.get("candidate_policy", {}).get("mode") == "memory_expanded":
                    return {"candidates": [{"label": "expanded", "query": "DigestAuthenticationFilter 설정 포인트"}]}
                return {"candidates": [{"label": "standalone", "query": "Security DigestAuthenticationFilter 같이 쓸때 포인트"}]}

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
                "canonical_anchors": self._transactional_canonical_payload(),
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
                candidate_count=2,
                query_language="ko",
                rewrite_terminology_hints_max_count=3,
                retrieval_context={
                    "retrieval_backend": "db_ann",
                    "vector_store": "postgresql-pgvector",
                    "retriever_mode": "hybrid",
                    "dense_embedding_model": "intfloat/multilingual-e5-small",
                    "fusion_weights": {"dense": 0.6, "bm25": 0.32, "technical": 0.08},
                },
                raw_retrieval_context=[
                    {
                        "rank": 1,
                        "technical_terms": ["DigestAuthenticationFilter"],
                        "text_preview": "DigestAuthenticationFilter config",
                    }
                ],
                domain_context=runtime._rewrite_domain_context("spring-security"),
                rewrite_query_profile="detailed_intent",
                trusted_memory_items=[{**memory_rows[0], "source_memory_index": 1}],
            )

        self.assertEqual(len(candidates), 2)
        self.assertEqual(candidates[0]["label"], "standalone")
        self.assertEqual(candidates[0]["source_memory_index"], 0)
        self.assertEqual(candidates[0]["added_anchors"], [])
        standalone_payload = json.loads(fake_client.user_prompts[0])
        self.assertEqual(standalone_payload.get("candidate_policy", {}).get("mode"), "raw_standalone")
        self.assertEqual(standalone_payload.get("rewrite_query_profile"), "detailed_intent")
        self.assertEqual(
            standalone_payload.get("candidate_policy", {}).get("rewrite_query_profile"),
            "detailed_intent",
        )
        self.assertEqual(standalone_payload.get("top_memory_candidates"), [])
        self.assertEqual(
            standalone_payload.get("raw_retrieval_context", [{}])[0].get("technical_terms"),
            ["DigestAuthenticationFilter"],
        )
        domain_context = standalone_payload.get("domain_context") or {}
        self.assertEqual(domain_context.get("current_technical_domain"), "Spring")
        self.assertIn(
            {"ko": "보안", "en": "Spring Security"},
            domain_context.get("ko_to_en_term_examples") or [],
        )
        self.assertNotIn("anchor_candidates", standalone_payload)
        self.assertNotIn("terminology_hints", standalone_payload)

        payload = json.loads(fake_client.user_prompts[1])
        self.assertEqual(payload.get("candidate_policy", {}).get("mode"), "memory_expanded")
        anchor_candidates = payload.get("anchor_candidates") or []
        anchor_terms = payload.get("anchor_terms") or []
        terminology_hints = payload.get("terminology_hints") or {}
        terminology_terms = terminology_hints.get("terms") or []
        canonical_anchor_hints = payload.get("canonical_anchor_hints") or {}
        canonical_anchor_terms = canonical_anchor_hints.get("terms") or []
        canonical_source_terms = canonical_anchor_hints.get("source_terms") or []
        self.assertGreaterEqual(len(anchor_candidates), 1)
        self.assertIn("DigestAuthenticationFilter", anchor_terms)
        self.assertNotIn("수 있습니다", anchor_terms)
        self.assertNotIn("지원합니다", anchor_terms)
        self.assertTrue(any(item.get("source") == "memory_glossary" for item in anchor_candidates))
        self.assertIn("DigestAuthenticationFilter", terminology_terms)
        self.assertNotIn("수 있습니다", terminology_terms)
        self.assertNotIn("지원합니다", terminology_terms)
        self.assertLessEqual(len(terminology_terms), 3)
        self.assertIn("@Transactional", canonical_anchor_terms)
        self.assertIn("transaction readonly", canonical_anchor_terms)
        self.assertLessEqual(len(canonical_anchor_terms), 3)
        self.assertNotIn("anchors", canonical_anchor_hints)
        self.assertTrue(all(item.get("source") == "canonical_anchor" for item in canonical_source_terms))
        self.assertTrue(all("canonical_term_id" not in item for item in canonical_source_terms))
        retrieval_context = payload.get("retrieval_context") or {}
        self.assertEqual(retrieval_context.get("retrieval_backend"), "db_ann")
        self.assertEqual(retrieval_context.get("vector_store"), "postgresql-pgvector")
        self.assertEqual(retrieval_context.get("retriever_mode"), "hybrid")
        self.assertEqual(retrieval_context.get("dense_embedding_model"), "intfloat/multilingual-e5-small")
        memory_prompt_row = payload["top_memory_candidates"][0]
        self.assertEqual(memory_prompt_row["source_memory_index"], 1)
        self.assertIn("synthetic_query", memory_prompt_row)
        self.assertIn("canonical_anchors", memory_prompt_row)
        self.assertNotIn("memory_id", memory_prompt_row)
        self.assertNotIn("target_doc_id", memory_prompt_row)
        self.assertNotIn("target_chunk_ids", memory_prompt_row)

    def test_standalone_candidate_drops_memory_anchor_contamination(self) -> None:
        class _FakeRewriteClient:
            def chat_json(self, **kwargs):
                return {
                    "candidates": [
                        {
                            "label": "standalone",
                            "query": "현재 트랜잭션 커밋됨 PostgreSQL SAVEPOINT",
                        }
                    ]
                }

        raw_query = "현재 트랜잭션 커밋됨?"
        with patch.object(runtime, "_REWRITE_PROMPT_TEXT", "rewrite prompt for test"), patch.object(
            runtime,
            "_rewrite_client",
            return_value=_FakeRewriteClient(),
        ):
            candidates = runtime.build_rewrite_candidates_v2(
                raw_query,
                [{"query_text": "PostgreSQL SAVEPOINT transaction rollback"}],
                session_context={},
                candidate_count=1,
                query_language="ko",
                trusted_memory_items=[],
            )

        self.assertEqual(candidates[0]["label"], "standalone")
        self.assertEqual(candidates[0]["query"], raw_query)
        self.assertEqual(candidates[0]["added_anchors"], [])

    def test_standalone_candidate_keeps_raw_retrieval_evidence_terms(self) -> None:
        class _FakeRewriteClient:
            def chat_json(self, **kwargs):
                return {
                    "candidates": [
                        {
                            "label": "standalone",
                            "query": "현재 트랜잭션 SAVEPOINT",
                        }
                    ]
                }

        with patch.object(runtime, "_REWRITE_PROMPT_TEXT", "rewrite prompt for test"), patch.object(
            runtime,
            "_rewrite_client",
            return_value=_FakeRewriteClient(),
        ):
            candidates = runtime.build_rewrite_candidates_v2(
                "현재 트랜잭션?",
                [{"query_text": "PostgreSQL SAVEPOINT transaction rollback"}],
                session_context={},
                candidate_count=1,
                query_language="ko",
                raw_retrieval_context=[{"technical_terms": ["SAVEPOINT"]}],
                trusted_memory_items=[],
            )

        self.assertEqual(candidates[0]["query"], "현재 트랜잭션 SAVEPOINT")

    def test_memory_rerank_prefers_raw_overlap_and_domain_match(self) -> None:
        raw_retrieval = [
            runtime.RetrievalCandidate(
                chunk_id="target-chunk",
                document_id="doc-target",
                score=0.60,
                text="SecurityFilterChain filter order",
            )
        ]
        memory_rows = [
            {
                "memory_id": "m-similar",
                "query_text": "generic Spring overview",
                "target_doc_id": "doc-other",
                "target_chunk_ids": ["other-chunk"],
                "product": "spring-framework",
                "glossary_terms": [],
                "similarity": 0.99,
                "utility_score": 0.20,
            },
            {
                "memory_id": "m-target",
                "query_text": "Spring Security SecurityFilterChain filter order",
                "target_doc_id": "doc-target",
                "target_chunk_ids": ["target-chunk"],
                "product": "spring-security-reference",
                "glossary_terms": ["SecurityFilterChain"],
                "similarity": 0.20,
                "utility_score": 0.90,
            },
        ]

        reranked = runtime._rerank_rewrite_memory_candidates(
            raw_query="filter order",
            memory_items=memory_rows,
            raw_retrieval=raw_retrieval,
            query_language="en",
            source_product="spring-security",
            top_n=2,
        )

        self.assertEqual(reranked[0]["memory_id"], "m-target")
        self.assertGreater(reranked[0]["memory_rerank_score"], reranked[1]["memory_rerank_score"])
        self.assertEqual(reranked[0]["memory_rank_before"], 2)
        self.assertEqual(reranked[0]["memory_rank_after"], 1)

    def test_selective_rewrite_passes_only_trusted_memory_to_candidate_builder(self) -> None:
        raw_query = "현재 트랜잭션 커밋됨?"
        captured: dict[str, object] = {}

        def fake_retrieve(query_text: str, *args, **kwargs):
            return [
                runtime.RetrievalCandidate(
                    chunk_id="chunk-end",
                    document_id="doc-end",
                    score=0.65,
                    text="END commits the current transaction",
                )
            ]

        def fake_memory(query_text: str, *args, **kwargs):
            return [
                {
                    "memory_id": "m-savepoint",
                    "query_text": "PostgreSQL에서 savepoint는 무엇이며 트랜잭션 상태를 어떻게 복원하나요?",
                    "target_doc_id": "doc-savepoint",
                    "target_chunk_ids": ["chunk-savepoint"],
                    "similarity": 0.98,
                    "utility_score": 0.85,
                    "product": "postgresql",
                },
                {
                    "memory_id": "m-end",
                    "query_text": "PostgreSQL END 명령은 현재 트랜잭션을 어떻게 커밋하나요?",
                    "target_doc_id": "doc-end",
                    "target_chunk_ids": ["chunk-end"],
                    "similarity": 0.20,
                    "utility_score": 0.70,
                    "product": "postgresql",
                },
            ]

        def fake_builder(*args, **kwargs):
            captured["trusted_memory_items"] = kwargs.get("trusted_memory_items")
            captured["domain_context"] = kwargs.get("domain_context")
            return [{"label": "standalone", "query": "현재 트랜잭션 커밋"}]

        with patch.object(runtime, "retrieve_top_k", side_effect=fake_retrieve), patch.object(
            runtime,
            "memory_top_n",
            side_effect=fake_memory,
        ), patch.object(runtime, "build_rewrite_candidates_v2", side_effect=fake_builder):
            runtime.run_selective_rewrite(
                raw_query=raw_query,
                query_language="ko",
                session_context={},
                chunks=[],
                memories=[],
                memory_top_n_value=2,
                candidate_count=2,
                threshold=0.01,
                retrieval_top_k=5,
                source_product="postgresql",
                retriever_config=build_retriever_config({"dense_fallback_enabled": True}),
                rewrite_adoption_policy={"thresholds": {"preservation_floor": 0.0}},
            )

        trusted = captured.get("trusted_memory_items")
        self.assertIsInstance(trusted, list)
        self.assertEqual([row["memory_id"] for row in trusted], ["m-end"])
        self.assertEqual(trusted[0]["source_memory_index"], 1)
        domain_context = captured.get("domain_context") or {}
        self.assertEqual(domain_context.get("current_technical_domain"), "PostgreSQL")
        self.assertIn("Postgres", domain_context.get("domain_aliases") or [])
        example_terms = {item.get("en") for item in domain_context.get("ko_to_en_term_examples") or []}
        self.assertIn("Transaction", example_terms)
        self.assertIn("COMMIT current transaction", example_terms)

    def test_standalone_scoring_ignores_trusted_memory_anchor_requirements(self) -> None:
        raw_query = "보안 보통 어떻게 씀?"

        def fake_retrieve(query_text: str, *args, **kwargs):
            score = 0.58 if query_text != raw_query else 0.56
            return [
                runtime.RetrievalCandidate(
                    chunk_id="chunk-password",
                    document_id="doc-password",
                    score=score,
                    text="DelegatingPasswordEncoder password storage",
                )
            ]

        def fake_memory(query_text: str, *args, **kwargs):
            return [
                {
                    "memory_id": "m-password",
                    "query_text": "DelegatingPasswordEncoder에서 비밀번호 매칭은 어떻게 동작하나요?",
                    "target_doc_id": "doc-password",
                    "target_chunk_ids": ["chunk-password"],
                    "similarity": 0.9,
                    "utility_score": 0.9,
                    "product": "spring-security",
                    "glossary_terms": ["DelegatingPasswordEncoder"],
                }
            ]

        def fake_builder(*args, **kwargs):
            return [{"label": "standalone", "query": "보안 사용 방법"}]

        with patch.object(runtime, "retrieve_top_k", side_effect=fake_retrieve), patch.object(
            runtime,
            "memory_top_n",
            side_effect=fake_memory,
        ), patch.object(runtime, "build_rewrite_candidates_v2", side_effect=fake_builder):
            outcome, _retrieval = runtime.run_selective_rewrite(
                raw_query=raw_query,
                query_language="ko",
                session_context={},
                chunks=[],
                memories=[],
                memory_top_n_value=1,
                candidate_count=1,
                threshold=0.05,
                retrieval_top_k=5,
                source_product="spring-security",
                retriever_config=build_retriever_config({"dense_fallback_enabled": True}),
                rewrite_adoption_policy={"thresholds": {"preservation_floor": 0.0}},
            )

        candidate = outcome.candidates[0]
        self.assertEqual(candidate["label"], "standalone")
        self.assertEqual(candidate["terminology_preservation_score"], 1.0)
        self.assertEqual(candidate["preservation_penalty"], 0.0)
        self.assertEqual(candidate["memory_target_missing_penalty"], 0.0)
        self.assertEqual(candidate["memory_target_tokens"], [])

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
        self.assertNotIn("canonical_anchor_hints", payload)

    def test_rewrite_candidate_count_is_capped_for_v3_contract(self) -> None:
        class _FakeRewriteClient:
            def __init__(self) -> None:
                self.user_prompts: list[str] = []

            def chat_json(self, **kwargs):
                self.user_prompts.append(str(kwargs.get("user_prompt") or ""))
                return {
                    "candidates": [
                        {"label": "standalone", "query": "query one"},
                        {"label": "expanded", "query": "query two"},
                        {"label": "extra", "query": "query three"},
                    ]
                }

        fake_client = _FakeRewriteClient()
        with patch.object(runtime, "_REWRITE_PROMPT_TEXT", "rewrite prompt for test"), patch.object(
            runtime,
            "_rewrite_client",
            return_value=fake_client,
        ):
            candidates = runtime.build_rewrite_candidates_v2(
                "probe 차이",
                [{"query_text": "probe readiness 차이", "source_memory_index": 1}],
                session_context={},
                candidate_count=3,
                query_language="ko",
                rewrite_anchor_injection_enabled=False,
                trusted_memory_items=[{"query_text": "probe readiness 차이", "source_memory_index": 1}],
            )

        self.assertEqual(len(candidates), 2)
        self.assertEqual([json.loads(prompt)["candidate_count"] for prompt in fake_client.user_prompts], [1, 1])
        self.assertEqual([candidate["label"] for candidate in candidates], ["standalone", "expanded"])

    def test_rewrite_payload_includes_multi_source_anchor_hints_when_enabled(self) -> None:
        class _FakeRewriteClient:
            def __init__(self) -> None:
                self.user_prompts: list[str] = []

            def chat_json(self, **kwargs):
                user_prompt = str(kwargs.get("user_prompt") or "")
                self.user_prompts.append(user_prompt)
                payload = json.loads(user_prompt)
                if payload.get("candidate_policy", {}).get("mode") == "memory_expanded":
                    return {"candidates": [{"label": "expanded", "query": "DigestAuthenticationFilter SecurityFilterChain"}]}
                return {"candidates": [{"label": "standalone", "query": "DigestAuthenticationFilter order"}]}

        fake_client = _FakeRewriteClient()
        index = runtime.MultiSourceAnchorIndex(
            relation_version="multi-source-anchor-v1",
            alias_to_canonical_ids={
                "digestauthenticationfilter": ["seed-digest"],
            },
            terms_by_id={
                "seed-digest": {
                    "canonical_form": "DigestAuthenticationFilter",
                    "normalized_form": "digestauthenticationfilter",
                    "term_type": "class",
                },
                "related-chain": {
                    "canonical_form": "SecurityFilterChain",
                    "normalized_form": "securityfilterchain",
                    "term_type": "interface",
                },
            },
            relations_by_anchor_id={
                "seed-digest": [
                    {
                        "related_anchor_id": "related-chain",
                        "canonical_form": "SecurityFilterChain",
                        "normalized_form": "securityfilterchain",
                        "term_type": "interface",
                        "relation_type": "synthetic_query_cooccurrence",
                        "relation_score": 0.86,
                        "relation_source": "synthetic_query_anchor_link",
                        "evidence_count": 3,
                    }
                ]
            },
        )
        hints = runtime._build_multi_source_anchor_hints(
            raw_query="DigestAuthenticationFilter order",
            query_language="en",
            memory_items=[],
            anchor_index=index,
            relation_type_allowlist=["synthetic_query_cooccurrence"],
            min_relation_score=0.72,
            max_per_seed=2,
            max_total=4,
        )

        with patch.object(runtime, "_REWRITE_PROMPT_TEXT", "rewrite prompt for test"), patch.object(
            runtime,
            "_rewrite_client",
            return_value=fake_client,
        ):
            runtime.build_rewrite_candidates_v2(
                "DigestAuthenticationFilter order",
                [{"query_text": "DigestAuthenticationFilter order", "source_memory_index": 1}],
                session_context={},
                candidate_count=2,
                query_language="en",
                multi_source_anchor_hints=hints,
                trusted_memory_items=[{"query_text": "DigestAuthenticationFilter order", "source_memory_index": 1}],
            )

        payload = json.loads(fake_client.user_prompts[1])
        multi_source_hints = payload.get("multi_source_anchor_hints") or {}
        self.assertIn("SecurityFilterChain", multi_source_hints.get("terms") or [])
        self.assertEqual(multi_source_hints.get("priority"), "low")
        self.assertEqual(
            1,
            multi_source_hints.get("diagnostics", {}).get("accepted_expanded_anchor_count"),
        )
        self.assertNotIn("related-chain", json.dumps(multi_source_hints))

    def test_rewrite_canonical_anchor_hints_include_only_scoring_approved_terms(self) -> None:
        hints = runtime._build_rewrite_canonical_anchor_hints(
            memory_items=[
                {
                    "memory_id": "memory-canonical-noise",
                    "query_text": "Spring transaction guide",
                    "canonical_anchors": self._canonical_payload_with_review_noise(),
                }
            ],
            query_language="ko",
            max_terms=8,
        )

        terms = hints["terms"]
        self.assertIn("@Transactional", terms)
        self.assertIn("transaction readonly", terms)
        self.assertIn("SelfFallbackAnchor", terms)
        self.assertNotIn("ReviewOnlyAnchor", terms)
        self.assertNotIn("AmbiguousAnchor", terms)
        self.assertNotIn("UnresolvedAnchor", terms)
        self.assertNotIn("PendingCandidateAnchor", terms)
        self.assertTrue(all("canonical_term_id" not in item for item in hints["source_terms"]))

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

    def test_rewrite_heuristic_fallback_does_not_use_untrusted_memory(self) -> None:
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
                "현재 트랜잭션 커밋됨?",
                [{"query_text": "PostgreSQL SAVEPOINT transaction rollback"}],
                session_context={},
                candidate_count=2,
                query_language="ko",
                rewrite_failure_policy="heuristic_fallback",
                rewrite_runtime_stats=runtime_stats,
                trusted_memory_items=[],
            )

        self.assertEqual(len(candidates), 1)
        self.assertEqual(candidates[0]["label"], "standalone")
        self.assertNotIn("SAVEPOINT", candidates[0]["query"])
        self.assertEqual(candidates[0]["source_memory_index"], 0)
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

    def test_canonical_alias_metadata_boosts_terminology_overlap(self) -> None:
        groups = runtime._collect_scoring_canonical_anchor_groups(
            [
                {
                    "memory_id": "m-canonical",
                    "query_text": "Spring Framework guide",
                    "glossary_terms": [],
                    "canonical_anchors": self._transactional_canonical_payload(),
                }
            ]
        )

        for candidate_query in (
            "transaction readonly setup",
            "transactional read only setup",
            "트랜잭션 읽기 전용 설정",
        ):
            with self.subTest(candidate_query=candidate_query):
                metrics = runtime._terminology_preservation_metrics(
                    raw_query="@Transactional 읽기 전용 설정",
                    candidate_query=candidate_query,
                    query_language="ko",
                    raw_anchor_terms=["@Transactional"],
                    canonical_anchor_groups=groups,
                )

                self.assertEqual(metrics["canonical_anchor_term_ids"], ["term-transactional"])
                self.assertAlmostEqual(metrics["canonical_anchor_overlap_ratio"], 1.0)
                self.assertAlmostEqual(metrics["anchor_overlap_ratio"], 1.0)
                self.assertAlmostEqual(metrics["terminology_preservation_score"], 1.0)

    def test_selective_rewrite_uses_canonical_alias_metadata_for_preservation(self) -> None:
        raw_query = "@Transactional 읽기 전용 설정"
        candidate_query = "transaction readonly setup"

        def fake_retrieve(query_text: str, *args, **kwargs):
            if query_text == raw_query:
                return self._single_retrieval(query_text, score=0.20, chunk_id="raw")
            return self._single_retrieval(query_text, score=0.85, chunk_id="cand")

        def fake_memory(query_text: str, *args, **kwargs):
            return [
                {
                    "memory_id": "m-canonical",
                    "query_text": "Spring Framework guide",
                    "target_doc_id": "doc-raw",
                    "target_chunk_ids": ["raw"],
                    "similarity": 0.0,
                    "product": "spring-framework",
                    "glossary_terms": [],
                    "canonical_anchors": self._transactional_canonical_payload(),
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
        self.assertAlmostEqual(outcome.candidates[0]["canonical_anchor_overlap_ratio"], 1.0)
        self.assertEqual(outcome.candidates[0]["canonical_anchor_term_ids"], ["term-transactional"])
        self.assertGreaterEqual(outcome.candidates[0]["terminology_preservation_score"], outcome.candidates[0]["preservation_floor"])
        self.assertTrue(any("transaction" in token for token in outcome.candidates[0]["memory_target_tokens"]))

    def test_terminology_metrics_without_canonical_metadata_keep_raw_ratios(self) -> None:
        base_metrics = runtime._terminology_preservation_metrics(
            raw_query="@Transactional 읽기 전용 설정",
            candidate_query="transaction readonly setup",
            query_language="ko",
            raw_anchor_terms=["@Transactional"],
        )
        empty_metadata_metrics = runtime._terminology_preservation_metrics(
            raw_query="@Transactional 읽기 전용 설정",
            candidate_query="transaction readonly setup",
            query_language="ko",
            raw_anchor_terms=["@Transactional"],
            canonical_anchor_groups={},
        )

        self.assertEqual(base_metrics["technical_preservation_ratio"], empty_metadata_metrics["technical_preservation_ratio"])
        self.assertEqual(base_metrics["anchor_overlap_ratio"], empty_metadata_metrics["anchor_overlap_ratio"])
        self.assertEqual(base_metrics["terminology_preservation_score"], empty_metadata_metrics["terminology_preservation_score"])
        self.assertEqual(empty_metadata_metrics["canonical_anchor_raw_count"], 0.0)
        self.assertEqual(empty_metadata_metrics["canonical_anchor_overlap_ratio"], 0.0)

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

    def test_selective_rewrite_rejects_confident_raw_top_loss(self) -> None:
        raw_query = "DigestAuthenticationFilter configuration"
        candidate_query = "DigestAuthenticationFilter security filter configuration"

        def fake_retrieve(query_text: str, *args, **kwargs):
            if query_text == raw_query:
                return self._single_retrieval(query_text, score=0.75, chunk_id="raw-top")
            return self._single_retrieval(query_text, score=1.00, chunk_id="candidate-top")

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
                query_language="en",
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
        self.assertEqual(outcome.rewrite_reason, "raw_loss_guard_top1_lost")
        self.assertEqual(retrieval[0].chunk_id, "raw-top")
        self.assertGreater(outcome.candidates[0]["final_score_delta"], outcome.candidates[0]["effective_threshold"])
        self.assertTrue(outcome.candidates[0]["raw_loss_guard_triggered"])
        self.assertEqual(outcome.candidates[0]["raw_loss_guard_topk_overlap_ratio"], 0.0)

    def test_force_rewrite_falls_back_to_raw_when_no_candidate_is_eligible(self) -> None:
        raw_query = "DigestAuthenticationFilter ?ㅼ젙 諛⑸쾿"
        candidate_query = "Spring Security filter setup"

        def fake_retrieve(query_text: str, *args, **kwargs):
            if query_text == raw_query:
                return self._single_retrieval(query_text, score=0.20, chunk_id="raw")
            return self._single_retrieval(query_text, score=0.90, chunk_id="cand")

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
                force_rewrite=True,
                retriever_config=build_retriever_config({"dense_fallback_enabled": True}),
            )

        self.assertFalse(outcome.rewrite_applied)
        self.assertEqual(outcome.final_query, raw_query)
        self.assertEqual(outcome.rewrite_reason, "preservation_below_floor")
        self.assertEqual(retrieval[0].chunk_id, "raw")
        self.assertEqual(outcome.candidates[0]["rejection_reason"], "preservation_below_floor")

    def test_force_rewrite_uses_best_eligible_candidate(self) -> None:
        raw_query = "DigestAuthenticationFilter ?ㅼ젙 諛⑸쾿"
        rejected_query = "Spring Security filter overview"
        accepted_query = "DigestAuthenticationFilter security config"

        def fake_retrieve(query_text: str, *args, **kwargs):
            if query_text == raw_query:
                return self._single_retrieval(query_text, score=0.20, chunk_id="raw")
            if query_text == rejected_query:
                return self._single_retrieval(query_text, score=0.95, chunk_id="rejected")
            return self._single_retrieval(query_text, score=0.80, chunk_id="accepted")

        def fake_memory(query_text: str, *args, **kwargs):
            return [
                {
                    "memory_id": "m1",
                    "query_text": query_text,
                    "similarity": 0.0,
                    "glossary_terms": ["DigestAuthenticationFilter"],
                }
            ]

        with patch.object(
            runtime,
            "build_rewrite_candidates_v2",
            return_value=[
                {"label": "rejected", "query": rejected_query},
                {"label": "accepted", "query": accepted_query},
            ],
        ), patch.object(
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
                candidate_count=2,
                threshold=0.01,
                retrieval_top_k=3,
                force_rewrite=True,
                retriever_config=build_retriever_config({"dense_fallback_enabled": True}),
            )

        self.assertTrue(outcome.rewrite_applied)
        self.assertEqual(outcome.rewrite_reason, "forced")
        self.assertEqual(outcome.final_query, accepted_query)
        self.assertEqual(retrieval[0].chunk_id, "accepted")
        self.assertEqual(outcome.candidates[0]["rejection_reason"], "preservation_below_floor")
        self.assertEqual(outcome.candidates[1]["rejection_reason"], "")

    def test_short_user_force_rewrite_rejects_generic_candidate_without_memory_target(self) -> None:
        raw_query = "how to use this in practice"
        candidate_query = "Spring Boot practical usage"

        def fake_retrieve(query_text: str, *args, **kwargs):
            if query_text == raw_query:
                return self._single_retrieval(query_text, score=0.20, chunk_id="raw")
            return self._single_retrieval(query_text, score=0.95, chunk_id="generic")

        def fake_memory(query_text: str, *args, **kwargs):
            return [
                {
                    "memory_id": "m1",
                    "query_text": "Spring Boot executable jar extraction",
                    "similarity": 0.70,
                    "product": "spring-boot",
                    "glossary_terms": [],
                }
            ]

        with patch.object(runtime, "build_rewrite_candidates_v2", return_value=[{"label": "generic", "query": candidate_query}]), patch.object(
            runtime,
            "retrieve_top_k",
            side_effect=fake_retrieve,
        ), patch.object(runtime, "memory_top_n", side_effect=fake_memory):
            outcome, retrieval = runtime.run_selective_rewrite(
                raw_query=raw_query,
                query_language="en",
                query_category="short_user",
                session_context={},
                chunks=[],
                memories=[],
                memory_top_n_value=3,
                candidate_count=1,
                threshold=0.01,
                retrieval_top_k=3,
                force_rewrite=True,
                retriever_config=build_retriever_config({"dense_fallback_enabled": True}),
            )

        self.assertFalse(outcome.rewrite_applied)
        self.assertEqual(outcome.rewrite_reason, "missing_memory_target")
        self.assertEqual(outcome.final_query, raw_query)
        self.assertEqual(retrieval[0].chunk_id, "raw")
        self.assertTrue(outcome.candidates[0]["raw_is_underspecified"])
        self.assertEqual(outcome.candidates[0]["candidate_target_overlap_count"], 0)

    def test_short_user_force_rewrite_prefers_candidate_with_memory_target(self) -> None:
        raw_query = "how to use this in practice"
        generic_candidate = "Spring Boot practical usage"
        target_candidate = "Spring Boot jar extraction"

        def fake_retrieve(query_text: str, *args, **kwargs):
            if query_text == raw_query:
                return self._single_retrieval(query_text, score=0.20, chunk_id="raw")
            if query_text == generic_candidate:
                return self._single_retrieval(query_text, score=0.95, chunk_id="generic")
            return self._single_retrieval(query_text, score=0.70, chunk_id="target")

        def fake_memory(query_text: str, *args, **kwargs):
            return [
                {
                    "memory_id": "m1",
                    "query_text": "Spring Boot executable jar extraction",
                    "similarity": 0.70,
                    "product": "spring-boot",
                    "glossary_terms": [],
                }
            ]

        with patch.object(
            runtime,
            "build_rewrite_candidates_v2",
            return_value=[
                {"label": "generic", "query": generic_candidate},
                {"label": "target", "query": target_candidate},
            ],
        ), patch.object(
            runtime,
            "retrieve_top_k",
            side_effect=fake_retrieve,
        ), patch.object(runtime, "memory_top_n", side_effect=fake_memory):
            outcome, retrieval = runtime.run_selective_rewrite(
                raw_query=raw_query,
                query_language="en",
                query_category="short_user",
                session_context={},
                chunks=[],
                memories=[],
                memory_top_n_value=3,
                candidate_count=2,
                threshold=0.01,
                retrieval_top_k=3,
                force_rewrite=True,
                retriever_config=build_retriever_config({"dense_fallback_enabled": True}),
            )

        self.assertTrue(outcome.rewrite_applied)
        self.assertEqual(outcome.rewrite_reason, "forced")
        self.assertEqual(outcome.final_query, target_candidate)
        self.assertEqual(retrieval[0].chunk_id, "target")
        self.assertEqual(outcome.candidates[0]["rejection_reason"], "missing_memory_target")
        self.assertGreater(outcome.candidates[1]["candidate_target_overlap_count"], 0)
        self.assertGreater(outcome.candidates[1]["memory_target_presence_bonus"], 0.0)

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
