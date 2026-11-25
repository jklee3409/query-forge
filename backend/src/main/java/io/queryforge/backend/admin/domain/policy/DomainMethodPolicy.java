package io.queryforge.backend.admin.domain.policy;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record DomainMethodPolicy(
        String methodCode,
        String methodName,
        boolean methodActive,
        boolean enabled,
        String defaultQueryLanguage,
        JsonNode metadata,
        Instant createdAt
) {
}
