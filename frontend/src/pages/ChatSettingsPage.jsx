import { useCallback, useEffect, useMemo, useState } from 'react'
import { ConfigSummaryCard, ExperimentSection, SectionHeader } from '../components/AdminUi.jsx'
import { IdBadge, StatusBadge } from '../components/Common.jsx'
import { appendQuery, fetchSyntheticMethods, requestJson, toNumber } from '../lib/api.js'
import { fmtTime } from '../lib/format.js'

const MODE_OPTIONS = [
  { value: 'strategy_router', label: 'strategy router' },
  { value: 'selective_rewrite', label: 'selective rewrite' },
  { value: 'anchor_aware_rewrite', label: 'anchor aware rewrite' },
  { value: 'agentic_multi_query', label: 'agentic multi query' },
  { value: 'raw_only', label: 'raw only' },
  { value: 'selective_rewrite_with_session', label: 'selective rewrite with session' },
  { value: 'rewrite_always', label: 'rewrite always' },
  { value: 'memory_only_gated', label: 'memory only gated' },
  { value: 'memory_only_ungated', label: 'memory only ungated' },
]

const GATING_PRESETS = ['full_gating', 'rule_plus_llm', 'rule_only', 'ungated']
const REWRITE_PROFILES = [
  { value: 'compact_anchor', label: 'compact anchor' },
  { value: 'detailed_intent', label: 'detailed intent' },
]
const FAILURE_POLICIES = ['heuristic_fallback', 'skip_to_raw', 'fail_run']
const RETRIEVAL_BACKENDS = ['local', 'db_ann']
const RETRIEVER_MODES = ['bm25_only', 'dense_only', 'hybrid']

const FAILURE_POLICY_LABELS = {
  heuristic_fallback: 'heuristic fallback',
  skip_to_raw: 'skip to raw',
  fail_run: 'fail run',
}

const GATING_PRESET_LABELS = {
  full_gating: 'full gating',
  rule_plus_llm: 'rule + LLM',
  rule_only: 'rule only',
  ungated: 'ungated',
}
const PROVENANCE_PAGE_SIZE = 3

const PROVENANCE_SOURCE_LABELS = {
  manual: 'manual save',
  apply_rag_run: 'Apply to Chat',
}

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
    routerEnabled: false,
    agenticMultiQueryEnabled: false,
    agenticMaxSubqueries: '3',
    agenticRrfK: '60',
    metadata: {},
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

function provenanceSourceLabel(source) {
  return PROVENANCE_SOURCE_LABELS[source] || source || '-'
}

function provenanceFieldLabel(field) {
  const normalized = String(field || '').trim()
  if (!normalized) return '-'
  return normalized
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/_/g, ' ')
    .replace(/\bid\b/gi, 'ID')
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

function gatingPresetLabel(preset) {
  return GATING_PRESET_LABELS[preset] || preset || '-'
}

function generationMethodLabel(methodCode) {
  const code = String(methodCode || '').trim().toUpperCase()
  if (!code) return '-'
  return /^[A-Z]$/.test(code) ? `${code}안` : code
}

function configSnapshotIds(configPayload) {
  const ids = Array.isArray(configPayload?.sourceGatingBatchIds)
    ? configPayload.sourceGatingBatchIds.filter(Boolean)
    : []
  if (ids.length > 0) return Array.from(new Set(ids))
  return configPayload?.sourceGatingBatchId ? [configPayload.sourceGatingBatchId] : []
}

function metadataObject(value) {
  return value && typeof value === 'object' && !Array.isArray(value) ? value : {}
}

function metadataFlag(metadata, keys) {
  const values = metadataObject(metadata)
  return keys.some((key) => values[key] === true || values[key] === 'true')
}

function chatRuntimeMetadata(configPayload) {
  return metadataObject(configPayload?.metadata)
}

