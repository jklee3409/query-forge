# Admin Backoffice

`2-3B` 단계에서 추가된 Spring Boot 기반 백오피스 GUI는 corpus 구축과 검수를 하나의 운영 화면에서 처리한다.

## 목적

- Spring 문서 수집, 정제, chunking, glossary 추출, PostgreSQL import를 웹에서 직접 실행
- corpus 상태와 pipeline run 상태를 한눈에 확인
- 문서 raw/cleaned, chunk boundary, glossary evidence를 운영자가 직접 검수

## 화면 목록

- `/admin`: 대시보드
- `/admin/sources`: source 관리 및 collect 실행
- `/admin/runs`: pipeline run 목록
- `/admin/runs/{runId}`: run 상세, step 상태, stdout/stderr 로그
- `/admin/documents`: 문서 목록
- `/admin/documents/{documentId}`: 문서 상세, raw vs cleaned, sections/chunks, run history
- `/admin/chunks`: chunk 목록
- `/admin/chunks/{chunkId}`: chunk 상세, prev/current/next, relation list
- `/admin/glossary`: glossary 목록
- `/admin/glossary/{termId}`: glossary 상세, policy 수정, alias CRUD, evidence
- `/admin/ingest-wizard`: source 선택부터 full ingest 실행까지 안내형 마법사
- `/admin/experiments`: 합성 질의 생성/게이팅/메모리/평가 실행 및 리포트 모니터링

## UI 설계 포인트

- 좌측 고정 sidebar + 상단 sticky topbar + 우측 content 영역
- light mode 기준 neutral gray + green accent
- 표 중심의 정보 밀도 높은 레이아웃
- 긴 ID/URL/path는 ellipsis, copy 버튼, 상세 drill-down 링크 제공
- 문서 상세는 `Overview / Raw vs Cleaned / Sections & Chunks / Run History`
- run 상세는 step 상태, artifact path, config snapshot, stdout/stderr 로그를 한 화면에 배치
- glossary 상세는 `keep_in_english`, `description_short`, alias CRUD를 즉시 수정 가능

## 상호작용

- action form은 JSON API를 호출하고 성공 시 toast + reload 또는 run detail redirect
- run 상태가 `queued/running`이면 page polling으로 자동 갱신
- filter form은 localStorage에 최근 사용 값을 보관
- destructive action은 공통 confirmation modal을 사용
- 실험 화면은 단계별 버튼(`generate`, `gate`, `build-memory`, `build-eval`, `eval-retrieval`, `eval-answer`)으로 실행 제어
- 실험 화면에서 최신 retrieval/answer report를 바로 확인 가능

## 현재 제한

- 모바일 최적화보다 desktop-first 관리 콘솔에 초점을 맞춤
- run streaming은 SSE 대신 polling 사용
- source enable/disable은 DB 기준 상태이며 YAML 파일 자체를 수정하지는 않음
