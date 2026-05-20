import { useCallback, useEffect, useMemo, useState } from 'react'
import { requestJson } from '../lib/api.js'

function shortHash(value) {
  if (!value) return 'n/a'
  return value.length > 12 ? value.slice(0, 12) : value
}

export function PromptStudioPage({ path, notify }) {
  const initialFamily = path.includes('/rag-rewrite') ? 'rewrite' : 'query_generation'
  const [family, setFamily] = useState(initialFamily)
  const [bindings, setBindings] = useState([])
  const [assets, setAssets] = useState([])
  const [selectedBindingKey, setSelectedBindingKey] = useState('')
  const [assetDetail, setAssetDetail] = useState(null)
  const [draft, setDraft] = useState('')
  const [revision, setRevision] = useState('')
  const [validation, setValidation] = useState(null)
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)

  const visibleBindings = useMemo(
    () => bindings.filter((binding) => binding.promptFamily === family),
    [bindings, family],
  )

  const selectedBinding = useMemo(
    () => bindings.find((binding) => binding.bindingKey === selectedBindingKey),
    [bindings, selectedBindingKey],
  )

  const loadCatalog = useCallback(async () => {
    setLoading(true)
    try {
      const [bindingRows, assetRows] = await Promise.all([
        requestJson('/api/admin/prompt-bindings'),
        requestJson('/api/admin/prompt-assets?active_only=false'),
      ])
      setBindings(bindingRows)
      setAssets(assetRows)
      const first = bindingRows.find((binding) => binding.promptFamily === family)
      setSelectedBindingKey((prev) => prev || first?.bindingKey || '')
    } catch (error) {
      notify(error.message, 'danger')
    } finally {
      setLoading(false)
    }
  }, [family, notify])

  useEffect(() => {
    loadCatalog()
  }, [loadCatalog])

  useEffect(() => {
    const first = bindings.find((binding) => binding.promptFamily === family)
    if (!first || bindings.some((binding) => binding.bindingKey === selectedBindingKey && binding.promptFamily === family)) return
    setSelectedBindingKey(first.bindingKey)
  }, [bindings, family, selectedBindingKey])

  useEffect(() => {
    if (!selectedBinding?.activePromptAssetId) {
      setAssetDetail(null)
      setDraft('')
      return
    }
    let cancelled = false
    requestJson(`/api/admin/prompt-assets/${selectedBinding.activePromptAssetId}`)
      .then((detail) => {
        if (cancelled) return
        setAssetDetail(detail)
        setDraft(detail.contentBody || '')
        setRevision('')
        setValidation(null)
      })
      .catch((error) => notify(error.message, 'danger'))
    return () => {
      cancelled = true
    }
  }, [selectedBinding?.activePromptAssetId, notify])

  const validateDraft = async () => {
    if (!selectedBinding) return
    try {
      const result = await requestJson(`/api/admin/prompt-bindings/${selectedBinding.bindingKey}/validate`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ contentBody: draft }),
      })
      setValidation(result)
    } catch (error) {
      notify(error.message, 'danger')
    }
  }

  const saveRevision = async () => {
    if (!assetDetail?.asset?.promptAssetId || !selectedBinding) return
    if (!revision.trim()) {
      notify('새 version 값을 입력하세요.', 'warning')
      return
    }
    setSaving(true)
    try {
      const created = await requestJson(`/api/admin/prompt-assets/${assetDetail.asset.promptAssetId}/revisions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          version: revision.trim(),
          contentBody: draft,
          updatedBy: 'admin-ui',
          metadata: { source: 'prompt-studio' },
        }),
      })
      await requestJson(`/api/admin/prompt-bindings/${selectedBinding.bindingKey}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          activePromptAssetId: created.asset.promptAssetId,
          updatedBy: 'admin-ui',
        }),
      })
      notify('프롬프트 revision을 활성화했습니다.')
      await loadCatalog()
      setSelectedBindingKey(selectedBinding.bindingKey)
    } catch (error) {
      notify(error.message, 'danger')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="prompt-studio">
      <section className="admin-card prompt-studio__sidebar">
        <div className="section-heading">
          <div className="section-heading__copy">
            <div className="section-heading__eyebrow">Global Prompts</div>
            <h2 className="section-heading__title">Prompt Studio</h2>
          </div>
        </div>
        <div className="segmented-control">
          <button
            type="button"
            className={family === 'query_generation' ? 'is-active' : ''}
            onClick={() => setFamily('query_generation')}
          >
            A-G
          </button>
          <button
            type="button"
            className={family === 'rewrite' ? 'is-active' : ''}
            onClick={() => setFamily('rewrite')}
          >
            Rewrite
          </button>
        </div>
        <div className="prompt-binding-list">
          {visibleBindings.map((binding) => (
            <button
              key={binding.bindingKey}
              type="button"
              className={`prompt-binding-list__item ${binding.bindingKey === selectedBindingKey ? 'is-active' : ''}`}
              onClick={() => setSelectedBindingKey(binding.bindingKey)}
            >
              <strong>{binding.bindingKey}</strong>
              <span>{binding.activePromptName} · {binding.activePromptVersion}</span>
              <small>{shortHash(binding.activeContentHash)}</small>
            </button>
          ))}
          {!loading && visibleBindings.length === 0 && (
            <div className="empty-state--panel">No prompt bindings</div>
          )}
        </div>
      </section>

      <section className="admin-card prompt-editor">
        <div className="section-heading">
          <div className="section-heading__copy">
            <div className="section-heading__eyebrow">{selectedBinding?.bindingKey || 'Binding'}</div>
            <h2 className="section-heading__title">
              {assetDetail?.asset?.promptName || '프롬프트 선택'}
            </h2>
            <p className="section-heading__description">
              Active version {assetDetail?.asset?.version || 'n/a'} · {assetDetail?.asset?.storageBackend || 'n/a'}
            </p>
          </div>
          <div className="section-heading__actions">
            <button type="button" className="button button--secondary" onClick={validateDraft}>Validate</button>
            <button type="button" className="button button--primary" onClick={saveRevision} disabled={saving || !draft}>
              {saving ? 'Saving' : 'Save Revision'}
            </button>
          </div>
        </div>
        <div className="prompt-editor__meta">
          <span>Assets {assets.filter((asset) => asset.promptFamily === family).length}</span>
          <span>Hash {shortHash(assetDetail?.asset?.contentHash)}</span>
        </div>
        <label className="prompt-editor__version">
          <span>New version</span>
          <input value={revision} onChange={(event) => setRevision(event.target.value)} placeholder="v2" />
        </label>
        <textarea
          className="prompt-editor__textarea"
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          spellCheck={false}
        />
        {validation && (
          <div className={`prompt-validation ${validation.valid ? 'is-valid' : 'is-invalid'}`}>
            <strong>{validation.valid ? 'Valid' : 'Invalid'}</strong>
            {[...(validation.errors || []), ...(validation.warnings || [])].map((item) => (
              <span key={item}>{item}</span>
            ))}
          </div>
        )}
      </section>
    </div>
  )
}
