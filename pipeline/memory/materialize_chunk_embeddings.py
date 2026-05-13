from __future__ import annotations

import logging
from dataclasses import replace
from pathlib import Path
from typing import Any

import psycopg
from psycopg.types.json import Jsonb

try:
    from common.embeddings import embedding_to_halfvec_literal
    from common.experiment_config import load_experiment_config
    from common.experiment_run import ExperimentRunRecorder
    from common.local_retriever import (
        RETRIEVAL_MODE_DENSE_ONLY,
        build_retriever_config,
        encode_passages_with_retriever_config,
    )
    from loaders.common import connect, default_database_args
except ModuleNotFoundError:  # pragma: no cover - direct module execution fallback
    from pipeline.common.embeddings import embedding_to_halfvec_literal
    from pipeline.common.experiment_config import load_experiment_config
    from pipeline.common.experiment_run import ExperimentRunRecorder
    from pipeline.common.local_retriever import (
        RETRIEVAL_MODE_DENSE_ONLY,
        build_retriever_config,
        encode_passages_with_retriever_config,
    )
    from pipeline.loaders.common import connect, default_database_args


LOGGER = logging.getLogger(__name__)
DEFAULT_BATCH_SIZE = 64


def _count_total_chunks(connection: psycopg.Connection[Any]) -> int:
    with connection.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) AS count FROM corpus_chunks")
        row = cursor.fetchone()
    return int((row or {}).get("count") or 0)


def _count_materialized_chunks(connection: psycopg.Connection[Any], *, embedding_model: str) -> int:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT COUNT(*) AS count
            FROM chunk_embeddings
            WHERE embedding_model = %s
            """,
            (embedding_model,),
        )
        row = cursor.fetchone()
    return int((row or {}).get("count") or 0)


def _load_chunks_for_materialization(
    connection: psycopg.Connection[Any],
    *,
    embedding_model: str,
    force_refresh: bool,
) -> list[dict[str, str]]:
    where_clause = ""
    if not force_refresh:
        where_clause = "WHERE ce.chunk_id IS NULL"
    with connection.cursor() as cursor:
        cursor.execute(
            f"""
            SELECT c.chunk_id,
                   c.document_id,
                   c.chunk_text
            FROM corpus_chunks c
            LEFT JOIN chunk_embeddings ce
              ON ce.chunk_id = c.chunk_id
             AND ce.embedding_model = %s
            {where_clause}
            ORDER BY c.document_id, c.chunk_index_in_document
            """,
            (embedding_model,),
        )
        rows = cursor.fetchall()
    return [
        {
            "chunk_id": str(row["chunk_id"]),
            "document_id": str(row["document_id"]),
            "chunk_text": str(row["chunk_text"] or ""),
        }
        for row in rows
    ]


def _upsert_chunk_embedding(
    connection: psycopg.Connection[Any],
    *,
    chunk_id: str,
    embedding_model: str,
    embedding_values: list[float],
    metadata: dict[str, Any],
) -> None:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO chunk_embeddings (
                chunk_id,
                embedding_model,
                embedding_dim,
                embedding,
                metadata,
                updated_at
            ) VALUES (
                %s, %s, %s, CAST(%s AS halfvec), %s, NOW()
            )
            ON CONFLICT (chunk_id, embedding_model) DO UPDATE
            SET embedding_dim = EXCLUDED.embedding_dim,
                embedding = EXCLUDED.embedding,
                metadata = EXCLUDED.metadata,
                updated_at = NOW()
            """,
            (
                chunk_id,
                embedding_model,
                len(embedding_values),
                embedding_to_halfvec_literal(embedding_values),
                Jsonb(metadata),
            ),
        )


def run_chunk_embedding_materialization(
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
                "stage": "materialize-chunk-embeddings",
                "retrieval_backend": str(config.raw.get("retrieval_backend") or "local"),
            },
            run_label="materialize-chunk-embeddings",
        )
        retriever_config = replace(
            build_retriever_config(config.raw),
            mode=RETRIEVAL_MODE_DENSE_ONLY,
            dense_embedding_required=True,
            dense_fallback_enabled=False,
        )
        embedding_model = str(config.raw.get("chunk_embedding_model") or retriever_config.dense_embedding_model).strip()
        if not embedding_model:
            raise RuntimeError("chunk embedding materialization requires dense_embedding_model")
        force_refresh = bool(config.raw.get("chunk_embedding_force_refresh", False))
        batch_size = max(1, int(config.raw.get("chunk_embedding_materialization_batch_size") or DEFAULT_BATCH_SIZE))

        total_chunks = _count_total_chunks(connection)
        materialized_before = _count_materialized_chunks(connection, embedding_model=embedding_model)
        pending_chunks = _load_chunks_for_materialization(
            connection,
            embedding_model=embedding_model,
            force_refresh=force_refresh,
        )

        materialized_count = 0
        fallback_used = False
        backend_name = retriever_config.dense_embedding_model
        for start in range(0, len(pending_chunks), batch_size):
            batch = pending_chunks[start : start + batch_size]
            texts = [row["chunk_text"] for row in batch]
            embeddings, backend_name, fallback_used = encode_passages_with_retriever_config(
                texts,
                retriever_config=retriever_config,
                require_real_dense=True,
            )
            if fallback_used:
                raise RuntimeError("db-ann chunk embedding materialization must not fall back to hash-embedding-v1")
            for row, embedding in zip(batch, embeddings, strict=True):
                _upsert_chunk_embedding(
                    connection,
                    chunk_id=row["chunk_id"],
                    embedding_model=embedding_model,
                    embedding_values=embedding,
                    metadata={
                        "document_id": row["document_id"],
                        "source_command": "materialize-chunk-embeddings",
                        "experiment_key": config.experiment_key,
                        "vector_store": "postgresql-pgvector",
                    },
                )
                materialized_count += 1

        materialized_after = _count_materialized_chunks(connection, embedding_model=embedding_model)
        summary = {
            "experiment_key": config.experiment_key,
            "experiment_run_id": run_context.experiment_run_id,
            "embedding_model": embedding_model,
            "dense_backend": backend_name,
            "retrieval_backend": str(config.raw.get("retrieval_backend") or "local"),
            "vector_store": "postgresql-pgvector",
            "fallback_used": fallback_used,
            "force_refresh": force_refresh,
            "batch_size": batch_size,
            "total_chunks": total_chunks,
            "materialized_before": materialized_before,
            "pending_before": len(pending_chunks),
            "materialized_count": materialized_count,
            "materialized_after": materialized_after,
            "missing_after": max(0, total_chunks - materialized_after),
        }
        recorder.finish_run(run_context, status="completed", metrics=summary)
        connection.commit()
        return summary
    except Exception:
        connection.rollback()
        LOGGER.exception("Chunk embedding materialization failed.")
        raise
    finally:
        connection.close()


def run_chunk_embedding_materialization_from_env(experiment: str) -> dict[str, Any]:
    defaults = default_database_args()
    return run_chunk_embedding_materialization(
        experiment=experiment,
        database_url=defaults["database_url"],
        db_host=defaults["db_host"],
        db_port=defaults["db_port"],
        db_name=defaults["db_name"],
        db_user=defaults["db_user"],
        db_password=defaults["db_password"],
    )
