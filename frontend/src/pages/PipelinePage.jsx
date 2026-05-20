import { useEffect, useMemo, useState } from 'react'
import { DetailCard, IdBadge, Modal, StatusBadge } from '../components/Common.jsx'
import { SelectDropdown } from '../components/SelectDropdown.jsx'
import { fmtTime, shortId } from '../lib/format.js'
import { appendQuery, queryString, requestJson } from '../lib/api.js'

const ANCHOR_REVIEW_DECISION_LABELS = {
  pending: '검토 대기',
  approve: '승인',
  skip: '건너뛰기',
}

const ANCHOR_REVIEW_STATUS_META = {
  would_update: {
    label: '변경 후보',
    helper: '현재 값과 제안 값이 다른 항목',
    tone: 'changed',
  },
  conflict: {
    label: '충돌',
    helper: '같은 정규화 값이 있어 수동 검토 필요',
    tone: 'conflict',
  },
  invalid: {
    label: '무효',
    helper: '정규화 결과가 비어 있거나 사용할 수 없음',
    tone: 'invalid',
  },
  unchanged: {
    label: '변경 없음',
    helper: '현재 값과 제안 값이 동일',
    tone: 'unchanged',
  },
}

const ANCHOR_RUN_STATUS_LABELS = {
  pending_review: '검토 대기',
  approved: '승인됨',
  rejected: '반려',
  failed: '실패',
}

const MULTI_SOURCE_ANCHOR_RELATION_LABELS = {
  canonical_alias: 'Canonical alias',
  synthetic_query_cooccurrence: 'Synthetic query co-occurrence',
  chunk_cooccurrence: 'Chunk co-occurrence',
}

const MULTI_SOURCE_ANCHOR_ACTIVE_STATUSES = new Set(['running'])

function formatInteger(value) {
  const numericValue = Number(value ?? 0)
  if (!Number.isFinite(numericValue)) return '-'
  return numericValue.toLocaleString()
}

function reviewDecisionLabel(value) {
  return ANCHOR_REVIEW_DECISION_LABELS[value] || value || '-'
}

function anchorStatusMeta(value) {
  return ANCHOR_REVIEW_STATUS_META[value] || {
    label: value || '-',
    helper: '상태 확인 필요',
    tone: 'unknown',
  }
}

function anchorRunStatusLabel(value) {
  return ANCHOR_RUN_STATUS_LABELS[value] || value || '-'
}

function AnchorReviewStat({ label, value, helper, tone = 'neutral' }) {
  return (
    <article className="anchor-review-stat" data-tone={tone}>
      <div className="anchor-review-stat__label">{label}</div>
      <strong>{value}</strong>
      <p>{helper}</p>
    </article>
  )
}

function AnchorCandidateStatusBadge({ status }) {
  const meta = anchorStatusMeta(status)
  return (
    <span className="anchor-review-status-badge" data-status={meta.tone} title={meta.helper}>
      <strong>{meta.label}</strong>
      {meta.tone === 'conflict' && <small>수동 검토 필요</small>}
    </span>
  )
}

function ReviewDecisionBadge({ decision }) {
  return (
    <span className="anchor-review-decision-badge" data-decision={decision || 'pending'}>
      {reviewDecisionLabel(decision)}
    </span>
  )
}

function AnchorValueCell({ value, normalizedValue, tone }) {
  const primaryValue = value || '-'
  const secondaryValue = normalizedValue || '-'
  return (
    <div className="anchor-review-value-stack" data-tone={tone}>
      <code className="anchor-review-code" title={primaryValue}>{primaryValue}</code>
      <code className="anchor-review-code anchor-review-code--sub" title={secondaryValue}>{secondaryValue}</code>
    </div>
  )
}

function ConflictCell({ conflictTermId }) {
  if (!conflictTermId) {
    return <span className="anchor-review-muted">없음</span>
  }
  return (
    <span className="anchor-review-conflict" title={`같은 정규화 값과 충돌하는 기존 용어 ID: ${conflictTermId}`}>
      <span>같은 정규화 값의 기존 용어</span>
      <span className="mono-text">{shortId(conflictTermId)}</span>
    </span>
  )
}

function candidateDecisionHelp(candidate) {
  if (candidate.resolutionStatus === 'would_update') {
    return '승인하면 전체 작업 승인 시 제안 값이 적용됩니다.'
  }
  if (candidate.resolutionStatus === 'conflict') {
    return '충돌 항목은 개별 승인할 수 없습니다. 검토 대기 또는 건너뛰기로 처리하세요.'
  }
  if (candidate.resolutionStatus === 'invalid') {
    return '무효 항목은 승인할 수 없습니다. 건너뛰기로 제외하세요.'
  }
  if (candidate.resolutionStatus === 'unchanged') {
    return '변경 없음 항목은 검토 결정이 필요하지 않습니다.'
  }
  return ''
}

