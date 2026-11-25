# 백엔드 소스 인벤토리

기준 커밋 `f3a2cc2`의 Java 클래스 의존 관계를 확인한 뒤, 기능 안에 역할별 하위 패키지를 도입했다. 이관 후 운영·테스트 소스를 다시 파싱해 모든 선언의 패키지와 실제 경로를 대조했다.

| 범위 | 최상위 타입 | 명명된 중첩 타입 | 타입이 선언된 패키지 |
| --- | ---: | ---: | ---: |
| `backend/src/main/java` | 230 | 227 | 203 |
| `backend/src/test/java` | 27 | 2 | 24 |
| 운영 소스 중 `rag` | 92 | — | 76 |
| 테스트 소스 중 `rag` | 15 | — | 14 |

`backend` 최상위에는 Gradle 빌드 정의, 래퍼, Dockerfile과 디렉토리 문서가 있다. `src/main/resources`에는 애플리케이션 설정, Flyway 마이그레이션, React 번들이 있고 `src/test/resources`에는 테스트 설정과 SQL 픽스처가 있다. `build`, `.gradle`, `bin`은 로컬 빌드·IDE 산출물로 확인했다. Gradle이 사용하는 Java 소스에는 패키지 경로 불일치나 빈 디렉토리가 없다.

기능마다 구현된 역할을 `controller`, `service`, `repository`, `dto` 등의 하위 패키지에 둔다. 클래스 간에 공유하는 레코드와 요청·응답 타입은 `dto`로 옮겼다. 한 클래스의 내부에서 쓰는 타입은 그 구현 안에 둔다. `QueryForgeApplication`과 부트스트랩 테스트는 루트에 있다.

기준 커밋과 비교해 Spring HTTP 매핑 133개, 기존 레코드 선언 227개, 기존 테스트 메서드 181개가 모두 유지됐다. Docker PostgreSQL을 포함한 백엔드 클린 테스트 181개와 Python의 Java 검색 연동 테스트 36개가 모두 통과했다.

## 기능 경계를 결정한 근거

| 영역 | 확인한 관계 | 적용한 분류 |
| --- | --- | --- |
| RAG 채팅 | 채팅 조정 서비스는 도메인 설정과 라우팅을 거쳐 일반·Agentic 검색에 위임한다. 답변 구성은 Spring AI 또는 기존 공급자 호출을 사용한다. | 채팅 조정과 응답 조립은 `chat`에 두고 답변 생성과 라이브 라우팅 입력 조합을 `answer`, `routing`에 배치했다. |
| RAG 검색 | 후보 수집과 랭킹은 일반 검색과 Agentic 검색이 공유한다. Agentic 경로의 계획 및 RRF는 각각 입력과 출력이 독립적이다. | `candidate`, `execution`, `memory`, `rewrite`, `rerank`, `routing`를 구분했다. Agentic 계획과 병합은 각 하위 패키지로 옮겼다. |
| RAG 평가와 실험 | 검색 평가 경로는 요청별 설정을 조합한 뒤 답변 생성 없이 실행한다. 보고서 파일 조회와 Python 실험 명령 실행은 별도의 입출력 경계를 가진다. | `eval.retrieval`, `eval.report`, `experiment.command`, `experiment.summary`로 나눴다. |
| RAG 런타임과 추적 | 설정 검증과 준비 상태가 게이팅 스냅샷 조회를 공유한다. 추적 조회 SQL과 온라인 기록 SQL은 서로 다른 진입점에서 사용된다. | `runtime.configuration`, `promotion`, `snapshot`, `readiness`로 나눴다. 추적은 `query`, `write`, `write.agentic`에 배치했다. |
| 관리자 평가 | 데이터셋 삭제는 실행 상태를 검증하고 연결된 이력을 같은 트랜잭션에서 정리한다. 앵커 평가 입력은 실행 응답 전체에 의존할 필요가 없다. | `dataset.lifecycle`에서 데이터셋과 실행의 삭제를 조정한다. `run`, `anchor`, `debug`에서 실행 이력과 각 진단을 소유한다. |
| 코퍼스와 파이프라인 | 앵커 추출·정규화·관계 서비스에 데이터베이스 코드가 섞여 있었다. 파이프라인 실행에는 HTTP 진입점·실행 조정·SQL·계약이 있다. | 앵커 서비스는 각 기능의 `repository`에 JDBC를 위임한다. 파이프라인 실행의 네 역할을 각 하위 패키지로 나눴다. |
| 나머지 관리자 기능과 공통 코드 | 합성 질의, 게이팅, 도메인, 프롬프트, 소스·문서·용어는 각 기능 패키지에 있었다. 여러 역할을 묶는 통합 테스트는 공통 진입점을 검증한다. | 모든 기능에서 역할별 하위 패키지를 사용한다. 영역 공통 오류 응답은 `web.advice`로, 여러 역할을 묶는 테스트는 `integration`으로 분리했다. |

아래 표는 패키지별 전체 선언 목록이다. 직접 참조 열은 Java 타입 이름과 명시적 import의 정적 참조를 기준으로 하며, 실행 중 Spring 빈 연결은 통합 테스트에서 확인했다. 명명된 중첩 타입과 레코드는 바깥 타입의 행에 포함했다.

## Production source

### `admin.console.configuration.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ConsoleExperimentConfig](../../backend/src/main/java/io/queryforge/backend/admin/console/configuration/service/ConsoleExperimentConfig.java#L20) | — | `AdminRuntimeOptionsDtos`, `AdminRuntimeOptionsService`, `ConsoleEnvironment`, `ConsoleParameters` |

### `admin.console.configuration.support`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ConsoleParameters](../../backend/src/main/java/io/queryforge/backend/admin/console/configuration/support/ConsoleParameters.java#L6) | — | — |

### `admin.console.dashboard.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ConsoleDashboardController](../../backend/src/main/java/io/queryforge/backend/admin/console/dashboard/controller/ConsoleDashboardController.java#L10) | — | `ConsoleDashboardDtos`, `ConsoleDashboardRepository` |

### `admin.console.dashboard.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ConsoleDashboardDtos](../../backend/src/main/java/io/queryforge/backend/admin/console/dashboard/dto/ConsoleDashboardDtos.java#L3) | `AdminDashboardStats` | — |

### `admin.console.dashboard.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ConsoleDashboardRepository](../../backend/src/main/java/io/queryforge/backend/admin/console/dashboard/repository/ConsoleDashboardRepository.java#L10) | — | `ConsoleDashboardDtos` |

### `admin.console.embedding.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChunkEmbeddingAdminController](../../backend/src/main/java/io/queryforge/backend/admin/console/embedding/controller/ChunkEmbeddingAdminController.java#L14) | — | `ChunkEmbeddingAdminDtos`, `ChunkEmbeddingAdminService`, `LlmJobDtos` |

### `admin.console.embedding.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChunkEmbeddingAdminDtos](../../backend/src/main/java/io/queryforge/backend/admin/console/embedding/dto/ChunkEmbeddingAdminDtos.java#L5) | `ChunkEmbeddingMaterializationRequest`, `ChunkEmbeddingMaterializationStatusResponse` | — |

### `admin.console.embedding.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChunkEmbeddingAdminRepository](../../backend/src/main/java/io/queryforge/backend/admin/console/embedding/repository/ChunkEmbeddingAdminRepository.java#L13) | — | — |

### `admin.console.embedding.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChunkEmbeddingAdminService](../../backend/src/main/java/io/queryforge/backend/admin/console/embedding/service/ChunkEmbeddingAdminService.java#L24) | — | `AdminRetrieverConfiguration`, `AdminRuntimeOptionsDtos`, `AdminRuntimeOptionsService`, `ChunkEmbeddingAdminDtos`, `ChunkEmbeddingAdminRepository`, `ConsoleExperimentConfig`, `ConsoleParameters`, `LlmJobDtos`, `LlmJobService` |

### `admin.console.evaluation.anchor.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagRewriteAnchorController](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/anchor/controller/RagRewriteAnchorController.java#L14) | — | `RagRewriteAnchorDtos`, `RagRewriteAnchorRepository` |

### `admin.console.evaluation.anchor.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagRewriteAnchorDtos](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/anchor/dto/RagRewriteAnchorDtos.java#L8) | `RagRewriteAnchorEvalRow`, `AnchorEvaluationCase`, `EvalDatasetAnchorMeta`, `MemoryAnchorSource` | — |

### `admin.console.evaluation.anchor.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagRewriteAnchorRepository](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/anchor/repository/RagRewriteAnchorRepository.java#L27) | — | `ConsoleJdbc`, `RagRewriteAnchorDtos` |

### `admin.console.evaluation.anchor.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagRewriteAnchorEvalService](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/anchor/service/RagRewriteAnchorEvalService.java#L30) | `AnchorDraft` | `AdminPipelineProperties`, `RagRewriteAnchorDtos`, `RagRewriteAnchorRepository` |

### `admin.console.evaluation.anchor.support`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CanonicalAnchorVersionMetadata](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/anchor/support/CanonicalAnchorVersionMetadata.java#L12) | — | `AnchorSchemaVersions` |

### `admin.console.evaluation.dataset.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [EvalDatasetController](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/dataset/controller/EvalDatasetController.java#L15) | — | `EvalDatasetDtos`, `EvalDatasetService` |

### `admin.console.evaluation.dataset.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [EvalDatasetDtos](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/dataset/dto/EvalDatasetDtos.java#L8) | `EvalDatasetRow`, `EvalDatasetItemRow`, `EvalSampleMeta` | — |

### `admin.console.evaluation.dataset.lifecycle.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [EvalDatasetDeletionController](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/dataset/lifecycle/controller/EvalDatasetDeletionController.java#L12) | — | `EvalDatasetDeletionService` |

### `admin.console.evaluation.dataset.lifecycle.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [EvalDatasetDeletionService](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/dataset/lifecycle/service/EvalDatasetDeletionService.java#L17) | — | `AdminEvalDatasetRepository`, `EvalDatasetService`, `RagTestDtos`, `RagTestHistoryService`, `RagTestRunRepository` |

