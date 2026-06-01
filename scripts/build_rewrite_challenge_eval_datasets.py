from __future__ import annotations

import argparse
import json
import re
import uuid
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import psycopg
from psycopg.types.json import Jsonb


REPO_ROOT = Path(__file__).resolve().parents[1]
VERSION_LABEL = "v1-2026-06-01"
DATASET_PROFILE = "rewrite_challenge_anchor_gap_80"
EVALUATION_FOCUS = ["rewrite", "anchor_recovery", "retrieval_stress", "domain_retrieval"]
KO_TARGET_METHOD = "A/C"
ASCII_ANCHOR_RE = re.compile(r"[A-Za-z@._-]")
HANGUL_RE = re.compile(r"[가-힣]")


@dataclass(frozen=True, slots=True)
class DomainSpec:
    name: str
    source_dataset_id: str
    source_dataset_key: str
    source_file: Path
    ko_dataset_key: str
    ko_dataset_name: str
    ko_description: str
    ko_output_file: Path
    sample_prefix: str

    @property
    def ko_dataset_id(self) -> str:
        return _dataset_id(self.ko_dataset_key)


DOMAIN_SPECS: tuple[DomainSpec, ...] = (
    DomainSpec(
        name="spring",
        source_dataset_id="44282405-1ea1-5f78-bf85-6270724ee475",
        source_dataset_key="spring_kr_anchor_translated_short_user_80",
        source_file=REPO_ROOT / "data" / "eval" / "spring_kr_anchor_translated_short_user_test_80.jsonl",
        ko_dataset_key="spring_kr_rewrite_challenge_80",
        ko_dataset_name="Spring KR Rewrite Challenge 80",
        ko_description=(
            "Spring Korean rewrite challenge dataset copied from the anchor-translated grounded "
            "short-user dataset. Query surfaces intentionally remove English/API anchor forms."
        ),
        ko_output_file=REPO_ROOT / "data" / "eval" / "spring_kr_rewrite_challenge_80.jsonl",
        sample_prefix="spring-kr-rewrite-challenge",
    ),
    DomainSpec(
        name="postgresql",
        source_dataset_id="8a08c160-e4cd-5ce0-9f5c-640c51b6d887",
        source_dataset_key="postgresql_kr_anchor_translated_short_user_80",
        source_file=REPO_ROOT / "data" / "eval" / "postgresql_kr_anchor_translated_short_user_test_80.jsonl",
        ko_dataset_key="postgresql_kr_rewrite_challenge_80",
        ko_dataset_name="PostgreSQL KR Rewrite Challenge 80",
        ko_description=(
            "PostgreSQL Korean rewrite challenge dataset copied from the anchor-translated grounded "
            "short-user dataset. Query surfaces intentionally remove English/SQL anchor forms."
        ),
        ko_output_file=REPO_ROOT / "data" / "eval" / "postgresql_kr_rewrite_challenge_80.jsonl",
        sample_prefix="postgresql-kr-rewrite-challenge",
    ),
    DomainSpec(
        name="kubernetes",
        source_dataset_id="87f74f10-1e61-5c56-84f9-f70a87fba424",
        source_dataset_key="kubernetes_kr_short_user_80",
        source_file=REPO_ROOT / "data" / "eval" / "kubernetes_kr_short_user_test_80.jsonl",
        ko_dataset_key="kubernetes_kr_rewrite_challenge_80",
        ko_dataset_name="Kubernetes KR Rewrite Challenge 80",
        ko_description=(
            "Kubernetes Korean rewrite challenge dataset copied from the grounded Korean "
            "short-user dataset. Query surfaces intentionally remove English/API anchor forms."
        ),
        ko_output_file=REPO_ROOT / "data" / "eval" / "kubernetes_kr_rewrite_challenge_80.jsonl",
        sample_prefix="kubernetes-kr-rewrite-challenge",
    ),
)


def _dataset_id(dataset_key: str) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"query-forge:{dataset_key}:{VERSION_LABEL}"))


def _load_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n",
        encoding="utf-8",
    )


def _select_specs(names: set[str] | None) -> list[DomainSpec]:
    if not names:
        return list(DOMAIN_SPECS)
    selected = [spec for spec in DOMAIN_SPECS if spec.name in names]
    missing = sorted(names - {spec.name for spec in selected})
    if missing:
        raise RuntimeError(f"Unknown domain names: {', '.join(missing)}")
    return selected


def _metadata(row: dict[str, Any], spec: DomainSpec, *, now: str) -> dict[str, Any]:
    source_metadata = row.get("metadata") if isinstance(row.get("metadata"), dict) else {}
    metadata = dict(source_metadata)
    metadata.update(
        {
            "updated_at": now,
            "dataset_key": spec.ko_dataset_key,
            "dataset_profile": DATASET_PROFILE,
            "query_style": "short_user",
            "query_language": "ko",
            "target_method": KO_TARGET_METHOD,
            "source_dataset_id": spec.source_dataset_id,
            "source_dataset_key": spec.source_dataset_key,
            "source_sample_id": row["sample_id"],
            "source_user_query_ko": row.get("user_query_ko"),
            "source_artifact": _rel_path(spec.source_file),
            "query_surface_policy": "Korean-only anchor-gap query with English/API anchor surfaces removed",
            "evaluation_focus": EVALUATION_FOCUS,
        }
    )
    return metadata


