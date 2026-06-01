from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import psycopg
from psycopg.rows import dict_row


REPO_ROOT = Path(__file__).resolve().parents[1]
if str(REPO_ROOT / "pipeline") not in sys.path:
    sys.path.insert(0, str(REPO_ROOT / "pipeline"))

from common.local_retriever import build_retriever_config  # noqa: E402
from eval.runtime import (  # noqa: E402
    DbAnnRuntimeRetrievalAdapter,
    EvalSample,
    derive_eval_corpus_scope,
    load_eval_samples,
    retrieval_metrics,
)


@dataclass(frozen=True, slots=True)
class SnapshotSpec:
    strategy: str
    gating_batch_id: str
    source_gating_run_id: str


@dataclass(frozen=True, slots=True)
class DatasetSpec:
    domain: str
    language: str
    dataset_key: str
    dataset_id: str
    path: Path
    snapshots: dict[str, SnapshotSpec]


SPRING_SNAPSHOTS = {
    "A": SnapshotSpec("A", "b45a1b9e-c135-4252-9aa2-ecb130c496cd", "60761faf-d7ea-48da-bbd5-e2a88c370b0e"),
    "C": SnapshotSpec("C", "73b5bfc1-73b5-4cfe-ab64-daf94729578b", "135d3403-7db5-4643-a31b-19eab9933e67"),
}
POSTGRESQL_SNAPSHOTS = {
    "A": SnapshotSpec("A", "1c80af8d-b993-4b88-8013-3fe7cf995bef", "a7b94d78-1798-4949-9594-921841931a96"),
    "C": SnapshotSpec("C", "3306f0cc-25c5-459f-b3dc-0e894e76e806", "f29a870d-2c65-4a77-9bb5-002f21caeff7"),
}
KUBERNETES_SNAPSHOTS = {
    "A": SnapshotSpec("A", "7793c399-5eea-45ca-befc-29d4f766ca9b", "8de303b9-4c11-495b-a8e6-2ec5d18cd1ff"),
    "C": SnapshotSpec("C", "d906f6ba-cd2d-44d3-85c8-05adb8a04824", "53dde3db-fca2-4c2e-a67c-0db827c22493"),
}


DATASETS = {
    "spring_kr": DatasetSpec(
        "spring",
        "ko",
        "spring_kr_rewrite_challenge_80",
        "57f313dd-461d-561d-9453-0f8e2e179b27",
        REPO_ROOT / "data" / "eval" / "spring_kr_rewrite_challenge_80.jsonl",
        SPRING_SNAPSHOTS,
    ),
    "spring_en": DatasetSpec(
        "spring",
        "en",
        "spring_en_rewrite_challenge_80",
        "0d95322e-69e2-5dc9-93a8-5cf857a1db78",
        REPO_ROOT / "data" / "eval" / "spring_en_rewrite_challenge_80.jsonl",
        SPRING_SNAPSHOTS,
    ),
    "postgresql_kr": DatasetSpec(
        "postgresql",
        "ko",
        "postgresql_kr_rewrite_challenge_80",
        "0a8a0077-7f63-5f6d-b19d-71ae3f137733",
        REPO_ROOT / "data" / "eval" / "postgresql_kr_rewrite_challenge_80.jsonl",
        POSTGRESQL_SNAPSHOTS,
    ),
    "postgresql_en": DatasetSpec(
        "postgresql",
        "en",
        "postgresql_en_rewrite_challenge_80",
        "96c55e78-3288-5c1e-9e98-1471495da8cc",
        REPO_ROOT / "data" / "eval" / "postgresql_en_rewrite_challenge_80.jsonl",
        POSTGRESQL_SNAPSHOTS,
    ),
    "kubernetes_kr": DatasetSpec(
        "kubernetes",
        "ko",
        "kubernetes_kr_rewrite_challenge_80",
        "c61421b4-6154-563a-b71a-fdef5f254b6e",
        REPO_ROOT / "data" / "eval" / "kubernetes_kr_rewrite_challenge_80.jsonl",
        KUBERNETES_SNAPSHOTS,
    ),
    "kubernetes_en": DatasetSpec(
        "kubernetes",
        "en",
        "kubernetes_en_rewrite_challenge_80",
        "d4ef4bd3-2eb0-5178-b794-f69f921de541",
        REPO_ROOT / "data" / "eval" / "kubernetes_en_rewrite_challenge_80.jsonl",
        KUBERNETES_SNAPSHOTS,
    ),
}


