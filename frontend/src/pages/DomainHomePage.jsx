import { useCallback, useEffect, useMemo, useState } from 'react'
import { requestJson } from '../lib/api.js'

const DEFAULT_DOMAIN_FORM = {
  domainKey: '',
  displayName: '',
  description: '',
  primaryLanguage: 'ko',
  sourceLanguage: 'en',
}

function formatNumber(value) {
  return Number(value || 0).toLocaleString()
}

function nodeStyle(index, count) {
  const angle = (index / Math.max(count, 1)) * Math.PI * 2
  const radiusX = 31
  const radiusY = 24
  return {
    left: `${50 + Math.cos(angle) * radiusX}%`,
    top: `${50 + Math.sin(angle) * radiusY}%`,
    '--float-delay': `${index * 0.4}s`,
  }
}

export function DomainHomePage({ navigate, notify }) {
  const [domains, setDomains] = useState([])
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [form, setForm] = useState(DEFAULT_DOMAIN_FORM)
  const [selectedDomainKey, setSelectedDomainKey] = useState('')
  const [domainDetail, setDomainDetail] = useState(null)
  const [sourceCatalog, setSourceCatalog] = useState([])
  const [sourcePanelLoading, setSourcePanelLoading] = useState(false)
  const [sourceMutation, setSourceMutation] = useState(false)
  const [attachSourceId, setAttachSourceId] = useState('')
  const [attachRole, setAttachRole] = useState('primary')

  const activeDomains = useMemo(
    () => domains.filter((domain) => domain.status !== 'archived'),
    [domains],
  )

  const loadDomains = useCallback(async () => {
    setLoading(true)
    try {
      setDomains(await requestJson('/api/admin/domains'))
    } catch (error) {
      notify(error.message, 'danger')
    } finally {
      setLoading(false)
    }
  }, [notify])

  useEffect(() => {
    loadDomains()
  }, [loadDomains])

  const loadSourceCatalog = useCallback(async () => {
    const payload = await requestJson('/api/admin/corpus/sources')
    const rows = Array.isArray(payload) ? payload : []
    setSourceCatalog(rows)
    return rows
  }, [])

  const loadDomainDetail = useCallback(
    async (domainKey) => {
      if (!domainKey) {
        setDomainDetail(null)
        setAttachSourceId('')
        return
      }
      setSourcePanelLoading(true)
      try {
        const [detail, sources] = await Promise.all([
          requestJson(`/api/admin/domains/${domainKey}`),
          loadSourceCatalog(),
        ])
        setDomainDetail(detail)
        const attachedSourceIds = new Set((detail.sources || []).map((source) => source.sourceId))
        const firstAvailableSource = sources.find((source) => !attachedSourceIds.has(source.sourceId))
        setAttachSourceId((prev) => {
          if (prev && sources.some((source) => source.sourceId === prev && !attachedSourceIds.has(source.sourceId))) {
            return prev
          }
          return firstAvailableSource?.sourceId || ''
        })
      } catch (error) {
        notify(error.message, 'danger')
      } finally {
        setSourcePanelLoading(false)
      }
    },
    [loadSourceCatalog, notify],
  )

  const selectDomainForSources = (domain) => {
    setSelectedDomainKey(domain.domainKey)
    loadDomainDetail(domain.domainKey)
  }

  const updateForm = (key, value) => {
    setForm((prev) => ({ ...prev, [key]: value }))
  }

  const createDomain = async (event) => {
    event.preventDefault()
    if (!form.domainKey.trim() || !form.displayName.trim()) {
      notify('도메인 key와 이름을 입력하세요.', 'warning')
      return
    }
    setSaving(true)
    try {
      const created = await requestJson('/api/admin/domains', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...form, createdBy: 'admin-ui' }),
      })
      const domainKey = created?.domain?.domainKey || form.domainKey
      setForm(DEFAULT_DOMAIN_FORM)
      await loadDomains()
      navigate(`/admin/domains/${domainKey}/pipeline`)
    } catch (error) {
      notify(error.message, 'danger')
    } finally {
      setSaving(false)
    }
  }

  const attachedSourceIds = useMemo(
    () => new Set((domainDetail?.sources || []).map((source) => source.sourceId)),
    [domainDetail],
  )

  const attachableSources = useMemo(
    () => sourceCatalog.filter((source) => !attachedSourceIds.has(source.sourceId)),
    [attachedSourceIds, sourceCatalog],
  )

  const attachSource = async (event) => {
    event.preventDefault()
    if (!selectedDomainKey || !attachSourceId) {
      notify('연결할 도메인과 source를 선택하세요.', 'warning')
      return
    }
    setSourceMutation(true)
    try {
      const detail = await requestJson(`/api/admin/domains/${selectedDomainKey}/sources`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          sourceId: attachSourceId,
          sourceRole: attachRole || 'primary',
          active: true,
        }),
      })
      setDomainDetail(detail)
      const [, refreshedSources] = await Promise.all([loadDomains(), loadSourceCatalog()])
      const refreshedAttachedIds = new Set((detail.sources || []).map((source) => source.sourceId))
      const nextSource = refreshedSources.find((source) => !refreshedAttachedIds.has(source.sourceId))
      setAttachSourceId(nextSource?.sourceId || '')
      notify('Source를 도메인에 연결했습니다.')
    } catch (error) {
      notify(error.message, 'danger')
    } finally {
      setSourceMutation(false)
    }
  }

  const detachSource = async (sourceId) => {
    if (!selectedDomainKey) return
    const confirmed = window.confirm(`${sourceId} source를 현재 도메인에서 분리할까요?`)
    if (!confirmed) return
    setSourceMutation(true)
    try {
      const detail = await requestJson(`/api/admin/domains/${selectedDomainKey}/sources/${sourceId}`, {
        method: 'DELETE',
      })
      setDomainDetail(detail)
      await Promise.all([loadDomains(), loadSourceCatalog()])
      notify('Source 연결을 해제했습니다.')
    } catch (error) {
      notify(error.message, 'danger')
    } finally {
      setSourceMutation(false)
    }
  }

  return (
    <div className="domain-home">
      <section className="domain-map" aria-label="기술 문서 도메인">
        <div className="domain-map__core">
          <span>QF</span>
          <strong>Domains</strong>
        </div>
        {loading && <div className="domain-map__loading">Loading</div>}
        {!loading && activeDomains.length === 0 && (
          <div className="domain-map__empty">No domains</div>
        )}
        {activeDomains.map((domain, index) => (
          <button
            key={domain.domainId}
            type="button"
            className="domain-node"
            style={nodeStyle(index, activeDomains.length)}
            onClick={() => navigate(`/admin/domains/${domain.domainKey}/pipeline`)}
          >
            <span className="domain-node__name">{domain.displayName}</span>
            <span className="domain-node__meta">
              {formatNumber(domain.sourceCount)} sources · {formatNumber(domain.activeChunkCount)} chunks
            </span>
            <span className="domain-node__stats">
              {formatNumber(domain.generationBatchCount)} batches · {formatNumber(domain.ragTestRunCount)} RAG
            </span>
          </button>
        ))}
      </section>

      <section className="domain-home__side">
        <div className="admin-card">
          <div className="section-heading">
            <div className="section-heading__copy">
              <div className="section-heading__eyebrow">Domain Registry</div>
              <h2 className="section-heading__title">기술 문서 도메인</h2>
              <p className="section-heading__description">
                도메인을 선택하면 해당 도메인의 수집, 합성 질의, 게이팅, RAG 테스트 작업 공간으로 이동합니다.
              </p>
            </div>
          </div>
          <div className="domain-list">
            {domains.map((domain) => (
              <div
                key={domain.domainId}
                className={`domain-list__item ${selectedDomainKey === domain.domainKey ? 'is-selected' : ''}`}
              >
                <button
                  type="button"
                  className="domain-list__main"
                  onClick={() => navigate(`/admin/domains/${domain.domainKey}/pipeline`)}
                >
                  <span>
                    <strong>{domain.displayName}</strong>
                    <small>{domain.domainKey}</small>
                  </span>
                  <span>{formatNumber(domain.activeDocumentCount)} docs</span>
                </button>
                <button
                  type="button"
                  className="button button--ghost button--compact"
                  onClick={() => selectDomainForSources(domain)}
                >
                  Sources
                </button>
              </div>
            ))}
          </div>
        </div>

        <div className="admin-card domain-source-manager">
          <div className="section-heading">
            <div className="section-heading__copy">
              <div className="section-heading__eyebrow">Source Membership</div>
              <h2 className="section-heading__title">도메인 Source</h2>
              <p className="section-heading__description">
                기존 corpus source를 선택한 도메인에 연결하거나 분리합니다.
              </p>
            </div>
          </div>
          {!selectedDomainKey && (
            <div className="empty-state empty-state--compact">
              <h3>도메인을 선택하세요</h3>
              <p>위 목록에서 Sources를 눌러 source 연결 상태를 관리합니다.</p>
            </div>
          )}
          {selectedDomainKey && sourcePanelLoading && (
            <div className="domain-source-manager__loading">Loading sources</div>
          )}
          {selectedDomainKey && !sourcePanelLoading && domainDetail && (
            <>
              <div className="domain-source-manager__summary">
                <strong>{domainDetail.domain?.displayName || selectedDomainKey}</strong>
                <span>{formatNumber(domainDetail.sources?.length)} linked sources</span>
              </div>
              <form className="domain-source-manager__form" onSubmit={attachSource}>
                <label>
                  <span>Available Source</span>
                  <select
                    value={attachSourceId}
                    onChange={(event) => setAttachSourceId(event.target.value)}
                    disabled={sourceMutation || attachableSources.length === 0}
                  >
                    <option value="">선택</option>
                    {attachableSources.map((source) => (
                      <option key={source.sourceId} value={source.sourceId}>
                        {source.productName || source.sourceId} · {source.sourceId}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  <span>Role</span>
                  <input
                    value={attachRole}
                    onChange={(event) => setAttachRole(event.target.value)}
                    placeholder="primary"
                  />
                </label>
                <button
                  type="submit"
                  className="button button--primary"
                  disabled={sourceMutation || !attachSourceId}
                >
                  Attach
                </button>
              </form>
              <div className="domain-source-list">
                {(domainDetail.sources || []).length === 0 && (
                  <div className="domain-source-list__empty">연결된 source가 없습니다.</div>
                )}
                {(domainDetail.sources || []).map((source) => (
                  <div key={source.sourceId} className="domain-source-list__row">
                    <span>
                      <strong>{source.productName || source.sourceId}</strong>
                      <small>{source.sourceId}</small>
                    </span>
                    <span>{formatNumber(source.activeDocumentCount)} docs</span>
                    <button
                      type="button"
                      className="button button--ghost button--compact"
                      disabled={sourceMutation}
                      onClick={() => detachSource(source.sourceId)}
                    >
                      Detach
                    </button>
                  </div>
                ))}
              </div>
            </>
          )}
        </div>

        <form className="admin-card domain-create-form" onSubmit={createDomain}>
          <div className="section-heading">
            <div className="section-heading__copy">
              <div className="section-heading__eyebrow">New Domain</div>
              <h2 className="section-heading__title">도메인 추가</h2>
            </div>
          </div>
          <label>
            <span>Key</span>
            <input
              value={form.domainKey}
              onChange={(event) => updateForm('domainKey', event.target.value)}
              placeholder="java"
            />
          </label>
          <label>
            <span>Name</span>
            <input
              value={form.displayName}
              onChange={(event) => updateForm('displayName', event.target.value)}
              placeholder="Java"
            />
          </label>
          <label>
            <span>Description</span>
            <textarea
              value={form.description}
              onChange={(event) => updateForm('description', event.target.value)}
              rows={3}
            />
          </label>
          <div className="domain-create-form__row">
            <label>
              <span>Query Lang</span>
              <input
                value={form.primaryLanguage}
                onChange={(event) => updateForm('primaryLanguage', event.target.value)}
              />
            </label>
            <label>
              <span>Source Lang</span>
              <input
                value={form.sourceLanguage}
                onChange={(event) => updateForm('sourceLanguage', event.target.value)}
              />
            </label>
          </div>
          <button type="submit" className="button button--primary" disabled={saving}>
            {saving ? 'Creating' : 'Create Domain'}
          </button>
        </form>
      </section>
    </div>
  )
}
