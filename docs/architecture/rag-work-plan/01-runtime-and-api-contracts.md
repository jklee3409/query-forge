# 01. 실행 설정과 API 계약

채팅, Java 검색 평가, Admin 등록이 같은 설정을 서로 다른 경로로 읽는다. 각 설정의 의미와 적용 범위를 기록하고, 공통으로 해석할 값은 타입과 기본값 규칙을 공유한다.

## 코드 위치

| 관심사 | 현재 진입점과 참조 |
| --- | --- |
| Spring AI 자동 구성과 모델 설정 | [application.yml](../../../backend/src/main/resources/application.yml), [build.gradle](../../../backend/build.gradle), [RuntimeEnvService.java](../../../backend/src/main/java/io/queryforge/backend/rag/provider/environment/service/RuntimeEnvService.java) |
| 온라인 설정·준비 상태·승격 | [ChatRuntimeConfigService.java](../../../backend/src/main/java/io/queryforge/backend/rag/runtime/configuration/service/ChatRuntimeConfigService.java), [ChatReadinessService.java](../../../backend/src/main/java/io/queryforge/backend/rag/runtime/readiness/service/ChatReadinessService.java), [ChatConfigPromotionService.java](../../../backend/src/main/java/io/queryforge/backend/rag/runtime/promotion/service/ChatConfigPromotionService.java) |
| 검색 실행 수치와 평가 override | [RagExecutionSettings.java](../../../backend/src/main/java/io/queryforge/backend/rag/runtime/configuration/model/RagExecutionSettings.java), [EvalRuntimeConfigService.java](../../../backend/src/main/java/io/queryforge/backend/rag/eval/retrieval/service/EvalRuntimeConfigService.java), [Java 평가 클라이언트](../../../pipeline/eval/java_retrieval_client.py) |
| Admin allowlist와 실험 설정 | [model_catalog.yml](../../../configs/app/model_catalog.yml), [AdminRuntimeOptionsService.java](../../../backend/src/main/java/io/queryforge/backend/admin/console/runtime/service/AdminRuntimeOptionsService.java), [RagTestService.java](../../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/run/service/RagTestService.java) |
| HTTP 오류와 소비자 | [RagApiExceptionHandler.java](../../../backend/src/main/java/io/queryforge/backend/rag/web/advice/RagApiExceptionHandler.java), [AdminApiExceptionHandler.java](../../../backend/src/main/java/io/queryforge/backend/admin/web/advice/AdminApiExceptionHandler.java), [api.js](../../../frontend/src/lib/api.js), [ChatPage.jsx](../../../frontend/src/pages/ChatPage.jsx) |

## 설정 소유권

| 설정군 | 유효값을 결정하는 곳 | 맞춰야 할 규칙 |
| --- | --- | --- |
| 배포·접속 | `application.yml`의 Spring Boot 설정과 기존 환경변수·`.env` 리졸버 | Spring 바인딩과 직접 환경변수 리졸버의 기본값·우선순위를 각각 기록한다. 키 출처, 공백 및 미설정 처리, `.env` 읽기 시점, Spring Bean 미등록 동작을 확인한다. 마이그레이션 중 해당 경로의 기존 키와 의미를 보존한다. |
| 모델과 프롬프트 | provider별 설정과 rewrite·planner·answer의 단계별 선택 | 단계별 모델 override, 기본 모델, endpoint, 자격 증명 참조, 출력 제한·timeout·retry·JSON 모드를 한 번 해석한다. 동일 의도의 설정은 같은 규칙을 쓰며, 의도한 단계별 차이는 타입과 테스트로 나타낸다. Adapter는 결정된 모델을 사용한다. |
| 온라인 도메인 | 활성 채팅 설정과 readiness | 도메인·스냅샷·생성 전략·allowlist를 활성 설정에서 읽는다. 같은 설정으로 readiness 검사와 실제 검색을 수행한다. 현재 활성 설정이 잘못됐을 때의 상태와 응답을 보존한다. |
| 공식 실험 | run 등록 시 검증·기록된 설정, 행별 스냅샷과 모드 | 기존 카탈로그와 `RagExecutionSettings`의 기본값을 사용한다. 당시의 run 설정으로 재현한다. 명시한 snapshot이 활성 채팅 설정의 기존 run/batch 목록에 가려지는 케이스를 고정한 뒤 [03](03-research-evaluation.md)에서 의도한 우선순위를 적용한다. |
| 실행 입력 | endpoint DTO와 모드별 override | 기존 snake_case·camelCase와 누락·null·빈 배열 의미를 입력 경계에서 정규화한다. `topK`만 준 요청의 호환 규칙과 rerank 상한을 명시한 요청을 구별한다. 공통 실행 서비스에는 해석된 값만 전달한다. |

