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

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "corpus_sources")
public class CorpusSourceEntity extends BaseAuditableEntity {

    @Id
    @Column(name = "source_id", nullable = false)
    private String sourceId;

    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "source_name", nullable = false)
    private String sourceName;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    @Column(name = "include_patterns", nullable = false, columnDefinition = "jsonb")
    private String includePatterns;

    @Column(name = "exclude_patterns", nullable = false, columnDefinition = "jsonb")
    private String excludePatterns;

    @Column(name = "default_version")
    private String defaultVersion;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;
}
