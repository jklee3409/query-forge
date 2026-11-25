package io.queryforge.backend.admin.console.scope;

import io.queryforge.backend.admin.console.synthetic.AdminSyntheticMethodRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.queryforge.backend.admin.console.configuration.ConsoleParameters.blankToNull;
import static io.queryforge.backend.admin.console.configuration.ConsoleParameters.isEnglishOnlyMethod;
import static io.queryforge.backend.admin.console.configuration.ConsoleParameters.normalizeMethodCode;

@Service
@RequiredArgsConstructor
public class ConsoleScopeService {

    private static final String QUERY_LANGUAGE_KO = "ko";

    private static final String QUERY_LANGUAGE_EN = "en";

    private static final Set<String> ALL_METHOD_CODES = Set.of("A", "B", "C", "D", "E", "F", "G");

    private static final Set<String> SPRING_TECHDOC_METHOD_CODES = Set.of("A", "B", "C", "D", "E");

    private static final Set<String> PYTHON_KR_METHOD_CODES = Set.of("F", "G");

    private static final Set<String> DISALLOWED_SYNTHETIC_SOURCE_IDS = Set.of("arahansa-github-io-docs-spring");

    private static final List<String> SPRING_TECHDOC_SOURCE_IDS = List.of(
            "spring-boot-reference",
            "spring-data-commons-reference",
            "spring-data-jpa-reference",
            "spring-framework-reference",
            "spring-security-reference"
    );

    private static final List<String> PYTHON_KR_SOURCE_IDS = List.of("docs-python-org-ko-3-14");

    private static final String SCOPE_LABEL_SPRING_TECHDOC = "spring_techdoc";

    private static final String SCOPE_LABEL_PYTHON_KR = "python_kr";

    private final AdminSyntheticMethodRepository syntheticMethodRepository;

    private final AdminConsoleDomainScopeRepository scopeRepository;

    private enum StrategyScope {
        SPRING_TECHDOC,
        PYTHON_KR,
        UNKNOWN
    }

    private record SourceResolution(
            String sourceId,
            AdminConsoleDomainScopeRepository.SourceStrategyContext context,
            StrategyScope scope
    ) {
    }

    private record DatasetResolution(
            AdminConsoleDomainScopeRepository.DatasetStrategyContext context,
            StrategyScope scope
    ) {
    }

    public Set<String> resolveAllowedMethodCodesForMethodList(
            String sourceId,
            String sourceDocumentId,
            UUID datasetId,
            UUID domainId
    ) {
        Set<String> domainAllowedMethodCodes = allowedMethodCodesForDomainLanguage(domainId);
        if (datasetId != null) {
            DatasetResolution datasetResolution = resolveDatasetScope(datasetId, false);
            return intersectMethodCodes(allowedMethodCodesForScope(datasetResolution.scope()), domainAllowedMethodCodes);
        }
        SourceResolution sourceResolution = resolveSourceScope(sourceId, sourceDocumentId, false);
        if (sourceResolution == null) {
            return domainAllowedMethodCodes;
        }
        if (domainId == null && !isAllowedSyntheticSourceForScope(sourceResolution.sourceId(), sourceResolution.scope())) {
            return Set.of();
        }
        return intersectMethodCodes(allowedMethodCodesForScope(sourceResolution.scope()), domainAllowedMethodCodes);
    }

    public void validateSyntheticSourceMethodRestriction(
            UUID domainId,
            String methodCode,
            String sourceId,
            String sourceDocumentId
    ) {
        SourceResolution sourceResolution = resolveSourceScope(sourceId, sourceDocumentId, false);
        if (sourceResolution == null) {
            if (domainId == null && allowedSourceIdsForMethod(methodCode).isEmpty()) {
                throw new IllegalArgumentException("source_id or source_document_id is required for method_code " + methodCode);
            }
            return;
        }
        validateSyntheticSourceAllowlist(domainId, methodCode, sourceResolution);
        Set<String> allowedMethods = allowedMethodCodesForScope(sourceResolution.scope());
        if (!allowedMethods.contains(methodCode)) {
            throw new IllegalArgumentException(
                    "method_code " + methodCode
                            + " is not allowed for source scope "
                            + scopeLabel(sourceResolution.scope())
                            + " (source_id=" + sourceResolution.sourceId()
                            + ", allowed=" + allowedMethods + ")"
            );
        }
    }

