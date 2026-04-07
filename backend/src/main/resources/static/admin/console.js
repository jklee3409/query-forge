(() => {
  const page = document.body?.dataset?.adminPage;
  if (!page) {
    return;
  }

  const escapeHtml = (value) => {
    const target = value == null ? "" : String(value);
    return target
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;")
      .replaceAll("'", "&#39;");
  };

  const formatDate = (value) => {
    if (!value) return "-";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return String(value);
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")} ${String(date.getHours()).padStart(2, "0")}:${String(date.getMinutes()).padStart(2, "0")}:${String(date.getSeconds()).padStart(2, "0")}`;
  };

  const toStatus = (value) => {
    const normalized = String(value || "").toLowerCase();
    if (normalized.includes("success") || normalized.includes("completed")) return "success";
    if (normalized.includes("run")) return "running";
    if (normalized.includes("fail")) return "failed";
    if (normalized.includes("queue") || normalized.includes("plan")) return "queued";
    return "cancelled";
  };

  const boolBadge = (value) => {
    if (value === true) return `<span class="status-badge" data-status="success">예</span>`;
    if (value === false) return `<span class="status-badge" data-status="failed">아니오</span>`;
    return `<span class="plain-badge">-</span>`;
  };

  const qs = (params) => {
    const query = new URLSearchParams();
    Object.entries(params || {}).forEach(([key, value]) => {
      if (value == null || value === "") return;
      query.append(key, value);
    });
    const built = query.toString();
    return built ? `?${built}` : "";
  };

  const fetchJson = async (url, options = {}) => {
    const response = await fetch(url, options);
    const text = await response.text();
    const payload = text ? (() => {
      try {
        return JSON.parse(text);
      } catch {
        return { raw: text };
      }
    })() : {};
    if (!response.ok) {
      const message = payload.detail || payload.error || payload.message || JSON.stringify(payload);
      throw new Error(message);
    }
    return payload;
  };

  const postJson = async (url, body) => fetchJson(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body || {}),
  });

  const renderCards = (target, cards) => {
    target.innerHTML = cards.map((card) => `
      <article class="summary-card">
        <div class="summary-card__label">${escapeHtml(card.label)}</div>
        <div class="summary-card__value">${escapeHtml(card.value)}</div>
        <div class="summary-card__meta">${escapeHtml(card.meta || "")}</div>
      </article>
    `).join("");
  };

  const submitFormToObject = (form) => {
    const formData = new FormData(form);
    const payload = {};
    formData.forEach((value, key) => {
      payload[key] = value;
    });
    form.querySelectorAll("input[type=checkbox]").forEach((input) => {
      if (!input.name) return;
      payload[input.name] = input.checked;
    });
    return payload;
  };

  const asArray = (value) => Array.isArray(value) ? value : [];

  const parseMaybeJson = (value) => {
    if (!value) return {};
    if (typeof value === "object") return value;
    try {
      return JSON.parse(value);
    } catch {
      return {};
    }
  };

  const initPipelinePage = () => {
    const summaryCards = document.getElementById("pipeline-summary-cards");
    const runsTable = document.querySelector("#pipeline-runs-table tbody");
    const docsTable = document.querySelector("#pipeline-documents-table tbody");
    const detailPanel = document.getElementById("pipeline-document-detail-panel");
    const sourceSelect = document.getElementById("pipeline-source-id");
    const filterForm = document.getElementById("pipeline-doc-filter-form");

    const loadSources = async () => {
      const sources = await fetchJson("/api/admin/corpus/sources");
      sourceSelect.innerHTML = `<option value="">전체</option>` + sources.map((source) => `
        <option value="${escapeHtml(source.sourceId)}">${escapeHtml(source.sourceId)} (${escapeHtml(source.productName || "")})</option>
      `).join("");
    };

    const loadSummary = async () => {
      const dashboard = await fetchJson("/api/admin/pipeline/dashboard");
      renderCards(summaryCards, [
        { label: "등록 소스", value: dashboard.sourceCount ?? 0, meta: "활성/비활성 포함" },
        { label: "수집 문서", value: dashboard.activeDocumentCount ?? 0, meta: "is_active 기준" },
        { label: "생성 청크", value: dashboard.activeChunkCount ?? 0, meta: "문서 기준 집계" },
        { label: "추출 용어", value: dashboard.glossaryTermCount ?? 0, meta: "활성 용어" },
        { label: "최근 성공", value: dashboard.recentRunSuccessCount ?? 0, meta: "7일 기준" },
        { label: "최근 실패", value: dashboard.recentRunFailureCount ?? 0, meta: "7일 기준" },
      ]);
    };

    const loadRuns = async () => {
      const runs = await fetchJson("/api/admin/pipeline/runs?limit=30");
      runsTable.innerHTML = runs.map((run) => {
        const summary = parseMaybeJson(run.summaryJson);
        const processed = summary.document_count ?? summary.documents_discovered ?? summary.documents_persisted ?? 0;
        const hasError = !!run.errorMessage;
        return `
          <tr>
            <td class="mono-truncate">${escapeHtml(run.runId)}</td>
            <td>${escapeHtml(run.runType)}</td>
            <td>${formatDate(run.startedAt)}</td>
            <td>${formatDate(run.finishedAt)}</td>
            <td><span class="status-badge" data-status="${toStatus(run.runStatus)}">${escapeHtml(run.runStatus)}</span></td>
            <td>${escapeHtml(processed)}</td>
            <td>${hasError ? `<span class="status-badge" data-status="failed">있음</span>` : `<span class="status-badge" data-status="success">없음</span>`}</td>
          </tr>
        `;
      }).join("");
    };

    const loadDocuments = async (params = {}) => {
      const documents = await fetchJson(`/api/admin/corpus/documents${qs({ active_only: true, limit: 30, ...params })}`);
      docsTable.innerHTML = documents.map((document) => `
        <tr>
          <td class="mono-truncate">${escapeHtml(document.documentId)}</td>
          <td>${escapeHtml(document.title)}</td>
          <td>${escapeHtml(document.sourceId)}</td>
          <td>${escapeHtml(document.versionLabel || "-")}</td>
          <td>${escapeHtml(document.sectionCount ?? 0)}</td>
          <td>${escapeHtml(document.chunkCount ?? 0)}</td>
          <td><button class="button button--ghost" data-pipeline-doc-detail="${escapeHtml(document.documentId)}">상세</button></td>
        </tr>
      `).join("");
      docsTable.querySelectorAll("[data-pipeline-doc-detail]").forEach((button) => {
        button.addEventListener("click", () => loadDocumentDetail(button.dataset.pipelineDocDetail));
      });
    };

    const loadDocumentDetail = async (documentId) => {
      const [document, rawVsCleaned, chunks] = await Promise.all([
        fetchJson(`/api/admin/corpus/documents/${documentId}`),
        fetchJson(`/api/admin/corpus/documents/${documentId}/preview/raw-vs-cleaned`),
        fetchJson(`/api/admin/corpus/documents/${documentId}/chunks?limit=20`),
      ]);
      detailPanel.innerHTML = `
        <div class="table-title">문서 상세: ${escapeHtml(documentId)}</div>
        <div class="detail-grid" style="margin-top: 12px;">
          <div class="detail-card">
            <div class="detail-item__label">문서 메타</div>
            <div class="detail-item__value">${escapeHtml(document.title || "-")}<br>${escapeHtml(document.canonicalUrl || "-")}</div>
          </div>
          <div class="detail-card">
            <div class="detail-item__label">정제 전/후 길이</div>
            <div class="detail-item__value">raw ${escapeHtml((rawVsCleaned.rawText || "").length)} / cleaned ${escapeHtml((rawVsCleaned.cleanedText || "").length)}</div>
          </div>
        </div>
        <div class="table-title" style="margin-top: 12px;">청크 미리보기</div>
        <div class="code-surface">${escapeHtml(asArray(chunks).slice(0, 5).map((item) => `[${item.chunkId}] ${item.sectionPathText}\n${item.chunkText || ""}`).join("\n\n"))}</div>
      `;
    };

    document.querySelectorAll("[data-pipeline-run]").forEach((button) => {
      button.addEventListener("click", async () => {
        const runType = button.dataset.pipelineRun;
        const endpoint = runType === "full_ingest" ? "/api/admin/pipeline/full-ingest" : `/api/admin/pipeline/${runType}`;
        button.disabled = true;
        try {
          await postJson(endpoint, {});
          await Promise.all([loadSummary(), loadRuns()]);
        } catch (error) {
          alert(error.message);
        } finally {
          button.disabled = false;
        }
      });
    });

    filterForm.addEventListener("submit", async (event) => {
      event.preventDefault();
      const values = submitFormToObject(filterForm);
      await loadDocuments(values);
    });

    Promise.all([loadSources(), loadSummary(), loadRuns(), loadDocuments()]).catch((error) => alert(error.message));
  };

  const initSyntheticPage = () => {
    const methodsTable = document.querySelector("#synthetic-methods-table tbody");
    const methodSelect = document.getElementById("synthetic-method-code");
    const methodFilter = document.getElementById("synthetic-filter-method");
    const batchFilter = document.getElementById("synthetic-filter-batch");
    const batchesTable = document.querySelector("#synthetic-batches-table tbody");
    const statsCards = document.getElementById("synthetic-stats-cards");
    const queriesTable = document.querySelector("#synthetic-queries-table tbody");
    const queryDetailPanel = document.getElementById("synthetic-query-detail-panel");
    const runForm = document.getElementById("synthetic-run-form");
    const filterForm = document.getElementById("synthetic-query-filter-form");

    let methods = [];
    let batches = [];

    const fillMethodOptions = () => {
      const options = `<option value="">선택</option>` + methods.map((method) => `
        <option value="${escapeHtml(method.methodCode)}">${escapeHtml(method.methodCode)} - ${escapeHtml(method.methodName)}</option>
      `).join("");
      methodSelect.innerHTML = options;
      methodFilter.innerHTML = `<option value="">전체</option>` + methods.map((method) => `
        <option value="${escapeHtml(method.methodCode)}">${escapeHtml(method.methodCode)}</option>
      `).join("");
    };

    const fillBatchOptions = () => {
      batchFilter.innerHTML = `<option value="">전체</option>` + batches.map((batch) => `
        <option value="${escapeHtml(batch.batchId)}">${escapeHtml(batch.versionName)} (${escapeHtml(batch.methodCode)})</option>
      `).join("");
    };

    const loadMethods = async () => {
      methods = await fetchJson("/api/admin/console/synthetic/methods");
      methodsTable.innerHTML = methods.map((method) => `
        <tr>
          <td>${escapeHtml(method.methodCode)}</td>
          <td>${escapeHtml(method.methodName)}</td>
          <td>${escapeHtml(method.description || "-")}</td>
          <td>${escapeHtml(method.promptTemplateVersion || "-")}</td>
          <td>${boolBadge(method.active)}</td>
        </tr>
      `).join("");
      fillMethodOptions();
    };

    const loadBatches = async () => {
      batches = await fetchJson("/api/admin/console/synthetic/batches?limit=50");
      batchesTable.innerHTML = batches.map((batch) => `
        <tr>
          <td class="mono-truncate">${escapeHtml(batch.batchId)}</td>
          <td>${escapeHtml(batch.methodCode)}</td>
          <td>${escapeHtml(batch.versionName)}</td>
          <td><span class="status-badge" data-status="${toStatus(batch.status)}">${escapeHtml(batch.status)}</span></td>
          <td>${formatDate(batch.startedAt)}</td>
          <td>${formatDate(batch.finishedAt)}</td>
          <td>${escapeHtml(batch.totalGeneratedCount ?? 0)}</td>
        </tr>
      `).join("");
      fillBatchOptions();
    };

    const loadStats = async () => {
      const values = submitFormToObject(filterForm);
      const stats = await fetchJson(`/api/admin/console/synthetic/stats${qs({
        method_code: values.method_code || null,
        batch_id: values.batch_id || null,
      })}`);
      const byMethod = asArray(stats.byMethod);
      const byType = asArray(stats.byQueryType);
      const total = byMethod.reduce((sum, item) => sum + Number(item.count || 0), 0);
      renderCards(statsCards, [
        { label: "총 합성 질의", value: total, meta: "현재 필터 기준" },
        { label: "방식 수", value: byMethod.length, meta: byMethod.map((item) => `${item.method_code}:${item.count}`).join(" / ") || "-" },
        { label: "질의 유형 수", value: byType.length, meta: byType.map((item) => `${item.query_type}:${item.count}`).join(" / ") || "-" },
      ]);
    };

    const loadQueries = async () => {
      const values = submitFormToObject(filterForm);
      const queries = await fetchJson(`/api/admin/console/synthetic/queries${qs({
        method_code: values.method_code || null,
        batch_id: values.batch_id || null,
        query_type: values.query_type || null,
        gated: values.gated || null,
        limit: 50,
      })}`);
      queriesTable.innerHTML = queries.map((query) => `
        <tr>
          <td class="mono-truncate">${escapeHtml(query.queryId)}</td>
          <td>${escapeHtml(query.queryText)}</td>
          <td>${escapeHtml(query.queryType)}</td>
          <td>${escapeHtml(query.generationMethod)}</td>
          <td>${escapeHtml(query.generationBatchVersion || "-")}</td>
          <td>${query.gated ? `<span class="status-badge" data-status="success">통과</span>` : `<span class="status-badge" data-status="failed">미통과</span>`}</td>
          <td><button class="button button--ghost" data-synthetic-detail="${escapeHtml(query.queryId)}">상세</button></td>
        </tr>
      `).join("");
      queriesTable.querySelectorAll("[data-synthetic-detail]").forEach((button) => {
        button.addEventListener("click", async () => {
          const detail = await fetchJson(`/api/admin/console/synthetic/queries/${button.dataset.syntheticDetail}`);
          queryDetailPanel.innerHTML = `
            <div class="table-title">합성 질의 상세</div>
            <div class="detail-grid" style="margin-top: 12px;">
              <div class="detail-card">
                <div class="detail-item__label">질의</div>
                <div class="detail-item__value">${escapeHtml(detail.queryText)}</div>
              </div>
              <div class="detail-card">
                <div class="detail-item__label">방식 / 유형</div>
                <div class="detail-item__value">${escapeHtml(detail.generationMethod)} / ${escapeHtml(detail.queryType)}</div>
              </div>
            </div>
            <div class="table-title" style="margin-top: 12px;">원본 청크</div>
            <div class="code-surface">${escapeHtml(JSON.stringify(detail.sourceChunk, null, 2))}</div>
            <div class="table-title" style="margin-top: 12px;">raw output</div>
            <div class="code-surface">${escapeHtml(JSON.stringify(detail.rawOutput, null, 2))}</div>
          `;
        });
      });
    };

    runForm.addEventListener("submit", async (event) => {
      event.preventDefault();
      const values = submitFormToObject(runForm);
      const body = {
        methodCode: values.methodCode,
        versionName: values.versionName,
        sourceDocumentVersion: values.sourceDocumentVersion || null,
        limitChunks: values.limitChunks ? Number(values.limitChunks) : null,
      };
      try {
        await postJson("/api/admin/console/synthetic/batches/run", body);
        await Promise.all([loadBatches(), loadQueries(), loadStats()]);
      } catch (error) {
        alert(error.message);
      }
    });

    filterForm.addEventListener("submit", async (event) => {
      event.preventDefault();
      await Promise.all([loadQueries(), loadStats()]);
    });

    Promise.all([loadMethods(), loadBatches()]).then(() => Promise.all([loadQueries(), loadStats()])).catch((error) => alert(error.message));
  };

  const initGatingPage = () => {
    const methodSelect = document.getElementById("gating-method-code");
    const batchSelect = document.getElementById("gating-generation-batch-id");
    const runForm = document.getElementById("gating-run-form");
    const batchesTable = document.querySelector("#gating-batches-table tbody");
    const funnelCards = document.getElementById("gating-funnel-cards");
    const resultsTable = document.querySelector("#gating-results-table tbody");
    let currentBatchId = null;

    const loadSelectors = async () => {
      const [methods, generationBatches] = await Promise.all([
        fetchJson("/api/admin/console/synthetic/methods"),
        fetchJson("/api/admin/console/synthetic/batches?limit=100"),
      ]);
      methodSelect.innerHTML = methods.map((method) => `
        <option value="${escapeHtml(method.methodCode)}">${escapeHtml(method.methodCode)} - ${escapeHtml(method.methodName)}</option>
      `).join("");
      batchSelect.innerHTML = `<option value="">선택 안 함</option>` + generationBatches.map((batch) => `
        <option value="${escapeHtml(batch.batchId)}">${escapeHtml(batch.versionName)} (${escapeHtml(batch.methodCode)})</option>
      `).join("");
    };

    const loadFunnel = async (gatingBatchId) => {
      const funnel = await fetchJson(`/api/admin/console/gating/batches/${gatingBatchId}/funnel`);
      renderCards(funnelCards, [
        { label: "생성 총량", value: funnel.generatedTotal ?? 0 },
        { label: "Rule 통과", value: funnel.passedRule ?? 0 },
        { label: "LLM 통과", value: funnel.passedLlm ?? 0 },
        { label: "Utility 통과", value: funnel.passedUtility ?? 0 },
        { label: "Diversity 통과", value: funnel.passedDiversity ?? 0 },
        { label: "최종 승인", value: funnel.finalAccepted ?? 0 },
      ]);
    };

    const loadResults = async (gatingBatchId) => {
      const results = await fetchJson(`/api/admin/console/gating/batches/${gatingBatchId}/results?limit=100`);
      resultsTable.innerHTML = results.map((row) => `
        <tr>
          <td class="mono-truncate">${escapeHtml(row.syntheticQueryId)}</td>
          <td>${escapeHtml(row.queryText)}</td>
          <td>${escapeHtml(row.queryType)}</td>
          <td>${boolBadge(row.passedRule)}</td>
          <td>${boolBadge(row.passedLlm)}</td>
          <td>${boolBadge(row.passedUtility)}</td>
          <td>${boolBadge(row.passedDiversity)}</td>
          <td>${escapeHtml(row.finalScore == null ? "-" : Number(row.finalScore).toFixed(4))}</td>
          <td>${row.finalDecision ? `<span class="status-badge" data-status="success">승인</span>` : `<span class="status-badge" data-status="failed">탈락</span>`}</td>
        </tr>
      `).join("");
    };

    const loadBatches = async () => {
      const batches = await fetchJson("/api/admin/console/gating/batches?limit=50");
      batchesTable.innerHTML = batches.map((batch) => {
        const survival = batch.processedCount > 0 ? ((batch.acceptedCount / batch.processedCount) * 100).toFixed(1) : "0.0";
        return `
          <tr>
            <td class="mono-truncate">${escapeHtml(batch.gatingBatchId)}</td>
            <td>${escapeHtml(batch.gatingPreset)}</td>
            <td>${escapeHtml(batch.methodCode || "-")}</td>
            <td><span class="status-badge" data-status="${toStatus(batch.status)}">${escapeHtml(batch.status)}</span></td>
            <td>${escapeHtml(batch.processedCount ?? 0)}</td>
            <td>${escapeHtml(batch.acceptedCount ?? 0)}</td>
            <td>${survival}%</td>
            <td><button class="button button--ghost" data-gating-batch="${escapeHtml(batch.gatingBatchId)}">조회</button></td>
          </tr>
        `;
      }).join("");
      batchesTable.querySelectorAll("[data-gating-batch]").forEach((button) => {
        button.addEventListener("click", async () => {
          currentBatchId = button.dataset.gatingBatch;
          await Promise.all([loadFunnel(currentBatchId), loadResults(currentBatchId)]);
        });
      });
      if (!currentBatchId && batches.length > 0) {
        currentBatchId = batches[0].gatingBatchId;
        await Promise.all([loadFunnel(currentBatchId), loadResults(currentBatchId)]);
      }
    };

    runForm.addEventListener("submit", async (event) => {
      event.preventDefault();
      const values = submitFormToObject(runForm);
      const body = {
        methodCode: values.methodCode,
        generationBatchId: values.generationBatchId || null,
        gatingPreset: values.gatingPreset,
        enableRuleFilter: !!values.enableRuleFilter,
        enableLlmSelfEval: !!values.enableLlmSelfEval,
        enableRetrievalUtility: !!values.enableRetrievalUtility,
        enableDiversity: !!values.enableDiversity,
      };
      try {
        const batch = await postJson("/api/admin/console/gating/batches/run", body);
        currentBatchId = batch.gatingBatchId;
        await loadBatches();
      } catch (error) {
        alert(error.message);
      }
    });

    Promise.all([loadSelectors(), loadBatches()]).catch((error) => alert(error.message));
  };

  const initRagTestsPage = () => {
    const methodChecks = document.getElementById("rag-method-checks");
    const datasetSelect = document.getElementById("rag-dataset-id");
    const datasetsTable = document.querySelector("#rag-datasets-table tbody");
    const datasetItemsPanel = document.getElementById("rag-dataset-items-panel");
    const testsTable = document.querySelector("#rag-tests-table tbody");
    const summaryCards = document.getElementById("rag-summary-cards");
    const detailsTable = document.querySelector("#rag-details-table tbody");
    const runForm = document.getElementById("rag-test-run-form");

    const loadMethods = async () => {
      const methods = await fetchJson("/api/admin/console/synthetic/methods");
      methodChecks.innerHTML = methods.map((method, index) => `
        <label class="plain-badge">
          <input type="checkbox" name="methodCodes" value="${escapeHtml(method.methodCode)}" ${index === 0 ? "checked" : ""}>
          ${escapeHtml(method.methodCode)}
        </label>
      `).join("");
    };

    const loadDatasets = async () => {
      const datasets = await fetchJson("/api/admin/console/rag/datasets");
      datasetSelect.innerHTML = datasets.map((dataset, index) => `
        <option value="${escapeHtml(dataset.datasetId)}" ${index === 0 ? "selected" : ""}>${escapeHtml(dataset.datasetName)} (${escapeHtml(dataset.totalItems)})</option>
      `).join("");
      datasetsTable.innerHTML = datasets.map((dataset) => `
        <tr>
          <td class="mono-truncate">${escapeHtml(dataset.datasetId)}</td>
          <td>${escapeHtml(dataset.datasetName)}</td>
          <td>${escapeHtml(dataset.version || "-")}</td>
          <td>${escapeHtml(dataset.totalItems ?? 0)}</td>
          <td>${formatDate(dataset.createdAt)}</td>
          <td><button class="button button--ghost" data-dataset-items="${escapeHtml(dataset.datasetId)}">문항 보기</button></td>
        </tr>
      `).join("");
      datasetsTable.querySelectorAll("[data-dataset-items]").forEach((button) => {
        button.addEventListener("click", async () => {
          const items = await fetchJson(`/api/admin/console/rag/datasets/${button.dataset.datasetItems}/items?limit=30`);
          datasetItemsPanel.innerHTML = `
            <div class="table-title">데이터셋 문항 미리보기 (${escapeHtml(items.length)})</div>
            <div class="code-surface">${escapeHtml(items.map((item) => `[${item.sampleId}] ${item.queryCategory} - ${item.userQueryKo}`).join("\n"))}</div>
          `;
        });
      });
    };

    const loadTests = async () => {
      const tests = await fetchJson("/api/admin/console/rag/tests?limit=50");
      testsTable.innerHTML = tests.map((test) => `
        <tr>
          <td class="mono-truncate">${escapeHtml(test.ragTestRunId)}</td>
          <td><span class="status-badge" data-status="${toStatus(test.status)}">${escapeHtml(test.status)}</span></td>
          <td>${escapeHtml(test.datasetName || "-")}</td>
          <td>${escapeHtml((test.generationMethodCodes || []).join(", ") || "-")}</td>
          <td>${boolBadge(test.gatingApplied)}</td>
          <td>${escapeHtml(test.selectiveRewrite ? "selective" : (test.rewriteEnabled ? "always/off" : "off"))}</td>
          <td>${escapeHtml(JSON.stringify(test.metricsJson || {}))}</td>
          <td><button class="button button--ghost" data-rag-detail="${escapeHtml(test.ragTestRunId)}">조회</button></td>
        </tr>
      `).join("");
      testsTable.querySelectorAll("[data-rag-detail]").forEach((button) => {
        button.addEventListener("click", () => loadRunDetail(button.dataset.ragDetail));
      });
    };

    const loadRunDetail = async (runId) => {
      const detail = await fetchJson(`/api/admin/console/rag/tests/${runId}?detail_limit=100`);
      const summary = detail.summary || {};
      renderCards(summaryCards, [
        { label: "Recall@5", value: summary.recall_at_5 == null ? "-" : Number(summary.recall_at_5).toFixed(4) },
        { label: "Hit@5", value: summary.hit_at_5 == null ? "-" : Number(summary.hit_at_5).toFixed(4) },
        { label: "MRR@10", value: summary.mrr_at_10 == null ? "-" : Number(summary.mrr_at_10).toFixed(4) },
        { label: "nDCG@10", value: summary.ndcg_at_10 == null ? "-" : Number(summary.ndcg_at_10).toFixed(4) },
        { label: "Latency(ms)", value: summary.latency_avg_ms == null ? "-" : Number(summary.latency_avg_ms).toFixed(2) },
        { label: "Rewrite 수용률", value: summary.rewrite_acceptance_rate == null ? "-" : `${(Number(summary.rewrite_acceptance_rate) * 100).toFixed(1)}%` },
      ]);
      detailsTable.innerHTML = asArray(detail.details).map((row) => `
        <tr>
          <td>${escapeHtml(row.sampleId)}</td>
          <td>${escapeHtml(row.queryCategory || "-")}</td>
          <td>${escapeHtml(row.rawQuery || "-")}</td>
          <td>${escapeHtml(row.rewriteQuery || "-")}</td>
          <td>${boolBadge(row.rewriteApplied)}</td>
          <td>${boolBadge(row.hitTarget)}</td>
          <td><div class="line-clamp-2">${escapeHtml(JSON.stringify(row.metricContribution || {}))}</div></td>
        </tr>
      `).join("");
    };

    runForm.addEventListener("submit", async (event) => {
      event.preventDefault();
      const values = submitFormToObject(runForm);
      const selectedMethods = Array.from(methodChecks.querySelectorAll("input[name=methodCodes]:checked")).map((node) => node.value);
      if (selectedMethods.length === 0) {
        alert("최소 1개 생성 방식을 선택하세요.");
        return;
      }
      const body = {
        datasetId: values.datasetId,
        methodCodes: selectedMethods,
        gatingPreset: values.gatingPreset,
        gatingApplied: !!values.gatingApplied,
        rewriteEnabled: !!values.rewriteEnabled,
        selectiveRewrite: !!values.selectiveRewrite,
        useSessionContext: !!values.useSessionContext,
        threshold: values.threshold ? Number(values.threshold) : null,
        retrievalTopK: values.retrievalTopK ? Number(values.retrievalTopK) : null,
        rerankTopN: values.rerankTopN ? Number(values.rerankTopN) : null,
      };
      try {
        const run = await postJson("/api/admin/console/rag/tests/run", body);
        await loadTests();
        await loadRunDetail(run.ragTestRunId);
      } catch (error) {
        alert(error.message);
      }
    });

    Promise.all([loadMethods(), loadDatasets(), loadTests()]).catch((error) => alert(error.message));
  };

  if (page === "pipeline") initPipelinePage();
  if (page === "synthetic") initSyntheticPage();
  if (page === "gating") initGatingPage();
  if (page === "rag-tests") initRagTestsPage();
})();

