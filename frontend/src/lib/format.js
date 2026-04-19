const STATUS_CLASS = {
  queued: 'queued',
  planned: 'queued',
  running: 'running',
  completed: 'success',
  success: 'success',
  failed: 'failed',
  cancelled: 'cancelled',
  paused: 'cancelled',
  pause_requested: 'queued',
  cancel_requested: 'queued',
}

const KST_TIME_FORMATTER = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
})

export function statusTone(value) {
  const normalized = String(value ?? '').toLowerCase()
  return STATUS_CLASS[normalized] ?? 'cancelled'
}

export function fmtTime(value) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  const parts = KST_TIME_FORMATTER.formatToParts(date)
  const mapped = parts.reduce((acc, part) => {
    acc[part.type] = part.value
    return acc
  }, {})
  return `${mapped.year}-${mapped.month}-${mapped.day} ${mapped.hour}:${mapped.minute}`
}

export function shortId(value) {
  if (!value) return '-'
  const raw = String(value)
  if (raw.length <= 14) return raw
  return `${raw.slice(0, 8)}...${raw.slice(-4)}`
}
