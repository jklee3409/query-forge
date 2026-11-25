# 백엔드 소스 인벤토리

기준 커밋 `285e3b9`의 Java 소스를 분석한 뒤 옮길 책임과 기존 기능에 속하는 책임을 구분했다. 리팩토링 후 `backend`의 운영·테스트 소스 트리를 다시 파싱해 모든 선언의 패키지와 실제 경로를 대조했다.

| 범위 | 최상위 타입 | 명명된 중첩 타입 | 타입이 선언된 패키지 |
| --- | ---: | ---: | ---: |
| `backend/src/main/java` | 217 | 227 | 71 |
| `backend/src/test/java` | 27 | 2 | 21 |
| 운영 소스 중 `rag` | 85 | — | 30 |
| 테스트 소스 중 `rag` | 15 | — | 11 |

타입 없이 하위 패키지를 묶는 디렉토리까지 포함하면 최종 `rag` 하위에 운영 디렉토리 36개와 테스트 디렉토리 14개가 있다. 운영·테스트 소스 트리 전체에서 기존 빈 레이어 폴더 32개를 정리했다.

`backend` 최상위에는 Gradle 빌드 정의, 래퍼, Dockerfile과 디렉토리 문서가 있다. `src/main/resources`에는 애플리케이션 설정, Flyway 마이그레이션, React 번들이 있고 `src/test/resources`에는 테스트 설정과 SQL 픽스처가 있다. `build`, `.gradle`, `bin`은 로컬 빌드·IDE 산출물로 확인했다. Gradle이 사용하는 Java 소스에는 패키지 경로 불일치나 빈 디렉토리가 없다.

기준 커밋과 비교해 Spring HTTP 매핑 133개, 기존 레코드 선언 226개, 기존 테스트 메서드 181개가 모두 유지됐다. 레코드 1개가 추가됐으며 관리자 앵커 평가가 받는 내부 입력이다. Docker PostgreSQL을 포함한 Gradle 클린 테스트 181개와 Python의 Java 검색 연동 테스트 36개가 모두 통과했다.

## 기능 경계를 결정한 근거

| 영역 | 확인한 관계 | 적용한 분류 |
| --- | --- | --- |
| RAG 채팅 | 채팅 조정 서비스는 도메인 설정과 라우팅을 거쳐 일반·Agentic 검색에 위임한다. 답변 구성은 Spring AI 또는 기존 공급자 호출을 사용한다. | 채팅 조정과 응답 조립은 `chat`에 두고 답변 생성과 라이브 라우팅 입력 조합을 `answer`, `routing`에 배치했다. |
| RAG 검색 | 후보 수집과 랭킹은 일반 검색과 Agentic 검색이 공유한다. Agentic 경로의 계획 및 RRF는 각각 입력과 출력이 독립적이다. | `candidate`, `execution`, `memory`, `rewrite`, `rerank`, `routing`를 구분했다. Agentic 계획과 병합은 각 하위 패키지로 옮겼다. |
| RAG 평가와 실험 | 검색 평가 경로는 요청별 설정을 조합한 뒤 답변 생성 없이 실행한다. 보고서 파일 조회와 Python 실험 명령 실행은 별도의 입출력 경계를 가진다. | `eval.retrieval`, `eval.report`, `experiment.command`, `experiment.summary`로 나눴다. |
| RAG 런타임과 추적 | 설정 검증과 준비 상태가 게이팅 스냅샷 조회를 공유한다. 추적 조회 SQL과 온라인 기록 SQL은 서로 다른 진입점에서 사용된다. | `runtime.configuration`, `promotion`, `snapshot`, `readiness`로 나눴다. 추적은 `query`, `write`, `write.agentic`에 배치했다. |
| 관리자 평가 | 데이터셋 삭제는 실행 상태를 검증하고 연결된 이력을 같은 트랜잭션에서 정리한다. 앵커 평가 입력은 실행 응답 전체에 의존할 필요가 없다. | `dataset.lifecycle`에서 데이터셋과 실행의 삭제를 조정한다. `run`, `anchor`, `debug`에서 실행 이력과 각 진단을 소유한다. |
| 코퍼스와 파이프라인 | 앵커 조회, 증거 재추출, 정규화 승인, 관계 생성, 평가가 각각 다른 데이터 흐름을 가진다. 파이프라인 설정은 실행과 소스 카탈로그에서 공유된다. | 앵커 구현을 `query`, `extraction`, `normalization`, `relation`, `evaluation`에 배치했다. 파이프라인 설정과 저장소 공통 유틸리티는 기능 영역의 하위 패키지로 옮겼다. |
| 나머지 관리자 기능과 공통 코드 | 합성 질의, 게이팅, 도메인, 프롬프트, 소스·문서·용어는 이미 각 기능 패키지에 있다. 여러 기능을 묶는 통합 테스트는 공통 진입점을 검증한다. | 각 기능의 기존 소유 관계를 유지했다. 관리자와 RAG 오류 응답은 각 영역의 `web`에 두고 애플리케이션 부트스트랩은 루트에 유지했다. |

아래 표는 패키지별 전체 선언 목록이다. 직접 참조 열은 Java 타입 이름과 명시적 import의 정적 참조를 기준으로 하며, 실행 중 Spring 빈 연결은 통합 테스트에서 확인했다. 명명된 중첩 타입과 레코드는 바깥 타입의 행에 포함했다.

## Production source

