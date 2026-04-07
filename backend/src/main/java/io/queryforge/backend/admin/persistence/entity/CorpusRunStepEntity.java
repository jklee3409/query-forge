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
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "corpus_run_steps")
public class CorpusRunStepEntity extends BaseAuditableEntity {

    @Id
    @Column(name = "step_id", nullable = false)
    private UUID stepId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private CorpusRunEntity run;

    @Column(name = "step_name", nullable = false)
    private String stepName;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Column(name = "step_status", nullable = false)
    private String stepStatus;

    @Column(name = "input_artifact_path")
    private String inputArtifactPath;

    @Column(name = "output_artifact_path")
    private String outputArtifactPath;

    @Column(name = "command_line")
    private String commandLine;

    @Column(name = "metrics_json", nullable = false, columnDefinition = "jsonb")
    private String metricsJson;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "stdout_log_path")
    private String stdoutLogPath;

    @Column(name = "stderr_log_path")
    private String stderrLogPath;

    @Column(name = "stdout_excerpt")
    private String stdoutExcerpt;

    @Column(name = "stderr_excerpt")
    private String stderrExcerpt;
}
