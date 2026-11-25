package io.queryforge.backend.rag.persistence.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

public final class RagJdbc {

    private RagJdbc() {
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
            ObjectNode fallback = objectMapper.createObjectNode();
            fallback.put("raw", raw);
            return fallback;
        }
    }
}
