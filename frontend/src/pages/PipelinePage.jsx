import { useEffect, useState } from 'react'
import { DetailCard, IdBadge, Modal, StatusBadge } from '../components/Common.jsx'
import { fmtTime, shortId } from '../lib/format.js'
import { queryString, requestJson } from '../lib/api.js'

export function PipelinePage({ notify }) {
  const runPageSize = 3
  const documentPageSize = 10

  const [summary, setSummary] = useState(null)
  const [runs, setRuns] = useState([])
  const [runPage, setRunPage] = useState(0)
  const [runHasNextPage, setRunHasNextPage] = useState(false)
  const [sources, setSources] = useState([])
  const [documents, setDocuments] = useState([])
  const [documentPage, setDocumentPage] = useState(0)
  const [documentHasNextPage, setDocumentHasNextPage] = useState(false)
  const [filters, setFilters] = useState({ source_id: '', version_label: '', document_id: '', search: '' })
  const [modal, setModal] = useState(null)
  const [busyRunType, setBusyRunType] = useState('')

  const loadSummary = async () => {
    setSummary(await requestJson('/api/admin/pipeline/dashboard'))
  }

  const loadRuns = async (page = runPage) => {
    const query = queryString({ limit: runPageSize + 1, offset: page * runPageSize })
    const payload = await requestJson(`/api/admin/pipeline/runs?${query}`)
    const normalized = Array.isArray(payload) ? payload : []
    setRunHasNextPage(normalized.length > runPageSize)
    setRuns(normalized.slice(0, runPageSize))
  }

  const loadSources = async () => {
    const payload = await requestJson('/api/admin/corpus/sources')
    setSources(Array.isArray(payload) ? payload : [])
  }

  const loadDocuments = async (nextFilters = filters, page = documentPage) => {
    const query = queryString({
      ...nextFilters,
      active_only: true,
      limit: documentPageSize + 1,
      offset: page * documentPageSize,
    })
    const payload = await requestJson(`/api/admin/corpus/documents${query ? `?${query}` : ''}`)
    const normalized = Array.isArray(payload) ? payload : []
    setDocumentHasNextPage(normalized.length > documentPageSize)
    setDocuments(normalized.slice(0, documentPageSize))
  }

  const applyDocumentFilters = async (nextFilters = filters) => {
    const initialPage = 0
    setDocumentPage(initialPage)
    await loadDocuments(nextFilters, initialPage)
  }

  useEffect(() => {
    Promise.all([loadSummary(), loadRuns(0), loadSources(), loadDocuments(filters, 0)]).catch((error) => notify(error.message, 'error'))
  }, [])

  const triggerPipeline = async (runType) => {
    setBusyRunType(runType)
    try {
      const endpoint = runType === 'full_ingest' ? '/api/admin/pipeline/full-ingest' : `/api/admin/pipeline/${runType}`
      await requestJson(endpoint, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })
      setRunPage(0)
      await Promise.all([loadSummary(), loadRuns(0)])
      notify('파이프라인 실행 요청을 접수했습니다.')
    } catch (error) {
      notify(error.message, 'error')
    } finally {
      setBusyRunType('')
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
        <div className="toolbar">
          <button type="button" className="button button--primary" disabled={busyRunType === 'collect'} onClick={() => triggerPipeline('collect')}>문서 수집</button>
          <button type="button" className="button button--primary" disabled={busyRunType === 'normalize'} onClick={() => triggerPipeline('normalize')}>문서 정제</button>
          <button type="button" className="button button--primary" disabled={busyRunType === 'chunk'} onClick={() => triggerPipeline('chunk')}>청킹</button>
          <button type="button" className="button button--primary" disabled={busyRunType === 'glossary'} onClick={() => triggerPipeline('glossary')}>용어 추출</button>
          <button type="button" className="button" disabled={busyRunType === 'full_ingest'} onClick={() => triggerPipeline('full_ingest')}>전체 실행</button>
        </div>
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
      <Modal data={modal} onClose={() => setModal(null)} />
    </>
  )
}
