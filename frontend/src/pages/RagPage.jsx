import { useEffect, useMemo, useState } from 'react'
import { DetailCard, IdBadge, Modal, StatusBadge } from '../components/Common.jsx'
import { LlmJobsTable } from '../components/LlmJobsTable.jsx'
import { requestJson, toNumber } from '../lib/api.js'
import { fmtTime, shortId } from '../lib/format.js'
import { usePolling } from '../lib/hooks.js'

const METRIC_DEFS = [
  { key: 'recall_at_5', label: 'Recall@5', max: 1 },
  { key: 'hit_at_5', label: 'Hit@5', max: 1 },
  { key: 'mrr_at_10', label: 'MRR@10', max: 1 },
  { key: 'ndcg_at_10', label: 'nDCG@10', max: 1 },
  { key: 'answer_relevance', label: 'Answer Relevance', max: 1 },
  { key: 'faithfulness', label: 'Faithfulness', max: 1 },
  { key: 'context_recall', label: 'Context Recall', max: 1 },
]

function parseMetricsNode(value) {
  if (!value) return {}
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value)
      return parsed && typeof parsed === 'object' ? parsed : {}
    } catch {
      return {}
    }
  }
  return typeof value === 'object' ? value : {}
}

function toMetricNumber(value) {
  if (value == null) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

function firstMetricNumber(values) {
  for (const value of values) {
    const parsed = toMetricNumber(value)
    if (parsed != null) return parsed
  }
  return null
}

function extractRunMetrics(metricsJson) {
  const payload = parseMetricsNode(metricsJson)
  const summaryRaw = payload.summary
  const summary = Array.isArray(summaryRaw)
    ? summaryRaw.find((row) => String(row?.mode || '').includes('selective')) || summaryRaw[0] || {}
    : (summaryRaw && typeof summaryRaw === 'object' ? summaryRaw : {})
  return {
    recall_at_5: firstMetricNumber([payload.recall_at_5, payload.recallAt5, summary.recall_at_5, summary['recall@5']]),
    hit_at_5: firstMetricNumber([payload.hit_at_5, payload.hitAt5, summary.hit_at_5, summary['hit@5']]),
    mrr_at_10: firstMetricNumber([payload.mrr_at_10, payload.mrrAt10, summary.mrr_at_10, summary['mrr@10']]),
    ndcg_at_10: firstMetricNumber([payload.ndcg_at_10, payload.ndcgAt10, summary.ndcg_at_10, summary['ndcg@10']]),
    answer_relevance: firstMetricNumber([payload.answer_relevance, summary.answer_relevance]),
    faithfulness: firstMetricNumber([payload.faithfulness, summary.faithfulness]),
    context_recall: firstMetricNumber([payload.context_recall, summary.context_recall]),
  }
}

function formatMetric(value) {
  if (value == null) return '-'
  return Number(value).toFixed(4)
}

export function RagPage({ notify }) {
  const [methods, setMethods] = useState([])
  const [datasets, setDatasets] = useState([])
  const [tests, setTests] = useState([])
  const [gatingBatches, setGatingBatches] = useState([])
  const [rewriteLogs, setRewriteLogs] = useState([])
  const [llmJobs, setLlmJobs] = useState([])
  const [modal, setModal] = useState(null)
  const [selectedMethods, setSelectedMethods] = useState([])
  const [compareRunIds, setCompareRunIds] = useState([])

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
    if (normalized.length > 0 && selectedMethods.length === 0) {
      setSelectedMethods([normalized[0].methodCode])
    }
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
      await Promise.all([loadLlmJobs(), loadGatingBatches()])
    } catch {
      // ignore polling errors
    }
  })

  const selectedSnapshot = useMemo(
    () => gatingBatches.find((batch) => batch.gatingBatchId === form.sourceGatingBatchId) || null,
    [gatingBatches, form.sourceGatingBatchId],
  )
  const snapshotMethodCode = selectedSnapshot?.methodCode ? String(selectedSnapshot.methodCode).toUpperCase() : null
  const methodSelectionLocked = Boolean(form.sourceGatingBatchId && snapshotMethodCode)

  useEffect(() => {
    if (!methodSelectionLocked || !snapshotMethodCode) return
    if (selectedMethods.length === 1 && selectedMethods[0] === snapshotMethodCode) return
    setSelectedMethods([snapshotMethodCode])
  }, [methodSelectionLocked, snapshotMethodCode, selectedMethods])

  useEffect(() => {
    if (!form.rewriteEnabled && (form.selectiveRewrite || form.useSessionContext)) {
      setForm((prev) => ({ ...prev, selectiveRewrite: false, useSessionContext: false }))
    }
    if (form.rewriteEnabled && !form.selectiveRewrite && form.useSessionContext) {
      setForm((prev) => ({ ...prev, useSessionContext: false }))
    }
  }, [form.rewriteEnabled, form.selectiveRewrite, form.useSessionContext])

  const effectiveGatingPreset = form.gatingApplied ? form.gatingPreset : 'ungated'
  const snapshotBatches = useMemo(
    () => gatingBatches.filter((batch) => batch && String(batch.status || '').toLowerCase() === 'completed'),
    [gatingBatches],
  )
  const methodCodesForRun = methodSelectionLocked && snapshotMethodCode ? [snapshotMethodCode] : selectedMethods

  useEffect(() => {
    if (!form.sourceGatingBatchId) return
    const exists = gatingBatches.some((batch) => batch.gatingBatchId === form.sourceGatingBatchId)
    if (exists) return
    setForm((prev) => ({ ...prev, sourceGatingBatchId: '' }))
  }, [form.sourceGatingBatchId, gatingBatches])

  useEffect(() => {
    setCompareRunIds((prev) => {
      const next = prev.filter((id) => tests.some((run) => run.ragTestRunId === id)).slice(0, 2)
      if (next.length === prev.length && next.every((item, index) => item === prev[index])) return prev
      return next
    })
  }, [tests])

  function isSnapshotCompatible(batch, gatingPreset, methodCodes) {
    if (!batch) return false
    if (batch.gatingPreset !== gatingPreset) return false
    if (!Array.isArray(methodCodes) || methodCodes.length === 0) return true
    if (!batch.methodCode) return true
    return methodCodes.includes(String(batch.methodCode).toUpperCase())
  }

  const handleToggleMethod = (methodCode, checked) => {
    if (methodSelectionLocked) return
    setSelectedMethods((prev) => {
      if (checked) return prev.includes(methodCode) ? prev : [...prev, methodCode]
      return prev.filter((value) => value !== methodCode)
    })
  }

  const runRag = async (event) => {
    event.preventDefault()
    if (methodCodesForRun.length === 0) {
      notify('최소 1개 생성 방식을 선택해야 합니다.', 'error')
      return
    }
    const runGatingPreset = effectiveGatingPreset
    if (form.sourceGatingBatchId) {
      const snapshot = selectedSnapshot
      if (!snapshot) {
        notify('선택한 스냅샷을 찾을 수 없습니다. 목록을 새로고침하세요.', 'error')
        return
      }
      if (!snapshot.sourceGatingRunId) {
        notify('선택한 스냅샷에는 source_gating_run_id가 없어 실행할 수 없습니다.', 'error')
        return
      }
      if (!isSnapshotCompatible(snapshot, runGatingPreset, methodCodesForRun)) {
        notify('선택한 스냅샷이 현재 게이팅 preset/method 조건과 호환되지 않습니다.', 'error')
        return
      }
    }
    try {
      const created = await requestJson('/api/admin/console/rag/tests/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          datasetId: form.datasetId,
          methodCodes: methodCodesForRun,
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
      await Promise.all([loadTests(), loadRewriteLogs(), loadLlmJobs(), loadGatingBatches()])
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

  const toggleCompareRun = (runId) => {
    const already = compareRunIds.includes(runId)
    if (!already && compareRunIds.length >= 2) {
      notify('비교 대상은 최대 2개까지 선택할 수 있습니다.', 'error')
      return
    }
    setCompareRunIds((prev) => (prev.includes(runId) ? prev.filter((id) => id !== runId) : [...prev, runId]))
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
            <DetailCard label="command / experiment" value={`${job.commandName || '-'} / ${job.experimentName || '-'}`} />
            <DetailCard
              label="progress"
              value={`${job.processedItems ?? 0} / ${job.totalItems ?? 0} (${job.progressPct == null ? '-' : `${Number(job.progressPct).toFixed(1)}%`})`}
            />
            <DetailCard label="job_items" value={JSON.stringify(items || [], null, 2)} />
          </div>
        ),
      })
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const openDatasetItems = async (datasetId) => {
    try {
      const rows = await requestJson(`/api/admin/console/rag/datasets/${datasetId}/items?limit=30`)
      const preview = (Array.isArray(rows) ? rows : [])
        .map((row) => {
          const method = row.targetMethod ? `[${row.targetMethod}] ` : ''
          const focus = Array.isArray(row.evaluationFocus) && row.evaluationFocus.length > 0 ? ` (${row.evaluationFocus.join(',')})` : ''
          return `[${row.sampleId}] ${method}${row.queryCategory} - ${row.userQueryKo}${focus}`
        })
        .join('\n')
      setModal({
        title: `평가 문항 미리보기 · ${shortId(datasetId)}`,
        body: <DetailCard label="samples" value={preview} />,
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

  const runMetricsMap = useMemo(() => {
    const map = new Map()
    for (const run of tests) {
      map.set(run.ragTestRunId, extractRunMetrics(run.metricsJson))
    }
    return map
  }, [tests])

  const compareRuns = useMemo(() => compareRunIds.map((id) => tests.find((run) => run.ragTestRunId === id)).filter(Boolean), [compareRunIds, tests])
  const compareRows = useMemo(() => {
    if (compareRuns.length !== 2) return []
    const [leftRun, rightRun] = compareRuns
    const leftMetrics = runMetricsMap.get(leftRun.ragTestRunId) || {}
    const rightMetrics = runMetricsMap.get(rightRun.ragTestRunId) || {}
    return METRIC_DEFS.map((meta) => ({
      ...meta,
      left: leftMetrics[meta.key],
      right: rightMetrics[meta.key],
    })).filter((row) => row.left != null || row.right != null)
  }, [compareRuns, runMetricsMap])

  const latestSummaryCards = useMemo(() => {
    const completedRuns = tests.filter((run) => String(run.status || '').toLowerCase() === 'completed')
    const lastRun = completedRuns[0]
    const lastMetrics = lastRun ? extractRunMetrics(lastRun.metricsJson) : {}
    return [
      { label: '완료된 테스트 수', value: String(completedRuns.length), meta: '최근 50개 실행 기준' },
      { label: '선택된 비교 대상', value: String(compareRunIds.length), meta: compareRunIds.length === 2 ? '비교 준비 완료' : '2개 선택 시 비교 차트 표시' },
      { label: '최근 Recall@5', value: formatMetric(lastMetrics.recall_at_5), meta: lastRun ? `run ${shortId(lastRun.ragTestRunId)}` : '완료된 실행 없음' },
      { label: '최근 nDCG@10', value: formatMetric(lastMetrics.ndcg_at_10), meta: lastRun ? fmtTime(lastRun.finishedAt || lastRun.startedAt) : '-' },
    ]
  }, [tests, compareRunIds])

  return (
    <>
      <section className="panel panel--hero">
        <div className="table-title">RAG 품질/성능 테스트 실행</div>
        <p className="panel-subtitle">
          스냅샷 기반 재현성, Rewrite 전략, 검색 파라미터를 실험 단위로 비교할 수 있도록 구성했습니다.
        </p>
        <form className="filter-bar filter-bar--stack" onSubmit={runRag}>
          <div className="form-grid form-grid--2">
            <label className="filter-field">평가 데이터셋
              <select value={form.datasetId} onChange={(event) => setForm((prev) => ({ ...prev, datasetId: event.target.value }))}>
                {datasets.map((dataset) => <option key={dataset.datasetId} value={dataset.datasetId}>{dataset.datasetName} ({dataset.totalItems})</option>)}
              </select>
              <span className="field-hint">테스트 입력 샘플 집합입니다.</span>
            </label>
            <label className="filter-field">Gating Snapshot
              <select value={form.sourceGatingBatchId} onChange={(event) => setForm((prev) => ({ ...prev, sourceGatingBatchId: event.target.value }))}>
                <option value="">Auto (latest matching)</option>
                {snapshotBatches.map((batch) => {
                  const compatible = isSnapshotCompatible(batch, effectiveGatingPreset, methodCodesForRun)
                  const runnable = Boolean(batch.sourceGatingRunId)
                  return (
                    <option key={batch.gatingBatchId} value={batch.gatingBatchId}>
                      {`${shortId(batch.gatingBatchId)} | ${batch.gatingPreset} | ${batch.methodCode || '-'} | ${fmtTime(batch.finishedAt)}${runnable ? '' : ' | unavailable(no source run)'}${compatible ? '' : ' | incompatible'}`}
                    </option>
                  )
                })}
              </select>
              <span className="field-hint">완료된 게이팅 배치 전체를 표시합니다. 실행 시 호환성 검증을 수행합니다.</span>
            </label>
            <label className="filter-field">게이팅 프리셋
              <select
                value={form.gatingPreset}
                disabled={!form.gatingApplied}
                onChange={(event) => setForm((prev) => ({ ...prev, gatingPreset: event.target.value }))}
              >
                <option value="ungated">ungated</option>
                <option value="rule_only">rule_only</option>
                <option value="rule_plus_llm">rule_plus_llm</option>
                <option value="full_gating">full_gating</option>
              </select>
              <span className="field-hint">
                {form.gatingApplied ? '메모리 조회 대상 게이팅 단계를 선택합니다.' : '게이팅 미반영 상태이므로 ungated로 고정됩니다.'}
              </span>
            </label>
            <label className="filter-field">생성 방식(A/B/C/D)
              <div className="method-row">
                {methods.map((method) => (
                  <label key={method.methodCode} className="plain-badge">
                    <input
                      type="checkbox"
                      checked={methodCodesForRun.includes(method.methodCode)}
                      disabled={methodSelectionLocked}
                      onChange={(event) => handleToggleMethod(method.methodCode, event.target.checked)}
                    />
                    {method.methodCode}
                  </label>
                ))}
              </div>
              <span className="field-hint">
                {methodSelectionLocked
                  ? `스냅샷 method(${snapshotMethodCode}) 기준으로 자동 고정되어 중복 선택을 제거했습니다.`
                  : '스냅샷 미선택 또는 legacy 스냅샷에서는 수동 선택이 필요합니다.'}
              </span>
            </label>
          </div>

          <div className="form-grid form-grid--3">
            <label className="filter-field filter-field--small">Rewrite Threshold
              <input
                type="number"
                min="0"
                max="1"
                step="0.01"
                value={form.threshold}
                disabled={!form.rewriteEnabled || !form.selectiveRewrite}
                onChange={(event) => setForm((prev) => ({ ...prev, threshold: event.target.value }))}
              />
              <span className="field-hint">`rewrite_threshold`: selective 모드에서 후보 쿼리 채택 임계값</span>
            </label>
            <label className="filter-field filter-field--small">Retrieval Top-K
              <input type="number" min="1" value={form.retrievalTopK} onChange={(event) => setForm((prev) => ({ ...prev, retrievalTopK: event.target.value }))} />
              <span className="field-hint">`retrieval_top_k`: 검색 단계에서 가져오는 후보 청크 수</span>
            </label>
            <label className="filter-field filter-field--small">Rerank Top-N
              <input type="number" min="1" value={form.rerankTopN} onChange={(event) => setForm((prev) => ({ ...prev, rerankTopN: event.target.value }))} />
              <span className="field-hint">`rerank_top_n`: answer eval에서 최종 재정렬에 쓰는 개수</span>
            </label>
          </div>

          <div className="checkbox-row">
            <label><input type="checkbox" checked={form.gatingApplied} onChange={(event) => setForm((prev) => ({ ...prev, gatingApplied: event.target.checked }))} />게이팅 반영</label>
            <label><input type="checkbox" checked={form.rewriteEnabled} onChange={(event) => setForm((prev) => ({ ...prev, rewriteEnabled: event.target.checked }))} />Rewrite 사용</label>
            <label><input type="checkbox" checked={form.selectiveRewrite} disabled={!form.rewriteEnabled} onChange={(event) => setForm((prev) => ({ ...prev, selectiveRewrite: event.target.checked, useSessionContext: event.target.checked ? prev.useSessionContext : false }))} />Selective</label>
            <label><input type="checkbox" checked={form.useSessionContext} disabled={!form.rewriteEnabled || !form.selectiveRewrite} onChange={(event) => setForm((prev) => ({ ...prev, useSessionContext: event.target.checked }))} />Session Context</label>
          </div>

          <div className="state-note">
            <strong>옵션 의미:</strong> 게이팅 반영=메모리 후보를 게이팅 결과로 제한, Rewrite 사용=질의 재작성 활성화,
            Selective=매번 Rewrite하지 않고 품질 개선 가능성 있을 때만 적용, Session Context=대화 문맥을 Rewrite 후보 생성에 투입.
          </div>

          {selectedSnapshot && (
            <div className="state-note">
              <strong>선택 스냅샷:</strong> {shortId(selectedSnapshot.gatingBatchId)} / preset {selectedSnapshot.gatingPreset} / method {selectedSnapshot.methodCode || '-'} / source run {selectedSnapshot.sourceGatingRunId ? 'available' : 'missing'}
            </div>
          )}

          <div className="form-actions">
            <button type="submit" className="button button--primary">테스트 실행</button>
            <button type="button" className="button" onClick={() => Promise.all([loadTests(), loadRewriteLogs(), loadLlmJobs(), loadGatingBatches()]).catch((error) => notify(error.message, 'error'))}>목록 새로고침</button>
          </div>
        </form>
      </section>

      <section className="summary-grid">
        {latestSummaryCards.map((card) => (
          <article key={card.label} className="summary-card">
            <div className="summary-card__label">{card.label}</div>
            <div className="summary-card__value">{card.value}</div>
            <div className="summary-card__meta">{card.meta}</div>
          </article>
        ))}
      </section>

      <section className="table-shell compare-shell">
        <div className="table-header">
          <div className="table-title">실험 비교 차트 (2개 선택)</div>
        </div>
        <div className="compare-body">
          {compareRuns.length !== 2 && (
            <div className="empty-state">
              비교할 테스트 2개를 아래 실행 이력 테이블에서 선택하세요.
            </div>
          )}
          {compareRuns.length === 2 && (
            <>
              <div className="compare-legend">
                <span className="compare-pill compare-pill--a">{shortId(compareRuns[0].ragTestRunId)}</span>
                <span className="compare-pill compare-pill--b">{shortId(compareRuns[1].ragTestRunId)}</span>
              </div>
              <div className="compare-grid">
                {compareRows.map((row) => {
                  const leftPct = row.left == null ? 0 : Math.max(0, Math.min(100, (row.left / row.max) * 100))
                  const rightPct = row.right == null ? 0 : Math.max(0, Math.min(100, (row.right / row.max) * 100))
                  return (
                    <div key={row.key} className="compare-row">
                      <div className="compare-row__label">{row.label}</div>
                      <div className="compare-bar-stack">
                        <div className="compare-bar compare-bar--a" style={{ width: `${leftPct}%` }}>
                          <span>{formatMetric(row.left)}</span>
                        </div>
                        <div className="compare-bar compare-bar--b" style={{ width: `${rightPct}%` }}>
                          <span>{formatMetric(row.right)}</span>
                        </div>
                      </div>
                    </div>
                  )
                })}
              </div>
            </>
          )}
        </div>
      </section>

      <section className="table-shell">
        <div className="table-header"><div className="table-title">평가 데이터셋</div></div>
        <div className="table-wrap">
          <table className="data-table">
            <thead><tr><th>데이터셋 ID</th><th>이름</th><th>버전</th><th>문항 수</th><th>생성 일시</th><th>상세</th></tr></thead>
            <tbody>
              {datasets.map((dataset) => (
                <tr key={dataset.datasetId}>
                  <td><IdBadge value={dataset.datasetId} /></td>
                  <td>{dataset.datasetName}</td>
                  <td>{dataset.version || '-'}</td>
                  <td>{dataset.totalItems ?? 0}</td>
                  <td>{fmtTime(dataset.createdAt)}</td>
                  <td><button type="button" className="button button--ghost" onClick={() => openDatasetItems(dataset.datasetId)}>상세 조회</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="table-shell">
        <div className="table-header">
          <div className="table-title">RAG 테스트 실행 이력</div>
          <button type="button" className="button" onClick={() => Promise.all([loadTests(), loadRewriteLogs(), loadLlmJobs(), loadGatingBatches()]).catch((error) => notify(error.message, 'error'))}>새로고침</button>
        </div>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>비교</th>
                <th>실행 ID</th>
                <th>상태</th>
                <th>데이터셋</th>
                <th>생성 방식</th>
                <th>게이팅</th>
                <th>Rewrite 모드</th>
                <th>핵심 지표</th>
                <th>상세</th>
              </tr>
            </thead>
            <tbody>
              {tests.map((run) => {
                const metrics = runMetricsMap.get(run.ragTestRunId) || {}
                const rewriteMode = run.rewriteEnabled
                  ? run.selectiveRewrite
                    ? (run.useSessionContext ? 'selective + session' : 'selective')
                    : 'always'
                  : 'off'
                return (
                  <tr key={run.ragTestRunId}>
                    <td>
                      <input
                        type="checkbox"
                        checked={compareRunIds.includes(run.ragTestRunId)}
                        onChange={() => toggleCompareRun(run.ragTestRunId)}
                      />
                    </td>
                    <td><IdBadge value={run.ragTestRunId} /></td>
                    <td><StatusBadge value={run.status} /></td>
                    <td>{run.datasetName || '-'}</td>
                    <td>{Array.isArray(run.generationMethodCodes) ? run.generationMethodCodes.join(', ') : '-'}</td>
                    <td>{run.gatingApplied ? `${run.gatingPreset || 'enabled'}` : 'ungated'}</td>
                    <td>{rewriteMode}</td>
                    <td className="line-clamp">{`R@5 ${formatMetric(metrics.recall_at_5)} | nDCG ${formatMetric(metrics.ndcg_at_10)}`}</td>
                    <td><button type="button" className="button button--ghost" onClick={() => openRunDetail(run.ragTestRunId)}>상세 조회</button></td>
                  </tr>
                )
              })}
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
                  <td><IdBadge value={row.rewriteLogId} /></td>
                  <td>{row.rawQuery || '-'}</td>
                  <td>{row.finalQuery || '-'}</td>
                  <td>{row.rewriteStrategy || '-'}</td>
                  <td>{row.rewriteApplied ? <StatusBadge value="success" label="적용" /> : <StatusBadge value="failed" label="미적용" />}</td>
                  <td>{row.confidenceDelta == null ? '-' : Number(row.confidenceDelta).toFixed(4)}</td>
                  <td>{row.decisionReason || row.rejectionReason || '-'}</td>
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
