from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb


REPO_ROOT = Path(__file__).resolve().parents[1]
ASCII_TERM_RE = re.compile(r"(?<![A-Za-z0-9_@./:-])[@\\-]*[A-Za-z][A-Za-z0-9_.$#:@/\\-]*(?![A-Za-z0-9_@./:-])")
HANGUL_RE = re.compile(r"[가-힣]")
SPACE_RE = re.compile(r"\s+")


@dataclass(frozen=True, slots=True)
class DatasetPair:
    domain: str
    kr_id: str
    en_id: str
    kr_key: str
    en_key: str
    kr_path: Path
    en_path: Path
    final_kr_name: str
    final_en_name: str


DATASETS = {
    "spring": DatasetPair(
        domain="spring",
        kr_id="57f313dd-461d-561d-9453-0f8e2e179b27",
        en_id="0d95322e-69e2-5dc9-93a8-5cf857a1db78",
        kr_key="spring_kr_rewrite_challenge_80",
        en_key="spring_en_rewrite_challenge_80",
        kr_path=REPO_ROOT / "data" / "eval" / "spring_kr_rewrite_challenge_80.jsonl",
        en_path=REPO_ROOT / "data" / "eval" / "spring_en_rewrite_challenge_80.jsonl",
        final_kr_name="FINAL_Spring_KR_Rewrite_Challenge_80",
        final_en_name="FINAL_Spring_EN_Rewrite_Challenge_80",
    ),
    "postgresql": DatasetPair(
        domain="postgresql",
        kr_id="0a8a0077-7f63-5f6d-b19d-71ae3f137733",
        en_id="96c55e78-3288-5c1e-9e98-1471495da8cc",
        kr_key="postgresql_kr_rewrite_challenge_80",
        en_key="postgresql_en_rewrite_challenge_80",
        kr_path=REPO_ROOT / "data" / "eval" / "postgresql_kr_rewrite_challenge_80.jsonl",
        en_path=REPO_ROOT / "data" / "eval" / "postgresql_en_rewrite_challenge_80.jsonl",
        final_kr_name="FINAL_PostgreSQL_KR_Rewrite_Challenge_80",
        final_en_name="FINAL_PostgreSQL_EN_Rewrite_Challenge_80",
    ),
    "kubernetes": DatasetPair(
        domain="kubernetes",
        kr_id="c61421b4-6154-563a-b71a-fdef5f254b6e",
        en_id="d4ef4bd3-2eb0-5178-b794-f69f921de541",
        kr_key="kubernetes_kr_rewrite_challenge_80",
        en_key="kubernetes_en_rewrite_challenge_80",
        kr_path=REPO_ROOT / "data" / "eval" / "kubernetes_kr_rewrite_challenge_80.jsonl",
        en_path=REPO_ROOT / "data" / "eval" / "kubernetes_en_rewrite_challenge_80.jsonl",
        final_kr_name="FINAL_Kubernetes_KR_Rewrite_Challenge_80",
        final_en_name="FINAL_Kubernetes_EN_Rewrite_Challenge_80",
    ),
}

EN_QUERY_OVERRIDES = {
    "spring-en-rewrite-challenge-020": "Spring Data 3.3 render pages without Spring HATEOAS PagedModel",
    "spring-en-rewrite-challenge-048": "WebClient exchangeToMono return different object by response status Person",
    "spring-en-rewrite-challenge-049": "Spring distributed transaction manager JtaTransactionManager TransactionManager UserTransaction",
}


def _load_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")


def _clean(value: str) -> str:
    return SPACE_RE.sub(" ", value).strip(" ?.,;")


def _ascii_terms(value: str) -> list[str]:
    terms: list[str] = []
    for match in ASCII_TERM_RE.finditer(value):
        term = match.group(0).strip(".,;:()[]{}")
        if len(term) < 2 and not term.startswith("\\"):
            continue
        lowered = term.casefold()
        if lowered in {"and", "or", "the", "for", "with", "what", "when", "where", "how"}:
            continue
        if term not in terms:
            terms.append(term)
    return terms


def _translated_query(kr_query: str, previous_en_query: str | None) -> str:
    kr_query = _clean(kr_query)
    if not HANGUL_RE.search(kr_query):
        return kr_query
    base = _clean(previous_en_query or "")
    terms = [term for term in _ascii_terms(kr_query) if term.casefold() not in base.casefold()]
    if not base:
        base = " ".join(terms) or kr_query
    if terms:
        base = _clean(f"{base} {' '.join(terms[:12])}")
    return base


