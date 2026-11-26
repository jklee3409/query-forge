# 04. Spring AI 모델 호출과 프롬프트

질의 재작성, Agentic 계획, 답변 생성의 프롬프트 구성과 모델 호출에 Spring AI 사용을 확대한다. 공급자별 HTTP·SDK 차이를 gateway에서 처리하고, 정책과 프롬프트 데이터 구성은 기능별로 남긴다. 기준 의존성은 [build.gradle](../../../backend/build.gradle)의 Spring AI BOM 2.0.0과 Spring Boot 4.1.0이다. [Spring AI 2.0.0 릴리스](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0)

## 현재 구현과 이동 대상

| 구성 | 현재 코드와 작업 경계 |
| --- | --- |
| 모델 호출 | [SpringAiChatGateway.java](../../../backend/src/main/java/io/queryforge/backend/rag/provider/springai/gateway/SpringAiChatGateway.java)는 `ChatClient`에 렌더링된 system/user 문자열을 전달한다. Bean과 설정에 따라 기존 HTTP 경로로 돌아간다. 이 gateway를 단계별 요청 옵션과 호출 결과를 받는 어댑터로 확장한다. |
| 재작성 | [RewriteCandidateService.java](../../../backend/src/main/java/io/queryforge/backend/rag/retrieval/rewrite/service/RewriteCandidateService.java)는 프롬프트 파일 탐색, memory/Anchor 입력, 호출, 파싱, 휴리스틱을 담당한다. 파일·템플릿 해석과 요청 메시지 구성, 응답 파싱·검증, 호출 순서를 분리한다. 후보의 label·개수·중복 제거·fallback은 현행대로 검증한다. |
| Agentic 계획 | [AgenticQueryPlannerService.java](../../../backend/src/main/java/io/queryforge/backend/rag/retrieval/agentic/planning/service/AgenticQueryPlannerService.java)의 모델 호출을 같은 gateway 계약에 연결한다. 하위 질의 정규화·개수·원 질의 fallback은 planner에 둔다. |
| 답변 | [ChatAnswerService.java](../../../backend/src/main/java/io/queryforge/backend/rag/chat/answer/service/ChatAnswerService.java)의 메시지 조립과 결과 파싱을 기능 안에서 분리한다. 근거·인용·답변 JSON 및 Gemini 재시도·503 오류를 유지한다. |
| 설정과 프롬프트 자산 | [application.yml](../../../backend/src/main/resources/application.yml)과 [01의 설정 계약](01-runtime-and-api-contracts.md)을 따른다. Java rewrite의 현재 파일 선택 순서·fallback·프롬프트 ID를 유지한다. DB 활성 binding을 새로 우선하도록 바꾸려면 별도 변경으로 비교한다. |

## 실행 단계

1. **기존 모델 호출을 고정한다.** 재작성·planner·답변 각각에서 실제 선택된 provider·model·URL 경로, system/user 메시지 순서와 본문, 출력 토큰·temperature·응답 형식, timeout·retry, 응답 없음 및 JSON 오류 이후 행동을 가짜 provider로 캡처한다. 이미 Spring AI를 선택하는 설정 조합도 기준에 넣는다.
2. **메시지 구성을 나눈다.** 정적 지시문과 동적 입력을 기능별 프롬프트 구성 코드에 둔다. `PromptTemplate`은 실제 템플릿 변수에만 적용하고, 사용자 입력과 JSON·중괄호를 포함한 렌더링 완료 문자열은 message 또는 `NoOpTemplateRenderer` 경계로 넣는다. 기존 한국어/영어 프로필, system/user 역할과 내용, 인용 ID·문서 순서·잘라내는 기준을 비교한다. [Spring AI ChatClient 템플릿 문서](https://docs.spring.io/spring-ai/reference/2.0/api/chatclient.html#_prompt_templates) · [2.0.0 ChatClient 소스](https://github.com/spring-projects/spring-ai/blob/v2.0.0/spring-ai-client-chat/src/main/java/org/springframework/ai/chat/client/ChatClient.java)
3. **단계별 호출을 공통 어댑터로 연결한다.** 기존 코드가 선택하던 provider·model을 명시적 옵션으로 전달한다. 현재 Spring AI 경로가 사용하는 모델과 기존 코드가 기록하는 모델이 다르면 fixture로 실제 전송값을 확인한다. 운용 모델 변경 없이 필요한 진단을 추가한다. Provider 전송과 오류 분류는 gateway에 두며, 앱 수준과 SDK의 자동 retry가 겹치지 않도록 단계별 최대 호출 수와 backoff를 확인한다. [OpenAI 모델·retry 설정](https://docs.spring.io/spring-ai/reference/2.0/api/chat/openai-chat.html)
4. **기존 파싱과 실패 의미를 유지한다.** 초기에 `ChatClient` 응답 텍스트를 기존 파서에 연결한다. Structured Output Converter나 native JSON Schema는 프롬프트·유효 응답 집합·fallback 변화가 없는 경우에만 적용한다. 전송 실패와 파싱 실패를 구분하며 rewrite의 휴리스틱, planner의 원 질의 계획, 답변의 provider 오류를 각 계약대로 처리한다.
5. **공급자별로 전환한다.** 이미 설치된 OpenAI 또는 OpenAI 호환 조합부터 재작성 → planner → 답변 순으로 작은 변경을 적용한다. Gemini는 동일 API와 모델·안전/생성 옵션·오류/재시도 의미가 재현되는 Spring AI 어댑터를 검증한 뒤 적용한다. 지원 차이가 있는 접속 설정은 기존 HTTP gateway를 유지한다. `application.yml`의 현재 자동 구성 기본값과 환경변수 키를 보존한다. 검증한 조합을 전환할 때 사용한 설정과 이전 값으로 복구하는 방법을 진행 기록에 남긴다.

## 완료 기준

- 기록된 provider 응답을 재생하면 후보·계획·답변 DTO와 HTTP 오류가 기존과 일치한다. 빈 응답, 잘못된 JSON, 잘못된 근거 ID, missing credential, timeout, 429·503, 재시도 후 성공을 확인한다.
- 요청 payload와 호출 수가 보존된다. Spring AI를 사용하기 위해 모델, JSON 모드, retry 정책이나 Spring Boot/BOM 버전을 함께 변경할 필요가 생기면 해당 전환을 별도 변경으로 기록한다.
- `ChatAnswerServiceTest`, `AgenticQueryPlannerServiceTest`, `RagServiceTest`, `RagControllerWebTest` 및 재작성 gateway·프롬프트 fixture를 검증한다. 공유된 후보 생성이 Java 평가에도 사용되므로 [03](03-research-evaluation.md)의 기준 샘플로 재확인한다.
