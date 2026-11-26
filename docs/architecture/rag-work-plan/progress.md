# RAG 공통 실행과 Spring AI 작업 진행 기록

| 작업 | 상태 | 검증 기록 |
| --- | --- | --- |
| [01 설정과 API 계약](01-runtime-and-api-contracts.md) | 구현·검증 완료 | Java 102건, Python 38건, React HTTP 계약 확인 |
| [02 Java 검색과 재작성 공통화](02-java-retrieval-execution.md) | 설계 완료 | 구현 대기 |
| [03 공식 연구 평가 교정과 검색 결과 재사용](03-research-evaluation.md) | 설계 완료 | 구현 대기 |
| [04 Spring AI 모델 호출과 프롬프트](04-spring-ai-provider-and-prompts.md) | 설계 완료 | 구현 대기 |
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
