package io.queryforge.backend.admin.console;

import io.queryforge.backend.admin.console.model.LlmJobType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LlmJobTypeConstraintSyncTest {

    private static final Pattern JOB_TYPE_VALUE_PATTERN = Pattern.compile("'([A-Z_]+)'");

    @Test
    void migrationConstraintMatchesJavaJobTypes() throws IOException {
        String migrationSql = readMigration("db/migration/V30__allow_materialize_chunk_embeddings_llm_job_type.sql");
        Set<String> dbValues = extractQuotedValues(migrationSql);
        Set<String> javaValues = new LinkedHashSet<>(LlmJobType.dbValues());

        assertEquals(javaValues, dbValues);
    }

    private String readMigration(String resourcePath) throws IOException {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(inputStream, "missing migration resource: " + resourcePath);
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Set<String> extractQuotedValues(String sql) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = JOB_TYPE_VALUE_PATTERN.matcher(sql);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }
}
