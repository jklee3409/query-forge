import { useEffect, useState } from 'react'
import { DetailCard, IdBadge, Modal, StatusBadge } from '../components/Common.jsx'
import { LlmJobsTable } from '../components/LlmJobsTable.jsx'
import { queryString, requestJson, toNumber } from '../lib/api.js'
import { fmtTime, shortId } from '../lib/format.js'
import { usePolling } from '../lib/hooks.js'

const DEFAULT_LLM_MODEL = 'gemini-2.5-flash'

export function SyntheticPage({ notify }) {
  const pageSize = 20
  const [methods, setMethods] = useState([])
  const [batches, setBatches] = useState([])
  const [sources, setSources] = useState([])
  const [sourceDocuments, setSourceDocuments] = useState([])
  const [queries, setQueries] = useState([])
  const [stats, setStats] = useState({ byMethod: [], byQueryType: [] })
  const [llmJobs, setLlmJobs] = useState([])
  const [queryPage, setQueryPage] = useState(0)
  const [hasNextPage, setHasNextPage] = useState(false)
  const [modal, setModal] = useState(null)

  const [runForm, setRunForm] = useState({
    methodCode: '',
    sourceId: '',
    sourceDocumentId: '',
    versionName: '',
    sourceDocumentVersion: '',
    limitChunks: '',
    avgQueriesPerChunk: '2.0',
    maxTotalQueries: '40',
    llmModel: DEFAULT_LLM_MODEL,
    llmRpm: '1000',
  })

  const [filterForm, setFilterForm] = useState({
    method_code: '',
    batch_id: '',
    query_type: '',
    gated: '',
  })

  const loadMethods = async () => {
    const rows = await requestJson('/api/admin/console/synthetic/methods')
    const normalized = Array.isArray(rows) ? rows : []
    setMethods(normalized)
    setRunForm((prev) => ({ ...prev, methodCode: prev.methodCode || normalized[0]?.methodCode || '' }))
  }

  const loadBatches = async () => {
    const rows = await requestJson('/api/admin/console/synthetic/batches?limit=50')
    setBatches(Array.isArray(rows) ? rows : [])
  }

  const loadSources = async () => {
    let sourceRows = await requestJson('/api/admin/corpus/sources')
    if (!Array.isArray(sourceRows) || sourceRows.length === 0) {
      const docs = await requestJson('/api/admin/corpus/documents?active_only=true&limit=300')
      const dedup = new Map()
      ;(Array.isArray(docs) ? docs : []).forEach((doc) => {
        if (!doc?.sourceId || dedup.has(doc.sourceId)) return
        dedup.set(doc.sourceId, { sourceId: doc.sourceId, productName: doc.productName || '-' })
      })
      sourceRows = Array.from(dedup.values())
    }
    setSources(Array.isArray(sourceRows) ? sourceRows : [])
  }

  const loadSourceDocuments = async (sourceId) => {
    if (!sourceId) {
      setSourceDocuments([])
      return
    }
    const query = queryString({ source_id: sourceId, active_only: true, limit: 200 })
    const rows = await requestJson(`/api/admin/corpus/documents?${query}`)
    setSourceDocuments(Array.isArray(rows) ? rows : [])
  }

  const loadStats = async () => {
    const query = queryString({ method_code: filterForm.method_code || null, batch_id: filterForm.batch_id || null })
    const payload = await requestJson(`/api/admin/console/synthetic/stats${query ? `?${query}` : ''}`)
    setStats({
      byMethod: Array.isArray(payload.byMethod) ? payload.byMethod : [],
      byQueryType: Array.isArray(payload.byQueryType) ? payload.byQueryType : [],
    })
  }

  const loadQueries = async (page = queryPage) => {
    const query = queryString({
      method_code: filterForm.method_code || null,
      batch_id: filterForm.batch_id || null,
      query_type: filterForm.query_type || null,
      gated: filterForm.gated || null,
      limit: pageSize + 1,
      offset: page * pageSize,
    })
    const rows = await requestJson(`/api/admin/console/synthetic/queries?${query}`)
    const normalized = Array.isArray(rows) ? rows : []
    setHasNextPage(normalized.length > pageSize)
    setQueries(normalized.slice(0, pageSize))
  }

  const loadLlmJobs = async () => {
    const rows = await requestJson('/api/admin/console/llm-jobs?limit=120')
    const filtered = (Array.isArray(rows) ? rows : []).filter((job) => job.jobType === 'GENERATE_SYNTHETIC_QUERY' || job.generationBatchId)
    setLlmJobs(filtered)
  }

  useEffect(() => {
    Promise.all([loadMethods(), loadBatches(), loadSources(), loadLlmJobs()])
      .then(() => Promise.all([loadQueries(0), loadStats()]))
      .catch((error) => notify(error.message, 'error'))
  }, [])

  useEffect(() => {
    loadSourceDocuments(runForm.sourceId).catch((error) => notify(error.message, 'error'))
  }, [runForm.sourceId])

  usePolling(true, 5000, async () => {
    try {
      await loadLlmJobs()
    } catch {
      // ignore polling errors
    }
  })

  const executeRun = async (event) => {
    event.preventDefault()
    try {
      await requestJson('/api/admin/console/synthetic/batches/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          methodCode: runForm.methodCode || null,
          sourceId: runForm.sourceId || null,
          sourceDocumentId: runForm.sourceDocumentId || null,
          versionName: runForm.versionName || null,
          sourceDocumentVersion: runForm.sourceDocumentVersion || null,
          limitChunks: toNumber(runForm.limitChunks),
          avgQueriesPerChunk: toNumber(runForm.avgQueriesPerChunk),
          maxTotalQueries: toNumber(runForm.maxTotalQueries),
          llmModel: runForm.llmModel || null,
          llmRpm: toNumber(runForm.llmRpm),
        }),
      })
      await Promise.all([loadBatches(), loadStats(), loadQueries(queryPage), loadLlmJobs()])
      notify('합성 질의 생성 배치가 등록되었습니다.')
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const openQueryDetail = async (queryId) => {
    try {
      const payload = await requestJson(`/api/admin/console/synthetic/queries/${queryId}`)
      setModal({
        title: `합성 질의 상세 · ${shortId(queryId)}`,
        body: (
          <div className="detail-grid detail-grid--single">
            <DetailCard label="질의 문장" value={payload.queryText || '-'} mono={false} />
            <DetailCard label="방식 / 유형" value={`${payload.generationMethod || '-'} / ${payload.queryType || '-'}`} />
            <DetailCard label="배치 / 언어" value={`${payload.generationBatchId || '-'} / ${payload.languageProfile || '-'}`} />
            <DetailCard label="source_chunk" value={JSON.stringify(payload.sourceChunk || {}, null, 2)} />
            <DetailCard label="source_links" value={JSON.stringify(payload.sourceLinks || {}, null, 2)} />
            <DetailCard label="raw_output" value={JSON.stringify(payload.rawOutput || {}, null, 2)} />
          </div>
        ),
      })
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const executeLlmAction = async (jobId, action) => {
    try {
      await requestJson(`/api/admin/console/llm-jobs/${jobId}/${action}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })
      await loadLlmJobs()
      notify(`JOB ${action} 요청을 전송했습니다.`)
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const openJobDetail = async (jobId) => {
    try {
      const [job, items] = await Promise.all([
        requestJson(`/api/admin/console/llm-jobs/${jobId}`),
        requestJson(`/api/admin/console/llm-jobs/${jobId}/items`),
      ])
      setModal({
        title: `LLM JOB 상세 · ${shortId(jobId)}`,
        body: (
          <div className="detail-grid detail-grid--single">
            <DetailCard label="커맨드 / 실험" value={`${job.commandName || '-'} / ${job.experimentName || '-'}`} />
            <DetailCard label="진행률" value={`${job.processedItems ?? 0} / ${job.totalItems ?? 0} (${job.progressPct == null ? '-' : `${Number(job.progressPct).toFixed(1)}%`})`} />
            <DetailCard label="job_items" value={JSON.stringify(items || [], null, 2)} />
          </div>
        ),
      })
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const byMethod = stats.byMethod || []
  const byType = stats.byQueryType || []
  const total = byMethod.reduce((sum, item) => sum + Number(item.count || 0), 0)

  return (
    <>
      <section className="table-shell">
        <div className="table-header"><div className="table-title">생성 방식 관리</div></div>
        <div className="table-wrap">
          <table className="data-table">
            <thead><tr><th>코드</th><th>방식명</th><th>설명</th><th>Prompt 버전</th><th>활성</th></tr></thead>
            <tbody>
              {methods.map((method) => (
                <tr key={method.methodCode}>
                  <td>{method.methodCode}</td><td>{method.methodName}</td><td>{method.description || '-'}</td><td>{method.promptTemplateVersion || '-'}</td>
                  <td>{method.active ? <StatusBadge value="success" label="활성" /> : <StatusBadge value="failed" label="비활성" />}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="panel">
        <div className="table-title">합성 질의 생성 실행</div>
        <form className="filter-bar" onSubmit={executeRun}>
          <label className="filter-field">생성 방식
            <select value={runForm.methodCode} onChange={(event) => setRunForm((prev) => ({ ...prev, methodCode: event.target.value }))}>
              <option value="">선택</option>{methods.map((method) => <option key={method.methodCode} value={method.methodCode}>{method.methodCode} - {method.methodName}</option>)}
            </select>
          </label>
          <label className="filter-field">원본 문서 소스
            <select value={runForm.sourceId} onChange={(event) => setRunForm((prev) => ({ ...prev, sourceId: event.target.value, sourceDocumentId: '' }))}>
              <option value="">전체 소스</option>{sources.map((source) => <option key={source.sourceId} value={source.sourceId}>{source.sourceId} ({source.productName || '-'})</option>)}
            </select>
          </label>
          <label className="filter-field">원본 문서(선택)
            <select value={runForm.sourceDocumentId} onChange={(event) => setRunForm((prev) => ({ ...prev, sourceDocumentId: event.target.value }))}>
              <option value="">전체 문서</option>{sourceDocuments.map((doc) => <option key={doc.documentId} value={doc.documentId}>{doc.documentId} | {doc.title}</option>)}
            </select>
          </label>
          <label className="filter-field">배치 버전명<input value={runForm.versionName} placeholder="예: c-main-v1" onChange={(event) => setRunForm((prev) => ({ ...prev, versionName: event.target.value }))} /></label>
          <label className="filter-field">소스 문서 버전<input value={runForm.sourceDocumentVersion} placeholder="예: 6.1" onChange={(event) => setRunForm((prev) => ({ ...prev, sourceDocumentVersion: event.target.value }))} /></label>
          <label className="filter-field filter-field--small">청크 수 제한<input type="number" min="1" value={runForm.limitChunks} onChange={(event) => setRunForm((prev) => ({ ...prev, limitChunks: event.target.value }))} /></label>
          <label className="filter-field filter-field--small">청크당 평균 질의<input type="number" min="0.2" max="20" step="0.1" value={runForm.avgQueriesPerChunk} onChange={(event) => setRunForm((prev) => ({ ...prev, avgQueriesPerChunk: event.target.value }))} /></label>
          <label className="filter-field filter-field--small">최대 생성 질의<input type="number" min="1" max="500" value={runForm.maxTotalQueries} onChange={(event) => setRunForm((prev) => ({ ...prev, maxTotalQueries: event.target.value }))} /></label>
          <label className="filter-field">Gemini 모델<input value={runForm.llmModel} onChange={(event) => setRunForm((prev) => ({ ...prev, llmModel: event.target.value }))} /></label>
          <label className="filter-field filter-field--small">LLM RPM<input type="number" min="1" max="1000" value={runForm.llmRpm} onChange={(event) => setRunForm((prev) => ({ ...prev, llmRpm: event.target.value }))} /></label>
          <div className="filter-field filter-field--small"><button type="submit" className="button button--primary">생성 실행</button></div>
        </form>
      </section>

      <section className="summary-grid">
        <article className="summary-card"><div className="summary-card__label">총 합성 질의</div><div className="summary-card__value">{total}</div></article>
        <article className="summary-card"><div className="summary-card__label">방식 수</div><div className="summary-card__value">{byMethod.length}</div><div className="summary-card__meta">{byMethod.map((item) => `${item.method_code}:${item.count}`).join(' / ') || '-'}</div></article>
        <article className="summary-card"><div className="summary-card__label">질의 유형 수</div><div className="summary-card__value">{byType.length}</div><div className="summary-card__meta">{byType.map((item) => `${item.query_type}:${item.count}`).join(' / ') || '-'}</div></article>
      </section>

      <section className="table-shell">
        <div className="table-header"><div className="table-title">생성 배치 이력</div><button type="button" className="button" onClick={() => Promise.all([loadBatches(), loadQueries(queryPage), loadStats()]).catch((error) => notify(error.message, 'error'))}>새로고침</button></div>
        <div className="table-wrap">
          <table className="data-table">
            <thead><tr><th>배치 ID</th><th>생성 방식</th><th>버전명</th><th>상태</th><th>시작</th><th>종료</th><th>생성 수</th></tr></thead>
            <tbody>
              {batches.map((batch) => (
                <tr key={batch.batchId}>
                  <td><IdBadge value={batch.batchId} /></td><td>{batch.methodCode}</td><td>{batch.versionName}</td><td><StatusBadge value={batch.status} /></td>
                  <td>{fmtTime(batch.startedAt)}</td><td>{fmtTime(batch.finishedAt)}</td><td>{batch.totalGeneratedCount ?? 0}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <LlmJobsTable jobs={llmJobs} onAction={executeLlmAction} onDetail={openJobDetail} />

      <section className="table-shell">
        <div className="table-header"><div className="table-title">합성 질의 조회</div></div>
        <form className="filter-bar" onSubmit={(event) => { event.preventDefault(); setQueryPage(0); loadQueries(0).then(loadStats).catch((error) => notify(error.message, 'error')) }}>
          <label className="filter-field">생성 방식
            <select value={filterForm.method_code} onChange={(event) => setFilterForm((prev) => ({ ...prev, method_code: event.target.value }))}>
              <option value="">전체</option>{methods.map((method) => <option key={method.methodCode} value={method.methodCode}>{method.methodCode}</option>)}
            </select>
          </label>
          <label className="filter-field">생성 배치
            <select value={filterForm.batch_id} onChange={(event) => setFilterForm((prev) => ({ ...prev, batch_id: event.target.value }))}>
              <option value="">전체</option>{batches.map((batch) => <option key={batch.batchId} value={batch.batchId}>{batch.versionName} ({batch.methodCode})</option>)}
            </select>
          </label>
          <label className="filter-field">질의 유형<input value={filterForm.query_type} placeholder="예: definition" onChange={(event) => setFilterForm((prev) => ({ ...prev, query_type: event.target.value }))} /></label>
          <label className="filter-field">게이팅 여부
            <select value={filterForm.gated} onChange={(event) => setFilterForm((prev) => ({ ...prev, gated: event.target.value }))}>
              <option value="">전체</option><option value="true">통과</option><option value="false">미통과</option>
            </select>
          </label>
          <div className="filter-field filter-field--small"><button type="submit" className="button button--primary">조회</button></div>
        </form>
        <div className="table-wrap">
          <table className="data-table">
            <thead><tr><th>질의 ID</th><th>질의 문장</th><th>유형</th><th>방식</th><th>배치</th><th>게이팅</th><th>상세</th></tr></thead>
            <tbody>
              {queries.map((query) => (
                <tr key={query.queryId}>
                  <td><IdBadge value={query.queryId} plain /></td><td>{query.queryText}</td><td>{query.queryType || '-'}</td><td>{query.generationMethod || '-'}</td>
                  <td>{query.generationBatchVersion || '-'}</td><td>{query.gated ? <StatusBadge value="success" label="통과" /> : <StatusBadge value="failed" label="미통과" />}</td>
                  <td><button type="button" className="button button--ghost" onClick={() => openQueryDetail(query.queryId)}>상세 조회</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="pagination">
          <button type="button" className="button" disabled={queryPage === 0} onClick={() => { const next = Math.max(0, queryPage - 1); setQueryPage(next); loadQueries(next).catch((error) => notify(error.message, 'error')) }}>이전</button>
          <div className="pagination__label">페이지 {queryPage + 1}</div>
          <button type="button" className="button" disabled={!hasNextPage} onClick={() => { const next = queryPage + 1; setQueryPage(next); loadQueries(next).catch((error) => notify(error.message, 'error')) }}>다음</button>
        </div>
      </section>

      <Modal data={modal} onClose={() => setModal(null)} />
    </>
  )
}
