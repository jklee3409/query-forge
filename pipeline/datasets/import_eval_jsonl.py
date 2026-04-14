from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from psycopg.types.json import Jsonb

try:
    from loaders.common import connect, default_database_args
except ModuleNotFoundError:  # pragma: no cover - direct module execution fallback
    from pipeline.loaders.common import connect, default_database_args


REQUIRED_FIELDS = (
    "sample_id",
    "split",
    "user_query_ko",
    "dialog_context",
    "expected_doc_ids",
    "expected_chunk_ids",
    "expected_answer_key_points",
    "query_category",
    "difficulty",
    "single_or_multi_chunk",
    "source_product",
    "source_version_if_available",
)


def _load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as source:
        for line_no, line in enumerate(source, start=1):
            raw = line.strip()
            if not raw:
                continue
            row = json.loads(raw)
            if not isinstance(row, dict):
                raise ValueError(f"{path}:{line_no} row must be an object")
            _validate_row(row, path=path, line_no=line_no)
            rows.append(row)
    return rows


def _validate_row(row: dict[str, Any], *, path: Path, line_no: int) -> None:
    missing = [field for field in REQUIRED_FIELDS if field not in row]
    if missing:
        raise ValueError(f"{path}:{line_no} missing required fields: {', '.join(missing)}")

    if row["split"] not in {"dev", "test", "train"}:
        raise ValueError(f"{path}:{line_no} invalid split: {row['split']}")

    for key in ("expected_doc_ids", "expected_chunk_ids", "expected_answer_key_points"):
        if not isinstance(row[key], list):
            raise ValueError(f"{path}:{line_no} field `{key}` must be an array")
    if not isinstance(row["dialog_context"], dict):
        raise ValueError(f"{path}:{line_no} field `dialog_context` must be an object")


def _build_metadata(row: dict[str, Any]) -> dict[str, Any]:
    metadata = row.get("metadata", {})
    if not isinstance(metadata, dict):
        metadata = {}

    if "target_method" in row:
        metadata["target_method"] = row["target_method"]
    if "evaluation_focus" in row:
        metadata["evaluation_focus"] = row["evaluation_focus"]
    return metadata


def run_eval_jsonl_import(
    *,
    eval_dev_path: Path,
    eval_test_path: Path,
    replace_splits: bool = True,
    database_url: str | None = None,
    db_host: str = "localhost",
    db_port: int = 5432,
    db_name: str = "query_forge",
    db_user: str = "query_forge",
    db_password: str = "query_forge",
) -> dict[str, Any]:
    rows: list[dict[str, Any]] = []
    source_paths: list[str] = []

    if eval_dev_path.exists():
        rows.extend(_load_jsonl(eval_dev_path))
        source_paths.append(str(eval_dev_path))
    if eval_test_path.exists():
        rows.extend(_load_jsonl(eval_test_path))
        source_paths.append(str(eval_test_path))

    if not rows:
        raise FileNotFoundError("No eval JSONL file found (checked dev/test paths).")

    sample_ids = [str(row["sample_id"]) for row in rows]
    unique_sample_ids = list(dict.fromkeys(sample_ids))
    splits = sorted({str(row["split"]) for row in rows})

    options = type(
        "DbOptions",
        (),
        {
            "database_url": database_url,
            "host": db_host,
            "port": db_port,
            "database": db_name,
            "user": db_user,
            "password": db_password,
        },
    )()
    connection = connect(options, autocommit=False)

    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT sample_id FROM eval_samples WHERE sample_id = ANY(%s)",
                (unique_sample_ids,),
            )
            existing_ids = {str(row["sample_id"]) for row in cursor.fetchall()}

            if replace_splits:
                cursor.execute(
                    "DELETE FROM eval_samples WHERE split = ANY(%s)",
                    (splits,),
                )

            for row in rows:
                metadata = _build_metadata(row)
                cursor.execute(
                    """
                    INSERT INTO eval_samples (
                        sample_id,
                        split,
                        user_query_ko,
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
                        %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s
                    )
                    ON CONFLICT (sample_id) DO UPDATE
                    SET split = EXCLUDED.split,
                        user_query_ko = EXCLUDED.user_query_ko,
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
                        Jsonb(row["dialog_context"]),
                        Jsonb(row["expected_doc_ids"]),
                        Jsonb(row["expected_chunk_ids"]),
                        Jsonb(row["expected_answer_key_points"]),
                        row["query_category"],
                        row["difficulty"],
                        row["single_or_multi_chunk"],
                        row.get("source_product"),
                        row.get("source_version_if_available"),
                        Jsonb(metadata),
                    ),
                )

        connection.commit()
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()

    inserted_count = sum(1 for sample_id in unique_sample_ids if sample_id not in existing_ids)
    updated_count = len(unique_sample_ids) - inserted_count

    return {
        "source_paths": source_paths,
        "imported_rows": len(rows),
        "unique_sample_ids": len(unique_sample_ids),
        "splits": splits,
        "replace_splits": replace_splits,
        "inserted_count": inserted_count,
        "updated_count": updated_count,
    }


def run_eval_jsonl_import_from_env(
    *,
    eval_dev_path: Path,
    eval_test_path: Path,
    replace_splits: bool = True,
) -> dict[str, Any]:
    defaults = default_database_args()
    return run_eval_jsonl_import(
        eval_dev_path=eval_dev_path,
        eval_test_path=eval_test_path,
        replace_splits=replace_splits,
        database_url=defaults["database_url"],
        db_host=defaults["db_host"],
        db_port=defaults["db_port"],
        db_name=defaults["db_name"],
        db_user=defaults["db_user"],
        db_password=defaults["db_password"],
    )
