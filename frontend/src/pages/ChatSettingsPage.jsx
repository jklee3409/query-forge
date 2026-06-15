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

function initialForm(domainId) {
  return {
    domainId: domainId || '',
    enabled: true,
    mode: 'selective_rewrite',
    generationStrategies: [],
    gatingPreset: 'full_gating',
    sourceGatingBatchId: '',
    rewriteQueryProfile: 'compact_anchor',
    rewriteAnchorInjectionEnabled: false,
    useSessionContext: false,
    retrievalTopK: '10',
    rerankTopN: '5',
    memoryTopN: '5',
    rewriteCandidateCount: '2',
    rewriteThreshold: '0.05',
    rewriteFailurePolicy: 'heuristic_fallback',
    updatedBy: 'admin-ui',
  }
}

export function ChatSettingsPage({ notify, domainId, domainKey }) {
  const [form, setForm] = useState(() => initialForm(domainId))
  const [config, setConfig] = useState(null)
  const [methods, setMethods] = useState([])
  const [gatingBatches, setGatingBatches] = useState([])
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)

  const load = useCallback(async () => {
    if (!domainId) return
    setLoading(true)
    try {
      const [configPayload, methodPayload, batchPayload] = await Promise.all([
        requestJson(appendQuery('/api/admin/chat/config', { domain_id: domainId })),
        fetchSyntheticMethods({ domainId }),
        requestJson(appendQuery('/api/admin/console/gating/batches', { domain_id: domainId, limit: 100 })),
      ])
      setConfig(configPayload)
      setMethods(Array.isArray(methodPayload) ? methodPayload : [])
      setGatingBatches(Array.isArray(batchPayload) ? batchPayload : [])
      setForm({
        domainId,
        enabled: Boolean(configPayload.enabled),
        mode: configPayload.mode || 'selective_rewrite',
        generationStrategies: Array.isArray(configPayload.generationStrategies) ? configPayload.generationStrategies : [],
        gatingPreset: configPayload.gatingPreset || 'full_gating',
        sourceGatingBatchId: configPayload.sourceGatingBatchId || '',
        rewriteQueryProfile: configPayload.rewriteQueryProfile || 'compact_anchor',
        rewriteAnchorInjectionEnabled: Boolean(configPayload.rewriteAnchorInjectionEnabled),
        useSessionContext: Boolean(configPayload.useSessionContext),
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
  const selectedSnapshot = snapshotOptions.find((batch) => batch.gatingBatchId === form.sourceGatingBatchId)
  const memoryBacked = form.mode !== 'raw_only'

  const updateField = (field, value) => {
    setForm((prev) => ({ ...prev, [field]: value }))
  }

  const toggleStrategy = (methodCode) => {
    setForm((prev) => {
      const next = new Set(prev.generationStrategies || [])
      if (next.has(methodCode)) next.delete(methodCode)
      else next.add(methodCode)
      return { ...prev, generationStrategies: Array.from(next).sort(), sourceGatingBatchId: '' }
    })
  }

  const save = async () => {
    if (!domainId) return
    if (memoryBacked && !form.sourceGatingBatchId) {
      notify('Select a completed gating snapshot for rewrite-backed chat.', 'danger')
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
          retrievalTopK: toNumber(form.retrievalTopK),
          rerankTopN: toNumber(form.rerankTopN),
          memoryTopN: toNumber(form.memoryTopN),
          rewriteCandidateCount: toNumber(form.rewriteCandidateCount),
          rewriteThreshold: toNumber(form.rewriteThreshold),
          sourceGatingBatchId: form.sourceGatingBatchId || null,
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
        description="Pin the online chat RAG path to one domain, one evaluated memory snapshot, and one rewrite policy."
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
                <select value={form.gatingPreset} onChange={(event) => setForm((prev) => ({ ...prev, gatingPreset: event.target.value, sourceGatingBatchId: '' }))}>
                  {GATING_PRESETS.map((preset) => <option key={preset} value={preset}>{preset}</option>)}
                </select>
              </label>
              <label className="filter-field">Completed snapshot
                <select value={form.sourceGatingBatchId} disabled={!memoryBacked} onChange={(event) => updateField('sourceGatingBatchId', event.target.value)}>
                  <option value="">{memoryBacked ? 'Select snapshot' : 'Not used in raw_only'}</option>
                  {snapshotOptions.map((batch) => (
                    <option key={batch.gatingBatchId} value={batch.gatingBatchId}>
                      {batch.methodCode || '-'} / {batch.gatingPreset} / accepted {batch.acceptedCount} / {batch.gatingBatchId}
                    </option>
                  ))}
                </select>
              </label>
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
            </div>
          </ExperimentSection>
        </div>

        <ConfigSummaryCard
          title="Active Chat Config"
          items={[
            { label: 'Domain', value: config?.displayName || domainKey || '-' },
            { label: 'Ready', value: config?.readyForRewrite ? 'yes' : config?.readinessMessage || '-' },
            { label: 'Strategies', value: (form.generationStrategies || []).join(', ') || '-' },
            { label: 'Snapshot', value: form.sourceGatingBatchId ? form.sourceGatingBatchId.slice(0, 8) : '-' },
            { label: 'Profile', value: form.rewriteQueryProfile },
            { label: 'Updated', value: config?.updatedAt ? fmtTime(config.updatedAt) : '-' },
          ]}
        />
      </div>

      <section className="data-panel">
        <div className="data-panel__header">
          <h3>Selected Snapshot</h3>
          {selectedSnapshot && <StatusBadge value={selectedSnapshot.status} />}
        </div>
        {selectedSnapshot ? (
          <div className="metadata-grid">
            <div><span>Batch</span><strong><IdBadge value={selectedSnapshot.gatingBatchId} /></strong></div>
            <div><span>Source run</span><strong><IdBadge value={selectedSnapshot.sourceGatingRunId} /></strong></div>
            <div><span>Method</span><strong>{selectedSnapshot.methodCode || '-'}</strong></div>
            <div><span>Accepted</span><strong>{selectedSnapshot.acceptedCount}</strong></div>
          </div>
        ) : (
          <div className="summary-card__meta">No compatible completed snapshot selected.</div>
        )}
      </section>
    </div>
  )
}
