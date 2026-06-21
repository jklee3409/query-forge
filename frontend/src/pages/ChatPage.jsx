import { useEffect, useMemo, useState } from 'react'
import { StatusBadge } from '../components/Common.jsx'
import {
  ChatAnchorHints,
  ChatMemoryCandidates,
  ChatRetrievedChunks,
  ChatRewriteCandidates,
  ChatTraceDisclosure,
} from '../components/ChatTraceDetails.jsx'
import { appendQuery, requestJson } from '../lib/api.js'

const CHAT_DOMAIN_STORAGE_KEY = 'query-forge-chat-domain-id'

function formatCount(value) {
  return Number(value || 0).toLocaleString()
}

function readinessReasons(readiness) {
  return Array.isArray(readiness?.blockingReasons) ? readiness.blockingReasons.filter(Boolean) : []
}

function chatAskErrorMessage(error) {
  const text = String(error?.message || '').toLowerCase()
  if (
    text.includes('chat_runtime_config')
    || text.includes('chat is disabled')
    || text.includes('ready for rewrite')
    || text.includes('blocking')
  ) {
    return '현재 Chat Settings 상태로는 질문을 처리할 수 없습니다. 설정을 확인하세요.'
  }
  return '질문 처리 중 오류가 발생했습니다. 잠시 후 다시 시도하세요.'
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
    setResult(null)
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
      notify(chatAskErrorMessage(error), 'error')
    } finally {
      setLoading(false)
    }
  }

  const rewrittenQuery = result?.rewriteApplied
    ? result.finalQueryUsed
    : '재작성 미적용 · 원본 질의를 그대로 사용했습니다.'
  const adminSettingsPath = selectedDomain?.domainKey
    ? `/admin/domains/${selectedDomain.domainKey}/chat-settings`
    : '/admin'
  const blockingReasons = readinessReasons(readiness)
  const rewriteBlocked = Boolean(readiness?.rewriteBackedMode && !readiness?.readyForRewrite)
  const baseBlocked = Boolean(readiness && (!readiness.activeConfigPresent || !readiness.configEnabled))
  const chatBlocked = baseBlocked || rewriteBlocked
  const chatDisabled = loading || !selectedDomainId || !config || !readiness || !config.enabled || chatBlocked
  const sourceGatingBatchIds = Array.isArray(config?.sourceGatingBatchIds)
    ? config.sourceGatingBatchIds.filter(Boolean)
    : []

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
          <span>
            {sourceGatingBatchIds.length > 0
              ? `snapshots ${sourceGatingBatchIds.length}`
              : config?.sourceGatingBatchId
                ? `snapshot ${config.sourceGatingBatchId.slice(0, 8)}`
                : 'snapshot required'}
          </span>
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
        <button type="button" className="button button--primary chat-ask-button" disabled={chatDisabled} onClick={ask}>
          <span className="chat-ask-button__content">
            {loading && <span className="chat-ask-button__spinner" aria-hidden="true" />}
            <span>{loading ? '답변 생성 중' : 'Ask'}</span>
          </span>
        </button>
      </section>

      <section className="chat-result">
        {loading && (
          <div className="chat-result__loading" aria-live="polite">
            <span className="chat-result__loading-spinner" aria-hidden="true" />
            <div>
              <strong>응답 생성 중</strong>
              <small>합성 질의 검색, query rewrite, answer generation을 순서대로 처리하고 있습니다.</small>
            </div>
          </div>
        )}
        {!loading && !result && <div className="summary-card__meta">No chat result yet.</div>}
        {!loading && result && (
          <div className="chat-result-stack">
            <article className="chat-answer-panel">
              <div className="chat-answer-panel__header">
                <div>
                  <span className="chat-answer-panel__eyebrow">LLM answer</span>
                  <h3>최종 응답</h3>
                </div>
                <div className="chat-answer-panel__meta">
                  <span className="plain-badge">{result.answerModel || 'gemini-2.5-flash-lite'}</span>
                  <StatusBadge
                    value={result.rewriteApplied ? 'completed' : 'queued'}
                    label={result.rewriteApplied ? 'rewrite applied' : 'raw kept'}
                  />
                  <span className="plain-badge">context {result.citedChunkIds?.length || 0}</span>
                </div>
              </div>
              <p className="chat-answer-panel__text">{result.answer || '-'}</p>
            </article>

            <div className="rag-query-focus-grid">
              <section className="rag-query-focus rag-query-focus--raw">
                <div className="rag-query-focus__label">Original query</div>
                <p className="rag-query-focus__text">{result.rawQuery || '-'}</p>
              </section>
              <section className="rag-query-focus rag-query-focus--rewrite">
                <div className="rag-query-focus__label">Rewritten / final query</div>
                <p className="rag-query-focus__text">{rewrittenQuery || '-'}</p>
              </section>
            </div>

            <div className="chat-disclosure-group">
              <ChatTraceDisclosure label="사용된 합성 질의" defaultOpen>
                <ChatMemoryCandidates value={result.memoryTopN} />
              </ChatTraceDisclosure>
              <ChatTraceDisclosure label="Anchor 힌트">
                <ChatAnchorHints value={result.memoryTopN} />
              </ChatTraceDisclosure>
              <ChatTraceDisclosure label="재작성 후보">
                <ChatRewriteCandidates value={result.rewriteCandidates} />
              </ChatTraceDisclosure>
              <ChatTraceDisclosure label="검색 컨텍스트">
                <ChatRetrievedChunks value={result.retrievedDocs} />
              </ChatTraceDisclosure>
            </div>
          </div>
        )}
      </section>
    </div>
  )
}
