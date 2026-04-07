package io.queryforge.backend.admin.ui.controller;

import io.queryforge.backend.admin.corpus.model.CorpusAdminDtos;
import io.queryforge.backend.admin.corpus.service.CorpusAdminService;
import io.queryforge.backend.admin.pipeline.config.AdminPipelineProperties;
import io.queryforge.backend.admin.pipeline.service.PipelineAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminUiController {

    private final PipelineAdminService pipelineAdminService;
    private final CorpusAdminService corpusAdminService;
    private final AdminPipelineProperties pipelineProperties;

    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("navKey", "dashboard");
        model.addAttribute("dashboard", pipelineAdminService.getDashboardStats());
        model.addAttribute("sources", corpusAdminService.listSources());
        model.addAttribute("pageTitle", "대시보드");
        model.addAttribute("pageSubtitle", "현재 코퍼스 상태와 최근 파이프라인 실행 결과를 확인합니다.");
        return "admin/dashboard";
    }

    @GetMapping("/sources")
    public String sources(Model model) {
        model.addAttribute("navKey", "sources");
        model.addAttribute("sources", corpusAdminService.listSources());
        model.addAttribute("pageTitle", "수집 원본");
        model.addAttribute("pageSubtitle", "수집 대상을 관리하고 선택한 원본만 수집 실행할 수 있습니다.");
        return "admin/sources";
    }

    @GetMapping("/runs")
    public String runs(
            @RequestParam(name = "run_status", required = false) String runStatus,
            @RequestParam(name = "run_type", required = false) String runType,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset,
            Model model
    ) {
        List<CorpusAdminDtos.RunSummary> runs = pipelineAdminService.listRuns(null, runStatus, runType, limit, offset);
        model.addAttribute("navKey", "runs");
        model.addAttribute("runs", runs);
        model.addAttribute("runStatus", runStatus);
        model.addAttribute("runType", runType);
        model.addAttribute("pageTitle", "실행 이력");
        model.addAttribute("pageSubtitle", "수집, 정제, 청킹, 용어 추출, 적재 단계의 상태와 로그를 확인합니다.");
        return "admin/runs";
    }

    @GetMapping("/runs/{runId}")
    public String runDetail(@PathVariable UUID runId, Model model) {
        CorpusAdminDtos.RunDetail detail = pipelineAdminService.getRun(runId);
        model.addAttribute("navKey", "runs");
        model.addAttribute("runDetail", detail);
        model.addAttribute("runLogs", pipelineAdminService.getRunLogs(runId));
        model.addAttribute("pageTitle", "실행 상세");
        model.addAttribute("pageSubtitle", "단계별 진행과 로그를 보고 새로고침으로 실시간 상태를 확인합니다.");
        return "admin/run-detail";
    }

    @GetMapping("/documents")
    public String documents(
            @RequestParam(name = "product_name", required = false) String productName,
            @RequestParam(name = "version_label", required = false) String versionLabel,
            @RequestParam(name = "source_id", required = false) String sourceId,
            @RequestParam(name = "document_id", required = false) String documentId,
            @RequestParam(name = "section_heading_keyword", required = false) String headingKeyword,
            @RequestParam(name = "chunk_keyword", required = false) String chunkKeyword,
            @RequestParam(name = "active_only", defaultValue = "true") boolean activeOnly,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset,
            Model model
    ) {
        model.addAttribute("navKey", "documents");
        model.addAttribute("documents", corpusAdminService.listDocuments(
                productName, versionLabel, sourceId, documentId, headingKeyword, chunkKeyword, null, activeOnly, limit, offset
        ));
        model.addAttribute("sources", corpusAdminService.listSources());
        model.addAttribute("pageTitle", "문서");
        model.addAttribute("pageSubtitle", "수집/정제된 문서를 조회하고 문서 단위 재실행을 시작할 수 있습니다.");
        return "admin/documents";
    }

    @GetMapping("/documents/{documentId}")
    public String documentDetail(@PathVariable String documentId, Model model) {
        CorpusAdminDtos.DocumentDetail document = corpusAdminService.getDocument(documentId);
        model.addAttribute("navKey", "documents");
        model.addAttribute("document", document);
        model.addAttribute("rawPreview", corpusAdminService.previewRawVsCleaned(documentId));
        model.addAttribute("sections", corpusAdminService.listSections(documentId, null, null));
        model.addAttribute("chunks", corpusAdminService.listDocumentChunks(documentId, null, null, 200, 0));
        model.addAttribute("chunkBoundaryPreview", corpusAdminService.previewChunkBoundaries(documentId));
        model.addAttribute("relatedRuns", pipelineAdminService.listRuns(document.importRunId(), null, null, 20, 0));
        model.addAttribute("pageTitle", "문서 상세");
        model.addAttribute("pageSubtitle", "원문, 정제 결과, 섹션 구조, 청크 경계를 한 번에 검토합니다.");
        return "admin/document-detail";
    }

    @GetMapping("/chunks")
    public String chunks(
            @RequestParam(name = "product_name", required = false) String productName,
            @RequestParam(name = "version_label", required = false) String versionLabel,
            @RequestParam(name = "source_id", required = false) String sourceId,
            @RequestParam(name = "document_id", required = false) String documentId,
            @RequestParam(name = "chunk_keyword", required = false) String chunkKeyword,
            @RequestParam(name = "code_presence", required = false) Boolean codePresence,
            @RequestParam(name = "min_token_len", required = false) Integer minTokenLen,
            @RequestParam(name = "max_token_len", required = false) Integer maxTokenLen,
            @RequestParam(name = "active_only", defaultValue = "true") boolean activeOnly,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset,
            Model model
    ) {
        model.addAttribute("navKey", "chunks");
        model.addAttribute("chunks", corpusAdminService.listChunks(
                productName, versionLabel, sourceId, documentId, chunkKeyword, codePresence, minTokenLen, maxTokenLen, null, activeOnly, limit, offset
        ));
        model.addAttribute("pageTitle", "청크");
        model.addAttribute("pageSubtitle", "청크 경계, 길이, 구조 신호, 이웃 관계를 점검합니다.");
        return "admin/chunks";
    }

    @GetMapping("/chunks/{chunkId}")
    public String chunkDetail(@PathVariable String chunkId, Model model) {
        CorpusAdminDtos.ChunkDetail chunk = corpusAdminService.getChunk(chunkId);
        model.addAttribute("navKey", "chunks");
        model.addAttribute("chunk", chunk);
        model.addAttribute("document", corpusAdminService.getDocument(chunk.documentId()));
        model.addAttribute("neighbors", corpusAdminService.listChunkNeighbors(chunkId));
        model.addAttribute("previousChunk", chunk.previousChunkId() != null ? corpusAdminService.getChunk(chunk.previousChunkId()) : null);
        model.addAttribute("nextChunk", chunk.nextChunkId() != null ? corpusAdminService.getChunk(chunk.nextChunkId()) : null);
        model.addAttribute("pageTitle", "청크 상세");
        model.addAttribute("pageSubtitle", "이전/현재/다음 맥락과 이웃 관계를 함께 확인합니다.");
        return "admin/chunk-detail";
    }

    @GetMapping("/glossary")
    public String glossary(
            @RequestParam(name = "product_name", required = false) String productName,
            @RequestParam(name = "version_label", required = false) String versionLabel,
            @RequestParam(name = "source_id", required = false) String sourceId,
            @RequestParam(name = "term_type", required = false) String termType,
            @RequestParam(name = "keep_in_english", required = false) Boolean keepInEnglish,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "active_only", defaultValue = "true") boolean activeOnly,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "offset", required = false) Integer offset,
            Model model
    ) {
        model.addAttribute("navKey", "glossary");
        model.addAttribute("terms", corpusAdminService.listGlossary(
                productName, versionLabel, sourceId, termType, keepInEnglish, null, activeOnly, keyword, limit, offset
        ));
        model.addAttribute("topTerms", corpusAdminService.previewTopTerms(10, productName, termType, keepInEnglish));
        model.addAttribute("pageTitle", "용어 사전");
        model.addAttribute("pageSubtitle", "표준 용어, 별칭, 근거 문장을 확인하고 정책을 조정합니다.");
        return "admin/glossary";
    }

    @GetMapping("/glossary/{termId}")
    public String glossaryDetail(@PathVariable UUID termId, Model model) {
        CorpusAdminDtos.GlossaryTermDetail termDetail = corpusAdminService.getGlossaryTerm(termId);
        model.addAttribute("navKey", "glossary");
        model.addAttribute("termDetail", termDetail);
        model.addAttribute("evidence", corpusAdminService.listGlossaryEvidence(termId));
        model.addAttribute("pageTitle", "용어 상세");
        model.addAttribute("pageSubtitle", "용어 정책과 근거 정보를 검토하고 수정합니다.");
        return "admin/glossary-detail";
    }

    @GetMapping("/ingest-wizard")
    public String ingestWizard(Model model) {
        model.addAttribute("navKey", "wizard");
        model.addAttribute("sources", corpusAdminService.listSources());
        model.addAttribute("pipelineProperties", pipelineProperties);
        model.addAttribute("pageTitle", "실행 안내");
        model.addAttribute("pageSubtitle", "원본 선택부터 로그 확인까지 한 화면에서 점검하고 전체 실행합니다.");
        return "admin/ingest-wizard";
    }

}
