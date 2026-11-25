package io.queryforge.backend.rag.eval;

import com.fasterxml.jackson.databind.JsonNode;

public final class EvalReportDtos {

    private EvalReportDtos() {
    }

    public record EvalReportResponse(
            String reportType,
            JsonNode payload
    ) {
    }
}
