# Pipeline Orchestration

`2-3B`에서는 Spring Boot가 Python pipeline을 orchestration하고, PostgreSQL `corpus_runs / corpus_run_steps`를 공통 run ledger로 사용한다.

## 핵심 구조

1. 관리자가 `/admin` 또는 `/admin/ingest-wizard`에서 collect/normalize/chunk/glossary/import/full-ingest를 시작한다.
2. Spring Boot `PipelineAdminService`가 `corpus_runs`에 run을 만들고 `corpus_run_steps`를 `queued` 상태로 생성한다.
3. 단일 worker executor가 run을 순차 실행한다.
4. 각 step은 `ProcessBuilder` 기반 `SubprocessPipelineCommandRunner`로 `python pipeline/cli.py ...`를 호출한다.
5. stdout/stderr는 `data/logs/admin-pipeline/{runId}/` 아래 파일로 저장되고, step row에는 log path/excerpt가 기록된다.
6. step 성공 시 stdout JSON summary를 `metrics_json`으로 저장한다.
7. 실패 또는 cancel 시 step/run 상태를 즉시 DB에 반영한다.

## run / step 상태 모델

- run status: `queued`, `running`, `success`, `failed`, `cancelled`
- step status: `queued`, `running`, `success`, `failed`, `cancelled`
- `cancel_requested_at`으로 cancel 요청 시점을 기록
- `command_line`, `stdout_log_path`, `stderr_log_path`, `stdout_excerpt`, `stderr_excerpt`로 UI 로그 뷰어를 지원

## 중복 실행 방지

- 현재 구현은 안전성을 위해 동시에 하나의 pipeline run만 허용한다.
- `corpus_runs`에 `queued/running` run이 있으면 새 실행 요청은 `409 Conflict`로 거절된다.
- 이유: collect/normalize/chunk/glossary artifact가 동일 경로를 공유하므로 동시 실행 시 overwrite 위험이 크다.

## run_id 연결 방식

- Spring Boot run이 최상위 run이다.
- `import-corpus`는 `--external-run-id`를 받아 별도 nested run을 만들지 않고 상위 run_id를 그대로 `import_run_id`로 사용한다.
- collect/normalize/chunk/glossary도 `--run-id`를 받아 stdout summary에 동일 run 맥락을 남긴다.

## partial / scoped 실행

- 문서 상세의 `Re-normalize`, `Re-chunk`는 document scope 기반 temp workspace를 사용한다.
- temp artifact는 `data/tmp/admin-runs/{runId}/` 아래에 생성된다.
- full ingest는 canonical artifact 경로(`data/raw`, `data/processed`)를 사용한다.

## glossary 단독 실행

- 기존 `chunk_docs.py`의 glossary extractor를 재사용하는 `pipeline/preprocess/extract_glossary.py`를 추가했다.
- `/api/admin/pipeline/glossary`와 wizard step 5는 이 wrapper를 호출한다.
## document artifact store

- 관리자 GUI pipeline 은 canonical JSONL 을 직접 source of truth 로 쓰지 않고 `data/artifacts/corpus-docs/{sourceId}/{versionLabel}/{documentId}/` 를 기준 저장소로 사용한다.
- 문서 디렉터리에는 `manifest.json`, `raw.json`, `sections.jsonl`, `chunks.jsonl`, `glossary_terms.jsonl` 이 저장된다.
- `manifest.json` 은 각 단계 checksum 과 upstream checksum 을 기록해서 이미 raw 가 같으면 normalize 를 다시 돌리지 않고, sections 가 같으면 chunk/glossary 를 다시 돌리지 않는다.
- `data/raw/*.jsonl`, `data/processed/*.jsonl` 은 artifact store 에서 합쳐서 재생성되는 aggregate snapshot 이다.
- full ingest 에서 `chunk` step 이 glossary artifact 를 최신 상태로 만들었다면 뒤의 `glossary` step 은 자동으로 skip 된다.
