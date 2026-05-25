import { formatEtaDuration, formatEtaProgress, formatEtaRate, isEtaActiveStatus, isEtaTerminalStatus } from '../lib/eta.js'
import { fmtTime, statusTone } from '../lib/format.js'

function elapsedSecondsBetween(startedAt, finishedAt) {
  if (!startedAt || !finishedAt) return null
  const startMs = new Date(startedAt).getTime()
  const finishMs = new Date(finishedAt).getTime()
  if (!Number.isFinite(startMs) || !Number.isFinite(finishMs)) return null
  return Math.max(0, (finishMs - startMs) / 1000)
}

export function RemainingEta({
  remainingSeconds,
  secondsPerUnit,
  completedCount,
  totalCount,
  unitLabel = 'item',
  status,
  compact = false,
  startedAt = null,
  finishedAt = null,
  showCompletedElapsed = false,
}) {
  const etaText = formatEtaDuration(remainingSeconds)
  const progressText = formatEtaProgress(completedCount, totalCount)
  const rateText = formatEtaRate(secondsPerUnit, unitLabel)
  const active = isEtaActiveStatus(status)
  const terminal = isEtaTerminalStatus(status)
  const completed = String(status || '').toLowerCase() === 'completed'
  const showElapsed = showCompletedElapsed && completed
  const elapsedText = formatEtaDuration(elapsedSecondsBetween(startedAt, finishedAt))
  const showEtaValue = etaText !== '-' && (active || terminal)
  const stateLabel = terminal ? 'Done' : (active ? 'ETA' : 'N/A')

  if (showElapsed) {
    return (
      <div className={`remaining-eta ${compact ? 'remaining-eta--compact' : ''}`} data-status={statusTone(status)}>
        <div className="remaining-eta__head">
          <span className="remaining-eta__label">작업 시작 KST</span>
          <span className="remaining-eta__state">Done</span>
        </div>
        <div className="remaining-eta__timestamp">{fmtTime(startedAt)}</div>
        <div className="remaining-eta__value">{elapsedText}</div>
        <div className="remaining-eta__meta">
          <span>걸린 시간</span>
          <span>{progressText}</span>
        </div>
      </div>
    )
  }

  return (
    <div className={`remaining-eta ${compact ? 'remaining-eta--compact' : ''}`} data-status={statusTone(status)}>
      <div className="remaining-eta__head">
        <span className="remaining-eta__label">남은 예상</span>
        <span className="remaining-eta__state">{stateLabel}</span>
      </div>
      <div className="remaining-eta__value">{showEtaValue ? etaText : '-'}</div>
      <div className="remaining-eta__meta">
        <span>{progressText}</span>
        <span>{rateText}</span>
      </div>
    </div>
  )
}
