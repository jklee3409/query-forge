import { useEffect, useState } from 'react'
import { BalanceBar } from '../components/AdminUi.jsx'
import { DetailCard, IdBadge, Modal, StatusBadge } from '../components/Common.jsx'
import { LlmJobsTable } from '../components/LlmJobsTable.jsx'
import { RemainingEta } from '../components/RemainingEta.jsx'
import { queryString, requestJson, toNumber } from '../lib/api.js'
import { shortId } from '../lib/format.js'

function ControlField({ label, value, onChange, step = '1', min, max, disabled = false, emphasis = false }) {
  return (
    <label className={`qf-control-field ${emphasis ? 'qf-control-field--emphasis' : ''} ${disabled ? 'is-disabled' : ''}`}>
      <span>{label}</span>
      <input
        type="number"
        step={step}
        min={min}
        max={max}
        value={value}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
  )
}

function SelectField({ label, value, onChange, disabled = false, children }) {
  return (
    <label className={`qf-control-field ${disabled ? 'is-disabled' : ''}`}>
      <span>{label}</span>
      <select value={value} disabled={disabled} onChange={(event) => onChange(event.target.value)}>
        {children}
      </select>
    </label>
  )
}

function GateCard({ code, title, description, checked, onToggle, tone = 'core', meta, children }) {
  return (
    <article className={`qf-gate-card ${checked ? 'is-enabled' : 'is-disabled'}`} data-tone={tone}>
      <header className="qf-gate-card__header">
        <div className="qf-gate-card__identity">
          <span className="qf-gate-card__code">{code}</span>
          <div>
            <h3>{title}</h3>
            {description && <p>{description}</p>}
          </div>
        </div>
        <label className={`toggle-switch ${checked ? 'is-active' : ''}`}>
          <input type="checkbox" checked={checked} onChange={(event) => onToggle(event.target.checked)} />
          <span className="toggle-switch__track" aria-hidden="true">
            <span className="toggle-switch__thumb" />
          </span>
          <span className="toggle-switch__label">{checked ? 'ON' : 'OFF'}</span>
        </label>
      </header>
      {Array.isArray(meta) && meta.length > 0 && (
        <div className="qf-gate-card__meta">
          {meta.map((item) => (
            <span key={item.label}>
              <small>{item.label}</small>
              <strong>{item.value}</strong>
            </span>
          ))}
        </div>
      )}
      <div className="qf-gate-card__body">{children}</div>
    </article>
  )
}

function ScoreGroup({ title, accent, children }) {
  return (
    <section className="qf-score-group" data-accent={accent}>
      <h4>{title}</h4>
      <div className="qf-score-grid">{children}</div>
    </section>
  )
}

function CapabilityChip({ label, meta, checked, disabled = false, onChange }) {
  return (
    <label className={`qf-capability-chip ${checked ? 'is-active' : ''} ${disabled ? 'is-disabled' : ''}`}>
      <input type="checkbox" checked={checked} disabled={disabled} onChange={(event) => onChange(event.target.checked)} />
      <span className="qf-capability-chip__dot" aria-hidden="true" />
      <span>
        <strong>{label}</strong>
        {meta && <small>{meta}</small>}
      </span>
    </label>
  )
}

