package io.queryforge.backend.admin.ui.controller;

import io.queryforge.backend.admin.corpus.model.CorpusAdminDtos;
import io.queryforge.backend.admin.corpus.service.CorpusAdminService;
import io.queryforge.backend.admin.pipeline.config.AdminPipelineProperties;
import io.queryforge.backend.admin.pipeline.service.PipelineAdminService;
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
public class AdminUiController {

    private final PipelineAdminService pipelineAdminService;
    private final CorpusAdminService corpusAdminService;
    private final AdminPipelineProperties pipelineProperties;

    public AdminUiController(
            PipelineAdminService pipelineAdminService,
            CorpusAdminService corpusAdminService,
            AdminPipelineProperties pipelineProperties
    ) {
        this.pipelineAdminService = pipelineAdminService;
        this.corpusAdminService = corpusAdminService;
        this.pipelineProperties = pipelineProperties;
    }

    @GetMapping
    public String dashboard(Model model) {
        model.addAttribute("navKey", "dashboard");
        model.addAttribute("dashboard", pipelineAdminService.getDashboardStats());
        model.addAttribute("sources", corpusAdminService.listSources());
        model.addAttribute("pageTitle", "대시보드 Dashboard");
        model.addAttribute("pageSubtitle", "현재 corpus 상태와 최근 파이프라인 실행 결과를 한눈에 확인합니다.");
        return "admin/dashboard";
    }

    @GetMapping("/sources")
    public String sources(Model model) {
        model.addAttribute("navKey", "sources");
        model.addAttribute("sources", corpusAdminService.listSources());
        model.addAttribute("pageTitle", "문서 Sources");
        model.addAttribute("pageSubtitle", "수집 대상 source를 관리하고, 선택한 source만 수집 실행할 수 있습니다.");
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
        model.addAttribute("pageTitle", "실행 이력 Runs");
        model.addAttribute("pageSubtitle", "collect -> normalize -> chunk -> glossary -> import 상태와 로그를 검수합니다.");
        return "admin/runs";
    }

    @GetMapping("/runs/{runId}")
    public String runDetail(@PathVariable UUID runId, Model model) {
        CorpusAdminDtos.RunDetail detail = pipelineAdminService.getRun(runId);
        model.addAttribute("navKey", "runs");
        model.addAttribute("runDetail", detail);
        model.addAttribute("runLogs", pipelineAdminService.getRunLogs(runId));
        model.addAttribute("pageTitle", "Run Detail");
        model.addAttribute("pageSubtitle", "실행 단계, 로그, 산출물 경로를 확인합니다.");
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
        model.addAttribute("pageTitle", "문서 Documents");
        model.addAttribute("pageSubtitle", "수집/정제된 문서를 검수하고 개별 문서 단위 재실행을 시작합니다.");
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
        model.addAttribute("pageTitle", "문서 상세 Document Detail");
        model.addAttribute("pageSubtitle", "원문, 정제 결과, 섹션 구조, chunk 분할 결과를 함께 검수합니다.");
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
        model.addAttribute("pageTitle", "청크 Chunks");
        model.addAttribute("pageSubtitle", "chunk boundary, 길이, structural signal, neighbor relation을 검수합니다.");
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
        model.addAttribute("pageTitle", "청크 상세 Chunk Detail");
        model.addAttribute("pageSubtitle", "prev/current/next context와 neighbor relation을 같이 확인합니다.");
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
        model.addAttribute("pageTitle", "용어 사전 Glossary");
        model.addAttribute("pageSubtitle", "canonical form, alias, evidence, keep_in_english 정책을 검수합니다.");
        return "admin/glossary";
    }

    @GetMapping("/glossary/{termId}")
    public String glossaryDetail(@PathVariable UUID termId, Model model) {
        CorpusAdminDtos.GlossaryTermDetail termDetail = corpusAdminService.getGlossaryTerm(termId);
        model.addAttribute("navKey", "glossary");
        model.addAttribute("termDetail", termDetail);
        model.addAttribute("evidence", corpusAdminService.listGlossaryEvidence(termId));
        model.addAttribute("pageTitle", "용어 상세 Glossary Detail");
        model.addAttribute("pageSubtitle", "용어 정책과 evidence provenance를 확인하고 수동 수정합니다.");
        return "admin/glossary-detail";
    }

    @GetMapping("/ingest-wizard")
    public String ingestWizard(Model model) {
        model.addAttribute("navKey", "wizard");
        model.addAttribute("sources", corpusAdminService.listSources());
        model.addAttribute("pipelineProperties", pipelineProperties);
        model.addAttribute("pageTitle", "실행 마법사 Ingest Wizard");
        model.addAttribute("pageSubtitle", "source 선택부터 import 옵션까지 한 번에 확인하고 전체 ingest를 시작합니다.");
        return "admin/ingest-wizard";
    }
}
