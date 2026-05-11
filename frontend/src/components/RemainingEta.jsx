import { formatEtaDuration, formatEtaProgress, formatEtaRate, isEtaActiveStatus, isEtaTerminalStatus } from '../lib/eta.js'
import { statusTone } from '../lib/format.js'

export function RemainingEta({
  remainingSeconds,
  secondsPerUnit,
  completedCount,
  totalCount,
  unitLabel = 'item',
  status,
  compact = false,
}) {
  const etaText = formatEtaDuration(remainingSeconds)
  const progressText = formatEtaProgress(completedCount, totalCount)
  const rateText = formatEtaRate(secondsPerUnit, unitLabel)
  const active = isEtaActiveStatus(status)
  const terminal = isEtaTerminalStatus(status)
  const showEtaValue = etaText !== '-' && (active || terminal)
  const stateLabel = terminal ? 'Done' : (active ? 'ETA' : 'N/A')

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
