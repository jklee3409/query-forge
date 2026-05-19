from __future__ import annotations

import unittest

from pipeline.gating.quality_gating import (
    RawQueryRow,
    _load_raw_queries,
    _pending_raw_queries,
    _resolve_generation_batch_ids,
    _resolve_generation_run_ids,
)


class _FakeCursor:
    def __init__(self) -> None:
        self.sql = ""
        self.parameters = None

    def __enter__(self) -> "_FakeCursor":
        return self

    def __exit__(self, exc_type, exc, traceback) -> None:  # noqa: ANN001
        return None

    def execute(self, sql, parameters) -> None:  # noqa: ANN001
        self.sql = sql
        self.parameters = parameters

    def fetchall(self) -> list[dict[str, object]]:
        return []


class _FakeConnection:
    def __init__(self) -> None:
        self.cursor_instance = _FakeCursor()

    def cursor(self) -> _FakeCursor:
        return self.cursor_instance


def _row(query_id: str) -> RawQueryRow:
    return RawQueryRow(
        synthetic_query_id=query_id,
        chunk_id_source="chunk-1",
        target_doc_id="doc-1",
        target_chunk_ids=["chunk-1"],
        answerability_type="single",
        query_text="query",
        query_language="ko",
        language_profile="ko",
        query_type="definition",
        generation_strategy="B",
        source_summary="",
        metadata={},
    )


class QualityGatingConfigTests(unittest.TestCase):
    def test_requires_explicit_source_generation_identity(self) -> None:
        with self.assertRaisesRegex(ValueError, "source_generation_batch_ids"):
            _resolve_generation_run_ids({})

    def test_batch_identity_satisfies_required_source_identity(self) -> None:
        resolved = _resolve_generation_run_ids({"source_generation_batch_ids": ["batch-a"]})
        self.assertEqual(resolved, [])

    def test_accepts_source_generation_run_ids_list(self) -> None:
        resolved = _resolve_generation_run_ids(
            {
                "source_generation_run_ids": [
                    "run-a",
                    " run-b ",
                    "",
                    "run-a",
                ]
            }
        )
        self.assertEqual(resolved, ["run-a", "run-b"])

    def test_falls_back_to_single_source_generation_run_id(self) -> None:
        resolved = _resolve_generation_run_ids({"source_generation_run_id": " run-single "})
        self.assertEqual(resolved, ["run-single"])

    def test_list_has_priority_over_single_value(self) -> None:
        resolved = _resolve_generation_run_ids(
            {
                "source_generation_run_ids": ["run-list"],
                "source_generation_run_id": "run-single",
            }
        )
        self.assertEqual(resolved, ["run-list"])

    def test_accepts_source_generation_batch_ids_list(self) -> None:
        resolved = _resolve_generation_batch_ids(
            {
                "source_generation_batch_ids": [
                    "batch-a",
                    " batch-b ",
                    "",
                    "batch-a",
                ]
            }
        )
        self.assertEqual(resolved, ["batch-a", "batch-b"])

    def test_falls_back_to_single_source_generation_batch_id(self) -> None:
        resolved = _resolve_generation_batch_ids({"source_generation_batch_id": " batch-single "})
        self.assertEqual(resolved, ["batch-single"])

    def test_raw_query_loading_prefers_batch_identity_over_run_identity(self) -> None:
        connection = _FakeConnection()

        _load_raw_queries(
            connection,
            strategies=["B"],
            generation_batch_ids=["batch-a"],
            generation_run_ids=["run-a"],
        )

        self.assertIn("generation_batch_id = ANY", connection.cursor_instance.sql)
        self.assertNotIn("experiment_run_id = ANY", connection.cursor_instance.sql)
        self.assertEqual(connection.cursor_instance.parameters, [["B"], ["batch-a"]])

    def test_raw_query_loading_uses_run_identity_without_batch_identity(self) -> None:
        connection = _FakeConnection()

        _load_raw_queries(
            connection,
            strategies=["B"],
            generation_batch_ids=[],
            generation_run_ids=["run-a"],
        )

        self.assertIn("experiment_run_id = ANY", connection.cursor_instance.sql)
        self.assertEqual(connection.cursor_instance.parameters, [["B"], ["run-a"]])

    def test_pending_queries_uses_last_processed_checkpoint_when_prefix_is_complete(self) -> None:
        pending = _pending_raw_queries(
            [_row("a"), _row("b"), _row("c")],
            processed_query_ids={"a", "b"},
            last_processed_query_id="b",
        )

        self.assertEqual([row.synthetic_query_id for row in pending], ["c"])

    def test_pending_queries_keeps_unprocessed_prefix_when_source_scope_expands(self) -> None:
        pending = _pending_raw_queries(
            [_row("old-a"), _row("old-b"), _row("last-retry-row")],
            processed_query_ids={"last-retry-row"},
            last_processed_query_id="last-retry-row",
        )

        self.assertEqual([row.synthetic_query_id for row in pending], ["old-a", "old-b"])


if __name__ == "__main__":
    unittest.main()