export function ChatSettingsPage({ notify, domainId, domainKey }) {
  const [form, setForm] = useState(() => initialForm(domainId))
  const [config, setConfig] = useState(null)
  const [readiness, setReadiness] = useState(null)
  const [methods, setMethods] = useState([])
  const [gatingBatches, setGatingBatches] = useState([])
  const [provenance, setProvenance] = useState([])
  const [provenancePage, setProvenancePage] = useState(0)
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
        requestJson(appendQuery('/api/admin/chat/config/provenance', { domain_id: domainId, limit: 30 })),
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
      const metadata = chatRuntimeMetadata(configPayload)
      setConfig(configPayload)
      setReadiness(readinessPayload)
      setMethods(Array.isArray(methodPayload) ? methodPayload : [])
      setGatingBatches(Array.isArray(batchPayload) ? batchPayload : [])
      setProvenance(Array.isArray(provenancePayload) ? provenancePayload : [])
      setProvenancePage(0)
      setRuntimeOptions(nextRuntimeOptions)
      setForm({
        domainId,
        enabled: Boolean(configPayload.enabled),
        mode: configPayload.mode || 'selective_rewrite',
        generationStrategies: Array.isArray(configPayload.generationStrategies) ? configPayload.generationStrategies : [],
        gatingPreset: configPayload.gatingPreset || 'full_gating',
        sourceGatingBatchId: configSnapshotIds(configPayload)[0] || '',
        sourceGatingBatchIds: configSnapshotIds(configPayload),
        routerEnabled: configPayload.routerEnabled != null
          ? Boolean(configPayload.routerEnabled)
          : metadataFlag(metadata, ['routerEnabled', 'queryRouterEnabled', 'query_router_enabled']),
        agenticMultiQueryEnabled: metadataFlag(metadata, [
          'agenticMultiQueryEnabled',
          'agentic_multi_query_enabled',
          'queryRouterAgenticEnabled',
          'query_router_agentic_enabled',
        ]),
        agenticMaxSubqueries: String(metadata.maxSubqueries || metadata.max_subqueries || metadata.agenticMaxSubqueries || metadata.agentic_max_subqueries || 3),
        agenticRrfK: String(metadata.rrfK || metadata.rrf_k || metadata.agenticRrfK || metadata.agentic_rrf_k || 60),
        metadata,
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
  const provenancePageCount = Math.max(1, Math.ceil(provenance.length / PROVENANCE_PAGE_SIZE))
  const visibleProvenance = provenance.slice(
    provenancePage * PROVENANCE_PAGE_SIZE,
    (provenancePage + 1) * PROVENANCE_PAGE_SIZE,
  )

  useEffect(() => {
    setProvenancePage((current) => Math.min(current, Math.max(0, provenancePageCount - 1)))
  }, [provenancePageCount])

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
      notify('rewrite-backed chat에는 완료된 합성 질의 배치가 하나 이상 필요합니다.', 'danger')
      return
    }
    setSaving(true)
    try {
      const requestForm = { ...form }
      delete requestForm.agenticMultiQueryEnabled
      delete requestForm.agenticMaxSubqueries
      delete requestForm.agenticRrfK
      delete requestForm.metadata
      const metadataPayload = { ...metadataObject(form.metadata) }
      delete metadataPayload.queryRouterEnabled
      delete metadataPayload.query_router_enabled
      delete metadataPayload.agentic_multi_query_enabled
      delete metadataPayload.queryRouterAgenticEnabled
      delete metadataPayload.query_router_agentic_enabled
      delete metadataPayload.max_subqueries
      delete metadataPayload.agentic_max_subqueries
      delete metadataPayload.rrf_k
      delete metadataPayload.agentic_rrf_k
      metadataPayload.routerEnabled = Boolean(form.routerEnabled)
      metadataPayload.agenticMultiQueryEnabled = Boolean(form.agenticMultiQueryEnabled)
      metadataPayload.maxSubqueries = toNumber(form.agenticMaxSubqueries) || 3
      metadataPayload.agenticMaxSubqueries = toNumber(form.agenticMaxSubqueries) || 3
      metadataPayload.rrfK = toNumber(form.agenticRrfK) || 60
      metadataPayload.agenticRrfK = toNumber(form.agenticRrfK) || 60
      const payload = await requestJson('/api/admin/chat/config', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          ...requestForm,
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
          metadata: metadataPayload,
        }),
      })
      setConfig(payload)
      notify('Chat runtime config를 저장했습니다.')
      await load()
    } catch (error) {
      notify(error.message, 'danger')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="page-stack chat-settings-page">
      <SectionHeader
        eyebrow={`Domain Chat / ${domainKey || config?.domainKey || '-'}`}
        title="Chat Runtime 설정"
        description="이 도메인의 live chat이 어떤 합성 질의 메모리, 재작성 정책, 검색 방식을 사용할지 고정합니다."
        actions={<button type="button" className="button button--primary chat-settings-save" disabled={saving || loading} onClick={save}>{saving ? '저장 중...' : '설정 저장'}</button>}
      />

      <section className="chat-settings-guide" aria-label="Chat 설정 흐름">
        <div>
          <span>1</span>
          <strong>동작 모드 선택</strong>
          <small>raw-only 또는 rewrite-backed chat을 정합니다.</small>
        </div>
        <div>
          <span>2</span>
          <strong>합성 질의 배치 선택</strong>
          <small>선택한 배치의 built memory만 재작성 예시에 사용됩니다.</small>
        </div>
        <div>
          <span>3</span>
          <strong>검색·재작성 정책 확인</strong>
          <small>Admin RAG 테스트와 같은 backend/model/mode를 유지합니다.</small>
        </div>
      </section>

      <div className="experiment-layout">
        <div className="experiment-layout__main">
          <ExperimentSection title="도메인 실행 범위" description="live chat에 들어갈 도메인, 모드, gating preset을 먼저 고정합니다.">
            <div className="form-grid">
              <label className="filter-field">Chat 사용
                <select value={String(form.enabled)} onChange={(event) => updateField('enabled', event.target.value === 'true')}>
                  <option value="true">사용</option>
                  <option value="false">중지</option>
                </select>
              </label>
              <label className="filter-field">동작 모드
                <select value={form.mode} onChange={(event) => updateField('mode', event.target.value)}>
                  {MODE_OPTIONS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
                </select>
              </label>
              <label className="filter-field">Gating preset
                <select value={form.gatingPreset} onChange={(event) => setForm((prev) => ({ ...prev, gatingPreset: event.target.value, sourceGatingBatchId: '', sourceGatingBatchIds: [] }))}>
                  {GATING_PRESETS.map((preset) => <option key={preset} value={preset}>{gatingPresetLabel(preset)}</option>)}
                </select>
              </label>
            </div>

            <div className="chat-settings-option-header">
              <div>
                <strong>합성 질의 배치</strong>
                <p>완료된 quality gating snapshot 중 live rewrite memory 후보로 사용할 배치를 선택합니다.</p>
              </div>
              <span>{memoryBacked ? `${selectedBatchIds.length}개 선택` : 'raw-only에서는 사용 안 함'}</span>
            </div>
            <div className="chat-settings-chip-grid chat-settings-chip-grid--snapshots">
              {snapshotOptions.length > 0 ? snapshotOptions.map((batch) => {
                const selected = selectedBatchIdSet.has(batch.gatingBatchId)
                return (
                  <button
                    key={batch.gatingBatchId}
                    type="button"
                    className={`chat-settings-chip chat-settings-chip--snapshot ${selected ? 'is-selected' : ''}`}
                    disabled={!memoryBacked}
                    onClick={() => toggleSnapshot(batch.gatingBatchId)}
                  >
                    <span className="chat-settings-chip__badge">{batch.methodCode || '-'}</span>
                    <span className="chat-settings-chip__body">
                      <span className="chat-settings-chip__title">{gatingPresetLabel(batch.gatingPreset)}</span>
                      <span className="chat-settings-chip__accepted">accepted <b>{formatCount(batch.acceptedCount)}</b></span>
                      <small>{batch.gatingBatchId.slice(0, 8)} · source {batch.sourceGatingRunId?.slice(0, 8) || '-'}</small>
                    </span>
                  </button>
                )
              }) : (
                <div className="chat-settings-empty">현재 조건에 맞는 완료 배치가 없습니다.</div>
              )}
            </div>

            <div className="chat-settings-option-header">
              <div>
                <strong>생성 방식</strong>
                <p>선택한 strategy의 memory만 live rewrite 예시 후보에 포함됩니다.</p>
              </div>
              <span>{selectedStrategies.size}개 선택</span>
            </div>
            <div className="chat-settings-chip-grid chat-settings-chip-grid--methods">
              {methods.map((method) => {
                const selected = selectedStrategies.has(method.methodCode)
                return (
                  <button
                    key={method.methodCode}
                    type="button"
                    className={`chat-settings-chip chat-settings-chip--method ${selected ? 'is-selected' : ''}`}
                    onClick={() => toggleStrategy(method.methodCode)}
                  >
                    <span className="chat-settings-chip__method-label">{generationMethodLabel(method.methodCode)}</span>
                  </button>
                )
              })}
            </div>
          </ExperimentSection>

          <ExperimentSection title="재작성 정책" description="Admin RAG 품질 테스트에서 검증한 rewrite profile과 fallback 동작을 live chat에 적용합니다.">
            <div className="form-grid">
              <label className="filter-field">Rewrite profile
                <select value={form.rewriteQueryProfile} onChange={(event) => updateField('rewriteQueryProfile', event.target.value)}>
                  {REWRITE_PROFILES.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
                </select>
              </label>
              <label className="filter-field">실패 처리
                <select value={form.rewriteFailurePolicy} onChange={(event) => updateField('rewriteFailurePolicy', event.target.value)}>
                  {FAILURE_POLICIES.map((policy) => <option key={policy} value={policy}>{FAILURE_POLICY_LABELS[policy] || policy}</option>)}
                </select>
              </label>
              <label className="filter-field">Rewrite threshold
                <input type="number" min="0" max="1" step="0.01" value={form.rewriteThreshold} onChange={(event) => updateField('rewriteThreshold', event.target.value)} />
              </label>
              <label className={`check-pill ${form.routerEnabled ? 'is-active' : ''}`}>
                <input type="checkbox" checked={form.routerEnabled} onChange={(event) => updateField('routerEnabled', event.target.checked)} />
                <span className="check-pill__box" aria-hidden="true">✓</span>
                <span className="check-pill__text">Query Strategy Router 사용</span>
              </label>
              <label className={`check-pill ${form.agenticMultiQueryEnabled ? 'is-active' : ''}`}>
                <input type="checkbox" checked={form.agenticMultiQueryEnabled} onChange={(event) => updateField('agenticMultiQueryEnabled', event.target.checked)} />
                <span className="check-pill__box" aria-hidden="true">✓</span>
                <span className="check-pill__text">Agentic Multi-Query</span>
              </label>
              <label className="filter-field">Agentic subqueries
                <input type="number" min="1" max="4" value={form.agenticMaxSubqueries} disabled={!form.agenticMultiQueryEnabled} onChange={(event) => updateField('agenticMaxSubqueries', event.target.value)} />
              </label>
              <label className="filter-field">Agentic RRF K
                <input type="number" min="1" max="500" value={form.agenticRrfK} disabled={!form.agenticMultiQueryEnabled} onChange={(event) => updateField('agenticRrfK', event.target.value)} />
              </label>
              <label className={`check-pill ${form.rewriteAnchorInjectionEnabled ? 'is-active' : ''}`}>
                <input type="checkbox" checked={form.rewriteAnchorInjectionEnabled} onChange={(event) => updateField('rewriteAnchorInjectionEnabled', event.target.checked)} />
                <span className="check-pill__box" aria-hidden="true">✓</span>
                <span className="check-pill__text">Anchor 보강</span>
              </label>
              <label className={`check-pill ${form.useSessionContext ? 'is-active' : ''}`}>
                <input type="checkbox" checked={form.useSessionContext} onChange={(event) => updateField('useSessionContext', event.target.checked)} />
                <span className="check-pill__box" aria-hidden="true">✓</span>
                <span className="check-pill__text">세션 문맥 사용</span>
              </label>
            </div>
            <p className="summary-card__meta">Router can choose Agentic Multi-Query for complex questions only when both Router and Agentic Multi-Query are enabled.</p>
          </ExperimentSection>

          <ExperimentSection title="검색 런타임" description="live chat 검색 backend/model/mode가 승격한 Admin RAG run과 맞는지 확인합니다.">
            <div className="form-grid">
              <label className="filter-field">검색 backend
                <select value={form.retrievalBackend} onChange={(event) => updateField('retrievalBackend', event.target.value)}>
                  {(runtimeOptions.retrievalBackends.length > 0 ? runtimeOptions.retrievalBackends : RETRIEVAL_BACKENDS).map((backend) => (
                    <option key={backend} value={backend}>{backend}</option>
                  ))}
                </select>
              </label>
              <label className="filter-field">검색 mode
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
              <label className="filter-field">후보 pool
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
            { label: '도메인', value: config?.displayName || domainKey || '-' },
            { label: '준비 상태', value: readiness?.readyForRewrite ? 'ready' : readinessBlockingReasons[0] || '-' },
            { label: '생성 방식', value: (form.generationStrategies || []).map(generationMethodLabel).join(', ') || '-' },
            { label: '배치', value: selectedBatchIds.length > 0 ? `${selectedBatchIds.length}개 선택` : '-' },
            { label: '검색', value: `${form.retrievalBackend} / ${retrieverModeLabel(form.retrieverMode)}` },
            { label: 'Embedding', value: form.denseEmbeddingModel || '-' },
            { label: 'Profile', value: form.rewriteQueryProfile },
            { label: 'Router', value: form.routerEnabled ? 'enabled' : 'disabled' },
            { label: 'Agentic', value: form.agenticMultiQueryEnabled ? 'enabled' : 'disabled' },
            { label: 'Agentic subqueries', value: form.agenticMultiQueryEnabled ? form.agenticMaxSubqueries : '-' },
            { label: 'Agentic RRF K', value: form.agenticMultiQueryEnabled ? form.agenticRrfK : '-' },
            { label: '수정 시각', value: config?.updatedAt ? fmtTime(config.updatedAt) : '-' },
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
              <div><span>Query Router</span><strong>{form.routerEnabled ? 'enabled' : 'disabled'}</strong></div>
              <div><span>Agentic Multi-Query</span><strong>{form.agenticMultiQueryEnabled ? 'enabled' : 'disabled'}</strong></div>
              <div><span>Agentic subqueries</span><strong>{form.agenticMultiQueryEnabled ? form.agenticMaxSubqueries : '-'}</strong></div>
              <div><span>Agentic RRF K</span><strong>{form.agenticMultiQueryEnabled ? form.agenticRrfK : '-'}</strong></div>
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
          {selectedSnapshots.length > 0 && <StatusBadge value="completed" label={`${selectedSnapshots.length}개 선택`} />}
        </div>
        {selectedSnapshots.length > 0 ? (
          <div className="metadata-grid">
            {selectedSnapshots.map((snapshot) => (
              <div key={snapshot.gatingBatchId}>
                <span>{generationMethodLabel(snapshot.methodCode)} / accepted {snapshot.acceptedCount}</span>
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
          <>
            <div className="chat-provenance-list">
              {visibleProvenance.map((row) => {
                const fields = provenanceChangedFields(row)
                return (
                  <article key={row.provenanceId} className="chat-provenance-card">
                    <div className="chat-provenance-card__header">
                      <div className="chat-provenance-card__stamp">
                        <span>Changed</span>
                        <strong>{fmtTime(row.createdAt)}</strong>
                      </div>
                      <div className="chat-provenance-card__header-meta">
                        <span className="chat-provenance-source" data-source={row.changeSource || 'unknown'}>
                          {provenanceSourceLabel(row.changeSource)}
                        </span>
                        <span className="plain-badge">
                          {fields.length > 0 ? `${fields.length} fields` : 'no delta'}
                        </span>
                      </div>
                    </div>

                    <div className="chat-provenance-card__meta-grid">
                      <div className="chat-provenance-card__meta-item">
                        <span>Updated by</span>
                        <strong>{row.updatedBy || '-'}</strong>
                      </div>
                      <div className="chat-provenance-card__meta-item">
                        <span>RAG run</span>
                        {row.sourceRagTestRunId ? <IdBadge value={row.sourceRagTestRunId} /> : <span className="plain-badge">-</span>}
                      </div>
                    </div>

                    <div className="chat-provenance-card__fields">
                      <span>Changed fields</span>
                      {fields.length > 0 ? (
                        <div className="token-badge-list chat-provenance-field-list">
                          {fields.map((field) => (
                            <span key={`${row.provenanceId}-${field}`} className="token-badge chat-provenance-field" title={field}>
                              <span className="token-badge__icon" aria-hidden="true">F</span>
                              <span className="token-badge__text">{provenanceFieldLabel(field)}</span>
                            </span>
                          ))}
                        </div>
                      ) : (
                        <span className="plain-badge">No changed fields</span>
                      )}
                    </div>
                  </article>
                )
              })}
            </div>
            {provenancePageCount > 1 && (
              <div className="chat-provenance-pagination">
                <button
                  type="button"
                  className="button button--ghost button--compact"
                  disabled={provenancePage === 0}
                  onClick={() => setProvenancePage((current) => Math.max(0, current - 1))}
                >
                  Previous
                </button>
                <div className="chat-provenance-pagination__pages">
                  {Array.from({ length: provenancePageCount }, (_, index) => (
                    <button
                      key={`provenance-page-${index}`}
                      type="button"
                      className={`chat-provenance-pagination__page ${index === provenancePage ? 'is-active' : ''}`}
                      onClick={() => setProvenancePage(index)}
                    >
                      {index + 1}
                    </button>
                  ))}
                </div>
                <button
                  type="button"
                  className="button button--ghost button--compact"
                  disabled={provenancePage >= provenancePageCount - 1}
                  onClick={() => setProvenancePage((current) => Math.min(provenancePageCount - 1, current + 1))}
                >
                  Next
                </button>
              </div>
            )}
          </>
        ) : (
          <div className="summary-card__meta">No provenance has been recorded for this domain yet.</div>
        )}
      </section>
    </div>
  )
}
