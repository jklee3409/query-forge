package io.queryforge.backend.rag.service;

import io.queryforge.backend.rag.repository.RagRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchResultMergerTest {

    private final SearchResultMerger merger = new SearchResultMerger();

    @Test
    void mergeRrfDedupesByChunkIdAndKeepsTopRankedChunks() {
        List<RagRepository.RetrievalDoc> first = List.of(
                new RagRepository.RetrievalDoc("doc-1", "chunk-a", "A", 0.8d),
                new RagRepository.RetrievalDoc("doc-2", "chunk-b", "B", 0.7d)
        );
        List<RagRepository.RetrievalDoc> second = List.of(
                new RagRepository.RetrievalDoc("doc-2", "chunk-b", "B updated", 0.95d),
                new RagRepository.RetrievalDoc("doc-3", "chunk-c", "C", 0.6d)
        );

        List<RagRepository.RetrievalDoc> merged = merger.mergeRrf(List.of(first, second), 3, 60);

        assertThat(merged).hasSize(3);
        assertThat(merged.getFirst().chunkId()).isEqualTo("chunk-b");
        assertThat(merged.getFirst().chunkText()).isEqualTo("B updated");
        assertThat(merged).extracting(RagRepository.RetrievalDoc::chunkId)
                .containsExactly("chunk-b", "chunk-a", "chunk-c");
    }
}
