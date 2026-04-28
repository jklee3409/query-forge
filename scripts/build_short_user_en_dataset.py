from __future__ import annotations

import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import psycopg
from psycopg.types.json import Jsonb


ASCII_TERM_RE = re.compile(r"@[A-Za-z][A-Za-z0-9_]+|[A-Za-z][A-Za-z0-9_.:/+-]{1,}")
SECTION_PATH_RE = re.compile(r"Section Path:\s*([^|.]+)")
GENERIC_TERMS = {
    "section",
    "path",
    "overlap",
    "context",
    "previous",
    "chunk",
    "java",
    "kotlin",
    "spring",
    "reference",
    "example",
}

EN_DATASET_ID = "8f0d6e0f-6f9e-4d64-9b07-f4e8ce5ebec0"
EN_DATASET_KEY = "human_eval_short_user_80_en"


def _normalize_spaces(text: str) -> str:
    return " ".join((text or "").strip().split())


def _extract_terms(*values: str, max_terms: int = 4) -> list[str]:
    picked: list[str] = []
    seen: set[str] = set()
    for value in values:
        for token in ASCII_TERM_RE.findall(value or ""):
            cleaned = token.strip(".,;:()[]{}<>\"'")
            if len(cleaned) < 2:
                continue
            key = cleaned.lower()
            if key in GENERIC_TERMS:
                continue
            if key in seen:
                continue
            seen.add(key)
            picked.append(cleaned)
            if len(picked) >= max_terms:
                return picked
    return picked


def _extract_section_hint(expected_points: list[str]) -> str:
    for point in expected_points:
        match = SECTION_PATH_RE.search(point or "")
        if match:
            section_text = _normalize_spaces(match.group(1))
            section_terms = _extract_terms(section_text, max_terms=4)
            if section_terms:
                return " ".join(section_terms[:2])
            return section_text
    return ""


def _build_query(source_row: dict[str, Any]) -> str:
    query_ko = str(source_row.get("user_query_ko") or "")
    expected_points = [str(item) for item in (source_row.get("expected_answer_key_points") or [])]
    terms = _extract_terms(
        query_ko,
        " ".join(expected_points),
        str(source_row.get("source_product") or ""),
        max_terms=4,
    )
    section_hint = _extract_section_hint(expected_points)
    multi = str(source_row.get("single_or_multi_chunk") or "").lower() == "multi"

    if len(terms) >= 2:
        if multi:
            query = f"{terms[0]} {terms[1]} usage and differences"
        else:
            query = f"{terms[0]} {terms[1]} example"
    elif len(terms) == 1:
        if section_hint:
            query = f"{terms[0]} {section_hint}"
        elif multi:
            query = f"{terms[0]} usage and configuration"
        else:
            query = f"{terms[0]} overview"
    elif section_hint:
        query = section_hint
    else:
        query = str(source_row.get("source_product") or "Spring docs")

    return _normalize_spaces(query).rstrip("?.!") + "?"


