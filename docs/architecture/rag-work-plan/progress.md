# RAG 공통 실행과 Spring AI 작업 진행 기록

| 작업 | 상태 | 검증 기록 |
| --- | --- | --- |
| [01 설정과 API 계약](01-runtime-and-api-contracts.md) | 구현·검증 완료 | Java 102건, Python 38건, React HTTP 계약 확인 |
| [02 Java 검색과 재작성 공통화](02-java-retrieval-execution.md) | 구현·검증 완료 | 대상 Java 87건 통과 |
| [03 공식 연구 평가 교정과 검색 결과 재사용](03-research-evaluation.md) | 구현·회귀 검증 완료, Anchor 실측 대기 | Java 79건(PostgreSQL 통합 포함), Python 58건, React 프로덕션 빌드 통과 |
| [04 Spring AI 모델 호출과 프롬프트](04-spring-ai-provider-and-prompts.md) | 회귀 기준 확보 | provider·답변·Planner 테스트 9건 통과 |
| [05 Spring AI RAG와 Tool](05-spring-ai-rag-and-tools.md) | 설계 완료 | 구현 대기 |

## 2026-11-26

현재 코드와 설정을 검토하고 5개 설계를 작성했다. 공식 평가 정책 변경의 적용 위치와 Spring AI 전환의 동등성 기준을 정했다. 구현 단계가 완료될 때 이 표에 변경 경로, 실행한 검증과 결과, 다음 단계 조건을 기록한다.

## 2026-11-26 · 01 구현 및 검증

- 실행 모델의 수치 키를 등록·승격에서 재사용했다. 검색기 기본값·입력 범위, Agentic 실행 범위, 목록이 비었을 때의 단수 스냅샷 호환, 직접 Java 모델 기본값과 단계별 override를 공유했다. Spring 설정 바인딩, DB 조회, 카탈로그 검증, 평가 override는 각 진입점에 있다.
- scoped advice 내부에서 `ProblemDetail` 조립을 공유했다. Java 평가의 `code`와 Gemini 503의 확장 필드, Admin 409와 RAG 500을 확인했다. Python 전송은 Java가 읽는 필드로 제한했다.
- 대상 Java 12개 클래스에서 102건 통과, 실패·오류·건너뜀 0건. Testcontainers PostgreSQL은 Admin 등록·승격, 구형 run의 누락 필드, 채팅 readiness와 Admin runtime 카탈로그를 검증했다. 코드 변경 전 자격 증명 누락 테스트의 환경 의존성을 확인하고 테스트 조회를 격리했다. 등록 fixture는 임시 파일 경로와 stub executor를 사용한다.
- `python -m unittest pipeline.tests.test_java_retrieval_client pipeline.tests.test_retrieval_eval_compare -q` 38건 통과. Node 가짜 fetch로 React의 503 retry 필드와 400 code 매핑을 확인했다. `git diff --check` 통과.
- 공식 `raw_only` 행에 run 강제 모드가 적용되는 03-A 재현과 활성 채팅 목록이 평가의 단수 스냅샷을 가리는 03-B 재현을 남겼다. [유효값 표와 재현 조건](01-runtime-and-api-contracts.md#응답-계약과-후속-재현)

```powershell
# backend에서 실행
.\gradlew.bat test --max-workers=2 `
  --tests "*.RagRetrievalExecutionModelTest" `
  --tests "*.RagControllerWebTest" `
  --tests "*.RagRetrievalEvalControllerTest" `
  --tests "*.RagRetrievalEvalServiceTest" `
  --tests "*.AdminConsoleRagIntegrationTest" `
  --tests "*.DomainWorkspaceIntegrationTest" `
  --tests "*.RagServiceTest" `
  --tests "*.ChatAnswerServiceTest" `
  --tests "*.AgenticQueryPlannerServiceTest" `
  --tests "*.ChatModelSettingsTest" `
  --tests "*.RagRetrievalExecutionServiceTest" `
  --tests "*.AdminConsoleGatingIntegrationTest"
```

## 2026-11-26 · 03-A와 Spring AI 기준 테스트

- 공식 rewrite 비교는 원 질의 행과 selective 행의 Java 요청을 분리한다. 요청과 응답의 실행 모드가 다르면 채점 전에 실패한다. 단일 모드와 비교 runner의 명시 선택을 보존했다.
- 재작성·Planner·답변의 실제 OpenAI 요청을 가짜 provider로 캡처했다. 단계별 모델, 메시지 역할·순서, 토큰, 온도 및 실패 대체 경로를 확인했다. 직접 호출 온도는 재작성 0.2, 답변·Planner 0.1이다.

## 02 Java 공통 검색 실행

- 채팅·Agentic·Java 평가의 메모리 범위와 문맥 조립, 재작성 점수·판정을 공유했다. 온라인은 후보 입력 순서, 기존 평가에는 후보 인덱스에 따른 동점 판정과 빈 후보 결과를 유지했다.
- Agentic의 재순위화 실패 전 진행 trace와 평가의 무기록 경계를 유지했다. 대상 Java 테스트 87건을 통과했다.

## 2026-11-26 · 03-B·C 공식 평가 v2

- 공식 게이팅 세 행의 스냅샷, `skip_to_raw`, Java 원 질의 대조군과 무기록 응답을 검증했다. 답변 평가가 행별 스냅샷과 retrieval run ID를 확인하고 Java 청크 순서대로 본문을 읽는다. PostgreSQL 작업 재시도 테스트는 완료된 검색 실행을 재사용한다.
- Java 대상 테스트 79건과 Python 58건을 통과했다. 동일 스냅샷 입력에서 Anchor 스위치에 따라 공급자 프롬프트에 들어가는 힌트를 확인했고 React 번들을 갱신했다. Anchor on/off의 실제 코퍼스 지표와 모델 지연은 측정하지 않았다.