    public void validateDatasetMethodRestriction(UUID domainId, UUID datasetId, List<String> methodCodes) {
        DatasetResolution datasetResolution = resolveDatasetScope(datasetId, domainId == null);
        Set<String> allowedMethods = allowedMethodCodesForScope(datasetResolution.scope());
        if (datasetResolution.scope() == StrategyScope.UNKNOWN && domainId != null) {
            allowedMethods = syntheticMethodRepository.findGenerationMethods(domainId).stream()
                    .map(method -> normalizeMethodCode(method.methodCode()))
                    .collect(LinkedHashSet::new, Set::add, Set::addAll);
            if (allowedMethods.isEmpty()) {
                throw new IllegalArgumentException(
                        "domain has no enabled generation methods for dataset "
                                + datasetResolution.context().datasetKey()
                                + " (dataset_id=" + datasetId
                                + ", domain_id=" + domainId + ")"
                );
            }
        }
        for (String methodCode : methodCodes) {
            if (!allowedMethods.contains(methodCode)) {
                throw new IllegalArgumentException(
                        "method_code " + methodCode
                                + " is not allowed for dataset "
                                + datasetResolution.context().datasetKey()
                                + " (dataset_id=" + datasetId
                                + ", scope=" + scopeLabel(datasetResolution.scope())
                                + ", allowed=" + allowedMethods + ")"
                );
            }
        }
    }

    public void validateRagMethodLanguageCompatibility(String evalQueryLanguage, List<String> methodCodes) {
        if (methodCodes == null || methodCodes.isEmpty()) {
            return;
        }
        boolean englishEval = QUERY_LANGUAGE_EN.equalsIgnoreCase(evalQueryLanguage);
        Set<String> allowedMethodsForLanguage = englishEval
                ? Set.of("E", "F")
                : Set.of("A", "B", "C", "D", "G");
        for (String methodCode : methodCodes) {
            String normalized = normalizeMethodCode(methodCode);
            boolean englishMethod = isEnglishOnlyMethod(normalized);
            if (englishEval == englishMethod) {
                continue;
            }
            throw new IllegalArgumentException(
                    "method_code " + normalized
                            + " is not allowed for eval_query_language=" + evalQueryLanguage
                            + " (allowed=" + allowedMethodsForLanguage + ")"
            );
        }
    }

    private SourceResolution resolveSourceScope(String sourceId, String sourceDocumentId, boolean strict) {
        String normalizedSourceId = blankToNull(sourceId);
        String normalizedSourceDocumentId = blankToNull(sourceDocumentId);
        if (normalizedSourceId == null && normalizedSourceDocumentId == null) {
            if (strict) {
                throw new IllegalArgumentException("source_id or source_document_id is required");
            }
            return null;
        }

        String sourceIdFromDocument = null;
        if (normalizedSourceDocumentId != null) {
            sourceIdFromDocument = scopeRepository.findSourceIdByDocumentId(normalizedSourceDocumentId)
                    .orElseThrow(() -> new IllegalArgumentException("source document not found: " + normalizedSourceDocumentId));
        }

        if (normalizedSourceId != null
                && sourceIdFromDocument != null
                && !normalizedSourceId.equalsIgnoreCase(sourceIdFromDocument)) {
            throw new IllegalArgumentException(
                    "source_id and source_document_id mismatch: source_id=" + normalizedSourceId
                            + ", source_document_id=" + normalizedSourceDocumentId
                            + ", resolved_source_id=" + sourceIdFromDocument
            );
        }

        String effectiveSourceId = normalizedSourceId != null ? normalizedSourceId : sourceIdFromDocument;
        if (effectiveSourceId == null) {
            if (strict) {
                throw new IllegalArgumentException("unable to resolve source scope");
            }
            return null;
        }

        AdminConsoleDomainScopeRepository.SourceStrategyContext sourceContext = scopeRepository.findSourceStrategyContext(effectiveSourceId)
                .orElse(null);
        StrategyScope scope = inferSourceScope(effectiveSourceId, sourceContext);
        if (strict && scope == StrategyScope.UNKNOWN) {
            throw new IllegalArgumentException(
                    "unable to determine source strategy scope: source_id=" + effectiveSourceId
                            + " (expected spring-source or python-ko-source)"
            );
        }
        return new SourceResolution(effectiveSourceId, sourceContext, scope);
    }

