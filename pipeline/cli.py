from __future__ import annotations

import argparse
import sys


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

    for command in COMMANDS:
        subparser = subparsers.add_parser(command)
        subparser.add_argument("--experiment", default="scaffold")

    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    print(
        f"[TODO] '{args.command}' is not implemented in stage 2-1. "
        f"experiment={args.experiment}",
        file=sys.stderr,
    )
    return 2


if __name__ == "__main__":
    raise SystemExit(main())