### `admin.console.evaluation.dataset.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AdminEvalDatasetRepository](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/dataset/repository/AdminEvalDatasetRepository.java#L22) | — | `EvalDatasetDtos` |

### `admin.console.evaluation.dataset.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [EvalDatasetService](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/dataset/service/EvalDatasetService.java#L14) | — | `AdminEvalDatasetRepository`, `EvalDatasetDtos` |

### `admin.console.evaluation.debug.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RewriteDebugController](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/debug/controller/RewriteDebugController.java#L15) | — | `RewriteDebugDtos`, `RewriteDebugService` |

### `admin.console.evaluation.debug.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RewriteDebugDtos](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/debug/dto/RewriteDebugDtos.java#L8) | `RewriteDebugRow`, `RewriteDebugDetail` | — |

### `admin.console.evaluation.debug.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RewriteDebugRepository](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/debug/repository/RewriteDebugRepository.java#L22) | — | `ConsoleJdbc`, `RewriteDebugDtos` |

### `admin.console.evaluation.debug.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RewriteDebugService](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/debug/service/RewriteDebugService.java#L12) | — | `RewriteDebugDtos`, `RewriteDebugRepository` |

### `admin.console.evaluation.run.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagTestController](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/run/controller/RagTestController.java#L19) | — | `RagTestDtos`, `RagTestHistoryService`, `RagTestService` |

### `admin.console.evaluation.run.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagTestDtos](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/run/dto/RagTestDtos.java#L12) | `RagTestRunRequest`, `RagTestRunRow`, `RagTestRunDetail`, `RagTestResultDetailRow`, `RagCompareResponse` | `AdminRuntimeOptionsDtos`, `RagRewriteAnchorDtos` |

### `admin.console.evaluation.run.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagTestRunRepository](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/run/repository/RagTestRunRepository.java#L31) | — | `ConsoleJdbc`, `RagRewriteAnchorDtos`, `RagRewriteAnchorRepository`, `RagTestDtos` |