ASCII_TOKEN_RE = re.compile(r"\b[A-Za-z][A-Za-z0-9_.$#:@/-]*\b")
PAREN_WITH_ASCII_RE = re.compile(r"\([^)]*[A-Za-z][^)]*\)")
SPACE_RE = re.compile(r"\s+")


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


def _retriever_config() -> Any:
    return build_retriever_config(
        {
            "retriever_mode": "hybrid",
            "dense_embedding_model": "intfloat/multilingual-e5-small",
            "dense_embedding_required": True,
            "dense_fallback_enabled": False,
            "dense_embedding_device": "cpu",
            "dense_embedding_batch_size": 32,
            "rerank_enabled": False,
            "retriever_candidate_pool_k": 50,
            "candidate_pool_k": 50,
            "retriever_fusion_weights": {"dense": 0.6, "bm25": 0.32, "technical": 0.08},
        }
    )


def _load_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _sample_rows_by_id(path: Path) -> dict[str, dict[str, Any]]:
    return {str(row["sample_id"]): row for row in _load_jsonl(path)}


def _clean_text(value: str) -> str:
    return SPACE_RE.sub(" ", value).strip(" ?.,;/")


def _strip_ascii_anchors(value: str) -> str:
    without_parens = PAREN_WITH_ASCII_RE.sub(" ", value)
    without_ascii = ASCII_TOKEN_RE.sub(" ", without_parens)
    return _clean_text(without_ascii)


def _first_rank(sample: EvalSample, retrieved: list[Any]) -> int | None:
    expected_chunks = {str(chunk_id) for chunk_id in sample.expected_chunk_ids if str(chunk_id).strip()}
    expected_docs = {str(doc_id) for doc_id in sample.expected_doc_ids if str(doc_id).strip()}
    for index, candidate in enumerate(retrieved, start=1):
        if candidate.chunk_id in expected_chunks:
            return index
        if not expected_chunks and candidate.document_id in expected_docs:
            return index
    return None


def _fetch_chunk_hints(connection: psycopg.Connection[Any], samples: list[EvalSample]) -> dict[str, dict[str, str]]:
    chunk_ids = sorted({chunk_id for sample in samples for chunk_id in sample.expected_chunk_ids})
    if not chunk_ids:
        return {}
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT chunk_id,
                   document_id,
                   product_name,
                   section_path_text,
                   LEFT(chunk_text, 360) AS chunk_preview
            FROM corpus_chunks
            WHERE chunk_id = ANY(%s)
            """,
            (chunk_ids,),
        )
        rows = cursor.fetchall()
    return {
        str(row["chunk_id"]): {
            "document_id": str(row["document_id"] or ""),
            "product_name": str(row["product_name"] or ""),
            "section_path_text": str(row["section_path_text"] or ""),
            "chunk_preview": str(row["chunk_preview"] or ""),
        }
        for row in rows
    }


def _fetch_synthetic_queries(
    connection: psycopg.Connection[Any],
    *,
    snapshot: SnapshotSpec,
    samples: list[EvalSample],
) -> dict[str, list[dict[str, Any]]]:
    chunk_ids = sorted({chunk_id for sample in samples for chunk_id in sample.expected_chunk_ids})
    if not chunk_ids:
        return {}
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT r.chunk_id_source,
                   r.target_chunk_ids,
                   r.query_text,
                   r.query_language,
                   r.language_profile,
                   r.glossary_terms,
                   gr.utility_score,
                   gr.final_score
            FROM synthetic_query_gating_result gr
            JOIN synthetic_queries_raw_all r
              ON r.synthetic_query_id = gr.synthetic_query_id
            WHERE gr.gating_batch_id = %s
              AND COALESCE(gr.accepted, FALSE)
              AND r.generation_strategy = %s
              AND (
                    r.chunk_id_source = ANY(%s)
                    OR r.target_chunk_ids ?| %s
              )
            ORDER BY COALESCE(gr.final_score, 0) DESC,
                     COALESCE(gr.utility_score, 0) DESC,
                     r.created_at ASC
            """,
            (snapshot.gating_batch_id, snapshot.strategy, chunk_ids, chunk_ids),
        )
        rows = cursor.fetchall()

    by_chunk: dict[str, list[dict[str, Any]]] = {chunk_id: [] for chunk_id in chunk_ids}
    for row in rows:
        targets = [str(item) for item in (row["target_chunk_ids"] or []) if str(item).strip()]
        source_chunk_id = str(row["chunk_id_source"] or "")
        for chunk_id in set(targets + [source_chunk_id]):
            if chunk_id in by_chunk:
                by_chunk[chunk_id].append(
                    {
                        "query_text": str(row["query_text"] or ""),
                        "query_language": str(row["query_language"] or ""),
                        "language_profile": str(row["language_profile"] or ""),
                        "glossary_terms": list(row["glossary_terms"] or []),
                        "utility_score": float(row["utility_score"] or 0.0),
                        "final_score": float(row["final_score"] or 0.0),
                    }
                )
    return by_chunk


