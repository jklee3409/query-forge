package io.queryforge.backend.rag.trace.query;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RagTraceQueryController {

    private final RagTraceQueryService traceQueryService;

    @GetMapping("/queries/{id}/trace")
    public RagTraceDtos.QueryTraceResponse queryTrace(@PathVariable("id") UUID onlineQueryId) {
        return traceQueryService.getQueryTrace(onlineQueryId);
    }
}
