package io.queryforge.backend.admin.console.evaluation.debug;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/console")
@RequiredArgsConstructor
public class RewriteDebugController {

    private final RewriteDebugService service;

    @GetMapping("/rewrite/logs")
    public List<RewriteDebugDtos.RewriteDebugRow> rewriteLogs(
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset
    ) {
        return service.listRewriteDebugRows(limit, offset);
    }

    @GetMapping("/rewrite/logs/{rewriteLogId}")
    public RewriteDebugDtos.RewriteDebugDetail rewriteLogDetail(@PathVariable UUID rewriteLogId) {
        return service.getRewriteDebugDetail(rewriteLogId);
    }
}
