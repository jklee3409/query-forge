import { useEffect, useMemo, useState } from 'react'
import { DetailCard, IdBadge, Modal, StatusBadge } from '../components/Common.jsx'
import { SelectDropdown } from '../components/SelectDropdown.jsx'
import { fmtTime, shortId } from '../lib/format.js'
import { queryString, requestJson } from '../lib/api.js'

export function PipelinePage({ notify }) {
  const runPageSize = 3
  const documentPageSize = 10
  const anchorPageSize = 10

  const [summary, setSummary] = useState(null)
  const [runs, setRuns] = useState([])
  const [runPage, setRunPage] = useState(0)
  const [runHasNextPage, setRunHasNextPage] = useState(false)
  const [sources, setSources] = useState([])
  const [sourceSearch, setSourceSearch] = useState('')
  const [selectedSourceIds, setSelectedSourceIds] = useState([])
  const [documents, setDocuments] = useState([])
  const [documentPage, setDocumentPage] = useState(0)
  const [documentHasNextPage, setDocumentHasNextPage] = useState(false)
  const [filters, setFilters] = useState({ source_id: '', version_label: '', document_id: '', search: '' })
  const [modal, setModal] = useState(null)
  const [busyRunType, setBusyRunType] = useState('')
  const [anchorEvalRuns, setAnchorEvalRuns] = useState([])
  const [anchorEvalRunsLoaded, setAnchorEvalRunsLoaded] = useState(false)
  const [anchorEvalRunsLoading, setAnchorEvalRunsLoading] = useState(false)
  const [anchorEvalBusy, setAnchorEvalBusy] = useState(false)
  const [anchorEvalDetailBusy, setAnchorEvalDetailBusy] = useState(false)
  const [anchorEvalScopeDocuments, setAnchorEvalScopeDocuments] = useState([])
  const [anchorEvalScopeChunks, setAnchorEvalScopeChunks] = useState([])
  const [anchorEvalLoadingScope, setAnchorEvalLoadingScope] = useState(false)
  const [anchors, setAnchors] = useState([])
  const [anchorListLoaded, setAnchorListLoaded] = useState(false)
  const [anchorListLoading, setAnchorListLoading] = useState(false)
  const [anchorPage, setAnchorPage] = useState(0)
  const [anchorHasNextPage, setAnchorHasNextPage] = useState(false)
  const [anchorFilterDocuments, setAnchorFilterDocuments] = useState([])
  const [anchorFilterDocumentsLoaded, setAnchorFilterDocumentsLoaded] = useState(false)
  const [anchorFilterChunks, setAnchorFilterChunks] = useState([])
  const [anchorFilterLoading, setAnchorFilterLoading] = useState(false)
  const [anchorFilters, setAnchorFilters] = useState({
    documentId: '',
    chunkId: '',
    keyword: '',
  })
  const [anchorEvalForm, setAnchorEvalForm] = useState({
    runName: '',
    productName: '',
    sourceId: '',
    selectedDocumentIds: [],
    selectedChunkIds: [],
    sampleSize: 20,
    candidateLimit: 10,
  })
  const [sourceForm, setSourceForm] = useState({
    sourceId: '',
    productName: '',
    startUrlsText: '',
    allowPrefixesText: '',
    denyUrlPatternsText: '',
    enabled: true,
    requestDelaySeconds: 0.75,
    maxDepth: 4,
  })
  const [quickSourceUrl, setQuickSourceUrl] = useState('')

  const selectedSourceForEval = useMemo(
    () => sources.find((source) => source.sourceId === anchorEvalForm.sourceId) || null,
    [sources, anchorEvalForm.sourceId]
  )

  const anchorDocumentOptions = useMemo(
    () =>
      anchorFilterDocuments.map((document) => ({
        value: document.documentId,
        label: document.title || document.documentId,
        meta: shortId(document.documentId),
      })),
    [anchorFilterDocuments]
  )

  const anchorChunkOptions = useMemo(
    () =>
      anchorFilterChunks.map((chunk) => ({
        value: chunk.chunkId,
        label: `${chunk.chunkId} · #${chunk.chunkIndexInDocument}`,
        meta: (chunk.sectionPathText || '').slice(0, 120),
      })),
    [anchorFilterChunks]
  )

  const loadSummary = async () => {
    setSummary(await requestJson('/api/admin/pipeline/dashboard'))
  }

  const loadRuns = async (page = runPage) => {
    const query = queryString({ limit: runPageSize + 1, offset: page * runPageSize })
    const payload = await requestJson(`/api/admin/pipeline/runs?${query}`)
    const normalized = Array.isArray(payload) ? payload : []
    setRunHasNextPage(normalized.length > runPageSize)
    setRuns(normalized.slice(0, runPageSize))
  }

  const loadSources = async () => {
    const payload = await requestJson('/api/admin/corpus/sources')
    setSources(Array.isArray(payload) ? payload : [])
  }

  const loadDocuments = async (nextFilters = filters, page = documentPage) => {
    const query = queryString({
      ...nextFilters,
      active_only: true,
      limit: documentPageSize + 1,
      offset: page * documentPageSize,
    })
    const payload = await requestJson(`/api/admin/corpus/documents${query ? `?${query}` : ''}`)
    const normalized = Array.isArray(payload) ? payload : []
    setDocumentHasNextPage(normalized.length > documentPageSize)
    setDocuments(normalized.slice(0, documentPageSize))
  }

  const loadAnchorEvalRuns = async () => {
    setAnchorEvalRunsLoading(true)
    try {
      const payload = await requestJson('/api/admin/corpus/anchors/eval/runs?limit=20&offset=0')
      setAnchorEvalRuns(Array.isArray(payload) ? payload : [])
      setAnchorEvalRunsLoaded(true)
    } finally {
      setAnchorEvalRunsLoading(false)
    }
  }

  const loadAnchorEvalScopeDocuments = async (sourceId, productName) => {
    if (!sourceId && !productName) {
      setAnchorEvalScopeDocuments([])
      return
    }
    const query = queryString({
      source_id: sourceId || undefined,
      product_name: productName || undefined,
      active_only: true,
      limit: 500,
      offset: 0,
    })
    const payload = await requestJson(`/api/admin/corpus/documents?${query}`)
    setAnchorEvalScopeDocuments(Array.isArray(payload) ? payload : [])
  }

  const loadAnchorEvalScopeChunks = async (documentIds) => {
    if (!Array.isArray(documentIds) || documentIds.length === 0) {
      setAnchorEvalScopeChunks([])
      return
    }
    const responses = await Promise.all(
      documentIds.map((documentId) => {
        const query = queryString({ document_id: documentId, active_only: true, limit: 200, offset: 0 })
        return requestJson(`/api/admin/corpus/chunks?${query}`)
      })
    )
    const merged = responses.flatMap((rows) => (Array.isArray(rows) ? rows : []))
    setAnchorEvalScopeChunks(merged)
  }

  const loadAnchorFilterDocuments = async () => {
    const query = queryString({
      active_only: true,
      limit: 500,
      offset: 0,
    })
    const payload = await requestJson(`/api/admin/corpus/documents?${query}`)
    setAnchorFilterDocuments(Array.isArray(payload) ? payload : [])
  }

  const ensureAnchorFilterDocumentsLoaded = async () => {
    if (anchorFilterDocumentsLoaded) return
    setAnchorFilterLoading(true)
    try {
      await loadAnchorFilterDocuments()
      setAnchorFilterDocumentsLoaded(true)
    } finally {
      setAnchorFilterLoading(false)
    }
  }

  const loadAnchorFilterChunks = async (documentId) => {
    if (!documentId) {
      setAnchorFilterChunks([])
      return
    }
    const query = queryString({
      document_id: documentId,
      active_only: true,
      limit: 500,
      offset: 0,
    })
    const payload = await requestJson(`/api/admin/corpus/chunks?${query}`)
    setAnchorFilterChunks(Array.isArray(payload) ? payload : [])
  }

  const loadAnchors = async (nextFilters = anchorFilters, page = anchorPage) => {
    setAnchorListLoading(true)
    try {
      const query = queryString({
        document_id: nextFilters.documentId || undefined,
        chunk_id: nextFilters.chunkId || undefined,
        keyword: nextFilters.keyword || undefined,
        active_only: true,
        limit: anchorPageSize + 1,
        offset: page * anchorPageSize,
      })
      const payload = await requestJson(`/api/admin/corpus/anchors${query ? `?${query}` : ''}`)
      const normalized = Array.isArray(payload) ? payload : []
      setAnchorHasNextPage(normalized.length > anchorPageSize)
      setAnchors(normalized.slice(0, anchorPageSize))
      setAnchorListLoaded(true)
    } finally {
      setAnchorListLoading(false)
    }
  }

  const handleAnchorFilterDocumentChange = async (documentId) => {
    setAnchorFilters((prev) => ({
      ...prev,
      documentId,
      chunkId: '',
    }))
    setAnchorFilterLoading(true)
    try {
      await loadAnchorFilterChunks(documentId)
    } finally {
      setAnchorFilterLoading(false)
    }
  }

  const applyAnchorFilters = async (nextFilters = anchorFilters) => {
    const initialPage = 0
    setAnchorPage(initialPage)
    await loadAnchors(nextFilters, initialPage)
  }

  const resetAnchorFilters = async () => {
    const cleared = {
      documentId: '',
      chunkId: '',
      keyword: '',
    }
    setAnchorFilters(cleared)
    setAnchorFilterChunks([])
    const initialPage = 0
    setAnchorPage(initialPage)
    await loadAnchors(cleared, initialPage)
  }

  const handleAnchorEvalSourceChange = async (nextSourceId) => {
    const source = sources.find((item) => item.sourceId === nextSourceId)
    setAnchorEvalForm((prev) => ({
      ...prev,
      sourceId: nextSourceId,
      productName: source?.productName || '',
      selectedDocumentIds: [],
      selectedChunkIds: [],
    }))
    setAnchorEvalLoadingScope(true)
    try {
      await loadAnchorEvalScopeDocuments(nextSourceId, source?.productName || '')
      setAnchorEvalScopeChunks([])
    } finally {
      setAnchorEvalLoadingScope(false)
    }
  }

  const handleAnchorEvalDocumentSelection = async (selectedDocumentIds) => {
    setAnchorEvalForm((prev) => ({
      ...prev,
      selectedDocumentIds,
      selectedChunkIds: [],
    }))
    setAnchorEvalLoadingScope(true)
    try {
      await loadAnchorEvalScopeChunks(selectedDocumentIds)
    } finally {
      setAnchorEvalLoadingScope(false)
    }
  }

  const selectAllAnchorEvalDocuments = async () => {
    const allDocumentIds = anchorEvalScopeDocuments.map((doc) => doc.documentId)
    await handleAnchorEvalDocumentSelection(allDocumentIds)
  }

  const selectAllAnchorEvalChunks = () => {
    setAnchorEvalForm((prev) => ({
      ...prev,
      selectedChunkIds: anchorEvalScopeChunks.map((chunk) => chunk.chunkId),
    }))
  }

  const applyDocumentFilters = async (nextFilters = filters) => {
    const initialPage = 0
    setDocumentPage(initialPage)
    await loadDocuments(nextFilters, initialPage)
  }

  useEffect(() => {
    Promise.all([
      loadSummary(),
      loadRuns(0),
      loadSources(),
      loadDocuments(filters, 0),
    ]).catch((error) => notify(error.message, 'error'))
  }, [])

  const parseLines = (value) =>
    String(value || '')
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter((line) => line.length > 0)

  const normalizedSourceSearch = sourceSearch.trim().toLowerCase()
  const filteredSources = sources.filter((source) => {
    if (!normalizedSourceSearch) {
      return true
    }
    const haystack = `${source.sourceId || ''} ${source.productName || ''} ${source.baseUrl || ''}`.toLowerCase()
    return haystack.includes(normalizedSourceSearch)
  })

  const toggleSourceSelection = (sourceId) => {
    setSelectedSourceIds((prev) =>
      prev.includes(sourceId) ? prev.filter((id) => id !== sourceId) : [...prev, sourceId]
    )
  }

  const toggleAllVisibleSources = () => {
    const visibleSourceIds = filteredSources.map((source) => source.sourceId)
    const allSelected = visibleSourceIds.length > 0 && visibleSourceIds.every((id) => selectedSourceIds.includes(id))
    if (allSelected) {
      setSelectedSourceIds((prev) => prev.filter((id) => !visibleSourceIds.includes(id)))
      return
    }
    setSelectedSourceIds((prev) => Array.from(new Set([...prev, ...visibleSourceIds])))
  }

  const triggerPipeline = async (runType) => {
    setBusyRunType(runType)
    try {
      const endpoint = runType === 'full_ingest' ? '/api/admin/pipeline/full-ingest' : `/api/admin/pipeline/${runType}`
      await requestJson(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sourceIds: selectedSourceIds }),
      })
      setRunPage(0)
      await Promise.all([loadSummary(), loadRuns(0)])
      notify('파이프라인 실행 요청을 접수했습니다.')
    } catch (error) {
      notify(error.message, 'error')
    } finally {
      setBusyRunType('')
    }
  }

  const submitSourceForm = async (event) => {
    event.preventDefault()
    try {
      await requestJson('/api/admin/corpus/sources', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          sourceId: sourceForm.sourceId.trim(),
          productName: sourceForm.productName.trim(),
          startUrls: parseLines(sourceForm.startUrlsText),
          allowPrefixes: parseLines(sourceForm.allowPrefixesText),
          denyUrlPatterns: parseLines(sourceForm.denyUrlPatternsText),
          enabled: sourceForm.enabled,
          requestDelaySeconds: Number(sourceForm.requestDelaySeconds),
          maxDepth: Number(sourceForm.maxDepth),
        }),
      })
      await Promise.all([loadSources(), loadSummary()])
      notify('Source saved.')
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const resetSourceForm = () => {
    setSourceForm({
      sourceId: '',
      productName: '',
      startUrlsText: '',
      allowPrefixesText: '',
      denyUrlPatternsText: '',
      enabled: true,
      requestDelaySeconds: 0.75,
      maxDepth: 4,
    })
  }

  const autoRegisterSourceFromUrl = async (event) => {
    event.preventDefault()
    try {
      await requestJson('/api/admin/corpus/sources/auto-register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url: quickSourceUrl.trim() }),
      })
      setQuickSourceUrl('')
      await Promise.all([loadSources(), loadSummary()])
      notify('URL 기반 source 자동 등록이 완료되었습니다.')
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const showDocumentDetail = async (documentId) => {
    try {
      const [doc, rawVsCleaned, boundaries] = await Promise.all([
        requestJson(`/api/admin/corpus/documents/${documentId}`),
        requestJson(`/api/admin/corpus/documents/${documentId}/preview/raw-vs-cleaned`),
        requestJson(`/api/admin/corpus/documents/${documentId}/preview/chunk-boundaries`),
      ])
      setModal({
        title: `문서 상세 · ${shortId(documentId)}`,
        body: (
          <div className="detail-grid detail-grid--single">
            <DetailCard label="문서 정보" value={`${doc.title || '-'}\n${doc.canonicalUrl || '-'}`} mono={false} />
            <DetailCard label="텍스트 길이" value={`raw ${(rawVsCleaned.rawText || '').length} / cleaned ${(rawVsCleaned.cleanedText || '').length}`} />
            <DetailCard
              label="청크 경계"
              value={(boundaries.boundaries || [])
                .slice(0, 14)
                .map((row) => `#${row.chunkIndexInDocument} ${row.sectionPathText}`)
                .join('\n')}
            />
          </div>
        ),
      })
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const createAnchorEvalRun = async (event) => {
    event.preventDefault()
    setAnchorEvalBusy(true)
    try {
      await requestJson('/api/admin/corpus/anchors/eval/runs', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          runName: anchorEvalForm.runName || null,
          productName: anchorEvalForm.productName || null,
          sourceId: anchorEvalForm.sourceId || null,
          documentIds: anchorEvalForm.selectedDocumentIds,
          chunkIds: anchorEvalForm.selectedChunkIds,
          sampleSize: Number(anchorEvalForm.sampleSize),
          candidateLimit: Number(anchorEvalForm.candidateLimit),
        }),
      })
      await loadAnchorEvalRuns()
      notify('Anchor Eval run created.')
    } catch (error) {
      notify(error.message, 'error')
    } finally {
      setAnchorEvalBusy(false)
    }
  }

  const openAnchorEvalRunDetail = async (runId) => {
    setAnchorEvalDetailBusy(true)
    try {
      const detail = await requestJson(`/api/admin/corpus/anchors/eval/runs/${runId}`)
      setModal({
        title: `Anchor Eval · ${detail.run.runName}`,
        body: (
          <div className="detail-grid detail-grid--single">
            <DetailCard label="Summary" value={JSON.stringify(detail.run.summaryJson || {}, null, 2)} />
            {(detail.samples || []).slice(0, 20).map((sample) => (
              <div key={sample.sampleId} className="detail-card">
                <div className="detail-item__label">{sample.chunkId}</div>
                <pre className="detail-item__value">{sample.chunkText?.slice(0, 500) || ''}</pre>
                <div className="token-badge-list">
                  {(sample.candidates || []).map((candidate) => (
                    <button
                      key={candidate.candidateId}
                      type="button"
                      className="button button--ghost"
                      onClick={async () => {
                        try {
                          await requestJson(`/api/admin/corpus/anchors/eval/runs/${runId}/labels`, {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({
                              candidateId: candidate.candidateId,
                              labelValue: 'valid',
                              confidence: 0.9,
                              note: 'quick-label',
                            }),
                          })
                          notify('Labeled as valid.')
                        } catch (error) {
                          notify(error.message, 'error')
                        }
                      }}
                    >
                      {candidate.canonicalForm} ({candidate.labelValue || 'unlabeled'})
                    </button>
                  ))}
                </div>
              </div>
            ))}
          </div>
        ),
      })
    } catch (error) {
      notify(error.message, 'error')
    } finally {
      setAnchorEvalDetailBusy(false)
    }
  }

  const cards = [
    { label: '문서 소스', value: summary?.sourceCount ?? 0 },
    { label: '활성 문서', value: summary?.activeDocumentCount ?? 0 },
    { label: '활성 청크', value: summary?.activeChunkCount ?? 0 },
    { label: '용어 수', value: summary?.glossaryTermCount ?? 0 },
    { label: '중복 URL 스킵', value: summary?.duplicateUrlSkippedCount ?? 0, meta: '최근 30일' },
    { label: '동일 hash 스킵', value: summary?.sameHashSkippedCount ?? 0, meta: '최근 30일' },
    { label: '변경 없음 스킵', value: summary?.unchangedSkippedCount ?? 0, meta: '최근 30일' },
    { label: '최근 성공', value: summary?.recentRunSuccessCount ?? 0, meta: '최근 7일' },
    { label: '최근 실패', value: summary?.recentRunFailureCount ?? 0, meta: '최근 7일' },
  ]

  return (
    <>
      <section className="summary-grid">
        {cards.map((card) => (
          <article key={card.label} className="summary-card">
            <div className="summary-card__label">{card.label}</div>
            <div className="summary-card__value">{card.value}</div>
            <div className="summary-card__meta">{card.meta || ''}</div>
          </article>
        ))}
      </section>

      <section className="panel">
        <div className="table-title">실행 제어</div>
        <p className="panel-subtitle">단계별 또는 전체 파이프라인 실행을 요청할 수 있습니다.</p>
        <div className="filter-bar filter-bar--stack">
          <div className="form-grid form-grid--3">
            <label className="filter-field">Run Sources Search
              <input
                value={sourceSearch}
                onChange={(event) => setSourceSearch(event.target.value)}
                placeholder="sourceId / productName / baseUrl"
              />
              <span className="field-hint">실행 대상 Source 목록을 검색해서 빠르게 고를 수 있습니다.</span>
            </label>
            <div className="filter-field">Selected Scope
              <div className="state-note">
                {selectedSourceIds.length === 0
                  ? '현재 선택 없음: 실행 시 enabled source 전체 대상'
                  : `${selectedSourceIds.length}개 source 선택됨`}
              </div>
              <span className="field-hint">선택이 없으면 백엔드 기본 정책으로 전체 활성 소스를 사용합니다.</span>
            </div>
            <div className="filter-field">Quick Action
              <div className="form-actions">
                <button type="button" className="button" onClick={toggleAllVisibleSources}>
                  {filteredSources.length > 0 && filteredSources.every((s) => selectedSourceIds.includes(s.sourceId)) ? '보이는 소스 해제' : '보이는 소스 전체 선택'}
                </button>
                <button type="button" className="button" onClick={() => setSelectedSourceIds([])}>선택 초기화</button>
              </div>
            </div>
          </div>

          <div className="source-pick-grid">
            {filteredSources.map((source) => {
              const isSelected = selectedSourceIds.includes(source.sourceId)
              return (
                <button
                  key={source.sourceId}
                  type="button"
                  className={`source-pick-card ${isSelected ? 'is-selected' : ''}`}
                  onClick={() => toggleSourceSelection(source.sourceId)}
                >
                  <div className="source-pick-card__head">
                    <strong>{source.sourceId}</strong>
                    <StatusBadge value={source.enabled ? 'success' : 'cancelled'} label={source.enabled ? 'enabled' : 'disabled'} />
                  </div>
                  <div className="source-pick-card__product">{source.productName || '-'}</div>
                  <div className="source-pick-card__url">{source.baseUrl || '-'}</div>
                  <div className="source-pick-card__stats">
                    total {source.totalDocuments ?? 0} / active {source.activeDocuments ?? 0}
                  </div>
                </button>
              )
            })}
          </div>
        </div>
        <div className="toolbar">
          <button type="button" className="button button--primary" disabled={busyRunType === 'collect'} onClick={() => triggerPipeline('collect')}>문서 수집</button>
          <button type="button" className="button button--primary" disabled={busyRunType === 'normalize'} onClick={() => triggerPipeline('normalize')}>문서 정제</button>
          <button type="button" className="button button--primary" disabled={busyRunType === 'chunk'} onClick={() => triggerPipeline('chunk')}>청킹</button>
          <button type="button" className="button button--primary" disabled={busyRunType === 'glossary'} onClick={() => triggerPipeline('glossary')}>용어 추출</button>
          <button type="button" className="button" disabled={busyRunType === 'full_ingest'} onClick={() => triggerPipeline('full_ingest')}>전체 실행</button>
        </div>
      </section>

      <section className="table-shell">
        <div className="table-header">
          <div className="table-title">Source Registration</div>
        </div>
        <form className="filter-bar" onSubmit={autoRegisterSourceFromUrl}>
          <label className="filter-field" style={{ flex: 1 }}>Quick URL Auto Register
            <input
              value={quickSourceUrl}
              onChange={(event) => setQuickSourceUrl(event.target.value)}
              placeholder="https://docs.spring.io/spring-framework/reference/integration/rest-clients.html"
              required
            />
            <span className="field-hint">URL 한 개를 넣으면 sourceId/product/allowPrefix/denyPattern 기본값을 자동 추론해 등록합니다.</span>
          </label>
          <div className="filter-field filter-field--small">
            <button type="submit" className="button button--primary">Auto Register</button>
          </div>
        </form>
        <form className="filter-bar filter-bar--stack" onSubmit={submitSourceForm}>
          <div className="form-grid form-grid--2">
            <label className="filter-field">sourceId
              <input value={sourceForm.sourceId} onChange={(event) => setSourceForm((prev) => ({ ...prev, sourceId: event.target.value }))} required />
              <span className="field-hint">소스 고유 ID입니다. 재사용 가능한 slug 형식 권장. 예: `spring-security-docs`</span>
            </label>
            <label className="filter-field">productName
              <input value={sourceForm.productName} onChange={(event) => setSourceForm((prev) => ({ ...prev, productName: event.target.value }))} required />
              <span className="field-hint">문서 군집명입니다. 문서/청크 조회와 통계에서 제품 이름으로 사용됩니다.</span>
            </label>
            <label className="filter-field">requestDelaySeconds
              <input type="number" step="0.1" min="0" value={sourceForm.requestDelaySeconds} onChange={(event) => setSourceForm((prev) => ({ ...prev, requestDelaySeconds: event.target.value }))} />
              <span className="field-hint">페이지 요청 사이 대기 시간(초)입니다. 차단 회피 및 서버 부하 제어에 사용됩니다.</span>
            </label>
            <label className="filter-field">maxDepth
              <input type="number" min="1" value={sourceForm.maxDepth} onChange={(event) => setSourceForm((prev) => ({ ...prev, maxDepth: event.target.value }))} />
              <span className="field-hint">시작 URL에서 링크를 몇 단계까지 추적할지 정하는 탐색 깊이입니다.</span>
            </label>
            <label className="filter-field">enabled
              <select value={sourceForm.enabled ? 'true' : 'false'} onChange={(event) => setSourceForm((prev) => ({ ...prev, enabled: event.target.value === 'true' }))}>
                <option value="true">true</option>
                <option value="false">false</option>
              </select>
              <span className="field-hint">collect/full_ingest 기본 실행 시 포함할지 여부입니다.</span>
            </label>
            <label className="filter-field">startUrls (line-separated)
              <textarea rows={4} value={sourceForm.startUrlsText} onChange={(event) => setSourceForm((prev) => ({ ...prev, startUrlsText: event.target.value }))} required />
              <span className="field-hint">크롤러 시작점 URL 목록입니다. 한 줄에 하나씩 입력합니다.</span>
            </label>
            <label className="filter-field">allowPrefixes (line-separated)
              <textarea rows={4} value={sourceForm.allowPrefixesText} onChange={(event) => setSourceForm((prev) => ({ ...prev, allowPrefixesText: event.target.value }))} required />
              <span className="field-hint">허용 URL prefix 범위입니다. 해당 prefix 외 링크는 수집에서 제외됩니다.</span>
            </label>
            <label className="filter-field">denyUrlPatterns (line-separated)
              <textarea rows={4} value={sourceForm.denyUrlPatternsText} onChange={(event) => setSourceForm((prev) => ({ ...prev, denyUrlPatternsText: event.target.value }))} />
              <span className="field-hint">제외 URL 정규식 패턴입니다. 로그인/검색/태그/앵커 링크 등을 차단할 때 사용합니다.</span>
            </label>
          </div>
          <div className="form-actions">
            <button type="submit" className="button button--primary">Save Source</button>
            <button type="button" className="button" onClick={resetSourceForm}>Reset Form</button>
          </div>
        </form>
      </section>

      <section className="table-shell">
        <div className="table-header">
          <div className="table-title">파이프라인 실행 이력</div>
          <button type="button" className="button" onClick={() => Promise.all([loadSummary(), loadRuns(runPage)]).catch((error) => notify(error.message, 'error'))}>새로고침</button>
        </div>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr><th>실행 ID</th><th>실행 유형</th><th>시작</th><th>종료</th><th>상태</th><th>처리 건수</th><th>오류</th></tr>
            </thead>
            <tbody>
              {runs.map((run) => {
                const payload = typeof run.summaryJson === 'object' ? run.summaryJson : {}
                const processed = payload.document_count ?? payload.documents_discovered ?? payload.documents_persisted ?? 0
                return (
                  <tr key={run.runId}>
                    <td><IdBadge value={run.runId} /></td>
                    <td>{run.runType}</td>
                    <td>{fmtTime(run.startedAt)}</td>
                    <td>{fmtTime(run.finishedAt)}</td>
                    <td><StatusBadge value={run.runStatus} /></td>
                    <td>{processed}</td>
                    <td>{run.errorMessage ? <StatusBadge value="failed" label="있음" /> : <StatusBadge value="success" label="없음" />}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
        <div className="pagination">
          <button
            type="button"
            className="button"
            disabled={runPage === 0}
            onClick={() => {
              const nextPage = Math.max(0, runPage - 1)
              setRunPage(nextPage)
              loadRuns(nextPage).catch((error) => notify(error.message, 'error'))
            }}
          >이전</button>
          <div className="pagination__label">페이지 {runPage + 1}</div>
          <button
            type="button"
            className="button"
            disabled={!runHasNextPage}
            onClick={() => {
              const nextPage = runPage + 1
              setRunPage(nextPage)
              loadRuns(nextPage).catch((error) => notify(error.message, 'error'))
            }}
          >다음</button>
        </div>
      </section>

      <section className="table-shell">
        <div className="table-header"><div className="table-title">문서/청크 조회</div></div>
        <form className="filter-bar" onSubmit={(event) => { event.preventDefault(); applyDocumentFilters(filters).catch((error) => notify(error.message, 'error')) }}>
          <label className="filter-field">문서 소스
            <select value={filters.source_id} onChange={(event) => setFilters((prev) => ({ ...prev, source_id: event.target.value }))}>
              <option value="">전체</option>
              {sources.map((source) => <option key={source.sourceId} value={source.sourceId}>{source.sourceId} ({source.productName || '-'})</option>)}
            </select>
          </label>
          <label className="filter-field">버전
            <input value={filters.version_label} placeholder="예: 6.1" onChange={(event) => setFilters((prev) => ({ ...prev, version_label: event.target.value }))} />
          </label>
          <label className="filter-field">문서 ID
            <input value={filters.document_id} placeholder="document_id" onChange={(event) => setFilters((prev) => ({ ...prev, document_id: event.target.value }))} />
          </label>
          <label className="filter-field">검색
            <input value={filters.search} placeholder="title/url/chunk keyword" onChange={(event) => setFilters((prev) => ({ ...prev, search: event.target.value }))} />
          </label>
          <div className="filter-field filter-field--small"><button type="submit" className="button button--primary">조회</button></div>
        </form>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr><th>문서 ID</th><th>제목</th><th>소스</th><th>버전</th><th>섹션 수</th><th>청크 수</th><th>상세</th></tr>
            </thead>
            <tbody>
              {documents.map((doc) => (
                <tr key={doc.documentId}>
                  <td><IdBadge value={doc.documentId} plain /></td>
                  <td>{doc.title}</td>
                  <td>{doc.sourceId}</td>
                  <td>{doc.versionLabel || '-'}</td>
                  <td>{doc.sectionCount ?? 0}</td>
                  <td>{doc.chunkCount ?? 0}</td>
                  <td><button type="button" className="button button--ghost" onClick={() => showDocumentDetail(doc.documentId)}>상세 조회</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="pagination">
          <button
            type="button"
            className="button"
            disabled={documentPage === 0}
            onClick={() => {
              const nextPage = Math.max(0, documentPage - 1)
              setDocumentPage(nextPage)
              loadDocuments(filters, nextPage).catch((error) => notify(error.message, 'error'))
            }}
          >이전</button>
          <div className="pagination__label">페이지 {documentPage + 1}</div>
          <button
            type="button"
            className="button"
            disabled={!documentHasNextPage}
            onClick={() => {
              const nextPage = documentPage + 1
              setDocumentPage(nextPage)
              loadDocuments(filters, nextPage).catch((error) => notify(error.message, 'error'))
            }}
          >다음</button>
        </div>
      </section>

      <section className="table-shell">
        <div className="table-header">
          <div className="table-title">Anchors</div>
          <button
            type="button"
            className="button"
            disabled={anchorListLoading}
            onClick={() => loadAnchors(anchorFilters, anchorPage).catch((error) => notify(error.message, 'error'))}
          >
            새로고침
          </button>
        </div>
        <form
          className="filter-bar filter-bar--stack"
          onSubmit={(event) => {
            event.preventDefault()
            applyAnchorFilters(anchorFilters).catch((error) => notify(error.message, 'error'))
          }}
        >
          <div className="form-grid form-grid--3">
            <label className="filter-field">
              Document Filter
              <SelectDropdown
                value={anchorFilters.documentId}
                options={anchorDocumentOptions}
                onOpen={() => {
                  ensureAnchorFilterDocumentsLoaded().catch((error) => notify(error.message, 'error'))
                }}
                onChange={(nextDocumentId) => {
                  handleAnchorFilterDocumentChange(nextDocumentId).catch((error) => notify(error.message, 'error'))
                }}
                placeholder="전체 문서"
                clearLabel="전체 문서"
                searchPlaceholder="문서 제목/ID 검색"
                emptyLabel="선택 가능한 문서가 없습니다."
              />
            </label>
            <label className="filter-field">
              Chunk Filter
              <SelectDropdown
                value={anchorFilters.chunkId}
                options={anchorChunkOptions}
                onChange={(nextChunkId) => setAnchorFilters((prev) => ({ ...prev, chunkId: nextChunkId }))}
                placeholder={anchorFilters.documentId ? '전체 청크' : '문서를 먼저 선택하세요'}
                clearLabel="전체 청크"
                searchPlaceholder="chunk id / section path 검색"
                emptyLabel="선택 가능한 청크가 없습니다."
                disabled={anchorFilterLoading || !anchorFilters.documentId}
              />
            </label>
            <label className="filter-field">
              Anchor Keyword
              <input
                value={anchorFilters.keyword}
                onChange={(event) => setAnchorFilters((prev) => ({ ...prev, keyword: event.target.value }))}
                placeholder="canonical anchor 검색"
              />
            </label>
          </div>
          <div className="form-actions">
            <button type="submit" className="button button--primary" disabled={anchorListLoading}>조회</button>
            <button type="button" className="button" onClick={() => resetAnchorFilters().catch((error) => notify(error.message, 'error'))}>
              초기화
            </button>
          </div>
        </form>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr><th>Anchor</th><th>Type</th><th>Confidence</th><th>Evidence</th><th>First Seen</th><th>Updated</th></tr>
            </thead>
            <tbody>
              {!anchorListLoaded && (
                <tr>
                  <td colSpan={6}>Anchor list is lazy-loaded. Click refresh or run a filter search.</td>
                </tr>
              )}
              {anchorListLoaded && anchors.map((anchor) => (
                <tr key={anchor.termId}>
                  <td>
                    <div>{anchor.canonicalForm}</div>
                    <div className="line-clamp mono-text">{shortId(anchor.termId)}</div>
                  </td>
                  <td>{anchor.termType || '-'}</td>
                  <td>{Number(anchor.sourceConfidence || 0).toFixed(2)}</td>
                  <td>{anchor.scopedEvidenceCount ?? 0} / {anchor.evidenceCount ?? 0}</td>
                  <td>
                    <div className="mono-text">{anchor.firstSeenDocumentId || '-'}</div>
                    <div className="mono-text">{anchor.firstSeenChunkId || '-'}</div>
                  </td>
                  <td>{fmtTime(anchor.updatedAt)}</td>
                </tr>
              ))}
              {anchorListLoaded && anchors.length === 0 && (
                <tr>
                  <td colSpan={6}>조회 결과가 없습니다.</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        <div className="pagination">
          <button
            type="button"
            className="button"
            disabled={!anchorListLoaded || anchorPage === 0}
            onClick={() => {
              const nextPage = Math.max(0, anchorPage - 1)
              setAnchorPage(nextPage)
              loadAnchors(anchorFilters, nextPage).catch((error) => notify(error.message, 'error'))
            }}
          >이전</button>
          <div className="pagination__label">페이지 {anchorPage + 1}</div>
          <button
            type="button"
            className="button"
            disabled={!anchorListLoaded || !anchorHasNextPage}
            onClick={() => {
              const nextPage = anchorPage + 1
              setAnchorPage(nextPage)
              loadAnchors(anchorFilters, nextPage).catch((error) => notify(error.message, 'error'))
            }}
          >다음</button>
        </div>
      </section>

      <section className="table-shell">
        <div className="table-header">
          <div className="table-title">Anchor Eval</div>
          <button type="button" className="button" onClick={() => loadAnchorEvalRuns().catch((error) => notify(error.message, 'error'))}>새로고침</button>
        </div>
        <form className="filter-bar filter-bar--stack anchor-eval-shell" onSubmit={createAnchorEvalRun}>
          <div className="form-grid form-grid--3">
            <label className="filter-field">Run Name
              <input value={anchorEvalForm.runName} onChange={(event) => setAnchorEvalForm((prev) => ({ ...prev, runName: event.target.value }))} placeholder="optional" />
            </label>
            <label className="filter-field">Source
              <select value={anchorEvalForm.sourceId} onChange={(event) => handleAnchorEvalSourceChange(event.target.value).catch((error) => notify(error.message, 'error'))}>
                <option value="">전체 소스</option>
                {sources.map((source) => (
                  <option key={source.sourceId} value={source.sourceId}>{source.sourceId} ({source.productName || '-'})</option>
                ))}
              </select>
              <span className="field-hint">소스를 고르면 문서/청크 드롭다운이 자동 갱신됩니다.</span>
            </label>
            <label className="filter-field">Product Name
              <input value={anchorEvalForm.productName} onChange={(event) => setAnchorEvalForm((prev) => ({ ...prev, productName: event.target.value }))} placeholder="optional" />
            </label>
            <label className="filter-field">Sample Size
              <input type="number" min="1" max="200" value={anchorEvalForm.sampleSize} onChange={(event) => setAnchorEvalForm((prev) => ({ ...prev, sampleSize: event.target.value }))} />
              <span className="field-hint">평가 샘플로 뽑을 chunk 개수입니다. 값이 커질수록 평가 범위는 넓어지지만 실행 시간이 늘어납니다.</span>
            </label>
            <label className="filter-field">Candidate Limit
              <input type="number" min="1" max="50" value={anchorEvalForm.candidateLimit} onChange={(event) => setAnchorEvalForm((prev) => ({ ...prev, candidateLimit: event.target.value }))} />
              <span className="field-hint">각 샘플 chunk마다 검토할 anchor 후보 최대 개수입니다. 값이 커질수록 후보 다양성은 증가하지만 라벨링 비용이 커집니다.</span>
            </label>
          </div>
          <div className="anchor-eval-panel">
            <div className="anchor-eval-panel__title">문서/청크 범위 선택</div>
            <div className="form-grid form-grid--2">
              <label className="filter-field">Documents (multi-select)
                <div className="form-actions">
                  <button type="button" className="button button--ghost" disabled={anchorEvalLoadingScope || !anchorEvalForm.sourceId || anchorEvalScopeDocuments.length === 0} onClick={() => selectAllAnchorEvalDocuments().catch((error) => notify(error.message, 'error'))}>전체 문서 선택</button>
                </div>
                <select
                  multiple
                  size={8}
                  value={anchorEvalForm.selectedDocumentIds}
                  onChange={(event) => {
                    const next = Array.from(event.target.selectedOptions).map((option) => option.value)
                    handleAnchorEvalDocumentSelection(next).catch((error) => notify(error.message, 'error'))
                  }}
                  disabled={anchorEvalLoadingScope || !anchorEvalForm.sourceId}
                >
                  {anchorEvalScopeDocuments.map((doc) => (
                    <option key={doc.documentId} value={doc.documentId}>{doc.title || doc.documentId}</option>
                  ))}
                </select>
              </label>
              <label className="filter-field">Chunks (multi-select)
                <div className="form-actions">
                  <button type="button" className="button button--ghost" disabled={anchorEvalLoadingScope || anchorEvalScopeChunks.length === 0} onClick={selectAllAnchorEvalChunks}>전체 청크 선택</button>
                </div>
                <select
                  multiple
                  size={8}
                  value={anchorEvalForm.selectedChunkIds}
                  onChange={(event) => {
                    const next = Array.from(event.target.selectedOptions).map((option) => option.value)
                    setAnchorEvalForm((prev) => ({ ...prev, selectedChunkIds: next }))
                  }}
                  disabled={anchorEvalLoadingScope || anchorEvalForm.selectedDocumentIds.length === 0}
                >
                  {anchorEvalScopeChunks.map((chunk) => (
                    <option key={chunk.chunkId} value={chunk.chunkId}>{chunk.chunkId} · {(chunk.sectionPathText || '').slice(0, 80)}</option>
                  ))}
                </select>
              </label>
            </div>
            <div className="anchor-eval-chip-row">
              <span className="anchor-eval-chip">source: {selectedSourceForEval?.sourceId || 'all'}</span>
              <span className="anchor-eval-chip">docs: {anchorEvalForm.selectedDocumentIds.length}</span>
              <span className="anchor-eval-chip">chunks: {anchorEvalForm.selectedChunkIds.length}</span>
              {anchorEvalLoadingScope && <span className="anchor-eval-chip is-loading">loading...</span>}
            </div>
          </div>
          <div className="filter-field filter-field--small">
            <button type="submit" className="button button--primary" disabled={anchorEvalBusy}>평가 Run 생성</button>
          </div>
        </form>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr><th>run</th><th>scope</th><th>samples</th><th>metrics</th><th>created</th><th>detail</th></tr>
            </thead>
            <tbody>
              {!anchorEvalRunsLoaded && (
                <tr>
                  <td colSpan={6}>{anchorEvalRunsLoading ? 'Loading Anchor Eval runs...' : 'Anchor Eval history is lazy-loaded. Click refresh to load runs.'}</td>
                </tr>
              )}
              {anchorEvalRunsLoaded && anchorEvalRuns.map((run) => (
                <tr key={run.runId}>
                  <td>{run.runName}</td>
                  <td>{run.productName || '-'} / {run.sourceId || '-'}</td>
                  <td>{run.sampleSize} x {run.candidateLimit}</td>
                  <td>{typeof run.summaryJson === 'object' ? JSON.stringify(run.summaryJson) : '-'}</td>
                  <td>{fmtTime(run.createdAt)}</td>
                  <td><button type="button" className="button button--ghost" disabled={anchorEvalDetailBusy} onClick={() => openAnchorEvalRunDetail(run.runId)}>열기</button></td>
                </tr>
              ))}
              {anchorEvalRunsLoaded && anchorEvalRuns.length === 0 && (
                <tr>
                  <td colSpan={6}>No Anchor Eval runs found.</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>
      <Modal data={modal} onClose={() => setModal(null)} />
    </>
  )
}
