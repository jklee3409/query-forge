#!/usr/bin/env bash
set -euo pipefail

python pipeline/cli.py import-corpus "$@"
