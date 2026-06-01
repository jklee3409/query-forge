from __future__ import annotations

import argparse
import json
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb


REPO_ROOT = Path(__file__).resolve().parents[1]


@dataclass(frozen=True, slots=True)
class DatasetSpec:
    alias: str
    dataset_key: str
    dataset_id: str
    language: str
    path: Path


DATASETS = {
    "spring_kr": DatasetSpec(
        "spring_kr",
        "spring_kr_rewrite_challenge_80",
        "57f313dd-461d-561d-9453-0f8e2e179b27",
        "ko",
        REPO_ROOT / "data" / "eval" / "spring_kr_rewrite_challenge_80.jsonl",
    ),
    "spring_en": DatasetSpec(
        "spring_en",
        "spring_en_rewrite_challenge_80",
        "0d95322e-69e2-5dc9-93a8-5cf857a1db78",
        "en",
        REPO_ROOT / "data" / "eval" / "spring_en_rewrite_challenge_80.jsonl",
    ),
    "postgresql_kr": DatasetSpec(
        "postgresql_kr",
        "postgresql_kr_rewrite_challenge_80",
        "0a8a0077-7f63-5f6d-b19d-71ae3f137733",
        "ko",
        REPO_ROOT / "data" / "eval" / "postgresql_kr_rewrite_challenge_80.jsonl",
    ),
    "postgresql_en": DatasetSpec(
        "postgresql_en",
        "postgresql_en_rewrite_challenge_80",
        "96c55e78-3288-5c1e-9e98-1471495da8cc",
        "en",
        REPO_ROOT / "data" / "eval" / "postgresql_en_rewrite_challenge_80.jsonl",
    ),
    "kubernetes_kr": DatasetSpec(
        "kubernetes_kr",
        "kubernetes_kr_rewrite_challenge_80",
        "c61421b4-6154-563a-b71a-fdef5f254b6e",
        "ko",
        REPO_ROOT / "data" / "eval" / "kubernetes_kr_rewrite_challenge_80.jsonl",
    ),
    "kubernetes_en": DatasetSpec(
        "kubernetes_en",
        "kubernetes_en_rewrite_challenge_80",
        "d4ef4bd3-2eb0-5178-b794-f69f921de541",
        "en",
        REPO_ROOT / "data" / "eval" / "kubernetes_en_rewrite_challenge_80.jsonl",
    ),
}


def _load_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def _write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")


