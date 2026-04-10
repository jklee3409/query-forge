export async function requestJson(url, options = {}) {
  const response = await fetch(url, options)
  const text = await response.text()
  let payload = null
  if (text) {
    try {
      payload = JSON.parse(text)
    } catch {
      payload = text
    }
  }
  if (!response.ok) {
    if (payload && typeof payload === 'object') {
      throw new Error(payload.detail || payload.error || payload.message || `요청 실패 (${response.status})`)
    }
    throw new Error(String(payload || `요청 실패 (${response.status})`))
  }
  return payload ?? {}
}

export function queryString(params) {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value == null || value === '') return
    search.append(key, value)
  })
  return search.toString()
}

export function toNumber(value) {
  if (value == null || value === '') return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}
