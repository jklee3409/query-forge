package io.queryforge.backend.rag.eval.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EvalReportService {

    private final ObjectMapper objectMapper;

    public EvalReportDtos.EvalReportResponse readEvalReport(String reportType) {
        String normalized = reportType == null ? "retrieval" : reportType.toLowerCase(Locale.ROOT);
        List<Path> reportRoots = List.of(
                Path.of("data/reports"),
                Path.of("../data/reports"),
                Path.of("../../data/reports")
        );
        try {
            String prefix = normalized.startsWith("answer") ? "answer_summary_" : "retrieval_summary_";
            Optional<Path> latest = Optional.empty();
            for (Path reportDir : reportRoots) {
                if (!Files.exists(reportDir)) {
                    continue;
                }
                Optional<Path> candidate;
                try (Stream<Path> paths = Files.list(reportDir)) {
                    candidate = paths
                            .filter(path -> path.getFileName().toString().startsWith(prefix))
                            .filter(path -> path.getFileName().toString().endsWith(".json"))
                            .max(Comparator.comparingLong(path -> path.toFile().lastModified()));
                }
                if (candidate.isPresent()) {
                    if (latest.isEmpty() || candidate.get().toFile().lastModified() > latest.get().toFile().lastModified()) {
                        latest = candidate;
                    }
                }
            }
            if (latest.isEmpty()) {
                return new EvalReportDtos.EvalReportResponse(normalized, objectMapper.createObjectNode());
            }
            JsonNode payload = objectMapper.readTree(Files.readString(latest.get()));
            return new EvalReportDtos.EvalReportResponse(normalized, payload);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read eval report.", exception);
        }
    }
}
