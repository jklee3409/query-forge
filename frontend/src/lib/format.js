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

export function statusTone(value) {
  const normalized = String(value ?? '').toLowerCase()
  return STATUS_CLASS[normalized] ?? 'cancelled'
}

export function fmtTime(value) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  const pad = (num) => String(num).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

export function shortId(value) {
  if (!value) return '-'
  const raw = String(value)
  if (raw.length <= 14) return raw
  return `${raw.slice(0, 8)}...${raw.slice(-4)}`
}
