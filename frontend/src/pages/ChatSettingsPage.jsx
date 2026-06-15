import { useCallback, useEffect, useMemo, useState } from 'react'
import { ConfigSummaryCard, ExperimentSection, SectionHeader } from '../components/AdminUi.jsx'
import { IdBadge, StatusBadge } from '../components/Common.jsx'
import { appendQuery, fetchSyntheticMethods, requestJson, toNumber } from '../lib/api.js'
import { fmtTime } from '../lib/format.js'

const MODE_OPTIONS = [
  { value: 'selective_rewrite', label: 'Selective rewrite' },
  { value: 'raw_only', label: 'Raw only' },
  { value: 'selective_rewrite_with_session', label: 'Selective + session' },
  { value: 'rewrite_always', label: 'Rewrite always' },
  { value: 'memory_only_gated', label: 'Memory only gated' },
  { value: 'memory_only_ungated', label: 'Memory only ungated' },
]

const GATING_PRESETS = ['full_gating', 'rule_plus_llm', 'rule_only', 'ungated']
const REWRITE_PROFILES = [
  { value: 'compact_anchor', label: 'Compact anchor' },
  { value: 'detailed_intent', label: 'Detailed intent' },
]
const FAILURE_POLICIES = ['heuristic_fallback', 'skip_to_raw', 'fail_run']
const RETRIEVAL_BACKENDS = ['local', 'db_ann']
const RETRIEVER_MODES = ['bm25_only', 'dense_only', 'hybrid']

function retrieverModeLabel(mode) {
  if (mode === 'bm25_only') return 'BM25 Only'
  if (mode === 'dense_only') return 'Dense Only'
  if (mode === 'hybrid') return 'Hybrid'
  return mode || '-'
}

function retrieverDefaults(mode, runtimeOptions = {}) {
  const defaults = runtimeOptions.retrieverModeDefaults?.[mode] || {}
  const weights = defaults.retriever_fusion_weights || defaults.retrieverFusionWeights || {}
  return {
    candidatePoolK: String(defaults.retriever_candidate_pool_k || defaults.retrieverCandidatePoolK || 50),
    denseWeight: String(weights.dense ?? (mode === 'dense_only' ? 1 : mode === 'bm25_only' ? 0 : 0.6)),
    bm25Weight: String(weights.bm25 ?? (mode === 'bm25_only' ? 1 : mode === 'dense_only' ? 0 : 0.32)),
    technicalWeight: String(weights.technical ?? (mode === 'hybrid' ? 0.08 : 0)),
  }
}

function initialForm(domainId) {
  const defaults = retrieverDefaults('hybrid')
  return {
    domainId: domainId || '',
    enabled: true,
    mode: 'selective_rewrite',
    generationStrategies: [],
    gatingPreset: 'full_gating',
    sourceGatingBatchId: '',
    sourceGatingBatchIds: [],
    rewriteQueryProfile: 'compact_anchor',
    rewriteAnchorInjectionEnabled: false,
    useSessionContext: false,
    retrievalBackend: 'local',
    denseEmbeddingModel: 'intfloat/multilingual-e5-small',
    retrieverMode: 'hybrid',
    retrieverCandidatePoolK: defaults.candidatePoolK,
    retrieverDenseWeight: defaults.denseWeight,
    retrieverBm25Weight: defaults.bm25Weight,
    retrieverTechnicalWeight: defaults.technicalWeight,
    retrievalTopK: '10',
    rerankTopN: '5',
    memoryTopN: '5',
    rewriteCandidateCount: '2',
    rewriteThreshold: '0.05',
    rewriteFailurePolicy: 'heuristic_fallback',
    updatedBy: 'admin-ui',
  }
}

function provenanceChangedFields(row) {
  const fields = row?.diff?.changed_fields || row?.diff?.changedFields
  return Array.isArray(fields) ? fields.filter(Boolean) : []
}

function yesNo(value) {
  return value ? 'yes' : 'no'
}

function formatCount(value) {
  return Number(value || 0).toLocaleString()
}

function readinessReasons(readiness) {
  return Array.isArray(readiness?.blockingReasons) ? readiness.blockingReasons.filter(Boolean) : []
}

function configSnapshotIds(configPayload) {
  const ids = Array.isArray(configPayload?.sourceGatingBatchIds)
    ? configPayload.sourceGatingBatchIds.filter(Boolean)
    : []
  if (ids.length > 0) return Array.from(new Set(ids))
  return configPayload?.sourceGatingBatchId ? [configPayload.sourceGatingBatchId] : []
}