def _build_en_rows(source_rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for index, row in enumerate(source_rows, start=1):
        query_en = _build_query(row)
        rows.append(
            {
                "sample_id": f"test-short-user-en-{index:03d}",
                "paired_sample_id": row["sample_id"],
                "split": row.get("split", "test"),
                "user_query_ko": "",
                "user_query_en": query_en,
                "query_language": "en",
                "dialog_context": row.get("dialog_context", {}),
                "expected_doc_ids": row.get("expected_doc_ids", []),
                "expected_chunk_ids": row.get("expected_chunk_ids", []),
                "expected_answer_key_points": row.get("expected_answer_key_points", []),
                "query_category": row.get("query_category", "short_user"),
                "difficulty": row.get("difficulty"),
                "single_or_multi_chunk": row.get("single_or_multi_chunk"),
                "source_product": row.get("source_product"),
                "source_version_if_available": row.get("source_version_if_available"),
                "metadata": {
                    "builder": "short-user-en-v1",
                    "query_language": "en",
                    "paired_sample_id": row["sample_id"],
                    "dataset_key": EN_DATASET_KEY,
                    "target_method": "E",
                    "evaluation_focus": ["rewrite", "retrieval", "language_comparison"],
                },
            }
        )
    return rows


def _upsert_dataset(connection: psycopg.Connection[Any], rows: list[dict[str, Any]], output_file: str) -> None:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO eval_dataset (
                dataset_id,
                dataset_key,
                dataset_name,
                description,
                version,
                split_strategy,
                total_items,
                category_distribution,
                single_multi_distribution,
                metadata
            ) VALUES (
                %s, %s, %s, %s, %s, %s, %s, %s, %s, %s
            )
            ON CONFLICT (dataset_key) DO UPDATE
            SET dataset_name = EXCLUDED.dataset_name,
                description = EXCLUDED.description,
                version = EXCLUDED.version,
                split_strategy = EXCLUDED.split_strategy,
                total_items = EXCLUDED.total_items,
                category_distribution = EXCLUDED.category_distribution,
                single_multi_distribution = EXCLUDED.single_multi_distribution,
                metadata = EXCLUDED.metadata,
                updated_at = NOW()
            """,
            (
                EN_DATASET_ID,
                EN_DATASET_KEY,
                "English Short User Eval 80",
                "English short-user evaluation dataset paired to the existing Korean short-user 80 set.",
                "v1-2026-04-28",
                "test_only",
                len(rows),
                Jsonb({"short_user": len(rows)}),
                Jsonb(
                    {
                        "single": sum(1 for row in rows if row.get("single_or_multi_chunk") == "single"),
                        "multi": sum(1 for row in rows if row.get("single_or_multi_chunk") == "multi"),
                    }
                ),
                Jsonb(
                    {
                        "query_language": "en",
                        "paired_dataset_key": "human_eval_short_user_80",
                        "source_file": output_file,
                        "updated_at": datetime.now(timezone.utc).isoformat(),
                    }
                ),
            ),
        )

        for row in rows:
            cursor.execute(
                """
                INSERT INTO eval_samples (
                    sample_id,
                    split,
                    user_query_ko,
                    user_query_en,
                    query_language,
                    dialog_context,
                    expected_doc_ids,
                    expected_chunk_ids,
                    expected_answer_key_points,
                    query_category,
                    difficulty,
                    single_or_multi_chunk,
                    source_product,
                    source_version_if_available,
                    metadata
                ) VALUES (
                    %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s
                )
                ON CONFLICT (sample_id) DO UPDATE
                SET split = EXCLUDED.split,
                    user_query_ko = EXCLUDED.user_query_ko,
                    user_query_en = EXCLUDED.user_query_en,
                    query_language = EXCLUDED.query_language,
                    dialog_context = EXCLUDED.dialog_context,
                    expected_doc_ids = EXCLUDED.expected_doc_ids,
                    expected_chunk_ids = EXCLUDED.expected_chunk_ids,
                    expected_answer_key_points = EXCLUDED.expected_answer_key_points,
                    query_category = EXCLUDED.query_category,
                    difficulty = EXCLUDED.difficulty,
                    single_or_multi_chunk = EXCLUDED.single_or_multi_chunk,
                    source_product = EXCLUDED.source_product,
                    source_version_if_available = EXCLUDED.source_version_if_available,
                    metadata = EXCLUDED.metadata
                """,
                (
                    row["sample_id"],
                    row["split"],
                    row["user_query_ko"],
                    row["user_query_en"],
                    row["query_language"],
                    Jsonb(row["dialog_context"]),
                    Jsonb(row["expected_doc_ids"]),
                    Jsonb(row["expected_chunk_ids"]),
                    Jsonb(row["expected_answer_key_points"]),
                    row["query_category"],
                    row["difficulty"],
                    row["single_or_multi_chunk"],
                    row["source_product"],
                    row["source_version_if_available"],
                    Jsonb(row["metadata"]),
                ),
            )

        cursor.execute("DELETE FROM eval_dataset_item WHERE dataset_id = %s", (EN_DATASET_ID,))
        for row in rows:
            cursor.execute(
                """
                INSERT INTO eval_dataset_item (
                    dataset_id,
                    sample_id,
                    query_category,
                    single_or_multi_chunk,
                    active
                ) VALUES (%s, %s, %s, %s, TRUE)
                """,
                (
                    EN_DATASET_ID,
                    row["sample_id"],
                    row["query_category"],
                    row["single_or_multi_chunk"],
                ),
            )


def run(
    *,
    input_file: Path,
    output_file: Path,
    skip_db: bool,
    db_host: str,
    db_port: int,
    db_name: str,
    db_user: str,
    db_password: str,
) -> dict[str, Any]:
    source_rows = [json.loads(line) for line in input_file.read_text(encoding="utf-8").splitlines() if line.strip()]
    en_rows = _build_en_rows(source_rows)
    output_file.parent.mkdir(parents=True, exist_ok=True)
    output_file.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in en_rows) + "\n",
        encoding="utf-8",
    )

    if not skip_db:
        with psycopg.connect(
            host=db_host,
            port=db_port,
            dbname=db_name,
            user=db_user,
            password=db_password,
            autocommit=False,
        ) as connection:
            _upsert_dataset(connection, en_rows, str(output_file).replace("\\", "/"))
            connection.commit()

    return {
        "dataset_id": EN_DATASET_ID,
        "dataset_key": EN_DATASET_KEY,
        "source_count": len(source_rows),
        "generated_count": len(en_rows),
        "output_file": str(output_file),
        "skip_db": skip_db,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Build the English short-user-80 eval dataset.")
    parser.add_argument("--input-file", default="data/eval/human_eval_short_user_test_80.jsonl")
    parser.add_argument("--output-file", default="data/eval/human_eval_short_user_test_80_en.jsonl")
    parser.add_argument("--skip-db", action="store_true")
    parser.add_argument("--db-host", default="localhost")
    parser.add_argument("--db-port", type=int, default=5432)
    parser.add_argument("--db-name", default="query_forge")
    parser.add_argument("--db-user", default="query_forge")
    parser.add_argument("--db-password", default="query_forge")
    args = parser.parse_args()

    summary = run(
        input_file=Path(args.input_file),
        output_file=Path(args.output_file),
        skip_db=args.skip_db,
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
