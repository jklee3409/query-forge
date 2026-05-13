import { useEffect, useState } from 'react'
import {
  BatchJobCard,
  ConfirmDialog,
  EmptyState,
  MetricCard,
  ProgressMetric,
  SectionHeader,
  StrategyFlow,
} from '../components/AdminUi.jsx'
import { DetailCard, IdBadge, Modal, StatusBadge } from '../components/Common.jsx'
import { LlmJobsTable } from '../components/LlmJobsTable.jsx'
import { RemainingEta } from '../components/RemainingEta.jsx'
import { fetchSyntheticMethods, queryString, requestJson, toNumber } from '../lib/api.js'
import { fmtTime, shortId } from '../lib/format.js'
import { usePolling } from '../lib/hooks.js'

const STRATEGY_UI_META = {
  A: {
    flow: ['EN Doc', 'EN Summary', 'EN Query', 'KO Translation'],
    badges: ['Spring EN', 'translation path', 'KO output'],
    purpose: 'English-first generation with Korean translated user-facing queries.',
    accent: 'blue',
  },
  B: {
    flow: ['EN Doc', 'KO Summary', 'Natural KO Query'],
    badges: ['Spring EN', 'natural KO', 'summary guided'],
    purpose: 'Natural Korean developer questions grounded in English technical docs.',
    accent: 'green',
  },
  C: {
    flow: ['EN Doc', 'KO Summary', 'Structured KO Query'],
    badges: ['Spring EN', 'precise', 'multi-step'],
    purpose: 'Structured Korean queries for precise retrieval and multi-chunk reasoning.',
    accent: 'violet',
  },
  D: {
    flow: ['EN Doc', 'Anchors', 'KO + Code Query'],
    badges: ['Spring EN', 'code-mixed', 'anchor preserving'],
    purpose: 'Code-mixed Korean queries that preserve technical tokens.',
    accent: 'amber',
  },
  E: {
    flow: ['EN Doc', 'EN Summary', 'EN Query'],
    badges: ['Spring EN', 'English-native', 'anchor preserving'],
    purpose: 'English-native developer questions grounded in the Spring corpus.',
    accent: 'slate',
  },
  F: {
    flow: ['KR Doc', 'KR Summary', 'KR Query', 'EN Query'],
    badges: ['Python KR', 'dual language', 'translation bridge'],
    purpose: 'Korean-source flow that emits Korean and English query variants.',
    accent: 'cyan',
  },
  G: {
    flow: ['KR Doc', 'KR Summary', 'KR Query'],
    badges: ['Python KR', 'Korean-native', 'source strict'],
    purpose: 'Korean-native generation flow for Korean technical documents.',
    accent: 'rose',
  },
}

function strategyMeta(method) {
  return STRATEGY_UI_META[String(method?.methodCode || '').toUpperCase()] || {
    flow: [method?.queryLanguageStrategy || 'Query Strategy'].filter(Boolean),
    badges: ['DB-driven'],
    purpose: method?.description || 'Server-provided strategy metadata.',
    accent: 'slate',
  }
}

function compactStatus(value) {
  return String(value || '-').toLowerCase()
}

