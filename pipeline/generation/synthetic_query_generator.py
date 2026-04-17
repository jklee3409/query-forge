from __future__ import annotations

import json
import logging
import random
import uuid
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import psycopg
from psycopg import sql
from psycopg.types.json import Jsonb

try:
    from common.corpus_shadow_sync import sync_shadow_tables
    from common.experiment_config import ExperimentConfig, load_experiment_config
    from common.experiment_run import ExperimentRunRecorder
    from common.llm_client import LlmClient, load_stage_config
    from common.prompt_assets import PromptAsset, load_and_register_prompt
    from loaders.common import connect, default_database_args
except ModuleNotFoundError:  # pragma: no cover - direct module execution fallback
    from pipeline.common.corpus_shadow_sync import sync_shadow_tables
    from pipeline.common.experiment_config import ExperimentConfig, load_experiment_config
    from pipeline.common.experiment_run import ExperimentRunRecorder
    from pipeline.common.llm_client import LlmClient, load_stage_config
    from pipeline.common.prompt_assets import PromptAsset, load_and_register_prompt
    from pipeline.loaders.common import connect, default_database_args


LOGGER = logging.getLogger(__name__)


QUERY_TYPE_LABELS_KO: dict[str, str] = {
    "definition": "정의/개념형",
    "reason": "원인/이유형",
    "procedure": "절차/방법형",
    "comparison": "비교형",
    "short_user": "짧은 사용자형",
    "code_mixed": "code-mixed",
    "follow_up": "문맥 의존형 후속 질의",
}

SUMMARY_EN_RESPONSE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": ["extractive_summary_en"],
    "properties": {
        "extractive_summary_en": {"type": "string"},
        "key_terms": {
            "type": "array",
            "items": {"type": "string"},
        },
    },
    "additionalProperties": True,
}

TRANSLATION_RESPONSE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": ["translated_chunk_ko"],
    "properties": {
        "translated_chunk_ko": {"type": "string"},
    },
    "additionalProperties": True,
}

SUMMARY_KO_RESPONSE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": ["summary_ko"],
    "properties": {
        "summary_ko": {"type": "string"},
    },
    "additionalProperties": True,
}

QUERY_BASE_RESPONSE_SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": ["query_ko"],
    "properties": {
        "query_ko": {"type": "string"},
        "query_en": {"type": "string"},
        "query_code_mixed": {"type": "string"},
        "query_type": {"type": "string"},
        "answerability_type": {"type": "string"},
        "style_note": {"type": "string"},
    },
    "additionalProperties": True,
}

STRATEGY_RAW_TABLES: dict[str, str] = {
    "A": "synthetic_queries_raw_a",
    "B": "synthetic_queries_raw_b",
    "C": "synthetic_queries_raw_c",
    "D": "synthetic_queries_raw_d",
}


@dataclass(slots=True)
class ChunkRow:
    chunk_id: str
    document_id: str
    chunk_text: str
    title: str
    product_name: str
    version_label: str | None
    content_checksum: str | None
    cleaned_checksum: str | None


@dataclass(slots=True)
class PromptBundle:
    summary_en_asset: PromptAsset
    summary_en_text: str
    summary_ko_asset: PromptAsset
    summary_ko_text: str
    translate_asset: PromptAsset
    translate_text: str
    query_assets: dict[str, PromptAsset]
    query_texts: dict[str, str]


def _stable_id(parts: list[str]) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "|".join(parts)))


def _weighted_choice(rng: random.Random, distribution: dict[str, float]) -> str:
    picks = list(distribution.items())
    roll = rng.random()
    cumulative = 0.0
    for key, weight in picks:
        cumulative += weight
        if roll <= cumulative:
            return key
    return picks[-1][0]


