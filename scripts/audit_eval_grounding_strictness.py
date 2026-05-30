from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import psycopg


REPO_ROOT = Path(__file__).resolve().parents[1]
REPORT_DEFAULT = REPO_ROOT / "data" / "reports" / "spring_postgresql_kubernetes_eval_strictness_2026-05-30.json"
HANGUL_RE = re.compile(r"[가-힣]")


@dataclass(frozen=True, slots=True)
class DatasetPair:
    domain: str
    kr_dataset_id: str
    en_dataset_id: str
    kr_prefix: str
    en_prefix: str


DATASET_PAIRS: tuple[DatasetPair, ...] = (
    DatasetPair(
        domain="spring",
        kr_dataset_id="b2d47254-8655-4c9c-81ac-7615677ec5bd",
        en_dataset_id="8f0d6e0f-6f9e-4d64-9b07-f4e8ce5ebec0",
        kr_prefix="test-short-user-",
        en_prefix="test-short-user-en-",
    ),
    DatasetPair(
        domain="postgresql",
        kr_dataset_id="862642e6-10bd-538d-9ba8-5de7f1f26d3c",
        en_dataset_id="020a93c4-0465-5655-b681-a5799a98fd15",
        kr_prefix="postgresql-kr-short-user-",
        en_prefix="postgresql-en-short-user-",
    ),
    DatasetPair(
        domain="kubernetes",
        kr_dataset_id="87f74f10-1e61-5c56-84f9-f70a87fba424",
        en_dataset_id="e0445e9e-7ed3-58aa-8ce1-a32d06d44a11",
        kr_prefix="kubernetes-kr-short-user-",
        en_prefix="kubernetes-en-short-user-",
    ),
)


def _fetch_dataset(connection: psycopg.Connection[Any], dataset_id: str) -> dict[str, Any]:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT dataset_id::text,
                   dataset_key,
                   dataset_name,
                   version,
                   total_items,
                   domain_id::text,
                   COALESCE(metadata, '{}'::jsonb)
            FROM eval_dataset
            WHERE dataset_id = %s
            """,
            (dataset_id,),
        )
        row = cursor.fetchone()
    if not row:
        raise RuntimeError(f"dataset not found: {dataset_id}")
    return {
        "dataset_id": str(row[0]),
        "dataset_key": str(row[1] or ""),
        "dataset_name": str(row[2] or ""),
        "version": str(row[3] or ""),
        "total_items": int(row[4] or 0),
        "domain_id": str(row[5] or ""),
        "metadata": row[6] or {},
    }


def _fetch_active_rows(connection: psycopg.Connection[Any], dataset_id: str) -> list[dict[str, Any]]:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT s.sample_id,
                   s.query_language,
                   COALESCE(s.user_query_ko, ''),
                   COALESCE(s.user_query_en, ''),
                   s.expected_doc_ids,
                   s.expected_chunk_ids,
                   s.expected_answer_key_points,
                   s.single_or_multi_chunk,
                   COALESCE(s.source_product, ''),
                   s.domain_id::text
            FROM eval_dataset_item i
            JOIN eval_samples s ON s.sample_id = i.sample_id
            WHERE i.dataset_id = %s
              AND i.active = TRUE
            ORDER BY s.sample_id
            """,
            (dataset_id,),
        )
        rows = cursor.fetchall()
    return [
        {
            "sample_id": str(row[0]),
            "query_language": str(row[1] or ""),
            "user_query_ko": str(row[2] or ""),
            "user_query_en": str(row[3] or ""),
            "expected_doc_ids": [str(value) for value in (row[4] or [])],
            "expected_chunk_ids": [str(value) for value in (row[5] or [])],
            "expected_answer_key_points": [str(value) for value in (row[6] or [])],
            "single_or_multi_chunk": str(row[7] or ""),
            "source_product": str(row[8] or ""),
            "domain_id": str(row[9] or ""),
        }
        for row in rows
    ]


