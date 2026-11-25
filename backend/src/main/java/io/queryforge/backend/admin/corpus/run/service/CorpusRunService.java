package io.queryforge.backend.admin.corpus.run.service;

import io.queryforge.backend.admin.corpus.run.dto.CorpusRunDtos;
import io.queryforge.backend.admin.corpus.run.repository.CorpusRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CorpusRunService {

    private final CorpusRunRepository repository;

    public List<CorpusRunDtos.RunSummary> listRuns(
            UUID runId,
            String runStatus,
            String runType,
            UUID domainId,
            Integer limit,
            Integer offset
    ) {
        return repository.findRuns(runId, runStatus, runType, domainId, limit, offset);
    }

    public CorpusRunDtos.RunDetail getRunDetail(UUID runId) {
        return new CorpusRunDtos.RunDetail(
                repository.findRun(runId),
                repository.findRunSteps(runId)
        );
    }
}
