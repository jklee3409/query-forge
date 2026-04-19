# progress.md

## Overview
High-level progress tracking for the project.

## [2026-04-19] Session Summary (Session Start Checklist Rule Added)
- What was done: Added Section `4.5 Session Start Checklist (MANDATORY)` to `.codex/AGENTS.md` so implementation turns begin with a fixed checklist in the first working update.
- Key decisions: Checklist requires AGENTS re-check, root/directory progress review, and explicit plan to update `progress.md` after edits.
- Issues encountered: None.
- Next steps: Keep checklist wording synchronized with future process-rule changes.

## [2026-04-18] Session Summary (AGENTS RAG Performance Evaluation Constraint Added)
- What was done: Updated `.codex/AGENTS.md` with new Section `3.7 RAG End-to-End Performance Evaluation` to require quality+performance joint reporting in RAG experiments.
- Key decisions: Defined mandatory performance fields (total run latency, stage latency, mode latency avg/p95, rewrite overhead) as reproducibility constraints.
- Issues encountered: None.
- Next steps: Keep this section synchronized with future experiment-run schema changes.

## [2026-04-13] Session Summary (Skill Added)
- What was done: Added `.codex/skills/git-commit` and authored a reusable workflow for git-diff analysis, logical commit splitting, AngularJS commit typing, and unnecessary file exclusion.
- Key decisions: Embedded Korean + English technical commit message examples directly in `SKILL.md` to enforce output style consistently.
- Issues encountered: Regenerated `agents/openai.yaml` after interface validation and `$git-commit` prompt escaping corrections.
- Next steps: Keep this skill updated when repository-specific commit boundaries or exclusion patterns evolve.

---

## [2026-04-13] Session Summary
- What was done: `.codex/AGENTS.md`를 기준으로 디렉토리별 문서화 정책을 반영했다.
- Key decisions: 에이전트 제약, 파이프라인 순서, 진행 기록 규칙을 모든 문서 작성 기준으로 삼았다.
- Issues encountered: 루트 `AGENTS.md`가 없어 `.codex/AGENTS.md`를 기준 문서로 사용했다.
- Next steps: 에이전트 규칙 변경 시 본 디렉토리 문서와 루트 문서를 함께 업데이트한다.

---

## Notes
- Keep this file concise
- Only record important changes
