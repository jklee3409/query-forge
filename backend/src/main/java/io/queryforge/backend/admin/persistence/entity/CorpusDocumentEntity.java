package io.queryforge.backend.admin.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "corpus_documents")
public class CorpusDocumentEntity extends BaseAuditableEntity {

    @Id
    @Column(name = "document_id", nullable = false)
    private String documentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false)
    private CorpusSourceEntity source;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "version_label")
    private String versionLabel;

    @Column(name = "canonical_url", nullable = false)
    private String canonicalUrl;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "section_path_text")
    private String sectionPathText;

    @Column(name = "heading_hierarchy_json", nullable = false, columnDefinition = "jsonb")
    private String headingHierarchyJson;

    @Column(name = "raw_checksum", nullable = false)
    private String rawChecksum;

    @Column(name = "cleaned_checksum", nullable = false)
    private String cleanedChecksum;

    @Column(name = "raw_text", nullable = false)
    private String rawText;

    @Column(name = "cleaned_text", nullable = false)
    private String cleanedText;

    @Column(name = "language_code", nullable = false)
    private String languageCode;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    @Column(name = "normalized_at", nullable = false)
    private Instant normalizedAt;

    @Column(name = "is_active", nullable = false)
    private boolean active;
}