설정 해석 결과는 값과 필요한 출처만 갖는 불변 객체로 넘긴다. 기존 `RagExecutionSettings`와 검색 runtime DTO를 먼저 재사용하고, 모델 호출 설정과 스냅샷 선택은 소유 기능의 작은 타입으로 둔다. 비밀 값은 로그·trace·보고서·오류 응답에 포함하지 않는다.

## 응답과 예외 규칙

| 경계 | 규칙 |
| --- | --- |
| 서비스 결과 | 성공, 원 질의 유지, 예상 가능한 fallback 및 warnings는 타입이 있는 실행 결과로 표현한다. 저장 ID는 trace 저장 경계에서 붙인다. |
| 예외 | 입력 검증, 상태 충돌, 외부 장애, 내부 실패를 발생 지점에서 의미 있게 구분한다. SDK와 I/O 예외는 gateway에서 처리하고 원인을 보존한다. 재시도와 fallback은 적용 단계의 기존 정책으로 결정한다. |
| 성공 HTTP | 기존 DTO, HTTP 상태, 필드명, 정렬, 누락/null/빈 배열 의미를 유지한다. 응답 조립은 API 경계에서 담당한다. |
| 오류 HTTP | 기존 Spring MVC `ProblemDetail`을 사용한다. RAG eval의 400 `code`, Gemini 503의 `errorCode`·`retryable`·`retryMessage` 및 부가 필드, Admin 상태 충돌 409와 RAG 상태 오류 500의 차이를 유지한다. 같은 구성 반복은 scoped advice에서 재사용한다. |
| Python·React | Python의 오류 코드 파싱과 fail-fast 정책, React의 오류 메시지·재시도 흐름을 계약 테스트로 고정한다. 새 진단은 버전이 있는 실험 결과 또는 선택적 필드로 추가하고 기존 응답의 의미를 유지한다. |

## 실행 단계

1. 기존 설정 등록·승격·로드·HTTP 소비자에서 실제 해석값을 목록화한다. 예제에는 provider 선택, rerank 상한, snapshot 단수·복수 충돌, boolean false, 잘못된 enum, 키 미설정을 포함한다.
2. 현재 계약을 특성화 테스트로 고정한다. 기존의 비즈니스 불일치는 재현을 기록하고 해당 정책을 다루는 후속 단계의 기대 결과로 연결한다.
3. 동작이 같은 기본값·정규화·범위 검증부터 공통화한다. DB 읽기, Spring 설정 바인딩, Admin allowlist, per-request override는 각 경계에서 처리한다. Python run config 직렬화는 필요한 필드만 명시적으로 전달한다.
4. 공통 오류 조립에 필요한 최소 코드를 도입한다. 유효한 요청, 기존 실패, 이미 기록된 데이터의 누락 필드를 포함한 응답을 비교한다.

## 완료 기준

- 동일 의미의 설정이 Admin 등록, 온라인 실행, Java 평가에서 같은 범위·타입으로 전달된다. 의도적으로 다른 값과 현재 미지원 옵션은 출처와 사유가 기록된다.
- `RagRetrievalExecutionModelTest`, `RagControllerWebTest`, `RagRetrievalEvalControllerTest`, 관련 Admin runtime·promotion 및 Python 클라이언트 테스트가 통과한다.
- Spring AI Bean 활성화 기본값과 기존 앱 응답에 의도하지 않은 변화가 없다. 설정 및 응답 변경은 단계 단위로 원복할 수 있다.

## 01 구현 기준으로 확인한 유효값

