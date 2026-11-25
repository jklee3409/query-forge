package io.queryforge.backend.admin.console.embedding;

import io.queryforge.backend.admin.console.configuration.ConsoleExperimentConfig;
import io.queryforge.backend.admin.console.jobs.LlmJobDtos;
import io.queryforge.backend.admin.console.jobs.LlmJobService;
import io.queryforge.backend.admin.console.runtime.AdminRuntimeOptionsDtos;
import io.queryforge.backend.admin.console.runtime.AdminRuntimeOptionsService;
import io.queryforge.backend.admin.console.runtime.AdminRuntimeOptionsService.RuntimeCatalog;
import io.queryforge.backend.admin.console.runtime.AdminRuntimeOptionsService.RuntimeOptionMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static io.queryforge.backend.admin.console.configuration.ConsoleParameters.blankToNull;
import static io.queryforge.backend.admin.console.configuration.ConsoleParameters.defaultCreatedBy;
import static io.queryforge.backend.admin.console.configuration.ConsoleParameters.firstNonBlank;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChunkEmbeddingAdminService {

    private final AdminRuntimeOptionsService runtimeOptionsService;
    private final ConsoleExperimentConfig experimentConfig;

    private static final String SYNTHETIC_FREE_BASELINE_METHOD = "BASELINE";

    private static final String RETRIEVAL_BACKEND_DB_ANN = "db_ann";

    private static final String RETRIEVER_MODE_DENSE_ONLY = "dense_only";

    private static final String DEFAULT_DENSE_EMBEDDING_MODEL = "intfloat/multilingual-e5-small";

    private static final int DEFAULT_RETRIEVER_CANDIDATE_POOL_K = 50;

    private static final String VECTOR_STORE_POSTGRESQL_PGVECTOR = "postgresql-pgvector";

    private final ChunkEmbeddingAdminRepository chunkEmbeddingRepository;

    private final LlmJobService llmJobService;

    public ChunkEmbeddingAdminDtos.ChunkEmbeddingMaterializationStatusResponse getChunkEmbeddingMaterializationStatus(String embeddingModel) {
        RuntimeCatalog catalog = runtimeOptionsService.loadRuntimeCatalog();
        String normalizedEmbeddingModel = normalizeChunkEmbeddingModel(embeddingModel, catalog);
        return buildChunkEmbeddingStatus(normalizedEmbeddingModel);
    }

    @Transactional
    public LlmJobDtos.LlmJobRow runChunkEmbeddingMaterialization(
            ChunkEmbeddingAdminDtos.ChunkEmbeddingMaterializationRequest request
    ) {
        RuntimeCatalog catalog = runtimeOptionsService.loadRuntimeCatalog();
        String normalizedEmbeddingModel = normalizeChunkEmbeddingModel(
                request == null ? null : request.embeddingModel(),
                catalog
        );
        ChunkEmbeddingAdminDtos.ChunkEmbeddingMaterializationStatusResponse status = buildChunkEmbeddingStatus(normalizedEmbeddingModel);
        if (status.ready()) {
            throw new IllegalArgumentException(
                    "chunk embeddings are already materialized for embedding_model=" + normalizedEmbeddingModel
            );
        }
        String experimentName = "admin_materialize_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> config = experimentConfig.baseExperimentConfig(experimentName, SYNTHETIC_FREE_BASELINE_METHOD);
        Map<String, Object> retrieverConfig = runtimeOptionsService.resolveRetrieverConfig(
                new AdminRuntimeOptionsDtos.RetrieverConfigRequest(
                        RETRIEVER_MODE_DENSE_ONLY,
                        normalizedEmbeddingModel,
                        true,
                        false,
                        false,
                        DEFAULT_RETRIEVER_CANDIDATE_POOL_K,
                        1.0d,
                        0.0d,
                        0.0d
                ),
                false
        );
        runtimeOptionsService.attachRetrieverConfig(config, retrieverConfig);
        config.put("retrieval_backend", RETRIEVAL_BACKEND_DB_ANN);
        config.put("chunk_embedding_model", normalizedEmbeddingModel);
        config.put("vector_store", VECTOR_STORE_POSTGRESQL_PGVECTOR);
        config.put("chunk_embedding_force_refresh", false);
        experimentConfig.writeExperimentConfig(experimentName, config);
        UUID jobId = llmJobService.createChunkEmbeddingMaterializationJob(
                experimentName,
                defaultCreatedBy(request == null ? null : request.createdBy())
        );
        return llmJobService.getJob(jobId);
    }

    private String normalizeChunkEmbeddingModel(String embeddingModel, RuntimeCatalog catalog) {
        String normalized = firstNonBlank(
                blankToNull(embeddingModel),
                blankToNull(catalog.defaultDenseEmbeddingModel()),
                DEFAULT_DENSE_EMBEDDING_MODEL
        );
        RuntimeOptionMetadata option = catalog.findDenseEmbeddingModel(normalized);
        if (option == null) {
            throw new IllegalArgumentException("dense_embedding_model is not allowed by catalog: " + normalized);
        }
        if (!runtimeOptionsService.isSelectable(option)) {
            throw new IllegalArgumentException(
                    "dense_embedding_model is not selectable by catalog: " + normalized + runtimeOptionsService.appendReason(option.reason())
            );
        }
        return normalized;
    }

    private ChunkEmbeddingAdminDtos.ChunkEmbeddingMaterializationStatusResponse buildChunkEmbeddingStatus(String embeddingModel) {
        long totalChunkCount = chunkEmbeddingRepository.countCorpusChunks();
        long materializedChunkCount = chunkEmbeddingRepository.countMaterializedChunkEmbeddings(embeddingModel);
        long missingChunkCount = Math.max(0L, totalChunkCount - materializedChunkCount);
        return new ChunkEmbeddingAdminDtos.ChunkEmbeddingMaterializationStatusResponse(
                embeddingModel,
                VECTOR_STORE_POSTGRESQL_PGVECTOR,
                totalChunkCount,
                materializedChunkCount,
                missingChunkCount,
                totalChunkCount > 0L && missingChunkCount == 0L,
                chunkEmbeddingRepository.findLatestChunkEmbeddingUpdatedAt(embeddingModel)
        );
    }

    public void requireChunkEmbeddingsReady(String embeddingModel) {
        if (embeddingModel == null || embeddingModel.isBlank()) {
            throw new IllegalArgumentException("db-ann retrieval_backend requires dense_embedding_model");
        }
        ChunkEmbeddingAdminDtos.ChunkEmbeddingMaterializationStatusResponse status = buildChunkEmbeddingStatus(embeddingModel);
        if (status.ready()) {
            return;
        }
        throw new IllegalStateException(
                "chunk embedding materialization required: embedding_model=" + embeddingModel
                        + ", total_chunks=" + status.totalChunkCount()
                        + ", materialized_chunks=" + status.materializedChunkCount()
                        + ", missing_chunks=" + status.missingChunkCount()
        );
    }
}