def _read_prompt(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _resolve_prompt_bundle(
    connection: psycopg.Connection[Any],
    *,
    config: ExperimentConfig,
    prompt_root: Path,
) -> PromptBundle:
    summary_en_path = prompt_root / "summary_extraction" / "extractive_summary_v1.md"
    summary_ko_path = prompt_root / "summary_extraction" / "summarize_ko_v1.md"
    translate_path = prompt_root / "translation" / "translate_chunk_en_to_ko_v1.md"
    query_paths = {
        "A": prompt_root / "query_generation" / "gen_a_v1.md",
        "B": prompt_root / "query_generation" / "gen_b_v1.md",
        "C": prompt_root / "query_generation" / "gen_c_v1.md",
        "D": prompt_root / "query_generation" / "gen_d_v1.md",
    }
    for required_path in (summary_en_path, summary_ko_path, translate_path, *query_paths.values()):
        if not required_path.exists():
            raise FileNotFoundError(f"Prompt file not found: {required_path}")

    return PromptBundle(
        summary_en_asset=load_and_register_prompt(connection, summary_en_path),
        summary_en_text=_read_prompt(summary_en_path),
        summary_ko_asset=load_and_register_prompt(connection, summary_ko_path),
        summary_ko_text=_read_prompt(summary_ko_path),
        translate_asset=load_and_register_prompt(connection, translate_path),
        translate_text=_read_prompt(translate_path),
        query_assets={key: load_and_register_prompt(connection, path) for key, path in query_paths.items()},
        query_texts={key: _read_prompt(path) for key, path in query_paths.items()},
    )


def _load_chunks(
    connection: psycopg.Connection[Any],
    *,
    limit: int | None,
    source_document_id: str | None = None,
    source_id: str | None = None,
    random_chunk_sampling: bool = False,
    random_seed: int | None = None,
) -> list[ChunkRow]:
    statement = """
        SELECT c.chunk_id,
               c.document_id,
               c.chunk_text,
               d.title,
               c.product_name,
               c.version_label,
               c.content_checksum,
               d.cleaned_checksum
        FROM corpus_chunks c
        JOIN corpus_documents d ON d.document_id = c.document_id
    """
    where_clauses: list[str] = []
    parameters: list[Any] = []
    if source_document_id:
        where_clauses.append("c.document_id = %s")
        parameters.append(source_document_id)
    if source_id:
        where_clauses.append("d.source_id = %s")
        parameters.append(source_id)
    where_clause = ""
    if where_clauses:
        where_clause = " WHERE " + " AND ".join(where_clauses) + " "
    statement = statement + where_clause + " ORDER BY c.document_id, c.chunk_index_in_document "
    with connection.cursor() as cursor:
        if random_chunk_sampling:
            cursor.execute(statement, parameters)
            rows = cursor.fetchall()
            if rows:
                sampler = random.Random(random_seed)
                sampler.shuffle(rows)
                if limit:
                    rows = rows[:limit]
        else:
            if limit:
                cursor.execute(statement + " LIMIT %s", (*parameters, limit))
            else:
                cursor.execute(statement, parameters)
            rows = cursor.fetchall()
    return [
        ChunkRow(
            chunk_id=str(row["chunk_id"]),
            document_id=str(row["document_id"]),
            chunk_text=str(row["chunk_text"]),
            title=str(row["title"]),
            product_name=str(row["product_name"]),
            version_label=row["version_label"],
            content_checksum=row["content_checksum"],
            cleaned_checksum=row["cleaned_checksum"],
        )
        for row in rows
    ]


def _load_relations(
    connection: psycopg.Connection[Any],
) -> dict[str, dict[str, list[str]]]:
    relations: dict[str, dict[str, list[str]]] = defaultdict(
        lambda: {"near": [], "far": []}
    )
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT source_chunk_id, target_chunk_id, relation_type
            FROM corpus_chunk_relations
            WHERE relation_type IN ('near', 'far')
            ORDER BY source_chunk_id, relation_type, distance_in_doc
            """
        )
        for row in cursor.fetchall():
            source_chunk_id = str(row["source_chunk_id"])
            target_chunk_id = str(row["target_chunk_id"])
            relation_type = str(row["relation_type"])
            relations[source_chunk_id][relation_type].append(target_chunk_id)
    return relations


def _load_glossary(
    connection: psycopg.Connection[Any],
) -> dict[str, list[str]]:
    glossary_by_doc: dict[str, list[str]] = defaultdict(list)
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT first_seen_document_id, canonical_form
            FROM corpus_glossary_terms
            WHERE is_active = TRUE
              AND first_seen_document_id IS NOT NULL
            ORDER BY evidence_count DESC, canonical_form
            """
        )
        for row in cursor.fetchall():
            document_id = str(row["first_seen_document_id"])
            term = str(row["canonical_form"])
            if term not in glossary_by_doc[document_id]:
                glossary_by_doc[document_id].append(term)
    return glossary_by_doc


def _select_answerability_target(
    chunk: ChunkRow,
    answerability_type: str,
    relations: dict[str, dict[str, list[str]]],
    rng: random.Random,
) -> tuple[str, list[str]]:
    source_relations = relations.get(chunk.chunk_id, {"near": [], "far": []})
    if answerability_type == "single":
        return "single", [chunk.chunk_id]

    candidates = source_relations.get(answerability_type, [])
    if candidates:
        target = rng.choice(candidates)
        return answerability_type, [chunk.chunk_id, target]

    near_candidates = source_relations.get("near", [])
    if near_candidates:
        target = rng.choice(near_candidates)
        return "near", [chunk.chunk_id, target]
    return "single", [chunk.chunk_id]


def _resolve_generation_method_id(
    connection: psycopg.Connection[Any],
    method_code: str,
) -> str | None:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT generation_method_id
            FROM synthetic_query_generation_method
            WHERE method_code = %s
            """,
            (method_code,),
        )
        row = cursor.fetchone()
    if row is None:
        return None
    value = row["generation_method_id"] if isinstance(row, dict) else row[0]
    return str(value) if value is not None else None


def _batch_exists(
    connection: psycopg.Connection[Any],
    batch_id: str,
) -> bool:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT 1
            FROM synthetic_query_generation_batch
            WHERE batch_id = %s
            """,
            (batch_id,),
        )
        return cursor.fetchone() is not None


def _normalize_query_text(text: str) -> str:
    return " ".join(text.strip().lower().split())


