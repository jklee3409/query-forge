import { useEffect, useState } from 'react'
import { DetailCard, IdBadge, Modal, NumberInput, StageCard, StatusBadge } from '../components/Common.jsx'
import { LlmJobsTable } from '../components/LlmJobsTable.jsx'
import { queryString, requestJson, toNumber } from '../lib/api.js'
import { shortId } from '../lib/format.js'

export function GatingPage({ notify }) {
  const resultPageSize = 20
  const historyPageSize = 3
  const normalizeMethodCode = (value) => String(value || '').trim().toUpperCase()

  const [methods, setMethods] = useState([])
  const [batches, setBatches] = useState([])
  const [gatingBatches, setGatingBatches] = useState([])
  const [results, setResults] = useState([])
  const [funnel, setFunnel] = useState(null)
  const [llmJobs, setLlmJobs] = useState([])
  const [selectedBatchId, setSelectedBatchId] = useState('')
  const [resultFilter, setResultFilter] = useState({ methodCode: '', generationBatchId: '', gatingBatchId: '', passStage: '' })
  const [funnelFilter, setFunnelFilter] = useState({ methodCode: '', generationBatchId: '', gatingBatchId: '' })
  const [gatingBatchPage, setGatingBatchPage] = useState(0)
  const [resultPage, setResultPage] = useState(0)
  const [resultHasNextPage, setResultHasNextPage] = useState(false)
  const [modal, setModal] = useState(null)

  const pickDefaultGatingBatchId = (rows) => {
    if (!Array.isArray(rows) || rows.length === 0) return ''
    const completed = rows.find((row) => String(row.status || '').toLowerCase() === 'completed')
    return (completed || rows[0]).gatingBatchId
  }

  const findQueryableGatingBatches = (methodCode, generationBatchId) => {
    const normalizedMethodCode = normalizeMethodCode(methodCode)
    return gatingBatches.filter((batch) => {
      const status = String(batch.status || '').toLowerCase()
      if (status !== 'completed' && status !== 'running') return false
      if (generationBatchId && batch.generationBatchId !== generationBatchId) return false
      if (!normalizedMethodCode) return true
      return normalizeMethodCode(batch.methodCode) === normalizedMethodCode
    })
  }

  const resolveGatingBatchId = (currentBatchId, options) => {
    if (currentBatchId && options.some((batch) => batch.gatingBatchId === currentBatchId)) {
      return currentBatchId
    }
    return options[0]?.gatingBatchId || ''
  }

  const isFilterReady = (filter) => Boolean(filter?.methodCode && filter?.generationBatchId)

  const findGenerationBatchOptions = (methodCode) => {
    const normalizedMethodCode = normalizeMethodCode(methodCode)
    if (!normalizedMethodCode) return batches
    return batches.filter((batch) => normalizeMethodCode(batch.methodCode) === normalizedMethodCode)
  }

  const findActiveGenerationBatchOptions = (methodCode) => (
    findGenerationBatchOptions(methodCode).filter((batch) => {
      const status = String(batch.status || '').toLowerCase()
      return status !== 'failed' && status !== 'cancelled'
    })
  )

  const [form, setForm] = useState({
    methodCode: '',
    generationBatchId: '',
    gatingPreset: 'full_gating',
    enableRuleFilter: true,
    enableLlmSelfEval: true,
    enableRetrievalUtility: true,
    enableDiversity: true,
    ruleMinLengthShort: '4',
    ruleMaxLengthShort: '60',
    ruleMinLengthLong: '8',
    ruleMaxLengthLong: '100',
    ruleMinTokens: '2',
    ruleMaxTokens: '30',
    ruleMinKoreanRatio: '0.2',
    llmWeight: '0.35',
    utilityWeight: '0.50',
    diversityWeight: '0.15',
    utilityTargetTop1Score: '1.00',
    utilityTargetTop3Score: '0.85',
    utilityTargetTop5Score: '0.70',
    utilityTargetTop10Score: '0.60',
    utilitySameDocTop3Score: '0.55',
    utilitySameDocTop5Score: '0.40',
    utilityOutsideTop5Score: '0.00',
    utilityMultiPartialBonus: '0.05',
    utilityMultiFullBonus: '0.12',
    utilityThreshold: '0.70',
    diversityThresholdSameChunk: '0.93',
    diversityThresholdSameDoc: '0.96',
    finalScoreThreshold: '0.75',
  })

  const loadSelectors = async () => {
    const [methodRows, batchRows] = await Promise.all([
      requestJson('/api/admin/console/synthetic/methods'),
      requestJson('/api/admin/console/synthetic/batches?limit=100'),
    ])
    const normalizedMethods = Array.isArray(methodRows) ? methodRows : []
    setMethods(normalizedMethods)
    setForm((prev) => ({ ...prev, methodCode: prev.methodCode || normalizedMethods[0]?.methodCode || '' }))
    setBatches(Array.isArray(batchRows) ? batchRows : [])
  }

  const loadGatingBatches = async () => {
    const rows = await requestJson('/api/admin/console/gating/batches?limit=200')
    const normalized = Array.isArray(rows) ? rows : []
    setGatingBatches(normalized)
    const hasSelected = Boolean(selectedBatchId) && normalized.some((batch) => batch.gatingBatchId === selectedBatchId)
    if (!hasSelected) {
      setSelectedBatchId(pickDefaultGatingBatchId(normalized))
    }
  }

  const loadFunnel = async (batchId, methodCode = funnelFilter.methodCode) => {
    if (!batchId) {
      setFunnel(null)
      return
    }
    const query = queryString({ method_code: methodCode || null })
    const url = query
      ? `/api/admin/console/gating/batches/${batchId}/funnel?${query}`
      : `/api/admin/console/gating/batches/${batchId}/funnel`
    setFunnel(await requestJson(url))
  }

  const loadResults = async (batchId, page = 0, methodCode = resultFilter.methodCode, passStage = resultFilter.passStage) => {
    if (!batchId) {
      setResults([])
      setResultHasNextPage(false)
      return
    }
    const query = queryString({
      method_code: methodCode || null,
      pass_stage: passStage || null,
      limit: resultPageSize + 1,
      offset: page * resultPageSize,
    })
    const rows = await requestJson(`/api/admin/console/gating/batches/${batchId}/results?${query}`)
    const normalized = Array.isArray(rows) ? rows : []
    setResultHasNextPage(normalized.length > resultPageSize)
    setResults(normalized.slice(0, resultPageSize))
  }

  const loadLlmJobs = async () => {
    const rows = await requestJson('/api/admin/console/llm-jobs?limit=120')
    const filtered = (Array.isArray(rows) ? rows : []).filter((job) => job.jobType === 'RUN_LLM_SELF_EVAL' || job.gatingBatchId)
    setLlmJobs(filtered)
  }

  useEffect(() => {
    Promise.all([loadSelectors(), loadGatingBatches(), loadLlmJobs()]).catch((error) => notify(error.message, 'error'))
  }, [])

  useEffect(() => {
    if (!gatingBatches.length) {
      setFunnel(null)
      setResults([])
      setResultHasNextPage(false)
      return
    }
    const funnelReady = isFilterReady(funnelFilter)
    const resultReady = isFilterReady(resultFilter)
    if (!funnelReady) setFunnel(null)
    if (!resultReady) {
      setResults([])
      setResultHasNextPage(false)
    }
    if (!funnelReady && !resultReady) return
    const initialPage = 0
    setResultPage(initialPage)
    const tasks = []
    if (funnelReady) {
      const funnelOptions = findQueryableGatingBatches(funnelFilter.methodCode, funnelFilter.generationBatchId)
      const funnelBatchId = resolveGatingBatchId(funnelFilter.gatingBatchId, funnelOptions)
      tasks.push(loadFunnel(funnelBatchId, funnelFilter.methodCode))
    }
    if (resultReady) {
      const resultOptions = findQueryableGatingBatches(resultFilter.methodCode, resultFilter.generationBatchId)
      const resultBatchId = resolveGatingBatchId(resultFilter.gatingBatchId, resultOptions)
      tasks.push(loadResults(resultBatchId, initialPage, resultFilter.methodCode, resultFilter.passStage))
    }
    Promise.all(tasks).catch((error) => notify(error.message, 'error'))
  }, [gatingBatches, funnelFilter.methodCode, funnelFilter.generationBatchId, funnelFilter.gatingBatchId, resultFilter.methodCode, resultFilter.generationBatchId, resultFilter.gatingBatchId, resultFilter.passStage])

  useEffect(() => {
    const totalPages = Math.max(1, Math.ceil(gatingBatches.length / historyPageSize))
    if (gatingBatchPage > totalPages - 1) {
      setGatingBatchPage(totalPages - 1)
    }
  }, [gatingBatches, gatingBatchPage])

  const runGating = async (event) => {
    event.preventDefault()
    if (!form.generationBatchId) {
      notify('생성 배치를 선택하세요.', 'error')
      return
    }
    try {
      await requestJson('/api/admin/console/gating/batches/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          methodCode: form.methodCode || null,
          generationBatchId: form.generationBatchId || null,
          gatingPreset: form.gatingPreset,
          config: {
            stageFlags: {
              enableRuleFilter: Boolean(form.enableRuleFilter),
              enableLlmSelfEval: Boolean(form.enableLlmSelfEval),
              enableRetrievalUtility: Boolean(form.enableRetrievalUtility),
              enableDiversity: Boolean(form.enableDiversity),
            },
            ruleConfig: {
              minLengthShort: toNumber(form.ruleMinLengthShort),
              maxLengthShort: toNumber(form.ruleMaxLengthShort),
              minLengthLong: toNumber(form.ruleMinLengthLong),
              maxLengthLong: toNumber(form.ruleMaxLengthLong),
              minTokens: toNumber(form.ruleMinTokens),
              maxTokens: toNumber(form.ruleMaxTokens),
              minKoreanRatio: toNumber(form.ruleMinKoreanRatio),
            },
            gatingWeights: {
              llmWeight: toNumber(form.llmWeight),
              utilityWeight: toNumber(form.utilityWeight),
              diversityWeight: toNumber(form.diversityWeight),
            },
            utilityScoreWeights: {
              targetTop1Score: toNumber(form.utilityTargetTop1Score),
              targetTop3Score: toNumber(form.utilityTargetTop3Score),
              targetTop5Score: toNumber(form.utilityTargetTop5Score),
              targetTop10Score: toNumber(form.utilityTargetTop10Score),
              sameDocTop3Score: toNumber(form.utilitySameDocTop3Score),
              sameDocTop5Score: toNumber(form.utilitySameDocTop5Score),
              outsideTop5Score: toNumber(form.utilityOutsideTop5Score),
              multiPartialBonus: toNumber(form.utilityMultiPartialBonus),
              multiFullBonus: toNumber(form.utilityMultiFullBonus),
            },
            thresholds: {
              utilityThreshold: toNumber(form.utilityThreshold),
              diversityThresholdSameChunk: toNumber(form.diversityThresholdSameChunk),
              diversityThresholdSameDoc: toNumber(form.diversityThresholdSameDoc),
              finalScoreThreshold: toNumber(form.finalScoreThreshold),
            },
          },
        }),
      })
      await Promise.all([loadGatingBatches(), loadLlmJobs()])
      notify('Quality Gating 배치를 등록했습니다.')
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const openBatchDetail = (batch) => {
    setSelectedBatchId(batch.gatingBatchId)
    setModal({
      title: `게이팅 배치 상세 · ${shortId(batch.gatingBatchId)}`,
      body: (
        <div className="detail-grid detail-grid--single">
          <DetailCard label="프리셋 / 방식" value={`${batch.gatingPreset || '-'} / ${batch.methodCode || '-'}`} />
          <DetailCard label="처리 / 승인" value={`${batch.processedCount ?? 0} / ${batch.acceptedCount ?? 0}`} />
          <DetailCard label="설정(stage_config_json)" value={JSON.stringify(batch.stageConfig || {}, null, 2)} />
          <DetailCard label="리젝트 요약" value={JSON.stringify(batch.rejectionSummary || {}, null, 2)} />
        </div>
      ),
    })
  }

  const executeLlmAction = async (jobId, action) => {
    try {
      await requestJson(`/api/admin/console/llm-jobs/${jobId}/${action}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: '{}',
      })
      await loadLlmJobs()
      notify(`JOB ${action} 요청을 전송했습니다.`)
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const openJobDetail = async (jobId) => {
    try {
      const [job, items] = await Promise.all([
        requestJson(`/api/admin/console/llm-jobs/${jobId}`),
        requestJson(`/api/admin/console/llm-jobs/${jobId}/items`),
      ])
      setModal({
        title: `LLM JOB 상세 · ${shortId(jobId)}`,
        body: (
          <div className="detail-grid detail-grid--single">
            <DetailCard label="커맨드 / 실험" value={`${job.commandName || '-'} / ${job.experimentName || '-'}`} />
            <DetailCard label="진행률" value={`${job.processedItems ?? 0} / ${job.totalItems ?? 0} (${job.progressPct == null ? '-' : `${Number(job.progressPct).toFixed(1)}%`})`} />
            <DetailCard label="job_items" value={JSON.stringify(items || [], null, 2)} />
          </div>
        ),
      })
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const applyResultFilter = async (event) => {
    event.preventDefault()
    if (!isFilterReady(resultFilter)) {
      notify('생성 방식과 생성 배치를 모두 선택하세요.', 'error')
      return
    }
    try {
      const initialPage = 0
      setResultPage(initialPage)
      const options = findQueryableGatingBatches(resultFilter.methodCode, resultFilter.generationBatchId)
      const batchId = resolveGatingBatchId(resultFilter.gatingBatchId, options)
      await loadResults(batchId, initialPage, resultFilter.methodCode, resultFilter.passStage)
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const applyFunnelFilter = async (event) => {
    event.preventDefault()
    if (!isFilterReady(funnelFilter)) {
      notify('생성 방식과 생성 배치를 모두 선택하세요.', 'error')
      return
    }
    try {
      const options = findQueryableGatingBatches(funnelFilter.methodCode, funnelFilter.generationBatchId)
      const batchId = resolveGatingBatchId(funnelFilter.gatingBatchId, options)
      await loadFunnel(batchId, funnelFilter.methodCode)
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const resultGatingBatchOptions = findQueryableGatingBatches(resultFilter.methodCode, resultFilter.generationBatchId)
  const funnelGatingBatchOptions = findQueryableGatingBatches(funnelFilter.methodCode, funnelFilter.generationBatchId)
  const resultReferenceBatchId = resolveGatingBatchId(resultFilter.gatingBatchId, resultGatingBatchOptions)
  const funnelReferenceBatchId = resolveGatingBatchId(funnelFilter.gatingBatchId, funnelGatingBatchOptions)
  const formBatchOptions = findGenerationBatchOptions(form.methodCode)
    .filter((batch) => String(batch.status || '').toLowerCase() === 'completed')
  const funnelBatchOptions = funnelFilter.methodCode ? findActiveGenerationBatchOptions(funnelFilter.methodCode) : []
  const resultBatchOptions = resultFilter.methodCode ? findActiveGenerationBatchOptions(resultFilter.methodCode) : []
  const gatingBatchTotalPages = Math.max(1, Math.ceil(gatingBatches.length / historyPageSize))
  const currentGatingBatchPage = Math.min(gatingBatchPage, gatingBatchTotalPages - 1)
  const pagedGatingBatches = gatingBatches.slice(currentGatingBatchPage * historyPageSize, (currentGatingBatchPage + 1) * historyPageSize)

  const funnelCards = [
    { label: '생성 총량', value: funnel?.generatedTotal ?? 0 },
    { label: 'Rule 통과', value: funnel?.passedRule ?? 0 },
    { label: 'LLM 통과', value: funnel?.passedLlm ?? 0 },
    { label: 'Utility 통과', value: funnel?.passedUtility ?? 0 },
    { label: 'Diversity 통과', value: funnel?.passedDiversity ?? 0 },
    { label: '최종 승인', value: funnel?.finalAccepted ?? 0 },
  ]

  const renderStageStatus = (value) => {
    if (value === true) return <StatusBadge value="success" label="통과" />
    if (value === false) return <StatusBadge value="failed" label="실패" />
    return <StatusBadge value="queued" label="미사용" />
  }

  const normalizeToken = (value) => String(value || '').trim().toLowerCase().replace(/\s+/g, '_')

  const tokenIconMap = {
    query: {
      short_user: 'SU',
      follow_up: 'FU',
      long_context: 'LC',
      long_user: 'LU',
      clarification: 'CL',
      multi_hop: 'MH',
    },
    stage: {
      rule: 'R',
      llm: 'L',
      utility: 'U',
      diversity: 'D',
      final: 'F',
    },
    reason: {
      too_short: 'S',
      too_long: 'L',
      low_korean_ratio: 'KR',
      low_score: 'SC',
      duplicate: 'DP',
      same_chunk: 'CH',
      same_doc: 'DOC',
      out_of_scope: 'OS',
    },
  }

  const parseTokenList = (value) => {
    if (value == null) return []
    if (Array.isArray(value)) {
      return value.map((item) => String(item || '').trim()).filter(Boolean)
    }
    if (typeof value === 'object') {
      return Object.entries(value)
        .filter(([, itemValue]) => Boolean(itemValue))
        .map(([itemKey]) => String(itemKey || '').trim())
        .filter(Boolean)
    }
    const text = String(value || '').trim()
    if (!text) return []
    if (text.startsWith('[') || text.startsWith('{')) {
      try {
        const parsed = JSON.parse(text)
        return parseTokenList(parsed)
      } catch {
        // ignore parse errors and fallback to delimiter split
      }
    }
    const tokens = text
      .split(/[|,;/]+/)
      .map((token) => token.trim())
      .filter(Boolean)
    return tokens.length ? tokens : [text]
  }

  const toTokenIcon = (kind, token) => {
    const normalized = normalizeToken(token)
    const mapped = tokenIconMap[kind]?.[normalized]
    if (mapped) return mapped
    const parts = normalized.split(/[^a-z0-9]+/).filter(Boolean)
    if (!parts.length) return '?'
    if (parts.length === 1) return parts[0].slice(0, 3).toUpperCase()
    return parts.slice(0, 2).map((part) => part.slice(0, 1)).join('').toUpperCase()
  }

  const renderTokenBadges = (value, kind) => {
    const tokens = parseTokenList(value)
    if (!tokens.length) return <span className="plain-badge">-</span>
    return (
      <div className="token-badge-list">
        {tokens.map((token, index) => {
          const normalized = normalizeToken(token)
          return (
            <span key={`${kind}-${normalized || index}-${index}`} className="token-badge" data-kind={kind} title={token}>
              <span className="token-badge__icon" aria-hidden="true">{toTokenIcon(kind, token)}</span>
              <span className="token-badge__text">{normalized || token}</span>
            </span>
          )
        })}
      </div>
    )
  }

  return (
    <>
      <section className="panel">
        <div className="table-title">게이팅 실행</div>
        <form className="filter-bar" onSubmit={runGating}>
          <label className="filter-field">생성 방식
            <select value={form.methodCode} onChange={(event) => setForm((prev) => ({ ...prev, methodCode: event.target.value, generationBatchId: '' }))}>
              {methods.map((method) => <option key={method.methodCode} value={method.methodCode}>{method.methodCode} - {method.methodName}</option>)}
            </select>
          </label>
          <label className="filter-field">생성 배치
            <select value={form.generationBatchId} onChange={(event) => setForm((prev) => ({ ...prev, generationBatchId: event.target.value }))}>
              <option value="" disabled>생성 배치를 선택하세요</option>{formBatchOptions.map((batch) => <option key={batch.batchId} value={batch.batchId}>{batch.versionName} ({batch.methodCode})</option>)}
            </select>
          </label>
          <label className="filter-field">게이팅 프리셋
            <select value={form.gatingPreset} onChange={(event) => setForm((prev) => ({ ...prev, gatingPreset: event.target.value }))}>
              <option value="ungated">ungated</option><option value="rule_only">rule_only</option><option value="rule_plus_llm">rule_plus_llm</option><option value="full_gating">full_gating</option>
            </select>
          </label>
          <div className="stage-config-grid">
            <StageCard title="Rule" checked={form.enableRuleFilter} onToggle={(checked) => setForm((prev) => ({ ...prev, enableRuleFilter: checked }))}>
              <NumberInput label="짧은 질의 최소 길이" value={form.ruleMinLengthShort} onChange={(value) => setForm((prev) => ({ ...prev, ruleMinLengthShort: value }))} />
              <NumberInput label="짧은 질의 최대 길이" value={form.ruleMaxLengthShort} onChange={(value) => setForm((prev) => ({ ...prev, ruleMaxLengthShort: value }))} />
              <NumberInput label="일반 질의 최소 길이" value={form.ruleMinLengthLong} onChange={(value) => setForm((prev) => ({ ...prev, ruleMinLengthLong: value }))} />
              <NumberInput label="일반 질의 최대 길이" value={form.ruleMaxLengthLong} onChange={(value) => setForm((prev) => ({ ...prev, ruleMaxLengthLong: value }))} />
              <NumberInput label="최소 토큰(단어 수 하한)" value={form.ruleMinTokens} onChange={(value) => setForm((prev) => ({ ...prev, ruleMinTokens: value }))} />
              <NumberInput label="최대 토큰(단어 수 상한)" value={form.ruleMaxTokens} onChange={(value) => setForm((prev) => ({ ...prev, ruleMaxTokens: value }))} />
              <NumberInput label="최소 한글 비중(0~1, 비우면 기본값)" step="0.01" value={form.ruleMinKoreanRatio} onChange={(value) => setForm((prev) => ({ ...prev, ruleMinKoreanRatio: value }))} />
            </StageCard>
            <StageCard title="LLM" checked={form.enableLlmSelfEval} onToggle={(checked) => setForm((prev) => ({ ...prev, enableLlmSelfEval: checked }))}>
              <NumberInput label="LLM 가중치" step="0.01" value={form.llmWeight} onChange={(value) => setForm((prev) => ({ ...prev, llmWeight: value }))} />
            </StageCard>
            <StageCard title="Utility" checked={form.enableRetrievalUtility} onToggle={(checked) => setForm((prev) => ({ ...prev, enableRetrievalUtility: checked }))}>
              <NumberInput label="Utility 가중치" step="0.01" value={form.utilityWeight} onChange={(value) => setForm((prev) => ({ ...prev, utilityWeight: value }))} />
              <NumberInput label="Target Top1 점수" step="0.01" value={form.utilityTargetTop1Score} onChange={(value) => setForm((prev) => ({ ...prev, utilityTargetTop1Score: value }))} />
              <NumberInput label="Target Top3 점수" step="0.01" value={form.utilityTargetTop3Score} onChange={(value) => setForm((prev) => ({ ...prev, utilityTargetTop3Score: value }))} />
              <NumberInput label="Target Top5 점수" step="0.01" value={form.utilityTargetTop5Score} onChange={(value) => setForm((prev) => ({ ...prev, utilityTargetTop5Score: value }))} />
              <NumberInput label="Target Top10 점수" step="0.01" value={form.utilityTargetTop10Score} onChange={(value) => setForm((prev) => ({ ...prev, utilityTargetTop10Score: value }))} />
              <NumberInput label="Same Doc Top3 점수" step="0.01" value={form.utilitySameDocTop3Score} onChange={(value) => setForm((prev) => ({ ...prev, utilitySameDocTop3Score: value }))} />
              <NumberInput label="Same Doc Top5 점수" step="0.01" value={form.utilitySameDocTop5Score} onChange={(value) => setForm((prev) => ({ ...prev, utilitySameDocTop5Score: value }))} />
              <NumberInput label="Outside Top5 점수" step="0.01" value={form.utilityOutsideTop5Score} onChange={(value) => setForm((prev) => ({ ...prev, utilityOutsideTop5Score: value }))} />
              <NumberInput label="멀티 부분 보너스" step="0.01" value={form.utilityMultiPartialBonus} onChange={(value) => setForm((prev) => ({ ...prev, utilityMultiPartialBonus: value }))} />
              <NumberInput label="멀티 전체 보너스" step="0.01" value={form.utilityMultiFullBonus} onChange={(value) => setForm((prev) => ({ ...prev, utilityMultiFullBonus: value }))} />
              <NumberInput label="Utility 임계치" step="0.01" value={form.utilityThreshold} onChange={(value) => setForm((prev) => ({ ...prev, utilityThreshold: value }))} />
            </StageCard>
            <StageCard title="Diversity" checked={form.enableDiversity} onToggle={(checked) => setForm((prev) => ({ ...prev, enableDiversity: checked }))}>
              <NumberInput label="Diversity 가중치" step="0.01" value={form.diversityWeight} onChange={(value) => setForm((prev) => ({ ...prev, diversityWeight: value }))} />
              <NumberInput label="동일 청크 임계치" step="0.01" value={form.diversityThresholdSameChunk} onChange={(value) => setForm((prev) => ({ ...prev, diversityThresholdSameChunk: value }))} />
              <NumberInput label="동일 문서 임계치" step="0.01" value={form.diversityThresholdSameDoc} onChange={(value) => setForm((prev) => ({ ...prev, diversityThresholdSameDoc: value }))} />
            </StageCard>
          </div>
          <label className="filter-field filter-field--small">최종 점수 임계치
            <input type="number" step="0.01" min="0" max="1" value={form.finalScoreThreshold} onChange={(event) => setForm((prev) => ({ ...prev, finalScoreThreshold: event.target.value }))} />
          </label>
          <div className="filter-field filter-field--small"><button type="submit" className="button button--primary" disabled={!form.generationBatchId}>게이팅 실행</button></div>
        </form>
      </section>

      <section className="table-shell">
        <div className="table-header"><div className="table-title">게이팅 배치 이력</div><button type="button" className="button" onClick={() => Promise.all([loadGatingBatches(), loadLlmJobs()]).catch((error) => notify(error.message, 'error'))}>새로고침</button></div>
        <div className="table-wrap">
          <table className="data-table">
            <thead><tr><th>게이팅 배치 ID</th><th>프리셋</th><th>방식</th><th>상태</th><th>처리 수</th><th>승인 수</th><th>승인률</th><th>상세</th></tr></thead>
            <tbody>
              {pagedGatingBatches.map((batch) => {
                const acceptance = batch.processedCount > 0 ? ((batch.acceptedCount / batch.processedCount) * 100).toFixed(1) : '0.0'
                return (
                  <tr key={batch.gatingBatchId}>
                    <td><IdBadge value={batch.gatingBatchId} /></td><td>{batch.gatingPreset}</td><td>{batch.methodCode || '-'}</td><td><StatusBadge value={batch.status} /></td>
                    <td>{batch.processedCount ?? 0}</td><td>{batch.acceptedCount ?? 0}</td><td>{acceptance}%</td>
                    <td><button type="button" className="button button--ghost" onClick={() => openBatchDetail(batch)}>상세 조회</button></td>
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
            disabled={currentGatingBatchPage === 0}
            onClick={() => setGatingBatchPage((prev) => Math.max(0, prev - 1))}
          >이전</button>
          <div className="pagination__label">페이지 {currentGatingBatchPage + 1}</div>
          <button
            type="button"
            className="button"
            disabled={currentGatingBatchPage + 1 >= gatingBatchTotalPages}
            onClick={() => setGatingBatchPage((prev) => Math.min(gatingBatchTotalPages - 1, prev + 1))}
          >다음</button>
        </div>
      </section>

      <LlmJobsTable jobs={llmJobs} onAction={executeLlmAction} onDetail={openJobDetail} />

      <section className="table-shell">
        <div className="table-header"><div className="table-title">게이팅 퍼널</div></div>
        <form className="filter-bar" onSubmit={applyFunnelFilter}>
          <label className="filter-field">생성 방식
            <select value={funnelFilter.methodCode} onChange={(event) => setFunnelFilter((prev) => ({ ...prev, methodCode: event.target.value, generationBatchId: '', gatingBatchId: '' }))}>
              <option value="" disabled>생성 방식 선택</option>{methods.map((method) => <option key={method.methodCode} value={method.methodCode}>{method.methodCode}</option>)}
            </select>
          </label>
          <label className="filter-field">생성 배치
            <select value={funnelFilter.generationBatchId} disabled={!funnelFilter.methodCode} onChange={(event) => setFunnelFilter((prev) => ({ ...prev, generationBatchId: event.target.value, gatingBatchId: '' }))}>
              <option value="" disabled>생성 배치 선택</option>{funnelBatchOptions.map((batch) => <option key={batch.batchId} value={batch.batchId}>{batch.versionName} ({batch.methodCode})</option>)}
            </select>
          </label>
          <label className="filter-field">게이팅 배치
            <select value={funnelFilter.gatingBatchId} onChange={(event) => setFunnelFilter((prev) => ({ ...prev, gatingBatchId: event.target.value }))}>
              <option value="">최신 배치</option>{funnelGatingBatchOptions.map((batch) => <option key={batch.gatingBatchId} value={batch.gatingBatchId}>{shortId(batch.gatingBatchId)} ({batch.gatingPreset}, {batch.status || '-'})</option>)}
            </select>
          </label>
          <div className="filter-field filter-field--small"><button type="submit" className="button button--primary" disabled={!isFilterReady(funnelFilter) || !funnelReferenceBatchId}>조회</button></div>
        </form>
        <section className="summary-grid">
          {funnelCards.map((card) => (
            <article className="summary-card" key={card.label}>
              <div className="summary-card__label">{card.label}</div>
              <div className="summary-card__value">{card.value}</div>
            </article>
          ))}
        </section>
      </section>

      <section className="table-shell">
        <div className="table-header"><div className="table-title">질의별 게이팅 결과</div></div>
        <form className="filter-bar" onSubmit={applyResultFilter}>
          <label className="filter-field">생성 방식
            <select value={resultFilter.methodCode} onChange={(event) => setResultFilter((prev) => ({ ...prev, methodCode: event.target.value, generationBatchId: '', gatingBatchId: '' }))}>
              <option value="" disabled>생성 방식 선택</option>{methods.map((method) => <option key={method.methodCode} value={method.methodCode}>{method.methodCode}</option>)}
            </select>
          </label>
          <label className="filter-field">생성 배치
            <select value={resultFilter.generationBatchId} disabled={!resultFilter.methodCode} onChange={(event) => setResultFilter((prev) => ({ ...prev, generationBatchId: event.target.value, gatingBatchId: '' }))}>
              <option value="" disabled>생성 배치 선택</option>{resultBatchOptions.map((batch) => <option key={batch.batchId} value={batch.batchId}>{batch.versionName} ({batch.methodCode})</option>)}
            </select>
          </label>
          <label className="filter-field">게이팅 배치
            <select value={resultFilter.gatingBatchId} onChange={(event) => setResultFilter((prev) => ({ ...prev, gatingBatchId: event.target.value }))}>
              <option value="">최신 배치</option>{resultGatingBatchOptions.map((batch) => <option key={batch.gatingBatchId} value={batch.gatingBatchId}>{shortId(batch.gatingBatchId)} ({batch.gatingPreset}, {batch.status || '-'})</option>)}
            </select>
          </label>
          <label className="filter-field">통과 단계
            <select value={resultFilter.passStage} onChange={(event) => setResultFilter((prev) => ({ ...prev, passStage: event.target.value }))}>
              <option value="">전체</option>
              <option value="failed_rule">Rule 탈락</option>
              <option value="passed_rule">Rule 통과 -> LLM 탈락</option>
              <option value="passed_llm">LLM 통과 -> Utility 탈락</option>
              <option value="passed_utility">Utility 통과 -> Diversity 탈락</option>
              <option value="passed_diversity">Diversity 통과 -> Final 탈락</option>
              <option value="passed_all">전체 통과</option>
            </select>
          </label>
          <div className="filter-field filter-field--small"><button type="submit" className="button button--primary" disabled={!isFilterReady(resultFilter) || !resultReferenceBatchId}>조회</button></div>
        </form>
        <div className="table-wrap">
          <table className="data-table">
            <thead><tr><th>질의 ID</th><th>질의 문장</th><th>유형</th><th>Rule</th><th>LLM</th><th>Utility</th><th>Diversity</th><th>Final</th><th>리젝트 단계</th><th>리젝트 사유</th><th>최종</th></tr></thead>
            <tbody>
              {results.map((row) => (
                <tr key={row.syntheticQueryId}>
                  <td><IdBadge value={row.syntheticQueryId} plain /></td><td>{row.queryText}</td><td>{renderTokenBadges(row.queryType, 'query')}</td>
                  <td>{renderStageStatus(row.passedRule)}</td>
                  <td>{renderStageStatus(row.passedLlm)}</td>
                  <td>{renderStageStatus(row.passedUtility)}</td>
                  <td>{renderStageStatus(row.passedDiversity)}</td>
                  <td>{row.finalScore == null ? '-' : Number(row.finalScore).toFixed(4)}</td><td>{renderTokenBadges(row.rejectedStage, 'stage')}</td><td>{renderTokenBadges(row.rejectedReason, 'reason')}</td>
                  <td>{row.finalDecision ? <StatusBadge value="success" label="승인" /> : <StatusBadge value="failed" label="거절" />}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="pagination">
          <button
            type="button"
            className="button"
            disabled={!resultReferenceBatchId || resultPage === 0}
            onClick={() => {
              const nextPage = Math.max(0, resultPage - 1)
              setResultPage(nextPage)
              loadResults(resultReferenceBatchId, nextPage, resultFilter.methodCode, resultFilter.passStage).catch((error) => notify(error.message, 'error'))
            }}
          >이전</button>
          <div className="pagination__label">페이지 {resultPage + 1}</div>
          <button
            type="button"
            className="button"
            disabled={!resultReferenceBatchId || !resultHasNextPage}
            onClick={() => {
              const nextPage = resultPage + 1
              setResultPage(nextPage)
              loadResults(resultReferenceBatchId, nextPage, resultFilter.methodCode, resultFilter.passStage).catch((error) => notify(error.message, 'error'))
            }}
          >다음</button>
        </div>
      </section>

      <Modal data={modal} onClose={() => setModal(null)} />
    </>
  )
}