def _best_query(rows: list[dict[str, Any]], *, prefer_code_mixed: bool) -> str:
    if not rows:
        return ""
    preferred = [
        row for row in rows if (str(row.get("language_profile") or "") == "code_mixed") == prefer_code_mixed
    ]
    source = preferred or rows
    return str(source[0].get("query_text") or "").strip()


def _best_glossary(rows: list[dict[str, Any]]) -> list[str]:
    terms: list[str] = []
    for row in rows:
        for term in row.get("glossary_terms") or []:
            text = str(term or "").strip()
            if text and text not in terms:
                terms.append(text)
    return terms[:6]


def _variant_queries(
    *,
    sample: EvalSample,
    row: dict[str, Any] | None,
    chunk_hints: dict[str, dict[str, str]],
    synthetic: dict[str, dict[str, list[dict[str, Any]]]],
) -> dict[str, str]:
    current = sample.query_text.strip()
    first_chunk = sample.expected_chunk_ids[0] if sample.expected_chunk_ids else ""
    hints = chunk_hints.get(first_chunk, {})
    section = _clean_text(hints.get("section_path_text", ""))
    product = _clean_text(hints.get("product_name", ""))

    metadata = row.get("metadata") if isinstance(row, dict) and isinstance(row.get("metadata"), dict) else {}
    paired_ko = str(metadata.get("paired_user_query_ko") or "").strip()
    source_ko = str(metadata.get("source_user_query_ko") or "").strip()

    variants: dict[str, str] = {
        "current": current,
        "section": section,
        "product_section": _clean_text(f"{product} {section}"),
        "current_section": _clean_text(f"{current} {section}"),
    }
    if paired_ko:
        variants["paired_ko"] = paired_ko
    if source_ko:
        variants["source_ko"] = source_ko

    for strategy in ("A", "C"):
        rows = synthetic.get(strategy, {}).get(first_chunk, [])
        natural = _best_query(rows, prefer_code_mixed=False)
        code_mixed = _best_query(rows, prefer_code_mixed=True)
        glossary = " ".join(_best_glossary(rows)[:3])
        for label, text in ((f"memory_{strategy.lower()}_ko", natural), (f"memory_{strategy.lower()}_code", code_mixed)):
            if text:
                variants[label] = text
                stripped = _strip_ascii_anchors(text)
                if stripped:
                    variants[f"{label}_anchorless"] = stripped
                    variants[f"current_{label}_anchorless"] = _clean_text(f"{current} {stripped}")
        if glossary:
            variants[f"glossary_{strategy.lower()}"] = glossary
            variants[f"current_glossary_{strategy.lower()}"] = _clean_text(f"{current} {glossary}")

    return {key: value for key, value in variants.items() if value}


