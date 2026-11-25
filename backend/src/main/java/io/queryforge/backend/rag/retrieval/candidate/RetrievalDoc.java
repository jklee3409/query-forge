package io.queryforge.backend.rag.retrieval.candidate;

public record RetrievalDoc(String documentId, String chunkId, String chunkText, double score) {
}