export function ChatSettingsPage({ notify, domainId, domainKey }) {
  const [form, setForm] = useState(() => initialForm(domainId))
  const [config, setConfig] = useState(null)
  const [readiness, setReadiness] = useState(null)
  const [methods, setMethods] = useState([])
  const [gatingBatches, setGatingBatches] = useState([])
  const [provenance, setProvenance] = useState([])
  const [runtimeOptions, setRuntimeOptions] = useState({
    denseEmbeddingModels: ['intfloat/multilingual-e5-small'],
    retrievalBackends: RETRIEVAL_BACKENDS,
    retrieverModes: RETRIEVER_MODES,
    retrieverModeDefaults: {},
  })
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)

  const load = useCallback(async () => {
    if (!domainId) return
    setLoading(true)
    try {
      const [configPayload, methodPayload, batchPayload, provenancePayload, runtimePayload, readinessPayload] = await Promise.all([
        requestJson(appendQuery('/api/admin/chat/config', { domain_id: domainId })),
        fetchSyntheticMethods({ domainId }),
        requestJson(appendQuery('/api/admin/console/gating/batches', { domain_id: domainId, limit: 100 })),
        requestJson(appendQuery('/api/admin/chat/config/provenance', { domain_id: domainId, limit: 10 })),
        requestJson('/api/admin/console/runtime/options'),
        requestJson(appendQuery('/api/admin/chat/readiness', { domain_id: domainId })),
      ])
      const nextRuntimeOptions = {
        denseEmbeddingModels: Array.isArray(runtimePayload.denseEmbeddingModels) && runtimePayload.denseEmbeddingModels.length > 0
          ? runtimePayload.denseEmbeddingModels
          : ['intfloat/multilingual-e5-small'],
        retrievalBackends: Array.isArray(runtimePayload.retrievalBackends) && runtimePayload.retrievalBackends.length > 0
          ? runtimePayload.retrievalBackends
          : RETRIEVAL_BACKENDS,
        retrieverModes: Array.isArray(runtimePayload.retrieverModes) && runtimePayload.retrieverModes.length > 0
          ? runtimePayload.retrieverModes
          : RETRIEVER_MODES,
        retrieverModeDefaults: runtimePayload.retrieverModeDefaults && typeof runtimePayload.retrieverModeDefaults === 'object'
          ? runtimePayload.retrieverModeDefaults
          : {},
      }
      setConfig(configPayload)
      setReadiness(readinessPayload)
      setMethods(Array.isArray(methodPayload) ? methodPayload : [])
      setGatingBatches(Array.isArray(batchPayload) ? batchPayload : [])
      setProvenance(Array.isArray(provenancePayload) ? provenancePayload : [])
      setRuntimeOptions(nextRuntimeOptions)
      setForm({
        domainId,
        enabled: Boolean(configPayload.enabled),
        mode: configPayload.mode || 'selective_rewrite',
        generationStrategies: Array.isArray(configPayload.generationStrategies) ? configPayload.generationStrategies : [],
        gatingPreset: configPayload.gatingPreset || 'full_gating',
        sourceGatingBatchId: configSnapshotIds(configPayload)[0] || '',
        sourceGatingBatchIds: configSnapshotIds(configPayload),
        rewriteQueryProfile: configPayload.rewriteQueryProfile || 'compact_anchor',
        rewriteAnchorInjectionEnabled: Boolean(configPayload.rewriteAnchorInjectionEnabled),
        useSessionContext: Boolean(configPayload.useSessionContext),
        retrievalBackend: configPayload.retrievalBackend || 'local',
        denseEmbeddingModel: configPayload.denseEmbeddingModel || nextRuntimeOptions.denseEmbeddingModels[0] || 'intfloat/multilingual-e5-small',
        retrieverMode: configPayload.retrieverMode || 'hybrid',
        retrieverCandidatePoolK: String(configPayload.retrieverCandidatePoolK || 50),
        retrieverDenseWeight: String(configPayload.retrieverDenseWeight ?? 0.6),
        retrieverBm25Weight: String(configPayload.retrieverBm25Weight ?? 0.32),
        retrieverTechnicalWeight: String(configPayload.retrieverTechnicalWeight ?? 0.08),
        retrievalTopK: String(configPayload.retrievalTopK || 10),
        rerankTopN: String(configPayload.rerankTopN || 5),
        memoryTopN: String(configPayload.memoryTopN || 5),
        rewriteCandidateCount: String(configPayload.rewriteCandidateCount || 2),
        rewriteThreshold: String(configPayload.rewriteThreshold ?? 0.05),
        rewriteFailurePolicy: configPayload.rewriteFailurePolicy || 'heuristic_fallback',
        updatedBy: 'admin-ui',
      })
    } catch (error) {
      notify(error.message, 'danger')
    } finally {
      setLoading(false)
    }
  }, [domainId, notify])

  useEffect(() => {
    load()
  }, [load])

  const selectedStrategies = useMemo(() => new Set(form.generationStrategies || []), [form.generationStrategies])
  const snapshotOptions = useMemo(() => {
    return gatingBatches
      .filter((batch) => batch.status === 'completed' && batch.sourceGatingRunId)
      .filter((batch) => !form.gatingPreset || batch.gatingPreset === form.gatingPreset)
      .filter((batch) => selectedStrategies.size === 0 || !batch.methodCode || selectedStrategies.has(batch.methodCode))
  }, [gatingBatches, form.gatingPreset, selectedStrategies])
  const selectedBatchIds = useMemo(
    () => Array.from(new Set((form.sourceGatingBatchIds || []).filter(Boolean))),
    [form.sourceGatingBatchIds],
  )
  const selectedBatchIdSet = useMemo(() => new Set(selectedBatchIds), [selectedBatchIds])
  const selectedSnapshots = snapshotOptions.filter((batch) => selectedBatchIdSet.has(batch.gatingBatchId))
  const memoryBacked = form.mode !== 'raw_only'
  const readinessBlockingReasons = readinessReasons(readiness)

  const updateField = (field, value) => {
    setForm((prev) => ({ ...prev, [field]: value }))
  }

  const updateRetrieverMode = (mode) => {
    const defaults = retrieverDefaults(mode, runtimeOptions)
    setForm((prev) => ({
      ...prev,
      retrieverMode: mode,
      retrieverCandidatePoolK: defaults.candidatePoolK,
      retrieverDenseWeight: defaults.denseWeight,
      retrieverBm25Weight: defaults.bm25Weight,
      retrieverTechnicalWeight: defaults.technicalWeight,
    }))
  }

  const toggleStrategy = (methodCode) => {
    setForm((prev) => {
      const next = new Set(prev.generationStrategies || [])
      if (next.has(methodCode)) next.delete(methodCode)
      else next.add(methodCode)
      return { ...prev, generationStrategies: Array.from(next).sort(), sourceGatingBatchId: '', sourceGatingBatchIds: [] }
    })
  }

  const toggleSnapshot = (batchId) => {
    if (!memoryBacked || !batchId) return
    setForm((prev) => {
      const next = new Set(prev.sourceGatingBatchIds || [])
      if (next.has(batchId)) next.delete(batchId)
      else next.add(batchId)
      const values = Array.from(next)
      return { ...prev, sourceGatingBatchIds: values, sourceGatingBatchId: values[0] || '' }
    })
  }

  const save = async () => {
    if (!domainId) return
    const nextSourceGatingBatchIds = memoryBacked ? selectedBatchIds : []
    if (memoryBacked && nextSourceGatingBatchIds.length === 0) {
      notify('Select one or more completed gating snapshots for rewrite-backed chat.', 'danger')
      return
    }
    setSaving(true)
    try {
      const payload = await requestJson('/api/admin/chat/config', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          ...form,
          domainId,
          retrievalBackend: form.retrievalBackend,
          denseEmbeddingModel: form.denseEmbeddingModel,
          retrieverMode: form.retrieverMode,
          retrieverCandidatePoolK: toNumber(form.retrieverCandidatePoolK),
          retrieverDenseWeight: toNumber(form.retrieverDenseWeight),
          retrieverBm25Weight: toNumber(form.retrieverBm25Weight),
          retrieverTechnicalWeight: toNumber(form.retrieverTechnicalWeight),
          retrievalTopK: toNumber(form.retrievalTopK),
          rerankTopN: toNumber(form.rerankTopN),
          memoryTopN: toNumber(form.memoryTopN),
          rewriteCandidateCount: toNumber(form.rewriteCandidateCount),
          rewriteThreshold: toNumber(form.rewriteThreshold),
          sourceGatingBatchId: nextSourceGatingBatchIds[0] || null,
          sourceGatingBatchIds: nextSourceGatingBatchIds,
        }),
      })
      setConfig(payload)
      notify('Chat runtime config saved.')
      await load()
    } catch (error) {
      notify(error.message, 'danger')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="page-stack">
      <SectionHeader
        eyebrow={`Domain Chat / ${domainKey || config?.domainKey || '-'}`}
        title="Chat Runtime Settings"
        description="Pin the online chat RAG path to one domain, evaluated memory snapshots, and one rewrite policy."
        actions={<button type="button" className="button button--primary" disabled={saving || loading} onClick={save}>{saving ? 'Saving...' : 'Save config'}</button>}
      />

      <div className="experiment-layout">
        <div className="experiment-layout__main">
          <ExperimentSection title="Domain Guard" description="These values decide which synthetic memory can enter the chat rewrite prompt.">
            <div className="form-grid">
              <label className="filter-field">Chat enabled
                <select value={String(form.enabled)} onChange={(event) => updateField('enabled', event.target.value === 'true')}>
                  <option value="true">Enabled</option>
                  <option value="false">Disabled</option>
                </select>
              </label>
              <label className="filter-field">Mode
                <select value={form.mode} onChange={(event) => updateField('mode', event.target.value)}>
                  {MODE_OPTIONS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
                </select>
              </label>
              <label className="filter-field">Gating preset
                <select value={form.gatingPreset} onChange={(event) => setForm((prev) => ({ ...prev, gatingPreset: event.target.value, sourceGatingBatchId: '', sourceGatingBatchIds: [] }))}>
                  {GATING_PRESETS.map((preset) => <option key={preset} value={preset}>{preset}</option>)}
                </select>
              </label>
            </div>

            <div className="summary-card__meta">
              Completed snapshots {memoryBacked ? `${selectedBatchIds.length} selected` : 'not used in raw_only'}
            </div>
            <div className="strategy-chip-grid">
              {snapshotOptions.map((batch) => {
                const selected = selectedBatchIdSet.has(batch.gatingBatchId)
                return (
                  <button
                    key={batch.gatingBatchId}
                    type="button"
                    className={`strategy-chip ${selected ? 'is-selected' : ''}`}
                    disabled={!memoryBacked}
                    onClick={() => toggleSnapshot(batch.gatingBatchId)}
                  >
                    <strong>{batch.methodCode || '-'}</strong>
                    <span>{batch.gatingPreset} / accepted {batch.acceptedCount}</span>
                    <span>{batch.gatingBatchId.slice(0, 8)}</span>
                  </button>
                )
              })}
            </div>

            <div className="strategy-chip-grid">
              {methods.map((method) => {
                const selected = selectedStrategies.has(method.methodCode)
                return (
                  <button
                    key={method.methodCode}
                    type="button"
                    className={`strategy-chip ${selected ? 'is-selected' : ''}`}
                    onClick={() => toggleStrategy(method.methodCode)}
                  >
                    <strong>{method.methodCode}</strong>
                    <span>{method.methodName || method.methodCode}</span>
                  </button>
                )
              })}
            </div>
          </ExperimentSection>

          <ExperimentSection title="Rewrite Policy" description="Use the same policy family validated in Retrieval Eval Lab, but apply it to live chat.">
            <div className="form-grid">
              <label className="filter-field">Rewrite profile
                <select value={form.rewriteQueryProfile} onChange={(event) => updateField('rewriteQueryProfile', event.target.value)}>
                  {REWRITE_PROFILES.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
                </select>
              </label>
              <label className="filter-field">Failure policy
                <select value={form.rewriteFailurePolicy} onChange={(event) => updateField('rewriteFailurePolicy', event.target.value)}>
                  {FAILURE_POLICIES.map((policy) => <option key={policy} value={policy}>{policy}</option>)}
                </select>
              </label>
              <label className="filter-field">Rewrite threshold
                <input type="number" min="0" max="1" step="0.01" value={form.rewriteThreshold} onChange={(event) => updateField('rewriteThreshold', event.target.value)} />
              </label>
              <label className="check-pill">
                <input type="checkbox" checked={form.rewriteAnchorInjectionEnabled} onChange={(event) => updateField('rewriteAnchorInjectionEnabled', event.target.checked)} />
                <span>Anchor injection</span>
              </label>
              <label className="check-pill">
                <input type="checkbox" checked={form.useSessionContext} onChange={(event) => updateField('useSessionContext', event.target.checked)} />
                <span>Session context</span>
              </label>
            </div>
          </ExperimentSection>

          <ExperimentSection title="Retrieval Shape" description="Keep these values aligned with the Admin RAG test that promoted this chat config.">
            <div className="form-grid">
              <label className="filter-field">Retrieval backend
                <select value={form.retrievalBackend} onChange={(event) => updateField('retrievalBackend', event.target.value)}>
                  {(runtimeOptions.retrievalBackends.length > 0 ? runtimeOptions.retrievalBackends : RETRIEVAL_BACKENDS).map((backend) => (
                    <option key={backend} value={backend}>{backend}</option>
                  ))}
                </select>
              </label>
              <label className="filter-field">Retriever mode
                <select value={form.retrieverMode} onChange={(event) => updateRetrieverMode(event.target.value)}>
                  {(runtimeOptions.retrieverModes.length > 0 ? runtimeOptions.retrieverModes : RETRIEVER_MODES).map((mode) => (
                    <option key={mode} value={mode}>{retrieverModeLabel(mode)}</option>
                  ))}
                </select>
              </label>
              <label className="filter-field">Dense embedding model
                <select value={form.denseEmbeddingModel} onChange={(event) => updateField('denseEmbeddingModel', event.target.value)}>
                  {(runtimeOptions.denseEmbeddingModels.length > 0 ? runtimeOptions.denseEmbeddingModels : ['intfloat/multilingual-e5-small']).map((model) => (
                    <option key={model} value={model}>{model}</option>
                  ))}
                </select>
              </label>
              <label className="filter-field">Candidate pool
                <input type="number" min="1" max="500" value={form.retrieverCandidatePoolK} onChange={(event) => updateField('retrieverCandidatePoolK', event.target.value)} />
              </label>
              <label className="filter-field">Retrieval Top-K
                <input type="number" min="1" max="100" value={form.retrievalTopK} onChange={(event) => updateField('retrievalTopK', event.target.value)} />
              </label>
              <label className="filter-field">Rerank Top-N
                <input type="number" min="1" max="100" value={form.rerankTopN} onChange={(event) => updateField('rerankTopN', event.target.value)} />
              </label>
              <label className="filter-field">Memory Top-N
                <input type="number" min="1" max="50" value={form.memoryTopN} onChange={(event) => updateField('memoryTopN', event.target.value)} />
              </label>
              <label className="filter-field">Rewrite candidates
                <input type="number" min="1" max="2" value={form.rewriteCandidateCount} onChange={(event) => updateField('rewriteCandidateCount', event.target.value)} />
              </label>
              <label className="filter-field">Dense weight
                <input type="number" min="0" max="1" step="0.01" disabled={form.retrieverMode !== 'hybrid'} value={form.retrieverDenseWeight} onChange={(event) => updateField('retrieverDenseWeight', event.target.value)} />
              </label>
              <label className="filter-field">BM25 weight
                <input type="number" min="0" max="1" step="0.01" disabled={form.retrieverMode !== 'hybrid'} value={form.retrieverBm25Weight} onChange={(event) => updateField('retrieverBm25Weight', event.target.value)} />
              </label>
              <label className="filter-field">Technical weight
                <input type="number" min="0" max="1" step="0.01" disabled={form.retrieverMode !== 'hybrid'} value={form.retrieverTechnicalWeight} onChange={(event) => updateField('retrieverTechnicalWeight', event.target.value)} />
              </label>
            </div>
          </ExperimentSection>
        </div>

        <ConfigSummaryCard
          title="Active Chat Config"
          items={[
            { label: 'Domain', value: config?.displayName || domainKey || '-' },
            { label: 'Ready', value: readiness?.readyForRewrite ? 'yes' : readinessBlockingReasons[0] || '-' },
            { label: 'Strategies', value: (form.generationStrategies || []).join(', ') || '-' },
            { label: 'Snapshots', value: selectedBatchIds.length > 0 ? `${selectedBatchIds.length} selected` : '-' },
            { label: 'Retrieval', value: `${form.retrievalBackend} / ${retrieverModeLabel(form.retrieverMode)}` },
            { label: 'Embedding', value: form.denseEmbeddingModel || '-' },
            { label: 'Profile', value: form.rewriteQueryProfile },
            { label: 'Updated', value: config?.updatedAt ? fmtTime(config.updatedAt) : '-' },
          ]}
        />
      </div>

      <section className="data-panel">
        <div className="data-panel__header">
          <h3>Domain Readiness</h3>
          {readiness && (
            <StatusBadge
              value={readiness.readyForRewrite ? 'completed' : 'failed'}
              label={readiness.readyForRewrite ? 'ready' : 'blocked'}
            />
          )}
        </div>
        {readiness ? (
          <>
            <div className="metadata-grid">
              <div><span>Active config</span><strong>{yesNo(readiness.activeConfigPresent)}</strong></div>
              <div><span>Rewrite-backed mode</span><strong>{yesNo(readiness.rewriteBackedMode)}</strong></div>
              <div><span>Snapshot exists</span><strong>{yesNo(readiness.snapshot?.selectedSnapshotPresent)}</strong></div>
              <div><span>Selected snapshots</span><strong>{formatCount(readiness.snapshot?.selectedSnapshotCount)}</strong></div>
              <div><span>Source gating run</span><strong>{yesNo(readiness.snapshot?.sourceGatingRunPresent)}</strong></div>
              <div><span>Snapshot domain mismatch</span><strong>{yesNo(readiness.snapshot?.domainMismatch)}</strong></div>
              <div><span>Strategy mismatch</span><strong>{yesNo(readiness.snapshot?.generationStrategyMismatch)}</strong></div>
              <div><span>Accepted gated queries</span><strong>{formatCount(readiness.acceptedGatedQueryCount)}</strong></div>
              <div><span>Built memory</span><strong>{formatCount(readiness.memoryCount)}</strong></div>
              <div>
                <span>Chunk embeddings</span>
                <strong>
                  {formatCount(readiness.chunkEmbeddings?.materializedChunkCount)}
                  {' / '}
                  {formatCount(readiness.chunkEmbeddings?.domainChunkCount)}
                </strong>
              </div>
              <div><span>Prompt binding</span><strong>{readiness.promptBinding?.active ? readiness.promptBinding.bindingKey : 'inactive'}</strong></div>
              <div><span>Prompt version</span><strong>{readiness.promptBinding?.activePromptVersion || '-'}</strong></div>
              <div><span>Retrieval</span><strong>{readiness.retrieval?.retrievalBackend || '-'} / {retrieverModeLabel(readiness.retrieval?.retrieverMode)}</strong></div>
            </div>
            {readinessBlockingReasons.length > 0 && (
              <div className="chat-warning">
                {readinessBlockingReasons.join('; ')}
              </div>
            )}
          </>
        ) : (
          <div className="summary-card__meta">Readiness status is loading.</div>
        )}
      </section>

      <section className="data-panel">
        <div className="data-panel__header">
          <h3>Selected Snapshots</h3>
          {selectedSnapshots.length > 0 && <StatusBadge value="completed" label={`${selectedSnapshots.length} selected`} />}
        </div>
        {selectedSnapshots.length > 0 ? (
          <div className="metadata-grid">
            {selectedSnapshots.map((snapshot) => (
              <div key={snapshot.gatingBatchId}>
                <span>{snapshot.methodCode || '-'} / accepted {snapshot.acceptedCount}</span>
                <strong><IdBadge value={snapshot.gatingBatchId} /></strong>
              </div>
            ))}
          </div>
        ) : (
          <div className="summary-card__meta">No compatible completed snapshots selected.</div>
        )}
      </section>

      <section className="data-panel">
        <div className="data-panel__header">
          <h3>Config Provenance</h3>
          <span className="summary-card__meta">Recent immutable changes</span>
        </div>
        {provenance.length > 0 ? (
          <div className="table-wrap">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Changed</th>
                  <th>Source</th>
                  <th>Updated by</th>
                  <th>RAG run</th>
                  <th>Fields</th>
                </tr>
              </thead>
              <tbody>
                {provenance.map((row) => {
                  const fields = provenanceChangedFields(row)
                  return (
                    <tr key={row.provenanceId}>
                      <td>{fmtTime(row.createdAt)}</td>
                      <td><StatusBadge value={row.changeSource} /></td>
                      <td>{row.updatedBy || '-'}</td>
                      <td>{row.sourceRagTestRunId ? <IdBadge value={row.sourceRagTestRunId} /> : '-'}</td>
                      <td>{fields.length > 0 ? fields.join(', ') : 'no field delta'}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="summary-card__meta">No provenance has been recorded for this domain yet.</div>
        )}
      </section>
    </div>
  )
}
