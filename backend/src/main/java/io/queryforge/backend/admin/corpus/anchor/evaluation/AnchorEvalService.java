package io.queryforge.backend.admin.corpus.anchor.evaluation;

import io.queryforge.backend.admin.corpus.document.CorpusDocumentDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnchorEvalService {

    private final AnchorEvalRepository repository;

    @Transactional
    public AnchorEvalDtos.AnchorEvalRunSummary createAnchorEvalRun(AnchorEvalDtos.AnchorEvalRunCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("anchor eval run request is required");
        }
        int sampleSize = request.sampleSize() == null ? 20 : Math.max(1, Math.min(200, request.sampleSize()));
        int candidateLimit = request.candidateLimit() == null ? 10 : Math.max(1, Math.min(50, request.candidateLimit()));
        UUID runId = UUID.randomUUID();
        String runName = request.runName() == null || request.runName().isBlank() ? "anchor-eval-" + runId.toString().substring(0, 8) : request.runName().trim();
        String createdBy = request.createdBy() == null || request.createdBy().isBlank() ? "admin-ui" : request.createdBy().trim();
        repository.createAnchorEvalRun(
                runId,
                runName,
                request.productName(),
                request.sourceId(),
                sampleSize,
                candidateLimit,
                createdBy
        );
        List<String> documentIds = normalizeDistinctNonBlank(request.documentIds());
        List<String> chunkIds = normalizeDistinctNonBlank(request.chunkIds());
        List<CorpusDocumentDtos.ChunkDetail> chunks = repository.findAnchorEvalTargetChunks(
                request.productName(),
                request.sourceId(),
                documentIds,
                chunkIds,
                sampleSize
        );
        for (CorpusDocumentDtos.ChunkDetail chunk : chunks) {
            UUID sampleId = UUID.randomUUID();
            repository.insertAnchorEvalSample(sampleId, runId, chunk.documentId(), chunk.chunkId(), chunk.chunkText());
            List<AnchorEvalDtos.AnchorEvalCandidateDto> candidates = repository.findAnchorEvalChunkCandidates(chunk.chunkId(), candidateLimit);
            for (AnchorEvalDtos.AnchorEvalCandidateDto candidate : candidates) {
                repository.insertAnchorEvalCandidate(sampleId, candidate);
            }
        }
        repository.completeAnchorEvalRun(runId, repository.computeAnchorEvalSummary(runId));
        return repository.findAnchorEvalRun(runId);
    }

    public List<AnchorEvalDtos.AnchorEvalRunSummary> listAnchorEvalRuns(Integer limit, Integer offset) {
        return repository.findAnchorEvalRuns(limit, offset);
    }

    public AnchorEvalDtos.AnchorEvalRunDetail getAnchorEvalRun(UUID runId) {
        return new AnchorEvalDtos.AnchorEvalRunDetail(
                repository.findAnchorEvalRun(runId),
                repository.findAnchorEvalSamples(runId)
        );
    }

    @Transactional
    public AnchorEvalDtos.AnchorEvalRunSummary upsertAnchorEvalLabel(UUID runId, AnchorEvalDtos.AnchorEvalLabelRequest request) {
        if (request == null || request.candidateId() == null || request.labelValue() == null || request.labelValue().isBlank()) {
            throw new IllegalArgumentException("candidateId and labelValue are required");
        }
        String labeledBy = request.labeledBy() == null || request.labeledBy().isBlank() ? "admin-ui" : request.labeledBy().trim();
        repository.upsertAnchorEvalLabel(
                runId,
                request.candidateId(),
                request.labelValue().trim(),
                request.confidence(),
                request.note(),
                labeledBy
        );
        repository.completeAnchorEvalRun(runId, repository.computeAnchorEvalSummary(runId));
        return repository.findAnchorEvalRun(runId);
    }

    @Transactional
    public AnchorEvalDtos.AnchorEvalRunSummary recomputeAnchorEvalSummary(UUID runId) {
        repository.completeAnchorEvalRun(runId, repository.computeAnchorEvalSummary(runId));
        return repository.findAnchorEvalRun(runId);
    }

    private List<String> normalizeDistinctNonBlank(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> dedup = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isBlank()) {
                dedup.add(trimmed);
            }
        }
        return List.copyOf(dedup);
    }
}
