import { useState } from 'react'
import { requestJson, toNumber } from '../lib/api.js'
import { DetailCard } from '../components/Common.jsx'

export function ChatPage({ navigate, notify }) {
  const [query, setQuery] = useState('')
  const [mode, setMode] = useState('selective_rewrite')
  const [gatingPreset, setGatingPreset] = useState('full_gating')
  const [threshold, setThreshold] = useState('0.05')
  const [loading, setLoading] = useState(false)
  const [reindexing, setReindexing] = useState(false)
  const [result, setResult] = useState(null)

  const ask = async () => {
    if (!query.trim()) return
    setLoading(true)
    try {
      const payload = await requestJson('/api/chat/ask', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          query: query.trim(),
          mode,
          rewriteThreshold: toNumber(threshold),
          gatingPreset,
        }),
      })
      setResult(payload)
    } catch (error) {
      notify(error.message, 'error')
    } finally {
      setLoading(false)
    }
  }

  const reindex = async () => {
    setReindexing(true)
    try {
      const payload = await requestJson('/api/admin/reindex', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ reindexChunks: true, reindexMemory: true }),
      })
      notify(`재색인 완료 · chunk=${payload.chunkEmbeddingsUpdated}, memory=${payload.memoryEmbeddingsUpdated}`)
    } catch (error) {
      notify(error.message, 'error')
    } finally {
      setReindexing(false)
    }
  }

  return (
    <div className="chat-shell">
      <header className="chat-hero">
        <div className="chat-hero__title">Query Forge Chat</div>
        <div className="chat-hero__subtitle">Spring 문서 기반 질의응답 + selective rewrite trace</div>
        <button type="button" className="button button--primary" onClick={() => navigate('/admin/pipeline')}>관리자 콘솔 이동</button>
      </header>

      <section className="chat-controls">
        <label>Mode
          <select value={mode} onChange={(event) => setMode(event.target.value)}>
            <option value="selective_rewrite">selective rewrite</option>
            <option value="raw_only">raw retrieval only</option>
            <option value="memory_only_gated">memory retrieval only (gated)</option>
            <option value="memory_only_ungated">memory retrieval only (ungated)</option>
            <option value="rewrite_always">rewrite always</option>
            <option value="selective_rewrite_with_session">selective rewrite + session context</option>
          </select>
        </label>
        <label>Gating Preset
          <select value={gatingPreset} onChange={(event) => setGatingPreset(event.target.value)}>
            <option value="full_gating">full_gating</option>
            <option value="rule_plus_llm">rule_plus_llm</option>
            <option value="rule_only">rule_only</option>
            <option value="ungated">ungated</option>
          </select>
        </label>
        <label>Threshold
          <input type="number" min="0" max="1" step="0.01" value={threshold} onChange={(event) => setThreshold(event.target.value)} />
        </label>
        <button type="button" className="button" disabled={reindexing} onClick={reindex}>{reindexing ? '재색인 중...' : '임베딩 재색인'}</button>
      </section>

      <section className="chat-panel">
        <textarea value={query} onChange={(event) => setQuery(event.target.value)} placeholder="질문을 입력하세요." />
        <button type="button" className="button button--primary" disabled={loading} onClick={ask}>{loading ? '처리 중...' : '질문하기'}</button>
      </section>

      <section className="chat-result">
        {!result && <div className="summary-card__meta">아직 실행 결과가 없습니다.</div>}
        {result && (
          <div className="detail-grid detail-grid--single">
            <DetailCard label="Answer" value={result.answer || '-'} mono={false} />
            <DetailCard
              label="Trace"
              value={[
                `queryId=${result.onlineQueryId || '-'}`,
                `rawQuery=${result.rawQuery || '-'}`,
                `finalQueryUsed=${result.finalQueryUsed || '-'}`,
                `rewriteApplied=${result.rewriteApplied}`,
                `latency=${JSON.stringify(result.latencyBreakdown || {})}`,
              ].join('\n')}
            />
            <DetailCard label="Rewrite Candidates" value={JSON.stringify(result.rewriteCandidates || [], null, 2)} />
            <DetailCard label="Retrieved Top Chunks" value={JSON.stringify(result.retrievedDocs || [], null, 2)} />
            <DetailCard label="Reranked Top Chunks" value={JSON.stringify(result.rerankedDocs || [], null, 2)} />
          </div>
        )}
      </section>
    </div>
  )
}
