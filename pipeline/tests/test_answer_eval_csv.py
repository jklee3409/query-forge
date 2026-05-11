from __future__ import annotations

import csv
import tempfile
import unittest
from pathlib import Path

from pipeline.eval.answer_eval import _write_csv


class AnswerEvalCsvTests(unittest.TestCase):
    def test_write_csv_extends_fieldnames_for_new_columns(self) -> None:
        rows = [
            {
                "sample_id": "sample-1",
                "correctness": 0.75,
                "rewrite_llm_attempted": True,
                "rewrite_llm_succeeded": False,
                "rewrite_heuristic_fallback_used": True,
            }
        ]
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "answer_detail.csv"
            _write_csv(path, rows, ["sample_id", "correctness"])

            with path.open("r", encoding="utf-8", newline="") as source:
                reader = csv.DictReader(source)
                fieldnames = reader.fieldnames or []
                self.assertIn("rewrite_llm_attempted", fieldnames)
                self.assertIn("rewrite_llm_succeeded", fieldnames)
                self.assertIn("rewrite_heuristic_fallback_used", fieldnames)
                written = next(reader)

            self.assertEqual(written["sample_id"], "sample-1")
            self.assertEqual(written["rewrite_llm_attempted"], "True")
            self.assertEqual(written["rewrite_llm_succeeded"], "False")
            self.assertEqual(written["rewrite_heuristic_fallback_used"], "True")


if __name__ == "__main__":
    unittest.main()