def _sync_pair(pair: DatasetPair) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    kr_rows = _load_jsonl(pair.kr_path)
    en_rows = _load_jsonl(pair.en_path)
    if len(kr_rows) != 80 or len(en_rows) != 80:
        raise RuntimeError(f"{pair.domain} row count mismatch: kr={len(kr_rows)} en={len(en_rows)}")

    now = datetime.now(timezone.utc).isoformat()
    synced: list[dict[str, Any]] = []
    changed = 0
    for index, (kr_row, en_row) in enumerate(zip(kr_rows, en_rows, strict=True), start=1):
        expected_fields = (
            "expected_doc_ids",
            "expected_chunk_ids",
            "expected_answer_key_points",
            "query_category",
            "difficulty",
            "single_or_multi_chunk",
            "source_product",
            "source_version_if_available",
            "target_method",
            "evaluation_focus",
        )
        updated = dict(en_row)
        for field in expected_fields:
            updated[field] = kr_row.get(field)
        updated["query_language"] = "en"
        updated["user_query_ko"] = ""
        updated["user_query_en"] = EN_QUERY_OVERRIDES.get(
            str(updated.get("sample_id") or ""),
            _translated_query(str(kr_row.get("user_query_ko") or ""), en_row.get("user_query_en")),
        )
        updated["metadata"] = {
            **(kr_row.get("metadata") or {}),
            "dataset_key": pair.en_key,
            "query_language": "en",
            "paired_kr_sample_id": kr_row.get("sample_id"),
            "translated_from_ko_query": kr_row.get("user_query_ko"),
            "final_sync_profile": "FINAL_en_query_sync_from_kr_query_v1",
            "final_synced_at": now,
        }
        if updated["user_query_en"] != en_row.get("user_query_en"):
            changed += 1
        synced.append(updated)

    summary = {
        "domain": pair.domain,
        "row_count": len(synced),
        "changed_query_count": changed,
        "kr_dataset_id": pair.kr_id,
        "en_dataset_id": pair.en_id,
    }
    return synced, summary


def _connect(args: argparse.Namespace) -> psycopg.Connection[Any]:
    return psycopg.connect(
        host=args.db_host,
        port=args.db_port,
        dbname=args.db_name,
        user=args.db_user,
        password=args.db_password,
        row_factory=dict_row,
        autocommit=False,
    )


def _update_db(connection: psycopg.Connection[Any], pair: DatasetPair, rows: list[dict[str, Any]], summary: dict[str, Any]) -> None:
    now = datetime.now(timezone.utc).isoformat()
    with connection.cursor() as cursor:
        for dataset_id, dataset_name in ((pair.kr_id, pair.final_kr_name), (pair.en_id, pair.final_en_name)):
            cursor.execute(
                """
                UPDATE eval_dataset
                SET dataset_name = %s,
                    version = %s,
                    metadata = COALESCE(metadata, '{}'::jsonb) || %s,
                    updated_at = NOW()
                WHERE dataset_id = %s
                """,
                (
                    dataset_name,
                    "FINAL-2026-06-02",
                    Jsonb(
                        {
                            "final_dataset_name": dataset_name,
                            "finalized_at": now,
                            "final_sync_profile": "FINAL_en_query_sync_from_kr_query_v1",
                        }
                    ),
                    dataset_id,
                ),
            )
        for row in rows:
            cursor.execute(
                """
                UPDATE eval_samples
                SET user_query_ko = %s,
                    user_query_en = %s,
                    query_language = %s,
                    difficulty = %s,
                    metadata = %s
                WHERE sample_id = %s
                """,
                (
                    row.get("user_query_ko"),
                    row.get("user_query_en"),
                    row.get("query_language"),
                    row.get("difficulty"),
                    Jsonb(row.get("metadata") or {}),
                    row["sample_id"],
                ),
            )
    summary["db_updated"] = True


def run(args: argparse.Namespace) -> list[dict[str, Any]]:
    domains = args.domains or sorted(DATASETS)
    summaries: list[dict[str, Any]] = []
    pending: list[tuple[DatasetPair, list[dict[str, Any]], dict[str, Any]]] = []
    for domain in domains:
        pair = DATASETS[domain]
        rows, summary = _sync_pair(pair)
        pending.append((pair, rows, summary))
        summaries.append(summary)
        if args.apply:
            _write_jsonl(pair.en_path, rows)
    if args.apply:
        with _connect(args) as connection:
            for pair, rows, summary in pending:
                _update_db(connection, pair, rows, summary)
            connection.commit()
    print(json.dumps(summaries, ensure_ascii=False, indent=2))
    return summaries


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Sync final EN rewrite challenge datasets from calibrated KR datasets.")
    parser.add_argument("--domains", nargs="*", choices=sorted(DATASETS))
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--db-host", default="localhost")
    parser.add_argument("--db-port", type=int, default=5432)
    parser.add_argument("--db-name", default="query_forge")
    parser.add_argument("--db-user", default="query_forge")
    parser.add_argument("--db-password", default="query_forge")
    return parser


if __name__ == "__main__":
    run(build_parser().parse_args())
