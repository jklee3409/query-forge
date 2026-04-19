from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from typing import Any

import psycopg


WHITESPACE_PATTERN = re.compile(r"\s+")


def _normalize(text: str) -> str:
    return WHITESPACE_PATTERN.sub(" ", (text or "").strip()).lower()


def run(
    *,
    dataset_id: str,
    db_host: str,
    db_port: int,
    db_name: str,
    db_user: str,
    db_password: str,
) -> dict[str, Any]:
    connection = psycopg.connect(
        host=db_host,
        port=db_port,
        dbname=db_name,
        user=db_user,
        password=db_password,
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT dataset_name, dataset_key, total_items, version, metadata
                FROM eval_dataset
                WHERE dataset_id = %s
                """,
                (dataset_id,),
            )
            dataset_meta = cursor.fetchone()
            if not dataset_meta:
                raise RuntimeError(f"dataset not found: {dataset_id}")

            cursor.execute(
                """
                SELECT s.sample_id,
                       s.user_query_ko,
                       s.expected_chunk_ids,
                       COALESCE(s.metadata, '{}'::jsonb)
                FROM eval_dataset_item edi
                JOIN eval_samples s ON s.sample_id = edi.sample_id
                WHERE edi.dataset_id = %s
                  AND edi.active = TRUE
                ORDER BY s.sample_id
                """,
                (dataset_id,),
            )
            rows = cursor.fetchall()

            cursor.execute("SELECT query_text, target_chunk_ids FROM synthetic_queries_raw_all")
            synthetic_rows = cursor.fetchall()

        synthetic_queries = {_normalize(row[0]) for row in synthetic_rows if row and row[0]}
        synthetic_chunk_signatures = {
            tuple(sorted((row[1] or [])))
            for row in synthetic_rows
            if row and row[1]
        }

        origin_counter: Counter[str] = Counter()
        builder_rows = []
        for sample_id, user_query, expected_chunk_ids, metadata in rows:
            metadata = metadata or {}
            if metadata.get("builder") == "build-eval-dataset":
                origin = "build-eval-dataset"
            elif metadata.get("generation_mode") == "corpus_grounded_new_query":
                origin = "corpus_grounded_new_query"
            elif metadata.get("query_style") == "short_user":
                origin = "short_user_other"
            else:
                origin = "other_or_none"
            origin_counter[origin] += 1

            if origin == "build-eval-dataset":
                builder_rows.append((sample_id, user_query, expected_chunk_ids, metadata))

        builder_chunk_matches = [
            sample_id
            for sample_id, _, chunk_ids, _ in builder_rows
            if tuple(sorted((chunk_ids or []))) in synthetic_chunk_signatures
        ]
        builder_query_matches = [
            sample_id
            for sample_id, query, _, _ in builder_rows
            if _normalize(query) in synthetic_queries
        ]

        summary = {
            "dataset": {
                "dataset_id": dataset_id,
                "dataset_name": dataset_meta[0],
                "dataset_key": dataset_meta[1],
                "total_items": dataset_meta[2],
                "version": dataset_meta[3],
                "metadata": dataset_meta[4],
            },
            "active_sample_count": len(rows),
            "origin_distribution": dict(origin_counter),
            "build_eval_dataset_rows": len(builder_rows),
            "build_eval_dataset_chunk_signature_match_with_synthetic": {
                "count": len(builder_chunk_matches),
                "rate": round(len(builder_chunk_matches) / len(builder_rows), 4) if builder_rows else 0.0,
                "sample_ids_preview": builder_chunk_matches[:20],
            },
            "build_eval_dataset_query_exact_match_with_synthetic": {
                "count": len(builder_query_matches),
                "rate": round(len(builder_query_matches) / len(builder_rows), 4) if builder_rows else 0.0,
                "sample_ids_preview": builder_query_matches[:20],
            },
            "sample_preview": [
                {
                    "sample_id": sample_id,
                    "user_query_ko": user_query,
                    "metadata": metadata,
                }
                for sample_id, user_query, _, metadata in builder_rows[:10]
            ],
        }
        return summary
    finally:
        connection.close()


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify whether eval dataset rows are synthetic-reselected.")
    parser.add_argument("--dataset-id", default="dd92f265-d35e-43f3-9dc7-bdc8243a3054")
    parser.add_argument("--db-host", default="localhost")
    parser.add_argument("--db-port", type=int, default=5432)
    parser.add_argument("--db-name", default="query_forge")
    parser.add_argument("--db-user", default="query_forge")
    parser.add_argument("--db-password", default="query_forge")
    args = parser.parse_args()

    summary = run(
        dataset_id=args.dataset_id,
        db_host=args.db_host,
        db_port=args.db_port,
        db_name=args.db_name,
        db_user=args.db_user,
        db_password=args.db_password,
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
