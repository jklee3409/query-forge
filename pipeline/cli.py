from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from collectors.spring_docs_collector import collect_documents
from loaders.import_corpus_to_postgres import run_import
from preprocess.chunk_docs import build_chunks_and_glossary
from preprocess.normalize_docs import normalize_documents
from loaders.common import build_options


COMMANDS = (
    "collect-docs",
    "preprocess",
    "chunk-docs",
    "import-corpus",
    "generate-queries",
    "gate-queries",
    "build-memory",
    "build-eval-dataset",
    "eval-retrieval",
    "eval-answer",
)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Query Forge offline pipeline scaffold"
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    collect = subparsers.add_parser("collect-docs")
    collect.add_argument("--experiment", default="scaffold")
    collect.add_argument("--config-dir", default="configs/app/sources")
    collect.add_argument("--output", default="data/raw/spring_docs_raw.jsonl")
    collect.add_argument("--limit", type=int, default=None)
    collect.add_argument("--source-id", action="append", default=None)
    collect.add_argument("--show-examples", action="store_true")

    preprocess = subparsers.add_parser("preprocess")
    preprocess.add_argument("--experiment", default="scaffold")
    preprocess.add_argument("--input", default="data/raw/spring_docs_raw.jsonl")
    preprocess.add_argument("--output", default="data/processed/spring_docs_sections.jsonl")
    preprocess.add_argument("--limit", type=int, default=None)
    preprocess.add_argument("--show-examples", action="store_true")

    chunk_docs = subparsers.add_parser("chunk-docs")
    chunk_docs.add_argument("--experiment", default="scaffold")
    chunk_docs.add_argument("--input", default="data/processed/spring_docs_sections.jsonl")
    chunk_docs.add_argument("--output-chunks", default="data/processed/chunks.jsonl")
    chunk_docs.add_argument("--output-glossary", default="data/processed/glossary_terms.jsonl")
    chunk_docs.add_argument("--output-relations-sql", default="data/processed/chunk_neighbors.sql")
    chunk_docs.add_argument("--output-visualization", default="data/processed/chunking_visualization.md")
    chunk_docs.add_argument("--config", default="configs/app/chunking.yml")
    chunk_docs.add_argument("--limit-documents", type=int, default=None)
    chunk_docs.add_argument("--show-examples", action="store_true")

    import_corpus = subparsers.add_parser("import-corpus")
    import_corpus.add_argument("--experiment", default="scaffold")
    import_corpus.add_argument("--database-url", default=None)
    import_corpus.add_argument("--db-host", default="localhost")
    import_corpus.add_argument("--db-port", type=int, default=5432)
    import_corpus.add_argument("--db-name", default="query_forge")
    import_corpus.add_argument("--db-user", default="query_forge")
    import_corpus.add_argument("--db-password", default="query_forge")
    import_corpus.add_argument("--source-config-dir", default="configs/app/sources")
    import_corpus.add_argument("--raw-input", default="data/raw/spring_docs_raw.jsonl")
    import_corpus.add_argument("--sections-input", default="data/processed/spring_docs_sections.jsonl")
    import_corpus.add_argument("--chunks-input", default="data/processed/chunks.jsonl")
    import_corpus.add_argument("--glossary-input", default="data/processed/glossary_terms.jsonl")
    import_corpus.add_argument("--dry-run", action="store_true")
    import_corpus.add_argument("--batch-size", type=int, default=200)
    import_corpus.add_argument("--trigger-type", default="manual")
    import_corpus.add_argument("--created-by", default=None)
    import_corpus.add_argument("--run-type", default="import")
    import_corpus.add_argument("--source-id", action="append", default=None)
    import_corpus.add_argument("--document-id", action="append", default=None)
    import_corpus.add_argument("--log-level", default="INFO")

    for command in (
        "generate-queries",
        "gate-queries",
        "build-memory",
        "build-eval-dataset",
        "eval-retrieval",
        "eval-answer",
    ):
        subparser = subparsers.add_parser(command)
        subparser.add_argument("--experiment", default="scaffold")

    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()

    if args.command == "collect-docs":
        summary = collect_documents(
            config_dir=Path(args.config_dir),
            output_path=Path(args.output),
            limit=args.limit,
            source_ids=set(args.source_id) if args.source_id else None,
            show_examples=args.show_examples,
        )
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return 0

    if args.command == "preprocess":
        summary = normalize_documents(
            input_path=Path(args.input),
            output_path=Path(args.output),
            limit=args.limit,
            show_examples=args.show_examples,
        )
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return 0

    if args.command == "chunk-docs":
        summary = build_chunks_and_glossary(
            input_path=Path(args.input),
            output_chunks_path=Path(args.output_chunks),
            output_glossary_path=Path(args.output_glossary),
            output_relations_sql_path=Path(args.output_relations_sql),
            output_visualization_path=Path(args.output_visualization),
            config_path=Path(args.config),
            limit_documents=args.limit_documents,
            show_examples=args.show_examples,
        )
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return 0

    if args.command == "import-corpus":
        summary = run_import(build_options(args))
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return 0

    print(
        f"[TODO] '{args.command}' is not implemented in stage 2-3A. "
        f"experiment={args.experiment}",
        file=sys.stderr,
    )
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
