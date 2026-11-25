package io.queryforge.backend.rag.retrieval.agentic;

import io.queryforge.backend.rag.retrieval.RetrievalDoc;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchResultMergerTest {

    private final SearchResultMerger merger = new SearchResultMerger();

    @Test
    void mergeRrfDedupesByChunkIdAndKeepsTopRankedChunks() {
        List<RetrievalDoc> first = List.of(
                new RetrievalDoc("doc-1", "chunk-a", "A", 0.8d),
                new RetrievalDoc("doc-2", "chunk-b", "B", 0.7d)
        );
        List<RetrievalDoc> second = List.of(
                new RetrievalDoc("doc-2", "chunk-b", "B updated", 0.95d),
                new RetrievalDoc("doc-3", "chunk-c", "C", 0.6d)
        );

        List<RetrievalDoc> merged = merger.mergeRrf(List.of(first, second), 3, 60);

        assertThat(merged).hasSize(3);
        assertThat(merged.getFirst().chunkId()).isEqualTo("chunk-b");
        assertThat(merged.getFirst().chunkText()).isEqualTo("B updated");
        assertThat(merged).extracting(RetrievalDoc::chunkId)
                .containsExactly("chunk-b", "chunk-a", "chunk-c");
    }
}
