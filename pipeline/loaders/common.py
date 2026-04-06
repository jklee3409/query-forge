from __future__ import annotations

import getpass
import hashlib
import json
import logging
import os
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable, Sequence

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

try:
    from collectors.spring_docs_collector import SourceConfig, load_source_configs
except ModuleNotFoundError:  # pragma: no cover - direct module execution fallback
    from pipeline.collectors.spring_docs_collector import SourceConfig, load_source_configs


LOGGER = logging.getLogger(__name__)


def configure_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s - %(message)s",
    )


def stable_uuid(value: str) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, value))


def checksum_text(text: str) -> str:
    return hashlib.sha1(text.encode("utf-8")).hexdigest()


def normalize_term_text(text: str) -> str:
    return " ".join(text.strip().lower().split())


def jsonb(value: Any) -> Jsonb:
    return Jsonb(value)


def now_ms() -> int:
    return int(time.time() * 1000)


def batch_iterable(values: Sequence[Any], batch_size: int) -> Iterable[Sequence[Any]]:
    for start in range(0, len(values), batch_size):
        yield values[start : start + batch_size]


@dataclass(slots=True)
class ImportStats:
    inserted: int = 0
    updated: int = 0
    skipped: int = 0
    failed: int = 0
    preview_ids: list[str] = field(default_factory=list)
    errors: list[dict[str, Any]] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "inserted": self.inserted,
            "updated": self.updated,
            "skipped": self.skipped,
            "failed": self.failed,
            "preview_ids": self.preview_ids,
            "errors": self.errors,
        }


@dataclass(slots=True)
class ImportOptions:
    database_url: str | None
    host: str
    port: int
    database: str
    user: str
    password: str
    source_config_dir: Path
    raw_input_path: Path | None
    sections_input_path: Path
    chunks_input_path: Path
    glossary_input_path: Path
    dry_run: bool
    batch_size: int
    trigger_type: str
    created_by: str
    source_ids: set[str]
    document_ids: set[str]
    run_type: str
    external_run_id: str | None


def build_options(args: Any) -> ImportOptions:
    return ImportOptions(
        database_url=args.database_url,
        host=args.db_host,
        port=args.db_port,
        database=args.db_name,
        user=args.db_user,
        password=args.db_password,
        source_config_dir=Path(args.source_config_dir),
        raw_input_path=Path(args.raw_input) if args.raw_input else None,
        sections_input_path=Path(args.sections_input),
        chunks_input_path=Path(args.chunks_input),
        glossary_input_path=Path(args.glossary_input),
        dry_run=bool(args.dry_run),
        batch_size=int(args.batch_size),
        trigger_type=str(args.trigger_type),
        created_by=str(args.created_by or getpass.getuser()),
        source_ids=set(args.source_id or []),
        document_ids=set(args.document_id or []),
        run_type=str(args.run_type),
        external_run_id=getattr(args, "external_run_id", None),
    )


def connect(options: ImportOptions, *, autocommit: bool = False) -> psycopg.Connection[Any]:
    if options.database_url:
        connection = psycopg.connect(options.database_url, row_factory=dict_row, autocommit=autocommit)
    else:
        connection = psycopg.connect(
            host=options.host,
            port=options.port,
            dbname=options.database,
            user=options.user,
            password=options.password,
            row_factory=dict_row,
            autocommit=autocommit,
        )
    return connection


def default_database_args() -> dict[str, Any]:
    return {
        "database_url": os.getenv("DATABASE_URL"),
        "db_host": os.getenv("POSTGRES_HOST", "localhost"),
        "db_port": int(os.getenv("POSTGRES_PORT", "5432")),
        "db_name": os.getenv("POSTGRES_DB", "query_forge"),
        "db_user": os.getenv("POSTGRES_USER", "query_forge"),
        "db_password": os.getenv("POSTGRES_PASSWORD", "query_forge"),
    }


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as source:
        for line in source:
            if line.strip():
                rows.append(json.loads(line))
    return rows


