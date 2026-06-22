package io.queryforge.backend.rag.service;

public class GeminiServiceUnavailableException extends IllegalStateException {

    private final String modelName;
    private final int statusCode;
    private final int attempts;

    public GeminiServiceUnavailableException(String modelName, int statusCode, int attempts) {
        super("Gemini answer generation failed with service unavailable status after retry");
        this.modelName = modelName;
        this.statusCode = statusCode;
        this.attempts = attempts;
    }

    public String modelName() {
        return modelName;
    }

    public int statusCode() {
        return statusCode;
    }

    public int attempts() {
        return attempts;
    }
}
