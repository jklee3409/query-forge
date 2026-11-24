package io.queryforge.backend.rag.provider;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class SpringAiEmbeddingGateway {

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final RuntimeEnvService runtimeEnvService;

    public Optional<List<Double>> embedQuery(String query, String denseEmbeddingModel) {
        if (!isEnabledFor(denseEmbeddingModel)) {
            return Optional.empty();
        }
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            return Optional.empty();
        }
        try {
            Optional<List<Double>> direct = invokeEmbed(embeddingModel, query);
            if (direct.isPresent()) {
                return direct;
            }
            Optional<List<Double>> response = invokeEmbedForResponse(embeddingModel, query);
            return response.filter(values -> !values.isEmpty());
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    private boolean isEnabledFor(String denseEmbeddingModel) {
        String client = runtimeEnvService.getOrDefault("QUERY_FORGE_DENSE_EMBEDDING_CLIENT", "")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (!client.isBlank()) {
            return "spring_ai".equals(client) || "spring-ai".equals(client);
        }
        String normalizedModel = denseEmbeddingModel == null ? "" : denseEmbeddingModel.trim().toLowerCase(Locale.ROOT);
        return normalizedModel.startsWith("spring-ai:");
    }

    private Optional<List<Double>> invokeEmbed(EmbeddingModel embeddingModel, String query) throws ReflectiveOperationException {
        try {
            Method method = embeddingModel.getClass().getMethod("embed", String.class);
            return toVector(method.invoke(embeddingModel, query));
        } catch (NoSuchMethodException ignored) {
            return Optional.empty();
        }
    }

    private Optional<List<Double>> invokeEmbedForResponse(EmbeddingModel embeddingModel, String query) throws ReflectiveOperationException {
        Method method = embeddingModel.getClass().getMethod("embedForResponse", List.class);
        Object response = method.invoke(embeddingModel, List.of(query));
        return toVector(response);
    }

    private Optional<List<Double>> toVector(Object value) throws ReflectiveOperationException {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof float[] floats) {
            List<Double> result = new ArrayList<>(floats.length);
            for (float item : floats) {
                result.add((double) item);
            }
            return Optional.of(result);
        }
        if (value instanceof double[] doubles) {
            List<Double> result = new ArrayList<>(doubles.length);
            for (double item : doubles) {
                result.add(item);
            }
            return Optional.of(result);
        }
        if (value instanceof List<?> rows) {
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            if (rows.stream().allMatch(Number.class::isInstance)) {
                return Optional.of(rows.stream().map(Number.class::cast).map(Number::doubleValue).toList());
            }
            return toVector(rows.getFirst());
        }
        Optional<List<Double>> output = invokeNoArgVectorAccessor(value, "getOutput");
        if (output.isPresent()) {
            return output;
        }
        output = invokeNoArgVectorAccessor(value, "getEmbedding");
        if (output.isPresent()) {
            return output;
        }
        output = invokeNoArgVectorAccessor(value, "getResult");
        if (output.isPresent()) {
            return output;
        }
        output = invokeNoArgVectorAccessor(value, "getResults");
        return output;
    }

    private Optional<List<Double>> invokeNoArgVectorAccessor(Object value, String methodName) throws ReflectiveOperationException {
        try {
            Method method = value.getClass().getMethod(methodName);
            return toVector(method.invoke(value));
        } catch (NoSuchMethodException ignored) {
            return Optional.empty();
        }
    }
}