### `admin.console.evaluation.run.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagTestHistoryService](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/run/service/RagTestHistoryService.java#L17) | — | `RagRewriteAnchorRepository`, `RagTestDtos`, `RagTestRunRepository` |
| [RagTestService](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/run/service/RagTestService.java#L42) | — | `AdminEvalDatasetRepository`, `AdminRetrieverConfiguration`, `AdminRuntimeOptionsDtos`, `AdminRuntimeOptionsService`, `CanonicalAnchorVersionMetadata`, `ChunkEmbeddingAdminService`, `ConsoleExperimentConfig`, `ConsoleParameters`, `ConsoleScopeService`, `EvalDatasetService`, `GatingDtos`, `GatingRepository`, `LlmJobService`, `RagTestDtos`, `RagTestRunRepository`, `RewriteLlmPreflight` |
| [RewriteLlmPreflight](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/run/service/RewriteLlmPreflight.java#L13) | — | `ConsoleEnvironment`, `ConsoleParameters` |

### `admin.console.gating.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [GatingController](../../backend/src/main/java/io/queryforge/backend/admin/console/gating/controller/GatingController.java#L17) | — | `GatingDtos`, `GatingService` |

### `admin.console.gating.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [GatingDtos](../../backend/src/main/java/io/queryforge/backend/admin/console/gating/dto/GatingDtos.java#L10) | `GatingBatchRunRequest`, `GatingRunConfig`, `GatingStageFlags`, `GatingRuleConfig`, `GatingWeightsConfig`, `GatingUtilityScoreConfig`, `GatingThresholdConfig`, `GatingBatchRow`, `GatingFunnelResponse`, `GatingResultRow` | `AdminRuntimeOptionsDtos` |

### `admin.console.gating.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [GatingRepository](../../backend/src/main/java/io/queryforge/backend/admin/console/gating/repository/GatingRepository.java#L25) | — | `ConsoleJdbc`, `GatingDtos` |

### `admin.console.gating.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [GatingService](../../backend/src/main/java/io/queryforge/backend/admin/console/gating/service/GatingService.java#L35) | — | `AdminRetrieverConfiguration`, `AdminRuntimeOptionsDtos`, `AdminRuntimeOptionsService`, `AdminSyntheticMethodRepository`, `ConsoleExperimentConfig`, `ConsoleParameters`, `ConsoleScopeService`, `GatingDtos`, `GatingRepository`, `LlmJobService`, `SyntheticGenerationDtos`, `SyntheticGenerationRepository` |

### `admin.console.jobs.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [LlmJobController](../../backend/src/main/java/io/queryforge/backend/admin/console/jobs/controller/LlmJobController.java#L16) | — | `LlmJobDtos`, `LlmJobService` |

### `admin.console.jobs.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [LlmJobDtos](../../backend/src/main/java/io/queryforge/backend/admin/console/jobs/dto/LlmJobDtos.java#L8) | `LlmJobRow`, `LlmJobItemRow` | — |

### `admin.console.jobs.model`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [LlmJobType](../../backend/src/main/java/io/queryforge/backend/admin/console/jobs/model/LlmJobType.java#L5) | — | — |

### `admin.console.jobs.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [LlmJobRepository](../../backend/src/main/java/io/queryforge/backend/admin/console/jobs/repository/LlmJobRepository.java#L20) | — | `LlmJobDtos` |

### `admin.console.jobs.result.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [LlmJobResultService](../../backend/src/main/java/io/queryforge/backend/admin/console/jobs/result/service/LlmJobResultService.java#L33) | — | `AdminEvalDatasetRepository`, `AdminPipelineProperties`, `CanonicalAnchorVersionMetadata`, `EvalDatasetDtos`, `GatingRepository`, `LlmJobDtos`, `LlmJobType`, `RagRewriteAnchorDtos`, `RagRewriteAnchorEvalService`, `RagRewriteAnchorRepository`, `RagTestDtos`, `RagTestRunRepository`, `SyntheticGenerationRepository` |

### `admin.console.jobs.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [LlmJobService](../../backend/src/main/java/io/queryforge/backend/admin/console/jobs/service/LlmJobService.java#L42) | — | `ExperimentCommandDtos`, `ExperimentPipelineService`, `GatingRepository`, `LlmJobDtos`, `LlmJobRepository`, `LlmJobResultService`, `LlmJobType`, `RagTestRunRepository`, `SyntheticGenerationRepository` |

### `admin.console.persistence.support`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ConsoleJdbc](../../backend/src/main/java/io/queryforge/backend/admin/console/persistence/support/ConsoleJdbc.java#L13) | — | — |

### `admin.console.runtime.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AdminRuntimeOptionsController](../../backend/src/main/java/io/queryforge/backend/admin/console/runtime/controller/AdminRuntimeOptionsController.java#L10) | — | `AdminRuntimeOptionsDtos`, `AdminRuntimeOptionsService` |

### `admin.console.runtime.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AdminRuntimeOptionsDtos](../../backend/src/main/java/io/queryforge/backend/admin/console/runtime/dto/AdminRuntimeOptionsDtos.java#L8) | `RetrieverConfigRequest`, `RuntimeOptionsResponse`, `RuntimeOption`, `RuntimeParameterRange`, `RuntimeCatalog`, `RuntimeOptionMetadata` | — |

### `admin.console.runtime.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AdminRetrieverConfiguration](../../backend/src/main/java/io/queryforge/backend/admin/console/runtime/service/AdminRetrieverConfiguration.java#L18) | — | `AdminRuntimeOptionsDtos`, `AdminRuntimeOptionsService`, `ConsoleParameters` |
| [AdminRuntimeOptionsService](../../backend/src/main/java/io/queryforge/backend/admin/console/runtime/service/AdminRuntimeOptionsService.java#L27) | — | `AdminRuntimeOptionsDtos`, `ConsoleEnvironment`, `ConsoleParameters` |
| [ConsoleEnvironment](../../backend/src/main/java/io/queryforge/backend/admin/console/runtime/service/ConsoleEnvironment.java#L12) | — | `AdminPipelineProperties` |

### `admin.console.scope.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ConsoleScopeDtos](../../backend/src/main/java/io/queryforge/backend/admin/console/scope/dto/ConsoleScopeDtos.java#L5) | `SourceStrategyContext`, `DatasetStrategyContext`, `DomainLanguageContext` | — |

### `admin.console.scope.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AdminConsoleDomainScopeRepository](../../backend/src/main/java/io/queryforge/backend/admin/console/scope/repository/AdminConsoleDomainScopeRepository.java#L18) | — | `ConsoleScopeDtos` |

### `admin.console.scope.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ConsoleScopeService](../../backend/src/main/java/io/queryforge/backend/admin/console/scope/service/ConsoleScopeService.java#L21) | `StrategyScope`, `SourceResolution`, `DatasetResolution` | `AdminConsoleDomainScopeRepository`, `AdminSyntheticMethodRepository`, `ConsoleParameters`, `ConsoleScopeDtos` |

### `admin.console.synthetic.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [SyntheticGenerationController](../../backend/src/main/java/io/queryforge/backend/admin/console/synthetic/controller/SyntheticGenerationController.java#L18) | — | `SyntheticGenerationDtos`, `SyntheticGenerationService` |

### `admin.console.synthetic.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [SyntheticGenerationDtos](../../backend/src/main/java/io/queryforge/backend/admin/console/synthetic/dto/SyntheticGenerationDtos.java#L8) | `SyntheticGenerationMethod`, `SyntheticBatchRunRequest`, `SyntheticGenerationBatchRow`, `SyntheticQueryRow`, `SyntheticQueryDetailResponse`, `SyntheticStatsResponse` | — |

### `admin.console.synthetic.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AdminConsoleStrategyTables](../../backend/src/main/java/io/queryforge/backend/admin/console/synthetic/repository/AdminConsoleStrategyTables.java#L5) | — | — |
| [AdminSyntheticMethodRepository](../../backend/src/main/java/io/queryforge/backend/admin/console/synthetic/repository/AdminSyntheticMethodRepository.java#L18) | — | `SyntheticGenerationDtos` |
| [SyntheticGenerationRepository](../../backend/src/main/java/io/queryforge/backend/admin/console/synthetic/repository/SyntheticGenerationRepository.java#L23) | — | `AdminConsoleStrategyTables`, `ConsoleJdbc`, `SyntheticGenerationDtos` |

### `admin.console.synthetic.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [SyntheticGenerationService](../../backend/src/main/java/io/queryforge/backend/admin/console/synthetic/service/SyntheticGenerationService.java#L28) | — | `AdminRuntimeOptionsDtos`, `AdminRuntimeOptionsService`, `AdminSyntheticMethodRepository`, `ConsoleExperimentConfig`, `ConsoleParameters`, `ConsoleScopeService`, `LlmJobService`, `SyntheticGenerationDtos`, `SyntheticGenerationRepository` |

### `admin.corpus.anchor.evaluation.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AnchorEvalController](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/evaluation/controller/AnchorEvalController.java#L17) | — | `AnchorEvalDtos`, `AnchorEvalService` |

### `admin.corpus.anchor.evaluation.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AnchorEvalDtos](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/evaluation/dto/AnchorEvalDtos.java#L10) | `AnchorEvalRunCreateRequest`, `AnchorEvalRunSummary`, `AnchorEvalCandidateDto`, `AnchorEvalSampleDto`, `AnchorEvalRunDetail`, `AnchorEvalLabelRequest` | — |

### `admin.corpus.anchor.evaluation.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AnchorEvalRepository](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/evaluation/repository/AnchorEvalRepository.java#L26) | — | `AnchorEvalDtos`, `CorpusDocumentDtos`, `CorpusJdbc` |

### `admin.corpus.anchor.evaluation.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AnchorEvalService](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/evaluation/service/AnchorEvalService.java#L14) | — | `AnchorEvalDtos`, `AnchorEvalRepository`, `CorpusDocumentDtos` |

### `admin.corpus.anchor.extraction.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AnchorExtractionController](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/extraction/controller/AnchorExtractionController.java#L11) | — | `AnchorExtractionDtos`, `AnchorExtractionService` |

### `admin.corpus.anchor.extraction.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AnchorExtractionDtos](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/extraction/dto/AnchorExtractionDtos.java#L7) | `AnchorExtractRequest`, `AnchorExtractResponse`, `AnchorChunkRow`, `AnchorCandidate`, `TermRefreshSummary`, `AnchorRemapSummary` | — |

### `admin.corpus.anchor.extraction.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AnchorExtractionRepository](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/extraction/repository/AnchorExtractionRepository.java#L17) | `TermStatRow` | `AnchorExtractionDtos` |

### `admin.corpus.anchor.extraction.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AnchorExtractionService](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/extraction/service/AnchorExtractionService.java#L32) | — | `AdminPipelineProperties`, `AnchorExtractionDtos`, `AnchorExtractionRepository`, `SourceCatalogService` |

### `admin.corpus.anchor.normalization.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AnchorNormalizationController](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/normalization/controller/AnchorNormalizationController.java#L18) | — | `AnchorNormalizationDtos`, `AnchorNormalizationService` |

### `admin.corpus.anchor.normalization.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AnchorNormalizationDtos](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/normalization/dto/AnchorNormalizationDtos.java#L10) | `AnchorNormalizationRunCreateRequest`, `AnchorNormalizationReviewRequest`, `AnchorNormalizationCandidateReviewRequest`, `AnchorNormalizationCandidateDecision`, `AnchorNormalizationCandidateReviewBatchRequest`, `AnchorNormalizationRunSummary`, `AnchorNormalizationCandidateDto`, `AnchorNormalizationRunDetail`, `TargetAnchor`, `CandidateDraft` | — |

### `admin.corpus.anchor.normalization.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AnchorNormalizationRepository](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/normalization/repository/AnchorNormalizationRepository.java#L25) | — | `AnchorNormalizationDtos`, `AnchorSchemaVersions` |

### `admin.corpus.anchor.normalization.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AnchorNormalizationService](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/normalization/service/AnchorNormalizationService.java#L26) | — | `AnchorNormalizationDtos`, `AnchorNormalizationRepository`, `AnchorSchemaVersions` |

### `admin.corpus.anchor.query.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusAnchorController](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/query/controller/CorpusAnchorController.java#L13) | — | `CorpusAnchorDtos`, `CorpusAnchorService` |

### `admin.corpus.anchor.query.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusAnchorDtos](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/query/dto/CorpusAnchorDtos.java#L6) | `AnchorSummary` | — |

### `admin.corpus.anchor.query.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusAnchorRepository](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/query/repository/CorpusAnchorRepository.java#L19) | — | `CorpusAnchorDtos`, `CorpusJdbc` |

### `admin.corpus.anchor.query.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusAnchorService](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/query/service/CorpusAnchorService.java#L11) | — | `CorpusAnchorDtos`, `CorpusAnchorRepository` |

### `admin.corpus.anchor.relation.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [MultiSourceAnchorController](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/relation/controller/MultiSourceAnchorController.java#L17) | — | `MultiSourceAnchorDtos`, `MultiSourceAnchorService` |

### `admin.corpus.anchor.relation.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [MultiSourceAnchorDtos](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/relation/dto/MultiSourceAnchorDtos.java#L10) | `MultiSourceAnchorBuildRequest`, `MultiSourceAnchorBuildRunSummary` | — |

### `admin.corpus.anchor.relation.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [MultiSourceAnchorRepository](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/relation/repository/MultiSourceAnchorRepository.java#L22) | — | `AnchorSchemaVersions`, `MultiSourceAnchorDtos` |

### `admin.corpus.anchor.relation.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [MultiSourceAnchorService](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/relation/service/MultiSourceAnchorService.java#L20) | — | `MultiSourceAnchorDtos`, `MultiSourceAnchorRepository` |

### `admin.corpus.anchor.support`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AnchorSchemaVersions](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/support/AnchorSchemaVersions.java#L3) | — | — |

### `admin.corpus.document.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusDocumentController](../../backend/src/main/java/io/queryforge/backend/admin/corpus/document/controller/CorpusDocumentController.java#L15) | — | `CorpusDocumentDtos`, `CorpusDocumentService` |

### `admin.corpus.document.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusDocumentDtos](../../backend/src/main/java/io/queryforge/backend/admin/corpus/document/dto/CorpusDocumentDtos.java#L9) | `DocumentSummary`, `DocumentDetail`, `SectionDto`, `ChunkSummary`, `ChunkDetail`, `ChunkNeighborDto`, `RawVsCleanedPreview`, `ChunkBoundaryDto`, `ChunkBoundaryPreview` | — |

### `admin.corpus.document.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusDocumentRepository](../../backend/src/main/java/io/queryforge/backend/admin/corpus/document/repository/CorpusDocumentRepository.java#L23) | — | `CorpusDocumentDtos`, `CorpusJdbc` |

### `admin.corpus.document.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusDocumentService](../../backend/src/main/java/io/queryforge/backend/admin/corpus/document/service/CorpusDocumentService.java#L14) | — | `CorpusDocumentDtos`, `CorpusDocumentRepository` |

### `admin.corpus.glossary.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusGlossaryController](../../backend/src/main/java/io/queryforge/backend/admin/corpus/glossary/controller/CorpusGlossaryController.java#L19) | — | `CorpusGlossaryDtos`, `CorpusGlossaryService` |

### `admin.corpus.glossary.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusGlossaryDtos](../../backend/src/main/java/io/queryforge/backend/admin/corpus/glossary/dto/CorpusGlossaryDtos.java#L10) | `GlossaryAliasDto`, `GlossaryTermSummary`, `GlossaryTermDetail`, `GlossaryEvidenceDto`, `TopTermPreview`, `GlossaryTermPatchRequest`, `GlossaryAliasCreateRequest` | — |

### `admin.corpus.glossary.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusGlossaryRepository](../../backend/src/main/java/io/queryforge/backend/admin/corpus/glossary/repository/CorpusGlossaryRepository.java#L23) | — | `CorpusGlossaryDtos`, `CorpusJdbc` |

### `admin.corpus.glossary.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusGlossaryService](../../backend/src/main/java/io/queryforge/backend/admin/corpus/glossary/service/CorpusGlossaryService.java#L12) | — | `CorpusGlossaryDtos`, `CorpusGlossaryRepository` |

### `admin.corpus.persistence.support`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusJdbc](../../backend/src/main/java/io/queryforge/backend/admin/corpus/persistence/support/CorpusJdbc.java#L12) | — | — |

### `admin.corpus.run.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusRunController](../../backend/src/main/java/io/queryforge/backend/admin/corpus/run/controller/CorpusRunController.java#L15) | — | `CorpusRunDtos`, `CorpusRunService` |

### `admin.corpus.run.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusRunDtos](../../backend/src/main/java/io/queryforge/backend/admin/corpus/run/dto/CorpusRunDtos.java#L9) | `RunSummary`, `RunStep`, `RunDetail` | — |

### `admin.corpus.run.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusRunRepository](../../backend/src/main/java/io/queryforge/backend/admin/corpus/run/repository/CorpusRunRepository.java#L22) | — | `CorpusJdbc`, `CorpusRunDtos` |

### `admin.corpus.run.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusRunService](../../backend/src/main/java/io/queryforge/backend/admin/corpus/run/service/CorpusRunService.java#L12) | — | `CorpusRunDtos`, `CorpusRunRepository` |

### `admin.corpus.source.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusSourceController](../../backend/src/main/java/io/queryforge/backend/admin/corpus/source/controller/CorpusSourceController.java#L18) | — | `CorpusSourceDtos`, `CorpusSourceService` |

### `admin.corpus.source.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusSourceDtos](../../backend/src/main/java/io/queryforge/backend/admin/corpus/source/dto/CorpusSourceDtos.java#L10) | `SourceSummary`, `SourceUpdateRequest`, `SourceUpsertRequest`, `SourceAutoRegisterRequest` | — |

### `admin.corpus.source.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusSourceRepository](../../backend/src/main/java/io/queryforge/backend/admin/corpus/source/repository/CorpusSourceRepository.java#L18) | — | `CorpusJdbc`, `CorpusSourceDtos` |

### `admin.corpus.source.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusSourceService](../../backend/src/main/java/io/queryforge/backend/admin/corpus/source/service/CorpusSourceService.java#L19) | — | `CorpusSourceDtos`, `CorpusSourceRepository`, `DomainSourceDtos`, `DomainSourceService`, `SourceCatalogService` |

### `admin.domain.dashboard.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DomainDashboardController](../../backend/src/main/java/io/queryforge/backend/admin/domain/dashboard/controller/DomainDashboardController.java#L11) | — | `DomainDashboardDtos`, `DomainDashboardService` |

### `admin.domain.dashboard.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DomainDashboardDtos](../../backend/src/main/java/io/queryforge/backend/admin/domain/dashboard/dto/DomainDashboardDtos.java#L6) | `DomainDashboardSummary` | — |

### `admin.domain.dashboard.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DomainDashboardRepository](../../backend/src/main/java/io/queryforge/backend/admin/domain/dashboard/repository/DomainDashboardRepository.java#L18) | — | `DomainDashboardDtos` |

### `admin.domain.dashboard.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DomainDashboardService](../../backend/src/main/java/io/queryforge/backend/admin/domain/dashboard/service/DomainDashboardService.java#L8) | — | `DomainDashboardDtos`, `DomainDashboardRepository` |

### `admin.domain.identity.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DomainIdentityDtos](../../backend/src/main/java/io/queryforge/backend/admin/domain/identity/dto/DomainIdentityDtos.java#L8) | `DomainSummary`, `DomainCreateRequest`, `DomainUpdateRequest` | — |

### `admin.domain.identity.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DomainIdentityRepository](../../backend/src/main/java/io/queryforge/backend/admin/domain/identity/repository/DomainIdentityRepository.java#L22) | — | `DomainIdentityDtos` |

### `admin.domain.identity.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DomainIdentityService](../../backend/src/main/java/io/queryforge/backend/admin/domain/identity/service/DomainIdentityService.java#L14) | — | `DomainIdentityDtos`, `DomainIdentityRepository` |

### `admin.domain.policy.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DomainMethodPolicy](../../backend/src/main/java/io/queryforge/backend/admin/domain/policy/dto/DomainMethodPolicy.java#L7) | — | — |

### `admin.domain.policy.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DomainMethodPolicyRepository](../../backend/src/main/java/io/queryforge/backend/admin/domain/policy/repository/DomainMethodPolicyRepository.java#L18) | — | `DomainMethodPolicy` |

### `admin.domain.source.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DomainSourceDtos](../../backend/src/main/java/io/queryforge/backend/admin/domain/source/dto/DomainSourceDtos.java#L5) | `DomainSource`, `DomainSourceAttachRequest` | — |

### `admin.domain.source.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DomainSourceRepository](../../backend/src/main/java/io/queryforge/backend/admin/domain/source/repository/DomainSourceRepository.java#L18) | — | `DomainSourceDtos` |

### `admin.domain.source.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DomainSourceService](../../backend/src/main/java/io/queryforge/backend/admin/domain/source/service/DomainSourceService.java#L13) | — | `DomainIdentityService`, `DomainSourceDtos`, `DomainSourceRepository` |

### `admin.domain.workspace.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DomainWorkspaceController](../../backend/src/main/java/io/queryforge/backend/admin/domain/workspace/controller/DomainWorkspaceController.java#L19) | — | `DomainDetail`, `DomainIdentityDtos`, `DomainSourceDtos`, `DomainWorkspaceService` |

### `admin.domain.workspace.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DomainDetail](../../backend/src/main/java/io/queryforge/backend/admin/domain/workspace/dto/DomainDetail.java#L9) | — | `DomainIdentityDtos`, `DomainMethodPolicy`, `DomainSourceDtos` |

### `admin.domain.workspace.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DomainWorkspaceService](../../backend/src/main/java/io/queryforge/backend/admin/domain/workspace/service/DomainWorkspaceService.java#L15) | — | `DomainDetail`, `DomainIdentityDtos`, `DomainIdentityService`, `DomainMethodPolicyRepository`, `DomainSourceDtos`, `DomainSourceService` |

### `admin.pipeline.artifact.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DocumentArtifactDtos](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/artifact/dto/DocumentArtifactDtos.java#L5) | `PersistResult` | — |

### `admin.pipeline.artifact.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DocumentArtifactStoreService](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/artifact/service/DocumentArtifactStoreService.java#L32) | `Stage`, `StageMetadata`, `DocumentManifest`, `AggregatedGlossaryRow` | `AdminPipelineProperties`, `DocumentArtifactDtos`, `SourceCatalogService` |

### `admin.pipeline.configuration`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AdminPipelineConfiguration](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/configuration/AdminPipelineConfiguration.java#L12) | — | — |
| [AdminPipelineProperties](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/configuration/AdminPipelineProperties.java#L5) | — | — |

### `admin.pipeline.dashboard.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [PipelineDashboardController](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/dashboard/controller/PipelineDashboardController.java#L13) | — | `PipelineDashboardDtos`, `PipelineDashboardService` |

### `admin.pipeline.dashboard.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [PipelineDashboardDtos](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/dashboard/dto/PipelineDashboardDtos.java#L7) | `DashboardStats`, `ProductDocumentStat`, `RecentRunStat`, `FailedStepStat` | — |

### `admin.pipeline.dashboard.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [PipelineDashboardRepository](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/dashboard/repository/PipelineDashboardRepository.java#L17) | — | `PipelineDashboardDtos` |

### `admin.pipeline.dashboard.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [PipelineDashboardService](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/dashboard/service/PipelineDashboardService.java#L12) | — | `PipelineDashboardDtos`, `PipelineDashboardRepository`, `SourceCatalogService` |

### `admin.pipeline.execution.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [PipelineAdminController](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/execution/controller/PipelineAdminController.java#L18) | — | `CorpusRunDtos`, `PipelineAdminService`, `PipelineExecutionDtos` |

### `admin.pipeline.execution.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [PipelineExecutionDtos](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/execution/dto/PipelineExecutionDtos.java#L11) | `PipelineRunRequest`, `PipelineRunActionResponse`, `StepLogDto`, `PipelineRunLogsResponse`, `CommandRequest`, `CommandResult` | — |

### `admin.pipeline.execution.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [PipelineAdminRepository](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/execution/repository/PipelineAdminRepository.java#L19) | — | — |

### `admin.pipeline.execution.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [PipelineAdminService](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/execution/service/PipelineAdminService.java#L39) | `StepWarningDecision`, `Scope`, `ArtifactContext`, `StepPlan`, `ManagedRunContext` | `AdminPipelineProperties`, `CorpusRunDtos`, `CorpusRunService`, `CorpusSourceDtos`, `CorpusSourceService`, `DocumentArtifactDtos`, `DocumentArtifactStoreService`, `PipelineAdminRepository`, `PipelineCommandRunner`, `PipelineExecutionDtos`, `PipelineStepPlanFactory`, `SourceCatalogService` |
| [PipelineCommandRunner](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/execution/service/PipelineCommandRunner.java#L8) | `ProcessObserver` | `PipelineExecutionDtos` |
| [PipelineStepPlanFactory](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/execution/service/PipelineStepPlanFactory.java#L18) | `DatabaseTarget` | `AdminPipelineProperties`, `PipelineAdminService`, `SourceCatalogService` |
| [SubprocessPipelineCommandRunner](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/execution/service/SubprocessPipelineCommandRunner.java#L19) | — | `PipelineCommandRunner`, `PipelineExecutionDtos` |

### `admin.pipeline.source.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [PipelineSourceRepository](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/source/repository/PipelineSourceRepository.java#L15) | — | — |

### `admin.pipeline.source.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ProjectPathResolver](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/source/service/ProjectPathResolver.java#L11) | — | — |
| [SourceCatalogService](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/source/service/SourceCatalogService.java#L19) | — | `AdminPipelineProperties`, `PipelineSourceRepository`, `ProjectPathResolver` |

### `admin.prompt.asset.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [PromptAssetController](../../backend/src/main/java/io/queryforge/backend/admin/prompt/asset/controller/PromptAssetController.java#L18) | — | `PromptAssetDtos`, `PromptAssetService` |

### `admin.prompt.asset.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [PromptAssetDtos](../../backend/src/main/java/io/queryforge/backend/admin/prompt/asset/dto/PromptAssetDtos.java#L8) | `PromptAssetRow`, `PromptAssetDetail`, `PromptRevisionRequest`, `PromptAssetUpdateRequest` | — |

### `admin.prompt.asset.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [PromptAssetRepository](../../backend/src/main/java/io/queryforge/backend/admin/prompt/asset/repository/PromptAssetRepository.java#L22) | — | `PromptAssetDtos` |

### `admin.prompt.asset.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [PromptAssetService](../../backend/src/main/java/io/queryforge/backend/admin/prompt/asset/service/PromptAssetService.java#L14) | — | `PromptAssetDtos`, `PromptAssetRepository` |

### `admin.prompt.binding.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [PromptBindingController](../../backend/src/main/java/io/queryforge/backend/admin/prompt/binding/controller/PromptBindingController.java#L17) | — | `PromptBindingDtos`, `PromptBindingService` |

### `admin.prompt.binding.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [PromptBindingDtos](../../backend/src/main/java/io/queryforge/backend/admin/prompt/binding/dto/PromptBindingDtos.java#L9) | `PromptBindingRow`, `PromptBindingUpdateRequest`, `PromptValidationRequest`, `PromptValidationResponse` | — |

### `admin.prompt.binding.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [PromptBindingRepository](../../backend/src/main/java/io/queryforge/backend/admin/prompt/binding/repository/PromptBindingRepository.java#L22) | — | `PromptBindingDtos` |

### `admin.prompt.binding.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [PromptBindingService](../../backend/src/main/java/io/queryforge/backend/admin/prompt/binding/service/PromptBindingService.java#L12) | — | `PromptBindingDtos`, `PromptBindingRepository` |

### `admin.web.advice`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AdminApiExceptionHandler](../../backend/src/main/java/io/queryforge/backend/admin/web/advice/AdminApiExceptionHandler.java#L8) | — | — |

### `common.logging.aspect`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ApplicationLayerLoggingAspect](../../backend/src/main/java/io/queryforge/backend/common/logging/aspect/ApplicationLayerLoggingAspect.java#L30) | — | — |

### `io.queryforge.backend`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [QueryForgeApplication](../../backend/src/main/java/io/queryforge/backend/QueryForgeApplication.java#L7) | — | — |

### `rag.chat.answer.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChatAnswerDtos](../../backend/src/main/java/io/queryforge/backend/rag/chat/answer/dto/ChatAnswerDtos.java#L5) | `GeneratedAnswer` | — |

### `rag.chat.answer.exception`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [GeminiServiceUnavailableException](../../backend/src/main/java/io/queryforge/backend/rag/chat/answer/exception/GeminiServiceUnavailableException.java#L3) | — | — |

### `rag.chat.answer.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChatAnswerService](../../backend/src/main/java/io/queryforge/backend/rag/chat/answer/service/ChatAnswerService.java#L30) | — | `ChatAnswerDtos`, `GeminiServiceUnavailableException`, `RetrievalDoc`, `RuntimeEnvService`, `SpringAiChatGateway` |

### `rag.chat.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagController](../../backend/src/main/java/io/queryforge/backend/rag/chat/controller/RagController.java#L11) | — | `ChatDtos`, `RagService` |

### `rag.chat.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChatDtos](../../backend/src/main/java/io/queryforge/backend/rag/chat/dto/ChatDtos.java#L13) | `AskRequest`, `AskResponse` | `AgenticRetrievalDtos`, `ChatConfigDtos`, `RetrievalDtos`, `RewriteDtos` |

### `rag.chat.routing.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChatRequestRouting](../../backend/src/main/java/io/queryforge/backend/rag/chat/routing/service/ChatRequestRouting.java#L10) | — | `ChatConfigDtos`, `ChatReadinessDtos`, `QueryRouteContext` |

### `rag.chat.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AgenticChatService](../../backend/src/main/java/io/queryforge/backend/rag/chat/service/AgenticChatService.java#L35) | — | `AgenticExecutionDtos`, `AgenticRetrievalDtos`, `AgenticRetrievalService`, `AgenticTracePersistenceService`, `AgenticTraceWriteDtos`, `ChatConfigDtos`, `ChatDtos`, `ChatReadinessDtos`, `ChatResponseAssembler`, `MemoryCandidate`, `QueryRouteDecision`, `RagPersistPolicy`, `RagTraceRepository`, `RetrievalDoc`, `RetrievalDtos`, `RewriteScoring`, `TraceWriteResults` |
| [ChatResponseAssembler](../../backend/src/main/java/io/queryforge/backend/rag/chat/service/ChatResponseAssembler.java#L26) | `AgenticSettings`, `AnswerDraft` | `AgenticExecutionDtos`, `AgenticRetrievalDtos`, `ChatAnswerDtos`, `ChatAnswerService`, `ChatConfigDtos`, `MemoryCandidate`, `QueryRouteDecision`, `RetrievalDoc`, `RetrievalDtos`, `RewriteDtos` |
| [RagService](../../backend/src/main/java/io/queryforge/backend/rag/chat/service/RagService.java#L47) | `GeneratedCandidate`, `Decision` | `AgenticChatService`, `ChatConfigDtos`, `ChatDtos`, `ChatReadinessDtos`, `ChatReadinessService`, `ChatRequestRouting`, `ChatResponseAssembler`, `ChatRuntimeConfigService`, `CohereRerankService`, `DomainScopedRetrievalService`, `MemoryCandidate`, `QueryRouteDecision`, `QueryStrategy`, `QueryStrategyRouter`, `RagPersistPolicy`, `RagRetrievalExecutionService`, `RagTracePersistenceService`, `RagTraceRepository`, `RagTraceWriteDtos`, `RetrievalDoc`, `RetrievalDtos`, `RetrievalExecutionDtos`, `RewriteCandidateService`, `RewriteDtos`, `RewriteScoring`, `TraceWriteResults` |

### `rag.embedding.reindex.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagReindexController](../../backend/src/main/java/io/queryforge/backend/rag/embedding/reindex/controller/RagReindexController.java#L11) | — | `RagReindexDtos`, `RagReindexService` |

### `rag.embedding.reindex.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagReindexDtos](../../backend/src/main/java/io/queryforge/backend/rag/embedding/reindex/dto/RagReindexDtos.java#L5) | `ReindexRequest`, `ReindexResponse`, `ChunkSource`, `MemorySource` | — |

### `rag.embedding.reindex.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagEmbeddingRepository](../../backend/src/main/java/io/queryforge/backend/rag/embedding/reindex/repository/RagEmbeddingRepository.java#L17) | — | `RagJdbc`, `RagReindexDtos` |

### `rag.embedding.reindex.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagReindexService](../../backend/src/main/java/io/queryforge/backend/rag/embedding/reindex/service/RagReindexService.java#L11) | — | `HashEmbeddingService`, `RagEmbeddingRepository`, `RagReindexDtos` |

### `rag.embedding.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DenseEmbeddingService](../../backend/src/main/java/io/queryforge/backend/rag/embedding/service/DenseEmbeddingService.java#L24) | — | `AdminPipelineProperties`, `SpringAiEmbeddingGateway` |
| [HashEmbeddingService](../../backend/src/main/java/io/queryforge/backend/rag/embedding/service/HashEmbeddingService.java#L13) | — | — |

### `rag.eval.report.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [EvalReportController](../../backend/src/main/java/io/queryforge/backend/rag/eval/report/controller/EvalReportController.java#L10) | — | `EvalReportDtos`, `EvalReportService` |

### `rag.eval.report.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [EvalReportDtos](../../backend/src/main/java/io/queryforge/backend/rag/eval/report/dto/EvalReportDtos.java#L5) | `EvalReportResponse` | — |

### `rag.eval.report.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [EvalReportService](../../backend/src/main/java/io/queryforge/backend/rag/eval/report/service/EvalReportService.java#L19) | — | `EvalReportDtos` |

### `rag.eval.retrieval.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagRetrievalEvalController](../../backend/src/main/java/io/queryforge/backend/rag/eval/retrieval/controller/RagRetrievalEvalController.java#L11) | — | `RagRetrievalEvalDtos`, `RagRetrievalEvalService` |

### `rag.eval.retrieval.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagRetrievalEvalDtos](../../backend/src/main/java/io/queryforge/backend/rag/eval/retrieval/dto/RagRetrievalEvalDtos.java#L11) | `RagRetrievalEvalRequest`, `RagRetrievalEvalResponse`, `RagRetrievalEvalDoc`, `RagEvalTrace` | `RagLlmCallCount`, `RagPersistPolicy` |

### `rag.eval.retrieval.exception`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagRetrievalEvalException](../../backend/src/main/java/io/queryforge/backend/rag/eval/retrieval/exception/RagRetrievalEvalException.java#L3) | — | — |

### `rag.eval.retrieval.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [EvalRuntimeConfigService](../../backend/src/main/java/io/queryforge/backend/rag/eval/retrieval/service/EvalRuntimeConfigService.java#L16) | `RuntimeEvalContext`, `AgenticEvalSettings` | `ChatConfigDtos`, `ChatReadinessDtos`, `RagRetrievalEvalDtos` |
| [RagRetrievalEvalService](../../backend/src/main/java/io/queryforge/backend/rag/eval/retrieval/service/RagRetrievalEvalService.java#L44) | — | `AgenticExecutionDtos`, `AgenticRetrievalService`, `ChatConfigDtos`, `ChatReadinessDtos`, `ChatReadinessService`, `ChatRuntimeConfigService`, `DomainScopedRetrievalService`, `EvalRuntimeConfigService`, `MemoryCandidate`, `QueryRouteContext`, `QueryRouteDecision`, `QueryStrategyRouter`, `RagLlmCallCount`, `RagPersistPolicy`, `RagRetrievalEvalDtos`, `RagRetrievalEvalException`, `RagRetrievalExecutionService`, `RetrievalDoc`, `RetrievalDtos`, `RetrievalExecutionDtos` |

### `rag.experiment.command.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ExperimentCommandController](../../backend/src/main/java/io/queryforge/backend/rag/experiment/command/controller/ExperimentCommandController.java#L11) | — | `ExperimentCommandDtos`, `ExperimentPipelineService` |

### `rag.experiment.command.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ExperimentCommandDtos](../../backend/src/main/java/io/queryforge/backend/rag/experiment/command/dto/ExperimentCommandDtos.java#L5) | `ExperimentCommandRequest`, `ExperimentCommandResponse` | — |

### `rag.experiment.command.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ExperimentPipelineService](../../backend/src/main/java/io/queryforge/backend/rag/experiment/command/service/ExperimentPipelineService.java#L28) | — | `AdminPipelineProperties`, `ExperimentCommandDtos` |

### `rag.experiment.summary.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ExperimentSummaryController](../../backend/src/main/java/io/queryforge/backend/rag/experiment/summary/controller/ExperimentSummaryController.java#L13) | — | `ExperimentSummaryDtos`, `RagExperimentQueryService` |

### `rag.experiment.summary.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ExperimentSummaryDtos](../../backend/src/main/java/io/queryforge/backend/rag/experiment/summary/dto/ExperimentSummaryDtos.java#L8) | `ExperimentSummaryResponse`, `ExperimentRunSummary` | — |

### `rag.experiment.summary.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagExperimentRepository](../../backend/src/main/java/io/queryforge/backend/rag/experiment/summary/repository/RagExperimentRepository.java#L19) | — | `ExperimentSummaryDtos`, `RagJdbc` |

### `rag.experiment.summary.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagExperimentQueryService](../../backend/src/main/java/io/queryforge/backend/rag/experiment/summary/service/RagExperimentQueryService.java#L12) | — | `ExperimentSummaryDtos`, `RagExperimentRepository` |

### `rag.persistence.support`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagJdbc](../../backend/src/main/java/io/queryforge/backend/rag/persistence/support/RagJdbc.java#L13) | — | — |

### `rag.provider.environment.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RuntimeEnvService](../../backend/src/main/java/io/queryforge/backend/rag/provider/environment/service/RuntimeEnvService.java#L14) | — | `AdminPipelineProperties` |

### `rag.provider.springai.gateway`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [SpringAiChatGateway](../../backend/src/main/java/io/queryforge/backend/rag/provider/springai/gateway/SpringAiChatGateway.java#L12) | — | `RuntimeEnvService` |
| [SpringAiEmbeddingGateway](../../backend/src/main/java/io/queryforge/backend/rag/provider/springai/gateway/SpringAiEmbeddingGateway.java#L15) | — | `RuntimeEnvService` |
| [SpringAiVectorStoreGateway](../../backend/src/main/java/io/queryforge/backend/rag/provider/springai/gateway/SpringAiVectorStoreGateway.java#L8) | — | — |

### `rag.retrieval.agentic.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AgenticExecutionDtos](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/agentic/dto/AgenticExecutionDtos.java#L14) | `AgenticExecutionRequest`, `AgenticExecutionResult`, `PersistedRewriteCandidate` | `AgenticPlanningDtos`, `AgenticRetrievalDtos`, `ChatConfigDtos`, `ChatReadinessDtos`, `MemoryCandidate`, `RagPersistPolicy`, `RetrievalDoc` |
| [AgenticRetrievalDtos](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/agentic/dto/AgenticRetrievalDtos.java#L10) | `SubqueryRetrievalTrace`, `AgenticRetrievalMetadata` | `AgenticPlanningDtos`, `RetrievalDtos`, `RewriteDtos` |

### `rag.retrieval.agentic.fusion.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [SearchResultMerger](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/agentic/fusion/service/SearchResultMerger.java#L11) | `Accumulator` | `RetrievalDoc` |

### `rag.retrieval.agentic.planning.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AgenticPlanningDtos](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/agentic/planning/dto/AgenticPlanningDtos.java#L8) | `AgenticSubquery`, `AgenticQueryPlan` | — |

### `rag.retrieval.agentic.planning.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AgenticQueryPlannerService](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/agentic/planning/service/AgenticQueryPlannerService.java#L28) | `PlannerPayload` | `AgenticPlanningDtos`, `ChatConfigDtos`, `MemoryCandidate`, `RuntimeEnvService`, `SpringAiChatGateway` |

### `rag.retrieval.agentic.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AgenticRetrievalService](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/agentic/service/AgenticRetrievalService.java#L42) | `SubqueryExecution`, `GeneratedCandidate`, `Decision` | `AgenticExecutionDtos`, `AgenticPlanningDtos`, `AgenticQueryPlannerService`, `AgenticRetrievalDtos`, `AgenticTracePersistenceService`, `AgenticTraceWriteDtos`, `ChatConfigDtos`, `ChatReadinessDtos`, `CohereRerankService`, `DomainScopedRetrievalService`, `MemoryCandidate`, `QueryRouteContext`, `QueryRouteDecision`, `QueryStrategy`, `QueryStrategyRouter`, `RagPersistPolicy`, `RetrievalDoc`, `RetrievalDtos`, `RewriteCandidateService`, `RewriteDtos`, `SearchResultMerger` |

### `rag.retrieval.candidate.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RetrievalDtos](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/candidate/dto/RetrievalDtos.java#L3) | `ScoredDocumentDto`, `RetrievalRuntime` | — |

### `rag.retrieval.candidate.model`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RetrievalDoc](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/candidate/model/RetrievalDoc.java#L3) | — | — |

### `rag.retrieval.candidate.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChunkRetrievalRepository](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/candidate/repository/ChunkRetrievalRepository.java#L15) | — | `RetrievalDoc` |

### `rag.retrieval.candidate.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DomainScopedRetrievalService](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/candidate/service/DomainScopedRetrievalService.java#L25) | — | `ChatConfigDtos`, `ChunkRetrievalRepository`, `DenseEmbeddingService`, `HashEmbeddingService`, `MemoryCandidate`, `RagMemoryRepository`, `RetrievalDoc`, `RetrievalDtos` |

### `rag.retrieval.execution.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagExecutionTrace](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/execution/dto/RagExecutionTrace.java#L6) | — | `ForcedRetrievalMode` |
| [RagLlmCallCount](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/execution/dto/RagLlmCallCount.java#L3) | — | — |
| [RagRetrievalExecutionRequest](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/execution/dto/RagRetrievalExecutionRequest.java#L9) | — | `ForcedRetrievalMode`, `RagPersistPolicy` |
| [RagRetrievalExecutionResult](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/execution/dto/RagRetrievalExecutionResult.java#L7) | — | `ForcedRetrievalMode`, `RagExecutionTrace`, `RagLlmCallCount`, `RagRetrievedDoc` |
| [RagRetrievedDoc](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/execution/dto/RagRetrievedDoc.java#L7) | — | — |
| [RetrievalExecutionDtos](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/execution/dto/RetrievalExecutionDtos.java#L13) | `RawOnlyExecutionRequest`, `NonAgenticExecutionResult`, `NonAgenticExecutionKind`, `RetrievalMaterial`, `RawOnlyExecutionResult`, `SelectiveRewriteExecutionRequest`, `SelectiveRewriteExecutionResult`, `AnchorAwareRewriteExecutionRequest`, `AnchorAwareRewriteExecutionResult`, `ExecutedRewriteCandidate` | `MemoryCandidate`, `RetrievalDoc`, `RetrievalDtos` |

### `rag.retrieval.execution.model`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ForcedRetrievalMode](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/execution/model/ForcedRetrievalMode.java#L3) | — | — |
| [RagPersistPolicy](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/execution/model/RagPersistPolicy.java#L3) | — | — |

### `rag.retrieval.execution.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagRetrievalExecutionService](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/execution/service/RagRetrievalExecutionService.java#L27) | — | `CohereRerankService`, `DomainScopedRetrievalService`, `MemoryCandidate`, `RetrievalDoc`, `RetrievalDtos`, `RetrievalExecutionDtos`, `RewriteCandidateService`, `RewriteDtos` |

### `rag.retrieval.memory.model`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [MemoryCandidate](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/memory/model/MemoryCandidate.java#L7) | — | — |

### `rag.retrieval.memory.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagMemoryRepository](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/memory/repository/RagMemoryRepository.java#L20) | — | `MemoryCandidate`, `RagJdbc` |

### `rag.retrieval.rerank.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CohereRerankService](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/rerank/service/CohereRerankService.java#L19) | — | `RetrievalDoc` |

### `rag.retrieval.rewrite.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RewriteDtos](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/rewrite/dto/RewriteDtos.java#L7) | `RewriteCandidateDto`, `CandidateTemplate` | — |

### `rag.retrieval.rewrite.preview.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RewritePreviewController](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/rewrite/preview/controller/RewritePreviewController.java#L11) | — | `RewritePreviewDtos`, `RewritePreviewService` |

### `rag.retrieval.rewrite.preview.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RewritePreviewDtos](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/rewrite/preview/dto/RewritePreviewDtos.java#L8) | `RewritePreviewRequest`, `RewritePreviewResponse` | `RewriteDtos` |

### `rag.retrieval.rewrite.preview.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RewritePreviewService](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/rewrite/preview/service/RewritePreviewService.java#L24) | — | `ChunkRetrievalRepository`, `CohereRerankService`, `HashEmbeddingService`, `MemoryCandidate`, `RagMemoryRepository`, `RetrievalDoc`, `RewriteCandidateService`, `RewriteDtos`, `RewritePreviewDtos`, `RewriteScoring` |

### `rag.retrieval.rewrite.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RewriteCandidateService](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/rewrite/service/RewriteCandidateService.java#L29) | `PromptAsset` | `MemoryCandidate`, `RewriteDtos`, `RuntimeEnvService`, `SpringAiChatGateway` |
| [RewriteScoring](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/rewrite/service/RewriteScoring.java#L11) | — | `MemoryCandidate`, `RetrievalDoc` |

### `rag.retrieval.routing.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [QueryRouteContext](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/routing/dto/QueryRouteContext.java#L8) | — | `ChatConfigDtos`, `ChatReadinessDtos` |
| [QueryRouteDecision](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/routing/dto/QueryRouteDecision.java#L8) | — | `QueryStrategy` |

### `rag.retrieval.routing.model`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [QueryStrategy](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/routing/model/QueryStrategy.java#L3) | — | — |

### `rag.retrieval.routing.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [QueryStrategyRouter](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/routing/service/QueryStrategyRouter.java#L17) | — | `ChatConfigDtos`, `ChatReadinessDtos`, `QueryRouteContext`, `QueryRouteDecision`, `QueryStrategy` |

### `rag.runtime.configuration.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChatRuntimeConfigController](../../backend/src/main/java/io/queryforge/backend/rag/runtime/configuration/controller/ChatRuntimeConfigController.java#L15) | — | `ChatConfigDtos`, `ChatRuntimeConfigService` |

### `rag.runtime.configuration.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChatConfigDtos](../../backend/src/main/java/io/queryforge/backend/rag/runtime/configuration/dto/ChatConfigDtos.java#L9) | `ChatDomainOption`, `ChatRuntimeConfigResponse`, `ChatRuntimeConfigRequest`, `ChatRuntimeConfigProvenanceRow` | — |

### `rag.runtime.configuration.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChatRuntimeConfigRepository](../../backend/src/main/java/io/queryforge/backend/rag/runtime/configuration/repository/ChatRuntimeConfigRepository.java#L22) | — | `ChatConfigDtos` |

### `rag.runtime.configuration.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChatRuntimeConfigService](../../backend/src/main/java/io/queryforge/backend/rag/runtime/configuration/service/ChatRuntimeConfigService.java#L26) | — | `AdminRuntimeOptionsDtos`, `AdminRuntimeOptionsService`, `ChatConfigDtos`, `ChatRuntimeConfigRepository`, `ChunkEmbeddingAdminDtos`, `ChunkEmbeddingAdminService`, `GatingSnapshotDtos`, `GatingSnapshotRepository` |

### `rag.runtime.promotion.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChatConfigPromotionController](../../backend/src/main/java/io/queryforge/backend/rag/runtime/promotion/controller/ChatConfigPromotionController.java#L11) | — | `ChatConfigDtos`, `ChatConfigPromotionDtos`, `ChatConfigPromotionService` |

### `rag.runtime.promotion.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChatConfigPromotionDtos](../../backend/src/main/java/io/queryforge/backend/rag/runtime/promotion/dto/ChatConfigPromotionDtos.java#L7) | `ApplyChatConfigFromRagRunRequest`, `RagRunApplySnapshot` | — |

### `rag.runtime.promotion.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChatConfigPromotionRepository](../../backend/src/main/java/io/queryforge/backend/rag/runtime/promotion/repository/ChatConfigPromotionRepository.java#L17) | — | `ChatConfigPromotionDtos` |

### `rag.runtime.promotion.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChatConfigPromotionService](../../backend/src/main/java/io/queryforge/backend/rag/runtime/promotion/service/ChatConfigPromotionService.java#L18) | — | `ChatConfigDtos`, `ChatConfigPromotionDtos`, `ChatConfigPromotionRepository`, `ChatRuntimeConfigService` |

### `rag.runtime.readiness.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChatReadinessController](../../backend/src/main/java/io/queryforge/backend/rag/runtime/readiness/controller/ChatReadinessController.java#L12) | — | `ChatReadinessDtos`, `ChatReadinessService` |

### `rag.runtime.readiness.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChatReadinessDtos](../../backend/src/main/java/io/queryforge/backend/rag/runtime/readiness/dto/ChatReadinessDtos.java#L7) | `ChatDomainReadinessResponse`, `SnapshotReadiness`, `ChunkEmbeddingReadiness`, `PromptBindingReadiness`, `RetrievalReadiness`, `DomainChunkEmbeddingStatus`, `PromptBindingSummary` | — |

### `rag.runtime.readiness.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChatReadinessRepository](../../backend/src/main/java/io/queryforge/backend/rag/runtime/readiness/repository/ChatReadinessRepository.java#L20) | — | `ChatReadinessDtos` |

### `rag.runtime.readiness.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChatReadinessService](../../backend/src/main/java/io/queryforge/backend/rag/runtime/readiness/service/ChatReadinessService.java#L19) | — | `ChatConfigDtos`, `ChatReadinessDtos`, `ChatReadinessRepository`, `ChatRuntimeConfigService`, `GatingSnapshotDtos`, `GatingSnapshotRepository` |

### `rag.runtime.snapshot.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [GatingSnapshotDtos](../../backend/src/main/java/io/queryforge/backend/rag/runtime/snapshot/dto/GatingSnapshotDtos.java#L5) | `GatingSnapshot` | — |

### `rag.runtime.snapshot.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [GatingSnapshotRepository](../../backend/src/main/java/io/queryforge/backend/rag/runtime/snapshot/repository/GatingSnapshotRepository.java#L15) | — | `GatingSnapshotDtos` |

### `rag.trace.query.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagTraceQueryController](../../backend/src/main/java/io/queryforge/backend/rag/trace/query/controller/RagTraceQueryController.java#L13) | — | `RagTraceDtos`, `RagTraceQueryService` |

### `rag.trace.query.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagTraceDtos](../../backend/src/main/java/io/queryforge/backend/rag/trace/query/dto/RagTraceDtos.java#L9) | `QueryTraceResponse`, `RewriteCandidateRow`, `OnlineQueryRow` | `RewriteDtos` |

### `rag.trace.query.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagTraceReadRepository](../../backend/src/main/java/io/queryforge/backend/rag/trace/query/repository/RagTraceReadRepository.java#L20) | — | `RagJdbc`, `RagTraceDtos` |

### `rag.trace.query.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagTraceQueryService](../../backend/src/main/java/io/queryforge/backend/rag/trace/query/service/RagTraceQueryService.java#L12) | — | `RagTraceDtos`, `RagTraceReadRepository`, `RewriteDtos` |

### `rag.trace.write.agentic.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AgenticTraceWriteDtos](../../backend/src/main/java/io/queryforge/backend/rag/trace/write/agentic/dto/AgenticTraceWriteDtos.java#L12) | `AgenticSubqueryRetrievalTracePersistenceRequest`, `AgenticRewriteCandidateTracePersistenceRequest`, `AgenticRewriteCandidateAdoptionPersistenceRequest`, `AgenticFinalRerankTracePersistenceRequest`, `AgenticOnlineRewriteLogPersistenceRequest`, `AgenticMemoryRetrievalLogPersistenceRequest`, `AgenticRewriteCandidateLogPersistenceRequest`, `AgenticOnlineQueryDecisionPersistenceRequest`, `AgenticOnlineQueryMetadataMergePersistenceRequest`, `AgenticRetrievalExecutionKind`, `AgenticSubqueryRetrievalTraceWriteScope` | `MemoryCandidate`, `RagPersistPolicy`, `RetrievalDoc` |

### `rag.trace.write.agentic.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AgenticTracePersistenceService](../../backend/src/main/java/io/queryforge/backend/rag/trace/write/agentic/service/AgenticTracePersistenceService.java#L24) | — | `AgenticTraceWriteDtos`, `RagPersistPolicy`, `RagTraceRepository`, `TraceWriteResults` |

### `rag.trace.write.dto`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagTraceWriteDtos](../../backend/src/main/java/io/queryforge/backend/rag/trace/write/dto/RagTraceWriteDtos.java#L15) | `RagTracePersistenceRequest`, `OnlineQueryDecisionPersistenceRequest`, `OnlineQueryMetadataMergePersistenceRequest`, `RawOnlyTracePersistenceRequest`, `RewriteCandidateTracePersistenceRequest`, `CreateRewriteCandidateTracePersistenceRequest`, `RewriteCandidateAdoptionPersistenceRequest`, `OnlineRewriteLogPersistenceRequest`, `MemoryRetrievalLogPersistenceRequest`, `RewriteCandidateLogPersistenceRequest`, `RawOnlyTraceWriteScope`, `RewriteCandidateTraceWriteScope` | `ForcedRetrievalMode`, `MemoryCandidate`, `RagPersistPolicy`, `RagRetrievalExecutionResult`, `RetrievalDoc`, `RetrievalExecutionDtos` |
| [TraceWriteResults](../../backend/src/main/java/io/queryforge/backend/rag/trace/write/dto/TraceWriteResults.java#L7) | `RagTracePersistenceResult`, `RewriteCandidatePersistenceResult`, `OnlineRewriteLogPersistenceResult` | `RagPersistPolicy` |

### `rag.trace.write.repository`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagTraceRepository](../../backend/src/main/java/io/queryforge/backend/rag/trace/write/repository/RagTraceRepository.java#L18) | — | `MemoryCandidate`, `RetrievalDoc` |

### `rag.trace.write.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagTracePersistenceService](../../backend/src/main/java/io/queryforge/backend/rag/trace/write/service/RagTracePersistenceService.java#L25) | — | `RagPersistPolicy`, `RagTraceRepository`, `RagTraceWriteDtos`, `RetrievalExecutionDtos`, `TraceWriteResults` |

### `rag.web.advice`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagApiExceptionHandler](../../backend/src/main/java/io/queryforge/backend/rag/web/advice/RagApiExceptionHandler.java#L10) | — | `GeminiServiceUnavailableException`, `RagRetrievalEvalException` |

### `ui.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ReactUiController](../../backend/src/main/java/io/queryforge/backend/ui/controller/ReactUiController.java#L6) | — | — |

## Test source

### `admin.console.evaluation.integration`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AdminConsoleRagIntegrationTest](../../backend/src/test/java/io/queryforge/backend/admin/console/evaluation/integration/AdminConsoleRagIntegrationTest.java#L28) | — | — |

### `admin.console.integration`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AdminConsoleGatingIntegrationTest](../../backend/src/test/java/io/queryforge/backend/admin/console/integration/AdminConsoleGatingIntegrationTest.java#L43) | — | `AdminSyntheticMethodRepository`, `GatingRepository`, `LlmJobDtos`, `LlmJobRepository`, `SyntheticGenerationDtos` |

### `admin.console.jobs.model`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [LlmJobTypeConstraintSyncTest](../../backend/src/test/java/io/queryforge/backend/admin/console/jobs/model/LlmJobTypeConstraintSyncTest.java#L16) | — | `LlmJobType` |

### `admin.corpus.integration`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusAdminApiIntegrationTest](../../backend/src/test/java/io/queryforge/backend/admin/corpus/integration/CorpusAdminApiIntegrationTest.java#L20) | — | — |
| [CorpusAdminMutationIntegrationTest](../../backend/src/test/java/io/queryforge/backend/admin/corpus/integration/CorpusAdminMutationIntegrationTest.java#L29) | — | — |

### `admin.domain.workspace.integration`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DomainWorkspaceIntegrationTest](../../backend/src/test/java/io/queryforge/backend/admin/domain/workspace/integration/DomainWorkspaceIntegrationTest.java#L30) | — | — |

### `admin.pipeline.artifact.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DocumentArtifactStoreServiceTest](../../backend/src/test/java/io/queryforge/backend/admin/pipeline/artifact/service/DocumentArtifactStoreServiceTest.java#L22) | — | `AdminPipelineProperties`, `DocumentArtifactDtos`, `DocumentArtifactStoreService`, `PipelineSourceRepository`, `SourceCatalogService` |

### `admin.pipeline.integration`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [PipelineAdminIntegrationTest](../../backend/src/test/java/io/queryforge/backend/admin/pipeline/integration/PipelineAdminIntegrationTest.java#L39) | `StubPipelineRunnerConfiguration` | `PipelineCommandRunner`, `PipelineExecutionDtos` |

### `admin.pipeline.source.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ProjectPathResolverTest](../../backend/src/test/java/io/queryforge/backend/admin/pipeline/source/service/ProjectPathResolverTest.java#L13) | — | `ProjectPathResolver` |

### `io.queryforge.backend`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [QueryForgeApplicationTests](../../backend/src/test/java/io/queryforge/backend/QueryForgeApplicationTests.java#L12) | — | — |

### `rag.chat.answer.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChatAnswerServiceTest](../../backend/src/test/java/io/queryforge/backend/rag/chat/answer/service/ChatAnswerServiceTest.java#L29) | `ExchangeHandler` | `ChatAnswerDtos`, `ChatAnswerService`, `GeminiServiceUnavailableException`, `RetrievalDoc`, `RuntimeEnvService`, `SpringAiChatGateway` |

### `rag.chat.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagControllerWebTest](../../backend/src/test/java/io/queryforge/backend/rag/chat/controller/RagControllerWebTest.java#L28) | — | `ChatDtos`, `EvalReportController`, `EvalReportDtos`, `EvalReportService`, `GeminiServiceUnavailableException`, `RagController`, `RagService` |

### `rag.chat.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagServiceTest](../../backend/src/test/java/io/queryforge/backend/rag/chat/service/RagServiceTest.java#L65) | — | `AgenticChatService`, `AgenticExecutionDtos`, `AgenticPlanningDtos`, `AgenticRetrievalDtos`, `AgenticRetrievalService`, `AgenticTracePersistenceService`, `AgenticTraceWriteDtos`, `ChatAnswerDtos`, `ChatAnswerService`, `ChatConfigDtos`, `ChatDtos`, `ChatReadinessDtos`, `ChatReadinessService`, `ChatResponseAssembler`, `ChatRuntimeConfigService`, `ChunkRetrievalRepository`, `CohereRerankService`, `DenseEmbeddingService`, `DomainScopedRetrievalService`, `HashEmbeddingService`, `MemoryCandidate`, `QueryStrategyRouter`, `RagMemoryRepository`, `RagPersistPolicy`, `RagRetrievalExecutionService`, `RagService`, `RagTracePersistenceService`, `RagTraceRepository`, `RagTraceWriteDtos`, `RetrievalDoc`, `RetrievalDtos`, `RetrievalExecutionDtos`, `RewriteCandidateService`, `RewriteDtos` |

### `rag.embedding.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DenseEmbeddingServiceTest](../../backend/src/test/java/io/queryforge/backend/rag/embedding/service/DenseEmbeddingServiceTest.java#L16) | — | `DenseEmbeddingService`, `SpringAiEmbeddingGateway` |
| [HashEmbeddingServiceTest](../../backend/src/test/java/io/queryforge/backend/rag/embedding/service/HashEmbeddingServiceTest.java#L9) | — | `HashEmbeddingService` |

### `rag.eval.retrieval.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagRetrievalEvalControllerTest](../../backend/src/test/java/io/queryforge/backend/rag/eval/retrieval/controller/RagRetrievalEvalControllerTest.java#L36) | — | `ChatAnswerService`, `ChunkRetrievalRepository`, `RagApiExceptionHandler`, `RagLlmCallCount`, `RagMemoryRepository`, `RagPersistPolicy`, `RagRetrievalEvalController`, `RagRetrievalEvalDtos`, `RagRetrievalEvalException`, `RagRetrievalEvalService`, `RagService`, `RagTraceRepository` |

### `rag.eval.retrieval.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagRetrievalEvalServiceTest](../../backend/src/test/java/io/queryforge/backend/rag/eval/retrieval/service/RagRetrievalEvalServiceTest.java#L53) | — | `AgenticExecutionDtos`, `AgenticPlanningDtos`, `AgenticRetrievalService`, `ChatAnswerService`, `ChatConfigDtos`, `ChatReadinessDtos`, `ChatReadinessService`, `ChatRuntimeConfigService`, `ChunkRetrievalRepository`, `DomainScopedRetrievalService`, `EvalRuntimeConfigService`, `MemoryCandidate`, `QueryRouteDecision`, `QueryStrategy`, `QueryStrategyRouter`, `RagMemoryRepository`, `RagPersistPolicy`, `RagRetrievalEvalDtos`, `RagRetrievalEvalException`, `RagRetrievalEvalService`, `RagRetrievalExecutionService`, `RagService`, `RagTraceRepository`, `RetrievalDoc`, `RetrievalDtos`, `RetrievalExecutionDtos` |

### `rag.retrieval.agentic.fusion.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [SearchResultMergerTest](../../backend/src/test/java/io/queryforge/backend/rag/retrieval/agentic/fusion/service/SearchResultMergerTest.java#L10) | — | `RetrievalDoc`, `SearchResultMerger` |

### `rag.retrieval.agentic.planning.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AgenticQueryPlannerServiceTest](../../backend/src/test/java/io/queryforge/backend/rag/retrieval/agentic/planning/service/AgenticQueryPlannerServiceTest.java#L25) | — | `AgenticPlanningDtos`, `AgenticQueryPlannerService`, `ChatConfigDtos`, `RuntimeEnvService`, `SpringAiChatGateway` |

### `rag.retrieval.agentic.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AgenticRetrievalServiceTest](../../backend/src/test/java/io/queryforge/backend/rag/retrieval/agentic/service/AgenticRetrievalServiceTest.java#L52) | — | `AgenticExecutionDtos`, `AgenticPlanningDtos`, `AgenticQueryPlannerService`, `AgenticRetrievalDtos`, `AgenticRetrievalService`, `AgenticTracePersistenceService`, `AgenticTraceWriteDtos`, `ChatConfigDtos`, `ChatReadinessDtos`, `ChunkRetrievalRepository`, `CohereRerankService`, `DenseEmbeddingService`, `DomainScopedRetrievalService`, `HashEmbeddingService`, `MemoryCandidate`, `QueryStrategyRouter`, `RagMemoryRepository`, `RagPersistPolicy`, `RagTraceRepository`, `RetrievalDoc`, `RewriteCandidateService`, `RewriteDtos`, `SearchResultMerger`, `TraceWriteResults` |

### `rag.retrieval.candidate.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DomainScopedRetrievalServiceTest](../../backend/src/test/java/io/queryforge/backend/rag/retrieval/candidate/service/DomainScopedRetrievalServiceTest.java#L30) | — | `ChunkRetrievalRepository`, `DenseEmbeddingService`, `DomainScopedRetrievalService`, `HashEmbeddingService`, `MemoryCandidate`, `RagMemoryRepository`, `RetrievalDoc`, `RetrievalDtos` |

### `rag.retrieval.execution.model`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagRetrievalExecutionModelTest](../../backend/src/test/java/io/queryforge/backend/rag/retrieval/execution/model/RagRetrievalExecutionModelTest.java#L18) | — | `ForcedRetrievalMode`, `QueryStrategy`, `RagExecutionTrace`, `RagLlmCallCount`, `RagPersistPolicy`, `RagRetrievalExecutionRequest`, `RagRetrievalExecutionResult`, `RagRetrievedDoc` |

### `rag.retrieval.execution.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagRetrievalExecutionServiceTest](../../backend/src/test/java/io/queryforge/backend/rag/retrieval/execution/service/RagRetrievalExecutionServiceTest.java#L38) | — | `ChunkRetrievalRepository`, `CohereRerankService`, `DenseEmbeddingService`, `DomainScopedRetrievalService`, `HashEmbeddingService`, `MemoryCandidate`, `RagMemoryRepository`, `RagRetrievalExecutionService`, `RagTracePersistenceService`, `RagTraceRepository`, `RetrievalDoc`, `RetrievalDtos`, `RetrievalExecutionDtos`, `RewriteCandidateService`, `RewriteDtos` |

### `rag.retrieval.routing.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [QueryStrategyRouterTest](../../backend/src/test/java/io/queryforge/backend/rag/retrieval/routing/service/QueryStrategyRouterTest.java#L18) | — | `ChatConfigDtos`, `ChatReadinessDtos`, `QueryRouteContext`, `QueryRouteDecision`, `QueryStrategy`, `QueryStrategyRouter` |

### `rag.trace.write.service`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagTracePersistenceServiceTest](../../backend/src/test/java/io/queryforge/backend/rag/trace/write/service/RagTracePersistenceServiceTest.java#L44) | — | `AgenticTracePersistenceService`, `AgenticTraceWriteDtos`, `ChatAnswerService`, `ForcedRetrievalMode`, `MemoryCandidate`, `RagPersistPolicy`, `RagRetrievalExecutionResult`, `RagTracePersistenceService`, `RagTraceRepository`, `RagTraceWriteDtos`, `RetrievalDoc`, `RetrievalExecutionDtos`, `TraceWriteResults` |

### `ui.controller`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AdminIngestWizardRenderTest](../../backend/src/test/java/io/queryforge/backend/ui/controller/AdminIngestWizardRenderTest.java#L12) | — | `ReactUiController` |
| [AdminUiSmokeTest](../../backend/src/test/java/io/queryforge/backend/ui/controller/AdminUiSmokeTest.java#L23) | — | — |
