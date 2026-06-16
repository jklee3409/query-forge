from __future__ import annotations

import unittest

from pipeline.memory.build_memory import (
    GatedRow,
    _insert_memory_entry,
    _with_canonical_anchor_metadata,
)


class CaptureCursor:
    def __init__(self) -> None:
        self.sql = ""
        self.parameters = ()

    def execute(self, sql: str, parameters: tuple[object, ...]) -> None:
        self.sql = sql
        self.parameters = parameters


class BuildMemoryCanonicalAnchorMetadataTests(unittest.TestCase):
    def test_metadata_adds_canonical_anchor_payload_without_rewriting_memory_fields(self) -> None:
        memory_query = "How do I configure read-only transactions?"
        glossary_terms = ["@Transactional"]
        base_metadata = {
            "gating_preset": "full_gating",
            "source_gate_run_id": "gate-run-1",
            "source_gating_batch_id": "gate-batch-1",
            "stage_cutoff_enabled": False,
            "stage_cutoff_level": None,
            "stage_cutoff_source_gating_batch_id": None,
            "memory_build_run_id": "memory-build-run-1",
            "memory_experiment_key": "rag-session-8",
            "embedding_model": "intfloat/multilingual-e5-small",
            "retrieval_backend": "local",
            "fallback_used": False,
        }

        metadata = _with_canonical_anchor_metadata(
            base_metadata,
            connection=None,
            memory_id="memory-1",
            source_gated_query_id="gated-1",
            synthetic_query_id="synthetic-1",
            query_language="en",
            language_profile="short_user",
            generation_strategy="E",
            glossary_terms=glossary_terms,
            glossary_term_candidates=[
                {
                    "term_id": "term-transactional",
                    "canonical_form": "@Transactional",
                    "normalized_form": "@transactional",
                    "term_type": "annotation",
                    "is_active": True,
                }
            ],
        )

        self.assertEqual(memory_query, "How do I configure read-only transactions?")
        self.assertEqual(glossary_terms, ["@Transactional"])
        self.assertNotIn("canonical_anchors", base_metadata)
        self.assertEqual(metadata["source_gate_run_id"], "gate-run-1")
        self.assertEqual(metadata["source_gating_batch_id"], "gate-batch-1")
        self.assertEqual(metadata["memory_build_run_id"], "memory-build-run-1")
        self.assertEqual(metadata["memory_experiment_key"], "rag-session-8")
        self.assertEqual(metadata["anchor_mapping_version"], "anchor-map-v1")
        self.assertEqual(metadata["anchor_normalization_version"], "anchor-normalize-v1")

        canonical = metadata["canonical_anchors"]
        self.assertEqual(canonical["schema_version"], "canonical-anchor-runtime-v1")
        self.assertEqual(canonical["mapping_version"], "anchor-map-v1")
        self.assertEqual(canonical["normalization_version"], "anchor-normalize-v1")
        self.assertEqual(canonical["source_context"]["kind"], "memory_entry")
        self.assertEqual(canonical["source_context"]["source_id"], "memory-1")
        self.assertEqual(canonical["source_context"]["source_field"], "query")
        self.assertEqual(canonical["source_context"]["synthetic_query_id"], "synthetic-1")
        self.assertEqual(canonical["source_context"]["source_gated_query_id"], "gated-1")
        self.assertEqual(canonical["canonical_terms"], ["@Transactional"])
        self.assertEqual(canonical["canonical_term_ids"], ["term-transactional"])
        self.assertEqual(canonical["unresolved_aliases"], [])

        anchor = canonical["anchors"][0]
        self.assertEqual(anchor["input_alias"], "@Transactional")
        self.assertEqual(anchor["source_field"], "glossary_terms")
        self.assertEqual(anchor["resolution_status"], "self_fallback")
        self.assertTrue(anchor["used_for_scoring"])

    def test_metadata_uses_empty_payload_when_term_type_is_not_explicit(self) -> None:
        metadata = _with_canonical_anchor_metadata(
            {
                "source_gate_run_id": "gate-run-1",
                "source_gating_batch_id": "gate-batch-1",
            },
            connection=None,
            memory_id="memory-2",
            source_gated_query_id="gated-2",
            synthetic_query_id="synthetic-2",
            query_language="ko",
            language_profile="code_mixed",
            generation_strategy="D",
            glossary_terms=["transaction readonly"],
            glossary_term_candidates=[],
        )

        canonical = metadata["canonical_anchors"]
        self.assertEqual(canonical["source_context"]["kind"], "memory_entry")
        self.assertEqual(canonical["source_context"]["source_field"], "query")
        self.assertEqual(canonical["anchors"], [])
        self.assertEqual(canonical["canonical_terms"], [])
        self.assertEqual(canonical["canonical_term_ids"], [])
        self.assertEqual(canonical["unresolved_aliases"], [])

    def test_memory_insert_persists_domain_id_for_domain_scoped_chat_readiness(self) -> None:
        cursor = CaptureCursor()
        row = GatedRow(
            gated_query_id="gated-1",
            synthetic_query_id="synthetic-1",
            domain_id="6240b791-1bf0-432d-9709-faab193a2530",
            query_text="How does readOnly transaction configuration work?",
            query_type="short_user",
            query_language="ko",
            language_profile="ko",
            generation_strategy="C",
            target_chunk_ids=["chunk-1"],
            target_doc_id="doc-1",
            chunk_id_source="chunk-1",
            glossary_terms=["@Transactional"],
            llm_scores={"grounding": 0.9},
            utility_score=0.8,
            novelty_score=0.7,
            final_score=0.85,
            prompt_version="v1",
            prompt_hash="hash-1",
            product_name="spring-framework",
        )

        _insert_memory_entry(
            cursor,
            memory_id="memory-1",
            row=row,
            embedding_literal="[0.1,0.2,0.3]",
            memory_metadata={
                "source_gate_run_id": "135d3403-7db5-4643-a31b-19eab9933e67",
                "source_gating_batch_id": "73b5bfc1-73b5-4cfe-ab64-daf94729578b",
            },
        )

        self.assertIn("domain_id", cursor.sql)
        self.assertEqual(cursor.parameters[0], "memory-1")
        self.assertEqual(cursor.parameters[1], "gated-1")
        self.assertEqual(cursor.parameters[2], "6240b791-1bf0-432d-9709-faab193a2530")


if __name__ == "__main__":
    unittest.main()
