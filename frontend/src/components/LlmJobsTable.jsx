import { IdBadge, StatusBadge } from './Common.jsx'

export function LlmJobsTable({ jobs, onAction, onDetail }) {
  return (
    <section className="table-shell">
      <div className="table-header">
        <div className="table-title">LLM 비동기 JOB 상태</div>
      </div>
      <div className="table-wrap">
        <table className="data-table">
          <thead>
            <tr>
              <th>job_id</th>
              <th>유형</th>
              <th>상태</th>
              <th>진행률</th>
              <th>재시도</th>
              <th>연결 대상</th>
              <th>제어</th>
            </tr>
          </thead>
          <tbody>
            {jobs.map((job) => {
              const status = String(job.jobStatus || '').toLowerCase()
              return (
                <tr key={job.jobId}>
                  <td><IdBadge value={job.jobId} /></td>
                  <td>{job.jobType || '-'}</td>
                  <td><StatusBadge value={job.jobStatus} /></td>
                  <td>{job.progressPct == null ? '-' : `${Number(job.progressPct).toFixed(1)}%`}</td>
                  <td>{job.retryCount ?? 0}/{job.maxRetries ?? 0}</td>
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
    </section>
  )
}
