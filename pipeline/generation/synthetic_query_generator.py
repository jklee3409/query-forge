from __future__ import annotations

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
    from common.prompt_assets import PromptAsset, load_and_register_prompt
    from common.text_utils import extract_extractive_summary, naive_translate_to_korean
    from loaders.common import connect, default_database_args
except ModuleNotFoundError:  # pragma: no cover - direct module execution fallback
    from pipeline.common.corpus_shadow_sync import sync_shadow_tables
    from pipeline.common.experiment_config import ExperimentConfig, load_experiment_config
    from pipeline.common.experiment_run import ExperimentRunRecorder
    from pipeline.common.prompt_assets import PromptAsset, load_and_register_prompt
    from pipeline.common.text_utils import extract_extractive_summary, naive_translate_to_korean
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


def _weighted_choice(rng: random.Random, distribution: dict[str, float]) -> str:
    picks = list(distribution.items())
    roll = rng.random()
    cumulative = 0.0
    for key, weight in picks:
        cumulative += weight
        if roll <= cumulative:
            return key
    return picks[-1][0]


def _stable_id(parts: list[str]) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "|".join(parts)))


@dataclass(slots=True)
class ChunkRow:
    chunk_id: str
    document_id: str
    chunk_text: str
    title: str
    product_name: str
    version_label: str | None


