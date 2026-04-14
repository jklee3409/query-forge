from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import psycopg
from psycopg.types.json import Jsonb

try:
    from common.embeddings import embed_text, embedding_to_halfvec_literal
    from common.experiment_config import load_experiment_config
    from common.experiment_run import ExperimentRunRecorder
    from loaders.common import connect, default_database_args
except ModuleNotFoundError:  # pragma: no cover - direct module execution fallback
    from pipeline.common.embeddings import embed_text, embedding_to_halfvec_literal
    from pipeline.common.experiment_config import load_experiment_config
    from pipeline.common.experiment_run import ExperimentRunRecorder
    from pipeline.loaders.common import connect, default_database_args


LOGGER = logging.getLogger(__name__)


@dataclass(slots=True)
class GatedRow:
    gated_query_id: str
    synthetic_query_id: str
    query_text: str
    query_type: str
    generation_strategy: str
    target_chunk_ids: list[str]
    target_doc_id: str
    chunk_id_source: str
    glossary_terms: list[str]
    llm_scores: dict[str, Any]
    utility_score: float
    novelty_score: float
    final_score: float
    prompt_version: str | None
    prompt_hash: str | None
    product_name: str | None


def _latest_gating_run_id(
    connection: psycopg.Connection[Any],
    preset: str,
    strategies: list[str] | None = None,
) -> str | None:
    where_strategy = ""
    parameters: list[Any] = [preset]
    normalized = [str(item).upper() for item in (strategies or []) if str(item).strip()]
    if normalized:
        where_strategy = " AND r.generation_strategy = ANY(%s)"
        parameters.append(normalized)
    with connection.cursor() as cursor:
        cursor.execute(
            f"""
            SELECT g.metadata ->> 'experiment_run_id' AS run_id
            FROM synthetic_queries_gated g
            JOIN synthetic_queries_raw_all r ON r.synthetic_query_id = g.synthetic_query_id
            WHERE g.gating_preset = %s
              {where_strategy}
            ORDER BY g.created_at DESC
            LIMIT 1
            """,
            parameters,
        )
        row = cursor.fetchone()
    if row is None:
        return None
    run_id = row["run_id"]
    return str(run_id) if run_id else None


def _load_gated_rows(
    connection: psycopg.Connection[Any],
    *,
    preset: str,
    source_run_id: str | None,
    strategies: list[str] | None = None,
) -> list[GatedRow]:
    where_source = ""
    parameters: list[Any] = [preset]
    if source_run_id:
        where_source = " AND g.metadata ->> 'experiment_run_id' = %s"
        parameters.append(source_run_id)
    where_strategy = ""
    normalized = [str(item).upper() for item in (strategies or []) if str(item).strip()]
    if normalized:
        where_strategy = " AND r.generation_strategy = ANY(%s)"
        parameters.append(normalized)
    with connection.cursor() as cursor:
        cursor.execute(
            f"""
            SELECT g.gated_query_id,
                   g.synthetic_query_id,
                   r.query_text,
                   r.query_type,
                   r.generation_strategy,
                   r.target_chunk_ids,
                   r.target_doc_id,
                   r.chunk_id_source,
                   r.glossary_terms,
                   g.llm_scores,
                   g.utility_score,
                   g.novelty_score,
                   g.final_score,
                   r.prompt_version,
                   r.prompt_hash,
                   sc.product_name
            FROM synthetic_queries_gated g
            JOIN synthetic_queries_raw_all r ON r.synthetic_query_id = g.synthetic_query_id
            JOIN corpus_documents td ON td.document_id = r.target_doc_id
            JOIN corpus_chunks sc ON sc.chunk_id = r.chunk_id_source
            WHERE g.gating_preset = %s
              AND g.final_decision = TRUE
              {where_source}
              {where_strategy}
            ORDER BY g.created_at ASC
            """,
            parameters,
        )
        rows = cursor.fetchall()
    return [
        GatedRow(
            gated_query_id=str(row["gated_query_id"]),
            synthetic_query_id=str(row["synthetic_query_id"]),
            query_text=str(row["query_text"]),
            query_type=str(row["query_type"]),
            generation_strategy=str(row["generation_strategy"]),
            target_chunk_ids=list(row["target_chunk_ids"] or []),
            target_doc_id=str(row["target_doc_id"]),
            chunk_id_source=str(row["chunk_id_source"]),
            glossary_terms=list(row["glossary_terms"] or []),
            llm_scores=dict(row["llm_scores"] or {}),
            utility_score=float(row["utility_score"] or 0.0),
            novelty_score=float(row["novelty_score"] or 0.0),
            final_score=float(row["final_score"] or 0.0),
            prompt_version=row["prompt_version"],
            prompt_hash=row["prompt_hash"],
            product_name=row["product_name"],
        )
        for row in rows
    ]


