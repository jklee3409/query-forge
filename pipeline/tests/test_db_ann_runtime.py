from __future__ import annotations

import unittest
from unittest.mock import patch

from pipeline.common.embeddings import embed_text
from pipeline.common.local_retriever import build_retriever_config
from pipeline.eval import runtime
from pipeline.eval.runtime import ChunkItem
from pipeline.memory.materialize_chunk_embeddings import _upsert_chunk_embedding


class _FakeCursor:
    def __init__(self, connection: "_FakeConnection") -> None:
        self._connection = connection

    def __enter__(self) -> "_FakeCursor":
        return self

    def __exit__(self, exc_type, exc, tb) -> bool:
        return False

    def execute(self, sql: str, params=None) -> None:
        self._connection.executed.append((sql, params))

    def fetchall(self):
        return list(self._connection.rows)


class _FakeConnection:
    def __init__(self, rows) -> None:
        self.rows = rows
        self.executed: list[tuple[str, object]] = []

    def cursor(self) -> _FakeCursor:
        return _FakeCursor(self)


class DbAnnRuntimeTests(unittest.TestCase):
    def test_db_ann_chunk_retrieval_uses_pgvector_sql_and_embedding_model_filter(self) -> None:
        connection = _FakeConnection(
            [
                {
                    "chunk_id": "chk-1",
                    "document_id": "doc-1",
                    "chunk_text": "BeanFactory basics",
                    "embedding_literal": "[0.10,0.20]",
                    "ann_score": 0.91,
                }
            ]
        )
        config = build_retriever_config(
            {
                "retriever_mode": "dense_only",
                "dense_embedding_model": "intfloat/multilingual-e5-small",
                "dense_embedding_required": True,
                "dense_fallback_enabled": False,
                "rerank_enabled": False,
            }
        )
        with patch.object(
            runtime,
            "embed_query_with_retriever_config",
            return_value=([0.10, 0.20], "intfloat/multilingual-e5-small", False),
        ):
            adapter = runtime.DbAnnRuntimeRetrievalAdapter(
                connection,
                allowed_products=["spring-framework"],
                include_document_ids=None,
                memory_experiment_key="exp-db-ann",
                retriever_config=config,
            )
            rows = adapter.retrieve_top_k("BeanFactory", top_k=1)

        self.assertEqual(len(rows), 1)
        self.assertIsInstance(rows[0], runtime.RetrievalCandidate)
        sql, params = connection.executed[0]
        self.assertIn("FROM chunk_embeddings ce", sql)
        self.assertIn("ce.embedding_model = %s", sql)
        self.assertIn("<=>", sql)
        self.assertIn("intfloat/multilingual-e5-small", list(params))

    def test_db_ann_memory_top_n_filters_by_embedding_model_and_matches_memory_shape(self) -> None:
        connection = _FakeConnection(
            [
                {
                    "memory_id": "memory-1",
                    "query_text": "BeanFactory basics",
                    "target_doc_id": "doc-1",
                    "target_chunk_ids": ["chk-1"],
                    "generation_strategy": "A",
                    "product": "spring-framework",
                    "glossary_terms": ["BeanFactory"],
                    "embedding_literal": "[0.10,0.20]",
                    "ann_score": 0.88,
                }
            ]
        )
        config = build_retriever_config(
            {
                "retriever_mode": "hybrid",
                "dense_embedding_model": "intfloat/multilingual-e5-small",
                "dense_embedding_required": True,
                "dense_fallback_enabled": False,
                "rerank_enabled": False,
            }
        )
        with patch.object(
            runtime,
            "embed_query_with_retriever_config",
            return_value=([0.10, 0.20], "intfloat/multilingual-e5-small", False),
        ):
            adapter = runtime.DbAnnRuntimeRetrievalAdapter(
                connection,
                allowed_products=None,
                include_document_ids=None,
                memory_experiment_key="exp-db-ann",
                retriever_config=config,
            )
            rows = runtime.memory_top_n(
                "BeanFactory",
                [],
                top_n=1,
                preset_filter="full_gating",
                source_gate_run_id="gate-run",
                strategy_filters=["A"],
                retrieval_adapter=adapter,
            )

        self.assertEqual(len(rows), 1)
        self.assertTrue(
            {
                "memory_id",
                "query_text",
                "target_doc_id",
                "target_chunk_ids",
                "generation_strategy",
                "similarity",
                "dense_similarity",
                "bm25_score",
                "technical_token_overlap",
                "retriever",
            }.issubset(rows[0].keys())
        )
        sql, _params = connection.executed[0]
        self.assertIn("m.metadata ->> 'embedding_model' = %s", sql)
        self.assertIn("<=>", sql)

    def test_db_ann_query_embedding_model_mismatch_fails(self) -> None:
        connection = _FakeConnection([])
        config = build_retriever_config(
            {
                "retriever_mode": "dense_only",
                "dense_embedding_model": "intfloat/multilingual-e5-small",
                "dense_embedding_required": True,
                "dense_fallback_enabled": False,
                "rerank_enabled": False,
            }
        )
        with patch.object(
            runtime,
            "embed_query_with_retriever_config",
            return_value=([0.10, 0.20], "hash-embedding-v1", False),
        ):
            adapter = runtime.DbAnnRuntimeRetrievalAdapter(
                connection,
                allowed_products=None,
                include_document_ids=None,
                memory_experiment_key="exp-db-ann",
                retriever_config=config,
            )
            with self.assertRaisesRegex(RuntimeError, "query embedding model mismatch"):
                adapter.retrieve_top_k("BeanFactory", top_k=1)

    def test_chunk_embedding_upsert_uses_chunk_and_model_conflict_key(self) -> None:
        connection = _FakeConnection([])

        _upsert_chunk_embedding(
            connection,
            chunk_id="chk-1",
            embedding_model="intfloat/multilingual-e5-small",
            embedding_values=[0.10, 0.20],
            metadata={"source": "unit-test"},
        )

        self.assertEqual(len(connection.executed), 1)
        sql, params = connection.executed[0]
        self.assertIn("ON CONFLICT (chunk_id, embedding_model) DO UPDATE", sql)
        self.assertEqual(params[0], "chk-1")
        self.assertEqual(params[1], "intfloat/multilingual-e5-small")

    def test_local_and_db_ann_retrieval_return_compatible_candidate_type(self) -> None:
        local_rows = runtime.retrieve_top_k(
            "BeanFactory",
            [
                ChunkItem(
                    chunk_id="chk-local",
                    document_id="doc-local",
                    text="BeanFactory basics",
                    embedding=embed_text("BeanFactory basics"),
                )
            ],
            top_k=1,
            retriever_config=build_retriever_config({"retriever_mode": "bm25_only"}),
        )
        connection = _FakeConnection(
            [
                {
                    "chunk_id": "chk-db",
                    "document_id": "doc-db",
                    "chunk_text": "BeanFactory basics",
                    "embedding_literal": "[0.10,0.20]",
                    "ann_score": 0.93,
                }
            ]
        )
        config = build_retriever_config(
            {
                "retriever_mode": "dense_only",
                "dense_embedding_model": "intfloat/multilingual-e5-small",
                "dense_embedding_required": True,
                "dense_fallback_enabled": False,
                "rerank_enabled": False,
            }
        )
        with patch.object(
            runtime,
            "embed_query_with_retriever_config",
            return_value=([0.10, 0.20], "intfloat/multilingual-e5-small", False),
        ):
            adapter = runtime.DbAnnRuntimeRetrievalAdapter(
                connection,
                allowed_products=None,
                include_document_ids=None,
                memory_experiment_key="exp-db-ann",
                retriever_config=config,
            )
            db_rows = runtime.retrieve_top_k("BeanFactory", [], top_k=1, retrieval_adapter=adapter)

        self.assertIsInstance(local_rows[0], runtime.RetrievalCandidate)
        self.assertIsInstance(db_rows[0], runtime.RetrievalCandidate)
        self.assertTrue(hasattr(local_rows[0], "chunk_id"))
        self.assertTrue(hasattr(db_rows[0], "chunk_id"))


if __name__ == "__main__":
    unittest.main()