export function GatingPage({ notify }) {
  const resultPageSize = 20
  const historyPageSize = 3
  const normalizeMethodCode = (value) => String(value || '').trim().toUpperCase()

  const [methods, setMethods] = useState([])
  const [batches, setBatches] = useState([])
  const [gatingBatches, setGatingBatches] = useState([])
  const [results, setResults] = useState([])
  const [funnel, setFunnel] = useState(null)
  const [llmJobs, setLlmJobs] = useState([])
  const [llmJobsLoaded, setLlmJobsLoaded] = useState(false)
  const [llmJobsLoading, setLlmJobsLoading] = useState(false)
  const [runtimeOptions, setRuntimeOptions] = useState({
    llmModels: [],
    defaultLlmModel: '',
    denseEmbeddingModels: [],
    defaultDenseEmbeddingModel: '',
    retrieverModes: [],
  })
  const [selectedBatchId, setSelectedBatchId] = useState('')
  const [resultFilter, setResultFilter] = useState({ methodCode: '', generationBatchId: '', gatingBatchId: '', passStage: '' })
  const [funnelFilter, setFunnelFilter] = useState({ methodCode: '', generationBatchId: '', gatingBatchId: '' })
  const [gatingBatchPage, setGatingBatchPage] = useState(0)
  const [resultPage, setResultPage] = useState(0)
  const [resultHasNextPage, setResultHasNextPage] = useState(false)
  const [runningGating, setRunningGating] = useState(false)
  const [modal, setModal] = useState(null)

  const pickDefaultGatingBatchId = (rows) => {
    if (!Array.isArray(rows) || rows.length === 0) return ''
    const completed = rows.find((row) => String(row.status || '').toLowerCase() === 'completed')
    return (completed || rows[0]).gatingBatchId
  }

  const findQueryableGatingBatches = (methodCode, generationBatchId) => {
    const normalizedMethodCode = normalizeMethodCode(methodCode)
    return gatingBatches.filter((batch) => {
      const status = String(batch.status || '').toLowerCase()
      if (status !== 'completed' && status !== 'running') return false
      if (generationBatchId && batch.generationBatchId !== generationBatchId) return false
      if (!normalizedMethodCode) return true
      return normalizeMethodCode(batch.methodCode) === normalizedMethodCode
    })
  }

  const resolveGatingBatchId = (currentBatchId, options) => {
    if (currentBatchId && options.some((batch) => batch.gatingBatchId === currentBatchId)) {
      return currentBatchId
    }
    return options[0]?.gatingBatchId || ''
  }

  const isFilterReady = (filter) => Boolean(filter?.methodCode && filter?.generationBatchId)

  const findGenerationBatchOptions = (methodCode) => {
    const normalizedMethodCode = normalizeMethodCode(methodCode)
    if (!normalizedMethodCode) return batches
    return batches.filter((batch) => normalizeMethodCode(batch.methodCode) === normalizedMethodCode)
  }

  const findActiveGenerationBatchOptions = (methodCode) => (
    findGenerationBatchOptions(methodCode).filter((batch) => {
      const status = String(batch.status || '').toLowerCase()
      return status !== 'failed' && status !== 'cancelled'
    })
  )

  const [form, setForm] = useState({
    methodCode: '',
    generationBatchId: '',
    generationBatchIds: [],
    gatingPreset: 'full_gating',
    llmModel: '',
    enableRuleFilter: true,
    enableLlmSelfEval: true,
    enableRetrievalUtility: true,
    enableDiversity: true,
    ruleMinLengthShort: '4',
    ruleMaxLengthShort: '60',
    ruleMinLengthLong: '8',
    ruleMaxLengthLong: '100',
    ruleMinTokens: '2',
    ruleMaxTokens: '30',
    ruleMinKoreanRatio: '0.2',
    llmWeight: '0.35',
    utilityWeight: '0.50',
    diversityWeight: '0.15',
    utilityTargetTop1Score: '1.00',
    utilityTargetTop3Score: '0.85',
    utilityTargetTop5Score: '0.70',
    utilityTargetTop10Score: '0.60',
    utilitySameDocTop3Score: '0.55',
    utilitySameDocTop5Score: '0.40',
    utilityOutsideTop5Score: '0.00',
    utilityMultiPartialBonus: '0.05',
    utilityMultiFullBonus: '0.12',
    utilityThreshold: '0.70',
    diversityThresholdSameChunk: '0.93',
    diversityThresholdSameDoc: '0.96',
    finalScoreThreshold: '0.75',
    retrieverMode: 'hybrid',
    denseEmbeddingModel: '',
    denseEmbeddingRequired: true,
    denseFallbackEnabled: false,
    retrieverRerankEnabled: true,
    retrieverCandidatePoolK: '50',
    retrieverDenseWeight: '0.58',
    retrieverBm25Weight: '0.34',
    retrieverTechnicalWeight: '0.08',
  })

  const retrieverModeLabel = (mode) => {
    if (mode === 'bm25_only') return 'BM25 Only'
    if (mode === 'dense_only') return 'Dense Only'
    if (mode === 'hybrid') return 'Hybrid'
    return mode || '-'
  }

  const loadSelectors = async () => {
    const [methodRows, batchRows, runtimePayload] = await Promise.all([
      requestJson('/api/admin/console/synthetic/methods'),
      requestJson('/api/admin/console/synthetic/batches?limit=100'),
      requestJson('/api/admin/console/runtime/options'),
    ])
    const normalizedMethods = Array.isArray(methodRows) ? methodRows : []
    setMethods(normalizedMethods)
    const normalizedBatches = Array.isArray(batchRows) ? batchRows : []
    const llmModels = Array.isArray(runtimePayload.llmModels) ? runtimePayload.llmModels.filter(Boolean) : []
    const denseEmbeddingModels = Array.isArray(runtimePayload.denseEmbeddingModels)
      ? runtimePayload.denseEmbeddingModels.filter(Boolean)
      : []
    const retrieverModes = Array.isArray(runtimePayload.retrieverModes)
      ? runtimePayload.retrieverModes.filter(Boolean)
      : []
    const defaultLlmModel = runtimePayload.defaultLlmModel || llmModels[0] || ''
    const defaultDenseEmbeddingModel = runtimePayload.defaultDenseEmbeddingModel || denseEmbeddingModels[0] || ''
    const defaultRetrieverMode = retrieverModes.includes(form.retrieverMode)
      ? form.retrieverMode
      : (retrieverModes[0] || form.retrieverMode || 'hybrid')
    setRuntimeOptions({
      llmModels,
      defaultLlmModel,
      denseEmbeddingModels,
      defaultDenseEmbeddingModel,
      retrieverModes,
    })
    setForm((prev) => ({
      ...prev,
      llmModel: prev.llmModel || defaultLlmModel,
      denseEmbeddingModel: prev.denseEmbeddingModel || defaultDenseEmbeddingModel,
      retrieverMode: retrieverModes.includes(prev.retrieverMode) ? prev.retrieverMode : defaultRetrieverMode,
    }))
    setBatches(normalizedBatches)
  }

  const loadGatingBatches = async () => {
    const rows = await requestJson('/api/admin/console/gating/batches?limit=200')
    const normalized = Array.isArray(rows) ? rows : []
    setGatingBatches(normalized)
    const hasSelected = Boolean(selectedBatchId) && normalized.some((batch) => batch.gatingBatchId === selectedBatchId)
    if (!hasSelected) {
      setSelectedBatchId(pickDefaultGatingBatchId(normalized))
    }
  }

  const loadFunnel = async (batchId, methodCode = funnelFilter.methodCode) => {
    if (!batchId) {
      setFunnel(null)
      return
    }
    const query = queryString({ method_code: methodCode || null })
    const url = query
      ? `/api/admin/console/gating/batches/${batchId}/funnel?${query}`
      : `/api/admin/console/gating/batches/${batchId}/funnel`
    setFunnel(await requestJson(url))
  }

  const loadResults = async (batchId, page = 0, methodCode = resultFilter.methodCode, passStage = resultFilter.passStage) => {
    if (!batchId) {
      setResults([])
      setResultHasNextPage(false)
      return
    }
    const query = queryString({
      method_code: methodCode || null,
      pass_stage: passStage || null,
      limit: resultPageSize + 1,
      offset: page * resultPageSize,
    })
    const rows = await requestJson(`/api/admin/console/gating/batches/${batchId}/results?${query}`)
    const normalized = Array.isArray(rows) ? rows : []
    setResultHasNextPage(normalized.length > resultPageSize)
    setResults(normalized.slice(0, resultPageSize))
  }

  const loadLlmJobs = async () => {
    setLlmJobsLoading(true)
    try {
      const rows = await requestJson('/api/admin/console/llm-jobs?limit=120')
      const filtered = (Array.isArray(rows) ? rows : [])
        .filter((job) => job.jobType === 'RUN_LLM_SELF_EVAL' || job.gatingBatchId)
      setLlmJobs(filtered)
      setLlmJobsLoaded(true)
    } finally {
      setLlmJobsLoading(false)
    }
  }

  useEffect(() => {
    Promise.all([loadSelectors(), loadGatingBatches()]).catch((error) => notify(error.message, 'error'))
  }, [])

  useEffect(() => {
    if (!gatingBatches.length) {
      setFunnel(null)
      setResults([])
      setResultHasNextPage(false)
      return
    }
    const funnelReady = isFilterReady(funnelFilter)
    const resultReady = isFilterReady(resultFilter)
    if (!funnelReady) setFunnel(null)
    if (!resultReady) {
      setResults([])
      setResultHasNextPage(false)
    }
    if (!funnelReady && !resultReady) return
    const initialPage = 0
    setResultPage(initialPage)
    const tasks = []
    if (funnelReady) {
      const funnelOptions = findQueryableGatingBatches(funnelFilter.methodCode, funnelFilter.generationBatchId)
      const funnelBatchId = resolveGatingBatchId(funnelFilter.gatingBatchId, funnelOptions)
      tasks.push(loadFunnel(funnelBatchId, funnelFilter.methodCode))
    }
    if (resultReady) {
      const resultOptions = findQueryableGatingBatches(resultFilter.methodCode, resultFilter.generationBatchId)
      const resultBatchId = resolveGatingBatchId(resultFilter.gatingBatchId, resultOptions)
      tasks.push(loadResults(resultBatchId, initialPage, resultFilter.methodCode, resultFilter.passStage))
    }
    Promise.all(tasks).catch((error) => notify(error.message, 'error'))
  }, [gatingBatches, funnelFilter.methodCode, funnelFilter.generationBatchId, funnelFilter.gatingBatchId, resultFilter.methodCode, resultFilter.generationBatchId, resultFilter.gatingBatchId, resultFilter.passStage])

  useEffect(() => {
    const totalPages = Math.max(1, Math.ceil(gatingBatches.length / historyPageSize))
    if (gatingBatchPage > totalPages - 1) {
      setGatingBatchPage(totalPages - 1)
    }
  }, [gatingBatches, gatingBatchPage])

  useEffect(() => {
    setForm((prev) => {
      const validBatchIds = new Set(formBatchOptions.map((batch) => batch.batchId))
      const normalizedSelected = Array.isArray(prev.generationBatchIds)
        ? prev.generationBatchIds.filter((batchId) => validBatchIds.has(batchId))
        : []
      const primaryBatchId = prev.generationBatchId && validBatchIds.has(prev.generationBatchId)
        ? prev.generationBatchId
        : ''
      if (
        normalizedSelected.length === (prev.generationBatchIds || []).length
        && normalizedSelected.every((batchId, index) => batchId === prev.generationBatchIds[index])
        && primaryBatchId === prev.generationBatchId
      ) {
        return prev
      }
      return {
        ...prev,
        generationBatchId: primaryBatchId,
        generationBatchIds: normalizedSelected,
      }
    })
  }, [batches, form.methodCode])

  const runGating = async (event) => {
    event.preventDefault()
    const selectedBatchIds = Array.isArray(form.generationBatchIds) ? form.generationBatchIds.filter(Boolean) : []
    const effectiveBatchIds = selectedBatchIds.length > 0
      ? selectedBatchIds
      : (form.generationBatchId ? [form.generationBatchId] : [])
    const selectedMethodCodes = Array.from(new Set(
      effectiveBatchIds
        .map((batchId) => batches.find((batch) => batch.batchId === batchId)?.methodCode)
        .filter(Boolean)
        .map((value) => normalizeMethodCode(value)),
    ))
    if (effectiveBatchIds.length === 0) {
      notify('생성 배치를 선택하세요.', 'error')
      return
    }
    if (!form.llmModel) {
      notify('LLM 모델을 선택하세요.', 'error')
      return
    }
    if (form.retrieverMode !== 'bm25_only' && !form.denseEmbeddingModel) {
      notify('Dense embedding 모델을 선택하세요.', 'error')
      return
    }
    setRunningGating(true)
    try {
      await requestJson('/api/admin/console/gating/batches/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          methodCode: form.methodCode || selectedMethodCodes[0] || null,
          methodCodes: selectedMethodCodes.length > 0 ? selectedMethodCodes : null,
          generationBatchId: effectiveBatchIds[0] || null,
          generationBatchIds: effectiveBatchIds,
          gatingPreset: form.gatingPreset,
          llmModel: form.llmModel,
          config: {
            stageFlags: {
              enableRuleFilter: Boolean(form.enableRuleFilter),
              enableLlmSelfEval: Boolean(form.enableLlmSelfEval),
              enableRetrievalUtility: Boolean(form.enableRetrievalUtility),
              enableDiversity: Boolean(form.enableDiversity),
            },
            ruleConfig: {
              minLengthShort: toNumber(form.ruleMinLengthShort),
              maxLengthShort: toNumber(form.ruleMaxLengthShort),
              minLengthLong: toNumber(form.ruleMinLengthLong),
              maxLengthLong: toNumber(form.ruleMaxLengthLong),
              minTokens: toNumber(form.ruleMinTokens),
              maxTokens: toNumber(form.ruleMaxTokens),
              minKoreanRatio: toNumber(form.ruleMinKoreanRatio),
            },
            gatingWeights: {
              llmWeight: toNumber(form.llmWeight),
              utilityWeight: toNumber(form.utilityWeight),
              diversityWeight: toNumber(form.diversityWeight),
            },
            utilityScoreWeights: {
              targetTop1Score: toNumber(form.utilityTargetTop1Score),
              targetTop3Score: toNumber(form.utilityTargetTop3Score),
              targetTop5Score: toNumber(form.utilityTargetTop5Score),
              targetTop10Score: toNumber(form.utilityTargetTop10Score),
              sameDocTop3Score: toNumber(form.utilitySameDocTop3Score),
              sameDocTop5Score: toNumber(form.utilitySameDocTop5Score),
              outsideTop5Score: toNumber(form.utilityOutsideTop5Score),
              multiPartialBonus: toNumber(form.utilityMultiPartialBonus),
              multiFullBonus: toNumber(form.utilityMultiFullBonus),
            },
            thresholds: {
              utilityThreshold: toNumber(form.utilityThreshold),
              diversityThresholdSameChunk: toNumber(form.diversityThresholdSameChunk),
              diversityThresholdSameDoc: toNumber(form.diversityThresholdSameDoc),
              finalScoreThreshold: toNumber(form.finalScoreThreshold),
            },
            retrieverConfig: {
              retrieverMode: form.retrieverMode,
              denseEmbeddingModel: form.denseEmbeddingModel,
              denseEmbeddingRequired: Boolean(form.denseEmbeddingRequired),
              denseFallbackEnabled: Boolean(form.denseFallbackEnabled),
              rerankEnabled: Boolean(form.retrieverRerankEnabled),
              candidatePoolK: toNumber(form.retrieverCandidatePoolK),
              denseWeight: toNumber(form.retrieverDenseWeight),
              bm25Weight: toNumber(form.retrieverBm25Weight),
              technicalWeight: toNumber(form.retrieverTechnicalWeight),
            },
          },
        }),
      })
      const refreshTasks = [loadGatingBatches()]
      if (llmJobsLoaded) {
        refreshTasks.push(loadLlmJobs())
      }
      await Promise.all(refreshTasks)
      notify('Quality Gating 배치를 등록했습니다.')
    } catch (error) {
      notify(error.message, 'error')
    } finally {
      setRunningGating(false)
    }
  }

  const openBatchDetail = (batch) => {
    setSelectedBatchId(batch.gatingBatchId)
    setModal({
      title: `게이팅 배치 상세 · ${shortId(batch.gatingBatchId)}`,
      body: (
        <div className="detail-grid detail-grid--single">
          <DetailCard label="프리셋 / 방식" value={`${batch.gatingPreset || '-'} / ${batch.methodCode || '-'}`} />
          <DetailCard label="처리 / 승인" value={`${batch.processedCount ?? 0} / ${batch.acceptedCount ?? 0}`} />
          <DetailCard label="설정(stage_config_json)" value={JSON.stringify(batch.stageConfig || {}, null, 2)} />
          <DetailCard label="리젝트 요약" value={JSON.stringify(batch.rejectionSummary || {}, null, 2)} />
        </div>
      ),
    })
  }

  const executeLlmAction = async (jobId, action) => {
    try {
      await requestJson(`/api/admin/console/llm-jobs/${jobId}/${action}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: '{}',
      })
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

  const applyResultFilter = async (event) => {
    event.preventDefault()
    if (!isFilterReady(resultFilter)) {
      notify('생성 방식과 생성 배치를 모두 선택하세요.', 'error')
      return
    }
    try {
      const initialPage = 0
      setResultPage(initialPage)
      const options = findQueryableGatingBatches(resultFilter.methodCode, resultFilter.generationBatchId)
      const batchId = resolveGatingBatchId(resultFilter.gatingBatchId, options)
      await loadResults(batchId, initialPage, resultFilter.methodCode, resultFilter.passStage)
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const applyFunnelFilter = async (event) => {
    event.preventDefault()
    if (!isFilterReady(funnelFilter)) {
      notify('생성 방식과 생성 배치를 모두 선택하세요.', 'error')
      return
    }
    try {
      const options = findQueryableGatingBatches(funnelFilter.methodCode, funnelFilter.generationBatchId)
      const batchId = resolveGatingBatchId(funnelFilter.gatingBatchId, options)
      await loadFunnel(batchId, funnelFilter.methodCode)
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const resultGatingBatchOptions = findQueryableGatingBatches(resultFilter.methodCode, resultFilter.generationBatchId)
  const funnelGatingBatchOptions = findQueryableGatingBatches(funnelFilter.methodCode, funnelFilter.generationBatchId)
  const resultReferenceBatchId = resolveGatingBatchId(resultFilter.gatingBatchId, resultGatingBatchOptions)
  const funnelReferenceBatchId = resolveGatingBatchId(funnelFilter.gatingBatchId, funnelGatingBatchOptions)
  const formBatchOptions = findGenerationBatchOptions(form.methodCode)
    .filter((batch) => String(batch.status || '').toLowerCase() === 'completed')
  const funnelBatchOptions = funnelFilter.methodCode ? findActiveGenerationBatchOptions(funnelFilter.methodCode) : []
  const resultBatchOptions = resultFilter.methodCode ? findActiveGenerationBatchOptions(resultFilter.methodCode) : []
  const gatingBatchTotalPages = Math.max(1, Math.ceil(gatingBatches.length / historyPageSize))
  const currentGatingBatchPage = Math.min(gatingBatchPage, gatingBatchTotalPages - 1)
  const pagedGatingBatches = gatingBatches.slice(currentGatingBatchPage * historyPageSize, (currentGatingBatchPage + 1) * historyPageSize)
  const selectedGenerationBatchIds = Array.isArray(form.generationBatchIds) && form.generationBatchIds.length > 0
    ? form.generationBatchIds
    : (form.generationBatchId ? [form.generationBatchId] : [])
  const selectedGenerationBatches = selectedGenerationBatchIds
    .map((batchId) => batches.find((batch) => batch.batchId === batchId))
    .filter(Boolean)
  const selectedMethodCodes = Array.from(new Set(
    selectedGenerationBatches.map((batch) => normalizeMethodCode(batch.methodCode)).filter(Boolean),
  ))
  const activeStageCount = [
    form.enableRuleFilter,
    form.enableLlmSelfEval,
    form.enableRetrievalUtility,
    form.enableDiversity,
  ].filter(Boolean).length
  const latestGatingBatch = gatingBatches[0]
  const runDisabled = runningGating
    || selectedGenerationBatchIds.length === 0
    || !form.llmModel
    || (form.retrieverMode !== 'bm25_only' && !form.denseEmbeddingModel)
  const pipelineStages = [
    { key: 'source', label: 'Source', active: selectedGenerationBatchIds.length > 0 },
    { key: 'rule', label: 'Rule', active: form.enableRuleFilter },
    { key: 'llm', label: 'LLM', active: form.enableLlmSelfEval },
    { key: 'utility', label: 'Utility', active: form.enableRetrievalUtility },
    { key: 'diversity', label: 'Diversity', active: form.enableDiversity },
    { key: 'final', label: 'Final', active: true },
  ]
  const gatingSummaryItems = [
    { label: 'Preset', value: form.gatingPreset },
    { label: 'Method', value: selectedMethodCodes.length ? selectedMethodCodes.join(' + ') : (form.methodCode || 'ALL') },
    { label: 'Batches', value: selectedGenerationBatchIds.length ? `${selectedGenerationBatchIds.length} selected` : 'Not selected' },
    { label: 'Stages', value: `${activeStageCount}/4 enabled` },
    { label: 'Final Threshold', value: form.finalScoreThreshold },
    { label: 'Retriever', value: retrieverModeLabel(form.retrieverMode) },
  ]

  const funnelCards = [
    { label: '생성 총량', value: funnel?.generatedTotal ?? 0 },
    { label: 'Rule 통과', value: funnel?.passedRule ?? 0 },
    { label: 'LLM 통과', value: funnel?.passedLlm ?? 0 },
    { label: 'Utility 통과', value: funnel?.passedUtility ?? 0 },
    { label: 'Diversity 통과', value: funnel?.passedDiversity ?? 0 },
    { label: '최종 승인', value: funnel?.finalAccepted ?? 0 },
  ]

  const renderStageStatus = (value) => {
    if (value === true) return <StatusBadge value="success" label="통과" />
    if (value === false) return <StatusBadge value="failed" label="실패" />
    return <StatusBadge value="queued" label="미사용" />
  }

  const normalizeToken = (value) => String(value || '').trim().toLowerCase().replace(/\s+/g, '_')

  const tokenIconMap = {
    query: {
      short_user: 'SU',
      follow_up: 'FU',
      long_context: 'LC',
      long_user: 'LU',
      clarification: 'CL',
      multi_hop: 'MH',
    },
    stage: {
      rule: 'R',
      llm: 'L',
      utility: 'U',
      diversity: 'D',
      final: 'F',
    },
    reason: {
      too_short: 'S',
      too_long: 'L',
      low_korean_ratio: 'KR',
      low_score: 'SC',
      duplicate: 'DP',
      same_chunk: 'CH',
      same_doc: 'DOC',
      out_of_scope: 'OS',
    },
  }

  const parseTokenList = (value) => {
    if (value == null) return []
    if (Array.isArray(value)) {
      return value.map((item) => String(item || '').trim()).filter(Boolean)
    }
    if (typeof value === 'object') {
      return Object.entries(value)
        .filter(([, itemValue]) => Boolean(itemValue))
        .map(([itemKey]) => String(itemKey || '').trim())
        .filter(Boolean)
    }
    const text = String(value || '').trim()
    if (!text) return []
    if (text.startsWith('[') || text.startsWith('{')) {
      try {
        const parsed = JSON.parse(text)
        return parseTokenList(parsed)
      } catch {
        // ignore parse errors and fallback to delimiter split
      }
    }
    const tokens = text
      .split(/[|,;/]+/)
      .map((token) => token.trim())
      .filter(Boolean)
    return tokens.length ? tokens : [text]
  }

  const toTokenIcon = (kind, token) => {
    const normalized = normalizeToken(token)
    const mapped = tokenIconMap[kind]?.[normalized]
    if (mapped) return mapped
    const parts = normalized.split(/[^a-z0-9]+/).filter(Boolean)
    if (!parts.length) return '?'
    if (parts.length === 1) return parts[0].slice(0, 3).toUpperCase()
    return parts.slice(0, 2).map((part) => part.slice(0, 1)).join('').toUpperCase()
  }

  const renderTokenBadges = (value, kind) => {
    const tokens = parseTokenList(value)
    if (!tokens.length) return <span className="plain-badge">-</span>
    return (
      <div className="token-badge-list">
        {tokens.map((token, index) => {
          const normalized = normalizeToken(token)
          return (
            <span key={`${kind}-${normalized || index}-${index}`} className="token-badge" data-kind={kind} title={token}>
              <span className="token-badge__icon" aria-hidden="true">{toTokenIcon(kind, token)}</span>
              <span className="token-badge__text">{normalized || token}</span>
            </span>
          )
        })}
      </div>
    )
  }

  const renderPresetRetrieverBadges = (batch) => {
    const preset = String(batch?.gatingPreset || '').trim()
    const retrieverMode = String(batch?.retrieverMode || '').trim()
    return (
      <div className="token-badge-list">
        <span className="token-badge" data-kind="stage" title={`preset: ${preset || '-'}`}>
          <span className="token-badge__icon" aria-hidden="true">P</span>
          <span className="token-badge__text">{preset || '-'}</span>
        </span>
        <span className="token-badge" data-kind="query" title={`retriever: ${retrieverMode || '-'}`}>
          <span className="token-badge__icon" aria-hidden="true">R</span>
          <span className="token-badge__text">{retrieverMode || '-'}</span>
        </span>
      </div>
    )
  }

  return (
    <>
      <section className="panel qf-gating-console">
        <div className="qf-console-hero">
          <div className="qf-console-hero__copy">
            <div className="qf-console-eyebrow">
              <span className="qf-live-dot" aria-hidden="true" />
              Runtime Gate Console
            </div>
            <h2>합성 질의 퀄리티 게이팅</h2>
            <div className="qf-console-badges" aria-label="현재 게이팅 설정">
              <span>{form.gatingPreset}</span>
              <span>{retrieverModeLabel(form.retrieverMode)}</span>
              <span>Final {form.finalScoreThreshold}</span>
            </div>
          </div>
          <div className="qf-console-health" aria-label="최근 게이팅 배치 상태">
            <span>Latest Batch</span>
            <strong>{latestGatingBatch ? shortId(latestGatingBatch.gatingBatchId) : '-'}</strong>
            <StatusBadge value={latestGatingBatch?.status || 'queued'} label={latestGatingBatch?.status || 'idle'} />
          </div>
        </div>

        <form className="qf-console-layout" onSubmit={runGating}>
          <aside className="qf-runtime-column">
            <section className="qf-runtime-panel qf-runtime-panel--context">
              <div className="qf-panel-heading">
                <span>01</span>
                <div>
                  <h3>Runtime Context</h3>
                  <p>Source identity / preset / model</p>
                </div>
              </div>

              <div className="qf-context-group">
                <div className="qf-context-group__title">Source Selection</div>
                <SelectField
                  label="생성 방식"
                  value={form.methodCode}
                  onChange={(value) => setForm((prev) => ({ ...prev, methodCode: value, generationBatchId: '', generationBatchIds: [] }))}
                >
                  <option value="">전체 방식</option>
                  {methods.map((method) => <option key={method.methodCode} value={method.methodCode}>{method.methodCode} - {method.methodName}</option>)}
                </SelectField>

                <SelectField
                  label="기본 생성 배치"
                  value={form.generationBatchId}
                  onChange={(value) => setForm((prev) => ({ ...prev, generationBatchId: value }))}
                >
                  <option value="" disabled>생성 배치를 선택하세요</option>
                  {formBatchOptions.map((batch) => <option key={batch.batchId} value={batch.batchId}>{batch.versionName} ({batch.methodCode})</option>)}
                </SelectField>

                <div className="qf-batch-picker">
                  <div className="qf-field-caption">Batch Set</div>
                  <div className="qf-batch-pick-list">
                    {formBatchOptions.map((batch) => {
                      const checked = Array.isArray(form.generationBatchIds) && form.generationBatchIds.includes(batch.batchId)
                      return (
                        <label key={batch.batchId} className={`check-pill qf-batch-pill ${checked ? 'is-active' : ''}`}>
                          <input
                            type="checkbox"
                            checked={checked}
                            onChange={(event) => setForm((prev) => {
                              const prevIds = Array.isArray(prev.generationBatchIds) ? prev.generationBatchIds : []
                              const nextIds = event.target.checked
                                ? Array.from(new Set([...prevIds, batch.batchId]))
                                : prevIds.filter((value) => value !== batch.batchId)
                              return { ...prev, generationBatchIds: nextIds }
                            })}
                          />
                          <span className="check-pill__box" aria-hidden="true">{checked ? '✓' : ''}</span>
                          <span className="check-pill__text">{batch.methodCode}:{batch.versionName}</span>
                        </label>
                      )
                    })}
                  </div>
                </div>
              </div>

              <div className="qf-context-group">
                <div className="qf-context-group__title">Strategy Runtime</div>
                <div className="qf-field-caption">Gating Preset</div>
                <div className="qf-segmented qf-segmented--preset" role="group" aria-label="게이팅 프리셋">
                  {['ungated', 'rule_only', 'rule_plus_llm', 'full_gating'].map((preset) => (
                    <button
                      key={preset}
                      type="button"
                      className={`qf-segmented__option ${form.gatingPreset === preset ? 'is-active' : ''}`}
                      aria-pressed={form.gatingPreset === preset}
                      onClick={() => setForm((prev) => ({ ...prev, gatingPreset: preset }))}
                    >
                      {preset}
                    </button>
                  ))}
                </div>

                <SelectField
                  label="LLM 모델"
                  value={form.llmModel}
                  onChange={(value) => setForm((prev) => ({ ...prev, llmModel: value }))}
                >
                  <option value="" disabled>LLM 모델 선택</option>
                  {runtimeOptions.llmModels.map((model) => <option key={model} value={model}>{model}</option>)}
                </SelectField>
              </div>
            </section>

          </aside>

          <main className="qf-gate-network">
            <div className="qf-pipeline-rail" aria-label="게이팅 파이프라인 상태">
              {pipelineStages.map((stage, index) => (
                <span key={stage.key} className={`qf-pipeline-node ${stage.active ? 'is-active' : 'is-inactive'}`}>
                  <strong>{stage.label}</strong>
                  {index < pipelineStages.length - 1 && <i aria-hidden="true" />}
                </span>
              ))}
            </div>

            <GateCard
              code="R"
              title="Rule Gate"
              description="Length / token / language ratio"
              checked={form.enableRuleFilter}
              tone="rule"
              meta={[
                { label: 'Short', value: `${form.ruleMinLengthShort}-${form.ruleMaxLengthShort}` },
                { label: 'Token', value: `${form.ruleMinTokens}-${form.ruleMaxTokens}` },
                { label: 'KR', value: form.ruleMinKoreanRatio },
              ]}
              onToggle={(checked) => setForm((prev) => ({ ...prev, enableRuleFilter: checked }))}
            >
              <ScoreGroup title="Length Bounds" accent="rule">
                <ControlField label="Short Min" value={form.ruleMinLengthShort} disabled={!form.enableRuleFilter} onChange={(value) => setForm((prev) => ({ ...prev, ruleMinLengthShort: value }))} />
                <ControlField label="Short Max" value={form.ruleMaxLengthShort} disabled={!form.enableRuleFilter} onChange={(value) => setForm((prev) => ({ ...prev, ruleMaxLengthShort: value }))} />
                <ControlField label="Long Min" value={form.ruleMinLengthLong} disabled={!form.enableRuleFilter} onChange={(value) => setForm((prev) => ({ ...prev, ruleMinLengthLong: value }))} />
                <ControlField label="Long Max" value={form.ruleMaxLengthLong} disabled={!form.enableRuleFilter} onChange={(value) => setForm((prev) => ({ ...prev, ruleMaxLengthLong: value }))} />
              </ScoreGroup>
              <ScoreGroup title="Token / Language" accent="rule">
                <ControlField label="Token Min" value={form.ruleMinTokens} disabled={!form.enableRuleFilter} onChange={(value) => setForm((prev) => ({ ...prev, ruleMinTokens: value }))} />
                <ControlField label="Token Max" value={form.ruleMaxTokens} disabled={!form.enableRuleFilter} onChange={(value) => setForm((prev) => ({ ...prev, ruleMaxTokens: value }))} />
                <ControlField label="Korean Ratio" step="0.01" value={form.ruleMinKoreanRatio} disabled={!form.enableRuleFilter} emphasis onChange={(value) => setForm((prev) => ({ ...prev, ruleMinKoreanRatio: value }))} />
              </ScoreGroup>
            </GateCard>

            <GateCard
              code="L"
              title="LLM Self-Eval"
              description="Semantic quality score contribution"
              checked={form.enableLlmSelfEval}
              tone="llm"
              meta={[
                { label: 'Weight', value: form.llmWeight },
                { label: 'Model', value: form.llmModel || '-' },
              ]}
              onToggle={(checked) => setForm((prev) => ({ ...prev, enableLlmSelfEval: checked }))}
            >
              <ScoreGroup title="Evaluator Weight" accent="llm">
                <ControlField label="LLM Weight" step="0.01" value={form.llmWeight} disabled={!form.enableLlmSelfEval} emphasis onChange={(value) => setForm((prev) => ({ ...prev, llmWeight: value }))} />
              </ScoreGroup>
              <div className={`qf-eval-signal ${form.enableLlmSelfEval ? 'is-active' : 'is-inactive'}`}>
                <span>Fluency</span>
                <span>Adequacy</span>
                <span>Answerability</span>
              </div>
            </GateCard>

            <GateCard
              code="U"
              title="Retrieval Utility"
              description="Top-K hit utility / consistency / bonuses"
              checked={form.enableRetrievalUtility}
              tone="utility"
              meta={[
                { label: 'Weight', value: form.utilityWeight },
                { label: 'Threshold', value: form.utilityThreshold },
              ]}
              onToggle={(checked) => setForm((prev) => ({ ...prev, enableRetrievalUtility: checked }))}
            >
              <ScoreGroup title="Top-K Retrieval" accent="utility">
                <ControlField label="Top1" step="0.01" value={form.utilityTargetTop1Score} disabled={!form.enableRetrievalUtility} emphasis onChange={(value) => setForm((prev) => ({ ...prev, utilityTargetTop1Score: value }))} />
                <ControlField label="Top3" step="0.01" value={form.utilityTargetTop3Score} disabled={!form.enableRetrievalUtility} onChange={(value) => setForm((prev) => ({ ...prev, utilityTargetTop3Score: value }))} />
                <ControlField label="Top5" step="0.01" value={form.utilityTargetTop5Score} disabled={!form.enableRetrievalUtility} onChange={(value) => setForm((prev) => ({ ...prev, utilityTargetTop5Score: value }))} />
                <ControlField label="Top10" step="0.01" value={form.utilityTargetTop10Score} disabled={!form.enableRetrievalUtility} onChange={(value) => setForm((prev) => ({ ...prev, utilityTargetTop10Score: value }))} />
              </ScoreGroup>
              <ScoreGroup title="Document Consistency" accent="doc">
                <ControlField label="Same Doc Top3" step="0.01" value={form.utilitySameDocTop3Score} disabled={!form.enableRetrievalUtility} onChange={(value) => setForm((prev) => ({ ...prev, utilitySameDocTop3Score: value }))} />
                <ControlField label="Same Doc Top5" step="0.01" value={form.utilitySameDocTop5Score} disabled={!form.enableRetrievalUtility} onChange={(value) => setForm((prev) => ({ ...prev, utilitySameDocTop5Score: value }))} />
                <ControlField label="Utility Threshold" step="0.01" value={form.utilityThreshold} disabled={!form.enableRetrievalUtility} emphasis onChange={(value) => setForm((prev) => ({ ...prev, utilityThreshold: value }))} />
              </ScoreGroup>
              <ScoreGroup title="Penalty / Bonus" accent="bonus">
                <ControlField label="Outside Top5" step="0.01" value={form.utilityOutsideTop5Score} disabled={!form.enableRetrievalUtility} onChange={(value) => setForm((prev) => ({ ...prev, utilityOutsideTop5Score: value }))} />
                <ControlField label="Multi Partial" step="0.01" value={form.utilityMultiPartialBonus} disabled={!form.enableRetrievalUtility} onChange={(value) => setForm((prev) => ({ ...prev, utilityMultiPartialBonus: value }))} />
                <ControlField label="Multi Full" step="0.01" value={form.utilityMultiFullBonus} disabled={!form.enableRetrievalUtility} onChange={(value) => setForm((prev) => ({ ...prev, utilityMultiFullBonus: value }))} />
              </ScoreGroup>
            </GateCard>

            <GateCard
              code="D"
              title="Diversity Gate"
              description="Near-duplicate suppression thresholds"
              checked={form.enableDiversity}
              tone="diversity"
              meta={[
                { label: 'Weight', value: form.diversityWeight },
                { label: 'Chunk', value: form.diversityThresholdSameChunk },
                { label: 'Doc', value: form.diversityThresholdSameDoc },
              ]}
              onToggle={(checked) => setForm((prev) => ({ ...prev, enableDiversity: checked }))}
            >
              <ScoreGroup title="Novelty Thresholds" accent="diversity">
                <ControlField label="Diversity Weight" step="0.01" value={form.diversityWeight} disabled={!form.enableDiversity} emphasis onChange={(value) => setForm((prev) => ({ ...prev, diversityWeight: value }))} />
                <ControlField label="Same Chunk" step="0.01" value={form.diversityThresholdSameChunk} disabled={!form.enableDiversity} onChange={(value) => setForm((prev) => ({ ...prev, diversityThresholdSameChunk: value }))} />
                <ControlField label="Same Doc" step="0.01" value={form.diversityThresholdSameDoc} disabled={!form.enableDiversity} onChange={(value) => setForm((prev) => ({ ...prev, diversityThresholdSameDoc: value }))} />
              </ScoreGroup>
            </GateCard>
          </main>

          <aside className="qf-sidecar">
            <section className="qf-runtime-panel">
              <div className="qf-panel-heading">
                <span>02</span>
                <div>
                  <h3>Retriever</h3>
                  <p>Dense / BM25 / rerank runtime</p>
                </div>
              </div>

              <div className="qf-field-caption">Retrieval Mode</div>
              <div className="qf-segmented qf-segmented--retriever" role="group" aria-label="검색 모드">
                {(runtimeOptions.retrieverModes.length > 0 ? runtimeOptions.retrieverModes : [form.retrieverMode]).filter(Boolean).map((mode) => (
                  <button
                    key={mode}
                    type="button"
                    className={`qf-segmented__option ${form.retrieverMode === mode ? 'is-active' : ''}`}
                    aria-pressed={form.retrieverMode === mode}
                    onClick={() => setForm((prev) => ({
                      ...prev,
                      retrieverMode: mode,
                      denseEmbeddingRequired: mode === 'bm25_only' ? false : prev.denseEmbeddingRequired,
                    }))}
                  >
                    {retrieverModeLabel(mode)}
                  </button>
                ))}
              </div>

              <SelectField
                label="Dense 모델"
                value={form.denseEmbeddingModel}
                disabled={form.retrieverMode === 'bm25_only'}
                onChange={(value) => setForm((prev) => ({ ...prev, denseEmbeddingModel: value }))}
              >
                {runtimeOptions.denseEmbeddingModels.map((model) => <option key={model} value={model}>{model}</option>)}
              </SelectField>

              <ScoreGroup title="Fusion Weights" accent="retriever">
                <ControlField label="Candidate K" value={form.retrieverCandidatePoolK} onChange={(value) => setForm((prev) => ({ ...prev, retrieverCandidatePoolK: value }))} />
                <ControlField label="Dense" step="0.01" value={form.retrieverDenseWeight} disabled={form.retrieverMode === 'bm25_only'} onChange={(value) => setForm((prev) => ({ ...prev, retrieverDenseWeight: value }))} />
                <ControlField label="BM25" step="0.01" value={form.retrieverBm25Weight} onChange={(value) => setForm((prev) => ({ ...prev, retrieverBm25Weight: value }))} />
                <ControlField label="Technical" step="0.01" value={form.retrieverTechnicalWeight} disabled={form.retrieverMode === 'bm25_only'} onChange={(value) => setForm((prev) => ({ ...prev, retrieverTechnicalWeight: value }))} />
              </ScoreGroup>

              <div className="qf-capability-grid">
                <CapabilityChip
                  label="Dense Required"
                  meta="embedding gate"
                  checked={form.denseEmbeddingRequired}
                  disabled={form.retrieverMode === 'bm25_only'}
                  onChange={(checked) => setForm((prev) => ({ ...prev, denseEmbeddingRequired: checked }))}
                />
                <CapabilityChip
                  label="Hash Fallback"
                  meta="backup path"
                  checked={form.denseFallbackEnabled}
                  disabled={form.retrieverMode === 'bm25_only'}
                  onChange={(checked) => setForm((prev) => ({ ...prev, denseFallbackEnabled: checked }))}
                />
                <CapabilityChip
                  label="Cohere Rerank"
                  meta="rank polish"
                  checked={form.retrieverRerankEnabled}
                  onChange={(checked) => setForm((prev) => ({ ...prev, retrieverRerankEnabled: checked }))}
                />
              </div>
            </section>

            <section className="qf-runtime-panel qf-runtime-panel--summary">
              <div className="qf-panel-heading">
                <span>03</span>
                <div>
                  <h3>Active Config</h3>
                  <p>Runtime summary</p>
                </div>
              </div>
              <div className="qf-summary-list">
                {gatingSummaryItems.map((item) => (
                  <div className="qf-summary-item" key={item.label}>
                    <span>{item.label}</span>
                    <strong title={String(item.value || '-')}>{item.value || '-'}</strong>
                  </div>
                ))}
              </div>
              <BalanceBar
                items={[
                  { label: 'LLM', value: toNumber(form.llmWeight), tone: 'blue' },
                  { label: 'Utility', value: toNumber(form.utilityWeight), tone: 'green' },
                  { label: 'Diversity', value: toNumber(form.diversityWeight), tone: 'amber' },
                ]}
              />
            </section>

            <section className="qf-runtime-panel qf-runtime-panel--launch">
              <div className="qf-panel-heading">
                <span>04</span>
                <div>
                  <h3>Launch</h3>
                  <p>Queued quality-gating operation</p>
                </div>
              </div>
              <ControlField
                label="최종 점수 임계치"
                step="0.01"
                min="0"
                max="1"
                value={form.finalScoreThreshold}
                emphasis
                onChange={(value) => setForm((prev) => ({ ...prev, finalScoreThreshold: value }))}
              />
              <button type="submit" className="button button--success qf-launch-button" disabled={runDisabled}>
                <span className="qf-launch-button__spark" aria-hidden="true" />
                {runningGating ? '큐 등록 중' : '게이팅 실행'}
              </button>
              <div className="qf-launch-state" data-ready={runDisabled ? 'false' : 'true'}>
                {runningGating ? 'Queueing operation' : (runDisabled ? 'Waiting for required config' : 'Ready to launch')}
              </div>
            </section>
          </aside>
        </form>
      </section>

      <section className="table-shell">
        <div className="table-header"><div className="table-title">게이팅 배치 이력</div><button type="button" className="button" onClick={() => loadGatingBatches().catch((error) => notify(error.message, 'error'))}>새로고침</button></div>
        <div className="table-wrap">
          <table className="data-table">
            <thead><tr><th>게이팅 배치 ID</th><th>프리셋</th><th>방식</th><th>상태</th><th>처리 수</th><th>승인 수</th><th>승인률</th><th>상세</th></tr></thead>
            <tbody>
              {pagedGatingBatches.map((batch) => {
                const acceptance = batch.processedCount > 0 ? ((batch.acceptedCount / batch.processedCount) * 100).toFixed(1) : '0.0'
                return (
                  <tr key={batch.gatingBatchId}>
                    <td><IdBadge value={batch.gatingBatchId} /></td><td>{renderPresetRetrieverBadges(batch)}</td><td>{batch.methodCode || '-'}</td><td><StatusBadge value={batch.status} /></td>
                    <td>{batch.processedCount ?? 0}</td><td>{batch.acceptedCount ?? 0}</td><td><div>{acceptance}%</div><RemainingEta remainingSeconds={batch.estimatedRemainingSeconds} secondsPerUnit={batch.estimatedSecondsPerQuery} completedCount={batch.processedCount} totalCount={batch.targetQueryCount} unitLabel="query" status={batch.status} compact /></td>
                    <td><button type="button" className="button button--ghost" onClick={() => openBatchDetail(batch)}>상세 조회</button></td>
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
            disabled={currentGatingBatchPage === 0}
            onClick={() => setGatingBatchPage((prev) => Math.max(0, prev - 1))}
          >이전</button>
          <div className="pagination__label">페이지 {currentGatingBatchPage + 1}</div>
          <button
            type="button"
            className="button"
            disabled={currentGatingBatchPage + 1 >= gatingBatchTotalPages}
            onClick={() => setGatingBatchPage((prev) => Math.min(gatingBatchTotalPages - 1, prev + 1))}
          >다음</button>
        </div>
      </section>

      <LlmJobsTable
        jobs={llmJobs}
        onAction={executeLlmAction}
        onDetail={openJobDetail}
        loaded={llmJobsLoaded}
        loading={llmJobsLoading}
        onLoad={() => loadLlmJobs().catch((error) => notify(error.message, 'error'))}
      />

      <section className="table-shell">
        <div className="table-header"><div className="table-title">게이팅 퍼널</div></div>
        <form className="filter-bar" onSubmit={applyFunnelFilter}>
          <label className="filter-field">생성 방식
            <select value={funnelFilter.methodCode} onChange={(event) => setFunnelFilter((prev) => ({ ...prev, methodCode: event.target.value, generationBatchId: '', gatingBatchId: '' }))}>
              <option value="" disabled>생성 방식 선택</option>{methods.map((method) => <option key={method.methodCode} value={method.methodCode}>{method.methodCode}</option>)}
            </select>
          </label>
          <label className="filter-field">생성 배치
            <select value={funnelFilter.generationBatchId} disabled={!funnelFilter.methodCode} onChange={(event) => setFunnelFilter((prev) => ({ ...prev, generationBatchId: event.target.value, gatingBatchId: '' }))}>
              <option value="" disabled>생성 배치 선택</option>{funnelBatchOptions.map((batch) => <option key={batch.batchId} value={batch.batchId}>{batch.versionName} ({batch.methodCode})</option>)}
            </select>
          </label>
          <label className="filter-field">게이팅 배치
            <select value={funnelFilter.gatingBatchId} onChange={(event) => setFunnelFilter((prev) => ({ ...prev, gatingBatchId: event.target.value }))}>
              <option value="">최신 배치</option>{funnelGatingBatchOptions.map((batch) => <option key={batch.gatingBatchId} value={batch.gatingBatchId}>{shortId(batch.gatingBatchId)} ({batch.gatingPreset}, {batch.status || '-'})</option>)}
            </select>
          </label>
          <div className="filter-field filter-field--small"><button type="submit" className="button button--primary" disabled={!isFilterReady(funnelFilter) || !funnelReferenceBatchId}>조회</button></div>
        </form>
        <section className="summary-grid">
          {funnelCards.map((card) => (
            <article className="summary-card" key={card.label}>
              <div className="summary-card__label">{card.label}</div>
              <div className="summary-card__value">{card.value}</div>
            </article>
          ))}
        </section>
      </section>

      <section className="table-shell">
        <div className="table-header"><div className="table-title">질의별 게이팅 결과</div></div>
        <form className="filter-bar" onSubmit={applyResultFilter}>
          <label className="filter-field">생성 방식
            <select value={resultFilter.methodCode} onChange={(event) => setResultFilter((prev) => ({ ...prev, methodCode: event.target.value, generationBatchId: '', gatingBatchId: '' }))}>
              <option value="" disabled>생성 방식 선택</option>{methods.map((method) => <option key={method.methodCode} value={method.methodCode}>{method.methodCode}</option>)}
            </select>
          </label>
          <label className="filter-field">생성 배치
            <select value={resultFilter.generationBatchId} disabled={!resultFilter.methodCode} onChange={(event) => setResultFilter((prev) => ({ ...prev, generationBatchId: event.target.value, gatingBatchId: '' }))}>
              <option value="" disabled>생성 배치 선택</option>{resultBatchOptions.map((batch) => <option key={batch.batchId} value={batch.batchId}>{batch.versionName} ({batch.methodCode})</option>)}
            </select>
          </label>
          <label className="filter-field">게이팅 배치
            <select value={resultFilter.gatingBatchId} onChange={(event) => setResultFilter((prev) => ({ ...prev, gatingBatchId: event.target.value }))}>
              <option value="">최신 배치</option>{resultGatingBatchOptions.map((batch) => <option key={batch.gatingBatchId} value={batch.gatingBatchId}>{shortId(batch.gatingBatchId)} ({batch.gatingPreset}, {batch.status || '-'})</option>)}
            </select>
          </label>
          <label className="filter-field">통과 단계
            <select value={resultFilter.passStage} onChange={(event) => setResultFilter((prev) => ({ ...prev, passStage: event.target.value }))}>
              <option value="">전체</option>
              <option value="failed_rule">Rule 탈락</option>
              <option value="passed_rule">Rule 통과 -&gt; LLM 탈락</option>
              <option value="passed_llm">LLM 통과 -&gt; Utility 탈락</option>
              <option value="passed_utility">Utility 통과 -&gt; Diversity 탈락</option>
              <option value="passed_diversity">Diversity 통과 -&gt; Final 탈락</option>
              <option value="passed_all">전체 통과</option>
            </select>
          </label>
          <div className="filter-field filter-field--small"><button type="submit" className="button button--primary" disabled={!isFilterReady(resultFilter) || !resultReferenceBatchId}>조회</button></div>
        </form>
        <div className="table-wrap">
          <table className="data-table">
            <thead><tr><th>질의 ID</th><th>질의 문장</th><th>유형</th><th>Rule</th><th>LLM</th><th>Utility</th><th>Diversity</th><th>Final</th><th>리젝트 단계</th><th>리젝트 사유</th><th>최종</th></tr></thead>
            <tbody>
              {results.map((row) => (
                <tr key={row.syntheticQueryId}>
                  <td><IdBadge value={row.syntheticQueryId} plain /></td><td>{row.queryText}</td><td>{renderTokenBadges(row.queryType, 'query')}</td>
                  <td>{renderStageStatus(row.passedRule)}</td>
                  <td>{renderStageStatus(row.passedLlm)}</td>
                  <td>{renderStageStatus(row.passedUtility)}</td>
                  <td>{renderStageStatus(row.passedDiversity)}</td>
                  <td>{row.finalScore == null ? '-' : Number(row.finalScore).toFixed(4)}</td><td>{renderTokenBadges(row.rejectedStage, 'stage')}</td><td>{renderTokenBadges(row.rejectedReason, 'reason')}</td>
                  <td>{row.finalDecision ? <StatusBadge value="success" label="승인" /> : <StatusBadge value="failed" label="거절" />}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="pagination">
          <button
            type="button"
            className="button"
            disabled={!resultReferenceBatchId || resultPage === 0}
            onClick={() => {
              const nextPage = Math.max(0, resultPage - 1)
              setResultPage(nextPage)
              loadResults(resultReferenceBatchId, nextPage, resultFilter.methodCode, resultFilter.passStage).catch((error) => notify(error.message, 'error'))
            }}
          >이전</button>
          <div className="pagination__label">페이지 {resultPage + 1}</div>
          <button
            type="button"
            className="button"
            disabled={!resultReferenceBatchId || !resultHasNextPage}
            onClick={() => {
              const nextPage = resultPage + 1
              setResultPage(nextPage)
              loadResults(resultReferenceBatchId, nextPage, resultFilter.methodCode, resultFilter.passStage).catch((error) => notify(error.message, 'error'))
            }}
          >다음</button>
        </div>
      </section>

      <Modal data={modal} onClose={() => setModal(null)} />
    </>
  )
}