def _language_profile(strategy: str, query_type: str) -> str:
    if strategy == "D" or query_type == "code_mixed":
        return "code_mixed"
    return "ko"


def _source_fingerprint(chunk: ChunkRow) -> str:
    if chunk.cleaned_checksum:
        return str(chunk.cleaned_checksum)
    if chunk.content_checksum:
        return str(chunk.content_checksum)
    return _stable_id([chunk.chunk_id, chunk.chunk_text[:256]])


def _find_existing_asset(
    connection: psycopg.Connection[Any],
    *,
    chunk_id: str,
    asset_type: str,
    llm_provider: str,
    llm_model: str,
    prompt_template_version: str,
    source_fingerprint: str,
) -> tuple[str, str] | None:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT asset_id, text_content
            FROM chunk_generation_asset
            WHERE chunk_id = %s
              AND asset_type = %s
              AND llm_provider = %s
              AND llm_model = %s
              AND prompt_template_version = %s
              AND source_fingerprint = %s
            ORDER BY created_at DESC
            LIMIT 1
            """,
            (
                chunk_id,
                asset_type,
                llm_provider,
                llm_model,
                prompt_template_version,
                source_fingerprint,
            ),
        )
        row = cursor.fetchone()
    if row is None:
        return None
    return str(row["asset_id"]), str(row["text_content"])


def _create_asset(
    connection: psycopg.Connection[Any],
    *,
    chunk: ChunkRow,
    asset_type: str,
    text_content: str,
    llm_provider: str,
    llm_model: str,
    prompt_template_version: str,
    source_fingerprint: str,
    metadata: dict[str, Any],
) -> str:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO chunk_generation_asset (
                chunk_id,
                source_document_id,
                asset_type,
                text_content,
                llm_provider,
                llm_model,
                prompt_template_version,
                source_fingerprint,
                metadata_json
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
            RETURNING asset_id
            """,
            (
                chunk.chunk_id,
                chunk.document_id,
                asset_type,
                text_content,
                llm_provider,
                llm_model,
                prompt_template_version,
                source_fingerprint,
                Jsonb(metadata),
            ),
        )
        row = cursor.fetchone()
    return str(row["asset_id"])


def _llm_json(
    client: LlmClient,
    *,
    prompt_text: str,
    payload: dict[str, Any],
    response_schema: dict[str, Any],
    request_purpose: str,
    trace_id: str,
) -> dict[str, Any]:
    return client.chat_json(
        system_prompt=prompt_text,
        user_prompt=json.dumps(payload, ensure_ascii=False, indent=2),
        response_schema=response_schema,
        request_purpose=request_purpose,
        trace_id=trace_id,
    )


def _resolve_or_create_summary_en(
    connection: psycopg.Connection[Any],
    *,
    chunk: ChunkRow,
    source_fingerprint: str,
    prompt_asset: PromptAsset,
    prompt_text: str,
    client: LlmClient,
) -> tuple[str, str, bool]:
    cached = _find_existing_asset(
        connection,
        chunk_id=chunk.chunk_id,
        asset_type="EN_EXTRACTIVE_SUMMARY",
        llm_provider=client.config.provider,
        llm_model=client.config.model,
        prompt_template_version=prompt_asset.version,
        source_fingerprint=source_fingerprint,
    )
    if cached:
        return cached[0], cached[1], True
    response = _llm_json(
        client,
        prompt_text=prompt_text,
        payload={
            "chunk_id": chunk.chunk_id,
            "title": chunk.title,
            "product": chunk.product_name,
            "chunk_text_en": chunk.chunk_text,
        },
        response_schema=SUMMARY_EN_RESPONSE_SCHEMA,
        request_purpose="summary_extraction_en",
        trace_id=f"chunk:{chunk.chunk_id}",
    )
    summary_en = str(response.get("extractive_summary_en") or response.get("summary_en") or "").strip()
    if not summary_en:
        raise RuntimeError(f"empty extractive_summary_en for chunk={chunk.chunk_id}")
    asset_id = _create_asset(
        connection,
        chunk=chunk,
        asset_type="EN_EXTRACTIVE_SUMMARY",
        text_content=summary_en,
        llm_provider=client.config.provider,
        llm_model=client.config.model,
        prompt_template_version=prompt_asset.version,
        source_fingerprint=source_fingerprint,
        metadata={"key_terms": response.get("key_terms") or []},
    )
    return asset_id, summary_en, False