### 배포와 모델

| 설정 | 출처와 적용 시점 | 기본값·검증·fallback |
| --- | --- | --- |
| 연결·Spring 바인딩 | 백엔드 `application.yml`의 Spring Environment가 시작 시 설정을 바인딩한다. Datasource는 `POSTGRES_*`, HTTP 포트는 `SERVER_PORT`를 사용한다. | PostgreSQL 호스트/포트/DB 기본값은 `localhost/5432/query_forge`, HTTP는 `8080`이다. Chat·embedding·vector store Bean 선택 기본값은 각각 `none`이다. Spring OpenAI의 YAML 기본값은 `https://api.openai.com`, `gpt-4o-mini`, 온도 0.1, 출력 384, JSON object, embedding `text-embedding-3-small`과 384차원이다. |
| 직접 환경 설정 | `RuntimeEnvService`와 Admin `ConsoleEnvironment`가 조회할 때 프로세스 환경변수 → 저장소 루트 `.env` → 호출부 기본값 순으로 읽는다. | 환경변수 공백은 파일 조회로 이어진다. 파일은 UTF-8로 읽고 첫 키를 사용하며 바깥따옴표 한 쌍을 벗긴다. 공백·파일 미존재·파일 I/O 오류는 미설정으로 처리한다. Spring 바인딩은 이 직접 조회 경로를 사용하지 않는다. |
| 직접 Java provider | answer·rewrite·planner는 `QUERY_FORGE_LLM_PROVIDER`를 소문자화한다. `QUERY_FORGE_LLM_CLIENT`가 설정되면 `spring_ai`·`spring-ai`만 Spring gateway를 켠다. 미설정이면 provider의 `spring-ai`·`openai` 접두어가 Spring 시도를 결정한다. | provider 기본값은 `gemini`다. Spring Bean이 없거나 빈 응답·호출 실패로 gateway 결과가 없으면 기존 직접 경로로 간다. 직접 호출은 `openai` 접두어면 OpenAI, 그 외에는 Gemini다. Provider 문자열은 이 경계에서 allowlist로 차단하지 않는다. |
| 직접 Java 모델·endpoint | answer는 `QUERY_FORGE_LLM_MODEL`, rewrite는 `QUERY_FORGE_LLM_REWRITE_MODEL` → 공통 모델, planner는 `QUERY_FORGE_LLM_PLANNER_MODEL` → rewrite 모델 → 공통 모델 순으로 선택한다. | 직접 Gemini 기본값은 `gemini-2.5-flash-lite`와 `QUERY_FORGE_GEMINI_BASE_URL`의 기본 endpoint `https://generativelanguage.googleapis.com`이다. OpenAI는 `gpt-4o-mini`와 `QUERY_FORGE_OPENAI_BASE_URL`의 기본 endpoint `https://api.openai.com/v1`이다. Spring gateway의 실제 모델은 Spring 설정을 사용한다. 기존 answer·planner 표시 모델은 별도 환경값과 fallback을 유지한다. |
| 직접 Java 요청 | Gemini 키는 `QUERY_FORGE_GEMINI_API_KEY` → `GEMINI_API_KEY` → `GOOGLE_API_KEY`, OpenAI 키는 `OPENAI_API_KEY`에서 조회한다. | 공통 출력 상한은 `QUERY_FORGE_LLM_MAX_OUTPUT_TOKENS` 정수 또는 384다. Answer·rewrite는 양수만 적용하고 planner는 0·음수도 전달한다. 세 단계 모두 온도 0.1·JSON 형식을 요청한다. 연결은 10초, 요청은 answer 60초·rewrite/planner 45초다. Gemini answer는 503에 최대 두 번 호출하고 소진 시 503을 반환한다. Rewrite·planner의 직접 실패는 기존 빈 결과와 각 단계 fallback으로 이어진다. |
| Admin 모델·프롬프트 | `model_catalog.yml`의 모델과 provider 상태를 검증한다. 기본 run은 Gemini flash-lite이며 공통 모델 입력을 여러 단계에 등록한 뒤 rewrite 모델 입력을 별도로 적용한다. Preflight는 run의 rewrite provider/model → 공통 값 → 단계별·공통 환경값을 확인하며 rewrite 키·공통 키·provider 키를 확인한다. | 카탈로그에서 비허용 모델은 400이다. Admin에서 OpenAI 모델을 선택해도 run의 provider가 자동으로 바뀌지는 않는다. Java eval은 run에 기록된 LLM 모델로 Java provider를 전환하지 않는다. Spring AI 모델·프롬프트와 Admin 설정 연결은 04에서 다룬다. |

