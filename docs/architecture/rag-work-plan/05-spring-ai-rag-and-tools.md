# 05. Spring AI RAG와 Tool

Spring AI의 RAG 구성 요소를 온라인 답변 단계에서 사용하고, 기존 질의 실행이 Tool 경계를 통과해도 같은 결과를 만드는지 검증한다. Query-Forge의 도메인·스냅샷 제한, 검색 및 rerank 정책, Agentic 호출 순서를 기준으로 삼는다.

## 현재 적용 지점

| 책임 | 현재 코드 |
| --- | --- |
| 검색과 랭킹 | [DomainScopedRetrievalService.java](../../../backend/src/main/java/io/queryforge/backend/rag/retrieval/candidate/service/DomainScopedRetrievalService.java), [ChunkRetrievalRepository.java](../../../backend/src/main/java/io/queryforge/backend/rag/retrieval/candidate/repository/ChunkRetrievalRepository.java), [CohereRerankService.java](../../../backend/src/main/java/io/queryforge/backend/rag/retrieval/rerank/service/CohereRerankService.java) |
| 임베딩 | [DenseEmbeddingService.java](../../../backend/src/main/java/io/queryforge/backend/rag/embedding/service/DenseEmbeddingService.java), [SpringAiEmbeddingGateway.java](../../../backend/src/main/java/io/queryforge/backend/rag/provider/springai/gateway/SpringAiEmbeddingGateway.java) |
| VectorStore | [SpringAiVectorStoreGateway.java](../../../backend/src/main/java/io/queryforge/backend/rag/provider/springai/gateway/SpringAiVectorStoreGateway.java)는 사용 가능 여부를 조회한다. 현재 코퍼스 검색은 JDBC 경로를 사용한다. |
| 답변과 Agentic | [ChatAnswerService.java](../../../backend/src/main/java/io/queryforge/backend/rag/chat/answer/service/ChatAnswerService.java), [AgenticRetrievalService.java](../../../backend/src/main/java/io/queryforge/backend/rag/retrieval/agentic/service/AgenticRetrievalService.java), [SearchResultMerger.java](../../../backend/src/main/java/io/queryforge/backend/rag/retrieval/agentic/fusion/service/SearchResultMerger.java) |

## A. RAG와 임베딩 어댑터

1. `SpringAiEmbeddingGateway`의 reflection을 BOM 2.0.0의 타입이 있는 API로 교체할 수 있는지 검증한다. 기존 선택 조건·직접 HTTP fallback, 모델·벡터 차원, 벡터 정규화 및 pgvector 호환성을 비교한다. 메모리·청크에 저장된 임베딩과 다른 모델이나 차원이 적용되는 전환은 별도 연구 작업으로 다룬다.
2. 공통 검색의 최종 청크를 답변 경계에서 Spring AI `Document`로 매핑한다. 청크 ID·문서 ID·순위·점수·본문·인용에 필요한 metadata를 보존하고, 이 매핑을 별도 RAG 어댑터에 둔다. 이 시점까지의 검색·rerank·RRF는 기존 Java 실행의 결과다.
3. `RetrievalAugmentationAdvisor`를 검토할 때는 요청에 담긴 기존 검색 결과를 반환하는 `DocumentRetriever`와 기존 컨텍스트 구성을 재현하는 `QueryAugmenter`를 사용한다. 별도 쿼리 확장 없이 모델 호출 전 메시지와 청크 순서를 비교한다. 결과 조합·빈 근거 처리·템플릿 추가 문장이 기존과 다르면 동등성이 확인된 `Document` 매핑과 `ChatClient` 경계까지 적용한다. Advisor는 동일 결과를 입증한 답변 호출에만 연결한다. [Spring AI 모듈형 RAG](https://docs.spring.io/spring-ai/reference/2.0/api/retrieval-augmented-generation.html) · [2.0.0 Advisor 소스](https://github.com/spring-projects/spring-ai/blob/v2.0.0/spring-ai-rag/src/main/java/org/springframework/ai/rag/advisor/RetrievalAugmentationAdvisor.java)
4. JDBC의 domain/snapshot 제약, hybrid 후보 풀, score와 tie-break, `halfvec` 인덱스 및 기존 스키마는 그대로 사용한다. PGVector 자동 스키마 생성은 현재 비활성 설정을 유지한다. 같은 입력에서 추가 DB 검색·LLM 호출·trace 쓰기가 발생하지 않는지 확인한다.

## B. Spring AI Tool 적용

현재 Agentic은 애플리케이션이 승인된 하위 질의를 실행한다. 1차 적용 후보는 그중 **기존 도메인 범위의 근거 검색 호출**이다. Spring AI의 `ToolCallback`으로 이 읽기 전용 동작을 표현하고, 동일 입력을 기존 메서드와 callback으로 각각 실행해 계약을 비교한다. 애플리케이션이 기존 계획의 같은 위치에서 같은 인수로 호출하는 경우에만 온라인 경로에 적용한다. [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/2.0/api/tools.html)

1. 필요한 질의 값만 입력 스키마에 담고, 도메인·허용 범위·Top-K·스냅샷은 서버가 해석한 요청 문맥에서 받는다. Tool은 기존 검색 서비스에 위임하고 순위·청크 ID와 오류 의미를 보존한다. DB 쓰기, 외부 임의 호출, Admin 작업은 Tool 범위에서 제외한다.
2. `FunctionToolCallback` 또는 `@Tool` 중 기존 호출 계약을 더 단순하게 표현하는 방법을 사용한다. 테스트에서 허용된 Tool을 요청 문맥에 명시적으로 지정한다. 기본 `ChatClient`에 Tool을 등록하거나 운영 프롬프트에 `tools`를 넘기지 않는다. Spring AI 2.0의 Tool Advisor가 모델의 추가 실행 루프를 시작할 수 있으므로, 자동 모델 주도 Tool 호출은 별도 기능 변경으로 다룬다. [ToolCallingAdvisor](https://docs.spring.io/spring-ai/reference/2.0/api/tools/tool-calling-advisor.html)
3. 동일한 쿼리, 문맥과 오류에서 호출 횟수·실행 순서·정렬 결과·저장 trace가 같은지 확인한다. JSON 변환만 추가하고 재사용할 호출 경계가 없다면 운영 연결을 도입하지 않고 검증 결과와 적용 가능한 조건을 기록한다.

## 완료 기준

- `DenseEmbeddingServiceTest`, `DomainScopedRetrievalServiceTest`, `AgenticRetrievalServiceTest`, `SearchResultMergerTest`, `ChatAnswerServiceTest`와 답변 payload fixture를 실행한다. 빈 근거, 긴 근거, 동일 score, 잘못된 질의 입력, 검색 실패와 rerank fallback을 포함한다.
- 동일 입력에서 검색 호출 수, 후보·최종 청크·인용 순서, system/user 메시지, 답변 모델 호출 수가 기준과 일치한다. 기존 Java 평가 엔드포인트의 무기록과 답변 없음 계약이 계속 통과한다.
- 기존 검색 경로와 모델 호출 경로로 독립적으로 원복할 수 있다. Tool 자동 호출이나 검색 엔진 교체는 각자의 연구·운영 계약을 정한 뒤 별도 검증으로 다룬다.
