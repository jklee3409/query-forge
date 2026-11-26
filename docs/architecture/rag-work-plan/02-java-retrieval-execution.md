# 02. Java 검색과 재작성 공통화

기존 `rag.retrieval.execution`에 raw 검색, 메모리 검색, 후보 실행과 채택에 필요한 공통 입력과 결과를 연결한다. 채팅의 답변·온라인 이력 및 평가 API의 무기록 계약은 각 진입점에서 처리한다.

## 코드 위치와 책임

| 책임 | 재사용할 구현 |
| --- | --- |
| 채팅 진입점·답변·응답 조립 | [RagService.java](../../../backend/src/main/java/io/queryforge/backend/rag/chat/service/RagService.java), [ChatResponseAssembler.java](../../../backend/src/main/java/io/queryforge/backend/rag/chat/service/ChatResponseAssembler.java), [QueryRouteContexts.java](../../../backend/src/main/java/io/queryforge/backend/rag/retrieval/routing/service/QueryRouteContexts.java) |
| 공통 실행·범위 제한 검색 | [RagRetrievalExecutionService.java](../../../backend/src/main/java/io/queryforge/backend/rag/retrieval/execution/service/RagRetrievalExecutionService.java), [DomainScopedRetrievalService.java](../../../backend/src/main/java/io/queryforge/backend/rag/retrieval/candidate/service/DomainScopedRetrievalService.java) |
| 전략·판정·비교 점수 | [QueryStrategyRouter.java](../../../backend/src/main/java/io/queryforge/backend/rag/retrieval/routing/service/QueryStrategyRouter.java), [RewriteDecisionPolicy.java](../../../backend/src/main/java/io/queryforge/backend/rag/retrieval/rewrite/policy/RewriteDecisionPolicy.java), [RewriteScoring.java](../../../backend/src/main/java/io/queryforge/backend/rag/retrieval/rewrite/service/RewriteScoring.java) |
| Agentic 하위 질의와 RRF | [AgenticRetrievalService.java](../../../backend/src/main/java/io/queryforge/backend/rag/retrieval/agentic/service/AgenticRetrievalService.java), [SearchResultMerger.java](../../../backend/src/main/java/io/queryforge/backend/rag/retrieval/agentic/fusion/service/SearchResultMerger.java) |
| 평가와 trace 경계 | [RagRetrievalEvalService.java](../../../backend/src/main/java/io/queryforge/backend/rag/eval/retrieval/service/RagRetrievalEvalService.java), [RagTracePersistenceService.java](../../../backend/src/main/java/io/queryforge/backend/rag/trace/write/service/RagTracePersistenceService.java), [Agentic 무기록 설계](../../rag-agentic-no-write-eval-design.md) |

## 모듈 경계

- 전략 요청, 메모리 범위, 세션 문맥, 검색 설정을 해석한 뒤 기존 실행 DTO를 확장해 전달한다. 결과는 원 질의 검색과 재순위, 생성 후보의 검색·confidence, 후보 식별자, 최종 질의·순위·사유를 포함한다. Agentic은 하위 질의와 RRF의 별도 결과를 유지한다.
- 순수 채택 정책은 `rag.retrieval`의 정책 소유 기능으로 이동한다. 온라인의 raw 유지·동점·threshold·강제 모드 의미를 보존한다. Agentic은 자신의 후보 식별자와 결합 규칙을 유지하면서 동일한 비교를 위임한다.
- 현재 eval의 후보 강제 선택은 호환 동작으로 표현한다. 공통 검색 서비스에서 선택 근거를 돌려주고, [03](03-research-evaluation.md)에서 새 공식 정책이 명시적으로 공통 판정을 요청하게 한다. 기존 온라인 fallback이나 direct-memory 동작을 바꾸면서 구조를 옮기지 않는다.
- JDBC 청크·메모리 조회, Cohere rerank, Router 조건과 Agentic RRF는 기존 기능에 둔다. 설정 해석·LLM 전송·API 응답·trace 저장을 실행 서비스 안으로 이동시키지 않는다.

## 실행 단계

1. 기존 온라인과 Java 평가의 차이를 fixture로 기록한다. 평가 강제 모드, 세션 입력, readiness, 메모리 부재, 원 질의 confidence 재판정, Agentic transient ID를 포함한다.
2. 원 질의 검색·후보 생성·후보 실행을 기존 `RagRetrievalExecutionService`에 연결한다. 메모리 필터와 임베딩 조회의 중복을 줄인다. 채팅에서 후보마다 저장하는 경로는 결과를 한꺼번에 쓰는 방식으로 바꾸지 않는다.
3. 채택 정책을 옮기고 채팅에서 먼저 검증한다. Agentic의 공통화는 순수 점수 비교부터 적용하되 같은 입력에서 선택·실패 이유와 RRF 입력 순서를 확인한다. 하위 질의 라우팅과 재귀 방지는 Agentic의 책임으로 둔다.
4. 평가가 공유 실행 재료를 사용하도록 연결한다. 기존 소비자에게는 현재 mode 및 응답 동작을 유지한다. Router 문맥 생성과 단계 순서를 공유할 수 있게 준비한 뒤 [03](03-research-evaluation.md)의 공식 정책에서 연결한다.
5. 온라인은 기존 쓰기 시점과 응답 조립을 유지한다. 평가에는 생성된 후보·검색·결정 결과를 메모리에서만 넘기고 답변·온라인 질의·trace 쓰기를 하지 않는 계약을 검증한다. 저장 ID를 생성하지 않아도 후보 식별과 채택이 가능해야 한다.

## 완료 기준

- 같은 테스트 데이터에서 구조 변경 전후의 온라인 순위, 후보 순서, 채택 사유와 응답을 비교한다. 후보 실행이나 답변 생성 중 예외가 발생해도 기존에 저장하던 trace가 조회되어야 한다.
- `RagServiceTest`, `RewriteDecisionPolicyTest`, `RagRetrievalExecutionServiceTest`, `DomainScopedRetrievalServiceTest`, `QueryStrategyRouterTest`, `AgenticRetrievalServiceTest`, `RagRetrievalEvalServiceTest`를 영향 단위로 실행한다.
- 공통화 전후의 의존성을 비교한다. 하나의 서비스가 실행, 설정 해석, 저장, 응답을 함께 소유하는 변경은 역할별로 분리한다.