def _fetch_chunks(connection: psycopg.Connection[Any], chunk_ids: list[str]) -> dict[str, dict[str, Any]]:
    if not chunk_ids:
        return {}
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT c.chunk_id,
                   c.document_id,
                   COALESCE(c.chunk_text, ''),
                   d.domain_id::text,
                   COALESCE(d.source_id, '')
            FROM corpus_chunks c
            JOIN corpus_documents d ON d.document_id = c.document_id
            WHERE c.chunk_id = ANY(%s)
            """,
            (list(dict.fromkeys(chunk_ids)),),
        )
        rows = cursor.fetchall()
    return {
        str(row[0]): {
            "document_id": str(row[1]),
            "chunk_text": str(row[2] or ""),
            "domain_id": str(row[3] or ""),
            "source_id": str(row[4] or ""),
        }
        for row in rows
    }


def _suffix(sample_id: str, prefix: str) -> str:
    if not sample_id.startswith(prefix):
        raise RuntimeError(f"sample id {sample_id} does not start with {prefix}")
    return sample_id[len(prefix) :]


def _row_validation(rows: list[dict[str, Any]], *, language: str, dataset_domain_id: str) -> dict[str, Any]:
    noisy = 0
    empty_query = 0
    wrong_language = 0
    en_ko_field_nonempty = 0
    en_hangul = 0
    duplicate_queries = 0
    sample_domain_mismatch = 0
    chunk_cardinality_mismatch = 0
    seen_queries: set[str] = set()
    for row in rows:
        if row["domain_id"] != dataset_domain_id:
            sample_domain_mismatch += 1
        if len(row["expected_chunk_ids"]) > 1 and row["single_or_multi_chunk"] != "multi":
            chunk_cardinality_mismatch += 1
        if len(row["expected_chunk_ids"]) == 1 and row["single_or_multi_chunk"] != "single":
            chunk_cardinality_mismatch += 1
        keypoints_text = json.dumps(row["expected_answer_key_points"], ensure_ascii=False)
        if (
            "Overlap context from previous chunk" in keypoints_text
            or "latest stable version" in keypoints_text.lower()
            or "still in development" in keypoints_text.lower()
        ):
            noisy += 1
        if row["query_language"] != language:
            wrong_language += 1
        query = row["user_query_en"] if language == "en" else row["user_query_ko"]
        if not query.strip():
            empty_query += 1
        if query in seen_queries:
            duplicate_queries += 1
        seen_queries.add(query)
        if language == "en":
            if row["user_query_ko"].strip():
                en_ko_field_nonempty += 1
            if HANGUL_RE.search(row["user_query_en"]):
                en_hangul += 1
    return {
        "sample_count": len(rows),
        "chunk_refs": sum(len(row["expected_chunk_ids"]) for row in rows),
        "single_count": sum(1 for row in rows if row["single_or_multi_chunk"] == "single"),
        "multi_count": sum(1 for row in rows if row["single_or_multi_chunk"] == "multi"),
        "noisy_keypoint_sample_count": noisy,
        "empty_query_count": empty_query,
        "wrong_query_language_count": wrong_language,
        "duplicate_query_count": duplicate_queries,
        "sample_domain_mismatch_count": sample_domain_mismatch,
        "chunk_cardinality_mismatch_count": chunk_cardinality_mismatch,
        "en_user_query_ko_nonempty_count": en_ko_field_nonempty,
        "en_hangul_query_count": en_hangul,
    }


def _reference_validation(
    rows: list[dict[str, Any]],
    chunks: dict[str, dict[str, Any]],
    dataset_domain_id: str,
) -> dict[str, Any]:
    missing = 0
    doc_mismatch = 0
    domain_mismatch = 0
    exact_doc_set_mismatch = 0
    continuation_refs = 0
    for row in rows:
        doc_ids_from_chunks: list[str] = []
        for chunk_id in row["expected_chunk_ids"]:
            chunk = chunks.get(chunk_id)
            if not chunk:
                missing += 1
                continue
            doc_ids_from_chunks.append(chunk["document_id"])
            if chunk["document_id"] not in row["expected_doc_ids"]:
                doc_mismatch += 1
            if chunk["domain_id"] != dataset_domain_id:
                domain_mismatch += 1
            if chunk["chunk_text"].lstrip().startswith("Overlap context from previous chunk:"):
                continuation_refs += 1
        if set(doc_ids_from_chunks) != set(row["expected_doc_ids"]):
            exact_doc_set_mismatch += 1
    return {
        "missing_chunk_count": missing,
        "chunk_doc_mismatch_count": doc_mismatch,
        "chunk_domain_mismatch_count": domain_mismatch,
        "exact_doc_set_mismatch_count": exact_doc_set_mismatch,
        "continuation_chunk_ref_count": continuation_refs,
    }


def _pair_validation(pair: DatasetPair, kr_rows: list[dict[str, Any]], en_rows: list[dict[str, Any]]) -> dict[str, Any]:
    en_by_suffix = {_suffix(row["sample_id"], pair.en_prefix): row for row in en_rows}
    missing_en_pair = 0
    grounding_mismatch = 0
    order = []
    for kr in kr_rows:
        suffix = _suffix(kr["sample_id"], pair.kr_prefix)
        en = en_by_suffix.get(suffix)
        if not en:
            missing_en_pair += 1
            continue
        if kr["expected_doc_ids"] != en["expected_doc_ids"]:
            grounding_mismatch += 1
        if kr["expected_chunk_ids"] != en["expected_chunk_ids"]:
            grounding_mismatch += 1
        if kr["expected_answer_key_points"] != en["expected_answer_key_points"]:
            grounding_mismatch += 1
        order.append(suffix)
    return {
        "pair_count": len(order),
        "missing_en_pair_count": missing_en_pair,
        "grounding_mismatch_count": grounding_mismatch,
    }


def _audit_pair(connection: psycopg.Connection[Any], pair: DatasetPair) -> dict[str, Any]:
    kr_dataset = _fetch_dataset(connection, pair.kr_dataset_id)
    en_dataset = _fetch_dataset(connection, pair.en_dataset_id)
    kr_rows = _fetch_active_rows(connection, pair.kr_dataset_id)
    en_rows = _fetch_active_rows(connection, pair.en_dataset_id)
    chunk_ids = [chunk_id for row in kr_rows for chunk_id in row["expected_chunk_ids"]]
    chunks = _fetch_chunks(connection, chunk_ids)
    kr_row_validation = _row_validation(kr_rows, language="ko", dataset_domain_id=kr_dataset["domain_id"])
    en_row_validation = _row_validation(en_rows, language="en", dataset_domain_id=en_dataset["domain_id"])
    reference_validation = _reference_validation(kr_rows, chunks, kr_dataset["domain_id"])
    pair_validation = _pair_validation(pair, kr_rows, en_rows)
    hard_fail_count = sum(
        [
            kr_row_validation["sample_count"] != 80,
            en_row_validation["sample_count"] != 80,
            kr_row_validation["noisy_keypoint_sample_count"],
            en_row_validation["noisy_keypoint_sample_count"],
            kr_row_validation["empty_query_count"],
            en_row_validation["empty_query_count"],
            kr_row_validation["wrong_query_language_count"],
            en_row_validation["wrong_query_language_count"],
            kr_row_validation["duplicate_query_count"],
            en_row_validation["duplicate_query_count"],
            kr_row_validation["sample_domain_mismatch_count"],
            en_row_validation["sample_domain_mismatch_count"],
            kr_row_validation["chunk_cardinality_mismatch_count"],
            en_row_validation["chunk_cardinality_mismatch_count"],
            en_row_validation["en_user_query_ko_nonempty_count"],
            en_row_validation["en_hangul_query_count"],
            reference_validation["missing_chunk_count"],
            reference_validation["chunk_doc_mismatch_count"],
            reference_validation["chunk_domain_mismatch_count"],
            reference_validation["exact_doc_set_mismatch_count"],
            pair_validation["missing_en_pair_count"],
            pair_validation["grounding_mismatch_count"],
        ]
    )
    return {
        "domain": pair.domain,
        "status": "pass" if hard_fail_count == 0 else "fail",
        "kr_dataset": {k: v for k, v in kr_dataset.items() if k != "metadata"},
        "en_dataset": {k: v for k, v in en_dataset.items() if k != "metadata"},
        "kr_row_validation": kr_row_validation,
        "en_row_validation": en_row_validation,
        "reference_validation": reference_validation,
        "pair_validation": pair_validation,
        "hard_fail_count": hard_fail_count,
    }


def run(args: argparse.Namespace) -> dict[str, Any]:
    generated_at = datetime.now(timezone.utc).isoformat()
    with psycopg.connect(
        host=args.db_host,
        port=args.db_port,
        dbname=args.db_name,
        user=args.db_user,
        password=args.db_password,
    ) as connection:
        audits = [_audit_pair(connection, pair) for pair in DATASET_PAIRS]
    report = {
        "generated_at": generated_at,
        "status": "pass" if all(audit["status"] == "pass" for audit in audits) else "fail",
        "domains": audits,
    }
    report_path = Path(args.report)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    report["report_path"] = str(report_path)
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description="Audit strict grounding for Spring/PostgreSQL/Kubernetes eval pairs.")
    parser.add_argument("--report", type=Path, default=REPORT_DEFAULT)
    parser.add_argument("--db-host", default="localhost")
    parser.add_argument("--db-port", type=int, default=5432)
    parser.add_argument("--db-name", default="query_forge")
    parser.add_argument("--db-user", default="query_forge")
    parser.add_argument("--db-password", default="query_forge")
    args = parser.parse_args()
    report = run(args)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if report["status"] != "pass":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