    private DatasetResolution resolveDatasetScope(UUID datasetId, boolean strict) {
        AdminConsoleDomainScopeRepository.DatasetStrategyContext context = scopeRepository.findDatasetStrategyContext(datasetId)
                .orElseThrow(() -> new IllegalArgumentException("dataset not found: " + datasetId));
        StrategyScope scope = inferDatasetScope(context);
        if (strict && scope == StrategyScope.UNKNOWN) {
            throw new IllegalArgumentException(
                    "unable to determine dataset strategy scope: dataset_id=" + datasetId
                            + ", dataset_key=" + context.datasetKey()
                            + " (expected spring-techdoc or python-kr dataset)"
            );
        }
        return new DatasetResolution(context, scope);
    }

    private StrategyScope inferSourceScope(String sourceId, AdminConsoleDomainScopeRepository.SourceStrategyContext context) {
        String normalizedSourceId = normalizeHint(sourceId);
        String normalizedProductName = normalizeHint(context == null ? null : context.productName());
        String normalizedSourceName = normalizeHint(context == null ? null : context.sourceName());
        String normalizedSourceType = normalizeHint(context == null ? null : context.sourceType());

        boolean springHint = containsSpringHint(normalizedSourceId)
                || containsSpringHint(normalizedProductName)
                || containsSpringHint(normalizedSourceName);
        boolean pythonHint = containsPythonHint(normalizedSourceId)
                || containsPythonHint(normalizedProductName)
                || containsPythonHint(normalizedSourceName)
                || containsPythonHint(normalizedSourceType);
        boolean koreanHint = containsKoreanHint(normalizedSourceId)
                || containsKoreanHint(normalizedProductName)
                || containsKoreanHint(normalizedSourceName)
                || (context != null && context.hasKoDocuments());
        boolean englishHint = context != null && context.hasEnDocuments();

        if (springHint && !pythonHint) {
            return StrategyScope.SPRING_TECHDOC;
        }
        if (pythonHint && !springHint && (koreanHint || !englishHint)) {
            return StrategyScope.PYTHON_KR;
        }
        return StrategyScope.UNKNOWN;
    }

    private StrategyScope inferDatasetScope(AdminConsoleDomainScopeRepository.DatasetStrategyContext context) {
        String metadataProfile = normalizeHint(context.metadataStrategyProfile());
        if (SCOPE_LABEL_SPRING_TECHDOC.equals(metadataProfile)) {
            return StrategyScope.SPRING_TECHDOC;
        }
        if (SCOPE_LABEL_PYTHON_KR.equals(metadataProfile)) {
            return StrategyScope.PYTHON_KR;
        }

        String datasetKey = normalizeHint(context.datasetKey());
        String metadataLanguage = normalizeHint(context.metadataQueryLanguage());
        boolean springHint = context.hasSpringSamples() || containsSpringHint(datasetKey);
        boolean pythonHint = context.hasPythonSamples() || containsPythonHint(datasetKey);
        boolean koreanHint = context.hasKoQueries()
                || QUERY_LANGUAGE_KO.equals(metadataLanguage)
                || containsKoreanHint(datasetKey);

        if (pythonHint && !springHint && koreanHint) {
            return StrategyScope.PYTHON_KR;
        }
        if (springHint && !pythonHint) {
            return StrategyScope.SPRING_TECHDOC;
        }
        return StrategyScope.UNKNOWN;
    }

    private Set<String> allowedMethodCodesForScope(StrategyScope scope) {
        return switch (scope) {
            case SPRING_TECHDOC -> SPRING_TECHDOC_METHOD_CODES;
            case PYTHON_KR -> PYTHON_KR_METHOD_CODES;
            case UNKNOWN -> ALL_METHOD_CODES;
        };
    }