### 등록·저장·요청

| 설정 | 유효값 결정 순서와 적용 단계 |
| --- | --- |
| 실행 수치 | `RagExecutionSettings` 기준으로 retrieval Top-K 10 / 1–100, rerank Top-N 5 / 1–100, memory Top-N 5 / 1–50, rewrite 후보 수 2 / 1–2, threshold 0.05 / 유한 실수 0–1이다. Admin 신규 등록과 채팅 설정 미생성 도메인은 카탈로그 기본값을 읽는다. Admin 및 채팅 설정 저장은 명시 입력에 카탈로그 범위를 적용하며 실행 모델 범위도 적용한다. 채팅은 저장된 값을 사용한다. 승격은 Top-K·Top-N·threshold의 run 열 → config JSON → 현재 채팅 값, memory Top-N·후보 수는 config JSON → 현재 채팅 값 순이다. |
| Java 평가 수치 | 요청 `topK` → `runtimeConfig.retrievalTopK` → `retrieval_top_k` → 중첩 `topK/top_k` → 활성 설정 순이다. Rerank는 `rerankTopN/rerank_top_n` → 요청 `topK` → 활성 설정 순이다. 중첩 Top-K 단독은 rerank 상한을 바꾸지 않는다. 나머지 수치는 camelCase → snake_case → 지원하는 legacy 별칭 → 활성 설정 순이다. 미설정·JSON null은 다음 출처를 사용한다. 정수가 아닌 정수 입력과 숫자가 아닌 threshold는 400이다. |
| 검색기 | 활성 채팅은 저장값을 우선하며 미생성 시 `local`, `hybrid`, `intfloat/multilingual-e5-small`, candidate pool 50, dense/BM25/technical 0.60/0.32/0.08이다. Admin은 카탈로그 모드 preset·범위를 쓴다. Admin 등록과 채팅 저장의 명시 pool 1–500, 각 weight 0–1 검증은 같은 규칙을 쓴다. 단일 검색기는 고정 weight를 적용한다. 채팅 저장은 0 합계를 거부하고 Admin hybrid는 카탈로그 weight로 돌아간다. Java 평가에서는 중첩 retriever object가 root override보다 우선하며 실행 경계는 이전과 같이 pool 하한 1, weight 보정·정규화와 0 합계 hybrid 기본값을 적용한다. |
| 채팅 모드·readiness | 활성 도메인 DB 설정과 도메인 허용 전략을 사용한다. 미생성 기본값은 `enabled=true`, `selective_rewrite`, `full_gating`, `compact_anchor`, anchor/session=false, `heuristic_fallback`, 빈 metadata다. Readiness는 별도로 실제 설정 존재, 도메인·스냅샷 일치, 임베딩, 프롬프트 바인딩, gated query·memory 존재를 본다. 채팅 저장은 지원하지 않는 mode/preset/profile/policy를 400으로 거부한다. 명시 router 설정은 기존 metadata 키에 쓴다. Legacy alias가 동시에 true인 경우의 기존 OR 판정을 유지한다. |
| 활성/승격 스냅샷 | 채팅 저장은 요청 복수 목록 → 요청 단수, 둘 다 미제공이면 현재 복수 → 현재 단수 순이다. 순서를 유지해 null·중복 UUID를 제거하고 rewrite 모드에서는 최대 20개와 완료/도메인/preset/전략을 검증한다. raw 모드는 스냅샷을 비운다. 읽기·readiness는 목록이 비면 단수로 복구한다. 응답에 반환되는 단수·복수 필드와 첫 스냅샷 순서를 유지한다. 승격은 완료 run만 받으며 rewrite 모드의 단수 우선순위는 `source_gating_batch_id` → 비교 스냅샷(preset, full, rule+LLM, rule, ungated) → `snapshot_id`다. |
| 평가 스냅샷과 flags | 평가의 runtime object는 활성 DB 설정과 metadata를 기본으로 alias override를 얹는다. UUID 배열에서 잘못된 값을 건너뛴다. 누락·null 배열은 활성 목록을 남기며 빈 스냅샷 배열은 최종 단수로 복구된다. 빈 generation strategies 배열은 빈 값으로 적용된다. 채팅 저장의 빈 전략 목록은 허용 전략으로 복구된다. Boolean false는 누락과 구별한다. 활성 설정 존재·enabled는 평가에서도 필수다. `readyForRewrite` override는 평가용 readiness에 적용하며 실제 온라인 readiness 조회를 대신하지 않는다. |
| Agentic 실행 수치 | 기본 RRF K 60과 subquery 1–4를 공유한다. 라이브·승격의 미설정 subquery 기본값은 3, 평가·Admin은 4다. RRF K는 기존 실행·승격에서 하한 1, 신규 등록에서 1–500으로 보정한다. Java 평가의 수치는 기존의 metadata 별칭에서 읽고 Admin 등록 root의 `agentic_max_subqueries`·`agentic_rrf_k`는 현재 직접 적용되지 않는다. 기록된 설정의 실행 적용 경계 정리는 02에 인계한다. |
| Java HTTP / Python | Java 평가 요청은 `persistPolicy=NONE`, `answerGeneration=false`, scores=true, trace/metadata=false가 기본이다. 미지의 forced mode는 400 `unsupported_forced_mode`, Jackson enum으로 역직렬화할 수 없는 persist policy는 MVC 400이다. 알려진 쓰기 policy는 API 400 `unsupported_persist_policy`다. Python은 기본 Java, 명시 legacy 허용, Java 오류 시 fail-fast다. Java에 전달하는 runtime object는 Java가 읽는 필드만 선별하고 false·null·빈 배열을 유지한다. Java endpoint·domain은 전용 config alias와 환경 기본값을 읽으며 timeout은 10초, 하한은 0.1초다. |