def _load_chunks(
    connection: psycopg.Connection[Any],
    *,
    limit: int | None,
) -> list[ChunkRow]:
    statement = """
        SELECT c.chunk_id,
               c.document_id,
               c.chunk_text,
               d.title,
               c.product_name,
               c.version_label
        FROM corpus_chunks c
        JOIN corpus_documents d ON d.document_id = c.document_id
        ORDER BY c.document_id, c.chunk_index_in_document
    """
    with connection.cursor() as cursor:
        if limit:
            cursor.execute(statement + " LIMIT %s", (limit,))
        else:
            cursor.execute(statement)
        rows = cursor.fetchall()
    return [
        ChunkRow(
            chunk_id=str(row["chunk_id"]),
            document_id=str(row["document_id"]),
            chunk_text=str(row["chunk_text"]),
            title=str(row["title"]),
            product_name=str(row["product_name"]),
            version_label=row["version_label"],
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


def _pick_focus_term(chunk: ChunkRow, glossary_terms: list[str]) -> str:
    if glossary_terms:
        return glossary_terms[0]
    tokens = [token.strip(".,()[]") for token in chunk.chunk_text.split()[:20]]
    for token in tokens:
        if token and token[0].isalpha() and len(token) >= 4:
            return token
    return chunk.product_name


def _generate_english_query(query_type: str, focus_term: str, summary_en: str) -> str:
    if query_type == "definition":
        return f"What is {focus_term} in Spring and when should it be used?"
    if query_type == "reason":
        return f"Why is {focus_term} required in this Spring configuration?"
    if query_type == "procedure":
        return f"How can I configure {focus_term} step by step in Spring?"
    if query_type == "comparison":
        return f"What is the difference between {focus_term} and the related alternative in Spring?"
    if query_type == "short_user":
        return f"{focus_term} 설정 방법?"
    if query_type == "code_mixed":
        return f"{focus_term} 설정 시 required property가 뭐야?"
    return f"Then how does this apply to {focus_term} in the next step?"


def _generate_korean_query(
    query_type: str,
    focus_term: str,
    summary_ko: str,
    *,
    strategy: str,
) -> str:
    if query_type == "definition":
        return f"{focus_term}는 스프링에서 무엇이고 언제 써야 하나요?"
    if query_type == "reason":
        return f"이 문맥에서 왜 {focus_term}가 필요한가요?"
    if query_type == "procedure":
        return f"{focus_term}를 설정하는 절차를 단계별로 알려주세요."
    if query_type == "comparison":
        return f"{focus_term}와 유사한 다른 방식의 차이를 비교해 주세요."
    if query_type == "short_user":
        return f"{focus_term} 설정 방법?"
    if query_type == "code_mixed":
        return f"{focus_term} 설정할 때 default 값이 뭐예요?"
    if strategy == "C":
        return f"방금 설명한 내용을 기준으로 {focus_term}는 다음 단계에서 어떻게 연결되나요?"
    return f"이어서 {focus_term}를 적용하려면 무엇을 확인해야 하나요?"


def _make_strategy_text(
    *,
    strategy: str,
    query_type: str,
    chunk: ChunkRow,
    summary_prompt: PromptAsset,
    query_prompt: PromptAsset,
    glossary_terms: list[str],
) -> tuple[str, str, dict[str, Any]]:
    summary_en = extract_extractive_summary(chunk.chunk_text, max_sentences=2)
    focus_term = _pick_focus_term(chunk, glossary_terms)
    llm_trace: dict[str, Any] = {
        "strategy": strategy,
        "summary_prompt_version": summary_prompt.version,
        "query_prompt_version": query_prompt.version,
        "summary_en": summary_en,
        "focus_term": focus_term,
    }

    if strategy == "A":
        query_en = _generate_english_query(query_type, focus_term, summary_en)
        query_ko = naive_translate_to_korean(query_en)
        llm_trace["query_en"] = query_en
        return query_ko, summary_en, llm_trace

    if strategy == "B":
        translated_chunk_ko = naive_translate_to_korean(chunk.chunk_text)
        summary_ko = extract_extractive_summary(translated_chunk_ko, max_sentences=2)
        query_ko = _generate_korean_query(query_type, focus_term, summary_ko, strategy=strategy)
        llm_trace["translated_chunk_ko"] = translated_chunk_ko[:400]
        llm_trace["summary_ko"] = summary_ko
        return query_ko, summary_ko, llm_trace

    summary_ko = naive_translate_to_korean(summary_en)
    query_ko = _generate_korean_query(query_type, focus_term, summary_ko, strategy=strategy)
    llm_trace["summary_ko"] = summary_ko
    llm_trace["glossary_terms"] = glossary_terms[:10]
    return query_ko, summary_ko, llm_trace


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
            source_chunk_ids,
            glossary_terms,
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
            %(source_chunk_ids)s,
            %(glossary_terms)s,
            %(llm_output)s,
            %(metadata)s
        )
        ON CONFLICT (synthetic_query_id) DO UPDATE
        SET query_text = EXCLUDED.query_text,
            target_chunk_ids = EXCLUDED.target_chunk_ids,
            source_summary = EXCLUDED.source_summary,
            glossary_terms = EXCLUDED.glossary_terms,
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


def _normalize_query_text(text: str) -> str:
    return " ".join(text.strip().lower().split())


def _language_profile(strategy: str, query_type: str) -> str:
    if strategy == "D" or query_type == "code_mixed":
        return "code_mixed"
    return "ko"


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


def _resolve_prompt_files(
    config: ExperimentConfig,
    prompt_root: Path,
) -> tuple[Path, Path]:
    summary_prompt_path = prompt_root / "summary_extraction" / "extractive_summary_v1.md"
    query_prompt_name = f"gen_{config.generation_strategy.lower()}_v1.md"
    query_prompt_path = prompt_root / "query_generation" / query_prompt_name
    if not query_prompt_path.exists():
        query_prompt_path = prompt_root / "query_generation" / "gen_c_v1.md"
    return summary_prompt_path, query_prompt_path


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
            },
            run_label="generate-queries",
        )

        summary_prompt_path, query_prompt_path = _resolve_prompt_files(config, prompt_root)
        summary_prompt = load_and_register_prompt(connection, summary_prompt_path)
        query_prompt = load_and_register_prompt(connection, query_prompt_path)

        chunks = _load_chunks(connection, limit=config.limit_chunks)
        relations = _load_relations(connection)
        glossary_by_doc = _load_glossary(connection)
        rng = random.Random(config.random_seed)

        strategy = config.generation_strategy
        generation_batch_id = str(config.raw.get("generation_batch_id") or "").strip() or None
        method_id_cache: dict[str, str | None] = {
            strategy: _resolve_generation_method_id(connection, strategy)
        }
        strategy_rows = 0
        query_type_counter: Counter[str] = Counter()
        answerability_counter: Counter[str] = Counter()
        generated_ids: list[str] = []

        for chunk in chunks:
            chunk_glossary_terms = glossary_by_doc.get(chunk.document_id, [])[:12]
            base_count = max(
                1,
                int(round(config.avg_queries_per_chunk + rng.uniform(-0.9, 0.9))),
            )
            for query_index in range(base_count):
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
                if generation_strategy not in method_id_cache:
                    method_id_cache[generation_strategy] = _resolve_generation_method_id(connection, generation_strategy)
                query_text, source_summary, trace_payload = _make_strategy_text(
                    strategy=generation_strategy if generation_strategy != "D" else "C",
                    query_type=query_type,
                    chunk=chunk,
                    summary_prompt=summary_prompt,
                    query_prompt=query_prompt,
                    glossary_terms=chunk_glossary_terms,
                )

                if generation_strategy == "D":
                    query_text = query_text.replace("설정", "setting")
                    query_text = query_text.replace("방법", "how to")
                    trace_payload["code_mixed_enabled"] = True

                normalized_query_text = _normalize_query_text(query_text)
                language_profile = _language_profile(generation_strategy, query_type)
                synthetic_query_id = _stable_id(
                    [
                        config.experiment_key,
                        run_context.experiment_run_id,
                        generation_strategy,
                        chunk.chunk_id,
                        str(query_index),
                        query_type,
                        "-".join(target_chunk_ids),
                    ]
                )

                payload = {
                    "synthetic_query_id": synthetic_query_id,
                    "experiment_run_id": run_context.experiment_run_id,
                    "generation_method_id": method_id_cache.get(generation_strategy),
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
                    "prompt_asset_id": query_prompt.prompt_asset_id,
                    "prompt_template_version": query_prompt.version,
                    "prompt_version": query_prompt.version,
                    "prompt_hash": query_prompt.content_hash,
                    "source_summary": source_summary,
                    "source_chunk_ids": Jsonb(target_chunk_ids),
                    "glossary_terms": Jsonb(chunk_glossary_terms),
                    "llm_output": Jsonb(
                        {
                            "queries": [
                                {
                                    "text": query_text,
                                    "query_type": query_type,
                                    "language_profile": language_profile,
                                    "grounding_terms": chunk_glossary_terms[:8],
                                    "notes": "generated by strategy scaffold",
                                }
                            ],
                            "trace": trace_payload,
                        }
                    ),
                    "metadata": Jsonb(
                        {
                            "query_type_label": QUERY_TYPE_LABELS_KO.get(query_type, query_type),
                            "title": chunk.title,
                            "product_name": chunk.product_name,
                            "version_label": chunk.version_label,
                            "summary_prompt_version": summary_prompt.version,
                            "generation_batch_id": generation_batch_id,
                        }
                    ),
                }

                _insert_query_row(connection, table_name="synthetic_queries_raw", payload=payload)
                _insert_source_link(
                    connection,
                    synthetic_query_id=synthetic_query_id,
                    source_doc_id=chunk.document_id,
                    source_chunk_id=chunk.chunk_id,
                    source_chunk_group_id=None,
                    source_role="primary",
                )
                strategy_rows += 1
                query_type_counter[query_type] += 1
                answerability_counter[answerability_type] += 1
                if len(generated_ids) < 20:
                    generated_ids.append(synthetic_query_id)

        summary = {
            "experiment_key": config.experiment_key,
            "experiment_run_id": run_context.experiment_run_id,
            "generation_strategy": strategy,
            "prompt_assets": {
                "summary": {
                    "id": summary_prompt.prompt_name,
                    "version": summary_prompt.version,
                    "hash": summary_prompt.content_hash,
                    "asset_id": summary_prompt.prompt_asset_id,
                },
                "query": {
                    "id": query_prompt.prompt_name,
                    "version": query_prompt.version,
                    "hash": query_prompt.content_hash,
                    "asset_id": query_prompt.prompt_asset_id,
                },
            },
            "chunks_processed": len(chunks),
            "generated_queries": strategy_rows,
            "query_type_distribution": dict(query_type_counter),
            "answerability_distribution": dict(answerability_counter),
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