def _resolve_or_create_translated_chunk(
    connection: psycopg.Connection[Any],
    *,
    chunk: ChunkRow,
    source_fingerprint: str,
    prompt_asset: PromptAsset,
    prompt_text: str,
    client: LlmClient,
) -> tuple[str, str, bool]:
    cached = _find_existing_asset(
        connection,
        chunk_id=chunk.chunk_id,
        asset_type="KO_TRANSLATED_CHUNK",
        llm_provider=client.config.provider,
        llm_model=client.config.model,
        prompt_template_version=prompt_asset.version,
        source_fingerprint=source_fingerprint,
    )
    if cached:
        return cached[0], cached[1], True
    response = _llm_json(
        client,
        prompt_text=prompt_text,
        payload={
            "chunk_id": chunk.chunk_id,
            "title": chunk.title,
            "chunk_text_en": chunk.chunk_text,
        },
        response_schema=TRANSLATION_RESPONSE_SCHEMA,
        request_purpose="translate_chunk_en_to_ko",
        trace_id=f"chunk:{chunk.chunk_id}",
    )
    translated = str(response.get("translated_chunk_ko") or "").strip()
    if not translated:
        raise RuntimeError(f"empty translated_chunk_ko for chunk={chunk.chunk_id}")
    asset_id = _create_asset(
        connection,
        chunk=chunk,
        asset_type="KO_TRANSLATED_CHUNK",
        text_content=translated,
        llm_provider=client.config.provider,
        llm_model=client.config.model,
        prompt_template_version=prompt_asset.version,
        source_fingerprint=source_fingerprint,
        metadata={"source": "en_chunk"},
    )
    return asset_id, translated, False


def _resolve_or_create_summary_ko(
    connection: psycopg.Connection[Any],
    *,
    chunk: ChunkRow,
    source_fingerprint: str,
    prompt_asset: PromptAsset,
    prompt_text: str,
    prompt_version_suffix: str,
    source_text_ko: str,
    client: LlmClient,
) -> tuple[str, str, bool]:
    template_version = f"{prompt_asset.version}:{prompt_version_suffix}"
    cached = _find_existing_asset(
        connection,
        chunk_id=chunk.chunk_id,
        asset_type="KO_SUMMARY",
        llm_provider=client.config.provider,
        llm_model=client.config.model,
        prompt_template_version=template_version,
        source_fingerprint=source_fingerprint,
    )
    if cached:
        return cached[0], cached[1], True
    response = _llm_json(
        client,
        prompt_text=prompt_text,
        payload={
            "chunk_id": chunk.chunk_id,
            "source_text_ko": source_text_ko,
        },
        response_schema=SUMMARY_KO_RESPONSE_SCHEMA,
        request_purpose="summary_extraction_ko",
        trace_id=f"chunk:{chunk.chunk_id}",
    )
    summary_ko = str(response.get("summary_ko") or "").strip()
    if not summary_ko:
        raise RuntimeError(f"empty summary_ko for chunk={chunk.chunk_id}")
    asset_id = _create_asset(
        connection,
        chunk=chunk,
        asset_type="KO_SUMMARY",
        text_content=summary_ko,
        llm_provider=client.config.provider,
        llm_model=client.config.model,
        prompt_template_version=template_version,
        source_fingerprint=source_fingerprint,
        metadata={"source": prompt_version_suffix},
    )
    return asset_id, summary_ko, False


def _extract_query_text(
    *,
    generation_strategy: str,
    query_type: str,
    response: dict[str, Any],
) -> tuple[str, dict[str, Any]]:
    def _fallback_query_text() -> str:
        candidate_keys = (
            "query_ko",
            "query_text",
            "query",
            "question",
            "search_query",
            "synthetic_query",
            "query_korean",
        )
        for key in candidate_keys:
            value = response.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()

        queries_value = response.get("queries")
        if isinstance(queries_value, list):
            for item in queries_value:
                if isinstance(item, str) and item.strip():
                    return item.strip()
                if isinstance(item, dict):
                    for key in candidate_keys:
                        value = item.get(key)
                        if isinstance(value, str) and value.strip():
                            return value.strip()

        for value in response.values():
            if isinstance(value, str) and value.strip():
                return value.strip()
        return ""

    if generation_strategy == "A":
        query_text = str(response.get("query_ko") or "").strip() or _fallback_query_text()
        query_en = str(response.get("query_en") or "").strip()
        return query_text, {"query_en": query_en}
    if generation_strategy == "D":
        query_ko = str(response.get("query_ko") or "").strip() or _fallback_query_text()
        query_code_mixed = str(response.get("query_code_mixed") or "").strip()
        if query_type == "code_mixed" and query_code_mixed:
            return query_code_mixed, {"query_ko": query_ko, "query_code_mixed": query_code_mixed}
        return query_ko, {"query_code_mixed": query_code_mixed}
    return str(response.get("query_ko") or "").strip() or _fallback_query_text(), {}


def _raw_table_for_strategy(generation_strategy: str) -> str:
    normalized = generation_strategy.strip().upper()
    table_name = STRATEGY_RAW_TABLES.get(normalized)
    if table_name is None:
        raise ValueError(f"unsupported generation strategy: {generation_strategy}")
    return table_name


def _find_cached_query(
    connection: psycopg.Connection[Any],
    *,
    table_name: str,
    synthetic_query_id: str,
    source_fingerprint: str,
    prompt_template_version: str,
) -> bool:
    statement = sql.SQL(
        """
        SELECT 1
        FROM {table_name}
        WHERE synthetic_query_id = %s
          AND source_fingerprint = %s
          AND prompt_template_version = %s
        """
    ).format(table_name=sql.Identifier(table_name))
    with connection.cursor() as cursor:
        cursor.execute(
            statement,
            (
                synthetic_query_id,
                source_fingerprint,
                prompt_template_version,
            ),
        )
        return cursor.fetchone() is not None