export function SyntheticPage({ notify }) {
  const pageSize = 20
  const historyPageSize = 4

  const [methods, setMethods] = useState([])
  const [runMethods, setRunMethods] = useState([])
  const [batches, setBatches] = useState([])
  const [sources, setSources] = useState([])
  const [sourceDocuments, setSourceDocuments] = useState([])
  const [queries, setQueries] = useState([])
  const [stats, setStats] = useState({ byMethod: [], byQueryType: [] })
  const [llmJobs, setLlmJobs] = useState([])
  const [llmJobsLoaded, setLlmJobsLoaded] = useState(false)
  const [llmJobsLoading, setLlmJobsLoading] = useState(false)
  const [runtimeOptions, setRuntimeOptions] = useState({ llmModels: [], defaultLlmModel: '' })
  const [historyPage, setHistoryPage] = useState(0)
  const [queryPage, setQueryPage] = useState(0)
  const [hasNextPage, setHasNextPage] = useState(false)
  const [modal, setModal] = useState(null)
  const [batchFilters, setBatchFilters] = useState({
    status: 'all',
    method: 'all',
    search: '',
    sort: 'newest',
  })
  const [pendingDeleteBatch, setPendingDeleteBatch] = useState(null)
  const [deletingBatchId, setDeletingBatchId] = useState('')

  const [runForm, setRunForm] = useState({
    methodCode: '',
    sourceId: '',
    sourceDocumentId: '',
    versionName: '',
    limitChunks: '',
    avgQueriesPerChunk: '2.0',
    maxTotalQueries: '1000',
    chunkSamplingMode: 'random',
    llmModel: '',
    llmRpm: '1000',
  })

  const [filterForm, setFilterForm] = useState({
    method_code: '',
    batch_id: '',
    query_type: '',
    gated: '',
  })

  const loadMethods = async () => {
    const rows = await fetchSyntheticMethods()
    const normalized = Array.isArray(rows) ? rows : []
    setMethods(normalized)
    setRunForm((prev) => ({ ...prev, methodCode: prev.methodCode || normalized[0]?.methodCode || '' }))
  }

  const loadRunMethods = async (sourceId, sourceDocumentId) => {
    const rows = await fetchSyntheticMethods({
      sourceId: sourceId || null,
      sourceDocumentId: sourceDocumentId || null,
    })
    const normalized = Array.isArray(rows) ? rows : []
    setRunMethods(normalized)
    setRunForm((prev) => {
      const stillValid = normalized.some((method) => method.methodCode === prev.methodCode)
      return {
        ...prev,
        methodCode: stillValid ? prev.methodCode : (normalized[0]?.methodCode || ''),
      }
    })
  }

  const loadRuntimeOptions = async () => {
    const payload = await requestJson('/api/admin/console/runtime/options')
    const llmModels = Array.isArray(payload.llmModels) ? payload.llmModels.filter(Boolean) : []
    const defaultLlmModel = payload.defaultLlmModel || llmModels[0] || ''
    setRuntimeOptions({ llmModels, defaultLlmModel })
    setRunForm((prev) => ({
      ...prev,
      llmModel: prev.llmModel || defaultLlmModel,
    }))
  }

  const loadBatches = async () => {
    const rows = await requestJson('/api/admin/console/synthetic/batches?limit=50')
    setBatches(Array.isArray(rows) ? rows : [])
  }

  const loadSources = async () => {
    let sourceRows = await requestJson('/api/admin/corpus/sources')
    if (!Array.isArray(sourceRows) || sourceRows.length === 0) {
      const docs = await requestJson('/api/admin/corpus/documents?active_only=true&limit=300')
      const dedup = new Map()
      ;(Array.isArray(docs) ? docs : []).forEach((doc) => {
        if (!doc?.sourceId || dedup.has(doc.sourceId)) return
        dedup.set(doc.sourceId, { sourceId: doc.sourceId, productName: doc.productName || '-' })
      })
      sourceRows = Array.from(dedup.values())
    }
    setSources(Array.isArray(sourceRows) ? sourceRows : [])
  }

  const loadSourceDocuments = async (sourceId) => {
    if (!sourceId) {
      setSourceDocuments([])
      return
    }
    const query = queryString({ source_id: sourceId, active_only: true, limit: 200 })
    const rows = await requestJson(`/api/admin/corpus/documents?${query}`)
    setSourceDocuments(Array.isArray(rows) ? rows : [])
  }

  const loadStats = async () => {
    const query = queryString({ method_code: filterForm.method_code || null, batch_id: filterForm.batch_id || null })
    const payload = await requestJson(`/api/admin/console/synthetic/stats${query ? `?${query}` : ''}`)
    setStats({
      byMethod: Array.isArray(payload.byMethod) ? payload.byMethod : [],
      byQueryType: Array.isArray(payload.byQueryType) ? payload.byQueryType : [],
    })
  }

  const loadQueries = async (page = queryPage) => {
    const query = queryString({
      method_code: filterForm.method_code || null,
      batch_id: filterForm.batch_id || null,
      query_type: filterForm.query_type || null,
      gated: filterForm.gated || null,
      limit: pageSize + 1,
      offset: page * pageSize,
    })
    const rows = await requestJson(`/api/admin/console/synthetic/queries?${query}`)
    const normalized = Array.isArray(rows) ? rows : []
    setHasNextPage(normalized.length > pageSize)
    setQueries(normalized.slice(0, pageSize))
  }

  const loadLlmJobs = async () => {
    setLlmJobsLoading(true)
    try {
      const rows = await requestJson('/api/admin/console/llm-jobs?limit=120')
      const filtered = (Array.isArray(rows) ? rows : []).filter((job) => job.jobType === 'GENERATE_SYNTHETIC_QUERY' || job.generationBatchId)
      setLlmJobs(filtered)
      setLlmJobsLoaded(true)
    } finally {
      setLlmJobsLoading(false)
    }
  }

  useEffect(() => {
    Promise.all([loadMethods(), loadBatches(), loadSources(), loadRuntimeOptions()])
      .then(() => Promise.all([loadRunMethods('', ''), loadQueries(0), loadStats()]))
      .catch((error) => notify(error.message, 'error'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    Promise.all([
      loadSourceDocuments(runForm.sourceId),
      loadRunMethods(runForm.sourceId, runForm.sourceDocumentId),
    ]).catch((error) => notify(error.message, 'error'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runForm.sourceId, runForm.sourceDocumentId])

  useEffect(() => {
    const totalPages = Math.max(1, Math.ceil(batches.length / historyPageSize))
    if (historyPage > totalPages - 1) {
      setHistoryPage(totalPages - 1)
    }
  }, [batches, historyPage])

  useEffect(() => {
    setHistoryPage(0)
  }, [batchFilters.status, batchFilters.method, batchFilters.search, batchFilters.sort])

  const isBatchSelectableInQueryFilter = (batch) => {
    const status = compactStatus(batch?.status)
    return status !== 'failed' && status !== 'cancelled'
  }

  const hasActiveGenerationBatch = batches.some((batch) => {
    const status = compactStatus(batch?.status)
    return status === 'planned' || status === 'queued' || status === 'running'
  })

  const isBatchDeletable = (batch) => {
    const status = compactStatus(batch?.status)
    return status !== 'planned' && status !== 'queued' && status !== 'running'
  }

  const formatLlmJobState = (batch) => {
    const jobStatus = batch?.llmJobStatus || '-'
    const itemStatus = batch?.llmJobItemStatus || '-'
    const retries = batch?.llmRetryCount ?? 0
    const maxRetries = Number(batch?.llmMaxRetries) < 0 ? 'unlimited' : (batch?.llmMaxRetries ?? 0)
    return `${jobStatus} / ${itemStatus} (${retries}/${maxRetries})`
  }

  usePolling(hasActiveGenerationBatch, 3000, () => {
    loadBatches().catch(() => {})
  })

  useEffect(() => {
    if (!filterForm.batch_id) return
    const selectedStillValid = batches.some((batch) => batch.batchId === filterForm.batch_id && isBatchSelectableInQueryFilter(batch))
    if (!selectedStillValid) {
      setFilterForm((prev) => ({ ...prev, batch_id: '' }))
    }
  }, [batches, filterForm.batch_id])

  const executeRun = async (event) => {
    event.preventDefault()
    if (!runForm.sourceId && !runForm.sourceDocumentId) {
      notify('Select a source or source document before starting generation.', 'error')
      return
    }
    if (!runForm.llmModel) {
      notify('Select an LLM model.', 'error')
      return
    }
    try {
      await requestJson('/api/admin/console/synthetic/batches/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          methodCode: runForm.methodCode || null,
          sourceId: runForm.sourceId || null,
          sourceDocumentId: runForm.sourceDocumentId || null,
          versionName: runForm.versionName || null,
          limitChunks: toNumber(runForm.limitChunks),
          avgQueriesPerChunk: toNumber(runForm.avgQueriesPerChunk),
          maxTotalQueries: toNumber(runForm.maxTotalQueries),
          randomChunkSampling: runForm.chunkSamplingMode === 'random',
          llmModel: runForm.llmModel || runtimeOptions.defaultLlmModel || null,
          llmRpm: toNumber(runForm.llmRpm),
        }),
      })
      const refreshTasks = [loadBatches(), loadStats(), loadQueries(queryPage)]
      if (llmJobsLoaded) refreshTasks.push(loadLlmJobs())
      await Promise.all(refreshTasks)
      notify('Synthetic generation batch has been queued.')
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const openQueryDetail = async (queryId) => {
    try {
      const payload = await requestJson(`/api/admin/console/synthetic/queries/${queryId}`)
      setModal({
        title: `Synthetic query detail - ${shortId(queryId)}`,
        body: (
          <div className="detail-grid detail-grid--single">
            <DetailCard label="query text" value={payload.queryText || '-'} mono={false} />
            <DetailCard label="method / type" value={`${payload.generationMethod || '-'} / ${payload.queryType || '-'}`} />
            <DetailCard label="batch / language" value={`${payload.generationBatchId || '-'} / ${payload.languageProfile || '-'}`} />
            <DetailCard label="source_chunk" value={JSON.stringify(payload.sourceChunk || {}, null, 2)} />
            <DetailCard label="source_links" value={JSON.stringify(payload.sourceLinks || {}, null, 2)} />
            <DetailCard label="mapped_anchors" value={JSON.stringify(payload.mappedAnchors || [], null, 2)} />
            <DetailCard label="raw_output" value={JSON.stringify(payload.rawOutput || {}, null, 2)} />
          </div>
        ),
      })
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const executeLlmAction = async (jobId, action) => {
    try {
      await requestJson(`/api/admin/console/llm-jobs/${jobId}/${action}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })
      const refreshTasks = [loadBatches(), loadStats(), loadQueries(queryPage)]
      if (llmJobsLoaded) refreshTasks.push(loadLlmJobs())
      await Promise.all(refreshTasks)
      notify(`JOB ${action} request has been sent.`)
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const deleteBatch = async (batch) => {
    if (!batch?.batchId) return
    if (!isBatchDeletable(batch)) {
      notify('Running batch cannot be deleted. Cancel the job first.', 'error')
      return
    }
    setPendingDeleteBatch(batch)
  }

  const confirmDeleteBatch = async () => {
    const batch = pendingDeleteBatch
    if (!batch?.batchId) return
    setDeletingBatchId(batch.batchId)
    try {
      await requestJson(`/api/admin/console/synthetic/batches/${batch.batchId}`, { method: 'DELETE' })
      const refreshTasks = [loadBatches(), loadStats(), loadQueries(queryPage)]
      if (llmJobsLoaded) refreshTasks.push(loadLlmJobs())
      await Promise.all(refreshTasks)
      notify('Synthetic batch history deleted.')
      setPendingDeleteBatch(null)
    } catch (error) {
      notify(error.message, 'error')
    } finally {
      setDeletingBatchId('')
    }
  }

  const openJobDetail = async (jobId) => {
    try {
      const [job, items] = await Promise.all([
        requestJson(`/api/admin/console/llm-jobs/${jobId}`),
        requestJson(`/api/admin/console/llm-jobs/${jobId}/items`),
      ])
      setModal({
        title: `LLM job detail - ${shortId(jobId)}`,
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

  const byMethod = stats.byMethod || []
  const byType = stats.byQueryType || []
  const total = byMethod.reduce((sum, item) => sum + Number(item.count || 0), 0)
  const queryFilterBatchOptions = batches.filter(isBatchSelectableInQueryFilter)
  const runMethodOptions = runMethods.length > 0 ? runMethods : methods
  const methodCountMap = new Map(byMethod.map((item) => [String(item.method_code || item.methodCode || '').toUpperCase(), Number(item.count || 0)]))
  const batchStatusOptions = Array.from(new Set(batches.map((batch) => compactStatus(batch.status)).filter(Boolean))).sort()
  const batchMethodOptions = Array.from(new Set(batches.map((batch) => String(batch.methodCode || '').toUpperCase()).filter(Boolean))).sort()
  const filteredBatches = batches
    .filter((batch) => {
      const status = compactStatus(batch.status)
      const method = String(batch.methodCode || '').toUpperCase()
      const search = batchFilters.search.trim().toLowerCase()
      const matchesStatus = batchFilters.status === 'all' || status === batchFilters.status
      const matchesMethod = batchFilters.method === 'all' || method === batchFilters.method
      const haystack = [batch.batchId, batch.versionName, batch.methodCode, batch.status, batch.sourceGenerationRunId]
        .filter(Boolean)
        .join(' ')
        .toLowerCase()
      const matchesSearch = !search || haystack.includes(search)
      return matchesStatus && matchesMethod && matchesSearch
    })
    .sort((left, right) => {
      const leftTime = new Date(left.startedAt || left.finishedAt || 0).getTime()
      const rightTime = new Date(right.startedAt || right.finishedAt || 0).getTime()
      if (batchFilters.sort === 'oldest') return leftTime - rightTime
      if (batchFilters.sort === 'generated_desc') return Number(right.totalGeneratedCount || 0) - Number(left.totalGeneratedCount || 0)
      if (batchFilters.sort === 'generated_asc') return Number(left.totalGeneratedCount || 0) - Number(right.totalGeneratedCount || 0)
      return rightTime - leftTime
    })
  const historyTotalPages = Math.max(1, Math.ceil(filteredBatches.length / historyPageSize))
  const currentHistoryPage = Math.min(historyPage, historyTotalPages - 1)
  const pagedBatches = filteredBatches.slice(currentHistoryPage * historyPageSize, (currentHistoryPage + 1) * historyPageSize)

  return (
    <>
      <section className="admin-card strategy-dashboard">
        <SectionHeader
          eyebrow="Generation Methods"
          title="Strategy Cards"
          description="A/G strategy metadata is still loaded from the backend. The cards only change how the same contract is displayed."
        />
        <div className="strategy-card-grid">
          {methods.map((method) => {
            const meta = strategyMeta(method)
            const code = String(method.methodCode || '').toUpperCase()
            return (
              <article className="strategy-card" data-accent={meta.accent} key={method.methodCode}>
                <div className="strategy-card__top">
                  <div className="strategy-card__code">{method.methodCode}</div>
                  <StatusBadge value={method.active ? 'success' : 'failed'} label={method.active ? 'Active' : 'Inactive'} />
                </div>
                <h3>{method.methodName || `Strategy ${method.methodCode}`}</h3>
                <p>{method.description || meta.purpose}</p>
                <StrategyFlow steps={meta.flow} />
                <div className="strategy-card__badges">
                  {meta.badges.map((badge) => <span key={badge}>{badge}</span>)}
                  {method.queryLanguageStrategy && <span>{method.queryLanguageStrategy}</span>}
                  {method.summaryStrategy && <span>{method.summaryStrategy}</span>}
                </div>
                <div className="strategy-card__meta">
                  <div><span>Prompt</span><strong>{method.promptTemplateVersion || '-'}</strong></div>
                  <div><span>Queries</span><strong>{methodCountMap.get(code) ?? 0}</strong></div>
                  <div><span>Terminology</span><strong>{method.terminologyPreservationRule || '-'}</strong></div>
                </div>
              </article>
            )
          })}
        </div>
      </section>

      <section className="admin-card">
        <SectionHeader
          eyebrow="Run Builder"
          title="Synthetic Generation"
          description="Source-scoped method options and request payload fields are unchanged."
        />
        <form className="builder-form" onSubmit={executeRun}>
          <div className="strategy-selector-grid" role="radiogroup" aria-label="Generation strategy">
            {runMethodOptions.map((method) => {
              const selected = runForm.methodCode === method.methodCode
              const meta = strategyMeta(method)
              return (
                <button
                  key={method.methodCode}
                  type="button"
                  className={`strategy-selector-card ${selected ? 'is-selected' : ''}`}
                  data-accent={meta.accent}
                  onClick={() => setRunForm((prev) => ({ ...prev, methodCode: method.methodCode }))}
                  aria-pressed={selected}
                >
                  <span>{method.methodCode}</span>
                  <strong>{method.methodName || `Strategy ${method.methodCode}`}</strong>
                  <small>{method.promptTemplateVersion || 'prompt version n/a'}</small>
                </button>
              )
            })}
          </div>
          <div className="form-grid form-grid--3">
            <label className="filter-field">Source
              <select value={runForm.sourceId} onChange={(event) => setRunForm((prev) => ({ ...prev, sourceId: event.target.value, sourceDocumentId: '' }))}>
                <option value="">All sources</option>
                {sources.map((source) => <option key={source.sourceId} value={source.sourceId}>{source.sourceId} ({source.productName || '-'})</option>)}
              </select>
              <span className="field-hint">F/G restrictions remain enforced by source-scoped backend options.</span>
            </label>
            <label className="filter-field">Source Document
              <select value={runForm.sourceDocumentId} onChange={(event) => setRunForm((prev) => ({ ...prev, sourceDocumentId: event.target.value }))}>
                <option value="">All active documents</option>
                {sourceDocuments.map((doc) => <option key={doc.documentId} value={doc.documentId}>{doc.documentId} | {doc.title}</option>)}
              </select>
            </label>
            <label className="filter-field">Batch Version
              <input value={runForm.versionName} placeholder="c-main-v1" onChange={(event) => setRunForm((prev) => ({ ...prev, versionName: event.target.value }))} />
            </label>
            <label className="filter-field filter-field--small">Chunk Limit
              <input type="number" min="1" value={runForm.limitChunks} onChange={(event) => setRunForm((prev) => ({ ...prev, limitChunks: event.target.value }))} />
            </label>
            <label className="filter-field filter-field--small">Avg Queries / Chunk
              <input type="number" min="0.2" max="20" step="0.1" value={runForm.avgQueriesPerChunk} onChange={(event) => setRunForm((prev) => ({ ...prev, avgQueriesPerChunk: event.target.value }))} />
            </label>
            <label className="filter-field filter-field--small">Target Queries
              <input type="number" min="1" max="2000" value={runForm.maxTotalQueries} onChange={(event) => setRunForm((prev) => ({ ...prev, maxTotalQueries: event.target.value }))} />
            </label>
            <label className="filter-field filter-field--small">Chunk Sampling
              <div className="segmented">
                <button type="button" className={`segmented__option ${runForm.chunkSamplingMode === 'random' ? 'is-active' : ''}`} onClick={() => setRunForm((prev) => ({ ...prev, chunkSamplingMode: 'random' }))}>Random</button>
                <button type="button" className={`segmented__option ${runForm.chunkSamplingMode === 'ordered' ? 'is-active' : ''}`} onClick={() => setRunForm((prev) => ({ ...prev, chunkSamplingMode: 'ordered' }))}>Document order</button>
              </div>
            </label>
            <label className="filter-field">LLM Model
              <select value={runForm.llmModel} onChange={(event) => setRunForm((prev) => ({ ...prev, llmModel: event.target.value }))}>
                <option value="" disabled>Select LLM model</option>
                {runtimeOptions.llmModels.map((model) => <option key={model} value={model}>{model}</option>)}
              </select>
            </label>
            <label className="filter-field filter-field--small">LLM RPM
              <input type="number" min="1" max="1000" value={runForm.llmRpm} onChange={(event) => setRunForm((prev) => ({ ...prev, llmRpm: event.target.value }))} />
            </label>
          </div>
          <div className="form-actions form-actions--end">
            <button type="submit" className="button button--primary">Start Generation</button>
          </div>
        </form>
      </section>

      <section className="metric-grid">
        <MetricCard label="Total Synthetic Queries" value={total} meta="Current filter-aware inventory" tone="primary" />
        <MetricCard label="Strategy Coverage" value={byMethod.length} meta={byMethod.map((item) => `${item.method_code}:${item.count}`).join(' / ') || '-'} />
        <MetricCard label="Query Types" value={byType.length} meta={byType.map((item) => `${item.query_type}:${item.count}`).join(' / ') || '-'} />
        <MetricCard label="Active Batches" value={batches.filter((batch) => ['planned', 'queued', 'running'].includes(compactStatus(batch.status))).length} meta="planned / queued / running" tone="running" />
      </section>

      <section className="admin-card">
        <SectionHeader
          eyebrow="Batch History"
          title="Batch Timeline"
          description="Progress, ETA, retry state, and generated counts are rendered from the existing batch response."
          actions={<button type="button" className="button" onClick={() => Promise.all([loadBatches(), loadQueries(queryPage), loadStats()]).catch((error) => notify(error.message, 'error'))}>Refresh</button>}
        />
        <div className="batch-toolbar">
          <label className="filter-field">Search
            <input value={batchFilters.search} placeholder="batch id, version, source run" onChange={(event) => setBatchFilters((prev) => ({ ...prev, search: event.target.value }))} />
          </label>
          <label className="filter-field filter-field--small">Status
            <select value={batchFilters.status} onChange={(event) => setBatchFilters((prev) => ({ ...prev, status: event.target.value }))}>
              <option value="all">All</option>
              {batchStatusOptions.map((status) => <option key={status} value={status}>{status}</option>)}
            </select>
          </label>
          <label className="filter-field filter-field--small">Strategy
            <select value={batchFilters.method} onChange={(event) => setBatchFilters((prev) => ({ ...prev, method: event.target.value }))}>
              <option value="all">All</option>
              {batchMethodOptions.map((method) => <option key={method} value={method}>{method}</option>)}
            </select>
          </label>
          <label className="filter-field filter-field--small">Sort
            <select value={batchFilters.sort} onChange={(event) => setBatchFilters((prev) => ({ ...prev, sort: event.target.value }))}>
              <option value="newest">Newest first</option>
              <option value="oldest">Oldest first</option>
              <option value="generated_desc">Generated high to low</option>
              <option value="generated_asc">Generated low to high</option>
            </select>
          </label>
        </div>
        {pagedBatches.length === 0 ? (
          <EmptyState title="No batches match the current filters" description="Adjust status, strategy, or search text." />
        ) : (
          <div className="batch-timeline">
            {pagedBatches.map((batch) => (
              <BatchJobCard
                key={batch.batchId}
                title={`${batch.methodCode || '-'} / ${batch.versionName || 'unversioned'}`}
                subtitle={`Started ${fmtTime(batch.startedAt)} / Completed ${fmtTime(batch.finishedAt)}`}
                statusSlot={<StatusBadge value={batch.status} />}
                idSlot={<IdBadge value={batch.batchId} />}
                meta={[
                  { label: 'Strategy', value: batch.methodName || batch.methodCode || '-' },
                  { label: 'Source Run', value: batch.sourceGenerationRunId ? shortId(batch.sourceGenerationRunId) : '-' },
                  { label: 'LLM Job', value: formatLlmJobState(batch) },
                ]}
                metrics={[
                  { label: 'Generated', value: batch.totalGeneratedCount ?? 0, meta: `target ${batch.targetQueryCount ?? '-'}`, tone: 'primary' },
                  { label: 'Avg Latency', value: batch.estimatedSecondsPerQuery == null ? '-' : `${Number(batch.estimatedSecondsPerQuery).toFixed(2)}s`, meta: 'per query' },
                  { label: 'Retry', value: batch.llmRetryCount ?? 0, meta: `max ${Number(batch.llmMaxRetries) < 0 ? 'unlimited' : (batch.llmMaxRetries ?? 0)}` },
                ]}
                progress={(
                  <ProgressMetric
                    label="Generation progress"
                    value={batch.totalGeneratedCount ?? 0}
                    max={batch.targetQueryCount ?? 0}
                    helper={<RemainingEta remainingSeconds={batch.estimatedRemainingSeconds} secondsPerUnit={batch.estimatedSecondsPerQuery} completedCount={batch.totalGeneratedCount} totalCount={batch.targetQueryCount} unitLabel="query" status={batch.status} compact />}
                  />
                )}
                actions={(
                  <button
                    type="button"
                    title="Delete batch history"
                    className="button button--danger-ghost"
                    disabled={!isBatchDeletable(batch) || deletingBatchId === batch.batchId}
                    onClick={() => deleteBatch(batch)}
                  >
                    Delete
                  </button>
                )}
              />
            ))}
          </div>
        )}
        <div className="pagination">
          <button type="button" className="button" disabled={currentHistoryPage === 0} onClick={() => setHistoryPage((prev) => Math.max(0, prev - 1))}>Previous</button>
          <div className="pagination__label">Page {currentHistoryPage + 1} / {historyTotalPages} ({filteredBatches.length} batches)</div>
          <button type="button" className="button" disabled={currentHistoryPage + 1 >= historyTotalPages} onClick={() => setHistoryPage((prev) => Math.min(historyTotalPages - 1, prev + 1))}>Next</button>
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

      <section className="table-shell modern-data-section">
        <div className="table-header"><div className="table-title">Synthetic Query Inventory</div></div>
        <form className="filter-bar" onSubmit={(event) => { event.preventDefault(); setQueryPage(0); loadQueries(0).then(loadStats).catch((error) => notify(error.message, 'error')) }}>
          <label className="filter-field">Strategy
            <select value={filterForm.method_code} onChange={(event) => setFilterForm((prev) => ({ ...prev, method_code: event.target.value }))}>
              <option value="">All</option>
              {methods.map((method) => <option key={method.methodCode} value={method.methodCode}>{method.methodCode}</option>)}
            </select>
          </label>
          <label className="filter-field">Batch
            <select value={filterForm.batch_id} onChange={(event) => setFilterForm((prev) => ({ ...prev, batch_id: event.target.value }))}>
              <option value="">All</option>
              {queryFilterBatchOptions.map((batch) => <option key={batch.batchId} value={batch.batchId}>{batch.versionName} ({batch.methodCode})</option>)}
            </select>
          </label>
          <label className="filter-field">Query Type
            <input value={filterForm.query_type} placeholder="definition" onChange={(event) => setFilterForm((prev) => ({ ...prev, query_type: event.target.value }))} />
          </label>
          <label className="filter-field">Gated
            <select value={filterForm.gated} onChange={(event) => setFilterForm((prev) => ({ ...prev, gated: event.target.value }))}>
              <option value="">All</option><option value="true">Passed</option><option value="false">Not passed</option>
            </select>
          </label>
          <div className="filter-field filter-field--small"><button type="submit" className="button button--primary">Apply</button></div>
        </form>
        <div className="table-wrap">
          <table className="data-table">
            <thead><tr><th>Query ID</th><th>Query Text</th><th>Type</th><th>Strategy</th><th>Batch</th><th>Gated</th><th>Detail</th></tr></thead>
            <tbody>
              {queries.map((query) => (
                <tr key={query.queryId}>
                  <td><IdBadge value={query.queryId} plain /></td>
                  <td>{query.queryText}</td>
                  <td>{query.queryType || '-'}</td>
                  <td>{query.generationMethod || '-'}</td>
                  <td>{query.generationBatchVersion || '-'}</td>
                  <td>{query.gated ? <StatusBadge value="success" label="Passed" /> : <StatusBadge value="failed" label="Not passed" />}</td>
                  <td><button type="button" className="button button--ghost" onClick={() => openQueryDetail(query.queryId)}>Detail</button></td>
                </tr>
              ))}
              {queries.length === 0 && (
                <tr>
                  <td colSpan={7}>No synthetic queries found.</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        <div className="pagination">
          <button type="button" className="button" disabled={queryPage === 0} onClick={() => { const next = Math.max(0, queryPage - 1); setQueryPage(next); loadQueries(next).catch((error) => notify(error.message, 'error')) }}>Previous</button>
          <div className="pagination__label">Page {queryPage + 1}</div>
          <button type="button" className="button" disabled={!hasNextPage} onClick={() => { const next = queryPage + 1; setQueryPage(next); loadQueries(next).catch((error) => notify(error.message, 'error')) }}>Next</button>
        </div>
      </section>

      <ConfirmDialog
        open={Boolean(pendingDeleteBatch)}
        title="Delete generation batch history?"
        description={pendingDeleteBatch ? `Batch ${pendingDeleteBatch.batchId} and linked synthetic rows will be deleted by the existing backend endpoint.` : ''}
        confirmLabel="Delete"
        loading={Boolean(deletingBatchId)}
        onCancel={() => setPendingDeleteBatch(null)}
        onConfirm={confirmDeleteBatch}
      />
      <Modal data={modal} onClose={() => setModal(null)} />
    </>
  )
}