def _score_variant_matrix(
    connection: psycopg.Connection[Any],
    spec: DatasetSpec,
    *,
    variant_filter: set[str] | None,
) -> dict[str, Any]:
    samples = load_eval_samples(connection, dataset_id=spec.dataset_id, query_language=spec.language)
    rows_by_id = _sample_rows_by_id(spec.path)
    scope = derive_eval_corpus_scope(samples)
    adapter = DbAnnRuntimeRetrievalAdapter(
        connection,
        allowed_products=sorted(scope["product_filters"]),
        include_document_ids=sorted(scope["expected_doc_ids"]),
        memory_experiment_key=None,
        retriever_config=_retriever_config(),
    )
    chunk_hints = _fetch_chunk_hints(connection, samples)
    synthetic = {
        strategy: _fetch_synthetic_queries(connection, snapshot=snapshot, samples=samples)
        for strategy, snapshot in spec.snapshots.items()
    }

    variants_by_sample = {
        sample.sample_id: _variant_queries(
            sample=sample,
            row=rows_by_id.get(sample.sample_id),
            chunk_hints=chunk_hints,
            synthetic=synthetic,
        )
        for sample in samples
    }
    all_variants = sorted({variant for variants in variants_by_sample.values() for variant in variants})
    selected_variants = sorted(variant_filter or set(all_variants))
    summaries: dict[str, dict[str, Any]] = {}
    sample_rows: list[dict[str, Any]] = []

    for variant in selected_variants:
        hit_count = 0
        missing_variant_count = 0
        ranks: list[int] = []
        for sample in samples:
            query = variants_by_sample[sample.sample_id].get(variant, "")
            if not query:
                missing_variant_count += 1
                continue
            retrieved = adapter.retrieve_top_k(query, top_k=10)
            metrics = retrieval_metrics(
                expected_chunk_ids=sample.expected_chunk_ids,
                expected_doc_ids=sample.expected_doc_ids,
                retrieved=retrieved,
            )
            hit = int(metrics["hit@5"] > 0)
            hit_count += hit
            rank = _first_rank(sample, retrieved)
            if rank is not None:
                ranks.append(rank)
            sample_rows.append(
                {
                    "dataset_key": spec.dataset_key,
                    "sample_id": sample.sample_id,
                    "variant": variant,
                    "hit@5": hit,
                    "first_rank": rank,
                    "query": query,
                    "expected_chunk_ids": sample.expected_chunk_ids,
                    "top5_chunk_ids": [item.chunk_id for item in retrieved[:5]],
                }
            )
        summaries[variant] = {
            "hit_count": hit_count,
            "hit_rate": hit_count / max(1, len(samples)),
            "missing_variant_count": missing_variant_count,
            "ranked_count": len(ranks),
            "mean_rank_when_found": sum(ranks) / len(ranks) if ranks else None,
        }

    return {
        "dataset_key": spec.dataset_key,
        "dataset_id": spec.dataset_id,
        "domain": spec.domain,
        "language": spec.language,
        "sample_count": len(samples),
        "summaries": summaries,
        "samples": sample_rows,
    }


def run_score(args: argparse.Namespace) -> dict[str, Any]:
    selected = args.dataset or sorted(DATASETS)
    unknown = sorted(set(selected) - set(DATASETS))
    if unknown:
        raise RuntimeError(f"Unknown dataset aliases: {', '.join(unknown)}")
    variant_filter = set(args.variant or []) or None
    with _connect(args) as connection:
        results = [
            _score_variant_matrix(connection, DATASETS[alias], variant_filter=variant_filter)
            for alias in selected
        ]
    payload = {"datasets": results}
    if args.output:
        output_path = Path(args.output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return payload


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Probe rewrite challenge retrieval behavior.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    score = subparsers.add_parser("score-variants")
    score.add_argument("--dataset", action="append", choices=sorted(DATASETS))
    score.add_argument("--variant", action="append")
    score.add_argument("--output", default="data/reports/rewrite_challenge_variant_probe.json")
    score.add_argument("--db-host", default="localhost")
    score.add_argument("--db-port", type=int, default=5432)
    score.add_argument("--db-name", default="query_forge")
    score.add_argument("--db-user", default="query_forge")
    score.add_argument("--db-password", default="query_forge")
    score.set_defaults(func=run_score)
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    payload = args.func(args)
    for dataset in payload["datasets"]:
        print(f"{dataset['dataset_key']} ({dataset['language']})")
        for variant, summary in sorted(dataset["summaries"].items()):
            print(
                f"  {variant}: hit@5={summary['hit_count']}/{dataset['sample_count']} "
                f"missing_variant={summary['missing_variant_count']}"
            )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
