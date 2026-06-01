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
EN_TARGET_METHOD = "E"
HANGUL_RE = re.compile(r"[가-힣]")


SPRING_EN_TRANSLATIONS: tuple[str, ...] = (
    "Security digest authentication filter use points?",
    "How is security usually used?",
    "Points when using two forwarded headers together?",
    "Points when using Java persistence methods together?",
    "What is the schema application order?",
    "Points when using a synchronous call client and a new client together?",
    "External authentication or login, which fits?",
    "Example of using an access token response converter?",
    "What is the latency application order?",
    "Quick interface summary?",
    "How is expression language usually used?",
    "When is security used?",
    "How to use the web test client?",
    "Points when using the latest common module version?",
    "Web request method and service, which fits?",
    "Property path factory bean usage points?",
    "How to use security?",
    "Java persistence and synchronous behavior, which fits?",
    "Quick summary of aspect-oriented programming?",
    "What is the page model?",
    "How to configure a data transfer object?",
    "How is execution usually handled in practice?",
    "What is Java messaging?",
    "Entity essentials only?",
    "Query condition annotation essentials only?",
    "Example of using page tests together?",
    "Quick security summary?",
    "Quick inversion of control summary?",
    "What is a registered client and what are the usage points?",
    "What is the object relational mapping application order?",
    "Which cloud deployment platform fits?",
    "Example of exchange calls and asynchronous exchange?",
    "Quick authentication metadata summary?",
    "When use an execution permission manager?",
    "Management attribute annotation essentials only?",
    "New request client customization points?",
    "What is the web model controller application order?",
    "Token issuer reactive authentication manager selector usage points?",
    "How to use the external authentication authorized client manager?",
    "Example using precompilation and reflection together?",
    "One-line summary of proxy-capable annotation?",
    "Java persistence essentials only?",
    "How to configure security?",
    "Quick Kotlin summary?",
    "How to configure an application event publisher?",
    "Repository method context usage points?",
    "Proxy factory bean essentials only?",
    "Web client response conversion points?",
    "How to use a distributed transaction manager?",
    "Example of sending observation data?",
    "Example used in the latest security version?",
    "Summary of enabling a WebSocket message broker?",
    "Repository interface example?",
    "How is security usually used in practice?",
    "Example of using messaging with document configuration?",
    "Delete handling example?",
    "Quick environment object summary?",
    "What is the expression language application order?",
    "Web client essentials only?",
    "Quick test summary?",
    "Mock security request post-processing essentials only?",
    "Quick external authentication summary?",
    "Quick example matcher summary?",
    "One-line summary of enabling multipart request handling?",
    "Quick Java persistence summary?",
    "What is the security application order?",
    "Security essentials only?",
    "What is the assertion library application order?",
    "Example of using Kotlin coroutines together?",
    "How to configure Java persistence?",
    "What is the scheduled execution application order?",
    "Points when using web model controller and reactive web together?",
    "How to configure path matching strategy?",
    "When use a pointcut expression?",
    "Points when using inner beans together?",
    "Quick precompilation summary?",
    "What is a platform transaction manager?",
    "Points when using import annotations together?",
    "Quick multipart form data summary?",
    "What is database initialization annotation?",
)