    private Set<String> allowedMethodCodesForDomainLanguage(UUID domainId) {
        if (domainId == null) {
            return ALL_METHOD_CODES;
        }
        Optional<AdminConsoleDomainScopeRepository.DomainLanguageContext> languageContext =
                scopeRepository.findDomainLanguageContext(domainId);
        if (languageContext.isPresent()) {
            AdminConsoleDomainScopeRepository.DomainLanguageContext context = languageContext.get();
            if (isEnglishLanguage(context.sourceLanguage())) {
                return SPRING_TECHDOC_METHOD_CODES;
            }
            if (isKoreanLanguage(context.sourceLanguage())) {
                return PYTHON_KR_METHOD_CODES;
            }
            if (isEnglishLanguage(context.primaryLanguage())) {
                return SPRING_TECHDOC_METHOD_CODES;
            }
            if (isKoreanLanguage(context.primaryLanguage())) {
                return PYTHON_KR_METHOD_CODES;
            }
        }
        Set<String> policyMethodCodes = syntheticMethodRepository.findGenerationMethods(domainId).stream()
                .map(method -> normalizeMethodCode(method.methodCode()))
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        return policyMethodCodes.isEmpty() ? ALL_METHOD_CODES : policyMethodCodes;
    }

    private Set<String> intersectMethodCodes(Set<String> left, Set<String> right) {
        if (left == null || left.isEmpty() || right == null || right.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String methodCode : left) {
            if (right.contains(methodCode)) {
                result.add(methodCode);
            }
        }
        return result;
    }

    private void validateSyntheticSourceAllowlist(UUID domainId, String methodCode, SourceResolution sourceResolution) {
        if (isDisallowedSyntheticSource(sourceResolution.sourceId())) {
            throw new IllegalArgumentException("source_id is not allowed for synthetic generation: " + sourceResolution.sourceId());
        }
        if (domainId != null) {
            return;
        }
        List<String> allowedSourceIds = allowedSourceIdsForMethod(methodCode);
        if (!allowedSourceIds.isEmpty() && !containsSourceId(allowedSourceIds, sourceResolution.sourceId())) {
            throw new IllegalArgumentException(
                    "source_id " + sourceResolution.sourceId()
                            + " is not allowed for method_code " + methodCode
                            + " (allowed=" + allowedSourceIds + ")"
            );
        }
    }

    private List<String> allowedSourceIdsForMethod(String methodCode) {
        if (SPRING_TECHDOC_METHOD_CODES.contains(methodCode)) {
            return SPRING_TECHDOC_SOURCE_IDS;
        }
        if (PYTHON_KR_METHOD_CODES.contains(methodCode)) {
            return PYTHON_KR_SOURCE_IDS;
        }
        return List.of();
    }