def _attach_cached_query(
    connection: psycopg.Connection[Any],
    *,
    table_name: str,
    synthetic_query_id: str,
    generation_method_id: str | None,
    generation_batch_id: str | None,
    llm_provider: str,
    llm_model: str,
    generation_asset_ids: list[str],
) -> None:
    statement = sql.SQL(
        """
        UPDATE {table_name}
        SET generation_method_id = %s,
            generation_batch_id = %s,
            llm_provider = %s,
            llm_model = %s,
            generation_asset_ids = %s
        WHERE synthetic_query_id = %s
        """
    ).format(table_name=sql.Identifier(table_name))
    with connection.cursor() as cursor:
        cursor.execute(
            statement,
            (
                generation_method_id,
                generation_batch_id,
                llm_provider,
                llm_model,
                Jsonb(generation_asset_ids),
                synthetic_query_id,
            ),
        )


def _insert_query_row(
    connection: psycopg.Connection[Any],
    *,
    table_name: str,
    payload: dict[str, Any],
) -> None:
    statement = sql.SQL(
        """
        INSERT INTO {table_name} (
            synthetic_query_id,
            experiment_run_id,
            generation_method_id,
            generation_batch_id,
            chunk_id_source,
            source_chunk_group_id,
            target_doc_id,
            target_chunk_ids,
            answerability_type,
            query_text,
            normalized_query_text,
            query_language,
            language_profile,
            query_type,
            generation_strategy,
            prompt_asset_id,
            prompt_template_version,
            prompt_version,
            prompt_hash,
            source_summary,
            source_fingerprint,
            source_chunk_ids,
            glossary_terms,
            llm_provider,
            llm_model,
            generation_asset_ids,
            llm_output,
            metadata
        ) VALUES (
            %(synthetic_query_id)s,
            %(experiment_run_id)s,
            %(generation_method_id)s,
            %(generation_batch_id)s,
            %(chunk_id_source)s,
            %(source_chunk_group_id)s,
            %(target_doc_id)s,
            %(target_chunk_ids)s,
            %(answerability_type)s,
            %(query_text)s,
            %(normalized_query_text)s,
            %(query_language)s,
            %(language_profile)s,
            %(query_type)s,
            %(generation_strategy)s,
            %(prompt_asset_id)s,
            %(prompt_template_version)s,
            %(prompt_version)s,
            %(prompt_hash)s,
            %(source_summary)s,
            %(source_fingerprint)s,
            %(source_chunk_ids)s,
            %(glossary_terms)s,
            %(llm_provider)s,
            %(llm_model)s,
            %(generation_asset_ids)s,
            %(llm_output)s,
            %(metadata)s
        )
        ON CONFLICT (synthetic_query_id) DO UPDATE
        SET query_text = EXCLUDED.query_text,
            target_chunk_ids = EXCLUDED.target_chunk_ids,
            source_summary = EXCLUDED.source_summary,
            source_fingerprint = EXCLUDED.source_fingerprint,
            glossary_terms = EXCLUDED.glossary_terms,
            llm_provider = EXCLUDED.llm_provider,
            llm_model = EXCLUDED.llm_model,
            generation_asset_ids = EXCLUDED.generation_asset_ids,
            llm_output = EXCLUDED.llm_output,
            metadata = EXCLUDED.metadata,
            generation_method_id = EXCLUDED.generation_method_id,
            generation_batch_id = EXCLUDED.generation_batch_id,
            normalized_query_text = EXCLUDED.normalized_query_text,
            language_profile = EXCLUDED.language_profile,
            prompt_template_version = EXCLUDED.prompt_template_version
        """
    ).format(table_name=sql.Identifier(table_name))

    with connection.cursor() as cursor:
        cursor.execute(statement, payload)


