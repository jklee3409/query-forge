package io.queryforge.backend.rag.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HashEmbeddingServiceTest {

    private final HashEmbeddingService service = new HashEmbeddingService();

    @Test
    void embeddingIsDeterministicAndNormalized() {
        List<Double> first = service.embed("Spring Security filter chain 설정 순서");
        List<Double> second = service.embed("Spring Security filter chain 설정 순서");

        assertThat(first).hasSize(HashEmbeddingService.DIMENSION);
        assertThat(second).hasSize(HashEmbeddingService.DIMENSION);
        assertThat(first).containsExactlyElementsOf(second);

        double norm = Math.sqrt(first.stream().mapToDouble(value -> value * value).sum());
        assertThat(norm).isBetween(0.99, 1.01);
    }

    @Test
    void cosineIsHigherForSimilarTexts() {
        List<Double> base = service.embed("Spring Boot datasource 설정");
        List<Double> similar = service.embed("Spring Boot datasource 설정 방법");
        List<Double> different = service.embed("Hibernate second level cache tuning");

        assertThat(service.cosine(base, similar)).isGreaterThan(service.cosine(base, different));
    }
}

