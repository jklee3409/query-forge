package io.queryforge.backend.rag.controller;

import io.queryforge.backend.rag.service.GeminiServiceUnavailableException;
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
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                GEMINI_SERVICE_UNAVAILABLE_MESSAGE
        );
        detail.setTitle("Gemini service unavailable");
        detail.setProperty("errorCode", GEMINI_SERVICE_UNAVAILABLE_CODE);
        detail.setProperty("retryable", true);
        detail.setProperty("retryMessage", GEMINI_RETRY_MESSAGE);
        detail.setProperty("provider", "gemini");
        detail.setProperty("model", exception.modelName());
        detail.setProperty("statusCode", exception.statusCode());
        detail.setProperty("attempts", exception.attempts());
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        detail.setTitle("Invalid request");
        return detail;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleIllegalState(IllegalStateException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
        detail.setTitle("Pipeline error");
        return detail;
    }
}
