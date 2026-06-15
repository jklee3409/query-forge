import { useEffect, useMemo, useState } from 'react'
import { DetailCard, StatusBadge } from '../components/Common.jsx'
import { appendQuery, requestJson } from '../lib/api.js'

const CHAT_DOMAIN_STORAGE_KEY = 'query-forge-chat-domain-id'

function formatCount(value) {
  return Number(value || 0).toLocaleString()
}

function readinessReasons(readiness) {
  return Array.isArray(readiness?.blockingReasons) ? readiness.blockingReasons.filter(Boolean) : []
}

export function ChatPage({ navigate, notify }) {
  const [domains, setDomains] = useState([])
  const [selectedDomainId, setSelectedDomainId] = useState(() => {
    try {
      return window.localStorage.getItem(CHAT_DOMAIN_STORAGE_KEY) || ''
    } catch {
      return ''
    }
  })
  const [config, setConfig] = useState(null)
  const [readiness, setReadiness] = useState(null)
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(false)
  const [domainsLoading, setDomainsLoading] = useState(false)
  const [result, setResult] = useState(null)

  const selectedDomain = useMemo(
    () => domains.find((domain) => domain.domainId === selectedDomainId) || null,
    [domains, selectedDomainId],
  )

  useEffect(() => {
    let cancelled = false
    setDomainsLoading(true)
    requestJson('/api/chat/domains')
      .then((payload) => {
        if (cancelled) return
        const rows = Array.isArray(payload) ? payload : []
        setDomains(rows)
        setSelectedDomainId((current) => {
          if (current && rows.some((row) => row.domainId === current)) return current
          return rows[0]?.domainId || ''
        })
      })
      .catch((error) => {
        if (!cancelled) notify(error.message, 'error')
      })
      .finally(() => {
        if (!cancelled) setDomainsLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [notify])

  useEffect(() => {
    if (!selectedDomainId) {
      setConfig(null)
      setReadiness(null)
      return undefined
    }
    let cancelled = false
    try {
      window.localStorage.setItem(CHAT_DOMAIN_STORAGE_KEY, selectedDomainId)
    } catch {
      // Domain selection still works for the current session.
    }
    Promise.all([
      requestJson(appendQuery('/api/chat/config', { domain_id: selectedDomainId })),
      requestJson(appendQuery('/api/chat/readiness', { domain_id: selectedDomainId })),
    ])
      .then(([configPayload, readinessPayload]) => {
        if (!cancelled) {
          setConfig(configPayload)
          setReadiness(readinessPayload)
        }
      })
      .catch((error) => {
        if (!cancelled) {
          setConfig(null)
          setReadiness(null)
          notify(error.message, 'error')
        }
      })
    return () => {
      cancelled = true
    }
  }, [selectedDomainId, notify])

  const ask = async () => {
    if (!query.trim() || !selectedDomainId) return
    setLoading(true)
    try {
      const payload = await requestJson('/api/chat/ask', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          domainId: selectedDomainId,
          query: query.trim(),
        }),
      })
      setResult(payload)
    } catch (error) {
      notify(error.message, 'error')
    } finally {
      setLoading(false)
    }
  }

  const rewrittenQuery = result?.rewriteApplied ? result.finalQueryUsed : ''
  const adminSettingsPath = selectedDomain?.domainKey
    ? `/admin/domains/${selectedDomain.domainKey}/chat-settings`
    : '/admin'
  const blockingReasons = readinessReasons(readiness)
  const rewriteBlocked = Boolean(readiness?.rewriteBackedMode && !readiness?.readyForRewrite)
  const baseBlocked = Boolean(readiness && (!readiness.activeConfigPresent || !readiness.configEnabled))
  const chatBlocked = baseBlocked || rewriteBlocked
  const chatDisabled = loading || !selectedDomainId || !config || !readiness || !config.enabled || chatBlocked

  return (
    <div className="chat-shell">
      <header className="chat-hero">
        <div className="chat-hero__title">Query Forge Chat</div>
        <div className="chat-hero__subtitle">Domain-pinned RAG chat with selective rewrite trace</div>
        <div className="chat-hero__actions">
          <button type="button" className="button" onClick={() => navigate(adminSettingsPath)}>Chat settings</button>
          <button type="button" className="button button--primary" onClick={() => navigate('/admin')}>Admin console</button>
        </div>
      </header>

      <section className="chat-controls">
        <label>Domain
          <select
            value={selectedDomainId}
            disabled={domainsLoading || domains.length === 0}
            onChange={(event) => {
              setSelectedDomainId(event.target.value)
              setResult(null)
            }}
          >
            {domains.map((domain) => (
              <option key={domain.domainId} value={domain.domainId}>
                {domain.displayName || domain.domainKey}
              </option>
            ))}
          </select>
        </label>
        <label>Rewrite profile
          <select value={config?.rewriteQueryProfile || 'compact_anchor'} disabled>
            <option value="compact_anchor">Compact anchor</option>
            <option value="detailed_intent">Detailed intent</option>
          </select>
        </label>
        <div className="chat-config-strip">
          <span>{config?.mode || '-'}</span>
          <span>{config?.gatingPreset || '-'}</span>
          <span>{(config?.generationStrategies || []).join('+') || '-'}</span>
          <span>{config?.retrievalBackend || 'local'} / {config?.retrieverMode || 'hybrid'}</span>
          <span>{config?.denseEmbeddingModel || '-'}</span>
          <span>{config?.sourceGatingBatchId ? `snapshot ${config.sourceGatingBatchId.slice(0, 8)}` : 'snapshot required'}</span>
          <span>{config?.rewriteAnchorInjectionEnabled ? 'anchor on' : 'anchor off'}</span>
        </div>
      </section>

      {chatBlocked && (
        <section className="chat-warning">
          {blockingReasons.join('; ') || 'Chat runtime config is incomplete.'}
        </section>
      )}

      <section className="chat-controls">
        <div className="chat-config-strip">
          <StatusBadge
            value={!readiness ? 'queued' : readiness.readyForRewrite ? 'completed' : 'failed'}
            label={!readiness ? 'loading' : readiness.readyForRewrite ? 'ready' : 'blocked'}
          />
          <span>memory {formatCount(readiness?.memoryCount)}</span>
          <span>accepted {formatCount(readiness?.acceptedGatedQueryCount)}</span>
          <span>
            embeddings {formatCount(readiness?.chunkEmbeddings?.materializedChunkCount)}
            /
            {formatCount(readiness?.chunkEmbeddings?.domainChunkCount)}
          </span>
          <span>{readiness?.promptBinding?.bindingKey || 'prompt binding'}</span>
        </div>
        {blockingReasons.length > 0 && (
          <div className="summary-card__meta">{blockingReasons.join('; ')}</div>
        )}
      </section>

      <section className="chat-panel">
        <textarea value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Enter a question for the selected technical-doc domain." />
        <button type="button" className="button button--primary" disabled={chatDisabled} onClick={ask}>
          {loading ? 'Running...' : 'Ask'}
        </button>
      </section>

      <section className="chat-result">
        {!result && <div className="summary-card__meta">No chat result yet.</div>}
        {result && (
          <div className="detail-grid detail-grid--single">
            <DetailCard label="Answer" value={result.answer || '-'} mono={false} />
            <div className="rewrite-trace-card">
              <div className="rewrite-trace-card__row">
                <span>Raw query</span>
                <strong>{result.rawQuery || '-'}</strong>
              </div>
              <div className="rewrite-trace-card__row">
                <span>Rewrite applied</span>
                <strong>{String(Boolean(result.rewriteApplied))}</strong>
              </div>
              <div className="rewrite-trace-card__row">
                <span>Rewritten query</span>
                <strong>{rewrittenQuery || '-'}</strong>
              </div>
              <div className="rewrite-trace-card__row">
                <span>Final query used</span>
                <strong>{result.finalQueryUsed || '-'}</strong>
              </div>
            </div>
            <DetailCard
              label="Applied Config"
              value={JSON.stringify({
                domain: result.appliedConfig?.displayName,
                mode: result.appliedConfig?.mode,
                gatingPreset: result.appliedConfig?.gatingPreset,
                generationStrategies: result.appliedConfig?.generationStrategies,
                sourceGatingBatchId: result.appliedConfig?.sourceGatingBatchId,
                sourceGatingRunId: result.appliedConfig?.sourceGatingRunId,
                retrievalBackend: result.appliedConfig?.retrievalBackend,
                denseEmbeddingModel: result.appliedConfig?.denseEmbeddingModel,
                retrieverMode: result.appliedConfig?.retrieverMode,
                retrieverCandidatePoolK: result.appliedConfig?.retrieverCandidatePoolK,
                retrieverFusionWeights: {
                  dense: result.appliedConfig?.retrieverDenseWeight,
                  bm25: result.appliedConfig?.retrieverBm25Weight,
                  technical: result.appliedConfig?.retrieverTechnicalWeight,
                },
                rewriteQueryProfile: result.appliedConfig?.rewriteQueryProfile,
                rewriteAnchorInjectionEnabled: result.appliedConfig?.rewriteAnchorInjectionEnabled,
              }, null, 2)}
            />
            <DetailCard label="Rewrite Candidates" value={JSON.stringify(result.rewriteCandidates || [], null, 2)} />
            <DetailCard label="Retrieved Top Chunks" value={JSON.stringify(result.retrievedDocs || [], null, 2)} />
            <DetailCard label="Memory Candidates" value={JSON.stringify(result.memoryTopN || [], null, 2)} />
          </div>
        )}
      </section>
    </div>
  )
}