def load_raw_documents(path: Path | None) -> dict[str, dict[str, Any]]:
    if path is None or not path.exists():
        return {}
    return {row["document_id"]: row for row in load_jsonl(path)}


def load_sources(config_dir: Path, source_ids: set[str] | None = None) -> dict[str, SourceConfig]:
    configs = load_source_configs(config_dir)
    if source_ids:
        configs = [config for config in configs if config.source_id in source_ids]
    return {config.source_id: config for config in configs}


def filter_rows(
    rows: Iterable[dict[str, Any]],
    *,
    source_ids: set[str],
    document_ids: set[str],
    raw_documents: dict[str, dict[str, Any]],
) -> list[dict[str, Any]]:
    filtered: list[dict[str, Any]] = []
    for row in rows:
        document_id = str(row.get("document_id") or "")
        if document_ids and document_id not in document_ids:
            continue
        if source_ids:
            raw_source_id = str(raw_documents.get(document_id, {}).get("source_id") or "")
            if raw_source_id not in source_ids:
                continue
        filtered.append(row)
    return filtered


def source_scope_json(options: ImportOptions) -> dict[str, Any]:
    return {
        "source_ids": sorted(options.source_ids),
        "document_ids": sorted(options.document_ids),
        "raw_input_path": str(options.raw_input_path) if options.raw_input_path else None,
        "sections_input_path": str(options.sections_input_path),
        "chunks_input_path": str(options.chunks_input_path),
        "glossary_input_path": str(options.glossary_input_path),
    }


def config_snapshot_json(options: ImportOptions) -> dict[str, Any]:
    return {
        "source_config_dir": str(options.source_config_dir),
        "batch_size": options.batch_size,
        "dry_run": options.dry_run,
        "trigger_type": options.trigger_type,
        "run_type": options.run_type,
    }


class RunRecorder:
    def __init__(self, connection: psycopg.Connection[Any]) -> None:
        self.connection = connection

    def create_run(
        self,
        *,
        run_type: str,
        trigger_type: str,
        source_scope: dict[str, Any],
        config_snapshot: dict[str, Any],
        created_by: str,
    ) -> str:
        run_id = str(uuid.uuid4())
        with self.connection.cursor() as cursor:
            cursor.execute(
                """
                INSERT INTO corpus_runs (
                    run_id,
                    run_type,
                    run_status,
                    trigger_type,
                    source_scope,
                    config_snapshot,
                    started_at,
                    created_by
                ) VALUES (%s, %s, 'running', %s, %s, %s, NOW(), %s)
                """,
                (
                    run_id,
                    run_type,
                    trigger_type,
                    jsonb(source_scope),
                    jsonb(config_snapshot),
                    created_by,
                ),
            )
        return run_id

    def finish_run(
        self,
        run_id: str,
        *,
        status: str,
        summary_json: dict[str, Any],
        error_message: str | None = None,
    ) -> None:
        with self.connection.cursor() as cursor:
            cursor.execute(
                """
                UPDATE corpus_runs
                SET run_status = %s,
                    finished_at = NOW(),
                    duration_ms = GREATEST(0, FLOOR(EXTRACT(EPOCH FROM (NOW() - started_at)) * 1000))::BIGINT,
                    summary_json = %s,
                    error_message = %s
                WHERE run_id = %s
                """,
                (status, jsonb(summary_json), error_message, run_id),
            )

    def create_step(
        self,
        *,
        run_id: str,
        step_name: str,
        step_order: int,
        input_artifact_path: str | None,
        output_artifact_path: str | None,
    ) -> str:
        step_id = str(uuid.uuid4())
        with self.connection.cursor() as cursor:
            cursor.execute(
                """
                INSERT INTO corpus_run_steps (
                    step_id,
                    run_id,
                    step_name,
                    step_order,
                    step_status,
                    input_artifact_path,
                    output_artifact_path,
                    started_at
                ) VALUES (%s, %s, %s, %s, 'running', %s, %s, NOW())
                """,
                (
                    step_id,
                    run_id,
                    step_name,
                    step_order,
                    input_artifact_path,
                    output_artifact_path,
                ),
            )
        return step_id

    def finish_step(
        self,
        step_id: str,
        *,
        status: str,
        metrics_json: dict[str, Any],
        error_message: str | None = None,
    ) -> None:
        with self.connection.cursor() as cursor:
            cursor.execute(
                """
                UPDATE corpus_run_steps
                SET step_status = %s,
                    metrics_json = %s,
                    finished_at = NOW(),
                    error_message = %s
                WHERE step_id = %s
                """,
                (status, jsonb(metrics_json), error_message, step_id),
            )


