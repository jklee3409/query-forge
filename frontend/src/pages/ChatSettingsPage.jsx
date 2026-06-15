import { useCallback, useEffect, useMemo, useState } from 'react'
import { ConfigSummaryCard, ExperimentSection, SectionHeader } from '../components/AdminUi.jsx'
import { IdBadge, StatusBadge } from '../components/Common.jsx'
import { appendQuery, fetchSyntheticMethods, requestJson, toNumber } from '../lib/api.js'
import { fmtTime } from '../lib/format.js'

const MODE_OPTIONS = [
  { value: 'selective_rewrite', label: '선택 재작성' },
  { value: 'raw_only', label: '원문 전용' },
  { value: 'selective_rewrite_with_session', label: '세션 포함 선택 재작성' },
  { value: 'rewrite_always', label: '항상 재작성' },
  { value: 'memory_only_gated', label: '게이트 메모리만' },
  { value: 'memory_only_ungated', label: '비게이트 메모리만' },
]

const GATING_PRESETS = ['full_gating', 'rule_plus_llm', 'rule_only', 'ungated']
const REWRITE_PROFILES = [
  { value: 'compact_anchor', label: '간결 anchor' },
  { value: 'detailed_intent', label: '상세 intent' },
]
const FAILURE_POLICIES = ['heuristic_fallback', 'skip_to_raw', 'fail_run']
const RETRIEVAL_BACKENDS = ['local', 'db_ann']
const RETRIEVER_MODES = ['bm25_only', 'dense_only', 'hybrid']

const FAILURE_POLICY_LABELS = {
  heuristic_fallback: '휴리스틱 fallback',
  skip_to_raw: '원문으로 전환',
  fail_run: '실패 처리',
}

const GATING_PRESET_LABELS = {
  full_gating: '전체 게이트 통과',
  rule_plus_llm: '규칙 + LLM',
  rule_only: '규칙만',
  ungated: '게이트 없음',
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

function gatingPresetLabel(preset) {
  return GATING_PRESET_LABELS[preset] || preset || '-'
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
      notify('rewrite-backed chat에는 완료된 합성 질의 배치가 하나 이상 필요합니다.', 'danger')
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
                      <strong>{gatingPresetLabel(batch.gatingPreset)} · accepted {formatCount(batch.acceptedCount)}</strong>
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
            <div className="chat-settings-chip-grid">
              {methods.map((method) => {
                const selected = selectedStrategies.has(method.methodCode)
                return (
                  <button
                    key={method.methodCode}
                    type="button"
                    className={`chat-settings-chip chat-settings-chip--method ${selected ? 'is-selected' : ''}`}
                    onClick={() => toggleStrategy(method.methodCode)}
                  >
                    <span className="chat-settings-chip__badge">{method.methodCode}</span>
                    <span className="chat-settings-chip__body">
                      <strong>{method.methodName || method.methodCode}</strong>
                      <small>synthetic memory strategy</small>
                    </span>
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
            { label: '생성 방식', value: (form.generationStrategies || []).join(', ') || '-' },
            { label: '배치', value: selectedBatchIds.length > 0 ? `${selectedBatchIds.length}개 선택` : '-' },
            { label: '검색', value: `${form.retrievalBackend} / ${retrieverModeLabel(form.retrieverMode)}` },
            { label: 'Embedding', value: form.denseEmbeddingModel || '-' },
            { label: 'Profile', value: form.rewriteQueryProfile },
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