def _parse_comparison_snapshots(raw_value: Any) -> dict[str, dict[str, str]]:
    if not isinstance(raw_value, dict):
        return {}
    snapshots: dict[str, dict[str, str]] = {}
    for preset, payload in raw_value.items():
        if not isinstance(preset, str) or not preset.strip():
            continue
        if not isinstance(payload, dict):
            continue
        source_run_id = str(payload.get("source_gating_run_id") or "").strip()
        gating_batch_id = str(payload.get("gating_batch_id") or "").strip()
        if not source_run_id:
            continue
        snapshots[preset.strip().lower()] = {
            "source_gating_run_id": source_run_id,
            "gating_batch_id": gating_batch_id,
        }
    return snapshots


def _upsert_query_embedding(
    connection: psycopg.Connection[Any],
    *,
    owner_type: str,
    owner_id: str,
    embedding_model: str,
    embedding_values: list[float],
    metadata: dict[str, Any],
) -> None:
    literal = embedding_to_halfvec_literal(embedding_values)
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO query_embeddings (
                owner_type,
                owner_id,
                embedding_model,
                embedding_dim,
                embedding,
                metadata
            ) VALUES (
                %s, %s, %s, 3072, CAST(%s AS halfvec), %s
            )
            ON CONFLICT (owner_type, owner_id, embedding_model) DO UPDATE
            SET embedding = EXCLUDED.embedding,
                metadata = EXCLUDED.metadata
            """,
            (owner_type, owner_id, embedding_model, literal, Jsonb(metadata)),
        )


def run_memory_build(
    *,
    experiment: str,
    experiment_root: Path = Path("configs/experiments"),
    database_url: str | None = None,
    db_host: str = "localhost",
    db_port: int = 5432,
    db_name: str = "query_forge",
    db_user: str = "query_forge",
    db_password: str = "query_forge",
) -> dict[str, Any]:
    config = load_experiment_config(experiment, experiment_root=experiment_root)
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
        recorder = ExperimentRunRecorder(connection)
        run_context = recorder.start_run(
            experiment_key=config.experiment_key,
            category=config.category,
            description=config.description,
            config_path=str(config.config_path),
            config_hash=config.config_hash,
            parameters={
                "stage": "build-memory",
                "gating_preset": config.gating_preset,
            },
            run_label="build-memory",
        )

        configured_strategies = (
            config.raw.get("source_generation_strategies")
            or config.raw.get("memory_generation_strategies")
            or [config.generation_strategy]
        )
        strategy_filters: list[str] = []
        for item in configured_strategies:
            normalized = str(item).upper().strip()
            if not normalized or normalized in strategy_filters:
                continue
            strategy_filters.append(normalized)
        configured_gating_run_id = str(config.raw.get("source_gating_run_id") or "").strip() or None
        comparison_snapshots = _parse_comparison_snapshots(config.raw.get("comparison_snapshots"))
        snapshot_plan: list[tuple[str, str | None, str | None]] = []
        if comparison_snapshots:
            for preset, payload in comparison_snapshots.items():
                snapshot_plan.append(
                    (
                        preset,
                        payload.get("source_gating_run_id"),
                        payload.get("gating_batch_id"),
                    )
                )
        else:
            source_run_id = configured_gating_run_id or _latest_gating_run_id(
                connection,
                config.gating_preset,
                strategy_filters,
            )
            snapshot_plan.append((config.gating_preset, source_run_id, str(config.raw.get("source_gating_batch_id") or "").strip() or None))

        inserted_memory_ids: list[str] = []
        built_by_snapshot: dict[str, int] = {}
        total_rows = 0
        primary_source_run_id: str | None = None
        for preset, source_run_id, source_batch_id in snapshot_plan:
            if primary_source_run_id is None and source_run_id:
                primary_source_run_id = source_run_id
            rows = _load_gated_rows(
                connection,
                preset=preset,
                source_run_id=source_run_id,
                strategies=strategy_filters,
            )
            built_by_snapshot[preset] = len(rows)
            total_rows += len(rows)
            for row in rows:
                memory_id = str(uuid.uuid4())
                embedding = embed_text(row.query_text)
                embedding_literal = embedding_to_halfvec_literal(embedding)
                with connection.cursor() as cursor:
                    cursor.execute(
                        """
                        DELETE FROM memory_entries
                        WHERE source_gated_query_id = %s
                        """,
                        (row.gated_query_id,),
                    )
                    cursor.execute(
                        """
                        INSERT INTO memory_entries (
                            memory_id,
                            source_gated_query_id,
                            query_text,
                            query_type,
                            generation_strategy,
                            target_chunk_ids,
                            target_doc_id,
                            chunk_id_source,
                            product,
                            glossary_terms,
                            llm_scores,
                            utility_score,
                            novelty_score,
                            final_score,
                            prompt_version,
                            prompt_hash,
                            query_embedding,
                            metadata
                        ) VALUES (
                            %s, %s, %s, %s, %s, %s, %s, %s, %s,
                            %s, %s, %s, %s, %s, %s, %s, CAST(%s AS halfvec), %s
                        )
                        """,
                        (
                            memory_id,
                            row.gated_query_id,
                            row.query_text,
                            row.query_type,
                            row.generation_strategy,
                            Jsonb(row.target_chunk_ids),
                            row.target_doc_id,
                            row.chunk_id_source,
                            row.product_name,
                            Jsonb(row.glossary_terms),
                            Jsonb(row.llm_scores),
                            row.utility_score,
                            row.novelty_score,
                            row.final_score,
                            row.prompt_version,
                            row.prompt_hash,
                            embedding_literal,
                            Jsonb(
                                {
                                    "gating_preset": preset,
                                    "source_gate_run_id": source_run_id,
                                    "source_gating_batch_id": source_batch_id,
                                    "memory_build_run_id": run_context.experiment_run_id,
                                }
                            ),
                        ),
                    )

                _upsert_query_embedding(
                    connection,
                    owner_type="memory",
                    owner_id=memory_id,
                    embedding_model="hash-embedding-v1",
                    embedding_values=embedding,
                    metadata={"source_gated_query_id": row.gated_query_id},
                )
                _upsert_query_embedding(
                    connection,
                    owner_type="synthetic_gated",
                    owner_id=row.gated_query_id,
                    embedding_model="hash-embedding-v1",
                    embedding_values=embedding,
                    metadata={"synthetic_query_id": row.synthetic_query_id},
                )
                _upsert_query_embedding(
                    connection,
                    owner_type="synthetic_raw",
                    owner_id=row.synthetic_query_id,
                    embedding_model="hash-embedding-v1",
                    embedding_values=embedding,
                    metadata={"gated_query_id": row.gated_query_id},
                )
                if len(inserted_memory_ids) < 20:
                    inserted_memory_ids.append(memory_id)

        summary = {
            "experiment_key": config.experiment_key,
            "experiment_run_id": run_context.experiment_run_id,
            "gating_preset": config.gating_preset,
            "source_gating_run_id": primary_source_run_id,
            "source_generation_strategies": strategy_filters,
            "memory_entries_built": total_rows,
            "memory_entries_by_snapshot": built_by_snapshot,
            "preview_memory_ids": inserted_memory_ids,
            "embedding_model": "hash-embedding-v1",
        }
        recorder.finish_run(run_context, status="completed", metrics=summary)
        connection.commit()
        return summary
    except Exception as exception:  # noqa: BLE001
        connection.rollback()
        LOGGER.exception("Memory build failed.")
        raise exception
    finally:
        connection.close()


def run_memory_build_from_env(experiment: str) -> dict[str, Any]:
    defaults = default_database_args()
    return run_memory_build(
        experiment=experiment,
        database_url=defaults["database_url"],
        db_host=defaults["db_host"],
        db_port=defaults["db_port"],
        db_name=defaults["db_name"],
        db_user=defaults["db_user"],
        db_password=defaults["db_password"],
    )
