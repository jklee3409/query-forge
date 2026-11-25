package io.queryforge.backend.rag.retrieval;

public record RetrievalDoc(String documentId, String chunkId, String chunkText, double score) {
}