def _rel_path(path: Path) -> str:
    return str(path.relative_to(REPO_ROOT)).replace("\\", "/")


def _build_ko_rows(spec: DomainSpec) -> list[dict[str, Any]]:
    source_rows = _load_jsonl(spec.source_file)
    if len(source_rows) != 80:
        raise RuntimeError(f"{spec.name} source row count mismatch: {len(source_rows)}")

    now = datetime.now(timezone.utc).isoformat()
    rows: list[dict[str, Any]] = []
    for index, source_row in enumerate(source_rows, start=1):
        query = str(source_row.get("user_query_ko") or "").strip()
        metadata = _metadata(source_row, spec, now=now)
        rows.append(
            {
                "sample_id": f"{spec.sample_prefix}-{index:03d}",
                "split": source_row.get("split") or "test",
                "query_language": "ko",
                "user_query_ko": query,
                "user_query_en": None,
                "dialog_context": source_row.get("dialog_context") or {},
                "expected_doc_ids": source_row.get("expected_doc_ids") or [],
                "expected_chunk_ids": source_row.get("expected_chunk_ids") or [],
                "expected_answer_key_points": source_row.get("expected_answer_key_points") or [],
                "query_category": source_row.get("query_category") or "short_user",
                "difficulty": "hard",
                "single_or_multi_chunk": source_row.get("single_or_multi_chunk") or "single",
                "source_product": source_row.get("source_product"),
                "source_version_if_available": source_row.get("source_version_if_available"),
                "target_method": KO_TARGET_METHOD,
                "evaluation_focus": EVALUATION_FOCUS,
                "metadata": metadata,
            }
        )
    return rows


def _validate_ko_rows(spec: DomainSpec, rows: list[dict[str, Any]]) -> dict[str, Any]:
    issues: list[str] = []
    if len(rows) != 80:
        issues.append(f"row count mismatch: {len(rows)} != 80")

    queries = [str(row.get("user_query_ko") or "") for row in rows]
    duplicate_queries = [query for query, count in Counter(queries).items() if count > 1]
    if duplicate_queries:
        issues.append(f"duplicate Korean queries: {duplicate_queries[:5]}")

    ascii_query_ids: list[str] = []
    missing_hangul_ids: list[str] = []
    missing_grounding_ids: list[str] = []
    for row in rows:
        sample_id = str(row["sample_id"])
        query = str(row.get("user_query_ko") or "")
        if ASCII_ANCHOR_RE.search(query):
            ascii_query_ids.append(sample_id)
        if not HANGUL_RE.search(query):
            missing_hangul_ids.append(sample_id)
        if not row.get("expected_doc_ids") or not row.get("expected_chunk_ids"):
            missing_grounding_ids.append(sample_id)
        if row.get("query_language") != "ko" or row.get("user_query_en") is not None:
            issues.append(f"{sample_id}: invalid Korean query fields")

    if ascii_query_ids:
        issues.append(f"queries containing ASCII anchor surfaces: {ascii_query_ids[:10]}")
    if missing_hangul_ids:
        issues.append(f"queries without Hangul: {missing_hangul_ids[:10]}")
    if missing_grounding_ids:
        issues.append(f"missing grounding: {missing_grounding_ids[:10]}")

    return {
        "status": "pass" if not issues else "fail",
        "issues": issues,
        "dataset_key": spec.ko_dataset_key,
        "dataset_id": spec.ko_dataset_id,
        "row_count": len(rows),
        "ascii_anchor_query_count": len(ascii_query_ids),
        "single_multi_distribution": dict(Counter(str(row["single_or_multi_chunk"]) for row in rows)),
        "target_method_distribution": dict(Counter(str(row["target_method"]) for row in rows)),
    }


def _fetch_source_domain_id(connection: psycopg.Connection[Any], spec: DomainSpec) -> str | None:
    with connection.cursor() as cursor:
        cursor.execute(
            "SELECT domain_id::text FROM eval_dataset WHERE dataset_id = %s",
            (spec.source_dataset_id,),
        )
        row = cursor.fetchone()
    return str(row[0]) if row and row[0] else None


