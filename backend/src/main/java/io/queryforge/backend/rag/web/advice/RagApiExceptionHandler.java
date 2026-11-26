package io.queryforge.backend.rag.web.advice;

import io.queryforge.backend.rag.chat.answer.exception.GeminiServiceUnavailableException;
import io.queryforge.backend.rag.eval.retrieval.exception.RagRetrievalEvalException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "io.queryforge.backend.rag")
public class RagApiExceptionHandler {

    private static final String GEMINI_SERVICE_UNAVAILABLE_CODE = "GEMINI_SERVICE_UNAVAILABLE";
    private static final String GEMINI_SERVICE_UNAVAILABLE_MESSAGE =
            "Gemini 모델에 문제가 발생하였습니다. 잠시 후 다시 시도해주세요.";
    private static final String GEMINI_RETRY_MESSAGE =
            "Gemini 모델에 문제가 발생하였습니다. 답변을 다시 생성 중입니다";

    @ExceptionHandler(GeminiServiceUnavailableException.class)
    public ProblemDetail handleGeminiServiceUnavailable(GeminiServiceUnavailableException exception) {
        ProblemDetail detail = problem(
                HttpStatus.SERVICE_UNAVAILABLE, "Gemini service unavailable", GEMINI_SERVICE_UNAVAILABLE_MESSAGE
        );
        detail.setProperty("errorCode", GEMINI_SERVICE_UNAVAILABLE_CODE);
        detail.setProperty("retryable", true);
        detail.setProperty("retryMessage", GEMINI_RETRY_MESSAGE);
        detail.setProperty("provider", "gemini");
        detail.setProperty("model", exception.modelName());
        detail.setProperty("statusCode", exception.statusCode());
        detail.setProperty("attempts", exception.attempts());
        return detail;
    }

    @ExceptionHandler(RagRetrievalEvalException.class)
    public ProblemDetail handleRagRetrievalEval(RagRetrievalEvalException exception) {
        ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, "Retrieval eval request rejected", exception.getMessage());
        detail.setProperty("code", exception.code());
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException exception) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Pipeline error", exception.getMessage());
    }

    private ProblemDetail problem(HttpStatus status, String title, String message) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setTitle(title);
        return detail;
    }
}
