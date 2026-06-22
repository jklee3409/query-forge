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
      const error = new Error(payload.detail || payload.error || payload.message || `요청 실패 (${response.status})`)
      error.status = response.status
      error.payload = payload
      error.errorCode = payload.errorCode || payload.code || null
      error.retryable = Boolean(payload.retryable)
      error.retryMessage = payload.retryMessage || null
      throw error
    }
    const error = new Error(String(payload || `요청 실패 (${response.status})`))
    error.status = response.status
    throw error
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

export function appendQuery(url, params) {
  const query = queryString(params)
  if (!query) return url
  return `${url}${url.includes('?') ? '&' : '?'}${query}`
}

export function fetchSyntheticMethods({ sourceId, sourceDocumentId, datasetId, domainId } = {}) {
  const query = queryString({
    source_id: sourceId || null,
    source_document_id: sourceDocumentId || null,
    dataset_id: datasetId || null,
    domain_id: domainId || null,
  })
  return requestJson(`/api/admin/console/synthetic/methods${query ? `?${query}` : ''}`)
}

export function toNumber(value) {
  if (value == null || value === '') return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}