### `admin.console.configuration`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ConsoleExperimentConfig](../../backend/src/main/java/io/queryforge/backend/admin/console/configuration/ConsoleExperimentConfig.java#L20) | — | `AdminRuntimeOptionsService`, `ConsoleEnvironment`, `ConsoleParameters` |
| [ConsoleParameters](../../backend/src/main/java/io/queryforge/backend/admin/console/configuration/ConsoleParameters.java#L6) | — | — |

### `admin.console.dashboard`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ConsoleDashboardController](../../backend/src/main/java/io/queryforge/backend/admin/console/dashboard/ConsoleDashboardController.java#L8) | — | `ConsoleDashboardDtos`, `ConsoleDashboardRepository` |
| [ConsoleDashboardDtos](../../backend/src/main/java/io/queryforge/backend/admin/console/dashboard/ConsoleDashboardDtos.java#L3) | `AdminDashboardStats` | — |
| [ConsoleDashboardRepository](../../backend/src/main/java/io/queryforge/backend/admin/console/dashboard/ConsoleDashboardRepository.java#L9) | — | `ConsoleDashboardDtos` |

### `admin.console.embedding`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChunkEmbeddingAdminController](../../backend/src/main/java/io/queryforge/backend/admin/console/embedding/ChunkEmbeddingAdminController.java#L12) | — | `ChunkEmbeddingAdminDtos`, `ChunkEmbeddingAdminService`, `LlmJobDtos` |
| [ChunkEmbeddingAdminDtos](../../backend/src/main/java/io/queryforge/backend/admin/console/embedding/ChunkEmbeddingAdminDtos.java#L5) | `ChunkEmbeddingMaterializationRequest`, `ChunkEmbeddingMaterializationStatusResponse` | — |
| [ChunkEmbeddingAdminRepository](../../backend/src/main/java/io/queryforge/backend/admin/console/embedding/ChunkEmbeddingAdminRepository.java#L13) | — | — |
| [ChunkEmbeddingAdminService](../../backend/src/main/java/io/queryforge/backend/admin/console/embedding/ChunkEmbeddingAdminService.java#L22) | — | `AdminRetrieverConfiguration`, `AdminRuntimeOptionsDtos`, `AdminRuntimeOptionsService`, `ChunkEmbeddingAdminDtos`, `ChunkEmbeddingAdminRepository`, `ConsoleExperimentConfig`, `ConsoleParameters`, `LlmJobDtos`, `LlmJobService` |

### `admin.console.evaluation.anchor`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CanonicalAnchorVersionMetadata](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/anchor/CanonicalAnchorVersionMetadata.java#L8) | — | — |
| [RagRewriteAnchorController](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/anchor/RagRewriteAnchorController.java#L12) | — | `RagRewriteAnchorDtos`, `RagRewriteAnchorRepository` |
| [RagRewriteAnchorDtos](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/anchor/RagRewriteAnchorDtos.java#L8) | `RagRewriteAnchorEvalRow`, `AnchorEvaluationCase` | — |
| [RagRewriteAnchorEvalService](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/anchor/RagRewriteAnchorEvalService.java#L28) | `AnchorDraft` | `AdminPipelineProperties`, `RagRewriteAnchorDtos`, `RagRewriteAnchorRepository` |
| [RagRewriteAnchorRepository](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/anchor/RagRewriteAnchorRepository.java#L24) | `EvalDatasetAnchorMeta`, `MemoryAnchorSource` | `ConsoleJdbc`, `RagRewriteAnchorDtos` |

### `admin.console.evaluation.dataset`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AdminEvalDatasetRepository](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/dataset/AdminEvalDatasetRepository.java#L20) | `EvalSampleMeta` | `EvalDatasetDtos` |
| [EvalDatasetController](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/dataset/EvalDatasetController.java#L13) | — | `EvalDatasetDtos`, `EvalDatasetService` |
| [EvalDatasetDtos](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/dataset/EvalDatasetDtos.java#L8) | `EvalDatasetRow`, `EvalDatasetItemRow` | — |
| [EvalDatasetService](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/dataset/EvalDatasetService.java#L12) | — | `AdminEvalDatasetRepository`, `EvalDatasetDtos` |

### `admin.console.evaluation.dataset.lifecycle`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [EvalDatasetDeletionController](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/dataset/lifecycle/EvalDatasetDeletionController.java#L11) | — | `EvalDatasetDeletionService` |
| [EvalDatasetDeletionService](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/dataset/lifecycle/EvalDatasetDeletionService.java#L17) | — | `AdminEvalDatasetRepository`, `EvalDatasetService`, `RagTestDtos`, `RagTestHistoryService`, `RagTestRunRepository` |

### `admin.console.evaluation.debug`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RewriteDebugController](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/debug/RewriteDebugController.java#L13) | — | `RewriteDebugDtos`, `RewriteDebugService` |
| [RewriteDebugDtos](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/debug/RewriteDebugDtos.java#L8) | `RewriteDebugRow`, `RewriteDebugDetail` | — |
| [RewriteDebugRepository](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/debug/RewriteDebugRepository.java#L21) | — | `ConsoleJdbc`, `RewriteDebugDtos` |
| [RewriteDebugService](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/debug/RewriteDebugService.java#L10) | — | `RewriteDebugDtos`, `RewriteDebugRepository` |

### `admin.console.evaluation.run`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagTestController](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/run/RagTestController.java#L16) | — | `RagTestDtos`, `RagTestHistoryService`, `RagTestService` |
| [RagTestDtos](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/run/RagTestDtos.java#L12) | `RagTestRunRequest`, `RagTestRunRow`, `RagTestRunDetail`, `RagTestResultDetailRow`, `RagCompareResponse` | `AdminRuntimeOptionsDtos`, `RagRewriteAnchorDtos` |
| [RagTestHistoryService](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/run/RagTestHistoryService.java#L15) | — | `RagRewriteAnchorRepository`, `RagTestDtos`, `RagTestRunRepository` |
| [RagTestRunRepository](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/run/RagTestRunRepository.java#L30) | — | `ConsoleJdbc`, `RagRewriteAnchorDtos`, `RagRewriteAnchorRepository`, `RagTestDtos` |
| [RagTestService](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/run/RagTestService.java#L40) | — | `AdminEvalDatasetRepository`, `AdminRetrieverConfiguration`, `AdminRuntimeOptionsService`, `CanonicalAnchorVersionMetadata`, `ChunkEmbeddingAdminService`, `ConsoleExperimentConfig`, `ConsoleParameters`, `ConsoleScopeService`, `EvalDatasetService`, `GatingDtos`, `GatingRepository`, `LlmJobService`, `RagTestDtos`, `RagTestRunRepository`, `RewriteLlmPreflight` |
| [RewriteLlmPreflight](../../backend/src/main/java/io/queryforge/backend/admin/console/evaluation/run/RewriteLlmPreflight.java#L13) | — | `ConsoleEnvironment`, `ConsoleParameters` |

### `admin.console.gating`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [GatingController](../../backend/src/main/java/io/queryforge/backend/admin/console/gating/GatingController.java#L15) | — | `GatingDtos`, `GatingService` |
| [GatingDtos](../../backend/src/main/java/io/queryforge/backend/admin/console/gating/GatingDtos.java#L10) | `GatingBatchRunRequest`, `GatingRunConfig`, `GatingStageFlags`, `GatingRuleConfig`, `GatingWeightsConfig`, `GatingUtilityScoreConfig`, `GatingThresholdConfig`, `GatingBatchRow`, `GatingFunnelResponse`, `GatingResultRow` | `AdminRuntimeOptionsDtos` |
| [GatingRepository](../../backend/src/main/java/io/queryforge/backend/admin/console/gating/GatingRepository.java#L24) | — | `ConsoleJdbc`, `GatingDtos` |
| [GatingService](../../backend/src/main/java/io/queryforge/backend/admin/console/gating/GatingService.java#L33) | — | `AdminRetrieverConfiguration`, `AdminRuntimeOptionsService`, `AdminSyntheticMethodRepository`, `ConsoleExperimentConfig`, `ConsoleParameters`, `ConsoleScopeService`, `GatingDtos`, `GatingRepository`, `LlmJobService`, `SyntheticGenerationDtos`, `SyntheticGenerationRepository` |

### `admin.console.jobs`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [LlmJobController](../../backend/src/main/java/io/queryforge/backend/admin/console/jobs/LlmJobController.java#L14) | — | `LlmJobDtos`, `LlmJobService` |
| [LlmJobDtos](../../backend/src/main/java/io/queryforge/backend/admin/console/jobs/LlmJobDtos.java#L8) | `LlmJobRow`, `LlmJobItemRow` | — |
| [LlmJobRepository](../../backend/src/main/java/io/queryforge/backend/admin/console/jobs/LlmJobRepository.java#L19) | — | `LlmJobDtos` |
| [LlmJobService](../../backend/src/main/java/io/queryforge/backend/admin/console/jobs/LlmJobService.java#L39) | — | `ExperimentCommandDtos`, `ExperimentPipelineService`, `GatingRepository`, `LlmJobDtos`, `LlmJobRepository`, `LlmJobResultService`, `LlmJobType`, `RagTestRunRepository`, `SyntheticGenerationRepository` |
| [LlmJobType](../../backend/src/main/java/io/queryforge/backend/admin/console/jobs/LlmJobType.java#L5) | — | — |

### `admin.console.jobs.result`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [LlmJobResultService](../../backend/src/main/java/io/queryforge/backend/admin/console/jobs/result/LlmJobResultService.java#L32) | — | `AdminEvalDatasetRepository`, `AdminPipelineProperties`, `CanonicalAnchorVersionMetadata`, `GatingRepository`, `LlmJobDtos`, `LlmJobType`, `RagRewriteAnchorDtos`, `RagRewriteAnchorEvalService`, `RagRewriteAnchorRepository`, `RagTestDtos`, `RagTestRunRepository`, `SyntheticGenerationRepository` |

### `admin.console.persistence`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ConsoleJdbc](../../backend/src/main/java/io/queryforge/backend/admin/console/persistence/ConsoleJdbc.java#L13) | — | — |

### `admin.console.runtime`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AdminRetrieverConfiguration](../../backend/src/main/java/io/queryforge/backend/admin/console/runtime/AdminRetrieverConfiguration.java#L17) | — | `AdminRuntimeOptionsDtos`, `AdminRuntimeOptionsService`, `ConsoleParameters` |
| [AdminRuntimeOptionsController](../../backend/src/main/java/io/queryforge/backend/admin/console/runtime/AdminRuntimeOptionsController.java#L8) | — | `AdminRuntimeOptionsDtos`, `AdminRuntimeOptionsService` |
| [AdminRuntimeOptionsDtos](../../backend/src/main/java/io/queryforge/backend/admin/console/runtime/AdminRuntimeOptionsDtos.java#L8) | `RetrieverConfigRequest`, `RuntimeOptionsResponse`, `RuntimeOption`, `RuntimeParameterRange` | — |
| [AdminRuntimeOptionsService](../../backend/src/main/java/io/queryforge/backend/admin/console/runtime/AdminRuntimeOptionsService.java#L24) | `RuntimeCatalog`, `RuntimeOptionMetadata` | `AdminRuntimeOptionsDtos`, `ConsoleEnvironment`, `ConsoleParameters` |
| [ConsoleEnvironment](../../backend/src/main/java/io/queryforge/backend/admin/console/runtime/ConsoleEnvironment.java#L12) | — | `AdminPipelineProperties` |

### `admin.console.scope`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AdminConsoleDomainScopeRepository](../../backend/src/main/java/io/queryforge/backend/admin/console/scope/AdminConsoleDomainScopeRepository.java#L15) | `SourceStrategyContext`, `DatasetStrategyContext`, `DomainLanguageContext` | — |
| [ConsoleScopeService](../../backend/src/main/java/io/queryforge/backend/admin/console/scope/ConsoleScopeService.java#L19) | `StrategyScope`, `SourceResolution`, `DatasetResolution` | `AdminConsoleDomainScopeRepository`, `AdminSyntheticMethodRepository`, `ConsoleParameters` |

### `admin.console.synthetic`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AdminConsoleStrategyTables](../../backend/src/main/java/io/queryforge/backend/admin/console/synthetic/AdminConsoleStrategyTables.java#L5) | — | — |
| [AdminSyntheticMethodRepository](../../backend/src/main/java/io/queryforge/backend/admin/console/synthetic/AdminSyntheticMethodRepository.java#L17) | — | `SyntheticGenerationDtos` |
| [SyntheticGenerationController](../../backend/src/main/java/io/queryforge/backend/admin/console/synthetic/SyntheticGenerationController.java#L16) | — | `SyntheticGenerationDtos`, `SyntheticGenerationService` |
| [SyntheticGenerationDtos](../../backend/src/main/java/io/queryforge/backend/admin/console/synthetic/SyntheticGenerationDtos.java#L8) | `SyntheticGenerationMethod`, `SyntheticBatchRunRequest`, `SyntheticGenerationBatchRow`, `SyntheticQueryRow`, `SyntheticQueryDetailResponse`, `SyntheticStatsResponse` | — |
| [SyntheticGenerationRepository](../../backend/src/main/java/io/queryforge/backend/admin/console/synthetic/SyntheticGenerationRepository.java#L22) | — | `AdminConsoleStrategyTables`, `ConsoleJdbc`, `SyntheticGenerationDtos` |
| [SyntheticGenerationService](../../backend/src/main/java/io/queryforge/backend/admin/console/synthetic/SyntheticGenerationService.java#L25) | — | `AdminRuntimeOptionsService`, `AdminSyntheticMethodRepository`, `ConsoleExperimentConfig`, `ConsoleParameters`, `ConsoleScopeService`, `LlmJobService`, `SyntheticGenerationDtos`, `SyntheticGenerationRepository` |

### `admin.corpus.anchor.evaluation`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AnchorEvalController](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/evaluation/AnchorEvalController.java#L15) | — | `AnchorEvalDtos`, `AnchorEvalService` |
| [AnchorEvalDtos](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/evaluation/AnchorEvalDtos.java#L10) | `AnchorEvalRunCreateRequest`, `AnchorEvalRunSummary`, `AnchorEvalCandidateDto`, `AnchorEvalSampleDto`, `AnchorEvalRunDetail`, `AnchorEvalLabelRequest` | — |
| [AnchorEvalRepository](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/evaluation/AnchorEvalRepository.java#L25) | — | `AnchorEvalDtos`, `CorpusDocumentDtos`, `CorpusJdbc` |
| [AnchorEvalService](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/evaluation/AnchorEvalService.java#L12) | — | `AnchorEvalDtos`, `AnchorEvalRepository`, `CorpusDocumentDtos` |

### `admin.corpus.anchor.extraction`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AnchorExtractionController](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/extraction/AnchorExtractionController.java#L9) | — | `AnchorExtractionDtos`, `AnchorExtractionService` |
| [AnchorExtractionDtos](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/extraction/AnchorExtractionDtos.java#L7) | `AnchorExtractRequest`, `AnchorExtractResponse` | — |
| [AnchorExtractionService](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/extraction/AnchorExtractionService.java#L28) | `AnchorChunkRow`, `AnchorCandidate`, `TermStatRow`, `TermRefreshSummary`, `AnchorRemapSummary` | `AdminPipelineProperties`, `AnchorExtractionDtos`, `SourceCatalogService` |

### `admin.corpus.anchor.normalization`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AnchorNormalizationController](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/normalization/AnchorNormalizationController.java#L16) | — | `AnchorNormalizationDtos`, `AnchorNormalizationService` |
| [AnchorNormalizationDtos](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/normalization/AnchorNormalizationDtos.java#L10) | `AnchorNormalizationRunCreateRequest`, `AnchorNormalizationReviewRequest`, `AnchorNormalizationCandidateReviewRequest`, `AnchorNormalizationCandidateDecision`, `AnchorNormalizationCandidateReviewBatchRequest`, `AnchorNormalizationRunSummary`, `AnchorNormalizationCandidateDto`, `AnchorNormalizationRunDetail` | — |
| [AnchorNormalizationService](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/normalization/AnchorNormalizationService.java#L25) | `TargetAnchor`, `CandidateDraft` | `AnchorNormalizationDtos` |

### `admin.corpus.anchor.query`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusAnchorController](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/query/CorpusAnchorController.java#L11) | — | `CorpusAnchorDtos`, `CorpusAnchorService` |
| [CorpusAnchorDtos](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/query/CorpusAnchorDtos.java#L6) | `AnchorSummary` | — |
| [CorpusAnchorRepository](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/query/CorpusAnchorRepository.java#L18) | — | `CorpusAnchorDtos`, `CorpusJdbc` |
| [CorpusAnchorService](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/query/CorpusAnchorService.java#L9) | — | `CorpusAnchorDtos`, `CorpusAnchorRepository` |

### `admin.corpus.anchor.relation`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [MultiSourceAnchorController](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/relation/MultiSourceAnchorController.java#L15) | — | `MultiSourceAnchorDtos`, `MultiSourceAnchorService` |
| [MultiSourceAnchorDtos](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/relation/MultiSourceAnchorDtos.java#L10) | `MultiSourceAnchorBuildRequest`, `MultiSourceAnchorBuildRunSummary` | — |
| [MultiSourceAnchorService](../../backend/src/main/java/io/queryforge/backend/admin/corpus/anchor/relation/MultiSourceAnchorService.java#L24) | — | `MultiSourceAnchorDtos` |

### `admin.corpus.document`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusDocumentController](../../backend/src/main/java/io/queryforge/backend/admin/corpus/document/CorpusDocumentController.java#L13) | — | `CorpusDocumentDtos`, `CorpusDocumentService` |
| [CorpusDocumentDtos](../../backend/src/main/java/io/queryforge/backend/admin/corpus/document/CorpusDocumentDtos.java#L9) | `DocumentSummary`, `DocumentDetail`, `SectionDto`, `ChunkSummary`, `ChunkDetail`, `ChunkNeighborDto`, `RawVsCleanedPreview`, `ChunkBoundaryDto`, `ChunkBoundaryPreview` | — |
| [CorpusDocumentRepository](../../backend/src/main/java/io/queryforge/backend/admin/corpus/document/CorpusDocumentRepository.java#L22) | — | `CorpusDocumentDtos`, `CorpusJdbc` |
| [CorpusDocumentService](../../backend/src/main/java/io/queryforge/backend/admin/corpus/document/CorpusDocumentService.java#L12) | — | `CorpusDocumentDtos`, `CorpusDocumentRepository` |

### `admin.corpus.glossary`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusGlossaryController](../../backend/src/main/java/io/queryforge/backend/admin/corpus/glossary/CorpusGlossaryController.java#L17) | — | `CorpusGlossaryDtos`, `CorpusGlossaryService` |
| [CorpusGlossaryDtos](../../backend/src/main/java/io/queryforge/backend/admin/corpus/glossary/CorpusGlossaryDtos.java#L10) | `GlossaryAliasDto`, `GlossaryTermSummary`, `GlossaryTermDetail`, `GlossaryEvidenceDto`, `TopTermPreview`, `GlossaryTermPatchRequest`, `GlossaryAliasCreateRequest` | — |
| [CorpusGlossaryRepository](../../backend/src/main/java/io/queryforge/backend/admin/corpus/glossary/CorpusGlossaryRepository.java#L22) | — | `CorpusGlossaryDtos`, `CorpusJdbc` |
| [CorpusGlossaryService](../../backend/src/main/java/io/queryforge/backend/admin/corpus/glossary/CorpusGlossaryService.java#L10) | — | `CorpusGlossaryDtos`, `CorpusGlossaryRepository` |

### `admin.corpus.persistence`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusJdbc](../../backend/src/main/java/io/queryforge/backend/admin/corpus/persistence/CorpusJdbc.java#L12) | — | — |

### `admin.corpus.run`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusRunController](../../backend/src/main/java/io/queryforge/backend/admin/corpus/run/CorpusRunController.java#L13) | — | `CorpusRunDtos`, `CorpusRunService` |
| [CorpusRunDtos](../../backend/src/main/java/io/queryforge/backend/admin/corpus/run/CorpusRunDtos.java#L9) | `RunSummary`, `RunStep`, `RunDetail` | — |
| [CorpusRunRepository](../../backend/src/main/java/io/queryforge/backend/admin/corpus/run/CorpusRunRepository.java#L21) | — | `CorpusJdbc`, `CorpusRunDtos` |
| [CorpusRunService](../../backend/src/main/java/io/queryforge/backend/admin/corpus/run/CorpusRunService.java#L10) | — | `CorpusRunDtos`, `CorpusRunRepository` |

### `admin.corpus.source`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusSourceController](../../backend/src/main/java/io/queryforge/backend/admin/corpus/source/CorpusSourceController.java#L16) | — | `CorpusSourceDtos`, `CorpusSourceService` |
| [CorpusSourceDtos](../../backend/src/main/java/io/queryforge/backend/admin/corpus/source/CorpusSourceDtos.java#L10) | `SourceSummary`, `SourceUpdateRequest`, `SourceUpsertRequest`, `SourceAutoRegisterRequest` | — |
| [CorpusSourceRepository](../../backend/src/main/java/io/queryforge/backend/admin/corpus/source/CorpusSourceRepository.java#L17) | — | `CorpusJdbc`, `CorpusSourceDtos` |
| [CorpusSourceService](../../backend/src/main/java/io/queryforge/backend/admin/corpus/source/CorpusSourceService.java#L17) | — | `CorpusSourceDtos`, `CorpusSourceRepository`, `DomainSourceDtos`, `DomainSourceService`, `SourceCatalogService` |

### `admin.domain.dashboard`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DomainDashboardController](../../backend/src/main/java/io/queryforge/backend/admin/domain/dashboard/DomainDashboardController.java#L9) | — | `DomainDashboardDtos`, `DomainDashboardService` |
| [DomainDashboardDtos](../../backend/src/main/java/io/queryforge/backend/admin/domain/dashboard/DomainDashboardDtos.java#L6) | `DomainDashboardSummary` | — |
| [DomainDashboardRepository](../../backend/src/main/java/io/queryforge/backend/admin/domain/dashboard/DomainDashboardRepository.java#L17) | — | `DomainDashboardDtos` |
| [DomainDashboardService](../../backend/src/main/java/io/queryforge/backend/admin/domain/dashboard/DomainDashboardService.java#L6) | — | `DomainDashboardDtos`, `DomainDashboardRepository` |

### `admin.domain.identity`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DomainIdentityDtos](../../backend/src/main/java/io/queryforge/backend/admin/domain/identity/DomainIdentityDtos.java#L8) | `DomainSummary`, `DomainCreateRequest`, `DomainUpdateRequest` | — |
| [DomainIdentityRepository](../../backend/src/main/java/io/queryforge/backend/admin/domain/identity/DomainIdentityRepository.java#L21) | — | `DomainIdentityDtos` |
| [DomainIdentityService](../../backend/src/main/java/io/queryforge/backend/admin/domain/identity/DomainIdentityService.java#L12) | — | `DomainIdentityDtos`, `DomainIdentityRepository` |

### `admin.domain.policy`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DomainMethodPolicy](../../backend/src/main/java/io/queryforge/backend/admin/domain/policy/DomainMethodPolicy.java#L7) | — | — |
| [DomainMethodPolicyRepository](../../backend/src/main/java/io/queryforge/backend/admin/domain/policy/DomainMethodPolicyRepository.java#L17) | — | `DomainMethodPolicy` |

### `admin.domain.source`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DomainSourceDtos](../../backend/src/main/java/io/queryforge/backend/admin/domain/source/DomainSourceDtos.java#L5) | `DomainSource`, `DomainSourceAttachRequest` | — |
| [DomainSourceRepository](../../backend/src/main/java/io/queryforge/backend/admin/domain/source/DomainSourceRepository.java#L17) | — | `DomainSourceDtos` |
| [DomainSourceService](../../backend/src/main/java/io/queryforge/backend/admin/domain/source/DomainSourceService.java#L11) | — | `DomainIdentityService`, `DomainSourceDtos`, `DomainSourceRepository` |

### `admin.domain.workspace`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DomainDetail](../../backend/src/main/java/io/queryforge/backend/admin/domain/workspace/DomainDetail.java#L9) | — | `DomainIdentityDtos`, `DomainMethodPolicy`, `DomainSourceDtos` |
| [DomainWorkspaceController](../../backend/src/main/java/io/queryforge/backend/admin/domain/workspace/DomainWorkspaceController.java#L17) | — | `DomainDetail`, `DomainIdentityDtos`, `DomainSourceDtos`, `DomainWorkspaceService` |
| [DomainWorkspaceService](../../backend/src/main/java/io/queryforge/backend/admin/domain/workspace/DomainWorkspaceService.java#L14) | — | `DomainDetail`, `DomainIdentityDtos`, `DomainIdentityService`, `DomainMethodPolicyRepository`, `DomainSourceDtos`, `DomainSourceService` |

### `admin.pipeline.artifact`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DocumentArtifactStoreService](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/artifact/DocumentArtifactStoreService.java#L31) | `PersistResult`, `Stage`, `StageMetadata`, `DocumentManifest`, `AggregatedGlossaryRow` | `AdminPipelineProperties`, `SourceCatalogService` |

### `admin.pipeline.configuration`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AdminPipelineConfiguration](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/configuration/AdminPipelineConfiguration.java#L12) | — | — |
| [AdminPipelineProperties](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/configuration/AdminPipelineProperties.java#L5) | — | — |

### `admin.pipeline.dashboard`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [PipelineDashboardController](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/dashboard/PipelineDashboardController.java#L11) | — | `PipelineDashboardDtos`, `PipelineDashboardService` |
| [PipelineDashboardDtos](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/dashboard/PipelineDashboardDtos.java#L7) | `DashboardStats`, `ProductDocumentStat`, `RecentRunStat`, `FailedStepStat` | — |
| [PipelineDashboardRepository](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/dashboard/PipelineDashboardRepository.java#L16) | — | `PipelineDashboardDtos` |
| [PipelineDashboardService](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/dashboard/PipelineDashboardService.java#L10) | — | `PipelineDashboardDtos`, `PipelineDashboardRepository`, `SourceCatalogService` |

### `admin.pipeline.execution`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [PipelineAdminController](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/execution/PipelineAdminController.java#L16) | — | `CorpusRunDtos`, `PipelineAdminService`, `PipelineExecutionDtos` |
| [PipelineAdminRepository](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/execution/PipelineAdminRepository.java#L19) | — | — |
| [PipelineAdminService](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/execution/PipelineAdminService.java#L36) | `StepWarningDecision`, `Scope`, `ArtifactContext`, `StepPlan`, `ManagedRunContext` | `AdminPipelineProperties`, `CorpusRunDtos`, `CorpusRunService`, `CorpusSourceDtos`, `CorpusSourceService`, `DocumentArtifactStoreService`, `PipelineAdminRepository`, `PipelineCommandRunner`, `PipelineExecutionDtos`, `PipelineStepPlanFactory`, `SourceCatalogService` |
| [PipelineCommandRunner](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/execution/PipelineCommandRunner.java#L8) | `CommandRequest`, `CommandResult`, `ProcessObserver` | — |
| [PipelineExecutionDtos](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/execution/PipelineExecutionDtos.java#L10) | `PipelineRunRequest`, `PipelineRunActionResponse`, `StepLogDto`, `PipelineRunLogsResponse` | — |
| [PipelineStepPlanFactory](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/execution/PipelineStepPlanFactory.java#L18) | `DatabaseTarget` | `AdminPipelineProperties`, `PipelineAdminService`, `SourceCatalogService` |
| [SubprocessPipelineCommandRunner](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/execution/SubprocessPipelineCommandRunner.java#L17) | — | `PipelineCommandRunner` |

### `admin.pipeline.source`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [PipelineSourceRepository](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/source/PipelineSourceRepository.java#L15) | — | — |
| [ProjectPathResolver](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/source/ProjectPathResolver.java#L11) | — | — |
| [SourceCatalogService](../../backend/src/main/java/io/queryforge/backend/admin/pipeline/source/SourceCatalogService.java#L18) | — | `AdminPipelineProperties`, `PipelineSourceRepository`, `ProjectPathResolver` |

### `admin.prompt.asset`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [PromptAssetController](../../backend/src/main/java/io/queryforge/backend/admin/prompt/asset/PromptAssetController.java#L16) | — | `PromptAssetDtos`, `PromptAssetService` |
| [PromptAssetDtos](../../backend/src/main/java/io/queryforge/backend/admin/prompt/asset/PromptAssetDtos.java#L8) | `PromptAssetRow`, `PromptAssetDetail`, `PromptRevisionRequest`, `PromptAssetUpdateRequest` | — |
| [PromptAssetRepository](../../backend/src/main/java/io/queryforge/backend/admin/prompt/asset/PromptAssetRepository.java#L21) | — | `PromptAssetDtos` |
| [PromptAssetService](../../backend/src/main/java/io/queryforge/backend/admin/prompt/asset/PromptAssetService.java#L12) | — | `PromptAssetDtos`, `PromptAssetRepository` |

### `admin.prompt.binding`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [PromptBindingController](../../backend/src/main/java/io/queryforge/backend/admin/prompt/binding/PromptBindingController.java#L15) | — | `PromptBindingDtos`, `PromptBindingService` |
| [PromptBindingDtos](../../backend/src/main/java/io/queryforge/backend/admin/prompt/binding/PromptBindingDtos.java#L9) | `PromptBindingRow`, `PromptBindingUpdateRequest`, `PromptValidationRequest`, `PromptValidationResponse` | — |
| [PromptBindingRepository](../../backend/src/main/java/io/queryforge/backend/admin/prompt/binding/PromptBindingRepository.java#L21) | — | `PromptBindingDtos` |
| [PromptBindingService](../../backend/src/main/java/io/queryforge/backend/admin/prompt/binding/PromptBindingService.java#L10) | — | `PromptBindingDtos`, `PromptBindingRepository` |

### `admin.web`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AdminApiExceptionHandler](../../backend/src/main/java/io/queryforge/backend/admin/web/AdminApiExceptionHandler.java#L8) | — | — |

### `common.logging`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ApplicationLayerLoggingAspect](../../backend/src/main/java/io/queryforge/backend/common/logging/ApplicationLayerLoggingAspect.java#L30) | — | — |

### `io.queryforge.backend`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [QueryForgeApplication](../../backend/src/main/java/io/queryforge/backend/QueryForgeApplication.java#L7) | — | — |

### `rag.chat`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AgenticChatService](../../backend/src/main/java/io/queryforge/backend/rag/chat/AgenticChatService.java#L32) | — | `AgenticRetrievalDtos`, `AgenticRetrievalService`, `AgenticTracePersistenceService`, `ChatConfigDtos`, `ChatDtos`, `ChatReadinessDtos`, `ChatResponseAssembler`, `DomainScopedRetrievalService`, `MemoryCandidate`, `QueryRouteDecision`, `RagPersistPolicy`, `RagTraceRepository`, `RetrievalDoc`, `RewriteScoring`, `TraceWriteResults` |
| [ChatDtos](../../backend/src/main/java/io/queryforge/backend/rag/chat/ChatDtos.java#L13) | `AskRequest`, `AskResponse` | `AgenticRetrievalDtos`, `ChatConfigDtos`, `RetrievalDtos`, `RewriteDtos` |
| [ChatResponseAssembler](../../backend/src/main/java/io/queryforge/backend/rag/chat/ChatResponseAssembler.java#L26) | `AgenticSettings`, `AnswerDraft` | `AgenticRetrievalDtos`, `AgenticRetrievalService`, `ChatAnswerService`, `ChatConfigDtos`, `DomainScopedRetrievalService`, `MemoryCandidate`, `QueryRouteDecision`, `RetrievalDoc`, `RetrievalDtos`, `RewriteDtos` |
| [RagController](../../backend/src/main/java/io/queryforge/backend/rag/chat/RagController.java#L9) | — | `ChatDtos`, `RagService` |
| [RagService](../../backend/src/main/java/io/queryforge/backend/rag/chat/RagService.java#L43) | `GeneratedCandidate`, `Decision` | `AgenticChatService`, `ChatConfigDtos`, `ChatDtos`, `ChatReadinessDtos`, `ChatReadinessService`, `ChatRequestRouting`, `ChatResponseAssembler`, `ChatRuntimeConfigService`, `CohereRerankService`, `DomainScopedRetrievalService`, `MemoryCandidate`, `QueryRouteDecision`, `QueryStrategy`, `QueryStrategyRouter`, `RagPersistPolicy`, `RagRetrievalExecutionService`, `RagTracePersistenceService`, `RagTraceRepository`, `RetrievalDoc`, `RewriteCandidateService`, `RewriteDtos`, `RewriteScoring`, `TraceWriteResults` |

### `rag.chat.answer`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChatAnswerService](../../backend/src/main/java/io/queryforge/backend/rag/chat/answer/ChatAnswerService.java#L28) | `GeneratedAnswer` | `GeminiServiceUnavailableException`, `RetrievalDoc`, `RuntimeEnvService`, `SpringAiChatGateway` |
| [GeminiServiceUnavailableException](../../backend/src/main/java/io/queryforge/backend/rag/chat/answer/GeminiServiceUnavailableException.java#L3) | — | — |

### `rag.chat.routing`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChatRequestRouting](../../backend/src/main/java/io/queryforge/backend/rag/chat/routing/ChatRequestRouting.java#L10) | — | `ChatConfigDtos`, `ChatReadinessDtos`, `QueryRouteContext` |

### `rag.embedding`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DenseEmbeddingService](../../backend/src/main/java/io/queryforge/backend/rag/embedding/DenseEmbeddingService.java#L24) | — | `AdminPipelineProperties`, `SpringAiEmbeddingGateway` |
| [HashEmbeddingService](../../backend/src/main/java/io/queryforge/backend/rag/embedding/HashEmbeddingService.java#L13) | — | — |

### `rag.embedding.reindex`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagEmbeddingRepository](../../backend/src/main/java/io/queryforge/backend/rag/embedding/reindex/RagEmbeddingRepository.java#L15) | `ChunkSource`, `MemorySource` | `RagJdbc` |
| [RagReindexController](../../backend/src/main/java/io/queryforge/backend/rag/embedding/reindex/RagReindexController.java#L9) | — | `RagReindexDtos`, `RagReindexService` |
| [RagReindexDtos](../../backend/src/main/java/io/queryforge/backend/rag/embedding/reindex/RagReindexDtos.java#L3) | `ReindexRequest`, `ReindexResponse` | — |
| [RagReindexService](../../backend/src/main/java/io/queryforge/backend/rag/embedding/reindex/RagReindexService.java#L9) | — | `HashEmbeddingService`, `RagEmbeddingRepository`, `RagReindexDtos` |

### `rag.eval.report`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [EvalReportController](../../backend/src/main/java/io/queryforge/backend/rag/eval/report/EvalReportController.java#L8) | — | `EvalReportDtos`, `EvalReportService` |
| [EvalReportDtos](../../backend/src/main/java/io/queryforge/backend/rag/eval/report/EvalReportDtos.java#L5) | `EvalReportResponse` | — |
| [EvalReportService](../../backend/src/main/java/io/queryforge/backend/rag/eval/report/EvalReportService.java#L18) | — | `EvalReportDtos` |

### `rag.eval.retrieval`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [EvalRuntimeConfigService](../../backend/src/main/java/io/queryforge/backend/rag/eval/retrieval/EvalRuntimeConfigService.java#L15) | `RuntimeEvalContext`, `AgenticEvalSettings` | `ChatConfigDtos`, `ChatReadinessDtos`, `RagRetrievalEvalDtos` |
| [RagRetrievalEvalController](../../backend/src/main/java/io/queryforge/backend/rag/eval/retrieval/RagRetrievalEvalController.java#L9) | — | `RagRetrievalEvalDtos`, `RagRetrievalEvalService` |
| [RagRetrievalEvalDtos](../../backend/src/main/java/io/queryforge/backend/rag/eval/retrieval/RagRetrievalEvalDtos.java#L11) | `RagRetrievalEvalRequest`, `RagRetrievalEvalResponse`, `RagRetrievalEvalDoc`, `RagEvalTrace` | `RagLlmCallCount`, `RagPersistPolicy` |
| [RagRetrievalEvalException](../../backend/src/main/java/io/queryforge/backend/rag/eval/retrieval/RagRetrievalEvalException.java#L3) | — | — |
| [RagRetrievalEvalService](../../backend/src/main/java/io/queryforge/backend/rag/eval/retrieval/RagRetrievalEvalService.java#L39) | — | `AgenticRetrievalService`, `ChatConfigDtos`, `ChatReadinessDtos`, `ChatReadinessService`, `ChatRuntimeConfigService`, `DomainScopedRetrievalService`, `EvalRuntimeConfigService`, `MemoryCandidate`, `QueryRouteContext`, `QueryRouteDecision`, `QueryStrategyRouter`, `RagLlmCallCount`, `RagPersistPolicy`, `RagRetrievalEvalDtos`, `RagRetrievalEvalException`, `RagRetrievalExecutionService`, `RetrievalDoc` |

### `rag.experiment.command`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ExperimentCommandController](../../backend/src/main/java/io/queryforge/backend/rag/experiment/command/ExperimentCommandController.java#L9) | — | `ExperimentCommandDtos`, `ExperimentPipelineService` |
| [ExperimentCommandDtos](../../backend/src/main/java/io/queryforge/backend/rag/experiment/command/ExperimentCommandDtos.java#L5) | `ExperimentCommandRequest`, `ExperimentCommandResponse` | — |
| [ExperimentPipelineService](../../backend/src/main/java/io/queryforge/backend/rag/experiment/command/ExperimentPipelineService.java#L27) | — | `AdminPipelineProperties`, `ExperimentCommandDtos` |

### `rag.experiment.summary`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ExperimentSummaryController](../../backend/src/main/java/io/queryforge/backend/rag/experiment/summary/ExperimentSummaryController.java#L11) | — | `ExperimentSummaryDtos`, `RagExperimentQueryService` |
| [ExperimentSummaryDtos](../../backend/src/main/java/io/queryforge/backend/rag/experiment/summary/ExperimentSummaryDtos.java#L8) | `ExperimentSummaryResponse` | — |
| [RagExperimentQueryService](../../backend/src/main/java/io/queryforge/backend/rag/experiment/summary/RagExperimentQueryService.java#L10) | — | `ExperimentSummaryDtos`, `RagExperimentRepository` |
| [RagExperimentRepository](../../backend/src/main/java/io/queryforge/backend/rag/experiment/summary/RagExperimentRepository.java#L20) | `ExperimentRunSummary` | `RagJdbc` |

### `rag.persistence`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagJdbc](../../backend/src/main/java/io/queryforge/backend/rag/persistence/RagJdbc.java#L13) | — | — |

### `rag.provider.environment`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RuntimeEnvService](../../backend/src/main/java/io/queryforge/backend/rag/provider/environment/RuntimeEnvService.java#L14) | — | `AdminPipelineProperties` |

### `rag.provider.springai`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [SpringAiChatGateway](../../backend/src/main/java/io/queryforge/backend/rag/provider/springai/SpringAiChatGateway.java#L12) | — | `RuntimeEnvService` |
| [SpringAiEmbeddingGateway](../../backend/src/main/java/io/queryforge/backend/rag/provider/springai/SpringAiEmbeddingGateway.java#L15) | — | `RuntimeEnvService` |
| [SpringAiVectorStoreGateway](../../backend/src/main/java/io/queryforge/backend/rag/provider/springai/SpringAiVectorStoreGateway.java#L8) | — | — |

### `rag.retrieval.agentic`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AgenticRetrievalDtos](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/agentic/AgenticRetrievalDtos.java#L10) | `SubqueryRetrievalTrace`, `AgenticRetrievalMetadata` | `AgenticPlanningDtos`, `RetrievalDtos`, `RewriteDtos` |
| [AgenticRetrievalService](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/agentic/AgenticRetrievalService.java#L37) | `AgenticExecutionRequest`, `AgenticExecutionResult`, `PersistedRewriteCandidate`, `SubqueryExecution`, `GeneratedCandidate`, `Decision` | `AgenticPlanningDtos`, `AgenticQueryPlannerService`, `AgenticRetrievalDtos`, `AgenticTracePersistenceService`, `ChatConfigDtos`, `ChatReadinessDtos`, `CohereRerankService`, `DomainScopedRetrievalService`, `MemoryCandidate`, `QueryRouteContext`, `QueryRouteDecision`, `QueryStrategy`, `QueryStrategyRouter`, `RagPersistPolicy`, `RetrievalDoc`, `RetrievalDtos`, `RewriteCandidateService`, `RewriteDtos`, `SearchResultMerger` |

### `rag.retrieval.agentic.fusion`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [SearchResultMerger](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/agentic/fusion/SearchResultMerger.java#L11) | `Accumulator` | `RetrievalDoc` |

### `rag.retrieval.agentic.planning`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AgenticPlanningDtos](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/agentic/planning/AgenticPlanningDtos.java#L8) | `AgenticSubquery`, `AgenticQueryPlan` | — |
| [AgenticQueryPlannerService](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/agentic/planning/AgenticQueryPlannerService.java#L27) | `PlannerPayload` | `AgenticPlanningDtos`, `ChatConfigDtos`, `MemoryCandidate`, `RuntimeEnvService`, `SpringAiChatGateway` |

### `rag.retrieval.candidate`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChunkRetrievalRepository](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/candidate/ChunkRetrievalRepository.java#L14) | — | `RetrievalDoc` |
| [DomainScopedRetrievalService](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/candidate/DomainScopedRetrievalService.java#L22) | `RetrievalRuntime` | `ChatConfigDtos`, `ChunkRetrievalRepository`, `DenseEmbeddingService`, `HashEmbeddingService`, `MemoryCandidate`, `RagMemoryRepository`, `RetrievalDoc` |
| [RetrievalDoc](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/candidate/RetrievalDoc.java#L3) | — | — |
| [RetrievalDtos](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/candidate/RetrievalDtos.java#L3) | `ScoredDocumentDto` | — |

### `rag.retrieval.execution`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ForcedRetrievalMode](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/execution/ForcedRetrievalMode.java#L3) | — | — |
| [RagExecutionTrace](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/execution/RagExecutionTrace.java#L5) | — | `ForcedRetrievalMode` |
| [RagLlmCallCount](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/execution/RagLlmCallCount.java#L3) | — | — |
| [RagPersistPolicy](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/execution/RagPersistPolicy.java#L3) | — | — |
| [RagRetrievalExecutionRequest](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/execution/RagRetrievalExecutionRequest.java#L7) | — | `ForcedRetrievalMode`, `RagPersistPolicy` |
| [RagRetrievalExecutionResult](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/execution/RagRetrievalExecutionResult.java#L5) | — | `ForcedRetrievalMode`, `RagExecutionTrace`, `RagLlmCallCount`, `RagRetrievedDoc` |
| [RagRetrievalExecutionService](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/execution/RagRetrievalExecutionService.java#L18) | `RawOnlyExecutionRequest`, `NonAgenticExecutionResult`, `NonAgenticExecutionKind`, `RetrievalMaterial`, `RawOnlyExecutionResult`, `SelectiveRewriteExecutionRequest`, `SelectiveRewriteExecutionResult`, `AnchorAwareRewriteExecutionRequest`, `AnchorAwareRewriteExecutionResult`, `ExecutedRewriteCandidate` | `CohereRerankService`, `DomainScopedRetrievalService`, `MemoryCandidate`, `RetrievalDoc`, `RewriteCandidateService` |
| [RagRetrievedDoc](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/execution/RagRetrievedDoc.java#L7) | — | — |

### `rag.retrieval.memory`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [MemoryCandidate](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/memory/MemoryCandidate.java#L7) | — | — |
| [RagMemoryRepository](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/memory/RagMemoryRepository.java#L19) | — | `MemoryCandidate`, `RagJdbc` |

### `rag.retrieval.rerank`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CohereRerankService](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/rerank/CohereRerankService.java#L19) | — | `RetrievalDoc` |

### `rag.retrieval.rewrite`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RewriteCandidateService](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/rewrite/RewriteCandidateService.java#L28) | `CandidateTemplate`, `PromptAsset` | `MemoryCandidate`, `RuntimeEnvService`, `SpringAiChatGateway` |
| [RewriteDtos](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/rewrite/RewriteDtos.java#L8) | `RewritePreviewRequest`, `RewriteCandidateDto`, `RewritePreviewResponse` | — |
| [RewriteScoring](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/rewrite/RewriteScoring.java#L11) | — | `MemoryCandidate`, `RetrievalDoc` |

### `rag.retrieval.rewrite.preview`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RewritePreviewController](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/rewrite/preview/RewritePreviewController.java#L10) | — | `RewriteDtos`, `RewritePreviewService` |
| [RewritePreviewService](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/rewrite/preview/RewritePreviewService.java#L23) | — | `ChunkRetrievalRepository`, `CohereRerankService`, `HashEmbeddingService`, `MemoryCandidate`, `RagMemoryRepository`, `RetrievalDoc`, `RewriteCandidateService`, `RewriteDtos`, `RewriteScoring` |

### `rag.retrieval.routing`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [QueryRouteContext](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/routing/QueryRouteContext.java#L8) | — | `ChatConfigDtos`, `ChatReadinessDtos` |
| [QueryRouteDecision](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/routing/QueryRouteDecision.java#L6) | — | `QueryStrategy` |
| [QueryStrategy](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/routing/QueryStrategy.java#L3) | — | — |
| [QueryStrategyRouter](../../backend/src/main/java/io/queryforge/backend/rag/retrieval/routing/QueryStrategyRouter.java#L14) | — | `ChatConfigDtos`, `ChatReadinessDtos`, `QueryRouteContext`, `QueryRouteDecision`, `QueryStrategy` |

### `rag.runtime.configuration`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChatConfigDtos](../../backend/src/main/java/io/queryforge/backend/rag/runtime/configuration/ChatConfigDtos.java#L9) | `ChatDomainOption`, `ChatRuntimeConfigResponse`, `ChatRuntimeConfigRequest`, `ChatRuntimeConfigProvenanceRow` | — |
| [ChatRuntimeConfigController](../../backend/src/main/java/io/queryforge/backend/rag/runtime/configuration/ChatRuntimeConfigController.java#L13) | — | `ChatConfigDtos`, `ChatRuntimeConfigService` |
| [ChatRuntimeConfigRepository](../../backend/src/main/java/io/queryforge/backend/rag/runtime/configuration/ChatRuntimeConfigRepository.java#L21) | — | `ChatConfigDtos` |
| [ChatRuntimeConfigService](../../backend/src/main/java/io/queryforge/backend/rag/runtime/configuration/ChatRuntimeConfigService.java#L23) | — | `AdminRuntimeOptionsDtos`, `AdminRuntimeOptionsService`, `ChatConfigDtos`, `ChatRuntimeConfigRepository`, `ChunkEmbeddingAdminDtos`, `ChunkEmbeddingAdminService`, `GatingSnapshotRepository` |

### `rag.runtime.promotion`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChatConfigPromotionController](../../backend/src/main/java/io/queryforge/backend/rag/runtime/promotion/ChatConfigPromotionController.java#L9) | — | `ChatConfigDtos`, `ChatConfigPromotionDtos`, `ChatConfigPromotionService` |
| [ChatConfigPromotionDtos](../../backend/src/main/java/io/queryforge/backend/rag/runtime/promotion/ChatConfigPromotionDtos.java#L5) | `ApplyChatConfigFromRagRunRequest` | — |
| [ChatConfigPromotionRepository](../../backend/src/main/java/io/queryforge/backend/rag/runtime/promotion/ChatConfigPromotionRepository.java#L16) | `RagRunApplySnapshot` | — |
| [ChatConfigPromotionService](../../backend/src/main/java/io/queryforge/backend/rag/runtime/promotion/ChatConfigPromotionService.java#L16) | — | `ChatConfigDtos`, `ChatConfigPromotionDtos`, `ChatConfigPromotionRepository`, `ChatRuntimeConfigService` |

### `rag.runtime.readiness`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChatReadinessController](../../backend/src/main/java/io/queryforge/backend/rag/runtime/readiness/ChatReadinessController.java#L10) | — | `ChatReadinessDtos`, `ChatReadinessService` |
| [ChatReadinessDtos](../../backend/src/main/java/io/queryforge/backend/rag/runtime/readiness/ChatReadinessDtos.java#L7) | `ChatDomainReadinessResponse`, `SnapshotReadiness`, `ChunkEmbeddingReadiness`, `PromptBindingReadiness`, `RetrievalReadiness` | — |
| [ChatReadinessRepository](../../backend/src/main/java/io/queryforge/backend/rag/runtime/readiness/ChatReadinessRepository.java#L18) | `DomainChunkEmbeddingStatus`, `PromptBindingSummary` | — |
| [ChatReadinessService](../../backend/src/main/java/io/queryforge/backend/rag/runtime/readiness/ChatReadinessService.java#L16) | — | `ChatConfigDtos`, `ChatReadinessDtos`, `ChatReadinessRepository`, `ChatRuntimeConfigService`, `GatingSnapshotRepository` |

### `rag.runtime.snapshot`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [GatingSnapshotRepository](../../backend/src/main/java/io/queryforge/backend/rag/runtime/snapshot/GatingSnapshotRepository.java#L14) | `GatingSnapshot` | — |

### `rag.trace.query`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagTraceDtos](../../backend/src/main/java/io/queryforge/backend/rag/trace/query/RagTraceDtos.java#L9) | `QueryTraceResponse` | `RewriteDtos` |
| [RagTraceQueryController](../../backend/src/main/java/io/queryforge/backend/rag/trace/query/RagTraceQueryController.java#L11) | — | `RagTraceDtos`, `RagTraceQueryService` |
| [RagTraceQueryService](../../backend/src/main/java/io/queryforge/backend/rag/trace/query/RagTraceQueryService.java#L10) | — | `RagTraceDtos`, `RagTraceReadRepository`, `RewriteDtos` |
| [RagTraceReadRepository](../../backend/src/main/java/io/queryforge/backend/rag/trace/query/RagTraceReadRepository.java#L18) | `RewriteCandidateRow`, `OnlineQueryRow` | `RagJdbc` |

### `rag.trace.write`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagTracePersistenceService](../../backend/src/main/java/io/queryforge/backend/rag/trace/write/RagTracePersistenceService.java#L21) | `RagTracePersistenceRequest`, `OnlineQueryDecisionPersistenceRequest`, `OnlineQueryMetadataMergePersistenceRequest`, `RawOnlyTracePersistenceRequest`, `RewriteCandidateTracePersistenceRequest`, `CreateRewriteCandidateTracePersistenceRequest`, `RewriteCandidateAdoptionPersistenceRequest`, `OnlineRewriteLogPersistenceRequest`, `MemoryRetrievalLogPersistenceRequest`, `RewriteCandidateLogPersistenceRequest`, `RawOnlyTraceWriteScope`, `RewriteCandidateTraceWriteScope` | `ForcedRetrievalMode`, `MemoryCandidate`, `RagPersistPolicy`, `RagRetrievalExecutionResult`, `RagRetrievalExecutionService`, `RagTraceRepository`, `RetrievalDoc`, `TraceWriteResults` |
| [RagTraceRepository](../../backend/src/main/java/io/queryforge/backend/rag/trace/write/RagTraceRepository.java#L18) | — | `MemoryCandidate`, `RetrievalDoc` |
| [TraceWriteResults](../../backend/src/main/java/io/queryforge/backend/rag/trace/write/TraceWriteResults.java#L7) | `RagTracePersistenceResult`, `RewriteCandidatePersistenceResult`, `OnlineRewriteLogPersistenceResult` | `RagPersistPolicy` |

### `rag.trace.write.agentic`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AgenticTracePersistenceService](../../backend/src/main/java/io/queryforge/backend/rag/trace/write/agentic/AgenticTracePersistenceService.java#L19) | `AgenticSubqueryRetrievalTracePersistenceRequest`, `AgenticRewriteCandidateTracePersistenceRequest`, `AgenticRewriteCandidateAdoptionPersistenceRequest`, `AgenticFinalRerankTracePersistenceRequest`, `AgenticOnlineRewriteLogPersistenceRequest`, `AgenticMemoryRetrievalLogPersistenceRequest`, `AgenticRewriteCandidateLogPersistenceRequest`, `AgenticOnlineQueryDecisionPersistenceRequest`, `AgenticOnlineQueryMetadataMergePersistenceRequest`, `AgenticRetrievalExecutionKind`, `AgenticSubqueryRetrievalTraceWriteScope` | `MemoryCandidate`, `RagPersistPolicy`, `RagTraceRepository`, `RetrievalDoc`, `TraceWriteResults` |

### `rag.web`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagApiExceptionHandler](../../backend/src/main/java/io/queryforge/backend/rag/web/RagApiExceptionHandler.java#L10) | — | `GeminiServiceUnavailableException`, `RagRetrievalEvalException` |

### `ui`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ReactUiController](../../backend/src/main/java/io/queryforge/backend/ui/ReactUiController.java#L6) | — | — |

## Test source

### `admin.console`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AdminConsoleGatingIntegrationTest](../../backend/src/test/java/io/queryforge/backend/admin/console/AdminConsoleGatingIntegrationTest.java#L43) | — | `AdminSyntheticMethodRepository`, `GatingRepository`, `LlmJobDtos`, `LlmJobRepository`, `SyntheticGenerationDtos` |

### `admin.console.evaluation`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AdminConsoleRagIntegrationTest](../../backend/src/test/java/io/queryforge/backend/admin/console/evaluation/AdminConsoleRagIntegrationTest.java#L28) | — | — |

### `admin.console.jobs`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [LlmJobTypeConstraintSyncTest](../../backend/src/test/java/io/queryforge/backend/admin/console/jobs/LlmJobTypeConstraintSyncTest.java#L16) | — | `LlmJobType` |

### `admin.corpus`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [CorpusAdminApiIntegrationTest](../../backend/src/test/java/io/queryforge/backend/admin/corpus/CorpusAdminApiIntegrationTest.java#L20) | — | — |
| [CorpusAdminMutationIntegrationTest](../../backend/src/test/java/io/queryforge/backend/admin/corpus/CorpusAdminMutationIntegrationTest.java#L29) | — | — |

### `admin.domain.workspace`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DomainWorkspaceIntegrationTest](../../backend/src/test/java/io/queryforge/backend/admin/domain/workspace/DomainWorkspaceIntegrationTest.java#L30) | — | — |

### `admin.pipeline`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [PipelineAdminIntegrationTest](../../backend/src/test/java/io/queryforge/backend/admin/pipeline/PipelineAdminIntegrationTest.java#L37) | `StubPipelineRunnerConfiguration` | `PipelineCommandRunner` |

### `admin.pipeline.artifact`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DocumentArtifactStoreServiceTest](../../backend/src/test/java/io/queryforge/backend/admin/pipeline/artifact/DocumentArtifactStoreServiceTest.java#L21) | — | `AdminPipelineProperties`, `DocumentArtifactStoreService`, `PipelineSourceRepository`, `SourceCatalogService` |

### `admin.pipeline.source`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ProjectPathResolverTest](../../backend/src/test/java/io/queryforge/backend/admin/pipeline/source/ProjectPathResolverTest.java#L13) | — | `ProjectPathResolver` |

### `io.queryforge.backend`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [QueryForgeApplicationTests](../../backend/src/test/java/io/queryforge/backend/QueryForgeApplicationTests.java#L12) | — | — |

### `rag.chat`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagControllerWebTest](../../backend/src/test/java/io/queryforge/backend/rag/chat/RagControllerWebTest.java#L26) | — | `ChatDtos`, `EvalReportController`, `EvalReportDtos`, `EvalReportService`, `GeminiServiceUnavailableException`, `RagController`, `RagService` |
| [RagServiceTest](../../backend/src/test/java/io/queryforge/backend/rag/chat/RagServiceTest.java#L58) | — | `AgenticChatService`, `AgenticPlanningDtos`, `AgenticRetrievalDtos`, `AgenticRetrievalService`, `AgenticTracePersistenceService`, `ChatAnswerService`, `ChatConfigDtos`, `ChatDtos`, `ChatReadinessDtos`, `ChatReadinessService`, `ChatResponseAssembler`, `ChatRuntimeConfigService`, `ChunkRetrievalRepository`, `CohereRerankService`, `DenseEmbeddingService`, `DomainScopedRetrievalService`, `HashEmbeddingService`, `MemoryCandidate`, `QueryStrategyRouter`, `RagMemoryRepository`, `RagPersistPolicy`, `RagRetrievalExecutionService`, `RagService`, `RagTracePersistenceService`, `RagTraceRepository`, `RetrievalDoc`, `RetrievalDtos`, `RewriteCandidateService` |

### `rag.chat.answer`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [ChatAnswerServiceTest](../../backend/src/test/java/io/queryforge/backend/rag/chat/answer/ChatAnswerServiceTest.java#L27) | `ExchangeHandler` | `ChatAnswerService`, `GeminiServiceUnavailableException`, `RetrievalDoc`, `RuntimeEnvService`, `SpringAiChatGateway` |

### `rag.embedding`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DenseEmbeddingServiceTest](../../backend/src/test/java/io/queryforge/backend/rag/embedding/DenseEmbeddingServiceTest.java#L16) | — | `DenseEmbeddingService`, `SpringAiEmbeddingGateway` |
| [HashEmbeddingServiceTest](../../backend/src/test/java/io/queryforge/backend/rag/embedding/HashEmbeddingServiceTest.java#L9) | — | `HashEmbeddingService` |

### `rag.eval.retrieval`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagRetrievalEvalControllerTest](../../backend/src/test/java/io/queryforge/backend/rag/eval/retrieval/RagRetrievalEvalControllerTest.java#L33) | — | `ChatAnswerService`, `ChunkRetrievalRepository`, `RagApiExceptionHandler`, `RagLlmCallCount`, `RagMemoryRepository`, `RagPersistPolicy`, `RagRetrievalEvalController`, `RagRetrievalEvalDtos`, `RagRetrievalEvalException`, `RagRetrievalEvalService`, `RagService`, `RagTraceRepository` |
| [RagRetrievalEvalServiceTest](../../backend/src/test/java/io/queryforge/backend/rag/eval/retrieval/RagRetrievalEvalServiceTest.java#L48) | — | `AgenticPlanningDtos`, `AgenticRetrievalService`, `ChatAnswerService`, `ChatConfigDtos`, `ChatReadinessDtos`, `ChatReadinessService`, `ChatRuntimeConfigService`, `ChunkRetrievalRepository`, `DomainScopedRetrievalService`, `EvalRuntimeConfigService`, `MemoryCandidate`, `QueryRouteDecision`, `QueryStrategy`, `QueryStrategyRouter`, `RagMemoryRepository`, `RagPersistPolicy`, `RagRetrievalEvalDtos`, `RagRetrievalEvalException`, `RagRetrievalEvalService`, `RagRetrievalExecutionService`, `RagService`, `RagTraceRepository`, `RetrievalDoc` |

### `rag.retrieval.agentic`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AgenticRetrievalServiceTest](../../backend/src/test/java/io/queryforge/backend/rag/retrieval/agentic/AgenticRetrievalServiceTest.java#L48) | — | `AgenticPlanningDtos`, `AgenticQueryPlannerService`, `AgenticRetrievalDtos`, `AgenticRetrievalService`, `AgenticTracePersistenceService`, `ChatConfigDtos`, `ChatReadinessDtos`, `ChunkRetrievalRepository`, `CohereRerankService`, `DenseEmbeddingService`, `DomainScopedRetrievalService`, `HashEmbeddingService`, `MemoryCandidate`, `QueryStrategyRouter`, `RagMemoryRepository`, `RagPersistPolicy`, `RagTraceRepository`, `RetrievalDoc`, `RewriteCandidateService`, `SearchResultMerger`, `TraceWriteResults` |

### `rag.retrieval.agentic.fusion`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [SearchResultMergerTest](../../backend/src/test/java/io/queryforge/backend/rag/retrieval/agentic/fusion/SearchResultMergerTest.java#L10) | — | `RetrievalDoc`, `SearchResultMerger` |

### `rag.retrieval.agentic.planning`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AgenticQueryPlannerServiceTest](../../backend/src/test/java/io/queryforge/backend/rag/retrieval/agentic/planning/AgenticQueryPlannerServiceTest.java#L24) | — | `AgenticPlanningDtos`, `AgenticQueryPlannerService`, `ChatConfigDtos`, `RuntimeEnvService`, `SpringAiChatGateway` |

### `rag.retrieval.candidate`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [DomainScopedRetrievalServiceTest](../../backend/src/test/java/io/queryforge/backend/rag/retrieval/candidate/DomainScopedRetrievalServiceTest.java#L27) | — | `ChunkRetrievalRepository`, `DenseEmbeddingService`, `DomainScopedRetrievalService`, `HashEmbeddingService`, `MemoryCandidate`, `RagMemoryRepository`, `RetrievalDoc` |

### `rag.retrieval.execution`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagRetrievalExecutionModelTest](../../backend/src/test/java/io/queryforge/backend/rag/retrieval/execution/RagRetrievalExecutionModelTest.java#L13) | — | `ForcedRetrievalMode`, `QueryStrategy`, `RagExecutionTrace`, `RagLlmCallCount`, `RagPersistPolicy`, `RagRetrievalExecutionRequest`, `RagRetrievalExecutionResult`, `RagRetrievedDoc` |
| [RagRetrievalExecutionServiceTest](../../backend/src/test/java/io/queryforge/backend/rag/retrieval/execution/RagRetrievalExecutionServiceTest.java#L35) | — | `ChunkRetrievalRepository`, `CohereRerankService`, `DenseEmbeddingService`, `DomainScopedRetrievalService`, `HashEmbeddingService`, `MemoryCandidate`, `RagMemoryRepository`, `RagRetrievalExecutionService`, `RagTracePersistenceService`, `RagTraceRepository`, `RetrievalDoc`, `RewriteCandidateService` |

### `rag.retrieval.routing`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [QueryStrategyRouterTest](../../backend/src/test/java/io/queryforge/backend/rag/retrieval/routing/QueryStrategyRouterTest.java#L15) | — | `ChatConfigDtos`, `ChatReadinessDtos`, `QueryRouteContext`, `QueryRouteDecision`, `QueryStrategy`, `QueryStrategyRouter` |

### `rag.trace.write`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [RagTracePersistenceServiceTest](../../backend/src/test/java/io/queryforge/backend/rag/trace/write/RagTracePersistenceServiceTest.java#L40) | — | `AgenticTracePersistenceService`, `ChatAnswerService`, `ForcedRetrievalMode`, `MemoryCandidate`, `RagPersistPolicy`, `RagRetrievalExecutionResult`, `RagRetrievalExecutionService`, `RagTracePersistenceService`, `RagTraceRepository`, `RetrievalDoc`, `TraceWriteResults` |

### `ui`

| Type | Named nested types | Backend type references |
| --- | --- | --- |
| [AdminIngestWizardRenderTest](../../backend/src/test/java/io/queryforge/backend/ui/AdminIngestWizardRenderTest.java#L12) | — | `ReactUiController` |
| [AdminUiSmokeTest](../../backend/src/test/java/io/queryforge/backend/ui/AdminUiSmokeTest.java#L23) | — | — |
