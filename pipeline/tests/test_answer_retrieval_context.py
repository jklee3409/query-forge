from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, patch
from uuid import uuid4

from pipeline.eval import answer_eval
from pipeline.eval.retrieval_context import load_retrieval_context, write_retrieval_context


class AnswerRetrievalContextTests(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.run_id = str(uuid4())
        self.batch_id = str(uuid4())
        self.identity = {
            "retrieval_run_id": self.run_id,
            "experiment_key": "official-fixture",
            "config_hash": "config-sha",
            "dataset_id": "dataset-1",
            "mode": "selective_rewrite_full_gating",
            "snapshot": self.batch_id,
        }
        self.sample = SimpleNamespace(
            sample_id="sample-1", query_text="Which configuration works?",
            split="test", query_category="configuration",
            expected_answer_key_points=["First document."],
            expected_chunk_ids=["chunk-first"], expected_doc_ids=["doc"],
        )
        self.row = {
            "sample_id": self.sample.sample_id,
            "query": self.sample.query_text,
            "final_query": "Which setting?",
            "snapshot": {"sourceGatingBatchIds": [self.batch_id]},
            "retrieved": [{"chunk_id": "chunk-first", "score": 0.7}, {"chunk_id": "chunk-next", "score": 0.9}],
            "rewrite": {"rewrite_applied": True, "rewrite_reason": "confidence_gain", "secret": "discarded"},
        }
        self.config = SimpleNamespace(
            experiment_key=self.identity["experiment_key"], config_hash=self.identity["config_hash"],
            config_path=Path("fixture.yml"), category="rag", description="fixture",
            retriever_config=SimpleNamespace(to_metadata=dict),
            raw={
                "eval_policy_version": 2,
                "answer_eval_mode": self.identity["mode"],
                "dataset_id": self.identity["dataset_id"],
                "snapshot_id": str(uuid4()),
                "comparison_snapshots": {"full_gating": {"gating_batch_id": self.batch_id}},
            },
        )

    def test_reads_the_exact_run_and_keeps_java_order_and_full_chunk_text(self) -> None:
        write_retrieval_context(self.root, **self.identity, rows=[self.row])
        write_retrieval_context(self.root, **{**self.identity, "retrieval_run_id": str(uuid4())},
                                rows=[{**self.row, "retrieved": []}])
        connection = MagicMock()
        cursor = connection.cursor.return_value.__enter__.return_value
        cursor.fetchone.return_value = {"ok": 1}
        cursor.fetchall.return_value = [
            {"chunk_id": "chunk-next", "document_id": "doc", "chunk_text": "Second document is included."},
            {"chunk_id": "chunk-first", "document_id": "doc", "chunk_text": "First document is authoritative."},
        ]
        with patch.object(answer_eval, "load_experiment_config", return_value=self.config), \
                patch.object(answer_eval, "connect", return_value=connection), \
                patch.object(answer_eval, "load_eval_samples", return_value=[self.sample]), \
                patch.object(answer_eval, "ExperimentRunRecorder") as recorder, \
                patch.object(answer_eval, "_resolve_raw_retrieval_cache") as raw, \
                patch.object(answer_eval, "rerank_retrieval_candidates") as rerank, \
                patch.object(answer_eval, "run_selective_rewrite") as rewrite:
            recorder.return_value.start_run.return_value.experiment_run_id = str(uuid4())
            summary = answer_eval.run_answer_eval(experiment="fixture", output_root=self.root,
                                                  docs_root=self.root, retrieval_run_id=self.run_id)
        self.assertEqual(summary["retrieval_run_id"], self.run_id)
        self.assertEqual(summary["retrieval_mode"], self.identity["mode"])
        self.assertEqual(summary["summary"]["context_recall"], 1.0)
        csv_text = (self.root / "answer_detail_official-fixture.csv").read_text(encoding="utf-8")
        self.assertIn("First document is authoritative. Second document is included.", csv_text)
        self.assertNotIn("secret", (self.root / "retrieval_context" / f"{self.run_id}.json").read_text(encoding="utf-8"))
        raw.assert_not_called()
        rerank.assert_not_called()
        rewrite.assert_not_called()
        ranked_rows = [call.args[1] for call in cursor.execute.call_args_list if "INSERT INTO rerank_results" in call.args[0]]
        self.assertEqual([row[5] for row in ranked_rows], [0.7, 0.9])
        connection.commit.assert_called_once()

    def test_rejects_mismatched_sample_snapshot_and_config(self) -> None:
        write_retrieval_context(self.root, **self.identity, rows=[self.row])
        for changed_key, changed_value in (("config_hash", "wrong"), ("snapshot", "wrong"), ("mode", "raw_only")):
            with self.subTest(field=changed_key), self.assertRaisesRegex(ValueError, f"{changed_key} mismatch"):
                load_retrieval_context(self.root, **{**self.identity, changed_key: changed_value},
                                       sample_queries={self.sample.sample_id: self.sample.query_text})
        with self.assertRaisesRegex(ValueError, "samples mismatch"):
            load_retrieval_context(self.root, **self.identity, sample_queries={self.sample.sample_id: "Changed."})
        artifact = self.root / "retrieval_context" / f"{self.run_id}.json"
        payload = json.loads(artifact.read_text(encoding="utf-8"))
        payload["rows"][0]["snapshot"] = {"sourceGatingBatchIds": [str(uuid4())]}
        artifact.write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "snapshot mismatch"):
            load_retrieval_context(self.root, **self.identity,
                                   sample_queries={self.sample.sample_id: self.sample.query_text})

    def test_accepts_empty_retrieval_without_a_chunk_lookup(self) -> None:
        write_retrieval_context(self.root, **self.identity, rows=[{**self.row, "retrieved": []}])
        connection = MagicMock()
        cursor = connection.cursor.return_value.__enter__.return_value
        cursor.fetchone.return_value = {"ok": 1}
        rows = answer_eval._evaluate_retrieved_answers(
            connection,
            config=self.config,
            dataset_id=self.identity["dataset_id"],
            samples=[self.sample],
            output_root=self.root,
            retrieval_run_id=self.run_id,
            mode=self.identity["mode"],
        )

        self.assertEqual(rows[0]["reranked"], [])
        self.assertEqual(rows[0]["sample_row"]["answer_text"], "")
        cursor.execute.assert_called_once()
        cursor.fetchall.assert_not_called()

    def test_rejects_null_retrieval_instead_of_treating_it_as_empty(self) -> None:
        write_retrieval_context(self.root, **self.identity, rows=[self.row])
        artifact = self.root / "retrieval_context" / f"{self.run_id}.json"
        payload = json.loads(artifact.read_text(encoding="utf-8"))
        payload["rows"][0]["retrieved"] = None
        artifact.write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Invalid retrieval context result"):
            load_retrieval_context(self.root, **self.identity,
                                   sample_queries={self.sample.sample_id: self.sample.query_text})

    def test_missing_chunk_rolls_back_before_replacing_existing_judgments(self) -> None:
        write_retrieval_context(self.root, **self.identity, rows=[self.row])
        connection = MagicMock()
        cursor = connection.cursor.return_value.__enter__.return_value
        cursor.fetchone.return_value = {"ok": 1}
        cursor.fetchall.return_value = []
        with patch.object(answer_eval, "load_experiment_config", return_value=self.config), \
                patch.object(answer_eval, "connect", return_value=connection), \
                patch.object(answer_eval, "load_eval_samples", return_value=[self.sample]), \
                patch.object(answer_eval, "ExperimentRunRecorder"), \
                self.assertLogs(answer_eval.LOGGER, level="ERROR"), \
                self.assertRaisesRegex(ValueError, "missing chunks"):
            answer_eval.run_answer_eval(experiment="fixture", output_root=self.root,
                                       docs_root=self.root, retrieval_run_id=self.run_id)
        self.assertFalse(any("DELETE FROM eval_judgments" in call.args[0] for call in cursor.execute.call_args_list))
        connection.rollback.assert_called_once()
        connection.commit.assert_not_called()