def ensure_source_rows(
    connection: psycopg.Connection[Any],
    source_configs: dict[str, SourceConfig],
    *,
    batch_size: int,
    dry_run: bool,
) -> ImportStats:
    stats = ImportStats()
    existing: dict[str, dict[str, Any]] = {}
    source_ids = sorted(source_configs)
    if not source_ids:
        return stats

    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT source_id, source_type, product_name, source_name, base_url, include_patterns, exclude_patterns,
                   default_version, enabled
            FROM corpus_sources
            WHERE source_id = ANY(%s)
            """,
            (source_ids,),
        )
        for row in cursor.fetchall():
            existing[str(row["source_id"])] = row

    upserts: list[tuple[Any, ...]] = []
    for source_id, config in source_configs.items():
        source_row = {
            "source_type": "html",
            "product_name": config.product,
            "source_name": config.source_id,
            "base_url": config.allow_prefixes[0] if config.allow_prefixes else config.start_urls[0],
            "include_patterns": config.allow_prefixes,
            "exclude_patterns": config.deny_url_patterns,
            "default_version": None,
            "enabled": config.enabled,
        }
        existing_row = existing.get(source_id)
        if existing_row and all(
            [
                existing_row["source_type"] == source_row["source_type"],
                existing_row["product_name"] == source_row["product_name"],
                existing_row["source_name"] == source_row["source_name"],
                existing_row["base_url"] == source_row["base_url"],
                list(existing_row["include_patterns"]) == source_row["include_patterns"],
                list(existing_row["exclude_patterns"]) == source_row["exclude_patterns"],
                existing_row["default_version"] == source_row["default_version"],
                existing_row["enabled"] == source_row["enabled"],
            ]
        ):
            stats.skipped += 1
            continue

        if existing_row:
            stats.updated += 1
        else:
            stats.inserted += 1
        stats.preview_ids.append(source_id)
        upserts.append(
            (
                source_id,
                source_row["source_type"],
                source_row["product_name"],
                source_row["source_name"],
                source_row["base_url"],
                jsonb(source_row["include_patterns"]),
                jsonb(source_row["exclude_patterns"]),
                source_row["default_version"],
                source_row["enabled"],
            )
        )

    if dry_run or not upserts:
        return stats

    sql = """
        INSERT INTO corpus_sources (
            source_id,
            source_type,
            product_name,
            source_name,
            base_url,
            include_patterns,
            exclude_patterns,
            default_version,
            enabled,
            created_at,
            updated_at
        ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, NOW(), NOW())
        ON CONFLICT (source_id) DO UPDATE
        SET source_type = EXCLUDED.source_type,
            product_name = EXCLUDED.product_name,
            source_name = EXCLUDED.source_name,
            base_url = EXCLUDED.base_url,
            include_patterns = EXCLUDED.include_patterns,
            exclude_patterns = EXCLUDED.exclude_patterns,
            default_version = EXCLUDED.default_version,
            enabled = EXCLUDED.enabled,
            updated_at = NOW()
    """
    with connection.cursor() as cursor:
        for batch in batch_iterable(upserts, batch_size):
            cursor.executemany(sql, batch)

    return stats
