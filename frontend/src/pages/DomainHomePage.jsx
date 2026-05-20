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
              <button
                key={domain.domainId}
                type="button"
                className="domain-list__item"
                onClick={() => navigate(`/admin/domains/${domain.domainKey}/pipeline`)}
              >
                <span>
                  <strong>{domain.displayName}</strong>
                  <small>{domain.domainKey}</small>
                </span>
                <span>{formatNumber(domain.activeDocumentCount)} docs</span>
              </button>
            ))}
          </div>
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
