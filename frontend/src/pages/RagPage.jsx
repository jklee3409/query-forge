import { useEffect, useMemo, useState } from 'react'
import { DetailCard, IdBadge, Modal, StatusBadge } from '../components/Common.jsx'
import { LlmJobsTable } from '../components/LlmJobsTable.jsx'
import { requestJson, toNumber } from '../lib/api.js'
import { fmtTime, shortId } from '../lib/format.js'

const QUALITY_METRIC_DEFS = [
  { key: 'recall_at_5', label: 'Recall@5', max: 1 },
  { key: 'hit_at_5', label: 'Hit@5', max: 1 },
  { key: 'mrr_at_10', label: 'MRR@10', max: 1 },
  { key: 'ndcg_at_10', label: 'nDCG@10', max: 1 },
  { key: 'correctness', label: 'Correctness', max: 1 },
  { key: 'grounding', label: 'Grounding', max: 1 },
  { key: 'hallucination_rate', label: 'Hallucination', max: 1 },
  { key: 'answer_relevance', label: 'Answer Relevance', max: 1 },
  { key: 'faithfulness', label: 'Faithfulness', max: 1 },
  { key: 'context_recall', label: 'Context Recall', max: 1 },
]

const PERFORMANCE_METRIC_DEFS = [
  { key: 'total_duration_ms', label: 'Total Duration', precision: 0, unit: 'ms' },
  { key: 'build_memory_ms', label: 'Build-Memory Stage', precision: 0, unit: 'ms' },
  { key: 'eval_retrieval_ms', label: 'Eval-Retrieval Stage', precision: 0, unit: 'ms' },
  { key: 'eval_answer_ms', label: 'Eval-Answer Stage', precision: 0, unit: 'ms' },
  { key: 'orchestration_overhead_ms', label: 'Orchestration Overhead', precision: 0, unit: 'ms' },
  { key: 'latency_avg_ms', label: 'Representative Avg Latency', precision: 2, unit: 'ms' },
  { key: 'latency_p95_ms', label: 'Representative P95 Latency', precision: 2, unit: 'ms' },
  { key: 'rewrite_overhead_avg_latency_ms', label: 'Rewrite Overhead (Avg)', precision: 2, unit: 'ms' },
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
  const retrievalPayload = parseMetricsNode(payload.retrieval || payload.metrics_json?.retrieval || payload)
  const answerPayload = parseMetricsNode(payload.answer || payload.metrics_json?.answer)
  const performancePayload = parseMetricsNode(payload.performance || payload.metrics_json?.performance)
  const stageDurationPayload = parseMetricsNode(performancePayload.stage_duration_ms)
  const answerSummary = parseMetricsNode(answerPayload.summary || answerPayload)
  const summaryRaw = Array.isArray(retrievalPayload.summary) ? retrievalPayload.summary : []
  const byMode = summaryRaw.reduce((acc, row) => {
    const mode = String(row?.mode || '')
    if (!mode) return acc
    acc[mode] = row
    return acc
  }, {})
  const representativeMode = String(payload.representative_mode || retrievalPayload.representative_mode || '')
  const preferredModes = [
    representativeMode,
    'selective_rewrite',
    'selective_rewrite_with_session',
    'memory_only_full_gating',
    'memory_only_gated',
    'raw_only',
  ].filter(Boolean)
  const summary = preferredModes.map((mode) => byMode[mode]).find(Boolean) || {}
  return {
    representative_mode: summary.mode || representativeMode || '-',
    by_mode: byMode,
    recall_at_5: firstMetricNumber([payload.recall_at_5, payload.recallAt5, summary.recall_at_5, summary['recall@5']]),
    hit_at_5: firstMetricNumber([payload.hit_at_5, payload.hitAt5, summary.hit_at_5, summary['hit@5']]),
    mrr_at_10: firstMetricNumber([payload.mrr_at_10, payload.mrrAt10, summary.mrr_at_10, summary['mrr@10']]),
    ndcg_at_10: firstMetricNumber([payload.ndcg_at_10, payload.ndcgAt10, summary.ndcg_at_10, summary['ndcg@10']]),
    correctness: firstMetricNumber([payload.correctness, answerSummary.correctness]),
    grounding: firstMetricNumber([payload.grounding, answerSummary.grounding]),
    hallucination_rate: firstMetricNumber([payload.hallucination_rate, answerSummary.hallucination_rate]),
    answer_relevance: firstMetricNumber([payload.answer_relevance, answerSummary.answer_relevance]),
    faithfulness: firstMetricNumber([payload.faithfulness, answerSummary.faithfulness]),
    context_recall: firstMetricNumber([payload.context_recall, answerSummary.context_recall]),
    total_duration_ms: firstMetricNumber([performancePayload.total_duration_ms]),
    build_memory_ms: firstMetricNumber([stageDurationPayload.build_memory_ms]),
    eval_retrieval_ms: firstMetricNumber([stageDurationPayload.eval_retrieval_ms]),
    eval_answer_ms: firstMetricNumber([stageDurationPayload.eval_answer_ms]),
    orchestration_overhead_ms: firstMetricNumber([performancePayload.orchestration_overhead_ms]),
    latency_avg_ms: firstMetricNumber([performancePayload.representative_mode_latency_avg_ms, payload.latency_avg_ms]),
    latency_p95_ms: firstMetricNumber([performancePayload.representative_mode_latency_p95_ms]),
    rewrite_overhead_avg_latency_ms: firstMetricNumber([performancePayload.rewrite_overhead_avg_latency_ms]),
  }
}