POSTGRESQL_EN_TRANSLATIONS: tuple[str, ...] = (
    "Who can be the object superuser?",
    "Is the current transaction committed?",
    "Where to see query results?",
    "What connection option should be added?",
    "Data directory path issue?",
    "Where are used parameters shown?",
    "What changes in value options?",
    "Where does query output go?",
    "Close a cursor on a remote database?",
    "What return value comes back?",
    "Result set information?",
    "Check database?",
    "What happens if rows are called again?",
    "Does the savepoint stay valid?",
    "Create a result as a new table?",
    "Trigger definition extension dependency?",
    "Result column metadata?",
    "Current connection database name?",
    "Connection when calling a function?",
    "How to avoid an error?",
    "Subsequent work not possible?",
    "Object name privilege?",
    "Handling link-symbol-like entries?",
    "When a file cannot be written?",
    "Handler validator option privilege?",
    "Foreign key delete behavior?",
    "Revoke privileges on contained objects?",
    "Where is the label provider module?",
    "Selection preference criteria?",
    "Table column privilege?",
    "Restore table options?",
    "Database server status?",
    "Compression level slot?",
    "Where is the output file created?",
    "Generate default key values?",
    "Move row-set cursor?",
    "Connection with a specified name?",
    "Remove argument mode and argument type?",
    "Refresh current collation rules?",
    "Current value of runtime parameters?",
    "Need a superuser command?",
    "Asynchronous query progress?",
    "Execute literal strings?",
    "Security definer restrictions?",
    "Purpose of table access?",
    "Are query rows stored?",
    "Standby server behavior difference?",
    "Effect of query mode?",
    "Pattern argument expansion?",
    "Parameter default value?",
    "Move from a row?",
    "What is the table name?",
    "Oldest transaction value?",
    "Delete user-owned objects?",
    "When to raise a notification?",
    "Rename column?",
    "Change new owner and new schema?",
    "Background worker status?",
    "Terminate existing connections?",
    "Where is the command history file?",
    "Structured query language reserved words?",
    "Difference between privilege and label?",
    "Read results from an active pipeline?",
    "Is this the right helper command for creating a database?",
    "Frame clause current row?",
    "System catalog caution?",
    "Show connection status in prompt?",
    "Turn off page skipping behavior?",
    "Range integer distribution?",
    "Is transaction safety supported?",
    "Ignore database in connection string?",
    "View file names?",
    "Convert identifier defaults?",
    "Restore partition hierarchy?",
    "What is the arbiter constraint?",
    "Where is the synthetic full backup?",
    "Debug level logs?",
    "Locking clause options?",
    "Client session variables?",
    "Output database settings?",
)


KUBERNETES_EN_TRANSLATIONS: tuple[str, ...] = (
    "Pod lifecycle phases?",
    "Pod resource requests limits?",
    "ResourceQuota namespace limits?",
    "Deployment rollout updates?",
    "StatefulSet identity storage?",
    "Job completion failure handling?",
    "Service selector ClusterIP?",
    "Ingress routing rules?",
    "NetworkPolicy pod isolation?",
    "PV PVC binding reclaim?",
    "Node status heartbeat?",
    "Taint toleration scheduling?",
    "Default scheduler selection?",
    "HPA scale calculation?",
    "Admission controller order?",
    "Dynamic admission webhooks?",
    "Authentication user identity?",
    "Authorization modes?",
    "RBAC RoleBinding permissions?",
    "Cluster initialization phases?",
    "CustomResource CRD difference?",
    "Access Kubernetes API from Pod?",
    "HostAliases hosts file entries?",
    "Sidecar container behavior?",
    "When use static Pods?",
    "Pod Security Standards profiles?",
    "Pod Security Admission labels?",
    "PodDisruptionBudget setup?",
    "Liveness readiness startup probe differences?",
    "Probe status check setup?",
    "CPU requests limits assign?",
    "Memory requests limits assign?",
    "Config map purpose?",
    "Use config map from a Pod?",
    "Secret sensitive data storage?",
    "Create secret data from the command line?",
    "Service account Pod identity?",
    "Set Pod service account name?",
    "Labels and selectors difference?",
    "When use annotations?",
    "Namespace isolation scope?",
    "Share a cluster with namespaces?",
    "Resource quota API fields?",
    "Run stateless deployment?",
    "Replica set role?",
    "Daemon set on every node?",
    "Create basic daemon set?",
    "Cron job schedule behavior?",
    "Finished job automatic cleanup?",
    "Frontend and backend service connection?",
    "Service and Pod name resolution?",
    "Why is an ingress controller needed?",
    "Network policy declaration example?",
    "Cluster networking requirements?",
    "Storage class provisioner setup?",
    "When use dynamic storage provisioning?",
    "Temporary volume types?",
    "Combine projected volume sources?",
    "Node status conditions?",
    "Node pressure eviction behavior?",
    "How to assign Pods to nodes?",
    "Required and preferred node affinity difference?",
    "Topology spread constraints setup?",
    "Scheduler node scoring percentage setup?",
    "HPA walkthrough metrics check?",
    "Node autoscaling components?",
    "Admission webhook good practices?",
    "RBAC good practices?",
    "Certificate signing requests?",
    "Cluster certificate management?",
    "Node join token?",
    "Cluster upgrade procedure?",
    "Node agent authentication and authorization setup?",
    "Container runtime requirements?",
    "Runtime class usage?",
    "API priority and fairness behavior?",
    "Eviction API behavior?",
    "Extend the API with custom resources?",
    "Custom resource definition spec?",
    "Limit storage consumption?",
)