### 응답 계약과 후속 재현

- RAG scoped advice는 입력 오류 400, eval 오류 400과 `code`, 상태 오류 500을 유지한다. Gemini 503은 기존 제목·메시지와 `errorCode`, `retryable`, `retryMessage`, `provider`, `model`, `statusCode`, `attempts` 순서를 유지한다. Admin scoped advice는 상태 오류를 409로 반환한다. 경로 접두어와 컨트롤러 패키지 범위가 별개이므로 `/api/admin/chat/config`는 기존 RAG advice에 속한다.
- 성공 DTO와 청크 순서·중복, 조건부 null과 빈 배열 규칙은 기존 계약을 따른다. React는 `errorCode` 또는 `code`와 retry 필드를 읽고 Gemini 메시지를 표시한 뒤 제출 버튼을 다시 사용할 수 있게 한다. Python은 ProblemDetail의 `code`와 `detail`을 읽고 Java 실패를 호출자에 전달한다.
- **03-B 스냅샷**. 활성 채팅의 run/batch 목록 A가 있을 때 Java eval에 단수 run/batch B만 넘기면 평가 설정 DTO의 단수는 B지만 메모리 조회는 목록 A를 받는다. `RagRetrievalEvalServiceTest.singularSnapshotOverrideKeepsActiveListsAndPreservesFalseAndEmptyOverrides`로 고정했다. 공식 실험의 행별 스냅샷을 Python에서 Java로 명시적으로 전달하고 활성 설정 목록보다 우선시키는 교정은 03-B에서 한다.
- **03-A 대조군**. run에 `forced_retrieval_mode=selective_rewrite`와 `retrieval_modes=[raw_only, selective_rewrite]`가 함께 있으면 Python이 `raw_only` 행에도 `forcedMode=selective_rewrite`를 선택한다. `test_official_raw_row_uses_recorded_forced_mode`로 고정했다. 03-A에서 실제 raw 대조군을 보장한다.
