import { useEffect, useState } from 'react'
import { DetailCard, IdBadge, Modal, StatusBadge } from '../components/Common.jsx'
import { LlmJobsTable } from '../components/LlmJobsTable.jsx'
import { requestJson, toNumber } from '../lib/api.js'
import { fmtTime, shortId } from '../lib/format.js'
import { usePolling } from '../lib/hooks.js'

export function RagPage({ notify }) {
  const [methods, setMethods] = useState([])
  const [datasets, setDatasets] = useState([])
  const [tests, setTests] = useState([])
  const [gatingBatches, setGatingBatches] = useState([])
  const [rewriteLogs, setRewriteLogs] = useState([])
  const [llmJobs, setLlmJobs] = useState([])
  const [modal, setModal] = useState(null)
  const [selectedMethods, setSelectedMethods] = useState([])

  const [form, setForm] = useState({
    datasetId: '',
    gatingPreset: 'full_gating',
    sourceGatingBatchId: '',
    threshold: '0.05',
    retrievalTopK: '20',
    rerankTopN: '5',
    gatingApplied: true,
    rewriteEnabled: true,
    selectiveRewrite: true,
    useSessionContext: false,
  })

  const loadMethods = async () => {
    const rows = await requestJson('/api/admin/console/synthetic/methods')
    const normalized = Array.isArray(rows) ? rows : []
    setMethods(normalized)
    if (normalized.length > 0 && selectedMethods.length === 0) setSelectedMethods([normalized[0].methodCode])
  }

  const loadDatasets = async () => {
    const rows = await requestJson('/api/admin/console/rag/datasets')
    const normalized = Array.isArray(rows) ? rows : []
    setDatasets(normalized)
    setForm((prev) => ({ ...prev, datasetId: prev.datasetId || normalized[0]?.datasetId || '' }))
  }

  const loadTests = async () => {
    const rows = await requestJson('/api/admin/console/rag/tests?limit=50')
    setTests(Array.isArray(rows) ? rows : [])
  }

  const loadGatingBatches = async () => {
    const rows = await requestJson('/api/admin/console/gating/batches?limit=100')
    setGatingBatches(Array.isArray(rows) ? rows : [])
  }

  const loadRewriteLogs = async () => {
    const rows = await requestJson('/api/admin/console/rewrite/logs?limit=100')
    setRewriteLogs(Array.isArray(rows) ? rows : [])
  }

  const loadLlmJobs = async () => {
    const rows = await requestJson('/api/admin/console/llm-jobs?limit=120')
    const filtered = (Array.isArray(rows) ? rows : []).filter((job) => job.jobType === 'RUN_RAG_TEST' || job.ragTestRunId)
    setLlmJobs(filtered)
  }

  useEffect(() => {
    Promise.all([loadMethods(), loadDatasets(), loadTests(), loadGatingBatches(), loadRewriteLogs(), loadLlmJobs()]).catch((error) => notify(error.message, 'error'))
  }, [])

  usePolling(true, 5000, async () => {
    try {
      await loadLlmJobs()
    } catch {
      // ignore polling errors
    }
  })

  const runRag = async (event) => {
    event.preventDefault()
    if (selectedMethods.length === 0) {
      notify('최소 1개 생성 방식을 선택해주세요.', 'error')
      return
    }
    const runGatingPreset = form.gatingApplied ? form.gatingPreset : 'ungated'
    try {
      const created = await requestJson('/api/admin/console/rag/tests/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          datasetId: form.datasetId,
          methodCodes: selectedMethods,
          gatingPreset: runGatingPreset,
          sourceGatingBatchId: form.sourceGatingBatchId || null,
          gatingApplied: Boolean(form.gatingApplied),
          rewriteEnabled: Boolean(form.rewriteEnabled),
          selectiveRewrite: Boolean(form.selectiveRewrite),
          useSessionContext: Boolean(form.useSessionContext),
          threshold: toNumber(form.threshold),
          retrievalTopK: toNumber(form.retrievalTopK),
          rerankTopN: toNumber(form.rerankTopN),
        }),
      })
      await Promise.all([loadTests(), loadRewriteLogs(), loadLlmJobs()])
      notify('RAG 테스트를 실행했습니다.')
      openRunDetail(created.ragTestRunId)
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const executeLlmAction = async (jobId, action) => {
    try {
      await requestJson(`/api/admin/console/llm-jobs/${jobId}/${action}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })
      await loadLlmJobs()
      notify(`JOB ${action} 요청을 전송했습니다.`)
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const openJobDetail = async (jobId) => {
    try {
      const [job, items] = await Promise.all([
        requestJson(`/api/admin/console/llm-jobs/${jobId}`),
        requestJson(`/api/admin/console/llm-jobs/${jobId}/items`),
      ])
      setModal({
        title: `LLM JOB 상세 · ${shortId(jobId)}`,
        body: (
          <div className="detail-grid detail-grid--single">
            <DetailCard label="커맨드 / 실험" value={`${job.commandName || '-'} / ${job.experimentName || '-'}`} />
            <DetailCard label="진행률" value={`${job.processedItems ?? 0} / ${job.totalItems ?? 0} (${job.progressPct == null ? '-' : `${Number(job.progressPct).toFixed(1)}%`})`} />
            <DetailCard label="job_items" value={JSON.stringify(items || [], null, 2)} />
          </div>
        ),
      })
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const effectiveGatingPreset = form.gatingApplied ? form.gatingPreset : 'ungated'
  const snapshotBatches = gatingBatches.filter((batch) => {
    if (!batch || String(batch.status || '').toLowerCase() !== 'completed') return false
    if (!batch.sourceGatingRunId) return false
    if (batch.gatingPreset !== effectiveGatingPreset) return false
    if (selectedMethods.length === 0) return true
    if (!batch.methodCode) return true
    return selectedMethods.includes(String(batch.methodCode).toUpperCase())
  })

  useEffect(() => {
    if (!form.sourceGatingBatchId) return
    const valid = snapshotBatches.some((batch) => batch.gatingBatchId === form.sourceGatingBatchId)
    if (valid) return
    setForm((prev) => ({ ...prev, sourceGatingBatchId: '' }))
  }, [form.sourceGatingBatchId, snapshotBatches])

  const openDatasetItems = async (datasetId) => {
    try {
      const rows = await requestJson(`/api/admin/console/rag/datasets/${datasetId}/items?limit=30`)
      setModal({
        title: `평가 문항 미리보기 · ${shortId(datasetId)}`,
        body: <DetailCard label="samples" value={(Array.isArray(rows) ? rows : []).map((row) => `[${row.sampleId}] ${row.queryCategory} - ${row.userQueryKo}`).join('\n')} />,
      })
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const openRunDetail = async (runId) => {
    try {
      const payload = await requestJson(`/api/admin/console/rag/tests/${runId}?detail_limit=100`)
      const summary = payload.summary || {}
      const details = Array.isArray(payload.details) ? payload.details : []
      setModal({
        title: `RAG 실행 상세 · ${shortId(runId)}`,
        body: (
          <div className="detail-grid detail-grid--single">
            <div className="summary-grid">
              <article className="summary-card"><div className="summary-card__label">Recall@5</div><div className="summary-card__value">{summary.recall_at_5 == null ? '-' : Number(summary.recall_at_5).toFixed(4)}</div></article>
              <article className="summary-card"><div className="summary-card__label">Hit@5</div><div className="summary-card__value">{summary.hit_at_5 == null ? '-' : Number(summary.hit_at_5).toFixed(4)}</div></article>
              <article className="summary-card"><div className="summary-card__label">MRR@10</div><div className="summary-card__value">{summary.mrr_at_10 == null ? '-' : Number(summary.mrr_at_10).toFixed(4)}</div></article>
              <article className="summary-card"><div className="summary-card__label">nDCG@10</div><div className="summary-card__value">{summary.ndcg_at_10 == null ? '-' : Number(summary.ndcg_at_10).toFixed(4)}</div></article>
            </div>
            <DetailCard label="detail_rows" value={JSON.stringify(details, null, 2)} />
          </div>
        ),
      })
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const openRewriteDetail = async (rewriteLogId) => {
    try {
      const payload = await requestJson(`/api/admin/console/rewrite/logs/${rewriteLogId}`)
      setModal({
        title: `Rewrite 로그 상세 · ${shortId(rewriteLogId)}`,
        body: (
          <div className="detail-grid detail-grid--single">
            <DetailCard label="raw / final" value={`${payload.rewrite?.rawQuery || '-'}\n${payload.rewrite?.finalQuery || '-'}`} />
            <DetailCard label="결정" value={`${payload.rewrite?.decisionReason || '-'} / delta=${payload.rewrite?.confidenceDelta == null ? '-' : Number(payload.rewrite.confidenceDelta).toFixed(4)}`} />
            <DetailCard label="memory_retrievals" value={JSON.stringify(payload.memoryRetrievals || [], null, 2)} />
            <DetailCard label="candidate_logs" value={JSON.stringify(payload.candidateLogs || [], null, 2)} />
          </div>
        ),
      })
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  return (
    <>
      <section className="panel">
        <div className="table-title">테스트 실행</div>
        <form className="filter-bar" onSubmit={runRag}>
          <label className="filter-field">평가 데이터셋
            <select value={form.datasetId} onChange={(event) => setForm((prev) => ({ ...prev, datasetId: event.target.value }))}>
              {datasets.map((dataset) => <option key={dataset.datasetId} value={dataset.datasetId}>{dataset.datasetName} ({dataset.totalItems})</option>)}
            </select>
          </label>
          <label className="filter-field">게이팅 프리셋
            <select value={form.gatingPreset} onChange={(event) => setForm((prev) => ({ ...prev, gatingPreset: event.target.value }))}>
              <option value="ungated">ungated</option><option value="rule_only">rule_only</option><option value="rule_plus_llm">rule_plus_llm</option><option value="full_gating">full_gating</option>
            </select>
          </label>
          <label className="filter-field">Gating Snapshot
            <select value={form.sourceGatingBatchId} onChange={(event) => setForm((prev) => ({ ...prev, sourceGatingBatchId: event.target.value }))}>
              <option value="">Auto (latest matching)</option>
              {snapshotBatches.map((batch) => (
                <option key={batch.gatingBatchId} value={batch.gatingBatchId}>
                  {`${shortId(batch.gatingBatchId)} | ${batch.gatingPreset} | ${batch.methodCode || '-'} | ${fmtTime(batch.finishedAt)}`}
                </option>
              ))}
            </select>
          </label>
          <label className="filter-field filter-field--small">Rewrite Threshold<input type="number" min="0" max="1" step="0.01" value={form.threshold} onChange={(event) => setForm((prev) => ({ ...prev, threshold: event.target.value }))} /></label>
          <label className="filter-field filter-field--small">Retrieval Top-K<input type="number" min="1" value={form.retrievalTopK} onChange={(event) => setForm((prev) => ({ ...prev, retrievalTopK: event.target.value }))} /></label>
          <label className="filter-field filter-field--small">Rerank Top-N<input type="number" min="1" value={form.rerankTopN} onChange={(event) => setForm((prev) => ({ ...prev, rerankTopN: event.target.value }))} /></label>
          <div className="checkbox-row">
            <label><input type="checkbox" checked={form.gatingApplied} onChange={(event) => setForm((prev) => ({ ...prev, gatingApplied: event.target.checked }))} /> 게이팅 반영</label>
            <label><input type="checkbox" checked={form.rewriteEnabled} onChange={(event) => setForm((prev) => ({ ...prev, rewriteEnabled: event.target.checked }))} /> Rewrite 사용</label>
            <label><input type="checkbox" checked={form.selectiveRewrite} onChange={(event) => setForm((prev) => ({ ...prev, selectiveRewrite: event.target.checked }))} /> Selective</label>
            <label><input type="checkbox" checked={form.useSessionContext} onChange={(event) => setForm((prev) => ({ ...prev, useSessionContext: event.target.checked }))} /> Session Context</label>
          </div>
          <div className="method-row">
            {methods.map((method) => (
              <label key={method.methodCode} className="plain-badge">
                <input
                  type="checkbox"
                  checked={selectedMethods.includes(method.methodCode)}
                  onChange={(event) => {
                    setSelectedMethods((prev) => {
                      if (event.target.checked) return prev.includes(method.methodCode) ? prev : [...prev, method.methodCode]
                      return prev.filter((value) => value !== method.methodCode)
                    })
                  }}
                />
                {method.methodCode}
              </label>
            ))}
          </div>
          <div className="filter-field filter-field--small"><button type="submit" className="button button--primary">테스트 실행</button></div>
        </form>
      </section>

      <section className="table-shell">
        <div className="table-header"><div className="table-title">평가 데이터셋</div></div>
        <div className="table-wrap">
          <table className="data-table">
            <thead><tr><th>데이터셋 ID</th><th>이름</th><th>버전</th><th>문항 수</th><th>생성 일시</th><th>상세</th></tr></thead>
            <tbody>
              {datasets.map((dataset) => (
                <tr key={dataset.datasetId}>
                  <td><IdBadge value={dataset.datasetId} /></td><td>{dataset.datasetName}</td><td>{dataset.version || '-'}</td><td>{dataset.totalItems ?? 0}</td><td>{fmtTime(dataset.createdAt)}</td>
                  <td><button type="button" className="button button--ghost" onClick={() => openDatasetItems(dataset.datasetId)}>상세 조회</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="table-shell">
        <div className="table-header"><div className="table-title">RAG 테스트 실행 이력</div><button type="button" className="button" onClick={() => Promise.all([loadTests(), loadRewriteLogs(), loadLlmJobs()]).catch((error) => notify(error.message, 'error'))}>새로고침</button></div>
        <div className="table-wrap">
          <table className="data-table">
            <thead><tr><th>실행 ID</th><th>상태</th><th>데이터셋</th><th>생성 방식</th><th>게이팅</th><th>Rewrite</th><th>지표 요약</th><th>상세</th></tr></thead>
            <tbody>
              {tests.map((run) => (
                <tr key={run.ragTestRunId}>
                  <td><IdBadge value={run.ragTestRunId} /></td><td><StatusBadge value={run.status} /></td><td>{run.datasetName || '-'}</td>
                  <td>{Array.isArray(run.generationMethodCodes) ? run.generationMethodCodes.join(', ') : '-'}</td>
                  <td>{run.gatingApplied ? <StatusBadge value="success" label="적용" /> : <StatusBadge value="failed" label="미적용" />}</td>
                  <td>{run.rewriteEnabled ? (run.selectiveRewrite ? 'selective' : 'always') : 'off'}</td>
                  <td className="line-clamp">{JSON.stringify(run.metricsJson || {})}</td>
                  <td><button type="button" className="button button--ghost" onClick={() => openRunDetail(run.ragTestRunId)}>상세 조회</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <LlmJobsTable jobs={llmJobs} onAction={executeLlmAction} onDetail={openJobDetail} />

      <section className="table-shell">
        <div className="table-header"><div className="table-title">Rewrite 디버그 로그</div></div>
        <div className="table-wrap">
          <table className="data-table">
            <thead><tr><th>rewrite_log_id</th><th>raw_query</th><th>final_query</th><th>전략</th><th>적용</th><th>delta</th><th>결정 사유</th><th>상세</th></tr></thead>
            <tbody>
              {rewriteLogs.map((row) => (
                <tr key={row.rewriteLogId}>
                  <td><IdBadge value={row.rewriteLogId} /></td><td>{row.rawQuery || '-'}</td><td>{row.finalQuery || '-'}</td><td>{row.rewriteStrategy || '-'}</td>
                  <td>{row.rewriteApplied ? <StatusBadge value="success" label="적용" /> : <StatusBadge value="failed" label="미적용" />}</td>
                  <td>{row.confidenceDelta == null ? '-' : Number(row.confidenceDelta).toFixed(4)}</td><td>{row.decisionReason || row.rejectionReason || '-'}</td>
                  <td><button type="button" className="button button--ghost" onClick={() => openRewriteDetail(row.rewriteLogId)}>상세 조회</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <Modal data={modal} onClose={() => setModal(null)} />
    </>
  )
}
