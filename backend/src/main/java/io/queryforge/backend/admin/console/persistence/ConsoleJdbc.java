package io.queryforge.backend.admin.console.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class ConsoleJdbc {

    private ConsoleJdbc() {
    }

    public static int normalizeLimit(Integer value, int defaultLimit) {
        if (value == null || value <= 0) {
            return defaultLimit;
        }
        return Math.min(value, 1000);
    }

    public static int normalizeOffset(Integer value) {
        if (value == null || value < 0) {
            return 0;
        }
        return value;
    }

    public static UUID readUuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(String.valueOf(value));
    }

    public static Instant readInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp != null ? timestamp.toInstant() : null;
    }

    public static JsonNode readJson(ObjectMapper objectMapper, ResultSet rs, String column) throws SQLException {
        return readJson(objectMapper, rs.getString(column));
    }

    public static JsonNode readJson(ObjectMapper objectMapper, String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception exception) {
            return objectMapper.valueToTree(Map.of("raw", raw));
        }
    }
}
