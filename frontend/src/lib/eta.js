const ACTIVE_STATUSES = new Set(['planned', 'queued', 'running', 'pause_requested', 'paused', 'cancel_requested'])
const TERMINAL_STATUSES = new Set(['completed', 'failed', 'cancelled'])

function toFiniteNumber(value) {
  if (value == null) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

export function isEtaActiveStatus(status) {
  const normalized = String(status || '').toLowerCase()
  return ACTIVE_STATUSES.has(normalized)
}

export function isEtaTerminalStatus(status) {
  const normalized = String(status || '').toLowerCase()
  return TERMINAL_STATUSES.has(normalized)
}

export function formatEtaDuration(seconds) {
  const safeSeconds = toFiniteNumber(seconds)
  if (safeSeconds == null) return '-'
  const rounded = Math.max(0, Math.round(safeSeconds))
  const hours = Math.floor(rounded / 3600)
  const minutes = Math.floor((rounded % 3600) / 60)
  const secs = rounded % 60
  if (hours > 0) return `${hours}h ${String(minutes).padStart(2, '0')}m`
  return `${String(minutes).padStart(2, '0')}:${String(secs).padStart(2, '0')}`
}

export function formatEtaRate(secondsPerUnit, unitLabel = 'item') {
  const safeRate = toFiniteNumber(secondsPerUnit)
  if (safeRate == null || safeRate <= 0) return '-'
  return `${safeRate.toFixed(2)}s/${unitLabel}`
}

export function formatEtaProgress(completedCount, totalCount) {
  const completed = toFiniteNumber(completedCount)
  const total = toFiniteNumber(totalCount)
  if (completed == null && total == null) return '-'
  const left = completed == null ? '-' : String(Math.max(0, Math.round(completed)))
  const right = total == null ? '?' : String(Math.max(0, Math.round(total)))
  return `${left} / ${right}`
}
