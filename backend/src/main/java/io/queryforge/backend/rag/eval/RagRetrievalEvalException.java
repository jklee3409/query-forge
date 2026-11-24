package io.queryforge.backend.rag.eval;

public final class RagRetrievalEvalException extends IllegalArgumentException {

    private final String code;

    public RagRetrievalEvalException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
