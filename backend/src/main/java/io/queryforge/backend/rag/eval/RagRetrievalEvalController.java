package io.queryforge.backend.rag.eval;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag/eval")
@RequiredArgsConstructor
public class RagRetrievalEvalController {

    private final RagRetrievalEvalService ragRetrievalEvalService;

    @PostMapping("/retrieval")
    public RagRetrievalEvalDtos.RagRetrievalEvalResponse evaluateRetrieval(
            @RequestBody RagRetrievalEvalDtos.RagRetrievalEvalRequest request
    ) {
        return ragRetrievalEvalService.evaluate(request);
    }
}