    public List<String> allowedSourceIdsForRun(String methodCode, UUID domainId) {
        List<String> methodAllowedSourceIds = allowedSourceIdsForMethod(methodCode);
        if (domainId == null) {
            return methodAllowedSourceIds;
        }
        List<String> domainSourceIds = scopeRepository.findDomainSourceIds(domainId).stream()
                .filter(sourceId -> !isDisallowedSyntheticSource(sourceId))
                .toList();
        if (allowedMethodCodesForDomainLanguage(domainId).contains(methodCode)) {
            return domainSourceIds;
        }
        if (methodAllowedSourceIds.isEmpty()) {
            return domainSourceIds;
        }
        List<String> scopedSourceIds = domainSourceIds.stream()
                .filter(sourceId -> containsSourceId(methodAllowedSourceIds, sourceId))
                .toList();
        if (scopedSourceIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "domain has no source allowed for method_code " + methodCode + " (domain_id=" + domainId + ")"
            );
        }
        return scopedSourceIds;
    }

    public void validateSyntheticDomainScope(
            UUID domainId,
            String methodCode,
            String sourceId,
            String sourceDocumentId
    ) {
        if (domainId == null) {
            return;
        }
        Set<String> languageAllowedMethodCodes = allowedMethodCodesForDomainLanguage(domainId);
        if (!languageAllowedMethodCodes.contains(methodCode)) {
            throw new IllegalArgumentException(
                    "method_code " + methodCode
                            + " is not allowed for domain source language"
                            + " (domain_id=" + domainId
                            + ", allowed=" + languageAllowedMethodCodes + ")"
            );
        }
        String normalizedSourceId = blankToNull(sourceId);
        if (normalizedSourceId != null && !scopeRepository.sourceBelongsToDomain(domainId, normalizedSourceId)) {
            throw new IllegalArgumentException(
                    "source_id " + normalizedSourceId + " does not belong to domain_id=" + domainId
            );
        }
        String normalizedDocumentId = blankToNull(sourceDocumentId);
        if (normalizedDocumentId != null && !scopeRepository.sourceDocumentBelongsToDomain(domainId, normalizedDocumentId)) {
            throw new IllegalArgumentException(
                    "source_document_id " + normalizedDocumentId + " does not belong to domain_id=" + domainId
            );
        }
    }

    public void validateGenerationBatchDomain(UUID domainId, UUID batchId) {
        if (!scopeRepository.generationBatchBelongsToDomain(domainId, batchId)) {
            throw new IllegalArgumentException(
                    "generation_batch_id " + batchId + " does not belong to domain_id=" + domainId
            );
        }
    }

    public void validateGenerationBatchDomains(UUID domainId, Collection<UUID> batchIds) {
        if (batchIds == null) {
            return;
        }
        for (UUID batchId : batchIds) {
            validateGenerationBatchDomain(domainId, batchId);
        }
    }

    public void validateGatingBatchDomains(UUID domainId, UUID batchId) {
        if (!scopeRepository.gatingBatchBelongsToDomain(domainId, batchId)) {
            throw new IllegalArgumentException(
                    "gating_batch_id " + batchId + " does not belong to domain_id=" + domainId
            );
        }
    }

    public void validateGatingBatchDomains(UUID domainId, Collection<UUID> batchIds) {
        if (batchIds == null) {
            return;
        }
        for (UUID batchId : batchIds) {
            validateGatingBatchDomains(domainId, batchId);
        }
    }

    public void validateEvalDatasetDomain(UUID domainId, UUID datasetId) {
        if (!scopeRepository.evalDatasetBelongsToDomain(domainId, datasetId)) {
            throw new IllegalArgumentException(
                    "dataset_id " + datasetId + " does not belong to domain_id=" + domainId
            );
        }
    }

    private boolean isAllowedSyntheticSourceForScope(String sourceId, StrategyScope scope) {
        if (isDisallowedSyntheticSource(sourceId)) {
            return false;
        }
        List<String> allowedSourceIds = switch (scope) {
            case SPRING_TECHDOC -> SPRING_TECHDOC_SOURCE_IDS;
            case PYTHON_KR -> PYTHON_KR_SOURCE_IDS;
            case UNKNOWN -> List.of();
        };
        return allowedSourceIds.isEmpty() || containsSourceId(allowedSourceIds, sourceId);
    }

    private boolean isDisallowedSyntheticSource(String sourceId) {
        return containsSourceId(DISALLOWED_SYNTHETIC_SOURCE_IDS, sourceId);
    }

    private boolean containsSourceId(Collection<String> sourceIds, String sourceId) {
        if (sourceId == null || sourceId.isBlank()) {
            return false;
        }
        return sourceIds.contains(sourceId.trim().toLowerCase(Locale.ROOT));
    }

    private String scopeLabel(StrategyScope scope) {
        return switch (scope) {
            case SPRING_TECHDOC -> SCOPE_LABEL_SPRING_TECHDOC;
            case PYTHON_KR -> SCOPE_LABEL_PYTHON_KR;
            case UNKNOWN -> "unknown";
        };
    }

    private boolean containsSpringHint(String normalizedText) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return false;
        }
        return normalizedText.contains("spring")
                || normalizedText.contains("docs_spring");
    }

    private boolean containsPythonHint(String normalizedText) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return false;
        }
        return normalizedText.contains("python");
    }

    private boolean containsKoreanHint(String normalizedText) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return false;
        }
        return normalizedText.contains("ko")
                || normalizedText.contains("kr")
                || normalizedText.contains("korean");
    }

    private boolean isEnglishLanguage(String value) {
        String normalized = normalizeHint(value);
        return "en".equals(normalized) || "eng".equals(normalized) || "english".equals(normalized);
    }

    private boolean isKoreanLanguage(String value) {
        String normalized = normalizeHint(value);
        return "ko".equals(normalized) || "kr".equals(normalized) || "kor".equals(normalized) || "korean".equals(normalized);
    }

    private String normalizeHint(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
