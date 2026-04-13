# progress.md

## Overview
High-level progress tracking for the project.

---

## [2026-04-13] Session Summary
- What was done: Read `.codex/AGENTS.md`, created root `index.md`/`progress.md`, and added `index.md`/`progress.md` for each major working directory (`.codex`, `backend`, `configs`, `data`, `docs`, `frontend`, `infra`, `pipeline`, `scripts`).
- Key decisions: Used `.codex/AGENTS.md` as the authoritative agent policy and documented each directory based on currently implemented files and execution flow.
- Issues encountered: Root `AGENTS.md` was not present; runtime/build artifact directories were excluded from documentation scope.
- Next steps: Maintain directory-level `index.md`/`progress.md` together with code/config changes in each affected directory.

## [2026-04-13] Session Summary (Raw Table Split Refactor)
- What was done: Reworked synthetic-query storage from single `synthetic_queries_raw` writes to strategy-specific writes (`synthetic_queries_raw_a/b/c/d`) and switched gating/memory/eval/admin-console/rag reads to split-backed source (`synthetic_queries_raw_all`).
- Key decisions: Added Flyway `V17__split_strategy_raw_tables_and_drop_legacy_raw.sql` to migrate data, introduce `synthetic_query_registry` for FK integrity, drop legacy `synthetic_queries_raw`, and create union view `synthetic_queries_raw_all`.
- Issues encountered: Existing workspace had unrelated modifications; this session avoided reverting non-target files and validated only touched paths.
- Next steps: Apply migration in target DB, then run admin GUI generation/gating smoke checks and verify A/B/C/D strategy counts independently.

## [2026-04-13] Session Summary (Root README Rewrite)
- What was done: Rewrote root `README.md` in Korean narrative form and aligned content with `.codex/AGENTS.md` requirements (project objective, research overview/details, methodology, and fixed end-to-end flow).
- Key decisions: Replaced the previous experiment-report style README with project-level guidance that emphasizes A/B/C/D strategy separation, selective/dynamic gating, and retrieval-aware evaluation dataset constraints.
- Issues encountered: Existing README content was not suitable as a stable root guide due to encoding/readability issues in terminal output.
- Next steps: Keep directory-level READMEs synchronized with implementation changes and expand per-module Korean documentation where legacy/default templates remain.

## [2026-04-13] Session Summary (Skill: git-commit)
- What was done: Created `.codex/skills/git-commit` using the `skill-creator` workflow, implemented a commit workflow based on `git diff`, and added AngularJS-style commit guidance with Korean + English technical message examples.
- Key decisions: Focused the skill on logical commit splitting, unnecessary file exclusion, and staged-diff verification before each commit.
- Issues encountered: Initial `openai.yaml` generation had a short-description length violation and `$git-commit` prompt escaping issue; regenerated metadata with valid interface values.
- Next steps: Use `$git-commit` in real commit sessions and refine exclusion heuristics if project-specific noise patterns are observed.

---

## Notes
- Keep this file concise
- Only record important changes
