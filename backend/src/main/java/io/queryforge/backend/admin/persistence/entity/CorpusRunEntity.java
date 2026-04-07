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

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "corpus_runs")
public class CorpusRunEntity extends BaseAuditableEntity {

    @Id
    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "run_type", nullable = false)
    private String runType;

    @Column(name = "run_status", nullable = false)
    private String runStatus;

    @Column(name = "trigger_type", nullable = false)
    private String triggerType;

    @Column(name = "source_scope", nullable = false, columnDefinition = "jsonb")
    private String sourceScope;

    @Column(name = "config_snapshot", nullable = false, columnDefinition = "jsonb")
    private String configSnapshot;

    @Column(name = "summary_json", nullable = false, columnDefinition = "jsonb")
    private String summaryJson;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "cancel_requested_at")
    private Instant cancelRequestedAt;
}