@dataclass(frozen=True, slots=True)
class DomainSpec:
    name: str
    ko_dataset_id: str
    ko_dataset_key: str
    ko_file: Path
    en_dataset_key: str
    en_dataset_name: str
    en_description: str
    en_output_file: Path
    en_sample_prefix: str
    translations: tuple[str, ...]

    @property
    def en_dataset_id(self) -> str:
        return _dataset_id(self.en_dataset_key)


DOMAIN_SPECS: tuple[DomainSpec, ...] = (
    DomainSpec(
        name="spring",
        ko_dataset_id="57f313dd-461d-561d-9453-0f8e2e179b27",
        ko_dataset_key="spring_kr_rewrite_challenge_80",
        ko_file=REPO_ROOT / "data" / "eval" / "spring_kr_rewrite_challenge_80.jsonl",
        en_dataset_key="spring_en_rewrite_challenge_80",
        en_dataset_name="Spring EN Rewrite Challenge 80",
        en_description=(
            "English companion to Spring KR Rewrite Challenge 80. Only the user query is translated "
            "from the Korean challenge row; grounding and sample order are preserved."
        ),
        en_output_file=REPO_ROOT / "data" / "eval" / "spring_en_rewrite_challenge_80.jsonl",
        en_sample_prefix="spring-en-rewrite-challenge",
        translations=SPRING_EN_TRANSLATIONS,
    ),
    DomainSpec(
        name="postgresql",
        ko_dataset_id="0a8a0077-7f63-5f6d-b19d-71ae3f137733",
        ko_dataset_key="postgresql_kr_rewrite_challenge_80",
        ko_file=REPO_ROOT / "data" / "eval" / "postgresql_kr_rewrite_challenge_80.jsonl",
        en_dataset_key="postgresql_en_rewrite_challenge_80",
        en_dataset_name="PostgreSQL EN Rewrite Challenge 80",
        en_description=(
            "English companion to PostgreSQL KR Rewrite Challenge 80. Only the user query is translated "
            "from the Korean challenge row; grounding and sample order are preserved."
        ),
        en_output_file=REPO_ROOT / "data" / "eval" / "postgresql_en_rewrite_challenge_80.jsonl",
        en_sample_prefix="postgresql-en-rewrite-challenge",
        translations=POSTGRESQL_EN_TRANSLATIONS,
    ),
    DomainSpec(
        name="kubernetes",
        ko_dataset_id="c61421b4-6154-563a-b71a-fdef5f254b6e",
        ko_dataset_key="kubernetes_kr_rewrite_challenge_80",
        ko_file=REPO_ROOT / "data" / "eval" / "kubernetes_kr_rewrite_challenge_80.jsonl",
        en_dataset_key="kubernetes_en_rewrite_challenge_80",
        en_dataset_name="Kubernetes EN Rewrite Challenge 80",
        en_description=(
            "English companion to Kubernetes KR Rewrite Challenge 80. Only the user query is translated "
            "from the Korean challenge row; grounding and sample order are preserved."
        ),
        en_output_file=REPO_ROOT / "data" / "eval" / "kubernetes_en_rewrite_challenge_80.jsonl",
        en_sample_prefix="kubernetes-en-rewrite-challenge",
        translations=KUBERNETES_EN_TRANSLATIONS,
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


def _rel_path(path: Path) -> str:
    return str(path.relative_to(REPO_ROOT)).replace("\\", "/")


def _select_specs(names: set[str] | None) -> list[DomainSpec]:
    if not names:
        return list(DOMAIN_SPECS)
    selected = [spec for spec in DOMAIN_SPECS if spec.name in names]
    missing = sorted(names - {spec.name for spec in selected})
    if missing:
        raise RuntimeError(f"Unknown domain names: {', '.join(missing)}")
    return selected


def _build_en_rows(spec: DomainSpec) -> list[dict[str, Any]]:
    ko_rows = _load_jsonl(spec.ko_file)
    if len(ko_rows) != 80:
        raise RuntimeError(f"{spec.name} KO row count mismatch: {len(ko_rows)}")
    if len(spec.translations) != len(ko_rows):
        raise RuntimeError(f"{spec.name} translation count mismatch: {len(spec.translations)} != {len(ko_rows)}")

    now = datetime.now(timezone.utc).isoformat()
    rows: list[dict[str, Any]] = []
    for index, (ko_row, translation) in enumerate(zip(ko_rows, spec.translations), start=1):
        metadata = dict(ko_row.get("metadata") or {})
        metadata.update(
            {
                "updated_at": now,
                "dataset_key": spec.en_dataset_key,
                "dataset_profile": DATASET_PROFILE,
                "query_style": "short_user",
                "query_language": "en",
                "target_method": EN_TARGET_METHOD,
                "paired_ko_dataset_id": spec.ko_dataset_id,
                "paired_ko_dataset_key": spec.ko_dataset_key,
                "paired_ko_sample_id": ko_row["sample_id"],
                "paired_user_query_ko": ko_row.get("user_query_ko"),
                "paired_ko_target_method": ko_row.get("target_method"),
                "source_artifact": _rel_path(spec.ko_file),
                "query_translation_policy": "translate only the Korean user query; preserve grounding and row order",
                "evaluation_focus": EVALUATION_FOCUS,
            }
        )
        rows.append(
            {
                "sample_id": f"{spec.en_sample_prefix}-{index:03d}",
                "split": ko_row.get("split") or "test",
                "query_language": "en",
                "user_query_ko": "",
                "user_query_en": translation,
                "dialog_context": ko_row.get("dialog_context") or {},
                "expected_doc_ids": ko_row.get("expected_doc_ids") or [],
                "expected_chunk_ids": ko_row.get("expected_chunk_ids") or [],
                "expected_answer_key_points": ko_row.get("expected_answer_key_points") or [],
                "query_category": ko_row.get("query_category") or "short_user",
                "difficulty": ko_row.get("difficulty") or "hard",
                "single_or_multi_chunk": ko_row.get("single_or_multi_chunk") or "single",
                "source_product": ko_row.get("source_product"),
                "source_version_if_available": ko_row.get("source_version_if_available"),
                "target_method": EN_TARGET_METHOD,
                "evaluation_focus": EVALUATION_FOCUS,
                "metadata": metadata,
            }
        )
    return rows


def _validate_en_rows(spec: DomainSpec, rows: list[dict[str, Any]]) -> dict[str, Any]:
    issues: list[str] = []
    ko_rows = _load_jsonl(spec.ko_file)
    if len(rows) != 80:
        issues.append(f"row count mismatch: {len(rows)} != 80")

    queries = [str(row.get("user_query_en") or "") for row in rows]
    duplicate_queries = [query for query, count in Counter(queries).items() if count > 1]
    if duplicate_queries:
        issues.append(f"duplicate English queries: {duplicate_queries[:5]}")

    hangul_query_ids: list[str] = []
    empty_query_ids: list[str] = []
    changed_grounding_ids: list[str] = []
    for row, ko_row in zip(rows, ko_rows):
        sample_id = str(row["sample_id"])
        query = str(row.get("user_query_en") or "")
        if not query.strip():
            empty_query_ids.append(sample_id)
        if HANGUL_RE.search(query) or HANGUL_RE.search(str(row.get("user_query_ko") or "")):
            hangul_query_ids.append(sample_id)
        for field in ("expected_doc_ids", "expected_chunk_ids", "expected_answer_key_points"):
            if row.get(field) != ko_row.get(field):
                changed_grounding_ids.append(sample_id)
        if row.get("query_language") != "en" or row.get("user_query_ko") != "":
            issues.append(f"{sample_id}: invalid English query fields")

    if hangul_query_ids:
        issues.append(f"English rows containing Hangul: {hangul_query_ids[:10]}")
    if empty_query_ids:
        issues.append(f"empty English queries: {empty_query_ids[:10]}")
    if changed_grounding_ids:
        issues.append(f"changed grounding rows: {changed_grounding_ids[:10]}")

    return {
        "status": "pass" if not issues else "fail",
        "issues": issues,
        "dataset_key": spec.en_dataset_key,
        "dataset_id": spec.en_dataset_id,
        "paired_ko_dataset_key": spec.ko_dataset_key,
        "paired_ko_dataset_id": spec.ko_dataset_id,
        "row_count": len(rows),
        "hangul_query_count": len(hangul_query_ids),
        "single_multi_distribution": dict(Counter(str(row["single_or_multi_chunk"]) for row in rows)),
        "target_method_distribution": dict(Counter(str(row["target_method"]) for row in rows)),
    }


def _fetch_ko_domain_id(connection: psycopg.Connection[Any], spec: DomainSpec) -> str | None:
    with connection.cursor() as cursor:
        cursor.execute(
            "SELECT domain_id::text FROM eval_dataset WHERE dataset_id = %s",
            (spec.ko_dataset_id,),
        )
        row = cursor.fetchone()
    return str(row[0]) if row and row[0] else None


def _upsert_dataset(connection: psycopg.Connection[Any], spec: DomainSpec, rows: list[dict[str, Any]]) -> None:
    now = datetime.now(timezone.utc).isoformat()
    domain_id = _fetch_ko_domain_id(connection, spec)
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
                spec.en_dataset_id,
                spec.en_dataset_key,
                spec.en_dataset_name,
                spec.en_description,
                VERSION_LABEL,
                "test_only",
                len(rows),
                Jsonb(dict(Counter(str(row["query_category"]) for row in rows))),
                Jsonb(dict(Counter(str(row["single_or_multi_chunk"]) for row in rows))),
                Jsonb(
                    {
                        "dataset_profile": DATASET_PROFILE,
                        "query_language": "en",
                        "target_method": EN_TARGET_METHOD,
                        "paired_ko_dataset_id": spec.ko_dataset_id,
                        "paired_ko_dataset_key": spec.ko_dataset_key,
                        "source_file": _rel_path(spec.ko_file),
                        "source_dataset_preserved": True,
                        "query_translation_policy": (
                            "translate only the Korean user query; preserve grounding and row order"
                        ),
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

        cursor.execute("DELETE FROM eval_dataset_item WHERE dataset_id = %s", (spec.en_dataset_id,))
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
                    spec.en_dataset_id,
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
            rows = _build_en_rows(spec)
            validation = _validate_en_rows(spec, rows)
            if validation["status"] != "pass":
                raise RuntimeError(json.dumps(validation, ensure_ascii=False, indent=2))
            _write_jsonl(spec.en_output_file, rows)
            if connection is not None:
                _upsert_dataset(connection, spec, rows)
            results.append(
                {
                    "domain": spec.name,
                    "language": "en",
                    "dataset_key": spec.en_dataset_key,
                    "dataset_id": spec.en_dataset_id,
                    "output_file": _rel_path(spec.en_output_file),
                    "paired_ko_dataset_id": spec.ko_dataset_id,
                    "paired_ko_dataset_key": spec.ko_dataset_key,
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
    parser = argparse.ArgumentParser(description="Build translated English companions for rewrite challenge datasets.")
    parser.add_argument("--domain", action="append", choices=[spec.name for spec in DOMAIN_SPECS])
    parser.add_argument(
        "--report-file",
        default=str(REPO_ROOT / "data" / "reports" / "rewrite_challenge_80_en_audit_2026-06-01.json"),
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
