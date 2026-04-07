package io.queryforge.backend.admin.pipeline.service;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

final class ProjectPathResolver {

    private static final List<String> REPO_MARKERS = List.of(
            "pipeline/cli.py",
            "backend/settings.gradle"
    );

    private ProjectPathResolver() {
    }

    static Path resolveRepoRoot(String configuredRepoRoot) {
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        addCandidate(candidates, configuredRepoRoot);
        candidates.add(Path.of("").toAbsolutePath().normalize());
        codeSourceDirectory().ifPresent(candidates::add);

        for (Path candidate : candidates) {
            Optional<Path> resolved = findRepoRoot(candidate);
            if (resolved.isPresent()) {
                return resolved.get();
            }
        }

        throw new IllegalStateException(
                "Failed to locate the query-forge repository root. Set QUERY_FORGE_REPO_ROOT to the project root."
        );
    }

    static Path resolveWithinRepo(Path repoRoot, String configuredPath, String label) {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank.");
        }

        Path candidate = Path.of(configuredPath);
        Path resolved = candidate.isAbsolute()
                ? candidate.toAbsolutePath().normalize()
                : repoRoot.resolve(candidate).normalize();

        if (!resolved.startsWith(repoRoot)) {
            throw new IllegalStateException(label + " must stay within repo root: " + repoRoot);
        }
        return resolved;
    }

    static Optional<Path> findRepoRoot(Path startingPoint) {
        if (startingPoint == null) {
            return Optional.empty();
        }

        Path current = startingPoint.toAbsolutePath().normalize();
        while (current != null) {
            if (isRepoRoot(current)) {
                return Optional.of(current);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    private static boolean isRepoRoot(Path candidate) {
        List<Path> markers = new ArrayList<>(REPO_MARKERS.size());
        for (String marker : REPO_MARKERS) {
            markers.add(candidate.resolve(marker));
        }
        return markers.stream().allMatch(Files::exists);
    }

    private static void addCandidate(LinkedHashSet<Path> candidates, String configuredRepoRoot) {
        if (configuredRepoRoot == null || configuredRepoRoot.isBlank()) {
            return;
        }
        candidates.add(Path.of(configuredRepoRoot).toAbsolutePath().normalize());
    }

    private static Optional<Path> codeSourceDirectory() {
        try {
            Path codeSource = Path.of(ProjectPathResolver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Optional.of(Files.isDirectory(codeSource) ? codeSource : codeSource.getParent());
        } catch (URISyntaxException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
