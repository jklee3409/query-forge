import { shortId } from '../lib/format.js'

function parseDetailPayload(value) {
  if (value == null) return null
  if (typeof value === 'string') {
    const trimmed = value.trim()
    if (!trimmed) return null
    try {
      return JSON.parse(trimmed)
    } catch {
      return trimmed
    }
  }
  return value
}

function isPlainDetailObject(value) {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function detailArray(value, nestedKeys = []) {
  const payload = parseDetailPayload(value)
  if (Array.isArray(payload)) return payload
  if (isPlainDetailObject(payload)) {
    for (const key of nestedKeys) {
      if (Array.isArray(payload[key])) return payload[key]
    }
  }
  return []
}

function firstDetailValue(value, keys = []) {
  if (!isPlainDetailObject(value)) return null
  for (const key of keys) {
    const item = value[key]
    if (item != null && String(item).trim() !== '') return item
  }
  return null
}

function formatDetailNumber(value, precision = 3) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed.toFixed(precision) : '-'
}

function renderEmpty(label) {
  return <div className="rag-detail-disclosure__empty">{label}</div>
}

function renderDetailTokenList(values, maxItems = 12) {
  const items = Array.isArray(values) ? values.filter((item) => item != null && String(item).trim()) : []
  if (!items.length) return null
  return (
    <div className="rag-detail-token-list">
      {items.slice(0, maxItems).map((item, index) => (
        <span key={`${String(item)}-${index}`} className="rag-detail-token">{String(item)}</span>
      ))}
    </div>
  )
}

function isTruthyDetailValue(value) {
  if (value === true || value === 1) return true
  if (typeof value !== 'string') return false
  return ['true', '1', 'yes', 'on'].includes(value.trim().toLowerCase())
}

function canonicalAnchorItems(candidate) {
  if (!candidate) return []
  const direct = parseDetailPayload(candidate.canonical_anchors || candidate.canonicalAnchors)
  if (Array.isArray(direct)) return direct.filter(isPlainDetailObject)
  if (isPlainDetailObject(direct) && Array.isArray(direct.anchors)) {
    return direct.anchors.filter(isPlainDetailObject)
  }
  const metadata = parseDetailPayload(candidate.metadata)
  const nested = metadata?.canonical_anchors || metadata?.canonicalAnchors
  if (Array.isArray(nested)) return nested.filter(isPlainDetailObject)
  if (isPlainDetailObject(nested) && Array.isArray(nested.anchors)) {
    return nested.anchors.filter(isPlainDetailObject)
  }
  return []
}