def _insert_source_link(
    connection: psycopg.Connection[Any],
    *,
    synthetic_query_id: str,
    source_doc_id: str,
    source_chunk_id: str,
    source_chunk_group_id: str | None,
    source_role: str,
) -> None:
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO synthetic_query_source_link (
                synthetic_query_id,
                source_doc_id,
                source_chunk_id,
                source_chunk_group_id,
                source_role,
                metadata_json
            ) VALUES (
                %s, %s, %s, %s, %s, %s
            )
            ON CONFLICT (synthetic_query_id, source_chunk_id, source_role) DO UPDATE
            SET source_doc_id = EXCLUDED.source_doc_id,
                source_chunk_group_id = EXCLUDED.source_chunk_group_id,
                metadata_json = EXCLUDED.metadata_json
            """,
            (
                synthetic_query_id,
                source_doc_id,
                source_chunk_id,
                source_chunk_group_id,
                source_role,
                Jsonb({"linked_by": "generate-queries"}),
            ),
        )


def run_generation(
    *,
    experiment: str,
    experiment_root: Path = Path("configs/experiments"),
    prompt_root: Path = Path("configs/prompts"),
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
        sync_shadow_tables(connection)
        recorder = ExperimentRunRecorder(connection)
        run_context = recorder.start_run(
            experiment_key=config.experiment_key,
            category=config.category,
            description=config.description,
            config_path=str(config.config_path),
            config_hash=config.config_hash,
            parameters={
                "stage": "generate-queries",
                "generation_strategy": config.generation_strategy,
                "enable_code_mixed": config.enable_code_mixed,
                "avg_queries_per_chunk": config.avg_queries_per_chunk,
                "max_total_queries": config.raw.get("max_total_queries"),
                "source_id": config.raw.get("source_id"),
                "source_document_id": config.raw.get("source_document_id"),
                "random_chunk_sampling": bool(config.raw.get("random_chunk_sampling", False)),
            },
            run_label="generate-queries",
        )

        prompts = _resolve_prompt_bundle(connection, config=config, prompt_root=prompt_root)
        summary_client = LlmClient(load_stage_config(stage="summary", raw_config=config.raw))
        query_client = LlmClient(load_stage_config(stage="query", raw_config=config.raw))
        translate_client = LlmClient(load_stage_config(stage="translation", raw_config=config.raw))

        source_document_id = str(config.raw.get("source_document_id") or "").strip() or None
        source_id = str(config.raw.get("source_id") or "").strip() or None
        random_chunk_sampling = bool(config.raw.get("random_chunk_sampling", False))
        chunks = _load_chunks(
            connection,
            limit=config.limit_chunks,
            source_document_id=source_document_id,
            source_id=source_id,
            random_chunk_sampling=random_chunk_sampling,
            random_seed=config.random_seed if random_chunk_sampling else None,
        )
        if not chunks:
            LOGGER.warning(
                "No chunks found for source_document_id=%s source_id=%s limit_chunks=%s",
                source_document_id,
                source_id,
                config.limit_chunks,
            )
        relations = _load_relations(connection)
        glossary_by_doc = _load_glossary(connection)
        rng = random.Random(config.random_seed)

        strategy = config.generation_strategy
        generation_batch_id = str(config.raw.get("generation_batch_id") or "").strip() or None
        if generation_batch_id and not _batch_exists(connection, generation_batch_id):
            LOGGER.warning(
                "generation_batch_id=%s not found. storing queries with NULL batch id and deferring linkage by experiment_run_id",
                generation_batch_id,
            )
            generation_batch_id = None

        method_id_cache: dict[str, str | None] = {
            strategy: _resolve_generation_method_id(connection, strategy),
            "D": _resolve_generation_method_id(connection, "D"),
        }
        generated_count = 0
        reused_count = 0
        skipped_empty_count = 0
        query_type_counter: Counter[str] = Counter()
        answerability_counter: Counter[str] = Counter()
        generated_ids: list[str] = []
        asset_cache_hits: Counter[str] = Counter()
        asset_created: Counter[str] = Counter()
        llm_batch_size = int(config.raw.get("llm_batch_size") or 20)
        llm_batch_size = max(1, min(llm_batch_size, 20))
        max_total_queries = config.raw.get("max_total_queries")
        max_total_queries = int(max_total_queries) if max_total_queries is not None else None
        if max_total_queries is not None and max_total_queries <= 0:
            max_total_queries = None

        for chunk_index, chunk in enumerate(chunks):
            if max_total_queries is not None and generated_count >= max_total_queries:
                break
            chunk_glossary_terms = glossary_by_doc.get(chunk.document_id, [])[:12]
            base_count = max(1, int(round(config.avg_queries_per_chunk + rng.uniform(-0.9, 0.9))))
            source_fingerprint = _source_fingerprint(chunk)
            for query_index in range(base_count):
                if max_total_queries is not None and generated_count >= max_total_queries:
                    break
                query_type = _weighted_choice(rng, config.query_type_distribution)
                answerability_type = _weighted_choice(rng, config.answerability_distribution)
                answerability_type, target_chunk_ids = _select_answerability_target(
                    chunk,
                    answerability_type,
                    relations,
                    rng,
                )
                generation_strategy = strategy
                if config.enable_code_mixed and query_type == "code_mixed":
                    generation_strategy = "D"
                raw_table_name = _raw_table_for_strategy(generation_strategy)
                generation_method_id = method_id_cache.get(generation_strategy)
                query_prompt_asset = prompts.query_assets[generation_strategy]
                query_prompt_text = prompts.query_texts[generation_strategy]
                stable_query_id = _stable_id(
                    [
                        generation_strategy,
                        chunk.chunk_id,
                        source_fingerprint,
                        query_prompt_asset.version,
                        query_type,
                        answerability_type,
                        str(query_index),
                    ]
                )

                en_summary_asset_id, en_summary, en_summary_cached = _resolve_or_create_summary_en(
                    connection,
                    chunk=chunk,
                    source_fingerprint=source_fingerprint,
                    prompt_asset=prompts.summary_en_asset,
                    prompt_text=prompts.summary_en_text,
                    client=summary_client,
                )
                if en_summary_cached:
                    asset_cache_hits["EN_EXTRACTIVE_SUMMARY"] += 1
                else:
                    asset_created["EN_EXTRACTIVE_SUMMARY"] += 1

                generation_asset_ids = [en_summary_asset_id]
                translated_chunk_ko = ""
                summary_ko = ""

                if generation_strategy == "B":
                    translate_asset_id, translated_chunk_ko, translated_cached = _resolve_or_create_translated_chunk(
                        connection,
                        chunk=chunk,
                        source_fingerprint=source_fingerprint,
                        prompt_asset=prompts.translate_asset,
                        prompt_text=prompts.translate_text,
                        client=translate_client,
                    )
                    if translated_cached:
                        asset_cache_hits["KO_TRANSLATED_CHUNK"] += 1
                    else:
                        asset_created["KO_TRANSLATED_CHUNK"] += 1
                    generation_asset_ids.append(translate_asset_id)

                    summary_ko_asset_id, summary_ko, summary_ko_cached = _resolve_or_create_summary_ko(
                        connection,
                        chunk=chunk,
                        source_fingerprint=source_fingerprint,
                        prompt_asset=prompts.summary_ko_asset,
                        prompt_text=prompts.summary_ko_text,
                        prompt_version_suffix="B",
                        source_text_ko=translated_chunk_ko,
                        client=summary_client,
                    )
                    if summary_ko_cached:
                        asset_cache_hits["KO_SUMMARY"] += 1
                    else:
                        asset_created["KO_SUMMARY"] += 1
                    generation_asset_ids.append(summary_ko_asset_id)
                elif generation_strategy in {"C", "D"}:
                    summary_ko_asset_id, summary_ko, summary_ko_cached = _resolve_or_create_summary_ko(
                        connection,
                        chunk=chunk,
                        source_fingerprint=source_fingerprint,
                        prompt_asset=prompts.summary_ko_asset,
                        prompt_text=prompts.summary_ko_text,
                        prompt_version_suffix=generation_strategy,
                        source_text_ko=en_summary,
                        client=summary_client,
                    )
                    if summary_ko_cached:
                        asset_cache_hits["KO_SUMMARY"] += 1
                    else:
                        asset_created["KO_SUMMARY"] += 1
                    generation_asset_ids.append(summary_ko_asset_id)

                if _find_cached_query(
                    connection,
                    table_name=raw_table_name,
                    synthetic_query_id=stable_query_id,
                    source_fingerprint=source_fingerprint,
                    prompt_template_version=query_prompt_asset.version,
                ):
                    _attach_cached_query(
                        connection,
                        table_name=raw_table_name,
                        synthetic_query_id=stable_query_id,
                        generation_method_id=generation_method_id,
                        generation_batch_id=generation_batch_id,
                        llm_provider=query_client.config.provider,
                        llm_model=query_client.config.model,
                        generation_asset_ids=generation_asset_ids,
                    )
                    _insert_source_link(
                        connection,
                        synthetic_query_id=stable_query_id,
                        source_doc_id=chunk.document_id,
                        source_chunk_id=chunk.chunk_id,
                        source_chunk_group_id=None,
                        source_role="primary",
                    )
                    reused_count += 1
                    continue

                query_payload = {
                    "chunk_id": chunk.chunk_id,
                    "document_id": chunk.document_id,
                    "title": chunk.title,
                    "product": chunk.product_name,
                    "version": chunk.version_label,
                    "original_chunk_en": chunk.chunk_text,
                    "extractive_summary_en": en_summary,
                    "translated_chunk_ko": translated_chunk_ko,
                    "extractive_summary_ko": summary_ko,
                    "glossary_terms_keep_english": chunk_glossary_terms,
                    "query_type": query_type,
                    "answerability_type": answerability_type,
                    "target_chunk_ids": target_chunk_ids,
                }
                query_response = _llm_json(
                    query_client,
                    prompt_text=query_prompt_text,
                    payload=query_payload,
                    response_schema=QUERY_BASE_RESPONSE_SCHEMA,
                    request_purpose="generate_query",
                    trace_id=f"query:{stable_query_id}",
                )
                query_text, extra_trace = _extract_query_text(
                    generation_strategy=generation_strategy,
                    query_type=query_type,
                    response=query_response,
                )
                if not query_text:
                    query_response = _llm_json(
                        query_client,
                        prompt_text=query_prompt_text,
                        payload={**query_payload, "retry_hint": "query_text_must_not_be_empty"},
                        response_schema=QUERY_BASE_RESPONSE_SCHEMA,
                        request_purpose="generate_query_retry",
                        trace_id=f"query:{stable_query_id}",
                    )
                    query_text, extra_trace = _extract_query_text(
                        generation_strategy=generation_strategy,
                        query_type=query_type,
                        response=query_response,
                    )
                if not query_text:
                    skipped_empty_count += 1
                    LOGGER.warning(
                        "Skip empty query output. chunk=%s strategy=%s query_type=%s",
                        chunk.chunk_id,
                        generation_strategy,
                        query_type,
                    )
                    continue

                normalized_query_text = _normalize_query_text(query_text)
                language_profile = _language_profile(generation_strategy, query_type)
                payload = {
                    "synthetic_query_id": stable_query_id,
                    "experiment_run_id": run_context.experiment_run_id,
                    "generation_method_id": generation_method_id,
                    "generation_batch_id": generation_batch_id,
                    "chunk_id_source": chunk.chunk_id,
                    "source_chunk_group_id": None,
                    "target_doc_id": chunk.document_id,
                    "target_chunk_ids": Jsonb(target_chunk_ids),
                    "answerability_type": answerability_type,
                    "query_text": query_text,
                    "normalized_query_text": normalized_query_text,
                    "query_language": "ko",
                    "language_profile": language_profile,
                    "query_type": query_type,
                    "generation_strategy": generation_strategy,
                    "prompt_asset_id": query_prompt_asset.prompt_asset_id,
                    "prompt_template_version": query_prompt_asset.version,
                    "prompt_version": query_prompt_asset.version,
                    "prompt_hash": query_prompt_asset.content_hash,
                    "source_summary": summary_ko if summary_ko else en_summary,
                    "source_fingerprint": source_fingerprint,
                    "source_chunk_ids": Jsonb(target_chunk_ids),
                    "glossary_terms": Jsonb(chunk_glossary_terms),
                    "llm_provider": query_client.config.provider,
                    "llm_model": query_client.config.model,
                    "generation_asset_ids": Jsonb(generation_asset_ids),
                    "llm_output": Jsonb(
                        {
                            "schema_version": "v1",
                            "response": query_response,
                            "query_type": query_type,
                            "answerability_type": answerability_type,
                            "trace": {
                                "en_summary": en_summary,
                                "ko_summary": summary_ko,
                                "translated_chunk_excerpt": translated_chunk_ko[:320],
                                **extra_trace,
                            },
                        }
                    ),
                    "metadata": Jsonb(
                        {
                            "query_type_label": QUERY_TYPE_LABELS_KO.get(query_type, query_type),
                            "title": chunk.title,
                            "product_name": chunk.product_name,
                            "version_label": chunk.version_label,
                            "generation_batch_id": generation_batch_id,
                            "source_fingerprint": source_fingerprint,
                        }
                    ),
                }
                _insert_query_row(connection, table_name=raw_table_name, payload=payload)
                _insert_source_link(
                    connection,
                    synthetic_query_id=stable_query_id,
                    source_doc_id=chunk.document_id,
                    source_chunk_id=chunk.chunk_id,
                    source_chunk_group_id=None,
                    source_role="primary",
                )
                generated_count += 1
                query_type_counter[query_type] += 1
                answerability_counter[answerability_type] += 1
                if len(generated_ids) < 20:
                    generated_ids.append(stable_query_id)

            if (chunk_index + 1) % llm_batch_size == 0:
                connection.commit()

        summary = {
            "experiment_key": config.experiment_key,
            "experiment_run_id": run_context.experiment_run_id,
            "generation_strategy": strategy,
            "source_id": source_id,
            "source_document_id": source_document_id,
            "max_total_queries": max_total_queries,
            "random_chunk_sampling": random_chunk_sampling,
            "llm": {
                "summary": {
                    "provider": summary_client.config.provider,
                    "model": summary_client.config.model,
                },
                "query": {
                    "provider": query_client.config.provider,
                    "model": query_client.config.model,
                },
                "translation": {
                    "provider": translate_client.config.provider,
                    "model": translate_client.config.model,
                },
            },
            "prompt_assets": {
                "summary_en": {
                    "id": prompts.summary_en_asset.prompt_name,
                    "version": prompts.summary_en_asset.version,
                    "hash": prompts.summary_en_asset.content_hash,
                    "asset_id": prompts.summary_en_asset.prompt_asset_id,
                },
                "summary_ko": {
                    "id": prompts.summary_ko_asset.prompt_name,
                    "version": prompts.summary_ko_asset.version,
                    "hash": prompts.summary_ko_asset.content_hash,
                    "asset_id": prompts.summary_ko_asset.prompt_asset_id,
                },
                "translate": {
                    "id": prompts.translate_asset.prompt_name,
                    "version": prompts.translate_asset.version,
                    "hash": prompts.translate_asset.content_hash,
                    "asset_id": prompts.translate_asset.prompt_asset_id,
                },
            },
            "chunks_processed": len(chunks),
            "generated_queries": generated_count,
            "reused_queries": reused_count,
            "skipped_empty_queries": skipped_empty_count,
            "query_type_distribution": dict(query_type_counter),
            "answerability_distribution": dict(answerability_counter),
            "asset_created": dict(asset_created),
            "asset_cache_hits": dict(asset_cache_hits),
            "preview_query_ids": generated_ids,
        }
        recorder.finish_run(run_context, status="completed", metrics=summary)
        connection.commit()
        return summary
    except Exception as exception:  # noqa: BLE001
        connection.rollback()
        LOGGER.exception("Synthetic query generation failed.")
        raise exception
    finally:
        connection.close()


def run_generation_from_env(experiment: str) -> dict[str, Any]:
    defaults = default_database_args()
    return run_generation(
        experiment=experiment,
        database_url=defaults["database_url"],
        db_host=defaults["db_host"],
        db_port=defaults["db_port"],
        db_name=defaults["db_name"],
        db_user=defaults["db_user"],
        db_password=defaults["db_password"],
    )
