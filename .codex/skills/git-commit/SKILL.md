---
name: git-commit
description: Analyze repository changes from git diff and produce clean, logically split commits with AngularJS-style commit conventions (feat, fix, docs, refactor, test, chore, perf, ci, build). Use when Codex needs to review unstaged/staged changes, exclude unnecessary files, and write commit messages using Korean + English technical terms.
---

# Git Commit

Use this workflow end-to-end whenever the user asks to "commit changes", "split commits", or "write commit messages".

## 1) Capture Change Snapshot

Run these commands first:

```bash
git status --short
git diff --name-status
git diff --staged --name-status
git diff --stat
git diff --staged --stat
```

Summarize changes by area (example: backend API, frontend UI, docs, tests, infra).

## 2) Exclude Unnecessary Files

Exclude files that are not required for the intended change unless the user explicitly requests them.

Default exclusion candidates:
- Local IDE/editor files (`.idea/`, `.vscode/`, swap/temp files)
- Build/output artifacts (`dist/`, `build/`, `coverage/`, cache files)
- Runtime logs and transient files (`*.log`, `tmp/`, generated reports not required)
- Secrets/local env files (`.env`, `.env.local`, key files)
- Unrelated formatting-only churn outside the target scope

If a file is ambiguous, ask before staging it.

## 3) Split Into Logical Commit Units

Create commit units by intent, not by file type.

Use these rules:
- Keep one primary intent per commit.
- Avoid mixing `feat` and `refactor` in one commit unless inseparable.
- Keep schema/migration + dependent code together when atomic deployment is required.
- Keep tests with the code they validate when possible.
- Keep docs with behavior/API changes they explain when tightly coupled.

Prepare a short commit plan before staging:
1. Commit type + scope
2. Files to include
3. Why this unit is independent

## 4) Stage and Verify Per Unit

For each unit:

```bash
git add <files...>
git diff --staged --stat
git diff --staged
```

Verify the staged diff only contains one intent. If mixed, unstage and split again.

## 5) Write AngularJS-Style Messages

Use format:

```text
<type>(<scope>): <subject>

<body>
```

Supported types:
- `feat`: new user-facing capability
- `fix`: bug fix
- `docs`: documentation only
- `style`: formatting only (no logic change)
- `refactor`: internal restructuring (no behavior change)
- `test`: tests added/updated
- `chore`: tooling or maintenance
- `perf`: performance improvement
- `ci`: CI workflow change
- `build`: build/dependency change

Message rules:
- Keep subject concise, imperative, and without period.
- Write subject/body with Korean + English technical terms.
- Explain `what + why` in body when change is non-trivial.
- Add `BREAKING CHANGE:` in body/footer when API/contract changes.

Examples:
- `feat(gating): 전략별 score threshold runtime 주입 지원`
- `fix(retrieval): chunk grounding 누락 시 fallback ranking 보정`
- `refactor(pipeline): query builder 모듈 분리로 coupling 감소`
- `docs(api): synthetic query endpoint usage 가이드 정리`

## 6) Commit Loop

Commit each unit sequentially:

```bash
git commit -m "<type>(<scope>): <subject>" -m "<body>"
git show --stat --oneline -1
```

Repeat until all planned units are committed.

## 7) Final Sanity Check

Run:

```bash
git status --short
git log --oneline -n 10
```

Confirm no accidental files were committed and commit ordering reads as a coherent change story.
