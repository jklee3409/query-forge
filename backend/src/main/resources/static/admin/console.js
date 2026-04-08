(() => {
  const page = document.body?.dataset?.adminPage;
  if (!page) return;

  const esc = (v) => String(v ?? "").replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;").replaceAll("'", "&#39;");
  const arr = (v) => Array.isArray(v) ? v : [];
  const dt = (v) => {
    if (!v) return "-";
    const d = new Date(v);
    if (Number.isNaN(d.getTime())) return String(v);
    const p = (n) => String(n).padStart(2, "0");
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
  };
  const fixed = (v, d = 4) => (v == null || Number.isNaN(Number(v)) ? "-" : Number(v).toFixed(d));
  const status = (v) => {
    const n = String(v || "").toLowerCase();
    if (n.includes("success") || n.includes("completed")) return "success";
    if (n.includes("run")) return "running";
    if (n.includes("fail")) return "failed";
    if (n.includes("queue") || n.includes("plan")) return "queued";
    return "cancelled";
  };
  const badge = (t, s) => `<span class="status-badge" data-status="${esc(s)}">${esc(t)}</span>`;
  const bool = (v) => v === true ? badge("예", "success") : v === false ? badge("아니오", "failed") : `<span class="plain-badge">-</span>`;
  const q = (m = {}) => {
    const sp = new URLSearchParams();
    Object.entries(m).forEach(([k, v]) => { if (v != null && v !== "") sp.append(k, v); });
    const s = sp.toString();
    return s ? `?${s}` : "";
  };
  const json = (v) => esc(JSON.stringify(v ?? {}, null, 2));
  const cards = (el, rows) => {
    if (!el) return;
    el.innerHTML = rows.map((r) => `<article class="summary-card"><div class="summary-card__label">${esc(r.label)}</div><div class="summary-card__value">${esc(r.value)}</div><div class="summary-card__meta">${esc(r.meta || "")}</div></article>`).join("");
  };
  const miniChart = (el, { title, rows, maxValue = null, valueFormatter = (v) => fixed(v, 4) }) => {
    if (!el) return;
    const safeRows = arr(rows).map((row) => ({
      label: String(row.label ?? "-"),
      value: Number(row.value ?? 0),
    })).filter((row) => Number.isFinite(row.value));
    if (safeRows.length === 0) {
      el.innerHTML = `<div class="table-title">${esc(title || "차트")}</div><div class="summary-card__meta">표시할 데이터가 없습니다.</div>`;
      return;
    }
    const max = maxValue != null ? Number(maxValue) : Math.max(...safeRows.map((row) => row.value), 1);
    el.innerHTML = `
      <div class="table-title">${esc(title || "차트")}</div>
      <div class="mini-chart">
        ${safeRows.map((row) => {
          const ratio = max > 0 ? Math.max(0, Math.min(1, row.value / max)) : 0;
          return `
            <div class="mini-chart__row">
              <div class="mini-chart__label">${esc(row.label)}</div>
              <div class="mini-chart__track"><div class="mini-chart__fill" style="width:${(ratio * 100).toFixed(2)}%"></div></div>
              <div class="mini-chart__value">${esc(valueFormatter(row.value))}</div>
            </div>
          `;
        }).join("")}
      </div>
    `;
  };
  const formObj = (f) => {
    const o = {};
    new FormData(f).forEach((v, k) => { o[k] = v; });
    f.querySelectorAll("input[type=checkbox]").forEach((c) => { if (c.name) o[c.name] = c.checked; });
    return o;
  };
  const fetchJson = async (u, opt = {}) => {
    const r = await fetch(u, opt);
    const t = await r.text();
    let p = {};
    if (t) { try { p = JSON.parse(t); } catch { p = { raw: t }; } }
    if (!r.ok) throw new Error(p.detail || p.error || p.message || JSON.stringify(p));
    return p;
  };
  const postJson = (u, b) => fetchJson(u, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(b || {}) });
  const startPolling = (fn, intervalMs = 5000) => {
    if (typeof fn !== "function") return () => {};
    const id = window.setInterval(() => {
      fn().catch(() => {});
    }, intervalMs);
    return () => window.clearInterval(id);
  };

  const buildLlmJobLoader = ({ tableId, panelId, filter }) => {
    const tbody = document.querySelector(`#${tableId} tbody`);
    if (!tbody) return async () => {};
    const panel = panelId ? document.getElementById(panelId) : null;
    return async () => {
      const rows = await fetchJson("/api/admin/console/llm-jobs?limit=120");
      const filtered = arr(rows).filter((r) => (typeof filter === "function" ? filter(r) : true));
      tbody.innerHTML = filtered.map((r) => {
        const progress = r.progressPct == null ? "-" : `${Number(r.progressPct).toFixed(1)}%`;
        const statusBadge = badge(r.jobStatus || "-", status(r.jobStatus));
        const controls = [];
        const current = String(r.jobStatus || "").toLowerCase();
        if (current === "queued" || current === "running") controls.push(`<button class="button button--ghost" data-job-act="pause" data-job-id="${esc(r.jobId)}">일시정지</button>`);
        if (current === "paused") controls.push(`<button class="button button--ghost" data-job-act="resume" data-job-id="${esc(r.jobId)}">재개</button>`);
        if (current === "queued" || current === "running" || current === "paused") controls.push(`<button class="button button--ghost" data-job-act="cancel" data-job-id="${esc(r.jobId)}">취소</button>`);
        if (current === "failed") controls.push(`<button class="button button--ghost" data-job-act="retry" data-job-id="${esc(r.jobId)}">재시도</button>`);
        controls.push(`<button class="button button--ghost" data-job-items="${esc(r.jobId)}">상세</button>`);
        return `<tr><td class="mono-truncate">${esc(r.jobId)}</td><td>${esc(r.jobType || "-")}</td><td>${statusBadge}</td><td>${esc(progress)}</td><td>${esc(r.retryCount ?? 0)}/${esc(r.maxRetries ?? 0)}</td><td>${esc(r.generationBatchId || r.gatingBatchId || r.ragTestRunId || "-")}</td><td>${controls.join(" ")}</td></tr>`;
      }).join("");
      tbody.querySelectorAll("[data-job-act]").forEach((btn) => btn.addEventListener("click", async () => {
        const id = btn.dataset.jobId;
        const action = btn.dataset.jobAct;
        await postJson(`/api/admin/console/llm-jobs/${id}/${action}`, {});
        const rerender = buildLlmJobLoader({ tableId, panelId, filter });
        await rerender();
      }));
      tbody.querySelectorAll("[data-job-items]").forEach((btn) => btn.addEventListener("click", async () => {
        if (!panel) return;
        const id = btn.dataset.jobItems;
        const [job, items] = await Promise.all([
          fetchJson(`/api/admin/console/llm-jobs/${id}`),
          fetchJson(`/api/admin/console/llm-jobs/${id}/items`),
        ]);
        panel.innerHTML = `<div class="table-title">LLM Job 상세: ${esc(id)}</div><div class="detail-grid" style="margin-top:12px;"><div class="detail-card"><div class="detail-item__label">command / experiment</div><div class="detail-item__value">${esc(job.commandName || "-")} / ${esc(job.experimentName || "-")}</div></div><div class="detail-card"><div class="detail-item__label">진행</div><div class="detail-item__value">${esc(job.processedItems ?? 0)} / ${esc(job.totalItems ?? 0)} (${esc(job.progressPct == null ? "-" : Number(job.progressPct).toFixed(1) + "%")})</div></div></div><div class="table-title" style="margin-top:12px;">job_items</div><div class="code-surface">${json(items)}</div>`;
      }));
    };
  };

  const initPipeline = () => {
    const summary = document.getElementById("pipeline-summary-cards");
    const runs = document.querySelector("#pipeline-runs-table tbody");
    const docs = document.querySelector("#pipeline-documents-table tbody");
    const detail = document.getElementById("pipeline-document-detail-panel");
    const source = document.getElementById("pipeline-source-id");
    const filter = document.getElementById("pipeline-doc-filter-form");

    const loadSummary = async () => {
      const d = await fetchJson("/api/admin/pipeline/dashboard");
      cards(summary, [
        { label: "문서 소스", value: d.sourceCount ?? 0 }, { label: "활성 문서", value: d.activeDocumentCount ?? 0 },
        { label: "활성 청크", value: d.activeChunkCount ?? 0 }, { label: "용어 수", value: d.glossaryTermCount ?? 0 },
        { label: "중복 URL 스킵", value: d.duplicateUrlSkippedCount ?? 0, meta: "최근 30일" },
        { label: "동일 hash 스킵", value: d.sameHashSkippedCount ?? 0, meta: "최근 30일" },
        { label: "변경 없음 스킵", value: d.unchangedSkippedCount ?? 0, meta: "최근 30일" },
        { label: "최근 성공", value: d.recentRunSuccessCount ?? 0, meta: "7일" },
        { label: "최근 실패", value: d.recentRunFailureCount ?? 0, meta: "7일" },
      ]);
    };
    const loadRuns = async () => {
      const rows = await fetchJson("/api/admin/pipeline/runs?limit=30");
      runs.innerHTML = rows.map((r) => {
        const s = typeof r.summaryJson === "object" ? r.summaryJson : {};
        const processed = s.document_count ?? s.documents_discovered ?? s.documents_persisted ?? 0;
        return `<tr><td class="mono-truncate">${esc(r.runId)}</td><td>${esc(r.runType)}</td><td>${dt(r.startedAt)}</td><td>${dt(r.finishedAt)}</td><td>${badge(r.runStatus || "-", status(r.runStatus))}</td><td>${esc(processed)}</td><td>${r.errorMessage ? badge("있음", "failed") : badge("없음", "success")}</td></tr>`;
      }).join("");
    };
    const loadDocs = async (p = {}) => {
      const rows = await fetchJson(`/api/admin/corpus/documents${q({ active_only: true, limit: 30, ...p })}`);
      docs.innerHTML = rows.map((r) => `<tr><td class="mono-truncate">${esc(r.documentId)}</td><td>${esc(r.title)}</td><td>${esc(r.sourceId)}</td><td>${esc(r.versionLabel || "-")}</td><td>${esc(r.sectionCount ?? 0)}</td><td>${esc(r.chunkCount ?? 0)}</td><td><button class="button button--ghost" data-doc="${esc(r.documentId)}">상세</button></td></tr>`).join("");
      docs.querySelectorAll("[data-doc]").forEach((b) => b.addEventListener("click", async () => {
        const id = b.dataset.doc;
        const [doc, rv, bd] = await Promise.all([fetchJson(`/api/admin/corpus/documents/${id}`), fetchJson(`/api/admin/corpus/documents/${id}/preview/raw-vs-cleaned`), fetchJson(`/api/admin/corpus/documents/${id}/preview/chunk-boundaries`)]);
        const lines = arr(bd.boundaries).slice(0, 10).map((x) => `#${x.chunkIndexInDocument} ${x.sectionPathText}`).join("\n");
        detail.innerHTML = `<div class="table-title">문서 상세: ${esc(id)}</div><div class="detail-grid" style="margin-top:12px;"><div class="detail-card"><div class="detail-item__label">제목 / URL</div><div class="detail-item__value">${esc(doc.title || "-")}<br>${esc(doc.canonicalUrl || "-")}</div></div><div class="detail-card"><div class="detail-item__label">정제 길이</div><div class="detail-item__value">raw ${esc((rv.rawText || "").length)} / cleaned ${esc((rv.cleanedText || "").length)}</div></div></div><div class="table-title" style="margin-top:12px;">청크 경계</div><div class="code-surface">${esc(lines || "-")}</div>`;
      }));
    };
    const loadSources = async () => {
      const rows = await fetchJson("/api/admin/corpus/sources");
      source.innerHTML = `<option value="">전체</option>` + rows.map((r) => `<option value="${esc(r.sourceId)}">${esc(r.sourceId)} (${esc(r.productName || "-")})</option>`).join("");
    };
    document.querySelectorAll("[data-pipeline-run]").forEach((b) => b.addEventListener("click", async () => {
      const runType = b.dataset.pipelineRun; b.disabled = true;
      try { await postJson(runType === "full_ingest" ? "/api/admin/pipeline/full-ingest" : `/api/admin/pipeline/${runType}`, {}); await Promise.all([loadSummary(), loadRuns()]); } catch (e) { alert(e.message); } finally { b.disabled = false; }
    }));
    filter.addEventListener("submit", async (e) => { e.preventDefault(); await loadDocs(formObj(filter)); });
    Promise.all([loadSources(), loadSummary(), loadRuns(), loadDocs()]).catch((e) => alert(e.message));
  };

  const initSynthetic = () => {
    const mTable = document.querySelector("#synthetic-methods-table tbody");
    const mSel = document.getElementById("synthetic-method-code");
    const mFil = document.getElementById("synthetic-filter-method");
    const bFil = document.getElementById("synthetic-filter-batch");
    const bTable = document.querySelector("#synthetic-batches-table tbody");
    const sCards = document.getElementById("synthetic-stats-cards");
    const methodChart = document.getElementById("synthetic-method-chart");
    const qTable = document.querySelector("#synthetic-queries-table tbody");
    const panel = document.getElementById("synthetic-query-detail-panel");
    const runForm = document.getElementById("synthetic-run-form");
    const filForm = document.getElementById("synthetic-query-filter-form");
    const loadLlmJobs = buildLlmJobLoader({
      tableId: "synthetic-llm-jobs-table",
      panelId: "synthetic-llm-job-items-panel",
      filter: (r) => (r.jobType === "GENERATE_SYNTHETIC_QUERY") || !!r.generationBatchId,
    });
    let methods = [], batches = [];
    const fill = () => {
      mSel.innerHTML = `<option value="">선택</option>` + methods.map((m) => `<option value="${esc(m.methodCode)}">${esc(m.methodCode)} - ${esc(m.methodName)}</option>`).join("");
      mFil.innerHTML = `<option value="">전체</option>` + methods.map((m) => `<option value="${esc(m.methodCode)}">${esc(m.methodCode)}</option>`).join("");
      bFil.innerHTML = `<option value="">전체</option>` + batches.map((b) => `<option value="${esc(b.batchId)}">${esc(b.versionName)} (${esc(b.methodCode)})</option>`).join("");
    };
    const loadMethods = async () => { methods = await fetchJson("/api/admin/console/synthetic/methods"); mTable.innerHTML = methods.map((m) => `<tr><td>${esc(m.methodCode)}</td><td>${esc(m.methodName)}</td><td>${esc(m.description || "-")}</td><td>${esc(m.promptTemplateVersion || "-")}</td><td>${bool(m.active)}</td></tr>`).join(""); fill(); };
    const loadBatches = async () => { batches = await fetchJson("/api/admin/console/synthetic/batches?limit=50"); bTable.innerHTML = batches.map((b) => `<tr><td class="mono-truncate">${esc(b.batchId)}</td><td>${esc(b.methodCode)}</td><td>${esc(b.versionName)}</td><td>${badge(b.status || "-", status(b.status))}</td><td>${dt(b.startedAt)}</td><td>${dt(b.finishedAt)}</td><td>${esc(b.totalGeneratedCount ?? 0)}</td></tr>`).join(""); fill(); };
    const loadStats = async () => {
      const v = formObj(filForm); const s = await fetchJson(`/api/admin/console/synthetic/stats${q({ method_code: v.method_code || null, batch_id: v.batch_id || null })}`);
      const byM = arr(s.byMethod), byT = arr(s.byQueryType); const total = byM.reduce((a, x) => a + Number(x.count || 0), 0);
      cards(sCards, [{ label: "총 합성 질의", value: total }, { label: "방식 수", value: byM.length, meta: byM.map((x) => `${x.method_code}:${x.count}`).join(" / ") || "-" }, { label: "질의 유형 수", value: byT.length, meta: byT.map((x) => `${x.query_type}:${x.count}`).join(" / ") || "-" }]);
      miniChart(methodChart, {
        title: "방식별 생성량 그래프",
        rows: byM.map((x) => ({ label: x.method_code, value: Number(x.count || 0) })),
        valueFormatter: (value) => `${Math.round(value)}건`,
      });
    };
    const loadQueries = async () => {
      const v = formObj(filForm); const rows = await fetchJson(`/api/admin/console/synthetic/queries${q({ method_code: v.method_code || null, batch_id: v.batch_id || null, query_type: v.query_type || null, gated: v.gated || null, limit: 50 })}`);
      qTable.innerHTML = rows.map((r) => `<tr><td class="mono-truncate">${esc(r.queryId)}</td><td>${esc(r.queryText)}</td><td>${esc(r.queryType || "-")}</td><td>${esc(r.generationMethod || "-")}</td><td>${esc(r.generationBatchVersion || "-")}</td><td>${r.gated ? badge("통과", "success") : badge("미통과", "failed")}</td><td><button class="button button--ghost" data-q="${esc(r.queryId)}">상세</button></td></tr>`).join("");
      qTable.querySelectorAll("[data-q]").forEach((b) => b.addEventListener("click", async () => {
        const d = await fetchJson(`/api/admin/console/synthetic/queries/${b.dataset.q}`);
        panel.innerHTML = `<div class="table-title">합성 질의 상세</div><div class="detail-grid" style="margin-top:12px;"><div class="detail-card"><div class="detail-item__label">질의</div><div class="detail-item__value">${esc(d.queryText)}</div></div><div class="detail-card"><div class="detail-item__label">방식 / 유형</div><div class="detail-item__value">${esc(d.generationMethod)} / ${esc(d.queryType)}</div></div><div class="detail-card"><div class="detail-item__label">배치 / 언어</div><div class="detail-item__value">${esc(d.generationBatchId || "-")} / ${esc(d.languageProfile || "-")}</div></div></div><div class="table-title" style="margin-top:12px;">source_chunk</div><div class="code-surface">${json(d.sourceChunk)}</div><div class="table-title" style="margin-top:12px;">source_links</div><div class="code-surface">${json(d.sourceLinks)}</div><div class="table-title" style="margin-top:12px;">raw_output</div><div class="code-surface">${json(d.rawOutput)}</div>`;
      }));
    };
    runForm.addEventListener("submit", async (e) => { e.preventDefault(); const v = formObj(runForm); try { await postJson("/api/admin/console/synthetic/batches/run", { methodCode: v.methodCode, versionName: v.versionName, sourceDocumentVersion: v.sourceDocumentVersion || null, limitChunks: v.limitChunks ? Number(v.limitChunks) : null }); await Promise.all([loadBatches(), loadQueries(), loadStats(), loadLlmJobs()]); } catch (x) { alert(x.message); } });
    filForm.addEventListener("submit", async (e) => { e.preventDefault(); await Promise.all([loadQueries(), loadStats()]); });
    startPolling(loadLlmJobs, 5000);
    Promise.all([loadMethods(), loadBatches(), loadLlmJobs()]).then(() => Promise.all([loadQueries(), loadStats(), loadLlmJobs()])).catch((e) => alert(e.message));
  };

  const initGating = () => {
    const mSel = document.getElementById("gating-method-code"), bSel = document.getElementById("gating-generation-batch-id"), run = document.getElementById("gating-run-form");
    const bTable = document.querySelector("#gating-batches-table tbody"), fCards = document.getElementById("gating-funnel-cards"), rTable = document.querySelector("#gating-results-table tbody");
    const funnelChart = document.getElementById("gating-funnel-chart");
    const loadLlmJobs = buildLlmJobLoader({
      tableId: "gating-llm-jobs-table",
      panelId: "gating-llm-job-items-panel",
      filter: (r) => (r.jobType === "RUN_LLM_SELF_EVAL") || !!r.gatingBatchId,
    });
    let current = null;
    const loadSelectors = async () => {
      const [m, b] = await Promise.all([fetchJson("/api/admin/console/synthetic/methods"), fetchJson("/api/admin/console/synthetic/batches?limit=100")]);
      mSel.innerHTML = m.map((x) => `<option value="${esc(x.methodCode)}">${esc(x.methodCode)} - ${esc(x.methodName)}</option>`).join("");
      bSel.innerHTML = `<option value="">선택</option>` + b.map((x) => `<option value="${esc(x.batchId)}">${esc(x.versionName)} (${esc(x.methodCode)})</option>`).join("");
    };
    const loadFunnel = async (id) => {
      const f = await fetchJson(`/api/admin/console/gating/batches/${id}/funnel`);
      cards(fCards, [{ label: "생성 총량", value: f.generatedTotal ?? 0 }, { label: "Rule 통과", value: f.passedRule ?? 0 }, { label: "LLM 통과", value: f.passedLlm ?? 0 }, { label: "Utility 통과", value: f.passedUtility ?? 0 }, { label: "Diversity 통과", value: f.passedDiversity ?? 0 }, { label: "최종 승인", value: f.finalAccepted ?? 0 }]);
      miniChart(funnelChart, {
        title: "게이팅 퍼널 그래프",
        rows: [
          { label: "입력", value: Number(f.generatedTotal || 0) },
          { label: "Rule", value: Number(f.passedRule || 0) },
          { label: "LLM", value: Number(f.passedLlm || 0) },
          { label: "Utility", value: Number(f.passedUtility || 0) },
          { label: "Diversity", value: Number(f.passedDiversity || 0) },
          { label: "승인", value: Number(f.finalAccepted || 0) },
        ],
        valueFormatter: (value) => `${Math.round(value)}건`,
      });
    };
    const loadResults = async (id) => { const rows = await fetchJson(`/api/admin/console/gating/batches/${id}/results?limit=120`); rTable.innerHTML = rows.map((r) => `<tr><td class="mono-truncate">${esc(r.syntheticQueryId)}</td><td>${esc(r.queryText)}</td><td>${esc(r.queryType || "-")}</td><td>${bool(r.passedRule)}</td><td>${bool(r.passedLlm)}</td><td>${bool(r.passedUtility)}</td><td>${bool(r.passedDiversity)}</td><td>${esc(fixed(r.finalScore, 4))}</td><td>${esc(r.rejectedStage || "-")}</td><td><div class="line-clamp-2">${esc(r.rejectedReason || "-")}</div></td><td>${r.finalDecision ? badge("승인", "success") : badge("탈락", "failed")}</td></tr>`).join(""); };
    const loadBatches = async () => {
      const rows = await fetchJson("/api/admin/console/gating/batches?limit=50");
      bTable.innerHTML = rows.map((r) => { const s = r.processedCount > 0 ? ((r.acceptedCount / r.processedCount) * 100).toFixed(1) : "0.0"; return `<tr><td class="mono-truncate">${esc(r.gatingBatchId)}</td><td>${esc(r.gatingPreset)}</td><td>${esc(r.methodCode || "-")}</td><td>${badge(r.status || "-", status(r.status))}</td><td>${esc(r.processedCount ?? 0)}</td><td>${esc(r.acceptedCount ?? 0)}</td><td>${s}%</td><td><button class="button button--ghost" data-b="${esc(r.gatingBatchId)}">열람</button></td></tr>`; }).join("");
      bTable.querySelectorAll("[data-b]").forEach((x) => x.addEventListener("click", async () => { current = x.dataset.b; await Promise.all([loadFunnel(current), loadResults(current)]); }));
      if (!current && rows.length > 0) { current = rows[0].gatingBatchId; await Promise.all([loadFunnel(current), loadResults(current)]); }
    };
    run.addEventListener("submit", async (e) => { e.preventDefault(); const v = formObj(run); try { const b = await postJson("/api/admin/console/gating/batches/run", { methodCode: v.methodCode, generationBatchId: v.generationBatchId || null, gatingPreset: v.gatingPreset, enableRuleFilter: !!v.enableRuleFilter, enableLlmSelfEval: !!v.enableLlmSelfEval, enableRetrievalUtility: !!v.enableRetrievalUtility, enableDiversity: !!v.enableDiversity }); current = b.gatingBatchId; await Promise.all([loadBatches(), loadLlmJobs()]); } catch (x) { alert(x.message); } });
    startPolling(loadLlmJobs, 5000);
    Promise.all([loadSelectors(), loadBatches(), loadLlmJobs()]).catch((e) => alert(e.message));
  };

  const initRag = () => {
    const methods = document.getElementById("rag-method-checks"), dataset = document.getElementById("rag-dataset-id"), runForm = document.getElementById("rag-test-run-form");
    const dsTable = document.querySelector("#rag-datasets-table tbody"), itemsPanel = document.getElementById("rag-dataset-items-panel"), tTable = document.querySelector("#rag-tests-table tbody");
    const sCards = document.getElementById("rag-summary-cards"), dTable = document.querySelector("#rag-details-table tbody"), rlTable = document.querySelector("#rag-rewrite-logs-table tbody"), rlPanel = document.getElementById("rag-rewrite-detail-panel");
    const metricsChart = document.getElementById("rag-metrics-chart");
    const loadLlmJobs = buildLlmJobLoader({
      tableId: "rag-llm-jobs-table",
      panelId: "rag-llm-job-items-panel",
      filter: (r) => (r.jobType === "RUN_RAG_TEST") || !!r.ragTestRunId,
    });
    const loadMethods = async () => { const rows = await fetchJson("/api/admin/console/synthetic/methods"); methods.innerHTML = rows.map((m, i) => `<label class="plain-badge"><input type="checkbox" name="methodCodes" value="${esc(m.methodCode)}" ${i === 0 ? "checked" : ""}> ${esc(m.methodCode)}</label>`).join(""); };
    const loadDatasets = async () => {
      const rows = await fetchJson("/api/admin/console/rag/datasets");
      dataset.innerHTML = rows.map((r, i) => `<option value="${esc(r.datasetId)}" ${i === 0 ? "selected" : ""}>${esc(r.datasetName)} (${esc(r.totalItems)})</option>`).join("");
      dsTable.innerHTML = rows.map((r) => `<tr><td class="mono-truncate">${esc(r.datasetId)}</td><td>${esc(r.datasetName)}</td><td>${esc(r.version || "-")}</td><td>${esc(r.totalItems ?? 0)}</td><td>${dt(r.createdAt)}</td><td><button class="button button--ghost" data-ds="${esc(r.datasetId)}">문항 보기</button></td></tr>`).join("");
      dsTable.querySelectorAll("[data-ds]").forEach((b) => b.addEventListener("click", async () => { const rows2 = await fetchJson(`/api/admin/console/rag/datasets/${b.dataset.ds}/items?limit=30`); itemsPanel.innerHTML = `<div class="table-title">데이터셋 문항 미리보기 (${esc(rows2.length)})</div><div class="code-surface">${esc(rows2.map((x) => `[${x.sampleId}] ${x.queryCategory} - ${x.userQueryKo}`).join("\n"))}</div>`; }));
    };
    const loadTests = async () => { const rows = await fetchJson("/api/admin/console/rag/tests?limit=50"); tTable.innerHTML = rows.map((r) => `<tr><td class="mono-truncate">${esc(r.ragTestRunId)}</td><td>${badge(r.status || "-", status(r.status))}</td><td>${esc(r.datasetName || "-")}</td><td>${esc(arr(r.generationMethodCodes).join(", ") || "-")}</td><td>${bool(r.gatingApplied)}</td><td>${esc(r.rewriteEnabled ? (r.selectiveRewrite ? "selective" : "always") : "off")}</td><td><div class="line-clamp-2">${esc(JSON.stringify(r.metricsJson || {}))}</div></td><td><button class="button button--ghost" data-run="${esc(r.ragTestRunId)}">상세</button></td></tr>`).join(""); tTable.querySelectorAll("[data-run]").forEach((b) => b.addEventListener("click", () => loadRunDetail(b.dataset.run))); };
    const loadRunDetail = async (id) => {
      const d = await fetchJson(`/api/admin/console/rag/tests/${id}?detail_limit=100`), s = d.summary || {};
      cards(sCards, [{ label: "Recall@5", value: fixed(s.recall_at_5, 4) }, { label: "Hit@5", value: fixed(s.hit_at_5, 4) }, { label: "MRR@10", value: fixed(s.mrr_at_10, 4) }, { label: "nDCG@10", value: fixed(s.ndcg_at_10, 4) }, { label: "Latency(ms)", value: fixed(s.latency_avg_ms, 2) }, { label: "Rewrite 수용률", value: s.rewrite_acceptance_rate == null ? "-" : `${(Number(s.rewrite_acceptance_rate) * 100).toFixed(1)}%` }, { label: "Rewrite 거절률", value: s.rewrite_rejection_rate == null ? "-" : `${(Number(s.rewrite_rejection_rate) * 100).toFixed(1)}%` }, { label: "평균 confidence delta", value: fixed(s.average_confidence_delta, 4) }]);
      miniChart(metricsChart, {
        title: "RAG 핵심 지표 그래프",
        rows: [
          { label: "Recall@5", value: Number(s.recall_at_5 || 0) },
          { label: "Hit@5", value: Number(s.hit_at_5 || 0) },
          { label: "MRR@10", value: Number(s.mrr_at_10 || 0) },
          { label: "nDCG@10", value: Number(s.ndcg_at_10 || 0) },
        ],
        maxValue: 1,
        valueFormatter: (value) => Number(value).toFixed(4),
      });
      dTable.innerHTML = arr(d.details).map((r) => `<tr><td>${esc(r.sampleId)}</td><td>${esc(r.queryCategory || "-")}</td><td>${esc(r.rawQuery || "-")}</td><td>${esc(r.rewriteQuery || "-")}</td><td>${bool(r.rewriteApplied)}</td><td>${bool(r.hitTarget)}</td><td><div class="line-clamp-2">${esc(JSON.stringify(r.metricContribution || {}))}</div></td></tr>`).join("");
    };
    const loadRewriteLogs = async () => {
      if (!rlTable) return;
      const rows = await fetchJson("/api/admin/console/rewrite/logs?limit=100");
      rlTable.innerHTML = rows.map((r) => `<tr><td class="mono-truncate">${esc(r.rewriteLogId)}</td><td>${esc(r.rawQuery || "-")}</td><td>${esc(r.finalQuery || "-")}</td><td>${esc(r.rewriteStrategy || "-")}</td><td>${bool(r.rewriteApplied)}</td><td>${esc(fixed(r.confidenceDelta, 4))}</td><td><div class="line-clamp-2">${esc(r.decisionReason || r.rejectionReason || "-")}</div></td><td><button class="button button--ghost" data-rl="${esc(r.rewriteLogId)}">상세</button></td></tr>`).join("");
      rlTable.querySelectorAll("[data-rl]").forEach((b) => b.addEventListener("click", async () => { const d = await fetchJson(`/api/admin/console/rewrite/logs/${b.dataset.rl}`); rlPanel.innerHTML = `<div class="table-title">Rewrite 로그 상세: ${esc(b.dataset.rl)}</div><div class="detail-grid" style="margin-top:12px;"><div class="detail-card"><div class="detail-item__label">raw / final</div><div class="detail-item__value">${esc(d.rewrite?.rawQuery || "-")}<br>${esc(d.rewrite?.finalQuery || "-")}</div></div><div class="detail-card"><div class="detail-item__label">결정</div><div class="detail-item__value">${esc(d.rewrite?.decisionReason || "-")} / delta=${esc(fixed(d.rewrite?.confidenceDelta, 4))}</div></div></div><div class="table-title" style="margin-top:12px;">memory_retrievals</div><div class="code-surface">${json(d.memoryRetrievals)}</div><div class="table-title" style="margin-top:12px;">candidate_logs</div><div class="code-surface">${json(d.candidateLogs)}</div>`; }));
    };
    runForm.addEventListener("submit", async (e) => {
      e.preventDefault();
      const v = formObj(runForm), selected = Array.from(methods.querySelectorAll("input[name=methodCodes]:checked")).map((x) => x.value);
      if (selected.length === 0) { alert("최소 1개 생성 방식을 선택하세요."); return; }
      try {
        const run = await postJson("/api/admin/console/rag/tests/run", { datasetId: v.datasetId, methodCodes: selected, gatingPreset: v.gatingPreset, gatingApplied: !!v.gatingApplied, rewriteEnabled: !!v.rewriteEnabled, selectiveRewrite: !!v.selectiveRewrite, useSessionContext: !!v.useSessionContext, threshold: v.threshold ? Number(v.threshold) : null, retrievalTopK: v.retrievalTopK ? Number(v.retrievalTopK) : null, rerankTopN: v.rerankTopN ? Number(v.rerankTopN) : null });
        await loadTests(); await loadRunDetail(run.ragTestRunId); await loadRewriteLogs(); await loadLlmJobs();
      } catch (x) { alert(x.message); }
    });
    startPolling(loadLlmJobs, 5000);
    Promise.all([loadMethods(), loadDatasets(), loadTests(), loadRewriteLogs(), loadLlmJobs()]).catch((e) => alert(e.message));
  };

  if (page === "pipeline") initPipeline();
  if (page === "synthetic") initSynthetic();
  if (page === "gating") initGating();
  if (page === "rag-tests") initRag();
})();
