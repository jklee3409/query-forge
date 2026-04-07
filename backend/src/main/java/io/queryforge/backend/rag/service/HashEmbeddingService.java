package io.queryforge.backend.rag.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class HashEmbeddingService {

    public static final int DIMENSION = 3072;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_./-]+|[가-힣]+");

    public List<Double> embed(String text) {
        List<Double> vector = new ArrayList<>(DIMENSION);
        for (int index = 0; index < DIMENSION; index++) {
            vector.add(0.0);
        }
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) {
            return vector;
        }
        for (String token : tokens) {
            long hash = stableHash(token);
            int index = (int) Math.floorMod(hash, DIMENSION);
            double signed = (hash % 2 == 0) ? 1.0 : -1.0;
            vector.set(index, vector.get(index) + signed);
        }
        double norm = 0.0;
        for (double value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        if (norm <= 0.0) {
            return vector;
        }
        for (int index = 0; index < vector.size(); index++) {
            vector.set(index, vector.get(index) / norm);
        }
        return vector;
    }

    public String toHalfvecLiteral(List<Double> vector) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < vector.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(String.format(Locale.ROOT, "%.6f", vector.get(index)));
        }
        builder.append(']');
        return builder.toString();
    }

    public double cosine(List<Double> left, List<Double> right) {
        if (left.isEmpty() || right.isEmpty() || left.size() != right.size()) {
            return 0.0;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int index = 0; index < left.size(); index++) {
            double leftValue = left.get(index);
            double rightValue = right.get(index);
            dot += leftValue * rightValue;
            leftNorm += leftValue * leftValue;
            rightNorm += rightValue * rightValue;
        }
        if (leftNorm <= 0.0 || rightNorm <= 0.0) {
            return 0.0;
        }
        return dot / Math.sqrt(leftNorm * rightNorm);
    }

    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return tokens;
    }

    private long stableHash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            long value = 0L;
            for (int index = 0; index < 8; index++) {
                value = (value << 8) | (bytes[index] & 0xFFL);
            }
            return value;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to hash token for embedding.", exception);
        }
    }
}