def _load_probe(path: Path, dataset_key: str) -> dict[str, dict[str, dict[str, Any]]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    dataset = None
    for candidate in payload.get("datasets", []):
        if candidate.get("dataset_key") == dataset_key:
            dataset = candidate
            break
    if not dataset:
        raise RuntimeError(f"Dataset {dataset_key} not found in probe report: {path}")
    matrix: dict[str, dict[str, dict[str, Any]]] = {}
    for row in dataset.get("samples", []):
        sample_id = str(row["sample_id"])
        variant = str(row["variant"])
        matrix.setdefault(sample_id, {})[variant] = row
    return matrix


def _load_memory_probe(path: Path, dataset_key: str) -> dict[str, dict[str, dict[str, Any]]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if payload.get("dataset_key") != dataset_key:
        raise RuntimeError(f"Dataset {dataset_key} not found in memory probe report: {path}")
    matrix: dict[str, dict[str, dict[str, Any]]] = {}
    for row in payload.get("rows", []):
        sample_id = str(row["sample_id"])
        variant = str(row["variant"])
        matrix.setdefault(sample_id, {})[variant] = {
            "sample_id": sample_id,
            "variant": variant,
            "query": row.get("query"),
            "hit@5": int(row.get("raw_hit@5") == 1),
            "first_rank": row.get("raw_first_rank"),
            "raw_found@10": int(row.get("raw_found@10") == 1),
            "trusted_target_in_top_n": bool(row.get("trusted_target_in_top_n")),
        }
    return matrix


def _variant_sort_key(row: dict[str, Any], priorities: list[str]) -> tuple[int, int, str]:
    variant = str(row.get("variant") or "")
    try:
        priority = priorities.index(variant)
    except ValueError:
        priority = len(priorities) + 1
    return priority, len(str(row.get("query") or "")), variant


def _select_memory_probe_variants(
    *,
    rows: list[dict[str, Any]],
    matrix: dict[str, dict[str, dict[str, Any]]],
    target_hit_count: int,
    hard_variant: str,
    priorities: list[str],
) -> dict[str, str]:
    selected: dict[str, str] = {}
    for row in rows:
        sample_id = str(row["sample_id"])
        variants = matrix.get(sample_id, {})
        rawmiss_trusted = [
            variant_row
            for variant_row in variants.values()
            if int(variant_row.get("hit@5") == 1) == 0 and bool(variant_row.get("trusted_target_in_top_n"))
        ]
        if rawmiss_trusted:
            rawmiss_trusted.sort(key=lambda item: _variant_sort_key(item, priorities))
            selected[sample_id] = str(rawmiss_trusted[0]["variant"])

    current_hit_count = sum(
        int(matrix.get(str(row["sample_id"]), {}).get(selected.get(str(row["sample_id"]), ""), {}).get("hit@5") == 1)
        for row in rows
    )
    rawhit_fill_candidates: list[tuple[bool, str, dict[str, Any]]] = []
    for row in rows:
        sample_id = str(row["sample_id"])
        if sample_id in selected:
            continue
        variants = matrix.get(sample_id, {})
        rawhit_variants = [variant_row for variant_row in variants.values() if int(variant_row.get("hit@5") == 1) == 1]
        if not rawhit_variants:
            continue
        rawmiss_available = any(int(variant_row.get("hit@5") == 1) == 0 for variant_row in variants.values())
        rawhit_variants.sort(key=lambda item: _variant_sort_key(item, priorities))
        rawhit_fill_candidates.append((rawmiss_available, sample_id, rawhit_variants[0]))
    rawhit_fill_candidates.sort(key=lambda item: (item[0], item[1]))
    for _, sample_id, variant_row in rawhit_fill_candidates:
        if current_hit_count >= target_hit_count:
            break
        selected[sample_id] = str(variant_row["variant"])
        current_hit_count += 1

    for row in rows:
        sample_id = str(row["sample_id"])
        if sample_id in selected:
            continue
        variants = matrix.get(sample_id, {})
        if current_hit_count >= target_hit_count:
            rawmiss_variants = [
                variant_row
                for variant_row in variants.values()
                if int(variant_row.get("hit@5") == 1) == 0 and str(variant_row.get("query") or "").strip()
            ]
            if rawmiss_variants:
                rawmiss_variants.sort(key=lambda item: _variant_sort_key(item, priorities))
                selected[sample_id] = str(rawmiss_variants[0]["variant"])
            elif hard_variant in variants:
                selected[sample_id] = hard_variant
            elif variants:
                fallback = sorted(variants.values(), key=lambda item: _variant_sort_key(item, priorities))[0]
                selected[sample_id] = str(fallback["variant"])
            else:
                raise RuntimeError(f"No variants available for sample_id={sample_id}")
        elif hard_variant in variants:
            selected[sample_id] = hard_variant
            current_hit_count += int(variants[hard_variant].get("hit@5") == 1)
        elif variants:
            fallback = sorted(variants.values(), key=lambda item: _variant_sort_key(item, priorities))[0]
            selected[sample_id] = str(fallback["variant"])
            current_hit_count += int(fallback.get("hit@5") == 1)
        else:
            raise RuntimeError(f"No variants available for sample_id={sample_id}")

    return selected


def _select_variants(
    *,
    rows: list[dict[str, Any]],
    matrix: dict[str, dict[str, dict[str, Any]]],
    base_variant: str,
    easy_variant: str,
    hard_variant: str,
    target_hit_count: int,
) -> dict[str, str]:
    selected: dict[str, str] = {}
    for row in rows:
        sample_id = str(row["sample_id"])
        variants = matrix.get(sample_id, {})
        selected[sample_id] = base_variant if base_variant in variants else hard_variant

    def hit_count() -> int:
        total = 0
        for row in rows:
            sample_id = str(row["sample_id"])
            variant = selected[sample_id]
            total += int(matrix.get(sample_id, {}).get(variant, {}).get("hit@5") == 1)
        return total

    current = hit_count()
    if current < target_hit_count:
        for row in rows:
            sample_id = str(row["sample_id"])
            variants = matrix.get(sample_id, {})
            if selected[sample_id] == easy_variant:
                continue
            selected_hit = int(variants.get(selected[sample_id], {}).get("hit@5") == 1)
            easy_hit = int(variants.get(easy_variant, {}).get("hit@5") == 1)
            if selected_hit == 0 and easy_hit == 1:
                selected[sample_id] = easy_variant
                current += 1
                if current >= target_hit_count:
                    break
    elif current > target_hit_count:
        for row in rows:
            sample_id = str(row["sample_id"])
            variants = matrix.get(sample_id, {})
            selected_hit = int(variants.get(selected[sample_id], {}).get("hit@5") == 1)
            hard_hit = int(variants.get(hard_variant, {}).get("hit@5") == 1)
            if selected_hit == 1 and hard_hit == 0:
                selected[sample_id] = hard_variant
                current -= 1
                if current <= target_hit_count:
                    break
    return selected


def _apply_to_rows(
    *,
    rows: list[dict[str, Any]],
    matrix: dict[str, dict[str, dict[str, Any]]],
    selected: dict[str, str],
    spec: DatasetSpec,
    profile: str,
    target_hit_count: int,
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    now = datetime.now(timezone.utc).isoformat()
    updated_rows: list[dict[str, Any]] = []
    before_grounding = {
        str(row["sample_id"]): (
            row.get("expected_doc_ids"),
            row.get("expected_chunk_ids"),
            row.get("expected_answer_key_points"),
        )
        for row in rows
    }
    for row in rows:
        sample_id = str(row["sample_id"])
        variant = selected[sample_id]
        variant_row = matrix.get(sample_id, {}).get(variant)
        if not variant_row or not str(variant_row.get("query") or "").strip():
            raise RuntimeError(f"Missing selected query: sample_id={sample_id}, variant={variant}")

        updated = dict(row)
        metadata = dict(updated.get("metadata") or {})
        previous_query = updated.get("user_query_en") if spec.language == "en" else updated.get("user_query_ko")
        query = str(variant_row["query"]).strip()
        if spec.language == "en":
            updated["user_query_en"] = query
            updated["user_query_ko"] = ""
            updated["query_language"] = "en"
        else:
            updated["user_query_ko"] = query
            updated["user_query_en"] = None
            updated["query_language"] = "ko"
        metadata.update(
            {
                "calibrated_at": now,
                "calibration_profile": profile,
                "calibration_variant": variant,
                "calibration_raw_target_hit_count": target_hit_count,
                "calibration_previous_query": previous_query,
                "calibration_probe_hit_at_5": int(variant_row.get("hit@5") == 1),
                "calibration_probe_first_rank": variant_row.get("first_rank"),
                "query_surface_policy": (
                    "calibrated rewrite challenge surface; grounding preserved; raw hit@5 target 35-40/80"
                ),
            }
        )
        updated["metadata"] = metadata
        updated["difficulty"] = "calibrated_raw_35_40"
        updated_rows.append(updated)

    for row in updated_rows:
        sample_id = str(row["sample_id"])
        grounding = (
            row.get("expected_doc_ids"),
            row.get("expected_chunk_ids"),
            row.get("expected_answer_key_points"),
        )
        if grounding != before_grounding[sample_id]:
            raise RuntimeError(f"Grounding changed unexpectedly: {sample_id}")

    variant_counts = Counter(selected.values())
    probe_hit_count = sum(
        int(matrix[str(row["sample_id"])][selected[str(row["sample_id"])]].get("hit@5") == 1)
        for row in updated_rows
    )
    return updated_rows, {
        "dataset_key": spec.dataset_key,
        "dataset_id": spec.dataset_id,
        "language": spec.language,
        "row_count": len(updated_rows),
        "profile": profile,
        "target_hit_count": target_hit_count,
        "probe_selected_hit_count": probe_hit_count,
        "variant_counts": dict(variant_counts),
    }


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


def _update_db(
    connection: psycopg.Connection[Any],
    *,
    spec: DatasetSpec,
    rows: list[dict[str, Any]],
    summary: dict[str, Any],
) -> None:
    now = datetime.now(timezone.utc).isoformat()
    with connection.cursor() as cursor:
        cursor.execute(
            """
            UPDATE eval_dataset
            SET metadata = COALESCE(metadata, '{}'::jsonb) || %s,
                version = %s,
                updated_at = NOW()
            WHERE dataset_id = %s
            """,
            (
                Jsonb(
                    {
                        "calibrated_at": now,
                        "calibration_profile": summary["profile"],
                        "calibration_raw_target_hit_count": summary["target_hit_count"],
                        "calibration_probe_selected_hit_count": summary["probe_selected_hit_count"],
                        "calibration_variant_counts": summary["variant_counts"],
                    }
                ),
                "v2-2026-06-01-calibrated",
                spec.dataset_id,
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


def run(args: argparse.Namespace) -> dict[str, Any]:
    spec = DATASETS[args.dataset]
    rows = _load_jsonl(spec.path)
    if len(rows) != 80:
        raise RuntimeError(f"{spec.dataset_key} row count mismatch: {len(rows)}")
    if args.memory_probe:
        matrix = _load_memory_probe(Path(args.memory_probe), spec.dataset_key)
        priorities = [item for item in args.priority_variants if str(item).strip()]
        selected = _select_memory_probe_variants(
            rows=rows,
            matrix=matrix,
            target_hit_count=args.target_hit_count,
            hard_variant=args.hard_variant,
            priorities=priorities,
        )
    else:
        matrix = _load_probe(Path(args.probe), spec.dataset_key)
        selected = _select_variants(
            rows=rows,
            matrix=matrix,
            base_variant=args.base_variant,
            easy_variant=args.easy_variant,
            hard_variant=args.hard_variant,
            target_hit_count=args.target_hit_count,
        )
    updated_rows, summary = _apply_to_rows(
        rows=rows,
        matrix=matrix,
        selected=selected,
        spec=spec,
        profile=args.profile,
        target_hit_count=args.target_hit_count,
    )
    summary["selected_rawmiss_trusted_target_count"] = sum(
        int(
            matrix[str(row["sample_id"])][selected[str(row["sample_id"])]].get("hit@5") != 1
            and matrix[str(row["sample_id"])][selected[str(row["sample_id"])]].get("trusted_target_in_top_n") is True
        )
        for row in updated_rows
    )
    if args.apply:
        _write_jsonl(spec.path, updated_rows)
        with _connect(args) as connection:
            _update_db(connection, spec=spec, rows=updated_rows, summary=summary)
            connection.commit()
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return summary


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Apply calibrated query surfaces from a rewrite challenge probe report.")
    parser.add_argument("--dataset", required=True, choices=sorted(DATASETS))
    parser.add_argument("--probe", default=None)
    parser.add_argument("--memory-probe", default=None)
    parser.add_argument("--base-variant", default=None)
    parser.add_argument("--easy-variant", default="section")
    parser.add_argument("--hard-variant", default="current")
    parser.add_argument("--target-hit-count", type=int, default=36)
    parser.add_argument("--profile", default="raw36_memory_anchorless_c")
    parser.add_argument(
        "--priority-variants",
        nargs="*",
        default=[
            "current",
            "memory_a_ko_anchorless",
            "memory_c_ko_anchorless",
            "current_memory_a_ko_anchorless",
            "current_memory_c_ko_anchorless",
            "current_memory_a_code_anchorless",
            "current_memory_c_code_anchorless",
            "current_glossary_a",
            "current_glossary_c",
        ],
    )
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--db-host", default="localhost")
    parser.add_argument("--db-port", type=int, default=5432)
    parser.add_argument("--db-name", default="query_forge")
    parser.add_argument("--db-user", default="query_forge")
    parser.add_argument("--db-password", default="query_forge")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    if bool(args.memory_probe) == bool(args.probe):
        raise SystemExit("Provide exactly one of --probe or --memory-probe")
    if args.probe and not args.base_variant:
        raise SystemExit("--base-variant is required with --probe")
    run(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
