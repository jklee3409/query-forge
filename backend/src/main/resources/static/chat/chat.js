(() => {
  const result = document.getElementById("result");
  const askBtn = document.getElementById("askBtn");
  const reindexBtn = document.getElementById("reindexBtn");
  const queryInput = document.getElementById("queryInput");

  const render = (payload) => {
    const rewriteRows = (payload.rewriteCandidates || [])
      .map(
        (candidate, index) =>
          `${index + 1}. [${candidate.label}] conf=${candidate.confidenceScore.toFixed(4)} adopted=${candidate.adopted}\n   ${candidate.candidateQuery}`
      )
      .join("\n");

    const retrievedRows = (payload.retrievedDocs || [])
      .slice(0, 5)
      .map(
        (doc, index) =>
          `${index + 1}. score=${doc.score.toFixed(4)} ${doc.documentId} / ${doc.chunkId}\n   ${doc.chunkTextPreview}`
      )
      .join("\n");

    const rerankedRows = (payload.rerankedDocs || [])
      .slice(0, 5)
      .map(
        (doc, index) =>
          `${index + 1}. score=${doc.score.toFixed(4)} ${doc.documentId} / ${doc.chunkId}\n   ${doc.chunkTextPreview}`
      )
      .join("\n");

    result.innerHTML = `
      <h3 class="result__title">Answer</h3>
      <div>${payload.answer || ""}</div>
      <h3 class="result__title">Trace</h3>
      <div><span class="mono">queryId=${payload.onlineQueryId}</span></div>
      <div>rawQuery: ${payload.rawQuery}</div>
      <div>finalQueryUsed: ${payload.finalQueryUsed}</div>
      <div>rewriteApplied: ${payload.rewriteApplied}</div>
      <div>latency: <span class="mono">${JSON.stringify(payload.latencyBreakdown || {})}</span></div>
      <h3 class="result__title">Rewrite Candidates</h3>
      <div>${rewriteRows || "(none)"}</div>
      <h3 class="result__title">Retrieved Top Chunks</h3>
      <div>${retrievedRows || "(none)"}</div>
      <h3 class="result__title">Reranked Top Chunks</h3>
      <div>${rerankedRows || "(none)"}</div>
    `;
  };

  askBtn?.addEventListener("click", async () => {
    const query = queryInput?.value?.trim();
    if (!query) {
      return;
    }
    askBtn.disabled = true;
    askBtn.textContent = "처리 중...";
    try {
      const response = await fetch("/api/chat/ask", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          query,
          mode: document.getElementById("mode").value,
          rewriteThreshold: Number(document.getElementById("threshold").value),
          gatingPreset: document.getElementById("gatingPreset").value,
        }),
      });
      const payload = await response.json();
      if (!response.ok) {
        throw new Error(payload.error || "요청 실패");
      }
      render(payload);
    } catch (error) {
      result.textContent = `오류: ${error.message}`;
    } finally {
      askBtn.disabled = false;
      askBtn.textContent = "질문하기";
    }
  });

  reindexBtn?.addEventListener("click", async () => {
    reindexBtn.disabled = true;
    reindexBtn.textContent = "재색인 중...";
    try {
      const response = await fetch("/api/admin/reindex", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ reindexChunks: true, reindexMemory: true }),
      });
      const payload = await response.json();
      if (!response.ok) {
        throw new Error(payload.error || "재색인 실패");
      }
      alert(`완료: chunk=${payload.chunkEmbeddingsUpdated}, memory=${payload.memoryEmbeddingsUpdated}`);
    } catch (error) {
      alert(`오류: ${error.message}`);
    } finally {
      reindexBtn.disabled = false;
      reindexBtn.textContent = "임베딩 재색인";
    }
  });
})();

