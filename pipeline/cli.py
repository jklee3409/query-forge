from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from collectors.spring_docs_collector import collect_documents
from preprocess.normalize_docs import normalize_documents


COMMANDS = (
    "collect-docs",
    "preprocess",
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

    print(
        f"[TODO] '{args.command}' is not implemented in stage 2-2. "
        f"experiment={args.experiment}",
        file=sys.stderr,
    )
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
