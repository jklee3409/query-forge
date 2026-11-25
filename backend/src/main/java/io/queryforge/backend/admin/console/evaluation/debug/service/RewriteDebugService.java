package io.queryforge.backend.admin.console.evaluation.debug.service;

import io.queryforge.backend.admin.console.evaluation.debug.dto.RewriteDebugDtos;
import io.queryforge.backend.admin.console.evaluation.debug.repository.RewriteDebugRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RewriteDebugService {

    private final RewriteDebugRepository rewriteDebugRepository;

    public List<RewriteDebugDtos.RewriteDebugRow> listRewriteDebugRows(Integer limit, Integer offset) {
        return rewriteDebugRepository.findRewriteDebugRows(limit, offset);
    }

    public RewriteDebugDtos.RewriteDebugDetail getRewriteDebugDetail(UUID rewriteLogId) {
        return rewriteDebugRepository.findRewriteDebugDetail(rewriteLogId)
                .orElseThrow(() -> new IllegalArgumentException("rewrite log not found: " + rewriteLogId));
    }
}
