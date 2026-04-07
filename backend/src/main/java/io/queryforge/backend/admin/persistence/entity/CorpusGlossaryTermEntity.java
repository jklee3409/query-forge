package io.queryforge.backend.admin.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "corpus_glossary_terms")
public class CorpusGlossaryTermEntity extends BaseAuditableEntity {

    @Id
    @Column(name = "term_id", nullable = false)
    private UUID termId;

    @Column(name = "canonical_form", nullable = false)
    private String canonicalForm;

    @Column(name = "normalized_form", nullable = false)
    private String normalizedForm;

    @Column(name = "term_type", nullable = false)
    private String termType;

    @Column(name = "keep_in_english", nullable = false)
    private boolean keepInEnglish;

    @Column(name = "description_short")
    private String descriptionShort;

    @Column(name = "source_confidence", nullable = false)
    private double sourceConfidence;

    @Column(name = "first_seen_document_id")
    private String firstSeenDocumentId;

    @Column(name = "first_seen_chunk_id")
    private String firstSeenChunkId;

    @Column(name = "evidence_count", nullable = false)
    private int evidenceCount;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "metadata_json", nullable = false, columnDefinition = "jsonb")
    private String metadataJson;
}