function formatMetric(value) {
  if (value == null) return '-'
  return Number(value).toFixed(4)
}

function formatMetricWithDef(value, def) {
  if (value == null) return '-'
  const precision = Number.isFinite(def?.precision) ? def.precision : 4
  const text = Number(value).toFixed(precision)
  return def?.unit ? `${text} ${def.unit}` : text
}

function formatDelta(value, def) {
  if (value == null) return '-'
  const precision = Number.isFinite(def?.precision) ? def.precision : 4
  const sign = value > 0 ? '+' : ''
  const text = `${sign}${Number(value).toFixed(precision)}`
  return def?.unit ? `${text} ${def.unit}` : text
}

export function RagPage({ notify }) {
  const historyPageSize = 3
  const [methods, setMethods] = useState([])
  const [datasets, setDatasets] = useState([])
  const [tests, setTests] = useState([])
  const [gatingBatches, setGatingBatches] = useState([])
  const [rewriteLogs, setRewriteLogs] = useState([])
  const [llmJobs, setLlmJobs] = useState([])
  const [historyPage, setHistoryPage] = useState(0)
  const [modal, setModal] = useState(null)
  const [selectedMethods, setSelectedMethods] = useState([])
  const [compareRunIds, setCompareRunIds] = useState([])
  const [deletingRunId, setDeletingRunId] = useState('')

  const [form, setForm] = useState({
    datasetId: '',
    runDiscipline: 'exploratory',
    officialComparisonType: 'rewrite_effect',
    gatingPreset: 'full_gating',
    sourceGatingBatchId: '',
    officialGatingUngatedBatchId: '',
    officialGatingRuleOnlyBatchId: '',
    officialGatingFullGatingBatchId: '',
    threshold: '0.05',
    retrievalTopK: '20',
    rerankTopN: '5',
    syntheticFreeBaseline: false,
    gatingApplied: true,
    stageCutoffEnabled: false,
    stageCutoffLevel: 'rule_only',
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

  useEffect(() => {
    const totalPages = Math.max(1, Math.ceil(tests.length / historyPageSize))
    if (historyPage > totalPages - 1) {
      setHistoryPage(totalPages - 1)
    }
  }, [tests, historyPage])

  const selectedSnapshot = useMemo(
    () => gatingBatches.find((batch) => batch.gatingBatchId === form.sourceGatingBatchId) || null,
    [gatingBatches, form.sourceGatingBatchId],
  )
  const snapshotMethodCode = selectedSnapshot?.methodCode ? String(selectedSnapshot.methodCode).toUpperCase() : null
  const methodSelectionLocked = !form.syntheticFreeBaseline && Boolean(form.sourceGatingBatchId && snapshotMethodCode)

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

  useEffect(() => {
    if (!form.syntheticFreeBaseline) return
    setForm((prev) => {
      if (
        prev.runDiscipline === 'exploratory'
        && !prev.sourceGatingBatchId
        && !prev.officialGatingUngatedBatchId
        && !prev.officialGatingRuleOnlyBatchId
        && !prev.officialGatingFullGatingBatchId
        && !prev.gatingApplied
        && !prev.stageCutoffEnabled
        && !prev.rewriteEnabled
        && !prev.selectiveRewrite
        && !prev.useSessionContext
      ) {
        return prev
      }
      return {
        ...prev,
        runDiscipline: 'exploratory',
        sourceGatingBatchId: '',
        officialGatingUngatedBatchId: '',
        officialGatingRuleOnlyBatchId: '',
        officialGatingFullGatingBatchId: '',
        gatingApplied: false,
        stageCutoffEnabled: false,
        stageCutoffLevel: 'rule_only',
        rewriteEnabled: false,
        selectiveRewrite: false,
        useSessionContext: false,
      }
    })
  }, [form.syntheticFreeBaseline])

  useEffect(() => {
    if (!form.stageCutoffEnabled) return
    if (!form.gatingApplied || form.syntheticFreeBaseline || form.runDiscipline === 'official') {
      setForm((prev) => ({ ...prev, stageCutoffEnabled: false }))
    }
  }, [form.stageCutoffEnabled, form.gatingApplied, form.syntheticFreeBaseline, form.runDiscipline])

  const effectiveGatingPreset = form.gatingApplied ? form.gatingPreset : 'ungated'
  const stageCutoffEnabledForRun = !form.syntheticFreeBaseline && Boolean(form.stageCutoffEnabled)
  const sourceSnapshotExpectedPreset = stageCutoffEnabledForRun ? 'full_gating' : effectiveGatingPreset
  const snapshotBatches = useMemo(
    () => gatingBatches.filter((batch) => batch && String(batch.status || '').toLowerCase() === 'completed'),
    [gatingBatches],
  )
  const sourceSnapshotOptions = useMemo(
    () => (stageCutoffEnabledForRun ? snapshotBatches.filter((batch) => batch.gatingPreset === 'full_gating') : snapshotBatches),
    [snapshotBatches, stageCutoffEnabledForRun],
  )
  const methodCodesForRun = form.syntheticFreeBaseline
    ? []
    : (methodSelectionLocked && snapshotMethodCode ? [snapshotMethodCode] : selectedMethods)

  useEffect(() => {
    if (!form.sourceGatingBatchId) return
    const exists = gatingBatches.some((batch) => batch.gatingBatchId === form.sourceGatingBatchId)
    if (exists) return
    setForm((prev) => ({ ...prev, sourceGatingBatchId: '' }))
  }, [form.sourceGatingBatchId, gatingBatches])

  useEffect(() => {
    if (form.runDiscipline !== 'official' || form.officialComparisonType !== 'gating_effect') return
    if (!form.sourceGatingBatchId) return
    setForm((prev) => ({ ...prev, sourceGatingBatchId: '' }))
  }, [form.runDiscipline, form.officialComparisonType, form.sourceGatingBatchId])

  useEffect(() => {
    const hasUngated = gatingBatches.some((batch) => batch.gatingBatchId === form.officialGatingUngatedBatchId)
    const hasRuleOnly = gatingBatches.some((batch) => batch.gatingBatchId === form.officialGatingRuleOnlyBatchId)
    const hasFullGating = gatingBatches.some((batch) => batch.gatingBatchId === form.officialGatingFullGatingBatchId)
    if (hasUngated && hasRuleOnly && hasFullGating) return
    const nextUngated = hasUngated ? form.officialGatingUngatedBatchId : ''
    const nextRuleOnly = hasRuleOnly ? form.officialGatingRuleOnlyBatchId : ''
    const nextFullGating = hasFullGating ? form.officialGatingFullGatingBatchId : ''
    if (
      nextUngated === form.officialGatingUngatedBatchId
      && nextRuleOnly === form.officialGatingRuleOnlyBatchId
      && nextFullGating === form.officialGatingFullGatingBatchId
    ) {
      return
    }
    setForm((prev) => ({
      ...prev,
      officialGatingUngatedBatchId: nextUngated,
      officialGatingRuleOnlyBatchId: nextRuleOnly,
      officialGatingFullGatingBatchId: nextFullGating,
    }))
  }, [
    form.officialGatingUngatedBatchId,
    form.officialGatingRuleOnlyBatchId,
    form.officialGatingFullGatingBatchId,
    gatingBatches,
  ])

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

  function snapshotOptionLabel(batch, expectedPreset = null) {
    const compatible = expectedPreset
      ? isSnapshotCompatible(batch, expectedPreset, methodCodesForRun)
      : isSnapshotCompatible(batch, sourceSnapshotExpectedPreset, methodCodesForRun)
    const runnable = Boolean(batch?.sourceGatingRunId)
    return `${shortId(batch.gatingBatchId)} | ${batch.gatingPreset} | ${batch.methodCode || '-'} | ${fmtTime(batch.finishedAt)}${runnable ? '' : ' | unavailable(no source run)'}${compatible ? '' : ' | incompatible'}`
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
    const syntheticFreeBaseline = Boolean(form.syntheticFreeBaseline)
    if (!syntheticFreeBaseline && methodCodesForRun.length === 0) {
      notify('최소 1개 생성 방식을 선택해야 합니다.', 'error')
      return
    }
    const officialRun = !syntheticFreeBaseline && form.runDiscipline === 'official'
    const stageCutoffEnabled = !syntheticFreeBaseline && Boolean(form.stageCutoffEnabled)
    const stageCutoffLevel = stageCutoffEnabled ? (form.stageCutoffLevel || 'full_gating') : null
    if (syntheticFreeBaseline && form.runDiscipline === 'official') {
      notify('Synthetic-free baseline은 exploratory 실행에서만 지원됩니다.', 'error')
      return
    }
    const runGatingPreset = syntheticFreeBaseline ? 'ungated' : effectiveGatingPreset
    const sourceSnapshotPreset = stageCutoffEnabled ? 'full_gating' : runGatingPreset
    if (stageCutoffEnabled && officialRun) {
      notify('stage-cutoff은 exploratory 실행에서만 사용할 수 있습니다.', 'error')
      return
    }
    if (stageCutoffEnabled && !form.gatingApplied) {
      notify('stage-cutoff 사용 시 gating 적용이 필요합니다.', 'error')
      return
    }
    if (stageCutoffEnabled && !form.sourceGatingBatchId) {
      notify('stage-cutoff 사용 시 full_gating source snapshot을 선택해야 합니다.', 'error')
      return
    }
    if (!syntheticFreeBaseline && officialRun && form.officialComparisonType === 'rewrite_effect' && !form.sourceGatingBatchId) {
      notify('공식 rewrite-effect 실행은 source snapshot 선택이 필수입니다.', 'error')
      return
    }
    if (!syntheticFreeBaseline && officialRun && form.officialComparisonType === 'gating_effect') {
      if (!form.officialGatingUngatedBatchId || !form.officialGatingRuleOnlyBatchId || !form.officialGatingFullGatingBatchId) {
        notify('공식 gating-effect 실행은 ungated/rule_only/full_gating 스냅샷 3개가 모두 필요합니다.', 'error')
        return
      }
      const requiredSnapshots = [
        { id: form.officialGatingUngatedBatchId, preset: 'ungated', label: 'ungated' },
        { id: form.officialGatingRuleOnlyBatchId, preset: 'rule_only', label: 'rule_only' },
        { id: form.officialGatingFullGatingBatchId, preset: 'full_gating', label: 'full_gating' },
      ]
      for (const required of requiredSnapshots) {
        const snapshot = snapshotBatches.find((batch) => batch.gatingBatchId === required.id)
        if (!snapshot) {
          notify(`공식 gating-effect ${required.label} snapshot을 찾을 수 없습니다.`, 'error')
          return
        }
        if (!snapshot.sourceGatingRunId) {
          notify(`공식 gating-effect ${required.label} snapshot에 source_gating_run_id가 없습니다.`, 'error')
          return
        }
        if (!isSnapshotCompatible(snapshot, required.preset, methodCodesForRun)) {
          notify(`공식 gating-effect ${required.label} snapshot이 preset/method와 호환되지 않습니다.`, 'error')
          return
        }
      }
    }
    if (!syntheticFreeBaseline && form.sourceGatingBatchId) {
      const snapshot = selectedSnapshot
      if (!snapshot) {
        notify('선택한 스냅샷을 찾을 수 없습니다. 목록을 새로고침하세요.', 'error')
        return
      }
      if (!snapshot.sourceGatingRunId) {
        notify('선택한 스냅샷에는 source_gating_run_id가 없어 실행할 수 없습니다.', 'error')
        return
      }
      if (!isSnapshotCompatible(snapshot, sourceSnapshotPreset, methodCodesForRun)) {
        notify('선택한 스냅샷이 현재 게이팅 preset/method 조건과 호환되지 않습니다.', 'error')
        return
      }
    }
    try {
      const gatingApplied = syntheticFreeBaseline ? false : Boolean(form.gatingApplied)
      const rewriteEnabled = syntheticFreeBaseline ? false : Boolean(form.rewriteEnabled)
      const selectiveRewrite = syntheticFreeBaseline ? false : Boolean(form.selectiveRewrite)
      const useSessionContext = syntheticFreeBaseline ? false : Boolean(form.useSessionContext)
      const created = await requestJson('/api/admin/console/rag/tests/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          datasetId: form.datasetId,
          methodCodes: syntheticFreeBaseline ? [] : methodCodesForRun,
          syntheticFreeBaseline,
          gatingPreset: runGatingPreset,
          sourceGatingBatchId: syntheticFreeBaseline
            ? null
            : officialRun && form.officialComparisonType === 'gating_effect'
              ? null
              : form.sourceGatingBatchId || null,
          comparisonGatingBatchIds: syntheticFreeBaseline
            ? null
            : officialRun && form.officialComparisonType === 'gating_effect'
              ? {
                  ungated: form.officialGatingUngatedBatchId || null,
                  rule_only: form.officialGatingRuleOnlyBatchId || null,
                  full_gating: form.officialGatingFullGatingBatchId || null,
                }
              : null,
          officialRun,
          officialComparisonType: officialRun ? form.officialComparisonType : null,
          gatingApplied,
          stageCutoffEnabled: stageCutoffEnabled || null,
          stageCutoffLevel,
          rewriteEnabled,
          selectiveRewrite,
          useSessionContext,
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
      const metricsJson = parseMetricsNode(summary.metrics_json)
      const performance = parseMetricsNode(metricsJson.performance)
      const retrievalByMode = parseMetricsNode(metricsJson.retrieval_by_mode)
      const retrievalByModeRows = Object.values(retrievalByMode).filter((row) => row && typeof row === 'object')
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
            <DetailCard label="performance" value={JSON.stringify(performance, null, 2)} />
            <DetailCard label="retrieval_by_mode" value={JSON.stringify(retrievalByModeRows, null, 2)} />
            <DetailCard label="detail_rows" value={JSON.stringify(details, null, 2)} />
          </div>
        ),
      })
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const deleteRun = async (runId) => {
    if (!window.confirm('선택한 RAG 테스트 이력과 결과를 삭제할까요?')) return
    setDeletingRunId(runId)
    try {
      await requestJson(`/api/admin/console/rag/tests/${runId}`, { method: 'DELETE' })
      setCompareRunIds((prev) => prev.filter((id) => id !== runId))
      await Promise.all([loadTests(), loadRewriteLogs(), loadLlmJobs()])
      notify('RAG 테스트 이력 및 결과를 삭제했습니다.')
    } catch (error) {
      notify(error.message, 'error')
    } finally {
      setDeletingRunId('')
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
    return QUALITY_METRIC_DEFS.map((meta) => ({
      ...meta,
      left: leftMetrics[meta.key],
      right: rightMetrics[meta.key],
    })).filter((row) => row.left != null || row.right != null)
  }, [compareRuns, runMetricsMap])
  const compareMetricRows = useMemo(() => {
    if (compareRuns.length !== 2) return []
    const [leftRun, rightRun] = compareRuns
    const leftMetrics = runMetricsMap.get(leftRun.ragTestRunId) || {}
    const rightMetrics = runMetricsMap.get(rightRun.ragTestRunId) || {}
    const qualityRows = QUALITY_METRIC_DEFS.map((def) => {
      const left = leftMetrics[def.key]
      const right = rightMetrics[def.key]
      return { category: 'quality', ...def, left, right, delta: (left != null && right != null) ? (right - left) : null }
    })
    const performanceRows = PERFORMANCE_METRIC_DEFS.map((def) => {
      const left = leftMetrics[def.key]
      const right = rightMetrics[def.key]
      return { category: 'performance', ...def, left, right, delta: (left != null && right != null) ? (right - left) : null }
    })
    return [...qualityRows, ...performanceRows].filter((row) => row.left != null || row.right != null)
  }, [compareRuns, runMetricsMap])
  const historyTotalPages = Math.max(1, Math.ceil(tests.length / historyPageSize))
  const currentHistoryPage = Math.min(historyPage, historyTotalPages - 1)
  const pagedTests = tests.slice(currentHistoryPage * historyPageSize, (currentHistoryPage + 1) * historyPageSize)

  const latestSummaryCards = useMemo(() => {
    const completedRuns = tests.filter((run) => String(run.status || '').toLowerCase() === 'completed')
    const lastRun = completedRuns[0]
    const lastMetrics = lastRun ? extractRunMetrics(lastRun.metricsJson) : {}
    return [
      { label: '완료된 테스트 수', value: String(completedRuns.length), meta: '최근 50개 실행 기준' },
      { label: '선택된 비교 대상', value: String(compareRunIds.length), meta: compareRunIds.length === 2 ? '비교 준비 완료' : '2개 선택 시 비교 차트 표시' },
      { label: '최근 Recall@5', value: formatMetric(lastMetrics.recall_at_5), meta: lastRun ? `run ${shortId(lastRun.ragTestRunId)}` : '완료된 실행 없음' },
      { label: '최근 nDCG@10', value: formatMetric(lastMetrics.ndcg_at_10), meta: lastRun ? fmtTime(lastRun.finishedAt || lastRun.startedAt) : '-' },
      { label: 'Latest Total Duration', value: formatMetricWithDef(lastMetrics.total_duration_ms, { precision: 0, unit: 'ms' }), meta: 'RAG end-to-end' },
      { label: 'Latest Avg Latency', value: formatMetricWithDef(lastMetrics.latency_avg_ms, { precision: 2, unit: 'ms' }), meta: 'representative mode' },
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
            <label className="filter-field">Run Discipline
              <select
                value={form.runDiscipline}
                disabled={form.syntheticFreeBaseline}
                onChange={(event) => setForm((prev) => ({ ...prev, runDiscipline: event.target.value }))}
              >
                <option value="exploratory">exploratory</option>
                <option value="official">official</option>
              </select>
              <span className="field-hint">official 실행은 snapshot/비교 조건 강제 검증이 적용됩니다.</span>
            </label>
            <label className="filter-field">Official Comparison Type
              <select
                value={form.officialComparisonType}
                disabled={form.syntheticFreeBaseline || form.runDiscipline !== 'official'}
                onChange={(event) => setForm((prev) => ({ ...prev, officialComparisonType: event.target.value }))}
              >
                <option value="rewrite_effect">rewrite_effect</option>
                <option value="gating_effect">gating_effect</option>
              </select>
              <span className="field-hint">official 전용: 한 번에 하나의 비교축만 허용합니다.</span>
            </label>
            {!form.syntheticFreeBaseline && form.runDiscipline === 'official' && form.officialComparisonType === 'gating_effect' && (
              <>
                <label className="filter-field">Official Snapshot (ungated)
                  <select value={form.officialGatingUngatedBatchId} onChange={(event) => setForm((prev) => ({ ...prev, officialGatingUngatedBatchId: event.target.value }))}>
                    <option value="">Select ungated snapshot</option>
                    {snapshotBatches
                      .filter((batch) => batch.gatingPreset === 'ungated')
                      .map((batch) => (
                        <option key={batch.gatingBatchId} value={batch.gatingBatchId}>
                          {snapshotOptionLabel(batch, 'ungated')}
                        </option>
                      ))}
                  </select>
                  <span className="field-hint">공식 gating-effect 비교용 ungated snapshot입니다.</span>
                </label>
                <label className="filter-field">Official Snapshot (rule_only)
                  <select value={form.officialGatingRuleOnlyBatchId} onChange={(event) => setForm((prev) => ({ ...prev, officialGatingRuleOnlyBatchId: event.target.value }))}>
                    <option value="">Select rule_only snapshot</option>
                    {snapshotBatches
                      .filter((batch) => batch.gatingPreset === 'rule_only')
                      .map((batch) => (
                        <option key={batch.gatingBatchId} value={batch.gatingBatchId}>
                          {snapshotOptionLabel(batch, 'rule_only')}
                        </option>
                      ))}
                  </select>
                  <span className="field-hint">공식 gating-effect 비교용 rule_only snapshot입니다.</span>
                </label>
                <label className="filter-field">Official Snapshot (full_gating)
                  <select value={form.officialGatingFullGatingBatchId} onChange={(event) => setForm((prev) => ({ ...prev, officialGatingFullGatingBatchId: event.target.value }))}>
                    <option value="">Select full_gating snapshot</option>
                    {snapshotBatches
                      .filter((batch) => batch.gatingPreset === 'full_gating')
                      .map((batch) => (
                        <option key={batch.gatingBatchId} value={batch.gatingBatchId}>
                          {snapshotOptionLabel(batch, 'full_gating')}
                        </option>
                      ))}
                  </select>
                  <span className="field-hint">공식 gating-effect 비교용 full_gating snapshot입니다.</span>
                </label>
              </>
            )}
            <label className="filter-field">Gating Snapshot
              <select
                value={form.sourceGatingBatchId}
                disabled={form.syntheticFreeBaseline || (form.runDiscipline === 'official' && form.officialComparisonType === 'gating_effect')}
                onChange={(event) => setForm((prev) => ({ ...prev, sourceGatingBatchId: event.target.value }))}
              >
                <option value="">
                  {form.syntheticFreeBaseline
                    ? 'Not used for synthetic-free baseline'
                    : form.runDiscipline === 'official'
                    ? (form.officialComparisonType === 'gating_effect' ? 'Not used for official gating-effect' : 'Select snapshot (required)')
                    : form.stageCutoffEnabled
                    ? 'Select full_gating snapshot (required)'
                    : 'Auto (latest matching)'}
                </option>
                {sourceSnapshotOptions.map((batch) => {
                  return (
                    <option key={batch.gatingBatchId} value={batch.gatingBatchId}>
                      {snapshotOptionLabel(batch)}
                    </option>
                  )
                })}
              </select>
              <span className="field-hint">
                {form.runDiscipline === 'official' && form.officialComparisonType === 'gating_effect'
                  ? 'official gating-effect에서는 위 3개 전용 snapshot을 사용합니다.'
                  : '완료된 게이팅 배치 전체를 표시합니다. 실행 시 호환성 검증을 수행합니다.'}
              </span>
            </label>
            <label className="filter-field">게이팅 프리셋
              <select
                value={form.gatingPreset}
                disabled={form.syntheticFreeBaseline || !form.gatingApplied}
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
                  <label
                    key={method.methodCode}
                    className={`check-pill ${methodCodesForRun.includes(method.methodCode) ? 'is-active' : ''} ${(form.syntheticFreeBaseline || methodSelectionLocked) ? 'is-disabled' : ''}`}
                  >
                    <input
                      type="checkbox"
                      checked={methodCodesForRun.includes(method.methodCode)}
                      disabled={form.syntheticFreeBaseline || methodSelectionLocked}
                      onChange={(event) => handleToggleMethod(method.methodCode, event.target.checked)}
                    />
                    <span className="check-pill__box" aria-hidden="true">{methodCodesForRun.includes(method.methodCode) ? '✓' : ''}</span>
                    <span className="check-pill__text">{method.methodCode}</span>
                  </label>
                ))}
              </div>
              <span className="field-hint">
                {form.syntheticFreeBaseline
                  ? 'Synthetic-free baseline에서는 생성 방식 선택이 비활성화됩니다.'
                  : methodSelectionLocked
                  ? `스냅샷 method(${snapshotMethodCode}) 기준으로 자동 고정되어 중복 선택을 제거했습니다.`
                  : '스냅샷 미선택 또는 legacy 스냅샷에서는 수동 선택이 필요합니다.'}
              </span>
            </label>
          </div>

          <div className="form-grid form-grid--3">
            <label className="filter-field filter-field--small">Stage Cutoff Level
              <select
                value={form.stageCutoffLevel}
                disabled={form.syntheticFreeBaseline || !form.gatingApplied || !form.stageCutoffEnabled || form.runDiscipline === 'official'}
                onChange={(event) => setForm((prev) => ({ ...prev, stageCutoffLevel: event.target.value }))}
              >
                <option value="rule_only">rule_only</option>
                <option value="rule_plus_llm">rule_plus_llm</option>
                <option value="utility">utility</option>
                <option value="diversity">diversity</option>
                <option value="full_gating">full_gating</option>
              </select>
              <span className="field-hint">full_gating 배치 기준 stage cutoff 레벨입니다.</span>
            </label>
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
            <label className={`check-pill ${form.syntheticFreeBaseline ? 'is-active' : ''}`}>
              <input type="checkbox" checked={form.syntheticFreeBaseline} onChange={(event) => setForm((prev) => ({ ...prev, syntheticFreeBaseline: event.target.checked }))} />
              <span className="check-pill__box" aria-hidden="true">{form.syntheticFreeBaseline ? '✓' : ''}</span>
              <span className="check-pill__text">Synthetic-free baseline</span>
            </label>
            <label className={`check-pill ${form.stageCutoffEnabled ? 'is-active' : ''} ${(form.syntheticFreeBaseline || !form.gatingApplied || form.runDiscipline === 'official') ? 'is-disabled' : ''}`}>
              <input type="checkbox" checked={form.stageCutoffEnabled} disabled={form.syntheticFreeBaseline || !form.gatingApplied || form.runDiscipline === 'official'} onChange={(event) => setForm((prev) => ({ ...prev, stageCutoffEnabled: event.target.checked }))} />
              <span className="check-pill__box" aria-hidden="true">{form.stageCutoffEnabled ? '✓' : ''}</span>
              <span className="check-pill__text">Stage Cutoff</span>
            </label>
            <label><input type="checkbox" checked={form.gatingApplied} disabled={form.syntheticFreeBaseline} onChange={(event) => setForm((prev) => ({ ...prev, gatingApplied: event.target.checked }))} />게이팅 반영</label>
            <label><input type="checkbox" checked={form.rewriteEnabled} disabled={form.syntheticFreeBaseline} onChange={(event) => setForm((prev) => ({ ...prev, rewriteEnabled: event.target.checked }))} />Rewrite 사용</label>
            <label><input type="checkbox" checked={form.selectiveRewrite} disabled={form.syntheticFreeBaseline || !form.rewriteEnabled} onChange={(event) => setForm((prev) => ({ ...prev, selectiveRewrite: event.target.checked, useSessionContext: event.target.checked ? prev.useSessionContext : false }))} />Selective</label>
            <label><input type="checkbox" checked={form.useSessionContext} disabled={form.syntheticFreeBaseline || !form.rewriteEnabled || !form.selectiveRewrite} onChange={(event) => setForm((prev) => ({ ...prev, useSessionContext: event.target.checked }))} />Session Context</label>
          </div>

          <div className="state-note">
            <strong>옵션 의미:</strong> 게이팅 반영=메모리 후보를 게이팅 결과로 제한, Rewrite 사용=질의 재작성 활성화,
            Selective=매번 Rewrite하지 않고 품질 개선 가능성 있을 때만 적용, Session Context=대화 문맥을 Rewrite 후보 생성에 투입.
          </div>

          {selectedSnapshot && !form.syntheticFreeBaseline && (
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
              <div className="compare-grid compare-grid--vertical">
                {compareRows.map((row) => {
                  const leftPct = row.left == null ? 0 : Math.max(0, Math.min(100, (row.left / row.max) * 100))
                  const rightPct = row.right == null ? 0 : Math.max(0, Math.min(100, (row.right / row.max) * 100))
                  return (
                    <article key={row.key} className="compare-metric">
                      <div className="compare-metric__label">{row.label}</div>
                      <div className="compare-metric__plot">
                        <div className="compare-metric__col">
                          <div className="compare-metric__bar compare-metric__bar--a" style={{ height: `${leftPct}%` }} />
                          <div className="compare-metric__value">{formatMetric(row.left)}</div>
                        </div>
                        <div className="compare-metric__col">
                          <div className="compare-metric__bar compare-metric__bar--b" style={{ height: `${rightPct}%` }} />
                          <div className="compare-metric__value">{formatMetric(row.right)}</div>
                        </div>
                      </div>
                    </article>
                  )
                })}
              </div>
            </>
          )}
        </div>
      </section>

      <section className="table-shell">
        <div className="table-header">
          <div className="table-title">품질 + 성능 통합 비교 테이블</div>
        </div>
        <div className="table-wrap">
          {compareRuns.length !== 2 && (
            <div className="empty-state">
              실행 이력에서 2개를 선택하면 품질/성능 지표를 함께 비교할 수 있습니다.
            </div>
          )}
          {compareRuns.length === 2 && (
            <table className="data-table">
              <thead>
                <tr>
                  <th>구분</th>
                  <th>지표</th>
                  <th>{shortId(compareRuns[0].ragTestRunId)}</th>
                  <th>{shortId(compareRuns[1].ragTestRunId)}</th>
                  <th>Delta (B-A)</th>
                </tr>
              </thead>
              <tbody>
                {compareMetricRows.map((row) => (
                  <tr key={`${row.category}:${row.key}`}>
                    <td>{row.category}</td>
                    <td>{row.label}</td>
                    <td>{formatMetricWithDef(row.left, row)}</td>
                    <td>{formatMetricWithDef(row.right, row)}</td>
                    <td>{formatDelta(row.delta, row)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
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
                <th>삭제</th>
              </tr>
            </thead>
            <tbody>
              {pagedTests.map((run) => {
                const metrics = runMetricsMap.get(run.ragTestRunId) || {}
                const methodCodes = Array.isArray(run.generationMethodCodes) ? run.generationMethodCodes : []
                const generationMethodLabel = methodCodes.length > 0 ? methodCodes.join(', ') : 'synthetic-free baseline'
                const rewriteMode = run.rewriteEnabled
                  ? run.selectiveRewrite
                    ? (run.useSessionContext ? 'selective + session' : 'selective')
                    : 'always'
                  : 'off'
                return (
                  <tr key={run.ragTestRunId}>
                    <td>
                      <label className={`compare-check ${compareRunIds.includes(run.ragTestRunId) ? 'is-selected' : ''}`}>
                        <input
                          type="checkbox"
                          checked={compareRunIds.includes(run.ragTestRunId)}
                          onChange={() => toggleCompareRun(run.ragTestRunId)}
                        />
                        <span className="compare-check__box" aria-hidden="true">
                          {compareRunIds.includes(run.ragTestRunId) ? '✓' : ''}
                        </span>
                        <span className="compare-check__text">
                          {compareRunIds.includes(run.ragTestRunId) ? '선택됨' : '선택'}
                        </span>
                      </label>
                    </td>
                    <td><IdBadge value={run.ragTestRunId} /></td>
                    <td><StatusBadge value={run.status} /></td>
                    <td>{run.datasetName || '-'}</td>
                    <td>{generationMethodLabel}</td>
                    <td>{run.gatingApplied ? `${run.gatingPreset || 'enabled'}` : 'ungated'}</td>
                    <td>{rewriteMode}</td>
                    <td className="line-clamp">
                      {`R@5 ${formatMetric(metrics.recall_at_5)} | nDCG ${formatMetric(metrics.ndcg_at_10)} | total ${formatMetricWithDef(metrics.total_duration_ms, { precision: 0, unit: 'ms' })}`}
                    </td>
                    <td><button type="button" className="button button--ghost" onClick={() => openRunDetail(run.ragTestRunId)}>상세 조회</button></td>
                    <td>
                      <button
                        type="button"
                        className="button button--ghost"
                        disabled={deletingRunId === run.ragTestRunId}
                        onClick={() => deleteRun(run.ragTestRunId)}
                      >
                        {deletingRunId === run.ragTestRunId ? '삭제 중...' : '삭제'}
                      </button>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
        <div className="pagination">
          <button
            type="button"
            className="button"
            disabled={currentHistoryPage === 0}
            onClick={() => setHistoryPage((prev) => Math.max(0, prev - 1))}
          >이전</button>
          <div className="pagination__label">페이지 {currentHistoryPage + 1}</div>
          <button
            type="button"
            className="button"
            disabled={currentHistoryPage + 1 >= historyTotalPages}
            onClick={() => setHistoryPage((prev) => Math.min(historyTotalPages - 1, prev + 1))}
          >다음</button>
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