def _upsert_dataset(
    connection: psycopg.Connection[Any],
    spec: DomainSpec,
    rows: list[dict[str, Any]],
) -> None:
    now = datetime.now(timezone.utc).isoformat()
    domain_id = _fetch_source_domain_id(connection, spec)
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
                metadata,
                domain_id
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            ON CONFLICT (dataset_key) DO UPDATE
            SET dataset_name = EXCLUDED.dataset_name,
                description = EXCLUDED.description,
                version = EXCLUDED.version,
                split_strategy = EXCLUDED.split_strategy,
                total_items = EXCLUDED.total_items,
                category_distribution = EXCLUDED.category_distribution,
                single_multi_distribution = EXCLUDED.single_multi_distribution,
                metadata = EXCLUDED.metadata,
                domain_id = EXCLUDED.domain_id,
                updated_at = NOW()
            """,
            (
                spec.ko_dataset_id,
                spec.ko_dataset_key,
                spec.ko_dataset_name,
                spec.ko_description,
                VERSION_LABEL,
                "test_only",
                len(rows),
                Jsonb(dict(Counter(str(row["query_category"]) for row in rows))),
                Jsonb(dict(Counter(str(row["single_or_multi_chunk"]) for row in rows))),
                Jsonb(
                    {
                        "dataset_profile": DATASET_PROFILE,
                        "query_language": "ko",
                        "target_method": KO_TARGET_METHOD,
                        "source_dataset_id": spec.source_dataset_id,
                        "source_dataset_key": spec.source_dataset_key,
                        "source_file": _rel_path(spec.source_file),
                        "source_dataset_preserved": True,
                        "source_file_query_surface_reused": True,
                        "query_surface_policy": "Korean-only anchor-gap query with English/API anchor surfaces removed",
                        "evaluation_focus": EVALUATION_FOCUS,
                        "updated_at": now,
                    }
                ),
                domain_id,
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
                    metadata,
                    domain_id
                ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
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
                    metadata = EXCLUDED.metadata,
                    domain_id = EXCLUDED.domain_id
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
                    domain_id,
                ),
            )

        cursor.execute("DELETE FROM eval_dataset_item WHERE dataset_id = %s", (spec.ko_dataset_id,))
        for row in rows:
            cursor.execute(
                """
                INSERT INTO eval_dataset_item (
                    dataset_id,
                    sample_id,
                    query_category,
                    single_or_multi_chunk,
                    active,
                    domain_id
                ) VALUES (%s, %s, %s, %s, TRUE, %s)
                """,
                (
                    spec.ko_dataset_id,
                    row["sample_id"],
                    row["query_category"],
                    row["single_or_multi_chunk"],
                    domain_id,
                ),
            )


def run(
    *,
    domain_names: set[str] | None,
    report_file: Path,
    skip_db: bool,
    db_host: str,
    db_port: int,
    db_name: str,
    db_user: str,
    db_password: str,
) -> dict[str, Any]:
    results: list[dict[str, Any]] = []
    selected_specs = _select_specs(domain_names)

    connection: psycopg.Connection[Any] | None = None
    if not skip_db:
        connection = psycopg.connect(
            host=db_host,
            port=db_port,
            dbname=db_name,
            user=db_user,
            password=db_password,
            autocommit=False,
        )

    try:
        for spec in selected_specs:
            rows = _build_ko_rows(spec)
            validation = _validate_ko_rows(spec, rows)
            if validation["status"] != "pass":
                raise RuntimeError(json.dumps(validation, ensure_ascii=False, indent=2))
            _write_jsonl(spec.ko_output_file, rows)
            if connection is not None:
                _upsert_dataset(connection, spec, rows)
            results.append(
                {
                    "domain": spec.name,
                    "language": "ko",
                    "dataset_key": spec.ko_dataset_key,
                    "dataset_id": spec.ko_dataset_id,
                    "output_file": _rel_path(spec.ko_output_file),
                    "source_dataset_id": spec.source_dataset_id,
                    "source_dataset_key": spec.source_dataset_key,
                    "validation": validation,
                }
            )
        if connection is not None:
            connection.commit()
    except Exception:
        if connection is not None:
            connection.rollback()
        raise
    finally:
        if connection is not None:
            connection.close()

    report = {
        "version": VERSION_LABEL,
        "dataset_profile": DATASET_PROFILE,
        "skip_db": skip_db,
        "built_at": datetime.now(timezone.utc).isoformat(),
        "results": results,
    }
    report_file.parent.mkdir(parents=True, exist_ok=True)
    report_file.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description="Build rewrite challenge eval datasets.")
    parser.add_argument("--domain", action="append", choices=[spec.name for spec in DOMAIN_SPECS])
    parser.add_argument(
        "--report-file",
        default=str(REPO_ROOT / "data" / "reports" / "rewrite_challenge_80_ko_audit_2026-06-01.json"),
    )
    parser.add_argument("--skip-db", action="store_true")
    parser.add_argument("--db-host", default="localhost")
    parser.add_argument("--db-port", type=int, default=5432)
    parser.add_argument("--db-name", default="query_forge")
    parser.add_argument("--db-user", default="query_forge")
    parser.add_argument("--db-password", default="query_forge")
    args = parser.parse_args()

    report = run(
        domain_names=set(args.domain) if args.domain else None,
        report_file=Path(args.report_file),
        skip_db=args.skip_db,
        db_host=args.db_host,
        db_port=args.db_port,
        db_name=args.db_name,
        db_user=args.db_user,
        db_password=args.db_password,
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