function AnchorNormalizationReviewBody({ detail, onSaveReviews, onSaveAndApprove, onRejectRun, onDirtyChange }) {
  const run = detail?.run || {}
  const candidates = useMemo(
    () => (Array.isArray(detail?.candidates) ? detail.candidates : []),
    [detail?.candidates]
  )
  const [showUnchanged, setShowUnchanged] = useState(false)
  const [reviewNote, setReviewNote] = useState('')
  const [saving, setSaving] = useState(false)
  const [decisions, setDecisions] = useState(() =>
    Object.fromEntries(candidates.map((candidate) => [
      candidate.candidateId,
      candidate.reviewDecision || 'pending',
    ]))
  )

  const actionableCandidates = useMemo(
    () => candidates.filter((candidate) => candidate.resolutionStatus !== 'unchanged'),
    [candidates]
  )

  const visibleCandidates = useMemo(
    () => (showUnchanged ? candidates : actionableCandidates),
    [actionableCandidates, candidates, showUnchanged]
  )

  const reviewStats = useMemo(() => {
    const pendingCount = actionableCandidates.filter((candidate) => (decisions[candidate.candidateId] || 'pending') === 'pending').length
    const invalidApprovalCount = actionableCandidates.filter((candidate) =>
      (decisions[candidate.candidateId] || 'pending') === 'approve' && candidate.resolutionStatus !== 'would_update'
    ).length
    const approvedCount = actionableCandidates.filter((candidate) => (decisions[candidate.candidateId] || 'pending') === 'approve').length
    const skippedCount = actionableCandidates.filter((candidate) => (decisions[candidate.candidateId] || 'pending') === 'skip').length
    const dirtyCount = actionableCandidates.filter((candidate) =>
      (decisions[candidate.candidateId] || 'pending') !== (candidate.reviewDecision || 'pending')
    ).length
    return { pendingCount, invalidApprovalCount, approvedCount, skippedCount, dirtyCount }
  }, [actionableCandidates, decisions])

  useEffect(() => {
    onDirtyChange?.(reviewStats.dirtyCount > 0)
  }, [onDirtyChange, reviewStats.dirtyCount])

  const updateDecision = (candidateId, decision) => {
    setDecisions((prev) => ({ ...prev, [candidateId]: decision }))
  }

  const markWouldUpdateApproved = () => {
    setDecisions((prev) => {
      const next = { ...prev }
      actionableCandidates.forEach((candidate) => {
        if (candidate.resolutionStatus === 'would_update') {
          next[candidate.candidateId] = 'approve'
        }
      })
      return next
    })
  }

  const markUnsafeSkipped = () => {
    setDecisions((prev) => {
      const next = { ...prev }
      actionableCandidates.forEach((candidate) => {
        if (candidate.resolutionStatus === 'conflict' || candidate.resolutionStatus === 'invalid') {
          next[candidate.candidateId] = 'skip'
        }
      })
      return next
    })
  }

  const resetActionable = () => {
    setDecisions((prev) => {
      const next = { ...prev }
      actionableCandidates.forEach((candidate) => {
        next[candidate.candidateId] = candidate.reviewDecision || 'pending'
      })
      return next
    })
  }

  const buildReviewPayload = () =>
    actionableCandidates.map((candidate) => ({
      candidateId: candidate.candidateId,
      decision: decisions[candidate.candidateId] || 'pending',
      note: reviewNote || null,
    }))

  const saveReviews = async () => {
    setSaving(true)
    try {
      await onSaveReviews(run.runId, buildReviewPayload(), reviewNote)
    } finally {
      setSaving(false)
    }
  }

  const saveAndApprove = async () => {
    setSaving(true)
    try {
      await onSaveAndApprove(run.runId, buildReviewPayload(), reviewNote)
    } finally {
      setSaving(false)
    }
  }

  const rejectRun = async () => {
    setSaving(true)
    try {
      await onRejectRun(run.runId)
    } finally {
      setSaving(false)
    }
  }

  const canApprove = run.status === 'pending_review'
    && reviewStats.pendingCount === 0
    && reviewStats.invalidApprovalCount === 0

  const approveBlockReason = useMemo(() => {
    if (saving) return '저장 처리가 진행 중입니다.'
    if (run.status !== 'pending_review') return '검토 대기 상태의 작업만 승인할 수 있습니다.'
    if (reviewStats.pendingCount > 0) return `검토 대기 항목이 ${reviewStats.pendingCount}개 남아 있어 승인할 수 없습니다.`
    if (reviewStats.invalidApprovalCount > 0) return '충돌/무효 항목은 승인할 수 없습니다. 건너뛰기로 바꿔 주세요.'
    return ''
  }, [reviewStats.invalidApprovalCount, reviewStats.pendingCount, run.status, saving])

  const saveBlockReason = reviewStats.invalidApprovalCount > 0
    ? '충돌/무효 항목은 승인으로 저장할 수 없습니다. 건너뛰기로 바꿔 주세요.'
    : ''

  return (
    <div className="anchor-review-modal">
      <p className="anchor-review-intro">
        Anchor 정규화 후보를 검토한 뒤 승인 또는 건너뛰기 결정을 저장하세요. 충돌/무효 항목은 자동 승인되지 않으며 수동 검토가 필요합니다.
      </p>

      <div className="anchor-review-stats-grid">
        <AnchorReviewStat label="전체 후보" value={`${run.candidateCount ?? 0}개`} helper="정규화 대상 후보 수" />
        <AnchorReviewStat label="변경 후보" value={`${run.changedCount ?? 0}개`} helper="현재 값과 제안 값이 다른 항목" tone="changed" />
        <AnchorReviewStat
          label="충돌 / 무효"
          value={`${run.conflictCount ?? 0}개 / ${run.invalidCount ?? 0}개`}
          helper="수동 확인이 필요한 항목"
          tone="warning"
        />
        <AnchorReviewStat label="검토 대기" value={`${reviewStats.pendingCount}개`} helper="저장 후 승인 전 결정이 필요한 항목" tone="pending" />
      </div>

      <div className="anchor-review-workflow">
        <section className="anchor-review-step">
          <div className="anchor-review-step__head">
            <span>1</span>
            <div>
              <h3>일괄 처리</h3>
              <p>안전한 변경은 승인으로 표시하고, 충돌/무효 항목은 건너뛰기로 분리합니다.</p>
            </div>
          </div>
          <div className="form-actions">
            <button type="button" className="button button--ghost" onClick={markWouldUpdateApproved} disabled={saving || actionableCandidates.length === 0}>
              안전한 변경 승인
            </button>
            <button type="button" className="button button--ghost" onClick={markUnsafeSkipped} disabled={saving || actionableCandidates.length === 0}>
              충돌/무효 항목 건너뛰기
            </button>
            <button
              type="button"
              className="button button--ghost"
              onClick={resetActionable}
              disabled={saving || reviewStats.dirtyCount === 0}
              title="현재 화면에서 바꾼 검토 결정을 마지막 저장 상태로 되돌립니다."
            >
              결정 초기화
            </button>
            <label className={`check-pill ${showUnchanged ? 'is-active' : ''}`}>
              <input type="checkbox" checked={showUnchanged} onChange={(event) => setShowUnchanged(event.target.checked)} />
              <span className="check-pill__box" aria-hidden="true">{showUnchanged ? '✓' : ''}</span>
              <span className="check-pill__text">변경 없음 표시</span>
            </label>
          </div>
          <p className="anchor-review-help">결정 초기화는 저장된 서버 값을 되돌리는 동작이 아니라, 아직 저장하지 않은 화면상의 변경만 되돌립니다.</p>
        </section>

        <section className="anchor-review-step">
          <div className="anchor-review-step__head">
            <span>2</span>
            <div>
              <h3>검토 메모</h3>
              <p>이번 저장/승인에 남길 메모입니다. 각 후보 결정과 함께 저장됩니다.</p>
            </div>
          </div>
          <label className="filter-field filter-field--grow">
            검토 메모
            <textarea
              value={reviewNote}
              onChange={(event) => setReviewNote(event.target.value)}
              placeholder="예: 충돌 항목은 기존 용어와 의미가 달라 건너뜁니다."
              rows={3}
            />
          </label>
        </section>

        <section className="anchor-review-step">
          <div className="anchor-review-step__head">
            <span>3</span>
            <div>
              <h3>저장 / 승인 / 반려</h3>
              <p>검토 결정을 먼저 저장한 뒤, 검토 대기 항목이 없을 때 작업을 승인할 수 있습니다.</p>
            </div>
          </div>
          <div className="form-actions">
            <button
              type="button"
              className="button"
              onClick={saveReviews}
              disabled={saving || run.status !== 'pending_review' || actionableCandidates.length === 0 || reviewStats.invalidApprovalCount > 0}
              title={saveBlockReason}
            >
              검토 결정 저장
            </button>
            <button type="button" className="button button--primary" onClick={saveAndApprove} disabled={saving || !canApprove} title={approveBlockReason}>
              저장 후 작업 승인
            </button>
            {run.status === 'pending_review' && (
              <button type="button" className="button button--danger" onClick={rejectRun} disabled={saving}>
                작업 반려
              </button>
            )}
          </div>
          {approveBlockReason && <div className="anchor-review-block-reason">{approveBlockReason}</div>}
        </section>

        <div className="anchor-review-summary-badges" aria-label="검토 결정 요약">
          <span data-kind="approved">승인 {reviewStats.approvedCount}개</span>
          <span data-kind="skipped">건너뛰기 {reviewStats.skippedCount}개</span>
          <span data-kind="pending">검토 대기 {reviewStats.pendingCount}개</span>
          <span data-kind={reviewStats.dirtyCount > 0 ? 'dirty' : 'clean'}>미저장 변경 {reviewStats.dirtyCount}개</span>
        </div>
      </div>

      <div className="table-wrap anchor-review-table-wrap">
        <table className="data-table anchor-review-table">
          <thead>
            <tr><th>상태</th><th>검토 결정</th><th>현재 값</th><th>제안 값</th><th>용어</th><th>충돌 대상</th></tr>
          </thead>
          <tbody>
            {visibleCandidates.map((candidate) => {
              const decision = decisions[candidate.candidateId] || 'pending'
              const disabled = candidate.resolutionStatus === 'unchanged' || run.status !== 'pending_review'
              const decisionHelp = candidateDecisionHelp(candidate)
              return (
                <tr key={candidate.candidateId}>
                  <td><AnchorCandidateStatusBadge status={candidate.resolutionStatus} /></td>
                  <td>
                    {disabled ? (
                      candidate.resolutionStatus === 'unchanged'
                        ? <span className="anchor-review-decision-badge" data-decision="unchanged">검토 불필요</span>
                        : <ReviewDecisionBadge decision={candidate.reviewDecision || 'pending'} />
                    ) : (
                      <select
                        className="anchor-review-select"
                        value={decision}
                        onChange={(event) => updateDecision(candidate.candidateId, event.target.value)}
                        aria-label={`${candidate.currentCanonicalForm || '후보'} 검토 결정`}
                      >
                        <option value="pending">검토 대기</option>
                        {candidate.resolutionStatus === 'would_update' && <option value="approve">승인</option>}
                        <option value="skip">건너뛰기</option>
                      </select>
                    )}
                    {decisionHelp && <div className="anchor-review-decision-help">{decisionHelp}</div>}
                    {candidate.reviewNote && (
                      <div className="anchor-review-note" title={candidate.reviewNote}>
                        메모: {candidate.reviewNote}
                      </div>
                    )}
                  </td>
                  <td><AnchorValueCell value={candidate.currentCanonicalForm} normalizedValue={candidate.currentNormalizedForm} tone="current" /></td>
                  <td><AnchorValueCell value={candidate.proposedCanonicalForm} normalizedValue={candidate.proposedNormalizedForm} tone="proposed" /></td>
                  <td>
                    <div className="anchor-review-term">
                      <strong>{candidate.termType || '용어'}</strong>
                      <span className="mono-text" title={candidate.termId}>{shortId(candidate.termId)}</span>
                    </div>
                  </td>
                  <td><ConflictCell conflictTermId={candidate.conflictTermId} /></td>
                </tr>
              )
            })}
            {visibleCandidates.length === 0 && (
              <tr><td colSpan={6}>검토가 필요한 후보가 없습니다.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}

export function PipelinePage({ notify, domainId = null }) {
  const runPageSize = 3
  const documentPageSize = 10
  const anchorPageSize = 10

  const [summary, setSummary] = useState(null)
  const [runs, setRuns] = useState([])
  const [runPage, setRunPage] = useState(0)
  const [runHasNextPage, setRunHasNextPage] = useState(false)
  const [sources, setSources] = useState([])
  const [sourceSearch, setSourceSearch] = useState('')
  const [selectedSourceIds, setSelectedSourceIds] = useState([])
  const [documents, setDocuments] = useState([])
  const [documentPage, setDocumentPage] = useState(0)
  const [documentHasNextPage, setDocumentHasNextPage] = useState(false)
  const [filters, setFilters] = useState({ source_id: '', version_label: '', document_id: '', search: '' })
  const [modal, setModal] = useState(null)
  const [busyRunType, setBusyRunType] = useState('')
  const [anchorEvalRuns, setAnchorEvalRuns] = useState([])
  const [anchorEvalRunsLoaded, setAnchorEvalRunsLoaded] = useState(false)
  const [anchorEvalRunsLoading, setAnchorEvalRunsLoading] = useState(false)
  const [anchorEvalBusy, setAnchorEvalBusy] = useState(false)
  const [anchorEvalDetailBusy, setAnchorEvalDetailBusy] = useState(false)
  const [anchorEvalScopeDocuments, setAnchorEvalScopeDocuments] = useState([])
  const [anchorEvalScopeChunks, setAnchorEvalScopeChunks] = useState([])
  const [anchorEvalLoadingScope, setAnchorEvalLoadingScope] = useState(false)
  const [anchors, setAnchors] = useState([])
  const [anchorListLoaded, setAnchorListLoaded] = useState(false)
  const [anchorListLoading, setAnchorListLoading] = useState(false)
  const [anchorPage, setAnchorPage] = useState(0)
  const [anchorHasNextPage, setAnchorHasNextPage] = useState(false)
  const [anchorNormalizationRuns, setAnchorNormalizationRuns] = useState([])
  const [anchorNormalizationRunsLoaded, setAnchorNormalizationRunsLoaded] = useState(false)
  const [anchorNormalizationRunsLoading, setAnchorNormalizationRunsLoading] = useState(false)
  const [anchorNormalizationBusy, setAnchorNormalizationBusy] = useState(false)
  const [anchorNormalizationDetailBusy, setAnchorNormalizationDetailBusy] = useState(false)
  const [anchorNormalizationDirty, setAnchorNormalizationDirty] = useState(false)
  const [multiSourceAnchorRuns, setMultiSourceAnchorRuns] = useState([])
  const [multiSourceAnchorRunsLoaded, setMultiSourceAnchorRunsLoaded] = useState(false)
  const [multiSourceAnchorRunsLoading, setMultiSourceAnchorRunsLoading] = useState(false)
  const [multiSourceAnchorBusy, setMultiSourceAnchorBusy] = useState(false)
  const [anchorFilterDocuments, setAnchorFilterDocuments] = useState([])
  const [anchorFilterDocumentsLoaded, setAnchorFilterDocumentsLoaded] = useState(false)
  const [anchorFilterChunks, setAnchorFilterChunks] = useState([])
  const [anchorFilterLoading, setAnchorFilterLoading] = useState(false)
  const [anchorFilters, setAnchorFilters] = useState({
    documentId: '',
    chunkId: '',
    keyword: '',
  })
  const [anchorEvalForm, setAnchorEvalForm] = useState({
    runName: '',
    productName: '',
    sourceId: '',
    selectedDocumentIds: [],
    selectedChunkIds: [],
    sampleSize: 20,
    candidateLimit: 10,
  })
  const [sourceForm, setSourceForm] = useState({
    sourceId: '',
    productName: '',
    startUrlsText: '',
    allowPrefixesText: '',
    denyUrlPatternsText: '',
    enabled: true,
    requestDelaySeconds: 0.75,
    maxDepth: 4,
  })
  const [quickSourceUrl, setQuickSourceUrl] = useState('')

  const selectedSourceForEval = useMemo(
    () => sources.find((source) => source.sourceId === anchorEvalForm.sourceId) || null,
    [sources, anchorEvalForm.sourceId]
  )

  const anchorDocumentOptions = useMemo(
    () =>
      anchorFilterDocuments.map((document) => ({
        value: document.documentId,
        label: document.title || document.documentId,
        meta: shortId(document.documentId),
      })),
    [anchorFilterDocuments]
  )

  const anchorChunkOptions = useMemo(
    () =>
      anchorFilterChunks.map((chunk) => ({
        value: chunk.chunkId,
        label: `${chunk.chunkId} · #${chunk.chunkIndexInDocument}`,
        meta: (chunk.sectionPathText || '').slice(0, 120),
      })),
    [anchorFilterChunks]
  )

  const latestMultiSourceAnchorRun = multiSourceAnchorRuns[0] || null
  const latestMultiSourceRelationRows = useMemo(() => {
    const rows = latestMultiSourceAnchorRun?.summaryJson?.affected_rows_by_relation_type || {}
    return Object.entries(rows).map(([relationType, count]) => ({
      relationType,
      label: MULTI_SOURCE_ANCHOR_RELATION_LABELS[relationType] || relationType,
      count,
    }))
  }, [latestMultiSourceAnchorRun])
  const multiSourceAnchorBuildActive = MULTI_SOURCE_ANCHOR_ACTIVE_STATUSES.has(
    String(latestMultiSourceAnchorRun?.status || '').toLowerCase()
  )

  const loadSummary = async () => {
    setSummary(await requestJson(appendQuery('/api/admin/pipeline/dashboard', { domain_id: domainId })))
  }

  const loadRuns = async (page = runPage) => {
    const query = queryString({ domain_id: domainId, limit: runPageSize + 1, offset: page * runPageSize })
    const payload = await requestJson(`/api/admin/pipeline/runs?${query}`)
    const normalized = Array.isArray(payload) ? payload : []
    setRunHasNextPage(normalized.length > runPageSize)
    setRuns(normalized.slice(0, runPageSize))
  }

  const loadSources = async () => {
    const payload = await requestJson(appendQuery('/api/admin/corpus/sources', { domain_id: domainId }))
    setSources(Array.isArray(payload) ? payload : [])
  }

  const loadDocuments = async (nextFilters = filters, page = documentPage) => {
    const query = queryString({
      ...nextFilters,
      domain_id: domainId,
      active_only: true,
      limit: documentPageSize + 1,
      offset: page * documentPageSize,
    })
    const payload = await requestJson(`/api/admin/corpus/documents${query ? `?${query}` : ''}`)
    const normalized = Array.isArray(payload) ? payload : []
    setDocumentHasNextPage(normalized.length > documentPageSize)
    setDocuments(normalized.slice(0, documentPageSize))
  }

  const loadAnchorEvalRuns = async () => {
    setAnchorEvalRunsLoading(true)
    try {
      const payload = await requestJson('/api/admin/corpus/anchors/eval/runs?limit=20&offset=0')
      setAnchorEvalRuns(Array.isArray(payload) ? payload : [])
      setAnchorEvalRunsLoaded(true)
    } finally {
      setAnchorEvalRunsLoading(false)
    }
  }

  const loadAnchorEvalScopeDocuments = async (sourceId, productName) => {
    if (!sourceId && !productName) {
      setAnchorEvalScopeDocuments([])
      return
    }
    const query = queryString({
      source_id: sourceId || undefined,
      product_name: productName || undefined,
      domain_id: domainId,
      active_only: true,
      limit: 500,
      offset: 0,
    })
    const payload = await requestJson(`/api/admin/corpus/documents?${query}`)
    setAnchorEvalScopeDocuments(Array.isArray(payload) ? payload : [])
  }

  const loadAnchorEvalScopeChunks = async (documentIds) => {
    if (!Array.isArray(documentIds) || documentIds.length === 0) {
      setAnchorEvalScopeChunks([])
      return
    }
    const responses = await Promise.all(
      documentIds.map((documentId) => {
        const query = queryString({ document_id: documentId, active_only: true, limit: 200, offset: 0 })
        return requestJson(`/api/admin/corpus/chunks?${query}`)
      })
    )
    const merged = responses.flatMap((rows) => (Array.isArray(rows) ? rows : []))
    setAnchorEvalScopeChunks(merged)
  }

  const loadAnchorFilterDocuments = async () => {
    const query = queryString({
      active_only: true,
      domain_id: domainId,
      limit: 500,
      offset: 0,
    })
    const payload = await requestJson(`/api/admin/corpus/documents?${query}`)
    setAnchorFilterDocuments(Array.isArray(payload) ? payload : [])
  }

  const ensureAnchorFilterDocumentsLoaded = async () => {
    if (anchorFilterDocumentsLoaded) return
    setAnchorFilterLoading(true)
    try {
      await loadAnchorFilterDocuments()
      setAnchorFilterDocumentsLoaded(true)
    } finally {
      setAnchorFilterLoading(false)
    }
  }

  const loadAnchorFilterChunks = async (documentId) => {
    if (!documentId) {
      setAnchorFilterChunks([])
      return
    }
    const query = queryString({
      document_id: documentId,
      active_only: true,
      limit: 500,
      offset: 0,
    })
    const payload = await requestJson(`/api/admin/corpus/chunks?${query}`)
    setAnchorFilterChunks(Array.isArray(payload) ? payload : [])
  }

  const loadAnchors = async (nextFilters = anchorFilters, page = anchorPage) => {
    setAnchorListLoading(true)
    try {
      const query = queryString({
        document_id: nextFilters.documentId || undefined,
        chunk_id: nextFilters.chunkId || undefined,
        keyword: nextFilters.keyword || undefined,
        active_only: true,
        limit: anchorPageSize + 1,
        offset: page * anchorPageSize,
      })
      const payload = await requestJson(`/api/admin/corpus/anchors${query ? `?${query}` : ''}`)
      const normalized = Array.isArray(payload) ? payload : []
      setAnchorHasNextPage(normalized.length > anchorPageSize)
      setAnchors(normalized.slice(0, anchorPageSize))
      setAnchorListLoaded(true)
    } finally {
      setAnchorListLoading(false)
    }
  }

  const loadAnchorNormalizationRuns = async () => {
    setAnchorNormalizationRunsLoading(true)
    try {
      const payload = await requestJson('/api/admin/corpus/anchors/normalization-runs?limit=20&offset=0')
      setAnchorNormalizationRuns(Array.isArray(payload) ? payload : [])
      setAnchorNormalizationRunsLoaded(true)
    } finally {
      setAnchorNormalizationRunsLoading(false)
    }
  }

  const loadMultiSourceAnchorRuns = async () => {
    setMultiSourceAnchorRunsLoading(true)
    try {
      const payload = await requestJson('/api/admin/corpus/anchors/multi-source/build-runs?limit=20&offset=0')
      setMultiSourceAnchorRuns(Array.isArray(payload) ? payload : [])
      setMultiSourceAnchorRunsLoaded(true)
    } finally {
      setMultiSourceAnchorRunsLoading(false)
    }
  }

  const handleAnchorFilterDocumentChange = async (documentId) => {
    setAnchorFilters((prev) => ({
      ...prev,
      documentId,
      chunkId: '',
    }))
    setAnchorFilterLoading(true)
    try {
      await loadAnchorFilterChunks(documentId)
    } finally {
      setAnchorFilterLoading(false)
    }
  }

  const applyAnchorFilters = async (nextFilters = anchorFilters) => {
    const initialPage = 0
    setAnchorPage(initialPage)
    await loadAnchors(nextFilters, initialPage)
  }

  const resetAnchorFilters = async () => {
    const cleared = {
      documentId: '',
      chunkId: '',
      keyword: '',
    }
    setAnchorFilters(cleared)
    setAnchorFilterChunks([])
    const initialPage = 0
    setAnchorPage(initialPage)
    await loadAnchors(cleared, initialPage)
  }

  const handleAnchorEvalSourceChange = async (nextSourceId) => {
    const source = sources.find((item) => item.sourceId === nextSourceId)
    setAnchorEvalForm((prev) => ({
      ...prev,
      sourceId: nextSourceId,
      productName: source?.productName || '',
      selectedDocumentIds: [],
      selectedChunkIds: [],
    }))
    setAnchorEvalLoadingScope(true)
    try {
      await loadAnchorEvalScopeDocuments(nextSourceId, source?.productName || '')
      setAnchorEvalScopeChunks([])
    } finally {
      setAnchorEvalLoadingScope(false)
    }
  }

  const handleAnchorEvalDocumentSelection = async (selectedDocumentIds) => {
    setAnchorEvalForm((prev) => ({
      ...prev,
      selectedDocumentIds,
      selectedChunkIds: [],
    }))
    setAnchorEvalLoadingScope(true)
    try {
      await loadAnchorEvalScopeChunks(selectedDocumentIds)
    } finally {
      setAnchorEvalLoadingScope(false)
    }
  }

  const selectAllAnchorEvalDocuments = async () => {
    const allDocumentIds = anchorEvalScopeDocuments.map((doc) => doc.documentId)
    await handleAnchorEvalDocumentSelection(allDocumentIds)
  }

  const selectAllAnchorEvalChunks = () => {
    setAnchorEvalForm((prev) => ({
      ...prev,
      selectedChunkIds: anchorEvalScopeChunks.map((chunk) => chunk.chunkId),
    }))
  }

  const applyDocumentFilters = async (nextFilters = filters) => {
    const initialPage = 0
    setDocumentPage(initialPage)
    await loadDocuments(nextFilters, initialPage)
  }

  useEffect(() => {
    Promise.all([
      loadSummary(),
      loadRuns(0),
      loadSources(),
      loadDocuments(filters, 0),
      loadMultiSourceAnchorRuns(),
    ]).catch((error) => notify(error.message, 'error'))
  }, [])

  useEffect(() => {
    if (!multiSourceAnchorRunsLoaded || !multiSourceAnchorBuildActive) {
      return undefined
    }
    const pollId = window.setInterval(() => {
      loadMultiSourceAnchorRuns().catch((error) => notify(error.message, 'error'))
    }, 15000)
    return () => window.clearInterval(pollId)
  }, [multiSourceAnchorRunsLoaded, multiSourceAnchorBuildActive, notify])

  const parseLines = (value) =>
    String(value || '')
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter((line) => line.length > 0)

  const normalizedSourceSearch = sourceSearch.trim().toLowerCase()
  const filteredSources = sources.filter((source) => {
    if (!normalizedSourceSearch) {
      return true
    }
    const haystack = `${source.sourceId || ''} ${source.productName || ''} ${source.baseUrl || ''}`.toLowerCase()
    return haystack.includes(normalizedSourceSearch)
  })

  const toggleSourceSelection = (sourceId) => {
    setSelectedSourceIds((prev) =>
      prev.includes(sourceId) ? prev.filter((id) => id !== sourceId) : [...prev, sourceId]
    )
  }

  const toggleAllVisibleSources = () => {
    const visibleSourceIds = filteredSources.map((source) => source.sourceId)
    const allSelected = visibleSourceIds.length > 0 && visibleSourceIds.every((id) => selectedSourceIds.includes(id))
    if (allSelected) {
      setSelectedSourceIds((prev) => prev.filter((id) => !visibleSourceIds.includes(id)))
      return
    }
    setSelectedSourceIds((prev) => Array.from(new Set([...prev, ...visibleSourceIds])))
  }

  const triggerPipeline = async (runType) => {
    setBusyRunType(runType)
    try {
      const endpoint = runType === 'full_ingest' ? '/api/admin/pipeline/full-ingest' : `/api/admin/pipeline/${runType}`
      await requestJson(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ domainId, sourceIds: selectedSourceIds }),
      })
      setRunPage(0)
      await Promise.all([loadSummary(), loadRuns(0)])
      notify('파이프라인 실행 요청을 접수했습니다.')
    } catch (error) {
      notify(error.message, 'error')
    } finally {
      setBusyRunType('')
    }
  }

  const submitSourceForm = async (event) => {
    event.preventDefault()
    try {
      await requestJson('/api/admin/corpus/sources', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          sourceId: sourceForm.sourceId.trim(),
          productName: sourceForm.productName.trim(),
          startUrls: parseLines(sourceForm.startUrlsText),
          allowPrefixes: parseLines(sourceForm.allowPrefixesText),
          denyUrlPatterns: parseLines(sourceForm.denyUrlPatternsText),
          enabled: sourceForm.enabled,
          requestDelaySeconds: Number(sourceForm.requestDelaySeconds),
          maxDepth: Number(sourceForm.maxDepth),
          domainId,
        }),
      })
      await Promise.all([loadSources(), loadSummary()])
      notify('Source saved.')
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const resetSourceForm = () => {
    setSourceForm({
      sourceId: '',
      productName: '',
      startUrlsText: '',
      allowPrefixesText: '',
      denyUrlPatternsText: '',
      enabled: true,
      requestDelaySeconds: 0.75,
      maxDepth: 4,
    })
  }

  const autoRegisterSourceFromUrl = async (event) => {
    event.preventDefault()
    try {
      await requestJson('/api/admin/corpus/sources/auto-register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url: quickSourceUrl.trim(), domainId }),
      })
      setQuickSourceUrl('')
      await Promise.all([loadSources(), loadSummary()])
      notify('URL 기반 source 자동 등록이 완료되었습니다.')
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const showDocumentDetail = async (documentId) => {
    try {
      const [doc, rawVsCleaned, boundaries] = await Promise.all([
        requestJson(`/api/admin/corpus/documents/${documentId}`),
        requestJson(`/api/admin/corpus/documents/${documentId}/preview/raw-vs-cleaned`),
        requestJson(`/api/admin/corpus/documents/${documentId}/preview/chunk-boundaries`),
      ])
      setModal({
        title: `문서 상세 · ${shortId(documentId)}`,
        body: (
          <div className="detail-grid detail-grid--single">
            <DetailCard label="문서 정보" value={`${doc.title || '-'}\n${doc.canonicalUrl || '-'}`} mono={false} />
            <DetailCard label="텍스트 길이" value={`raw ${(rawVsCleaned.rawText || '').length} / cleaned ${(rawVsCleaned.cleanedText || '').length}`} />
            <DetailCard
              label="청크 경계"
              value={(boundaries.boundaries || [])
                .slice(0, 14)
                .map((row) => `#${row.chunkIndexInDocument} ${row.sectionPathText}`)
                .join('\n')}
            />
          </div>
        ),
      })
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const createAnchorEvalRun = async (event) => {
    event.preventDefault()
    setAnchorEvalBusy(true)
    try {
      await requestJson('/api/admin/corpus/anchors/eval/runs', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          runName: anchorEvalForm.runName || null,
          productName: anchorEvalForm.productName || null,
          sourceId: anchorEvalForm.sourceId || null,
          documentIds: anchorEvalForm.selectedDocumentIds,
          chunkIds: anchorEvalForm.selectedChunkIds,
          sampleSize: Number(anchorEvalForm.sampleSize),
          candidateLimit: Number(anchorEvalForm.candidateLimit),
        }),
      })
      await loadAnchorEvalRuns()
      notify('Anchor Eval run created.')
    } catch (error) {
      notify(error.message, 'error')
    } finally {
      setAnchorEvalBusy(false)
    }
  }

  const openAnchorEvalRunDetail = async (runId) => {
    setAnchorEvalDetailBusy(true)
    try {
      const detail = await requestJson(`/api/admin/corpus/anchors/eval/runs/${runId}`)
      setModal({
        title: `Anchor Eval · ${detail.run.runName}`,
        body: (
          <div className="detail-grid detail-grid--single">
            <DetailCard label="Summary" value={JSON.stringify(detail.run.summaryJson || {}, null, 2)} />
            {(detail.samples || []).slice(0, 20).map((sample) => (
              <div key={sample.sampleId} className="detail-card">
                <div className="detail-item__label">{sample.chunkId}</div>
                <pre className="detail-item__value">{sample.chunkText?.slice(0, 500) || ''}</pre>
                <div className="token-badge-list">
                  {(sample.candidates || []).map((candidate) => (
                    <button
                      key={candidate.candidateId}
                      type="button"
                      className="button button--ghost"
                      onClick={async () => {
                        try {
                          await requestJson(`/api/admin/corpus/anchors/eval/runs/${runId}/labels`, {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({
                              candidateId: candidate.candidateId,
                              labelValue: 'valid',
                              confidence: 0.9,
                              note: 'quick-label',
                            }),
                          })
                          notify('Labeled as valid.')
                        } catch (error) {
                          notify(error.message, 'error')
                        }
                      }}
                    >
                      {candidate.canonicalForm} ({candidate.labelValue || 'unlabeled'})
                    </button>
                  ))}
                </div>
              </div>
            ))}
          </div>
        ),
      })
    } catch (error) {
      notify(error.message, 'error')
    } finally {
      setAnchorEvalDetailBusy(false)
    }
  }

  const createAnchorNormalizationDryRun = async () => {
    const confirmed = window.confirm('현재 DB의 전체 active Anchor를 대상으로 정규화 dry-run을 생성할까요? Anchors 필터와 페이지 제한은 적용하지 않습니다.')
    if (!confirmed) return
    setAnchorNormalizationBusy(true)
    try {
      const created = await requestJson('/api/admin/corpus/anchors/normalization-runs', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          activeOnly: true,
          createdBy: 'admin-ui',
        }),
      })
      await loadAnchorNormalizationRuns()
      notify(`Anchor 정규화 dry-run 생성: ${created.runName}`)
    } catch (error) {
      notify(error.message, 'error')
    } finally {
      setAnchorNormalizationBusy(false)
    }
  }

  const createMultiSourceAnchorBuildRun = async () => {
    const confirmed = window.confirm('Build multi-source anchor relations for current active anchors? This writes only relation tables.')
    if (!confirmed) return
    setMultiSourceAnchorBusy(true)
    try {
      const created = await requestJson('/api/admin/corpus/anchors/multi-source/build-runs', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          relationTypes: ['canonical_alias', 'synthetic_query_cooccurrence', 'chunk_cooccurrence'],
          minRelationScore: 0.55,
          maxRelationsPerAnchor: 80,
          createdBy: 'admin-ui',
        }),
      })
      await loadMultiSourceAnchorRuns()
      notify(`Multi-source anchor build completed: ${created.relationCount ?? 0} relations`)
    } catch (error) {
      notify(error.message, 'error')
    } finally {
      setMultiSourceAnchorBusy(false)
    }
  }

  const reviewAnchorNormalizationRun = async (runId, action) => {
    const confirmed = window.confirm(
      action === 'approve'
        ? '검토 결정을 기준으로 이 정규화 작업을 승인하고 canonical 컬럼만 업데이트할까요?'
        : '이 정규화 작업을 반려할까요? 반려 후에는 이 run을 승인할 수 없습니다.'
    )
    if (!confirmed) return false
    setAnchorNormalizationBusy(true)
    try {
      await requestJson(`/api/admin/corpus/anchors/normalization-runs/${runId}/${action}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          reviewedBy: 'admin-ui',
          note: action === 'approve' ? 'Anchors UI에서 승인함' : 'Anchors UI에서 반려함',
        }),
      })
      await Promise.all([
        loadAnchorNormalizationRuns(),
        loadAnchors(anchorFilters, anchorPage),
      ])
      notify(action === 'approve' ? 'Anchor 정규화 작업을 승인했습니다.' : 'Anchor 정규화 작업을 반려했습니다.')
      return true
    } catch (error) {
      notify(error.message, 'error')
      return false
    } finally {
      setAnchorNormalizationBusy(false)
    }
  }

  const deleteAnchorNormalizationRun = async (run) => {
    const runId = run?.runId
    if (!runId) return
    const runName = run.runName || shortId(runId)
    const confirmed = window.confirm(
      `Anchor 정규화 이력 "${runName}"을 삭제할까요?\n후보 검토 기록과 dry-run 보고서만 삭제되며, 이미 승인되어 적용된 canonical 컬럼 값은 되돌리지 않습니다.`
    )
    if (!confirmed) return
    setAnchorNormalizationBusy(true)
    try {
      await requestJson(`/api/admin/corpus/anchors/normalization-runs/${runId}`, { method: 'DELETE' })
      if (modal?.kind === 'anchor-normalization-review' && modal.runId === runId) {
        setAnchorNormalizationDirty(false)
        setModal(null)
      }
      await loadAnchorNormalizationRuns()
      notify('Anchor 정규화 이력을 삭제했습니다.')
    } catch (error) {
      notify(error.message, 'error')
    } finally {
      setAnchorNormalizationBusy(false)
    }
  }

  const saveAnchorNormalizationCandidateReviews = async (runId, decisions, note, approveAfter = false) => {
    setAnchorNormalizationBusy(true)
    try {
      let detail = await requestJson(`/api/admin/corpus/anchors/normalization-runs/${runId}/candidate-reviews`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          reviewedBy: 'admin-ui',
          note: note || 'Anchors UI에서 후보 검토 결정을 저장함',
          decisions,
        }),
      })
      if (approveAfter) {
        const confirmed = window.confirm('저장한 검토 결정을 기준으로 정규화 작업을 승인하고 적용할까요?')
        if (!confirmed) {
          showAnchorNormalizationRunDetail(detail)
          return
        }
        await requestJson(`/api/admin/corpus/anchors/normalization-runs/${runId}/approve`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            reviewedBy: 'admin-ui',
            note: note || '후보 검토 후 Anchors UI에서 승인함',
          }),
        })
        setAnchorNormalizationDirty(false)
        setModal(null)
        notify('Anchor 정규화 작업을 승인했습니다.')
      } else {
        detail = await requestJson(`/api/admin/corpus/anchors/normalization-runs/${runId}`)
        showAnchorNormalizationRunDetail(detail)
        notify('Anchor 정규화 검토 결정을 저장했습니다.')
      }
      await Promise.all([
        loadAnchorNormalizationRuns(),
        loadAnchors(anchorFilters, anchorPage),
      ])
    } catch (error) {
      notify(error.message, 'error')
    } finally {
      setAnchorNormalizationBusy(false)
    }
  }

  const rejectAnchorNormalizationRunFromDetail = async (runId) => {
    const rejected = await reviewAnchorNormalizationRun(runId, 'reject')
    if (rejected) {
      setAnchorNormalizationDirty(false)
      setModal(null)
    }
  }

  const showAnchorNormalizationRunDetail = (detail) => {
    setAnchorNormalizationDirty(false)
    setModal({
      kind: 'anchor-normalization-review',
      runId: detail.run.runId,
      title: `Anchor 정규화 검토 · ${detail.run.runName}`,
      body: (
        <AnchorNormalizationReviewBody
          key={`${detail.run.runId}-${detail.run.updatedAt}-${detail.run.reviewApprovedCount}-${detail.run.reviewSkippedCount}-${detail.run.reviewPendingCount}`}
          detail={detail}
          onSaveReviews={(runId, decisions, note) => saveAnchorNormalizationCandidateReviews(runId, decisions, note, false)}
          onSaveAndApprove={(runId, decisions, note) => saveAnchorNormalizationCandidateReviews(runId, decisions, note, true)}
          onRejectRun={rejectAnchorNormalizationRunFromDetail}
          onDirtyChange={setAnchorNormalizationDirty}
        />
      ),
    })
  }

  const closeModal = () => {
    if (modal?.kind === 'anchor-normalization-review' && anchorNormalizationDirty) {
      const confirmed = window.confirm('저장하지 않은 검토 결정이 있습니다. 저장하지 않고 닫을까요?')
      if (!confirmed) return
    }
    setAnchorNormalizationDirty(false)
    setModal(null)
  }

  const openAnchorNormalizationRunDetail = async (runId) => {
    setAnchorNormalizationDetailBusy(true)
    try {
      const detail = await requestJson(`/api/admin/corpus/anchors/normalization-runs/${runId}`)
      showAnchorNormalizationRunDetail(detail)
    } catch (error) {
      notify(error.message, 'error')
    } finally {
      setAnchorNormalizationDetailBusy(false)
    }
  }

  const cards = [
    { label: '문서 소스', value: summary?.sourceCount ?? 0 },
    { label: '활성 문서', value: summary?.activeDocumentCount ?? 0 },
    { label: '활성 청크', value: summary?.activeChunkCount ?? 0 },
    { label: '용어 수', value: summary?.glossaryTermCount ?? 0 },
    { label: '중복 URL 스킵', value: summary?.duplicateUrlSkippedCount ?? 0, meta: '최근 30일' },
    { label: '동일 hash 스킵', value: summary?.sameHashSkippedCount ?? 0, meta: '최근 30일' },
    { label: '변경 없음 스킵', value: summary?.unchangedSkippedCount ?? 0, meta: '최근 30일' },
    { label: '최근 성공', value: summary?.recentRunSuccessCount ?? 0, meta: '최근 7일' },
    { label: '최근 실패', value: summary?.recentRunFailureCount ?? 0, meta: '최근 7일' },
  ]

  return (
    <>
      <section className="summary-grid">
        {cards.map((card) => (
          <article key={card.label} className="summary-card">
            <div className="summary-card__label">{card.label}</div>
            <div className="summary-card__value">{card.value}</div>
            <div className="summary-card__meta">{card.meta || ''}</div>
          </article>
        ))}
      </section>

      <section className="panel">
        <div className="table-title">실행 제어</div>
        <p className="panel-subtitle">단계별 또는 전체 파이프라인 실행을 요청할 수 있습니다.</p>
        <div className="filter-bar filter-bar--stack">
          <div className="form-grid form-grid--3">
            <label className="filter-field">Run Sources Search
              <input
                value={sourceSearch}
                onChange={(event) => setSourceSearch(event.target.value)}
                placeholder="sourceId / productName / baseUrl"
              />
              <span className="field-hint">실행 대상 Source 목록을 검색해서 빠르게 고를 수 있습니다.</span>
            </label>
            <div className="filter-field">Selected Scope
              <div className="state-note">
                {selectedSourceIds.length === 0
                  ? '현재 선택 없음: 실행 시 enabled source 전체 대상'
                  : `${selectedSourceIds.length}개 source 선택됨`}
              </div>
              <span className="field-hint">선택이 없으면 백엔드 기본 정책으로 전체 활성 소스를 사용합니다.</span>
            </div>
            <div className="filter-field">Quick Action
              <div className="form-actions">
                <button type="button" className="button" onClick={toggleAllVisibleSources}>
                  {filteredSources.length > 0 && filteredSources.every((s) => selectedSourceIds.includes(s.sourceId)) ? '보이는 소스 해제' : '보이는 소스 전체 선택'}
                </button>
                <button type="button" className="button" onClick={() => setSelectedSourceIds([])}>선택 초기화</button>
              </div>
            </div>
          </div>

          <div className="source-pick-grid">
            {filteredSources.map((source) => {
              const isSelected = selectedSourceIds.includes(source.sourceId)
              return (
                <button
                  key={source.sourceId}
                  type="button"
                  className={`source-pick-card ${isSelected ? 'is-selected' : ''}`}
                  onClick={() => toggleSourceSelection(source.sourceId)}
                >
                  <div className="source-pick-card__head">
                    <strong>{source.sourceId}</strong>
                    <StatusBadge value={source.enabled ? 'success' : 'cancelled'} label={source.enabled ? 'enabled' : 'disabled'} />
                  </div>
                  <div className="source-pick-card__product">{source.productName || '-'}</div>
                  <div className="source-pick-card__url">{source.baseUrl || '-'}</div>
                  <div className="source-pick-card__stats">
                    total {source.totalDocuments ?? 0} / active {source.activeDocuments ?? 0}
                  </div>
                </button>
              )
            })}
          </div>
        </div>
        <div className="toolbar pipeline-run-toolbar">
          <button type="button" className="button button--primary" disabled={busyRunType === 'collect'} onClick={() => triggerPipeline('collect')}>문서 수집</button>
          <button type="button" className="button button--primary" disabled={busyRunType === 'normalize'} onClick={() => triggerPipeline('normalize')}>문서 정제</button>
          <button type="button" className="button button--primary" disabled={busyRunType === 'chunk'} onClick={() => triggerPipeline('chunk')}>청킹</button>
          <button type="button" className="button button--primary" disabled={busyRunType === 'glossary'} onClick={() => triggerPipeline('glossary')}>용어 추출</button>
          <button type="button" className="button button--success" disabled={busyRunType === 'full_ingest'} onClick={() => triggerPipeline('full_ingest')}>전체 실행</button>
        </div>
      </section>

      <section className="table-shell">
        <div className="table-header">
          <div className="table-title">Source Registration</div>
        </div>
        <form className="filter-bar" onSubmit={autoRegisterSourceFromUrl}>
          <label className="filter-field" style={{ flex: 1 }}>Quick URL Auto Register
            <input
              value={quickSourceUrl}
              onChange={(event) => setQuickSourceUrl(event.target.value)}
              placeholder="https://docs.spring.io/spring-framework/reference/integration/rest-clients.html"
              required
            />
            <span className="field-hint">URL 한 개를 넣으면 sourceId/product/allowPrefix/denyPattern 기본값을 자동 추론해 등록합니다.</span>
          </label>
          <div className="filter-field filter-field--small">
            <button type="submit" className="button button--primary">Auto Register</button>
          </div>
        </form>
        <form className="filter-bar filter-bar--stack" onSubmit={submitSourceForm}>
          <div className="form-grid form-grid--2">
            <label className="filter-field">sourceId
              <input value={sourceForm.sourceId} onChange={(event) => setSourceForm((prev) => ({ ...prev, sourceId: event.target.value }))} required />
              <span className="field-hint">소스 고유 ID입니다. 재사용 가능한 slug 형식 권장. 예: `spring-security-docs`</span>
            </label>
            <label className="filter-field">productName
              <input value={sourceForm.productName} onChange={(event) => setSourceForm((prev) => ({ ...prev, productName: event.target.value }))} required />
              <span className="field-hint">문서 군집명입니다. 문서/청크 조회와 통계에서 제품 이름으로 사용됩니다.</span>
            </label>
            <label className="filter-field">requestDelaySeconds
              <input type="number" step="0.1" min="0" value={sourceForm.requestDelaySeconds} onChange={(event) => setSourceForm((prev) => ({ ...prev, requestDelaySeconds: event.target.value }))} />
              <span className="field-hint">페이지 요청 사이 대기 시간(초)입니다. 차단 회피 및 서버 부하 제어에 사용됩니다.</span>
            </label>
            <label className="filter-field">maxDepth
              <input type="number" min="1" value={sourceForm.maxDepth} onChange={(event) => setSourceForm((prev) => ({ ...prev, maxDepth: event.target.value }))} />
              <span className="field-hint">시작 URL에서 링크를 몇 단계까지 추적할지 정하는 탐색 깊이입니다.</span>
            </label>
            <label className="filter-field">enabled
              <select value={sourceForm.enabled ? 'true' : 'false'} onChange={(event) => setSourceForm((prev) => ({ ...prev, enabled: event.target.value === 'true' }))}>
                <option value="true">true</option>
                <option value="false">false</option>
              </select>
              <span className="field-hint">collect/full_ingest 기본 실행 시 포함할지 여부입니다.</span>
            </label>
            <label className="filter-field">startUrls (line-separated)
              <textarea rows={4} value={sourceForm.startUrlsText} onChange={(event) => setSourceForm((prev) => ({ ...prev, startUrlsText: event.target.value }))} required />
              <span className="field-hint">크롤러 시작점 URL 목록입니다. 한 줄에 하나씩 입력합니다.</span>
            </label>
            <label className="filter-field">allowPrefixes (line-separated)
              <textarea rows={4} value={sourceForm.allowPrefixesText} onChange={(event) => setSourceForm((prev) => ({ ...prev, allowPrefixesText: event.target.value }))} required />
              <span className="field-hint">허용 URL prefix 범위입니다. 해당 prefix 외 링크는 수집에서 제외됩니다.</span>
            </label>
            <label className="filter-field">denyUrlPatterns (line-separated)
              <textarea rows={4} value={sourceForm.denyUrlPatternsText} onChange={(event) => setSourceForm((prev) => ({ ...prev, denyUrlPatternsText: event.target.value }))} />
              <span className="field-hint">제외 URL 정규식 패턴입니다. 로그인/검색/태그/앵커 링크 등을 차단할 때 사용합니다.</span>
            </label>
          </div>
          <div className="form-actions">
            <button type="submit" className="button button--primary">Save Source</button>
            <button type="button" className="button" onClick={resetSourceForm}>Reset Form</button>
          </div>
        </form>
      </section>

      <section className="table-shell">
        <div className="table-header">
          <div className="table-title">파이프라인 실행 이력</div>
          <button type="button" className="button" onClick={() => Promise.all([loadSummary(), loadRuns(runPage)]).catch((error) => notify(error.message, 'error'))}>새로고침</button>
        </div>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr><th>실행 ID</th><th>실행 유형</th><th>시작</th><th>종료</th><th>상태</th><th>처리 건수</th><th>오류</th></tr>
            </thead>
            <tbody>
              {runs.map((run) => {
                const payload = typeof run.summaryJson === 'object' ? run.summaryJson : {}
                const processed = payload.document_count ?? payload.documents_discovered ?? payload.documents_persisted ?? 0
                return (
                  <tr key={run.runId}>
                    <td><IdBadge value={run.runId} /></td>
                    <td>{run.runType}</td>
                    <td>{fmtTime(run.startedAt)}</td>
                    <td>{fmtTime(run.finishedAt)}</td>
                    <td><StatusBadge value={run.runStatus} /></td>
                    <td>{processed}</td>
                    <td>{run.errorMessage ? <StatusBadge value="failed" label="있음" /> : <StatusBadge value="success" label="없음" />}</td>
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
            disabled={runPage === 0}
            onClick={() => {
              const nextPage = Math.max(0, runPage - 1)
              setRunPage(nextPage)
              loadRuns(nextPage).catch((error) => notify(error.message, 'error'))
            }}
          >이전</button>
          <div className="pagination__label">페이지 {runPage + 1}</div>
          <button
            type="button"
            className="button"
            disabled={!runHasNextPage}
            onClick={() => {
              const nextPage = runPage + 1
              setRunPage(nextPage)
              loadRuns(nextPage).catch((error) => notify(error.message, 'error'))
            }}
          >다음</button>
        </div>
      </section>

      <section className="table-shell">
        <div className="table-header"><div className="table-title">문서/청크 조회</div></div>
        <form className="filter-bar" onSubmit={(event) => { event.preventDefault(); applyDocumentFilters(filters).catch((error) => notify(error.message, 'error')) }}>
          <label className="filter-field">문서 소스
            <select value={filters.source_id} onChange={(event) => setFilters((prev) => ({ ...prev, source_id: event.target.value }))}>
              <option value="">전체</option>
              {sources.map((source) => <option key={source.sourceId} value={source.sourceId}>{source.sourceId} ({source.productName || '-'})</option>)}
            </select>
          </label>
          <label className="filter-field">버전
            <input value={filters.version_label} placeholder="예: 6.1" onChange={(event) => setFilters((prev) => ({ ...prev, version_label: event.target.value }))} />
          </label>
          <label className="filter-field">문서 ID
            <input value={filters.document_id} placeholder="document_id" onChange={(event) => setFilters((prev) => ({ ...prev, document_id: event.target.value }))} />
          </label>
          <label className="filter-field">검색
            <input value={filters.search} placeholder="title/url/chunk keyword" onChange={(event) => setFilters((prev) => ({ ...prev, search: event.target.value }))} />
          </label>
          <div className="filter-field filter-field--small"><button type="submit" className="button button--primary">조회</button></div>
        </form>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr><th>문서 ID</th><th>제목</th><th>소스</th><th>버전</th><th>섹션 수</th><th>청크 수</th><th>상세</th></tr>
            </thead>
            <tbody>
              {documents.map((doc) => (
                <tr key={doc.documentId}>
                  <td><IdBadge value={doc.documentId} plain /></td>
                  <td>{doc.title}</td>
                  <td>{doc.sourceId}</td>
                  <td>{doc.versionLabel || '-'}</td>
                  <td>{doc.sectionCount ?? 0}</td>
                  <td>{doc.chunkCount ?? 0}</td>
                  <td><button type="button" className="button button--ghost" onClick={() => showDocumentDetail(doc.documentId)}>상세 조회</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="pagination">
          <button
            type="button"
            className="button"
            disabled={documentPage === 0}
            onClick={() => {
              const nextPage = Math.max(0, documentPage - 1)
              setDocumentPage(nextPage)
              loadDocuments(filters, nextPage).catch((error) => notify(error.message, 'error'))
            }}
          >이전</button>
          <div className="pagination__label">페이지 {documentPage + 1}</div>
          <button
            type="button"
            className="button"
            disabled={!documentHasNextPage}
            onClick={() => {
              const nextPage = documentPage + 1
              setDocumentPage(nextPage)
              loadDocuments(filters, nextPage).catch((error) => notify(error.message, 'error'))
            }}
          >다음</button>
        </div>
      </section>

      <section className="table-shell multi-source-anchor-tracker">
        <div className="table-header">
          <div>
            <div className="table-title">Multi-source Anchor Tracker</div>
            <p className="multi-source-anchor-tracker__subtitle">
              Tracks the relation-index build used by RAG multi-source anchor hints. Polling runs only while a build is active.
            </p>
          </div>
          <div className="multi-source-anchor-tracker__actions">
            <button
              type="button"
              className="button"
              disabled={multiSourceAnchorRunsLoading}
              onClick={() => loadMultiSourceAnchorRuns().catch((error) => notify(error.message, 'error'))}
            >
              Refresh
            </button>
            <button
              type="button"
              className="button button--success"
              disabled={multiSourceAnchorBusy}
              onClick={() => createMultiSourceAnchorBuildRun()}
            >
              Build / Retry
            </button>
          </div>
        </div>
        <div className="multi-source-anchor-tracker__body">
          {!multiSourceAnchorRunsLoaded && (
            <div className="multi-source-anchor-tracker__empty">
              {multiSourceAnchorRunsLoading ? 'Loading latest multi-source anchor run...' : 'No tracker data loaded yet.'}
            </div>
          )}
          {multiSourceAnchorRunsLoaded && latestMultiSourceAnchorRun && (
            <>
              <div className="multi-source-anchor-tracker__summary">
                <article className="multi-source-anchor-tracker__metric">
                  <span>Latest status</span>
                  <strong><StatusBadge value={latestMultiSourceAnchorRun.status} /></strong>
                  <small>{fmtTime(latestMultiSourceAnchorRun.updatedAt || latestMultiSourceAnchorRun.createdAt)}</small>
                </article>
                <article className="multi-source-anchor-tracker__metric">
                  <span>Candidate anchors</span>
                  <strong>{formatInteger(latestMultiSourceAnchorRun.candidateAnchorCount)}</strong>
                  <small>active anchors considered</small>
                </article>
                <article className="multi-source-anchor-tracker__metric">
                  <span>Relations</span>
                  <strong>{formatInteger(latestMultiSourceAnchorRun.relationCount)}</strong>
                  <small>active runtime links</small>
                </article>
                <article className="multi-source-anchor-tracker__metric">
                  <span>Evidence</span>
                  <strong>{formatInteger(latestMultiSourceAnchorRun.evidenceCount)}</strong>
                  <small>supporting observations</small>
                </article>
              </div>
              <div className="multi-source-anchor-tracker__detail">
                <div>
                  <span>Run</span>
                  <strong>{latestMultiSourceAnchorRun.runName}</strong>
                  <code>{shortId(latestMultiSourceAnchorRun.runId)}</code>
                </div>
                <div>
                  <span>Versions</span>
                  <strong>{latestMultiSourceAnchorRun.relationVersion || '-'}</strong>
                  <small>{latestMultiSourceAnchorRun.mappingVersion || '-'} / {latestMultiSourceAnchorRun.normalizationVersion || '-'}</small>
                </div>
                <div>
                  <span>Runtime policy</span>
                  <strong>{latestMultiSourceAnchorRun.summaryJson?.topic_drift_policy || 'Expanded anchors stay low-priority runtime hints.'}</strong>
                </div>
              </div>
              {latestMultiSourceRelationRows.length > 0 && (
                <div className="multi-source-anchor-tracker__breakdown">
                  {latestMultiSourceRelationRows.map((row) => (
                    <span key={row.relationType}>
                      <strong>{row.label}</strong>
                      <em>{formatInteger(row.count)}</em>
                    </span>
                  ))}
                </div>
              )}
              {latestMultiSourceAnchorRun.errorMessage && (
                <div className="multi-source-anchor-tracker__error">
                  {latestMultiSourceAnchorRun.errorMessage}
                </div>
              )}
            </>
          )}
          {multiSourceAnchorRunsLoaded && !latestMultiSourceAnchorRun && (
            <div className="multi-source-anchor-tracker__empty">No multi-source anchor build runs yet.</div>
          )}
        </div>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr><th>run</th><th>status</th><th>version</th><th>relations</th><th>evidence</th><th>created</th><th>action</th></tr>
            </thead>
            <tbody>
              {!multiSourceAnchorRunsLoaded && (
                <tr>
                  <td colSpan={7}>{multiSourceAnchorRunsLoading ? 'Loading multi-source anchor build history...' : 'Build history is loaded on demand.'}</td>
                </tr>
              )}
              {multiSourceAnchorRunsLoaded && multiSourceAnchorRuns.map((run) => (
                <tr key={run.runId}>
                  <td>
                    <div>{run.runName}</div>
                    <div className="mono-text">{shortId(run.runId)}</div>
                  </td>
                  <td><StatusBadge value={run.status} /></td>
                  <td>
                    <div>{run.relationVersion || '-'}</div>
                    <div className="mono-text">min {Number(run.minRelationScore || 0).toFixed(2)}</div>
                  </td>
                  <td>{formatInteger(run.relationCount)}</td>
                  <td>{formatInteger(run.evidenceCount)}</td>
                  <td>{fmtTime(run.createdAt)}</td>
                  <td>
                    {String(run.status || '').toLowerCase() === 'failed' ? (
                      <button
                        type="button"
                        className="button button--ghost"
                        disabled={multiSourceAnchorBusy}
                        onClick={() => createMultiSourceAnchorBuildRun()}
                      >
                        Retry
                      </button>
                    ) : (
                      <span className="plain-badge">-</span>
                    )}
                  </td>
                </tr>
              ))}
              {multiSourceAnchorRunsLoaded && multiSourceAnchorRuns.length === 0 && (
                <tr>
                  <td colSpan={7}>No multi-source anchor build runs yet.</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      <section className="table-shell">
        <div className="table-header">
          <div className="table-title">Anchors</div>
          <button
            type="button"
            className="button button--primary"
            disabled={multiSourceAnchorBusy}
            onClick={() => createMultiSourceAnchorBuildRun()}
          >
            Multi-source Build
          </button>
          <button
            type="button"
            className="button button--primary"
            disabled={anchorNormalizationBusy}
            onClick={() => createAnchorNormalizationDryRun()}
          >
            Anchor 정규화 Dry-run
          </button>
          <button
            type="button"
            className="button"
            disabled={anchorListLoading}
            onClick={() => loadAnchors(anchorFilters, anchorPage).catch((error) => notify(error.message, 'error'))}
          >
            새로고침
          </button>
        </div>
        <form
          className="filter-bar filter-bar--stack"
          onSubmit={(event) => {
            event.preventDefault()
            applyAnchorFilters(anchorFilters).catch((error) => notify(error.message, 'error'))
          }}
        >
          <div className="form-grid form-grid--3">
            <label className="filter-field">
              Document Filter
              <SelectDropdown
                value={anchorFilters.documentId}
                options={anchorDocumentOptions}
                onOpen={() => {
                  ensureAnchorFilterDocumentsLoaded().catch((error) => notify(error.message, 'error'))
                }}
                onChange={(nextDocumentId) => {
                  handleAnchorFilterDocumentChange(nextDocumentId).catch((error) => notify(error.message, 'error'))
                }}
                placeholder="전체 문서"
                clearLabel="전체 문서"
                searchPlaceholder="문서 제목/ID 검색"
                emptyLabel="선택 가능한 문서가 없습니다."
              />
            </label>
            <label className="filter-field">
              Chunk Filter
              <SelectDropdown
                value={anchorFilters.chunkId}
                options={anchorChunkOptions}
                onChange={(nextChunkId) => setAnchorFilters((prev) => ({ ...prev, chunkId: nextChunkId }))}
                placeholder={anchorFilters.documentId ? '전체 청크' : '문서를 먼저 선택하세요'}
                clearLabel="전체 청크"
                searchPlaceholder="chunk id / section path 검색"
                emptyLabel="선택 가능한 청크가 없습니다."
                disabled={anchorFilterLoading || !anchorFilters.documentId}
              />
            </label>
            <label className="filter-field">
              Anchor Keyword
              <input
                value={anchorFilters.keyword}
                onChange={(event) => setAnchorFilters((prev) => ({ ...prev, keyword: event.target.value }))}
                placeholder="canonical anchor 검색"
              />
            </label>
          </div>
          <div className="form-actions">
            <button type="submit" className="button button--primary" disabled={anchorListLoading}>조회</button>
            <button type="button" className="button" onClick={() => resetAnchorFilters().catch((error) => notify(error.message, 'error'))}>
              초기화
            </button>
          </div>
        </form>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr><th>Anchor</th><th>Type</th><th>Confidence</th><th>Evidence</th><th>First Seen</th><th>Updated</th></tr>
            </thead>
            <tbody>
              {!anchorListLoaded && (
                <tr>
                  <td colSpan={6}>Anchor list is lazy-loaded. Click refresh or run a filter search.</td>
                </tr>
              )}
              {anchorListLoaded && anchors.map((anchor) => (
                <tr key={anchor.termId}>
                  <td>
                    <div>{anchor.canonicalForm}</div>
                    <div className="mono-text">{anchor.normalizedForm || '-'}</div>
                    <div className="line-clamp mono-text">{shortId(anchor.termId)}</div>
                  </td>
                  <td>{anchor.termType || '-'}</td>
                  <td>{Number(anchor.sourceConfidence || 0).toFixed(2)}</td>
                  <td>{anchor.scopedEvidenceCount ?? 0} / {anchor.evidenceCount ?? 0}</td>
                  <td>
                    <div className="mono-text">{anchor.firstSeenDocumentId || '-'}</div>
                    <div className="mono-text">{anchor.firstSeenChunkId || '-'}</div>
                  </td>
                  <td>{fmtTime(anchor.updatedAt)}</td>
                </tr>
              ))}
              {anchorListLoaded && anchors.length === 0 && (
                <tr>
                  <td colSpan={6}>조회 결과가 없습니다.</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        <div className="pagination">
          <button
            type="button"
            className="button"
            disabled={!anchorListLoaded || anchorPage === 0}
            onClick={() => {
              const nextPage = Math.max(0, anchorPage - 1)
              setAnchorPage(nextPage)
              loadAnchors(anchorFilters, nextPage).catch((error) => notify(error.message, 'error'))
            }}
          >이전</button>
          <div className="pagination__label">페이지 {anchorPage + 1}</div>
          <button
            type="button"
            className="button"
            disabled={!anchorListLoaded || !anchorHasNextPage}
            onClick={() => {
              const nextPage = anchorPage + 1
              setAnchorPage(nextPage)
              loadAnchors(anchorFilters, nextPage).catch((error) => notify(error.message, 'error'))
            }}
          >다음</button>
        </div>
      </section>

      <section className="table-shell">
        <div className="table-header">
          <div className="table-title">Anchor 정규화 이력</div>
          <button type="button" className="button" disabled={anchorNormalizationRunsLoading} onClick={() => loadAnchorNormalizationRuns().catch((error) => notify(error.message, 'error'))}>새로고침</button>
        </div>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr><th>작업</th><th>상태</th><th>요약</th><th>적용</th><th>생성 시각</th><th>작업</th></tr>
            </thead>
            <tbody>
              {!anchorNormalizationRunsLoaded && (
                <tr>
                  <td colSpan={6}>{anchorNormalizationRunsLoading ? 'Anchor 정규화 이력을 불러오는 중...' : 'Anchor 정규화 이력은 필요할 때 새로고침합니다.'}</td>
                </tr>
              )}
              {anchorNormalizationRunsLoaded && anchorNormalizationRuns.map((run) => (
                <tr key={run.runId}>
                  <td>
                    <div>{run.runName}</div>
                    <div className="mono-text">{shortId(run.runId)}</div>
                  </td>
                  <td><StatusBadge value={run.status} label={anchorRunStatusLabel(run.status)} /></td>
                  <td>
                    변경 후보 {run.changedCount ?? 0}개 · 변경 없음 {run.unchangedCount ?? 0}개
                    <div className="mono-text">승인 {run.reviewApprovedCount ?? 0}개 · 건너뛰기 {run.reviewSkippedCount ?? 0}개 · 검토 대기 {run.reviewPendingCount ?? 0}개</div>
                    <div className="mono-text">충돌 {run.conflictCount ?? 0}개 · 무효 {run.invalidCount ?? 0}개</div>
                  </td>
                  <td>{run.appliedUpdateCount ?? 0}개</td>
                  <td>{fmtTime(run.createdAt)}</td>
                  <td>
                    <div className="form-actions">
                      <button type="button" className="button button--ghost" disabled={anchorNormalizationDetailBusy} onClick={() => openAnchorNormalizationRunDetail(run.runId)}>열기</button>
                      {run.status === 'pending_review' && (
                        <>
                          <button
                            type="button"
                            className="button button--primary"
                            disabled={anchorNormalizationBusy || (run.reviewPendingCount ?? 0) > 0}
                            onClick={() => reviewAnchorNormalizationRun(run.runId, 'approve')}
                            title={(run.reviewPendingCount ?? 0) > 0 ? '검토 대기 항목이 남아 있어 승인할 수 없습니다.' : ''}
                          >
                            작업 승인
                          </button>
                          <button
                            type="button"
                            className="button button--danger"
                            disabled={anchorNormalizationBusy}
                            onClick={() => reviewAnchorNormalizationRun(run.runId, 'reject')}
                          >
                            작업 반려
                          </button>
                        </>
                      )}
                      <button
                        type="button"
                        className="button button--danger-ghost"
                        disabled={anchorNormalizationBusy}
                        onClick={() => deleteAnchorNormalizationRun(run)}
                        title="이력과 후보 검토 기록만 삭제합니다. 이미 적용된 anchor 값은 되돌리지 않습니다."
                      >
                        이력 삭제
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
              {anchorNormalizationRunsLoaded && anchorNormalizationRuns.length === 0 && (
                <tr>
                  <td colSpan={6}>Anchor 정규화 이력이 없습니다.</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      <section className="table-shell">
        <div className="table-header">
          <div className="table-title">Anchor Eval</div>
          <button type="button" className="button" onClick={() => loadAnchorEvalRuns().catch((error) => notify(error.message, 'error'))}>새로고침</button>
        </div>
        <form className="filter-bar filter-bar--stack anchor-eval-shell" onSubmit={createAnchorEvalRun}>
          <div className="form-grid form-grid--3">
            <label className="filter-field">Run Name
              <input value={anchorEvalForm.runName} onChange={(event) => setAnchorEvalForm((prev) => ({ ...prev, runName: event.target.value }))} placeholder="optional" />
            </label>
            <label className="filter-field">Source
              <select value={anchorEvalForm.sourceId} onChange={(event) => handleAnchorEvalSourceChange(event.target.value).catch((error) => notify(error.message, 'error'))}>
                <option value="">전체 소스</option>
                {sources.map((source) => (
                  <option key={source.sourceId} value={source.sourceId}>{source.sourceId} ({source.productName || '-'})</option>
                ))}
              </select>
              <span className="field-hint">소스를 고르면 문서/청크 드롭다운이 자동 갱신됩니다.</span>
            </label>
            <label className="filter-field">Product Name
              <input value={anchorEvalForm.productName} onChange={(event) => setAnchorEvalForm((prev) => ({ ...prev, productName: event.target.value }))} placeholder="optional" />
            </label>
            <label className="filter-field">Sample Size
              <input type="number" min="1" max="200" value={anchorEvalForm.sampleSize} onChange={(event) => setAnchorEvalForm((prev) => ({ ...prev, sampleSize: event.target.value }))} />
              <span className="field-hint">평가 샘플로 뽑을 chunk 개수입니다. 값이 커질수록 평가 범위는 넓어지지만 실행 시간이 늘어납니다.</span>
            </label>
            <label className="filter-field">Candidate Limit
              <input type="number" min="1" max="50" value={anchorEvalForm.candidateLimit} onChange={(event) => setAnchorEvalForm((prev) => ({ ...prev, candidateLimit: event.target.value }))} />
              <span className="field-hint">각 샘플 chunk마다 검토할 anchor 후보 최대 개수입니다. 값이 커질수록 후보 다양성은 증가하지만 라벨링 비용이 커집니다.</span>
            </label>
          </div>
          <div className="anchor-eval-panel">
            <div className="anchor-eval-panel__title">문서/청크 범위 선택</div>
            <div className="form-grid form-grid--2">
              <label className="filter-field">Documents (multi-select)
                <div className="form-actions">
                  <button type="button" className="button button--ghost" disabled={anchorEvalLoadingScope || !anchorEvalForm.sourceId || anchorEvalScopeDocuments.length === 0} onClick={() => selectAllAnchorEvalDocuments().catch((error) => notify(error.message, 'error'))}>전체 문서 선택</button>
                </div>
                <select
                  multiple
                  size={8}
                  value={anchorEvalForm.selectedDocumentIds}
                  onChange={(event) => {
                    const next = Array.from(event.target.selectedOptions).map((option) => option.value)
                    handleAnchorEvalDocumentSelection(next).catch((error) => notify(error.message, 'error'))
                  }}
                  disabled={anchorEvalLoadingScope || !anchorEvalForm.sourceId}
                >
                  {anchorEvalScopeDocuments.map((doc) => (
                    <option key={doc.documentId} value={doc.documentId}>{doc.title || doc.documentId}</option>
                  ))}
                </select>
              </label>
              <label className="filter-field">Chunks (multi-select)
                <div className="form-actions">
                  <button type="button" className="button button--ghost" disabled={anchorEvalLoadingScope || anchorEvalScopeChunks.length === 0} onClick={selectAllAnchorEvalChunks}>전체 청크 선택</button>
                </div>
                <select
                  multiple
                  size={8}
                  value={anchorEvalForm.selectedChunkIds}
                  onChange={(event) => {
                    const next = Array.from(event.target.selectedOptions).map((option) => option.value)
                    setAnchorEvalForm((prev) => ({ ...prev, selectedChunkIds: next }))
                  }}
                  disabled={anchorEvalLoadingScope || anchorEvalForm.selectedDocumentIds.length === 0}
                >
                  {anchorEvalScopeChunks.map((chunk) => (
                    <option key={chunk.chunkId} value={chunk.chunkId}>{chunk.chunkId} · {(chunk.sectionPathText || '').slice(0, 80)}</option>
                  ))}
                </select>
              </label>
            </div>
            <div className="anchor-eval-chip-row">
              <span className="anchor-eval-chip">source: {selectedSourceForEval?.sourceId || 'all'}</span>
              <span className="anchor-eval-chip">docs: {anchorEvalForm.selectedDocumentIds.length}</span>
              <span className="anchor-eval-chip">chunks: {anchorEvalForm.selectedChunkIds.length}</span>
              {anchorEvalLoadingScope && <span className="anchor-eval-chip is-loading">불러오는 중...</span>}
            </div>
          </div>
          <div className="filter-field filter-field--small">
            <button type="submit" className="button button--primary" disabled={anchorEvalBusy}>평가 Run 생성</button>
          </div>
        </form>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr><th>run</th><th>scope</th><th>samples</th><th>metrics</th><th>created</th><th>detail</th></tr>
            </thead>
            <tbody>
              {!anchorEvalRunsLoaded && (
                <tr>
                  <td colSpan={6}>{anchorEvalRunsLoading ? 'Anchor 평가 실행을 불러오는 중...' : 'Anchor 평가 이력은 필요할 때 새로고침합니다.'}</td>
                </tr>
              )}
              {anchorEvalRunsLoaded && anchorEvalRuns.map((run) => (
                <tr key={run.runId}>
                  <td>{run.runName}</td>
                  <td>{run.productName || '-'} / {run.sourceId || '-'}</td>
                  <td>{run.sampleSize} x {run.candidateLimit}</td>
                  <td>{typeof run.summaryJson === 'object' ? JSON.stringify(run.summaryJson) : '-'}</td>
                  <td>{fmtTime(run.createdAt)}</td>
                  <td><button type="button" className="button button--ghost" disabled={anchorEvalDetailBusy} onClick={() => openAnchorEvalRunDetail(run.runId)}>열기</button></td>
                </tr>
              ))}
              {anchorEvalRunsLoaded && anchorEvalRuns.length === 0 && (
                <tr>
                  <td colSpan={6}>No Anchor Eval runs found.</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>
      <Modal data={modal} onClose={closeModal} />
    </>
  )
}