function renderCanonicalAnchorDetail(candidate) {
  const anchors = canonicalAnchorItems(candidate)
  if (!anchors.length) return null
  const scoringAnchors = anchors.filter((anchor) => isTruthyDetailValue(anchor.used_for_scoring ?? anchor.usedForScoring))
  const reviewAnchors = anchors.filter((anchor) => !isTruthyDetailValue(anchor.used_for_scoring ?? anchor.usedForScoring))
  const displayAnchors = [...scoringAnchors, ...reviewAnchors].slice(0, 6)
  return (
    <div className="rag-canonical-anchor-block">
      <div className="rag-canonical-anchor-block__header">
        <span>Canonical anchors</span>
        <strong>{scoringAnchors.length} scoring / {reviewAnchors.length} review</strong>
      </div>
      <div className="rag-canonical-anchor-list">
        {displayAnchors.map((anchor, index) => {
          const scoring = isTruthyDetailValue(anchor.used_for_scoring ?? anchor.usedForScoring)
          const canonical = firstDetailValue(anchor, ['canonical_form', 'canonicalForm']) || 'unresolved'
          const alias = firstDetailValue(anchor, ['display_alias', 'displayAlias', 'input_alias', 'inputAlias']) || '-'
          const confidence = firstDetailValue(anchor, ['confidence'])
          return (
            <div key={`${canonical}-${alias}-${index}`} className="rag-canonical-anchor" data-scoring={scoring ? 'true' : 'false'}>
              <div className="rag-canonical-anchor__main">
                <strong>{canonical}</strong>
              </div>
              <div className="rag-canonical-anchor__meta">
                <span>alias {alias}</span>
                {confidence != null && <span>conf {formatDetailNumber(confidence, 2)}</span>}
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

function collectAnchorHints(value) {
  const candidates = detailArray(value, ['memory_top_n', 'top_memory_candidates', 'memory_candidates', 'candidates', 'items'])
  const dedupe = new Set()
  const anchors = []
  const glossaryTerms = new Set()
  candidates.forEach((candidate, index) => {
    detailArray(candidate?.glossary_terms || candidate?.glossaryTerms || candidate?.terms).forEach((term) => {
      if (term != null && String(term).trim()) glossaryTerms.add(String(term).trim())
    })
    canonicalAnchorItems(candidate).forEach((anchor) => {
      const canonical = firstDetailValue(anchor, ['canonical_form', 'canonicalForm']) || ''
      const alias = firstDetailValue(anchor, ['display_alias', 'displayAlias', 'input_alias', 'inputAlias']) || ''
      const key = `${canonical}|${alias}`
      if (!dedupe.has(key)) {
        dedupe.add(key)
        anchors.push({
          ...anchor,
          memoryIndex: index + 1,
        })
      }
    })
  })
  return { anchors, glossaryTerms: Array.from(glossaryTerms) }
}

export function ChatTraceDisclosure({ label, defaultOpen = false, children }) {
  return (
    <details className="rag-detail-disclosure" open={defaultOpen}>
      <summary>{label}</summary>
      <div className="rag-detail-disclosure__content">{children}</div>
    </details>
  )
}

export function ChatMemoryCandidates({ value }) {
  const candidates = detailArray(value, ['memory_top_n', 'top_memory_candidates', 'memory_candidates', 'candidates', 'items'])
  if (!candidates.length) return renderEmpty('사용된 합성 질의가 없습니다.')
  return (
    <div className="rag-structured-list">
      {candidates.slice(0, 8).map((candidate, index) => {
        const queryText = firstDetailValue(candidate, ['query_text', 'queryText', 'query', 'text'])
        const score = firstDetailValue(candidate, ['similarity', 'score'])
        const method = firstDetailValue(candidate, ['generation_strategy', 'generationStrategy'])
        const docId = firstDetailValue(candidate, ['target_doc_id', 'targetDocId', 'document_id'])
        const memoryId = firstDetailValue(candidate, ['memory_id', 'memoryId'])
        const sourceBatch = firstDetailValue(candidate, ['source_gating_batch_id', 'sourceGatingBatchId'])
        const terms = detailArray(candidate?.glossary_terms || candidate?.glossaryTerms || candidate?.terms)
        const candidateTags = [
          method ? `method ${method}` : null,
          docId ? `doc ${shortId(docId)}` : null,
          sourceBatch ? `snapshot ${shortId(sourceBatch)}` : null,
          memoryId ? `memory ${shortId(memoryId)}` : null,
          ...terms,
        ].filter((item) => item != null && String(item).trim())
        return (
          <article key={memoryId || `${queryText}-${index}`} className="rag-structured-card rag-structured-card--candidate">
            <div className="rag-structured-card__topline">
              <span className="rag-structured-rank">#{index + 1}</span>
              {score != null && <span className="rag-structured-score">score {formatDetailNumber(score)}</span>}
            </div>
            <p className="rag-structured-card__text">{queryText || '-'}</p>
            {renderDetailTokenList(candidateTags, 6)}
            {renderCanonicalAnchorDetail(candidate)}
          </article>
        )
      })}
    </div>
  )
}

export function ChatAnchorHints({ value }) {
  const { anchors, glossaryTerms } = collectAnchorHints(value)
  if (!anchors.length && !glossaryTerms.length) return renderEmpty('표시할 Anchor 힌트가 없습니다.')
  return (
    <div className="rag-structured-list">
      {anchors.length > 0 && (
        <article className="rag-structured-card">
          <div className="rag-canonical-anchor-block">
            <div className="rag-canonical-anchor-block__header">
              <span>Anchor hints</span>
              <strong>{anchors.length} items</strong>
            </div>
            <div className="rag-canonical-anchor-list">
              {anchors.slice(0, 10).map((anchor, index) => {
                const scoring = isTruthyDetailValue(anchor.used_for_scoring ?? anchor.usedForScoring)
                const canonical = firstDetailValue(anchor, ['canonical_form', 'canonicalForm']) || 'unresolved'
                const alias = firstDetailValue(anchor, ['display_alias', 'displayAlias', 'input_alias', 'inputAlias']) || '-'
                const confidence = firstDetailValue(anchor, ['confidence'])
                return (
                  <div key={`${canonical}-${alias}-${index}`} className="rag-canonical-anchor" data-scoring={scoring ? 'true' : 'false'}>
                    <div className="rag-canonical-anchor__main">
                      <strong>{canonical}</strong>
                      <span>memory #{anchor.memoryIndex}</span>
                    </div>
                    <div className="rag-canonical-anchor__meta">
                      <span>alias {alias}</span>
                      {confidence != null && <span>conf {formatDetailNumber(confidence, 2)}</span>}
                    </div>
                  </div>
                )
              })}
            </div>
          </div>
        </article>
      )}
      {glossaryTerms.length > 0 && (
        <article className="rag-structured-card">
          <div className="rag-canonical-anchor-block__header">
            <span>Glossary terms</span>
            <strong>{glossaryTerms.length} terms</strong>
          </div>
          {renderDetailTokenList(glossaryTerms, 20)}
        </article>
      )}
    </div>
  )
}

export function ChatRewriteCandidates({ value }) {
  const candidates = detailArray(value, ['rewrite_candidates', 'candidates', 'items'])
  if (!candidates.length) return renderEmpty('재작성 후보가 없습니다.')
  return (
    <div className="rag-structured-list">
      {candidates.slice(0, 8).map((candidate, index) => {
        const label = firstDetailValue(candidate, ['label', 'name', 'strategy']) || `candidate_${index + 1}`
        const query = firstDetailValue(candidate, ['candidateQuery', 'candidate_query', 'query', 'query_text', 'rewrite_query'])
        const confidence = firstDetailValue(candidate, ['confidenceScore', 'confidence_score', 'confidence', 'score'])
        const adopted = Boolean(candidate?.adopted)
        const reason = firstDetailValue(candidate, ['rejectedReason', 'rejected_reason', 'reason', 'decision_reason'])
        return (
          <article key={`${label}-${index}`} className="rag-structured-card rag-structured-card--rewrite">
            <div className="rag-structured-card__topline">
              <span className="rag-detail-mode-chip">{label}</span>
              {confidence != null && <span className="rag-structured-score">confidence {formatDetailNumber(confidence)}</span>}
              {adopted && <span className="rag-structured-rank">selected</span>}
            </div>
            <p className="rag-structured-card__text">{query || '-'}</p>
            {reason && <div className="rag-structured-card__note">{reason}</div>}
          </article>
        )
      })}
    </div>
  )
}

export function ChatRetrievedChunks({ value }) {
  const chunks = detailArray(value, ['retrieved_top_k', 'retrieved_chunks', 'chunks', 'items'])
  if (!chunks.length) return renderEmpty('사용된 검색 컨텍스트가 없습니다.')
  return (
    <div className="rag-structured-list">
      {chunks.slice(0, 8).map((chunk, index) => {
        const chunkId = firstDetailValue(chunk, ['chunkId', 'chunk_id'])
        const documentId = firstDetailValue(chunk, ['documentId', 'document_id', 'doc_id'])
        const content = firstDetailValue(chunk, ['chunkTextPreview', 'chunk_text_preview', 'content', 'text', 'chunk_text', 'snippet'])
        const score = firstDetailValue(chunk, ['score', 'rerank_score', 'retrieval_score'])
        return (
          <article key={chunkId || `${documentId}-${index}`} className="rag-structured-card rag-structured-card--chunk">
            <div className="rag-structured-card__topline">
              <span className="rag-structured-rank">#{index + 1}</span>
              {score != null && <span className="rag-structured-score">score {formatDetailNumber(score)}</span>}
            </div>
            <div className="rag-structured-card__meta">
              {documentId && <span>doc {shortId(documentId)}</span>}
              {chunkId && <span>chunk {shortId(chunkId)}</span>}
            </div>
            {content && <p className="rag-structured-card__snippet">{String(content)}</p>}
          </article>
        )
      })}
    </div>
  )
}
