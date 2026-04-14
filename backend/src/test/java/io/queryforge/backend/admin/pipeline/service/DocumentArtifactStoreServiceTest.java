package io.queryforge.backend.admin.pipeline.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.queryforge.backend.admin.pipeline.config.AdminPipelineProperties;
import io.queryforge.backend.admin.pipeline.repository.PipelineAdminRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DocumentArtifactStoreServiceTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void storesArtifactsPerDocumentAndSkipsDuplicateRawWrites() throws Exception {
        Path repoRoot = createRepoFixture();
        DocumentArtifactStoreService service = createService(repoRoot);
        UUID runId = UUID.randomUUID();

        Path rawWorkspace = repoRoot.resolve("tmp/raw.jsonl");
        writeJsonl(rawWorkspace, List.of(
                """
                {"document_id":"doc_alpha","source_id":"spring-framework-reference","canonical_url":"https://docs.spring.io/a","versioned_url":"https://docs.spring.io/6.1/a","product":"spring-framework","version_if_available":"6.1","title":"Alpha","content_hash":"hash-alpha"}
                """,
                """
                {"document_id":"doc_beta","source_id":"spring-framework-reference","canonical_url":"https://docs.spring.io/b","versioned_url":"https://docs.spring.io/6.1/b","product":"spring-framework","version_if_available":"6.1","title":"Beta","content_hash":"hash-beta"}
                """
        ));

        DocumentArtifactStoreService.PersistResult rawPersist = service.persistRawArtifacts(rawWorkspace, runId);
        assertThat(rawPersist.persistedDocumentCount()).isEqualTo(2);
        assertThat(service.resolveDocumentIdsBySource(List.of("spring-framework-reference")))
                .containsExactly("doc_alpha", "doc_beta");
        assertThat(service.selectDocumentsForNormalize(List.of("doc_alpha", "doc_beta")))
                .containsExactly("doc_alpha", "doc_beta");

        DocumentArtifactStoreService.PersistResult duplicatePersist = service.persistRawArtifacts(rawWorkspace, UUID.randomUUID());
        assertThat(duplicatePersist.persistedDocumentCount()).isZero();
        assertThat(Files.readString(rawWorkspace, StandardCharsets.UTF_8)).isBlank();

        Path sectionsWorkspace = repoRoot.resolve("tmp/sections.jsonl");
        writeJsonl(sectionsWorkspace, List.of(
                """
                {"document_id":"doc_alpha","section_id":"sec_alpha","product":"spring-framework","document_title":"Alpha","section_title":"Alpha","section_path":"Alpha","heading_hierarchy":["Alpha"],"heading_level":1,"cleaned_text":"Alpha cleaned"}
                """,
                """
                {"document_id":"doc_beta","section_id":"sec_beta","product":"spring-framework","document_title":"Beta","section_title":"Beta","section_path":"Beta","heading_hierarchy":["Beta"],"heading_level":1,"cleaned_text":"Beta cleaned"}
                """
        ));
        service.persistNormalizedArtifacts(sectionsWorkspace, runId);
        assertThat(service.selectDocumentsForNormalize(List.of("doc_alpha", "doc_beta"))).isEmpty();
        assertThat(service.selectDocumentsForChunk(List.of("doc_alpha", "doc_beta")))
                .containsExactly("doc_alpha", "doc_beta");

        Path chunksWorkspace = repoRoot.resolve("tmp/chunks.jsonl");
        writeJsonl(chunksWorkspace, List.of(
                """
                {"chunk_id":"chk_alpha","document_id":"doc_alpha","section_id":"sec_alpha","chunk_index_in_doc":0,"content":"Alpha chunk"}
                """,
                """
                {"chunk_id":"chk_beta","document_id":"doc_beta","section_id":"sec_beta","chunk_index_in_doc":0,"content":"Beta chunk"}
                """
        ));
        Path glossaryWorkspace = repoRoot.resolve("tmp/glossary.jsonl");
        writeJsonl(glossaryWorkspace, List.of(
                """
                {"term_type":"annotation","canonical_form":"@Bean","aliases":["Bean"],"keep_in_english":true,"source_product":"spring-framework","metadata":{"document_ids":["doc_alpha","doc_beta"],"source_products":["spring-framework"],"evidence_count":2}}
                """
        ));

        service.persistChunkArtifacts(chunksWorkspace, glossaryWorkspace, runId);
        assertThat(service.selectDocumentsForChunk(List.of("doc_alpha", "doc_beta"))).isEmpty();
        assertThat(service.selectDocumentsForGlossary(List.of("doc_alpha", "doc_beta"))).isEmpty();

        Path aggregateGlossary = repoRoot.resolve("tmp/glossary_aggregate.jsonl");
        service.materializeGlossaryArtifacts(List.of("doc_alpha", "doc_beta"), aggregateGlossary);
        List<String> glossaryLines = Files.readAllLines(aggregateGlossary, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .toList();
        assertThat(glossaryLines).hasSize(1);
        JsonNode aggregated = objectMapper.readTree(glossaryLines.getFirst());
        assertThat(aggregated.path("metadata").path("document_ids")).hasSize(2);
        assertThat(aggregated.path("metadata").path("evidence_count").asInt()).isEqualTo(2);
    }

    private DocumentArtifactStoreService createService(Path repoRoot) {
        AdminPipelineProperties properties = new AdminPipelineProperties(
                repoRoot.toString(),
                "python",
                "data/logs/admin-pipeline",
                "data/artifacts/corpus-docs",
                "configs/app/sources",
                "configs/app/chunking.yml",
                "data/raw/spring_docs_raw.jsonl",
                "data/processed/spring_docs_sections.jsonl",
                "data/processed/chunks.jsonl",
                "data/processed/glossary_terms.jsonl",
                "data/processed/chunk_neighbors.sql",
                "data/processed/chunking_visualization.md",
                12000,
                14400
        );
        SourceCatalogService sourceCatalogService = new SourceCatalogService(properties, mock(PipelineAdminRepository.class));
        return new DocumentArtifactStoreService(properties, sourceCatalogService, objectMapper);
    }

    private Path createRepoFixture() throws IOException {
        Path repoRoot = Files.createDirectories(tempDir.resolve("query-forge"));
        Files.createDirectories(repoRoot.resolve("pipeline"));
        Files.writeString(repoRoot.resolve("pipeline/cli.py"), "print('ok')\n");
        Files.createDirectories(repoRoot.resolve("backend"));
        Files.writeString(repoRoot.resolve("backend/settings.gradle"), "rootProject.name = 'query-forge-backend'\n");
        return repoRoot.toAbsolutePath().normalize();
    }

    private void writeJsonl(Path path, List<String> lines) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, String.join(System.lineSeparator(), lines) + System.lineSeparator(), StandardCharsets.UTF_8);
    }
}
