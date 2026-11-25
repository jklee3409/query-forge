package io.queryforge.backend.admin.console.evaluation.anchor.support;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.queryforge.backend.admin.corpus.anchor.support.AnchorSchemaVersions.MAPPING_VERSION;
import static io.queryforge.backend.admin.corpus.anchor.support.AnchorSchemaVersions.NORMALIZATION_VERSION;
import static io.queryforge.backend.admin.corpus.anchor.support.AnchorSchemaVersions.RUNTIME_SCHEMA_VERSION;

public final class CanonicalAnchorVersionMetadata {

    private CanonicalAnchorVersionMetadata() {
    }

    static Map<String, Object> defaults() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("anchor_mapping_version", MAPPING_VERSION);
        values.put("anchor_normalization_version", NORMALIZATION_VERSION);
        values.put("canonical_anchor_runtime_schema_version", RUNTIME_SCHEMA_VERSION);
        return values;
    }

    public static Map<String, Object> fromConfig(JsonNode config) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(
                "anchor_mapping_version",
                readText(config, "anchor_mapping_version", MAPPING_VERSION)
        );
        values.put(
                "anchor_normalization_version",
                readText(config, "anchor_normalization_version", NORMALIZATION_VERSION)
        );
        values.put(
                "canonical_anchor_runtime_schema_version",
                readText(config, "canonical_anchor_runtime_schema_version", RUNTIME_SCHEMA_VERSION)
        );
        return values;
    }

    public static void putDefaults(Map<String, Object> target) {
        target.putAll(defaults());
    }

    private static String readText(JsonNode config, String key, String fallback) {
        if (config == null || config.isMissingNode() || config.isNull()) {
            return fallback;
        }
        String value = config.path(key).asText("").trim();
        return value.isEmpty() ? fallback : value;
    }
}
