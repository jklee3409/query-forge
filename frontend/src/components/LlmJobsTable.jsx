import { useEffect, useMemo, useState } from 'react'
import { IdBadge, StatusBadge } from './Common.jsx'
import { RemainingEta } from './RemainingEta.jsx'

function resolveEtaUnit(jobType) {
  if (jobType === 'RUN_LLM_SELF_EVAL') return 'query'
  if (jobType === 'RUN_RAG_TEST') return 'stage'
  if (jobType === 'GENERATE_SYNTHETIC_QUERY') return 'step'
  return 'item'
}

export function LlmJobsTable({ jobs, onAction, onDetail, loaded = true, loading = false, onLoad = null }) {
  const pageSize = 3
  const [page, setPage] = useState(0)
  const normalizedJobs = Array.isArray(jobs) ? jobs : []
  const totalPages = Math.max(1, Math.ceil(normalizedJobs.length / pageSize))
  const currentPage = Math.min(page, totalPages - 1)
  const pagedJobs = useMemo(
    () => normalizedJobs.slice(currentPage * pageSize, (currentPage + 1) * pageSize),
    [normalizedJobs, currentPage],
  )

  useEffect(() => {
    if (page !== currentPage) setPage(currentPage)
  }, [page, currentPage])

  if (!loaded && !loading) {
    return (
      <section className="table-shell">
        <div className="table-header">
          <div className="table-title">LLM 비동기 작업 상태</div>
          {onLoad && (
            <button type="button" className="button" onClick={onLoad}>
              불러오기
            </button>
          )}
        </div>
        <div className="empty-state">LLM 작업은 필요할 때 불러옵니다.</div>
      </section>
    )
  }

  return (
    <section className="table-shell">
      <div className="table-header">
        <div className="table-title">LLM 비동기 작업 상태</div>
        {onLoad && (
          <button type="button" className="button" disabled={loading} onClick={onLoad}>
            {loading ? '불러오는 중...' : (loaded ? '새로고침' : '불러오기')}
          </button>
        )}
      </div>
      <div className="table-wrap">
        <table className="data-table">
          <thead>
            <tr>
              <th>job_id</th>
              <th>유형</th>
              <th>상태</th>
              <th>진행률</th>
              <th>ETA</th>
              <th>재시도</th>
              <th>related_id</th>
              <th>작업</th>
            </tr>
          </thead>
          <tbody>
            {pagedJobs.map((job) => {
              const status = String(job.jobStatus || '').toLowerCase()
              const retryLimitLabel = Number(job.maxRetries) < 0 ? '∞' : (job.maxRetries ?? 0)
              return (
                <tr key={job.jobId}>
                  <td><IdBadge value={job.jobId} /></td>
                  <td>{job.jobType || '-'}</td>
                  <td><StatusBadge value={job.jobStatus} /></td>
                  <td>{job.progressPct == null ? '-' : `${Number(job.progressPct).toFixed(1)}%`}</td>
                  <td>
                    <RemainingEta
                      remainingSeconds={job.estimatedRemainingSeconds}
                      secondsPerUnit={job.estimatedSecondsPerUnit}
                      completedCount={job.processedItems}
                      totalCount={job.totalItems}
                      unitLabel={resolveEtaUnit(job.jobType)}
                      status={job.jobStatus}
                      compact
                    />
                  </td>
                  <td>{job.retryCount ?? 0}/{retryLimitLabel}</td>
                  <td><IdBadge value={job.generationBatchId || job.gatingBatchId || job.ragTestRunId} /></td>
                  <td>
                    <div className="toolbar">
                      {(status === 'queued' || status === 'running') && <button type="button" className="button button--ghost" onClick={() => onAction(job.jobId, 'pause')}>일시정지</button>}
                      {status === 'paused' && <button type="button" className="button button--ghost" onClick={() => onAction(job.jobId, 'resume')}>재개</button>}
                      {(status === 'queued' || status === 'running' || status === 'paused') && <button type="button" className="button button--ghost" onClick={() => onAction(job.jobId, 'cancel')}>취소</button>}
                      {status === 'failed' && <button type="button" className="button button--ghost" onClick={() => onAction(job.jobId, 'retry')}>재시도</button>}
                      <button type="button" className="button button--ghost" onClick={() => onDetail(job.jobId)}>상세 조회</button>
                    </div>
                  </td>
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
          disabled={currentPage === 0}
          onClick={() => setPage((prev) => Math.max(0, prev - 1))}
        >이전</button>
        <div className="pagination__label">페이지 {currentPage + 1}</div>
        <button
          type="button"
          className="button"
          disabled={currentPage + 1 >= totalPages}
          onClick={() => setPage((prev) => Math.min(totalPages - 1, prev + 1))}
        >다음</button>
      </div>
    </section>
  )
}
