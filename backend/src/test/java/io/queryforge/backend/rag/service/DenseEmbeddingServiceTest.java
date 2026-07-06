package io.queryforge.backend.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DenseEmbeddingServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void springAiEmbeddingGatewayCanSupplyDenseQueryEmbeddingAndStillUsesCache() {
        SpringAiEmbeddingGateway gateway = mock(SpringAiEmbeddingGateway.class);
        List<Double> embedding = IntStream.range(0, 384)
                .mapToDouble(index -> index / 384.0d)
                .boxed()
                .toList();
        when(gateway.embedQuery("FilterChainProxy order", "spring-ai:text-embedding-3-small"))
                .thenReturn(Optional.of(embedding));
        DenseEmbeddingService service = new DenseEmbeddingService(null, objectMapper, gateway);

        List<Double> first = service.embedQuery(
                "FilterChainProxy order",
                "dense_only",
                "spring-ai:text-embedding-3-small"
        );
        List<Double> second = service.embedQuery(
                "FilterChainProxy order",
                "dense_only",
                "spring-ai:text-embedding-3-small"
        );

        assertThat(first).containsExactlyElementsOf(embedding);
        assertThat(second).containsExactlyElementsOf(embedding);
        verify(gateway).embedQuery("FilterChainProxy order", "spring-ai:text-embedding-3-small");
    }
}
