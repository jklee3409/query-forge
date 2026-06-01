import { useEffect, useMemo, useState } from 'react'
import {
  BalanceBar,
  ConfigSummaryCard,
  ExperimentSection,
  SectionHeader,
} from '../components/AdminUi.jsx'
import { DetailCard, IdBadge, Modal, StatusBadge } from '../components/Common.jsx'
import { LlmJobsTable } from '../components/LlmJobsTable.jsx'
import { RemainingEta } from '../components/RemainingEta.jsx'
import { SelectDropdown } from '../components/SelectDropdown.jsx'
import { appendQuery, requestJson, toNumber } from '../lib/api.js'
import { fmtTime, shortId } from '../lib/format.js'

const RETRIEVER_MODE_PRESETS = {
  bm25_only: {
    denseEmbeddingRequired: false,
    denseFallbackEnabled: false,
    retrieverRerankEnabled: false,
    retrieverCandidatePoolK: '',
    retrieverDenseWeight: '',
    retrieverBm25Weight: '',
    retrieverTechnicalWeight: '',
  },
  dense_only: {
    denseEmbeddingRequired: true,
    denseFallbackEnabled: false,
    retrieverRerankEnabled: false,
    retrieverCandidatePoolK: '',
    retrieverDenseWeight: '',
    retrieverBm25Weight: '',
    retrieverTechnicalWeight: '',
  },
  hybrid: {
    denseEmbeddingRequired: true,
    denseFallbackEnabled: false,
    retrieverRerankEnabled: false,
    retrieverCandidatePoolK: '',
    retrieverDenseWeight: '',
    retrieverBm25Weight: '',
    retrieverTechnicalWeight: '',
  },
}

function numberString(value, fallback = '') {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed.toFixed(2) : fallback
}

function integerString(value, fallback = '') {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? String(Math.round(parsed)) : fallback
}

function parameterDefault(ranges, key, fallback) {
  const value = ranges?.[key]?.defaultValue ?? ranges?.[key]?.default ?? fallback
  return value == null ? fallback : value
}

function serverDefaultValue(current, fallbackValue, nextValue) {
  return current == null || current === '' || String(current) === String(fallbackValue)
    ? String(nextValue)
    : current
}

function retrieverPresetForMode(mode, denseEmbeddingModel = '', modeDefaults = {}) {
  if (!mode) {
    return {
      retrieverMode: '',
      denseEmbeddingModel,
      denseEmbeddingRequired: null,
      denseFallbackEnabled: null,
      retrieverRerankEnabled: null,
      retrieverCandidatePoolK: '',
      retrieverDenseWeight: '',
      retrieverBm25Weight: '',
      retrieverTechnicalWeight: '',
    }
  }
  const normalizedMode = (modeDefaults?.[mode] || RETRIEVER_MODE_PRESETS[mode]) ? mode : 'bm25_only'
  const fallback = RETRIEVER_MODE_PRESETS[normalizedMode] || RETRIEVER_MODE_PRESETS.bm25_only
  const serverPreset = modeDefaults?.[normalizedMode] || {}
  const weights = serverPreset.retriever_fusion_weights || serverPreset.retrieverFusionWeights || {}
  return {
    retrieverMode: normalizedMode,
    denseEmbeddingModel,
    denseEmbeddingRequired: serverPreset.dense_embedding_required ?? serverPreset.denseEmbeddingRequired ?? fallback.denseEmbeddingRequired,
    denseFallbackEnabled: serverPreset.dense_fallback_enabled ?? serverPreset.denseFallbackEnabled ?? fallback.denseFallbackEnabled,
    retrieverRerankEnabled: serverPreset.rerank_enabled ?? serverPreset.rerankEnabled ?? fallback.retrieverRerankEnabled,
    retrieverCandidatePoolK: integerString(
      serverPreset.retriever_candidate_pool_k ?? serverPreset.retrieverCandidatePoolK,
      fallback.retrieverCandidatePoolK,
    ),
    retrieverDenseWeight: numberString(weights.dense, fallback.retrieverDenseWeight),
    retrieverBm25Weight: numberString(weights.bm25, fallback.retrieverBm25Weight),
    retrieverTechnicalWeight: numberString(weights.technical, fallback.retrieverTechnicalWeight),
  }
}

function retrieverModeLabel(mode) {
  if (mode === 'bm25_only') return 'BM25 Only'
  if (mode === 'dense_only') return 'Dense Only'
  if (mode === 'hybrid') return 'Hybrid'
  return mode || '-'
}

const SPRING_TECHDOC_METHOD_CODES = ['A', 'B', 'C', 'D', 'E']
const PYTHON_KR_METHOD_CODES = ['F', 'G']
const ENGLISH_SYNTHETIC_METHOD_CODES = new Set(['E', 'F'])

function normalizeStrategyHint(value) {
  return String(value || '').trim().toLowerCase()
}

function normalizeStrategyMethodCode(value) {
  return String(value || '').trim().toUpperCase()
}

function normalizeEvalQueryLanguage(value) {
  return String(value || '').trim().toLowerCase() === 'en' ? 'en' : 'ko'
}

function methodMatchesEvalLanguage(methodCode, evalQueryLanguage) {
  const normalizedMethod = normalizeStrategyMethodCode(methodCode)
  const normalizedLanguage = normalizeEvalQueryLanguage(evalQueryLanguage)
  return ENGLISH_SYNTHETIC_METHOD_CODES.has(normalizedMethod) === (normalizedLanguage === 'en')
}

function resolveDatasetQueryLanguage(dataset) {
  if (!dataset) return 'ko'
  const explicitLanguage = normalizeStrategyHint(
    dataset.queryLanguage || dataset.metadataQueryLanguage || dataset.metadata?.query_language,
  )
  if (explicitLanguage === 'en') return 'en'
  if (explicitLanguage === 'ko') return 'ko'

  const datasetKey = normalizeStrategyHint(dataset.datasetKey)
  const datasetName = normalizeStrategyHint(dataset.datasetName)
  const hint = `${datasetKey} ${datasetName}`
  if (/(^|[_\s-])en([_\s-]|$)/.test(hint) || hint.includes('english')) return 'en'
  return 'ko'
}

function resolveDatasetAllowedMethodCodes(dataset) {
  if (!dataset) return null
  const datasetKey = normalizeStrategyHint(dataset.datasetKey)
  const datasetName = normalizeStrategyHint(dataset.datasetName)
  const strategyProfile = normalizeStrategyHint(
    dataset.strategyProfile || dataset.metadataStrategyProfile || dataset.metadata?.strategy_profile,
  )
  const hint = `${datasetKey} ${datasetName} ${strategyProfile}`
  if (strategyProfile === 'python_kr' || datasetKey.includes('python_kr') || hint.includes('python kr')) {
    return PYTHON_KR_METHOD_CODES
  }
  if (
    strategyProfile === 'spring_techdoc'
    || datasetKey === 'human_eval_default'
    || datasetKey.startsWith('human_eval_short_user')
    || hint.includes('build-eval-dataset')
    || hint.includes('spring')
  ) {
    return SPRING_TECHDOC_METHOD_CODES
  }
  return null
}

function isSnapshotAllowedForMethodSet(batch, allowedMethodSet) {
  if (!allowedMethodSet) return true
  const methodCode = normalizeStrategyMethodCode(batch?.methodCode)
  return Boolean(methodCode && allowedMethodSet.has(methodCode))
}

function snapshotMatchesEvalLanguage(batch, evalQueryLanguage) {
  const methodCode = normalizeStrategyMethodCode(batch?.methodCode)
  return !methodCode || methodMatchesEvalLanguage(methodCode, evalQueryLanguage)
}

const RETRIEVAL_METRIC_DEFS = [
  { key: 'recall_at_5', label: 'Recall@5', max: 1, precision: 3, trend: 'higher', priority: 'core' },
  { key: 'hit_at_5', label: 'Hit@5', max: 1, precision: 3, trend: 'higher', priority: 'core' },
  { key: 'mrr_at_10', label: 'MRR@10', max: 1, precision: 3, trend: 'higher', priority: 'core' },
  { key: 'ndcg_at_10', label: 'nDCG@10', max: 1, precision: 3, trend: 'higher', priority: 'core' },
]

const ANSWER_METRIC_DEFS = [
  { key: 'correctness', label: 'Correctness (정확성)', max: 1, precision: 3, trend: 'higher' },
  { key: 'grounding', label: 'Grounding (근거 충실도)', max: 1, precision: 3, trend: 'higher' },
  { key: 'faithfulness', label: 'Faithfulness (사실 충실도)', max: 1, precision: 3, trend: 'higher' },
  { key: 'context_recall', label: 'Context Recall (문맥 재현율)', max: 1, precision: 3, trend: 'higher' },
]

const LEGACY_PERFORMANCE_MESSAGE = '레거시 결과: 신규 지연 시간 지표 없음'

const PERFORMANCE_METRIC_DEFS = [
  {
    key: 'avg_query_eval_total_latency_ms',
    label: '질의 전체 평가 평균 시간',
    precision: 2,
    unit: 'ms',
    displayUnit: 's',
    displayScale: 0.001,
    trend: 'lower',
    priority: 'core',
    sampleCountKey: 'eval_sample_count',
    sampleCountLabel: '전체 평가 성공 샘플 기준',
    description: '질의 입력부터 최종 평가 결과 생성 완료까지의 평균 시간',
  },
  {
    key: 'avg_final_rewrite_latency_ms',
    label: '최종 재작성 확정 평균 시간',
    precision: 2,
    unit: 'ms',
    displayUnit: 's',
    displayScale: 0.001,
    trend: 'lower',
    priority: 'core',
    sampleCountKey: 'rewrite_sample_count',
    sampleCountLabel: '재작성 확정 샘플 기준',
    description: '최종 재작성 질의 확정까지 걸린 평균 시간',
  },
  {
    key: 'avg_pure_rewrite_latency_ms',
    label: '순수 질의 재작성 평균 시간',
    precision: 2,
    unit: 'ms',
    displayUnit: 's',
    displayScale: 0.001,
    trend: 'lower',
    priority: 'core',
    sampleCountKey: 'pure_rewrite_sample_count',
    sampleCountLabel: '순수 재작성 호출 샘플 기준',
    description: '순수 LLM 재작성 호출 시간 평균',
  },
]

const ANCHOR_METRIC_DEFS = [
  { key: 'anchor_precision', label: 'Anchor Precision', max: 1, precision: 3, trend: 'higher', priority: 'core', sampleCountKey: 'anchor_evaluated_sample_count', sampleCountLabel: 'evaluated samples' },
  { key: 'grounded_anchor_rate', label: 'Grounded Anchor Rate', max: 1, precision: 3, trend: 'higher', priority: 'core', sampleCountKey: 'anchor_evaluated_sample_count', sampleCountLabel: 'evaluated samples' },
  { key: 'added_anchor_grounded_rate', label: 'Added Anchor Grounded Rate', max: 1, precision: 3, trend: 'higher', sampleCountKey: 'anchor_evaluated_sample_count', sampleCountLabel: 'evaluated samples' },
  { key: 'risky_anchor_rate', label: 'Risky Anchor Rate', max: 1, precision: 3, trend: 'lower', priority: 'core', sampleCountKey: 'anchor_evaluated_sample_count', sampleCountLabel: 'evaluated samples' },
  { key: 'avg_anchor_relevance_score', label: 'Avg Anchor Relevance Score', max: 1, precision: 3, trend: 'higher', sampleCountKey: 'anchor_evaluated_sample_count', sampleCountLabel: 'evaluated samples' },
  { key: 'anchor_supported_rewrite_rate', label: 'Anchor Supported Rewrite Rate', max: 1, precision: 3, trend: 'higher', sampleCountKey: 'rewrite_applied_sample_count', sampleCountLabel: 'rewrite applied samples' },
  { key: 'anchor_evaluated_sample_count', label: 'Evaluated Samples', precision: 0, trend: 'higher' },
  { key: 'useful_anchor_count', label: 'Useful Anchor Count', precision: 0, trend: 'higher' },
  { key: 'risky_anchor_count', label: 'Risky Anchor Count', precision: 0, trend: 'lower' },
  { key: 'unsupported_anchor_count', label: 'Unsupported Anchor Count', precision: 0, trend: 'lower' },
]

const METRIC_GROUP_DEFS = [
  { key: 'retrieval', label: '검색 품질', description: '검색 품질 지표', metrics: RETRIEVAL_METRIC_DEFS },
  { key: 'answer', label: '답변 품질', description: '답변 품질 지표', metrics: ANSWER_METRIC_DEFS },
  { key: 'performance', label: '성능', description: '지연 시간 요약', metrics: PERFORMANCE_METRIC_DEFS },
  { key: 'anchor', label: 'Anchor Quality', description: 'Rewrite anchor grounding and drift risk', metrics: ANCHOR_METRIC_DEFS },
]
const METRIC_META_MAP = METRIC_GROUP_DEFS.reduce((acc, group) => {
  for (const metric of group.metrics) {
    acc[metric.key] = {
      ...metric,
      groupKey: group.key,
      groupLabel: group.label,
    }
  }
  return acc
}, {})

const COMPARE_FOCUS_RETRIEVAL_KEYS = ['recall_at_5', 'hit_at_5', 'mrr_at_10', 'ndcg_at_10']
const COMPARE_FOCUS_LATENCY_KEYS = [
  'avg_query_eval_total_latency_ms',
  'avg_final_rewrite_latency_ms',
  'avg_pure_rewrite_latency_ms',
]
const KPI_METRIC_KEYS = new Set([
  'recall_at_5',
  'hit_at_5',
  'mrr_at_10',
  'ndcg_at_10',
  'avg_query_eval_total_latency_ms',
  'avg_final_rewrite_latency_ms',
  'avg_pure_rewrite_latency_ms',
  'anchor_precision',
  'grounded_anchor_rate',
  'risky_anchor_rate',
])

const METRIC_TREND_LABEL = {
  higher: '',
  lower: '',
}

const TABLE_NUMBER_FORMATTERS = new Map()

function parseMetricsNode(value) {
  if (!value) return {}
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value)
      return parsed && typeof parsed === 'object' ? parsed : {}
    } catch {
      return {}
    }
  }
  return typeof value === 'object' ? value : {}
}

function toMetricNumber(value) {
  if (value == null) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

function firstMetricNumber(values) {
  for (const value of values) {
    const parsed = toMetricNumber(value)
    if (parsed != null) return parsed
  }
  return null
}

const RUN_METRIC_MODE_PRIORITY = [
  'selective_rewrite_with_session',
  'selective_rewrite',
  'rewrite_always',
  'raw_only',
  'memory_only_full_gating',
  'memory_only_gated',
  'memory_only_rule_only',
  'memory_only_ungated',
]

function pickRunMetricMode(byMode) {
  for (const mode of RUN_METRIC_MODE_PRIORITY) {
    if (byMode?.[mode]) return mode
  }
  const fallback = Object.keys(byMode || {})[0]
  return fallback || ''
}

function hasNewPerformanceMetrics(performancePayload) {
  if (!performancePayload || typeof performancePayload !== 'object') return false
  return PERFORMANCE_METRIC_DEFS.some((metric) => Object.prototype.hasOwnProperty.call(performancePayload, metric.key))
    || ['eval_sample_count', 'rewrite_sample_count', 'pure_rewrite_sample_count', 'excluded_sample_count']
      .some((key) => Object.prototype.hasOwnProperty.call(performancePayload, key))
}

function extractRunMetrics(metricsJson) {
  const payload = parseMetricsNode(metricsJson)
  const retrievalPayload = parseMetricsNode(payload.retrieval || payload.metrics_json?.retrieval || payload)
  const answerPayload = parseMetricsNode(payload.answer || payload.metrics_json?.answer)
  const performancePayload = parseMetricsNode(payload.performance || payload.metrics_json?.performance)
  const anchorPayload = parseMetricsNode(
    payload.anchor_evaluation
      || payload.anchorEvaluation
      || payload.metrics_json?.anchor_evaluation
      || payload.metrics_json?.anchorEvaluation,
  )
  const answerSummary = parseMetricsNode(answerPayload.summary || answerPayload)
  const summaryRaw = Array.isArray(retrievalPayload.summary) ? retrievalPayload.summary : []
  const byMode = summaryRaw.reduce((acc, row) => {
    const mode = String(row?.mode || '')
    if (!mode) return acc
    acc[mode] = row
    return acc
  }, {})
  const selectedMode = pickRunMetricMode(byMode)
  const summary = byMode[selectedMode] || {}
  const legacyPerformance = !hasNewPerformanceMetrics(performancePayload)
  return {
    selected_mode: summary.mode || selectedMode || '-',
    by_mode: byMode,
    recall_at_5: firstMetricNumber([payload.recall_at_5, payload.recallAt5, summary.recall_at_5, summary['recall@5']]),
    hit_at_5: firstMetricNumber([payload.hit_at_5, payload.hitAt5, summary.hit_at_5, summary['hit@5']]),
    mrr_at_10: firstMetricNumber([payload.mrr_at_10, payload.mrrAt10, summary.mrr_at_10, summary['mrr@10']]),
    ndcg_at_10: firstMetricNumber([payload.ndcg_at_10, payload.ndcgAt10, summary.ndcg_at_10, summary['ndcg@10']]),
    correctness: firstMetricNumber([payload.correctness, answerSummary.correctness]),
    grounding: firstMetricNumber([payload.grounding, answerSummary.grounding]),
    hallucination_rate: firstMetricNumber([payload.hallucination_rate, answerSummary.hallucination_rate]),
    answer_relevance: firstMetricNumber([payload.answer_relevance, answerSummary.answer_relevance]),
    faithfulness: firstMetricNumber([payload.faithfulness, answerSummary.faithfulness]),
    context_recall: firstMetricNumber([payload.context_recall, answerSummary.context_recall]),
    avg_query_eval_total_latency_ms: firstMetricNumber([performancePayload.avg_query_eval_total_latency_ms]),
    avg_final_rewrite_latency_ms: firstMetricNumber([performancePayload.avg_final_rewrite_latency_ms]),
    avg_pure_rewrite_latency_ms: firstMetricNumber([performancePayload.avg_pure_rewrite_latency_ms]),
    eval_sample_count: firstMetricNumber([performancePayload.eval_sample_count]),
    rewrite_sample_count: firstMetricNumber([performancePayload.rewrite_sample_count]),
    pure_rewrite_sample_count: firstMetricNumber([performancePayload.pure_rewrite_sample_count]),
    excluded_sample_count: firstMetricNumber([performancePayload.excluded_sample_count]),
    anchor_precision: firstMetricNumber([anchorPayload.anchor_precision, anchorPayload.anchorPrecision]),
    grounded_anchor_rate: firstMetricNumber([anchorPayload.grounded_anchor_rate, anchorPayload.groundedAnchorRate]),
    added_anchor_grounded_rate: firstMetricNumber([anchorPayload.added_anchor_grounded_rate, anchorPayload.addedAnchorGroundedRate]),
    risky_anchor_rate: firstMetricNumber([anchorPayload.risky_anchor_rate, anchorPayload.riskyAnchorRate]),
    avg_anchor_relevance_score: firstMetricNumber([anchorPayload.avg_anchor_relevance_score, anchorPayload.avgAnchorRelevanceScore]),
    anchor_supported_rewrite_rate: firstMetricNumber([anchorPayload.anchor_supported_rewrite_rate, anchorPayload.anchorSupportedRewriteRate]),
    rewrite_applied_sample_count: firstMetricNumber([anchorPayload.rewrite_applied_sample_count, anchorPayload.rewriteAppliedSampleCount]),
    anchor_evaluated_sample_count: firstMetricNumber([anchorPayload.anchor_evaluated_sample_count, anchorPayload.anchorEvaluatedSampleCount]),
    useful_anchor_count: firstMetricNumber([anchorPayload.useful_anchor_count, anchorPayload.usefulAnchorCount]),
    risky_anchor_count: firstMetricNumber([anchorPayload.risky_anchor_count, anchorPayload.riskyAnchorCount]),
    unsupported_anchor_count: firstMetricNumber([anchorPayload.unsupported_anchor_count, anchorPayload.unsupportedAnchorCount]),
    legacy_performance: legacyPerformance,
    legacy_performance_message: legacyPerformance ? LEGACY_PERFORMANCE_MESSAGE : '',
  }
}

function formatMetric(value) {
  if (value == null) return '-'
  return Number(value).toFixed(3)
}

const RUN_DETAIL_REWRITE_MODE_PRIORITY = [
  'selective_rewrite_with_session',
  'selective_rewrite',
  'rewrite_always',
  'memory_only_gated',
  'memory_only_full_gating',
  'memory_only_rule_only',
  'memory_only_ungated',
]

const RUN_DETAIL_COMPARISON_METRICS = [
  { label: 'Recall@5', aliases: ['recall@5', 'recall_at_5'], precision: 4 },
  { label: 'Hit@5', aliases: ['hit@5', 'hit_at_5'], precision: 4 },
  { label: 'MRR@10', aliases: ['mrr@10', 'mrr_at_10'], precision: 4 },
  { label: 'nDCG@10', aliases: ['ndcg@10', 'ndcg_at_10'], precision: 4 },
  { label: 'Adoption rate', aliases: ['adoption_rate'], precision: 4, rewriteOnly: true },
  { label: 'Bad rewrite rate', aliases: ['bad_rewrite_rate'], precision: 4, rewriteOnly: true },
  { label: 'MRR gain', aliases: ['rewrite_gain_mrr'], precision: 4, rewriteOnly: true },
  { label: 'nDCG gain', aliases: ['rewrite_gain_ndcg'], precision: 4, rewriteOnly: true },
  { label: 'Confidence delta', aliases: ['avg_confidence_delta'], precision: 4, rewriteOnly: true },
]

function normalizeModeName(row) {
  return String(row?.mode || '').trim()
}

function metricFromRow(row, aliases) {
  if (!row || !Array.isArray(aliases)) return null
  for (const alias of aliases) {
    const value = toMetricNumber(row[alias])
    if (value != null) return value
  }
  return null
}

function resolveRunDetailRewriteRow(rows) {
  const normalizedRows = Array.isArray(rows) ? rows : []
  for (const mode of RUN_DETAIL_REWRITE_MODE_PRIORITY) {
    const row = normalizedRows.find((item) => normalizeModeName(item) === mode)
    if (row) return row
  }
  return null
}

function isQueryRewriteMode(mode) {
  return ['selective_rewrite_with_session', 'selective_rewrite', 'rewrite_always'].includes(mode)
}

function renderRunDetailModeSummary(retrievalByModeRows) {
  const rows = (Array.isArray(retrievalByModeRows) ? retrievalByModeRows : [])
    .filter((row) => row && typeof row === 'object' && normalizeModeName(row))
  if (!rows.length) return null

  const byMode = rows.reduce((acc, row) => {
    acc[normalizeModeName(row)] = row
    return acc
  }, {})
  const orderedModes = [
    'raw_only',
    'rewrite_always',
    'selective_rewrite',
    'selective_rewrite_with_session',
    'memory_only_gated',
    'memory_only_full_gating',
    'memory_only_rule_only',
    'memory_only_ungated',
  ]
  const orderedRows = [
    ...orderedModes.map((mode) => byMode[mode]).filter(Boolean),
    ...rows.filter((row) => !orderedModes.includes(normalizeModeName(row))),
  ]

  return (
    <section className="run-mode-compare">
      <div className="run-mode-compare__header">
        <div>
          <strong>모드별 검색 요약</strong>
          <div className="run-mode-compare__subtitle">표시 값은 모드별 지표입니다.</div>
        </div>
      </div>
      <div className="summary-grid">
        {orderedRows.map((row) => {
          const mode = normalizeModeName(row)
          return (
            <article key={mode} className="summary-card">
              <div className="summary-card__label">{mode}</div>
              <div className="summary-card__value">R5 {metricFromRow(row, ['recall@5', 'recall_at_5'])?.toFixed(4) ?? '-'}</div>
              <div className="summary-card__meta">H5 {metricFromRow(row, ['hit@5', 'hit_at_5'])?.toFixed(4) ?? '-'}</div>
              <div className="summary-card__meta">MRR10 {metricFromRow(row, ['mrr@10', 'mrr_at_10'])?.toFixed(4) ?? '-'}</div>
              <div className="summary-card__meta">nDCG10 {metricFromRow(row, ['ndcg@10', 'ndcg_at_10'])?.toFixed(4) ?? '-'}</div>
            </article>
          )
        })}
      </div>
    </section>
  )
}

function renderRunDetailModeComparison(retrievalByModeRows) {
  const rows = Array.isArray(retrievalByModeRows) ? retrievalByModeRows : []
  const rawRow = rows.find((row) => normalizeModeName(row) === 'raw_only')
  const rewriteRow = resolveRunDetailRewriteRow(rows)
  if (!rawRow && !rewriteRow) return null

  const baselineOnly = !rewriteRow
  const rewriteMode = normalizeModeName(rewriteRow)
  const comparisonLabel = isQueryRewriteMode(rewriteMode) ? '원본 vs 질의 재작성' : '원본 vs 합성 메모리'
  const comparisonRows = RUN_DETAIL_COMPARISON_METRICS
    .map((metric) => {
      const raw = metric.rewriteOnly ? null : metricFromRow(rawRow, metric.aliases)
      const rewrite = rewriteRow ? metricFromRow(rewriteRow, metric.aliases) : null
      const delta = raw != null && rewrite != null ? rewrite - raw : null
      return { ...metric, raw, rewrite, delta }
    })
    .filter((row) => row.raw != null || row.rewrite != null || row.delta != null)

  if (!comparisonRows.length) return null

  return (
    <article className="run-mode-compare">
      <div className="run-mode-compare__header">
        <div>
          <div className="detail-item__label">{baselineOnly ? 'Baseline 모드' : comparisonLabel}</div>
          <div className="run-mode-compare__subtitle">{baselineOnly ? 'raw_only' : `raw_only / ${rewriteMode}`}</div>
        </div>
        <span className="metric-chip metric-chip--core">{baselineOnly ? 'raw_only' : rewriteMode}</span>
      </div>
      <div className="run-mode-compare__table-wrap">
        <table className="run-mode-compare__table">
          <thead>
            <tr>
              <th>지표</th>
              <th>raw_only</th>
              {!baselineOnly && <th>{rewriteMode}</th>}
              {!baselineOnly && <th>차이</th>}
            </tr>
          </thead>
          <tbody>
            {comparisonRows.map((row) => {
              const deltaTone = row.delta == null ? 'neutral' : row.delta > 0 ? 'positive' : row.delta < 0 ? 'negative' : 'neutral'
              return (
                <tr key={row.label}>
                  <td>{row.label}</td>
                  <td>{row.raw == null ? '-' : Number(row.raw).toFixed(row.precision)}</td>
                  {!baselineOnly && <td>{row.rewrite == null ? '-' : Number(row.rewrite).toFixed(row.precision)}</td>}
                  {!baselineOnly && (
                    <td className={`run-mode-compare__delta run-mode-compare__delta--${deltaTone}`}>
                      {row.delta == null ? '-' : formatDelta(row.delta, { precision: row.precision })}
                    </td>
                  )}
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </article>
  )
}

function normalizeQueryText(value) {
  if (value == null) return '-'
  const normalized = String(value).trim()
  return normalized || '-'
}

function hasDetailPayload(value) {
  if (value == null) return false
  if (Array.isArray(value)) return value.length > 0
  if (typeof value === 'object') return Object.keys(value).length > 0
  return String(value).trim().length > 0
}

function parseDetailPayload(value) {
  if (typeof value !== 'string') return value
  const normalized = value.trim()
  if (!normalized) return null
  try {
    return JSON.parse(normalized)
  } catch {
    return normalized
  }
}

function isPlainDetailObject(value) {
  return value != null && typeof value === 'object' && !Array.isArray(value)
}

function detailArray(value, nestedKeys = []) {
  const payload = parseDetailPayload(value)
  if (Array.isArray(payload)) return payload
  if (isPlainDetailObject(payload)) {
    for (const key of nestedKeys) {
      if (Array.isArray(payload[key])) return payload[key]
    }
  }
  return []
}

function firstDetailValue(source, keys) {
  if (!isPlainDetailObject(source)) return null
  for (const key of keys) {
    const value = source[key]
    if (value !== null && value !== undefined && value !== '') return value
  }
  return null
}

function formatDetailNumber(value, precision = 3) {
  const number = Number(value)
  if (!Number.isFinite(number)) return '-'
  return number.toFixed(precision)
}

function formatDetailCompactValue(value) {
  if (value == null || value === '') return '-'
  if (typeof value === 'boolean') return value ? 'true' : 'false'
  if (typeof value === 'number') return Number.isInteger(value) ? String(value) : formatDetailNumber(value)
  if (Array.isArray(value)) return value.length ? `${value.length}개` : '-'
  if (typeof value === 'object') return Object.keys(value).length ? `${Object.keys(value).length} fields` : '-'
  return String(value)
}

function detailKeyLabel(key) {
  const labels = {
    mode: '모드',
    raw_mrr: 'Raw MRR',
    mode_mrr: 'Mode MRR',
    raw_ndcg: 'Raw nDCG',
    mode_ndcg: 'Mode nDCG',
    raw_confidence: '원본 신뢰도',
    best_candidate_confidence: '최고 후보 신뢰도',
    confidence_delta: '신뢰도 변화',
    rewrite_reason: '재작성 판단',
    final_rewrite_latency_ms: '최종 재작성 시간',
    pure_rewrite_latency_ms: '순수 재작성 시간',
    rewrite_mode: '재작성 모드',
    rewrite_anchor_injection_enabled: '앵커 주입',
  }
  return labels[key] || String(key).replaceAll('_', ' ')
}

function renderDetailEmpty() {
  return <div className="rag-detail-disclosure__empty">데이터 없음</div>
}

function renderDetailTokenList(values, maxItems = 12) {
  const items = Array.isArray(values) ? values.filter((item) => item != null && String(item).trim()) : []
  if (!items.length) return null
  return (
    <div className="rag-detail-token-list">
      {items.slice(0, maxItems).map((item, index) => (
        <span key={`${String(item)}-${index}`} className="rag-detail-token">{String(item)}</span>
      ))}
    </div>
  )
}

function isTruthyDetailValue(value) {
  if (value === true || value === 1) return true
  if (typeof value !== 'string') return false
  return ['true', '1', 'yes', 'on'].includes(value.trim().toLowerCase())
}

function canonicalAnchorItems(value) {
  const payload = parseDetailPayload(value)
  if (Array.isArray(payload)) return payload.filter(isPlainDetailObject)
  if (isPlainDetailObject(payload) && Array.isArray(payload.anchors)) {
    return payload.anchors.filter(isPlainDetailObject)
  }
  return []
}

function renderCanonicalAnchorDetail(candidate) {
  const anchors = canonicalAnchorItems(candidate?.canonical_anchors || candidate?.canonicalAnchors)
  if (!anchors.length) return null
  const scoringAnchors = anchors.filter((anchor) => isTruthyDetailValue(anchor.used_for_scoring ?? anchor.usedForScoring))
  const reviewAnchors = anchors.filter((anchor) => !isTruthyDetailValue(anchor.used_for_scoring ?? anchor.usedForScoring))
  const displayAnchors = [...scoringAnchors, ...reviewAnchors].slice(0, 5)
  return (
    <div className="rag-canonical-anchor-block">
      <div className="rag-canonical-anchor-block__header">
        <span>Canonical anchors</span>
        <strong>{scoringAnchors.length} scoring / {reviewAnchors.length} review</strong>
      </div>
      <div className="rag-canonical-anchor-list">
        {displayAnchors.map((anchor, index) => {
          const scoring = isTruthyDetailValue(anchor.used_for_scoring ?? anchor.usedForScoring)
          const canonical = firstDetailValue(anchor, ['canonical_form', 'canonicalForm']) || 'unresolved'
          const alias = firstDetailValue(anchor, ['display_alias', 'displayAlias', 'input_alias', 'inputAlias']) || '-'
          const confidence = firstDetailValue(anchor, ['confidence'])
          return (
            <div key={`${alias}-${canonical}-${index}`} className="rag-canonical-anchor" data-scoring={scoring ? 'true' : 'false'}>
              <div className="rag-canonical-anchor__main">
                <strong>{canonical}</strong>
              </div>
              <div className="rag-canonical-anchor__meta">
                <span>alias {alias}</span>
                {confidence != null && <span>conf {formatDetailNumber(confidence, 2)}</span>}
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

function renderGenericDetailValue(value) {
  const payload = parseDetailPayload(value)
  if (!hasDetailPayload(payload)) return renderDetailEmpty()
  if (Array.isArray(payload)) {
    return (
      <div className="rag-structured-list">
        {payload.map((item, index) => (
          <article key={index} className="rag-structured-card">
            {isPlainDetailObject(item) ? renderGenericDetailValue(item) : <p className="rag-structured-card__text">{String(item)}</p>}
          </article>
        ))}
      </div>
    )
  }
  if (isPlainDetailObject(payload)) {
    return (
      <div className="rag-detail-kv-grid">
        {Object.entries(payload).map(([key, item]) => (
          <div key={key} className="rag-detail-kv">
            <span>{detailKeyLabel(key)}</span>
            <strong>{formatDetailCompactValue(item)}</strong>
          </div>
        ))}
      </div>
    )
  }
  return <p className="rag-structured-card__text">{String(payload)}</p>
}

function renderMetricContributionDetail(value) {
  const payload = parseDetailPayload(value)
  if (!isPlainDetailObject(payload)) return renderGenericDetailValue(payload)
  const metricRows = [
    { label: 'MRR@10', raw: payload.raw_mrr, mode: payload.mode_mrr },
    { label: 'nDCG@10', raw: payload.raw_ndcg, mode: payload.mode_ndcg },
    { label: '신뢰도', raw: payload.raw_confidence, mode: payload.best_candidate_confidence, delta: payload.confidence_delta },
  ]
  const latencyItems = [
    { label: '최종 재작성', value: payload.final_rewrite_latency_ms, metricDef: PERFORMANCE_METRIC_DEFS[1] },
    { label: '순수 재작성', value: payload.pure_rewrite_latency_ms, metricDef: PERFORMANCE_METRIC_DEFS[2] },
  ].filter((item) => item.value != null)

  return (
    <div className="rag-detail-metric-panel">
      <div className="rag-detail-metric-panel__header">
        <span className="rag-detail-mode-chip">{payload.mode || '-'}</span>
        {payload.rewrite_reason && <span className="rag-detail-reason">{payload.rewrite_reason}</span>}
      </div>
      <div className="rag-detail-metric-grid">
        {metricRows.map((item) => {
          const delta = item.delta ?? (Number.isFinite(Number(item.raw)) && Number.isFinite(Number(item.mode)) ? Number(item.mode) - Number(item.raw) : null)
          const tone = delta == null || delta === 0 ? 'neutral' : delta > 0 ? 'positive' : 'negative'
          return (
            <div key={item.label} className="rag-detail-metric-card">
              <span>{item.label}</span>
              <strong>{formatDetailNumber(item.mode)}</strong>
              <small>raw {formatDetailNumber(item.raw)}</small>
              <em data-tone={tone}>{delta == null ? '-' : formatDelta(delta, { precision: 3 })}</em>
            </div>
          )
        })}
      </div>
      {latencyItems.length > 0 && (
        <div className="rag-detail-kv-grid rag-detail-kv-grid--compact">
          {latencyItems.map((item) => (
            <div key={item.label} className="rag-detail-kv">
              <span>{item.label}</span>
              <strong>{formatTableMetricValue(item.value, item.metricDef).main}</strong>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function renderMemoryCandidateDetail(value) {
  const candidates = detailArray(value, ['memory_top_n', 'top_memory_candidates', 'memory_candidates', 'candidates', 'items'])
  if (!candidates.length) return renderGenericDetailValue(value)
  return (
    <div className="rag-structured-list">
      {candidates.slice(0, 8).map((candidate, index) => {
        const queryText = firstDetailValue(candidate, ['query_text', 'queryText', 'query', 'text'])
        const score = firstDetailValue(candidate, ['similarity', 'score', 'bm25_score', 'bm25Score'])
        const product = firstDetailValue(candidate, ['product', 'source_product', 'sourceProduct'])
        const retriever = firstDetailValue(candidate, ['retriever', 'retriever_name', 'retrieverName'])
        const docId = firstDetailValue(candidate, ['target_doc_id', 'document_id', 'doc_id'])
        const memoryId = firstDetailValue(candidate, ['memory_id', 'memoryId'])
        const terms = detailArray(candidate?.glossary_terms || candidate?.anchor_terms || candidate?.terms)
        const candidateTags = [
          product,
          retriever,
          docId ? `doc ${shortId(docId)}` : null,
          memoryId ? `memory ${shortId(memoryId)}` : null,
          ...terms,
        ].filter((item) => item != null && String(item).trim())
        return (
          <article key={memoryId || `${queryText}-${index}`} className="rag-structured-card rag-structured-card--candidate">
            <div className="rag-structured-card__topline">
              <span className="rag-structured-rank">#{index + 1}</span>
              {score != null && <span className="rag-structured-score">score {formatDetailNumber(score)}</span>}
            </div>
            <p className="rag-structured-card__text">{queryText || '-'}</p>
            {renderDetailTokenList(candidateTags, 3)}
            {renderCanonicalAnchorDetail(candidate)}
          </article>
        )
      })}
    </div>
  )
}

function renderRewriteCandidateDetail(value) {
  const candidates = detailArray(value, ['rewrite_candidates', 'candidates', 'items'])
  if (!candidates.length) return renderGenericDetailValue(value)
  return (
    <div className="rag-structured-list">
      {candidates.slice(0, 8).map((candidate, index) => {
        const label = firstDetailValue(candidate, ['label', 'name', 'strategy']) || `candidate_${index + 1}`
        const query = firstDetailValue(candidate, ['query', 'query_text', 'text', 'rewrite_query'])
        const confidence = firstDetailValue(candidate, ['confidence', 'score', 'candidate_confidence'])
        const reason = firstDetailValue(candidate, ['reason', 'decision_reason', 'why'])
        return (
          <article key={`${label}-${index}`} className="rag-structured-card rag-structured-card--rewrite">
            <div className="rag-structured-card__topline">
              <span className="rag-detail-mode-chip">{label}</span>
              {confidence != null && <span className="rag-structured-score">confidence {formatDetailNumber(confidence)}</span>}
            </div>
            <p className="rag-structured-card__text">{query || '-'}</p>
            {reason && <div className="rag-structured-card__note">{reason}</div>}
          </article>
        )
      })}
    </div>
  )
}

function renderRetrievedChunkDetail(value) {
  const chunks = detailArray(value, ['retrieved_top_k', 'retrieved_chunks', 'chunks', 'items'])
  if (!chunks.length) return renderGenericDetailValue(value)
  return (
    <div className="rag-structured-list">
      {chunks.slice(0, 8).map((chunk, index) => {
        const chunkId = firstDetailValue(chunk, ['chunk_id', 'chunkId'])
        const documentId = firstDetailValue(chunk, ['document_id', 'documentId', 'doc_id'])
        const title = firstDetailValue(chunk, ['title', 'section_title', 'heading'])
        const content = firstDetailValue(chunk, ['content', 'text', 'chunk_text', 'snippet'])
        const score = firstDetailValue(chunk, ['score', 'rerank_score', 'retrieval_score'])
        return (
          <article key={chunkId || `${documentId}-${index}`} className="rag-structured-card rag-structured-card--chunk">
            <div className="rag-structured-card__topline">
              <span className="rag-structured-rank">#{index + 1}</span>
              {score != null && <span className="rag-structured-score">score {formatDetailNumber(score)}</span>}
            </div>
            <div className="rag-structured-card__meta">
              {documentId && <span>doc {shortId(documentId)}</span>}
              {chunkId && <span>chunk {shortId(chunkId)}</span>}
            </div>
            {title && <strong className="rag-structured-card__title">{title}</strong>}
            {content && <p className="rag-structured-card__snippet">{String(content).slice(0, 520)}</p>}
          </article>
        )
      })}
    </div>
  )
}

function anchorLabelTone(label) {
  const normalized = String(label || 'unknown').toLowerCase()
  if (['useful', 'neutral', 'risky', 'unsupported', 'unknown'].includes(normalized)) return normalized
  return 'unknown'
}

function anchorSourceTags(row) {
  const parsed = parseDetailPayload(row?.sourceTags)
  const tags = Array.isArray(parsed) ? parsed : []
  const merged = [row?.anchorSource, ...tags].filter((value) => value != null && value !== '')
  return Array.from(new Set(merged.map((value) => String(value)))).slice(0, 3)
}

function renderAnchorEvaluationRows(value) {
  const rows = Array.isArray(value) ? value : []
  if (!rows.length) {
    return <div className="rag-anchor-empty">Anchor evaluation unavailable</div>
  }
  return (
    <section className="rag-anchor-panel">
      <div className="rag-anchor-panel__header">
        <strong>Rewrite Anchor Analysis</strong>
        <span>{rows.length} anchors</span>
      </div>
      <div className="rag-anchor-table-wrap">
        <table className="rag-anchor-table">
          <thead>
            <tr>
              <th>Anchor</th>
              <th>Source</th>
              <th>Label</th>
              <th>Risk</th>
              <th>Score</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row, index) => {
              const label = anchorLabelTone(row?.label)
              const tags = anchorSourceTags(row)
              return (
                <tr key={row?.id || `${row?.normalizedAnchorText || row?.anchorText}-${index}`} className={`rag-anchor-row rag-anchor-row--${label}`}>
                  <td>
                    <div className="rag-anchor-name">
                      <strong>{row?.anchorText || '-'}</strong>
                      {row?.canonicalAnchorText && <small>canonical {row.canonicalAnchorText}</small>}
                      {row?.sourceMemoryIndex != null && <small>memory index {row.sourceMemoryIndex}</small>}
                    </div>
                  </td>
                  <td>
                    <div className="rag-anchor-tags">
                      {tags.map((tag) => <span key={tag}>{tag}</span>)}
                    </div>
                  </td>
                  <td><span className={`rag-anchor-label rag-anchor-label--${label}`}>{label}</span></td>
                  <td>{formatDetailNumber(row?.driftRiskScore, 3)}</td>
                  <td>{formatDetailNumber(row?.overallAnchorScore, 3)}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </section>
  )
}

function renderAnchorEvaluationSummary(value) {
  const summary = parseMetricsNode(value)
  const totalAnchors = toMetricNumber(summary.total_anchor_count ?? summary.totalAnchorCount)
  const evaluatedSamples = toMetricNumber(summary.anchor_evaluated_sample_count ?? summary.anchorEvaluatedSampleCount)
  if (!totalAnchors && !evaluatedSamples) {
    return <div className="rag-anchor-empty">Anchor evaluation unavailable</div>
  }
  const cards = [
    { label: 'Anchor Precision', value: summary.anchor_precision ?? summary.anchorPrecision },
    { label: 'Grounded Anchor Rate', value: summary.grounded_anchor_rate ?? summary.groundedAnchorRate },
    { label: 'Added Anchor Grounded Rate', value: summary.added_anchor_grounded_rate ?? summary.addedAnchorGroundedRate },
    { label: 'Risky Anchor Rate', value: summary.risky_anchor_rate ?? summary.riskyAnchorRate },
    { label: 'Avg Anchor Relevance', value: summary.avg_anchor_relevance_score ?? summary.avgAnchorRelevanceScore },
    { label: 'Supported Rewrite Rate', value: summary.anchor_supported_rewrite_rate ?? summary.anchorSupportedRewriteRate },
  ]
  return (
    <section className="rag-anchor-summary">
      <div className="rag-anchor-summary__cards">
        {cards.map((card) => (
          <div className="rag-anchor-summary-card" key={card.label}>
            <span>{card.label}</span>
            <strong>{formatDetailNumber(card.value, 3)}</strong>
          </div>
        ))}
      </div>
      <div className="rag-anchor-summary__counts">
        <span>Evaluated Samples <strong>{evaluatedSamples ?? 0}</strong></span>
        <span>Rewrite Applied Samples <strong>{summary.rewrite_applied_sample_count ?? summary.rewriteAppliedSampleCount ?? 0}</strong></span>
        <span>Useful <strong>{summary.useful_anchor_count ?? summary.usefulAnchorCount ?? 0}</strong></span>
        <span>Risky <strong>{summary.risky_anchor_count ?? summary.riskyAnchorCount ?? 0}</strong></span>
        <span>Unsupported <strong>{summary.unsupported_anchor_count ?? summary.unsupportedAnchorCount ?? 0}</strong></span>
      </div>
    </section>
  )
}

function renderPerformanceMetricDetail(value) {
  const payload = parseMetricsNode(value)
  const hasMetrics = PERFORMANCE_METRIC_DEFS.some((metricDef) => payload?.[metricDef.key] != null)
  if (!hasMetrics) return renderGenericDetailValue(value)
  return (
    <div className="rag-performance-detail">
      {PERFORMANCE_METRIC_DEFS.map((metricDef) => {
        const formatted = formatTableMetricValue(payload?.[metricDef.key], metricDef)
        return (
          <article key={metricDef.key} className="rag-performance-detail__card">
            <span>{metricDef.key}</span>
            <strong>{formatted.main}</strong>
            <small>{metricDef.description}</small>
            <em>{formatMetricSampleBasis(metricDef, payload)}</em>
          </article>
        )
      })}
    </div>
  )
}

function displayRetrievalModeName(mode) {
  return mode === 'raw_only' ? 'raw_query' : mode
}

function renderRetrievalSummaryDetail(value) {
  const rows = Array.isArray(value) ? value : []
  if (!rows.length) return renderGenericDetailValue(value)
  const rawRow = rows.find((row) => normalizeModeName(row) === 'raw_only') || null
  const metricDefs = [
    { label: 'Recall@5', aliases: ['recall@5', 'recall_at_5'], precision: 4 },
    { label: 'Hit@5', aliases: ['hit@5', 'hit_at_5'], precision: 4 },
    { label: 'MRR@10', aliases: ['mrr@10', 'mrr_at_10'], precision: 4 },
    { label: 'nDCG@10', aliases: ['ndcg@10', 'ndcg_at_10'], precision: 4 },
  ]
  return (
    <div className="rag-retrieval-mode-list">
      {rows.map((row, index) => {
        const mode = normalizeModeName(row) || `mode_${index + 1}`
        const isRaw = mode === 'raw_only'
        return (
          <article key={`${mode}-${index}`} className={`rag-retrieval-mode-card ${isRaw ? 'is-raw' : ''}`}>
            <header className="rag-retrieval-mode-card__header">
              <span className="rag-detail-mode-chip">{displayRetrievalModeName(mode)}</span>
              <small>{isRaw ? '비교 기준' : 'raw_query 대비'}</small>
            </header>
            <div className="rag-retrieval-mode-card__metrics">
              {metricDefs.map((metric) => {
                const current = metricFromRow(row, metric.aliases)
                const raw = rawRow ? metricFromRow(rawRow, metric.aliases) : null
                const delta = !isRaw && current != null && raw != null ? current - raw : null
                const tone = delta == null || delta === 0 ? 'neutral' : delta > 0 ? 'positive' : 'negative'
                return (
                  <div key={metric.label} className="rag-retrieval-mode-metric">
                    <span>{metric.label}</span>
                    <strong>{current == null ? '-' : Number(current).toFixed(metric.precision)}</strong>
                    {!isRaw && (
                      <em data-tone={tone}>
                        {delta == null ? '-' : formatDelta(delta, { precision: metric.precision })}
                      </em>
                    )}
                    {!isRaw && raw != null && <small>raw {Number(raw).toFixed(metric.precision)}</small>}
                  </div>
                )
              })}
            </div>
          </article>
        )
      })}
    </div>
  )
}

function datasetQueryText(row) {
  const language = String(row?.queryLanguage || 'ko').toLowerCase()
  if (language === 'en') return normalizeQueryText(row?.userQueryEn || row?.userQueryKo)
  return normalizeQueryText(row?.userQueryKo || row?.userQueryEn)
}

function datasetAlternateQuery(row) {
  const language = String(row?.queryLanguage || 'ko').toLowerCase()
  const primary = datasetQueryText(row)
  const alternate = language === 'en' ? row?.userQueryKo : row?.userQueryEn
  const normalized = normalizeQueryText(alternate)
  return normalized === '-' || normalized === primary ? '' : normalized
}

function datasetFocusItems(value) {
  const payload = parseDetailPayload(value)
  if (Array.isArray(payload)) {
    return payload.map((item) => String(item || '').trim()).filter(Boolean)
  }
  return []
}

function distributionEntries(value) {
  const payload = parseDetailPayload(value)
  if (!isPlainDetailObject(payload)) return []
  return Object.entries(payload)
    .filter(([, item]) => item != null && item !== '')
    .map(([key, item]) => ({ key, value: item }))
}

function EvalDatasetDetail({ dataset, items }) {
  const rows = Array.isArray(items) ? items : []
  const categoryEntries = distributionEntries(dataset?.categoryDistribution)
  const splitEntries = distributionEntries(dataset?.singleMultiDistribution)
  return (
    <div className="eval-dataset-detail">
      <section className="eval-dataset-detail__summary">
        <article>
          <span>데이터셋</span>
          <strong>{dataset?.datasetName || '-'}</strong>
        </article>
        <article>
          <span>전체 질의</span>
          <strong>{formatTableNumber(rows.length, 0)}개</strong>
          {dataset?.totalItems != null && <small>등록 {formatTableNumber(dataset.totalItems, 0)}개</small>}
        </article>
        <article>
          <span>언어</span>
          <strong>{dataset?.queryLanguage || '-'}</strong>
        </article>
        <article>
          <span>버전</span>
          <strong>{dataset?.version || '-'}</strong>
        </article>
      </section>

      {(categoryEntries.length > 0 || splitEntries.length > 0) && (
        <section className="eval-dataset-detail__distribution">
          {categoryEntries.length > 0 && (
            <div>
              <span className="eval-dataset-detail__section-label">카테고리 분포</span>
              <div className="eval-dataset-detail__chips">
                {categoryEntries.map((entry) => (
                  <span key={entry.key}>{entry.key} <strong>{String(entry.value)}</strong></span>
                ))}
              </div>
            </div>
          )}
          {splitEntries.length > 0 && (
            <div>
              <span className="eval-dataset-detail__section-label">단일/복합 청크</span>
              <div className="eval-dataset-detail__chips">
                {splitEntries.map((entry) => (
                  <span key={entry.key}>{entry.key} <strong>{String(entry.value)}</strong></span>
                ))}
              </div>
            </div>
          )}
        </section>
      )}

      <section className="eval-dataset-query-list">
        {rows.map((row, index) => {
          const focusItems = datasetFocusItems(row?.evaluationFocus)
          const alternate = datasetAlternateQuery(row)
          return (
            <article key={`${row?.sampleId || index}`} className="eval-dataset-query-card">
              <header className="eval-dataset-query-card__header">
                <div>
                  <span className="eval-dataset-query-card__index">#{index + 1}</span>
                </div>
                <div className="eval-dataset-query-card__badges">
                  <span>{row?.queryLanguage || 'ko'}</span>
                  {row?.targetMethod && <span>method {row.targetMethod}</span>}
                  {row?.split && <span>{row.split}</span>}
                </div>
              </header>
              <p className="eval-dataset-query-card__query">{datasetQueryText(row)}</p>
              {alternate && (
                <div className="eval-dataset-query-card__alternate">
                  <span>다른 언어 질의</span>
                  <p>{alternate}</p>
                </div>
              )}
              <footer className="eval-dataset-query-card__meta">
                {row?.queryCategory && <span>{row.queryCategory}</span>}
                {row?.singleOrMultiChunk && <span>{row.singleOrMultiChunk}</span>}
                {focusItems.map((focus) => <span key={focus}>{focus}</span>)}
              </footer>
            </article>
          )
        })}
        {rows.length === 0 && <div className="empty-state">표시할 평가 질의가 없습니다.</div>}
      </section>
    </div>
  )
}

function renderRagDetailJsonDisclosure(label, value, renderer = renderGenericDetailValue, defaultOpen = false) {
  const parsed = parseDetailPayload(value)
  const isEmpty = !hasDetailPayload(parsed)
  return (
    <details className="rag-detail-disclosure" open={defaultOpen}>
      <summary>{label}</summary>
      <div className="rag-detail-disclosure__content">
        {isEmpty ? renderDetailEmpty() : renderer(parsed)}
      </div>
    </details>
  )
}

function detailRowKey(row, index = 0) {
  return String(row?.detailId || row?.sampleId || `detail-${index}`)
}

function detailSampleKey(row, index = 0) {
  const sampleId = String(row?.sampleId || '').trim()
  return sampleId || detailRowKey(row, index)
}

function detailPayloadScore(row) {
  let score = 0
  if (hasDetailPayload(parseDetailPayload(row?.rewriteCandidates))) score += 8
  if (hasDetailPayload(parseDetailPayload(row?.memoryCandidates))) score += 4
  if (row?.rewriteApplied) score += 2
  if (hasDetailPayload(parseDetailPayload(row?.anchorEvaluations))) score += 1
  return score
}

function dedupeRagDetailRows(details) {
  const rows = Array.isArray(details) ? details : []
  const bySample = new Map()
  rows.forEach((row, index) => {
    const sampleKey = detailSampleKey(row, index)
    const current = bySample.get(sampleKey)
    const score = detailPayloadScore(row)
    if (!current || score > current.score) {
      bySample.set(sampleKey, {
        row,
        score,
        order: current?.order ?? index,
      })
    }
  })
  return Array.from(bySample.values())
    .sort((left, right) => left.order - right.order)
    .map((entry) => entry.row)
}

function buildQueryDetailOption(row, index) {
  const queryText = compactText(row?.rawQuery || row?.rewriteQuery || '-', 96)
  const rewriteApplied = Boolean(row?.rewriteApplied)
  const badges = [
    {
      label: rewriteApplied ? 'applied' : 'skipped',
      tone: rewriteApplied ? 'success' : 'warning',
    },
  ]
  if (row?.hitTarget === false) {
    badges.push({ label: 'miss target', tone: 'danger' })
  }
  return {
    value: detailRowKey(row, index),
    label: `#${index + 1} · ${row?.sampleId || `sample-${index + 1}`}`,
    meta: `${row?.queryCategory || '-'} · ${queryText}`,
    badges,
  }
}

function resolveRunDetailModalTitle(run) {
  const runName = String(run?.runLabel || run?.runName || '').trim()
  return runName ? `RAG 실행 상세 · ${runName}` : 'RAG 실행 상세'
}

function renderRagQueryDetailRows(details) {
  const rows = Array.isArray(details) ? details : []
  if (!rows.length) {
    return <div className="rag-query-detail-empty">표시할 질의 상세 데이터가 없습니다.</div>
  }
  return (
    <section className="rag-query-detail-list">
      {rows.map((row, index) => {
        const rawQuery = normalizeQueryText(row?.rawQuery)
        const rewriteQuery = normalizeQueryText(row?.rewriteQuery)
        const finalRewriteQuery = rewriteQuery === '-' ? rawQuery : rewriteQuery
        const rowKey = row?.detailId || row?.sampleId || `detail-${index}`
        const showSkippedRewriteCandidates = !row?.rewriteApplied
        return (
          <article className="rag-query-detail-card" key={rowKey}>
            <header className="rag-query-detail-card__header">
              <div className="rag-query-detail-card__id">
                <strong>{row?.sampleId || `sample-${index + 1}`}</strong>
                <span>{row?.queryCategory || '-'}</span>
              </div>
              <div className="rag-query-detail-card__badges">
                <StatusBadge value={row?.rewriteApplied ? 'success' : 'warning'} label={row?.rewriteApplied ? 'rewrite applied' : 'rewrite skipped'} />
                <StatusBadge value={row?.hitTarget ? 'success' : 'failed'} label={row?.hitTarget ? 'hit target' : 'miss target'} />
              </div>
            </header>

            <div className="rag-query-focus-grid">
              <section className="rag-query-focus rag-query-focus--raw">
                <div className="rag-query-focus__label">원본 질의</div>
                <p className="rag-query-focus__text">{rawQuery}</p>
              </section>
              <section className="rag-query-focus rag-query-focus--rewrite">
                <div className="rag-query-focus__label">최종 재작성 합성 질의</div>
                <p className="rag-query-focus__text">{finalRewriteQuery}</p>
              </section>
            </div>

            {renderAnchorEvaluationRows(row?.anchorEvaluations)}

            <details className="rag-detail-disclosure rag-detail-disclosure--group" open={showSkippedRewriteCandidates}>
              <summary>세부 데이터 보기</summary>
              <div className="rag-detail-disclosure__content rag-detail-disclosure__content--group">
                {renderRagDetailJsonDisclosure('지표 기여', row?.metricContribution, renderMetricContributionDetail)}
                {renderRagDetailJsonDisclosure('추천 합성 질의 후보', row?.memoryCandidates, renderMemoryCandidateDetail, showSkippedRewriteCandidates)}
                {renderRagDetailJsonDisclosure('재작성 후보 로그', row?.rewriteCandidates, renderRewriteCandidateDetail, showSkippedRewriteCandidates)}
                {renderRagDetailJsonDisclosure('검색 청크 결과', row?.retrievedChunks, renderRetrievedChunkDetail)}
              </div>
            </details>
          </article>
        )
      })}
    </section>
  )
}

function RagRunDetailModalBody({
  details,
  runMetrics,
  anchorSummary,
  retrievalSummaryRows,
  performance,
  rewriteMode,
  anchorEnabled,
  multiSourceEnabled,
}) {
  const rows = useMemo(() => dedupeRagDetailRows(details), [details])
  const [selectedKey, setSelectedKey] = useState(() => detailRowKey(rows[0], 0))
  const detailOptions = useMemo(
    () => rows.map((row, index) => buildQueryDetailOption(row, index)),
    [rows],
  )
  const selectedIndex = rows.findIndex((row, index) => detailRowKey(row, index) === selectedKey)
  const resolvedSelectedIndex = selectedIndex >= 0 ? selectedIndex : 0
  const selectedRow = rows[resolvedSelectedIndex]

  return (
    <div className="detail-grid detail-grid--single rag-run-detail-modal">
      {rows.length > 0 && (
        <div className="rag-query-detail-selector">
          <span>질의 분석 보기</span>
          <SelectDropdown
            value={selectedRow ? detailRowKey(selectedRow, resolvedSelectedIndex) : ''}
            options={detailOptions}
            onChange={setSelectedKey}
            placeholder="질의 선택"
            searchPlaceholder="sample id 또는 질의 검색"
            emptyLabel="표시할 질의가 없습니다."
            allowClear={false}
          />
        </div>
      )}
      {renderRagQueryDetailRows(selectedRow ? [selectedRow] : [])}
      <details className="rag-detail-disclosure rag-detail-disclosure--group">
        <summary>실행 요약 지표 보기</summary>
        <div className="rag-detail-disclosure__content rag-detail-disclosure__content--group">
          {renderPerformanceCards(runMetrics)}
          {renderAnchorEvaluationSummary(anchorSummary)}
          {renderRunDetailModeSummary(retrievalSummaryRows)}
          {renderRunDetailModeComparison(retrievalSummaryRows)}
          {renderRagDetailJsonDisclosure('실행 프로필', {
            rewrite_mode: rewriteMode.label,
            rewrite_anchor_injection_enabled: anchorEnabled,
            multi_source_anchor_expansion_enabled: multiSourceEnabled,
          })}
          {renderRagDetailJsonDisclosure('성능 지표', performance, renderPerformanceMetricDetail)}
          {renderRagDetailJsonDisclosure('모드별 검색 지표', retrievalSummaryRows, renderRetrievalSummaryDetail)}
        </div>
      </details>
    </div>
  )
}

function formatDelta(value, def) {
  if (value == null) return '-'
  const precision = Number.isFinite(def?.precision) ? def.precision : 3
  const sign = value > 0 ? '+' : ''
  const text = `${sign}${Number(value).toFixed(precision)}`
  return def?.unit ? `${text} ${def.unit}` : text
}

function metricDisplayUnit(def) {
  return def?.displayUnit || def?.unit || ''
}

function metricDisplayScale(def) {
  return Number.isFinite(def?.displayScale) ? def.displayScale : 1
}

function hasScaledMetricDisplay(def) {
  return def?.displayUnit && Number.isFinite(def?.displayScale)
}

function formatMetricDisplayNumber(value, def, { signed = false, absolute = false } = {}) {
  if (value == null || !Number.isFinite(Number(value))) return '-'
  const precision = Number.isFinite(def?.precision) ? def.precision : 3
  const scaled = Number(value) * metricDisplayScale(def)
  const displayValue = absolute ? Math.abs(scaled) : scaled
  return signed
    ? formatSignedTableNumber(displayValue, precision)
    : formatTableNumber(displayValue, precision)
}

function formatMetricDisplayWithUnit(value, def, options = {}) {
  const numberText = formatMetricDisplayNumber(value, def, options)
  if (numberText === '-') return '-'
  const unit = metricDisplayUnit(def)
  return unit ? `${numberText} ${unit}` : numberText
}

function resolveTableNumberFormatter(precision) {
  const normalizedPrecision = Number.isFinite(precision) ? precision : 0
  if (!TABLE_NUMBER_FORMATTERS.has(normalizedPrecision)) {
    TABLE_NUMBER_FORMATTERS.set(
      normalizedPrecision,
      new Intl.NumberFormat('en-US', {
        minimumFractionDigits: normalizedPrecision,
        maximumFractionDigits: normalizedPrecision,
      }),
    )
  }
  return TABLE_NUMBER_FORMATTERS.get(normalizedPrecision)
}

function formatTableNumber(value, precision = 0) {
  if (value == null || !Number.isFinite(Number(value))) return '-'
  return resolveTableNumberFormatter(precision).format(Number(value))
}

function formatSignedTableNumber(value, precision = 0) {
  if (value == null || !Number.isFinite(Number(value))) return '-'
  const normalized = Number(value)
  const sign = normalized > 0 ? '+' : normalized < 0 ? '-' : ''
  return `${sign}${formatTableNumber(Math.abs(normalized), precision)}`
}

function formatTableDurationDisplay(value, options = {}) {
  const normalized = Number(value)
  if (!Number.isFinite(normalized)) return { main: '-', sub: '' }
  const signed = options.signed === true
  const precisionMs = Number.isFinite(options.precisionMs) ? options.precisionMs : 0
  const precisionSeconds = Number.isFinite(options.precisionSeconds) ? options.precisionSeconds : 2
  const includeRawMs = options.includeRawMs === true
  const abs = Math.abs(normalized)
  const signPrefix = signed ? (normalized > 0 ? '+' : normalized < 0 ? '-' : '') : (normalized < 0 ? '-' : '')
  const msValueText = signed
    ? formatSignedTableNumber(normalized, precisionMs)
    : formatTableNumber(normalized, precisionMs)
  const msText = `${msValueText} ms`
  if (abs < 1000) {
    return { main: msText, sub: '' }
  }
  if (abs < 60000) {
    const secondsText = signed
      ? formatSignedTableNumber(normalized / 1000, precisionSeconds)
      : formatTableNumber(normalized / 1000, precisionSeconds)
    return {
      main: `${secondsText} s`,
      sub: includeRawMs ? msText : '',
    }
  }
  const totalSecondsAbs = abs / 1000
  const minutes = Math.floor(totalSecondsAbs / 60)
  const seconds = totalSecondsAbs - (minutes * 60)
  return {
    main: `${signPrefix}${formatTableNumber(minutes, 0)}m ${formatTableNumber(seconds, precisionSeconds)}s`,
    sub: includeRawMs ? msText : '',
  }
}

function formatTableMetricValue(value, def) {
  if (value == null) return { main: '-', sub: '' }
  const precision = Number.isFinite(def?.precision) ? def.precision : 3
  if (hasScaledMetricDisplay(def)) {
    return {
      main: formatMetricDisplayWithUnit(value, def),
      sub: '',
    }
  }
  if (def?.unit === 'ms') {
    return formatTableDurationDisplay(value, {
      signed: false,
      precisionMs: precision,
      precisionSeconds: 2,
      includeRawMs: true,
    })
  }
  return {
    main: formatTableNumber(value, precision),
    sub: '',
  }
}

function formatTableDeltaRaw(value, def) {
  if (value == null) return '-'
  const precision = Number.isFinite(def?.precision) ? def.precision : 3
  if (hasScaledMetricDisplay(def)) {
    return formatMetricDisplayWithUnit(value, def, { signed: true })
  }
  if (def?.unit === 'ms') {
    return formatTableDurationDisplay(value, {
      signed: true,
      precisionMs: precision,
      precisionSeconds: 2,
      includeRawMs: false,
    }).main
  }
  const signed = formatSignedTableNumber(value, precision)
  return def?.unit ? `${signed} ${def.unit}` : signed
}

function formatTableDeltaRate(value) {
  if (value == null) return '-'
  return `${formatSignedTableNumber(value, 1)}%`
}

function formatTableDeltaMagnitude(row) {
  if (row?.deltaRate != null) return `${formatTableNumber(Math.abs(row.deltaRate), 1)}%`
  if (row?.delta == null) return '-'
  if (hasScaledMetricDisplay(row)) {
    return formatMetricDisplayWithUnit(row.delta, row, { absolute: true })
  }
  if (row?.unit === 'ms') {
    return formatTableDurationDisplay(Math.abs(row.delta), {
      signed: false,
      precisionMs: Number.isFinite(row?.precision) ? row.precision : 0,
      precisionSeconds: 2,
      includeRawMs: false,
    }).main
  }
  const precision = Number.isFinite(row?.precision) ? row.precision : 3
  const absText = formatTableNumber(Math.abs(row.delta), precision)
  return row?.unit ? `${absText} ${row.unit}` : absText
}

function formatTableDeltaDisplay(row) {
  if (!row || row.delta == null) return { main: '-', sub: '' }
  const precision = Number.isFinite(row?.precision) ? row.precision : 3
  if (hasScaledMetricDisplay(row)) {
    return {
      main: formatMetricDisplayWithUnit(row.delta, row, { signed: true }),
      sub: '',
    }
  }
  if (row.unit === 'ms') {
    return formatTableDurationDisplay(row.delta, {
      signed: true,
      precisionMs: precision,
      precisionSeconds: 2,
      includeRawMs: true,
    })
  }
  return {
    main: formatTableDeltaRaw(row.delta, row),
    sub: '',
  }
}

function formatDurationDisplay(value, options = {}) {
  const precisionMs = Number.isFinite(options.precisionMs) ? options.precisionMs : 0
  const precisionSeconds = Number.isFinite(options.precisionSeconds) ? options.precisionSeconds : 2
  const includeRawMs = options.includeRawMs === true
  const display = formatTableDurationDisplay(value, {
    signed: false,
    precisionMs,
    precisionSeconds,
    includeRawMs,
  })
  return {
    primary: display.main,
    secondary: display.sub,
  }
}

function formatSignedDurationDisplay(value, options = {}) {
  const precisionMs = Number.isFinite(options.precisionMs) ? options.precisionMs : 0
  const precisionSeconds = Number.isFinite(options.precisionSeconds) ? options.precisionSeconds : 2
  const includeRawMs = options.includeRawMs === true
  const display = formatTableDurationDisplay(value, {
    signed: true,
    precisionMs,
    precisionSeconds,
    includeRawMs,
  })
  return {
    primary: display.main,
    secondary: display.sub,
  }
}

function formatWorkspaceMetricValue(value, row) {
  if (value == null) return { primary: '-', secondary: '' }
  const precision = Number.isFinite(row?.precision) ? row.precision : 3
  if (hasScaledMetricDisplay(row)) {
    return { primary: formatMetricDisplayWithUnit(value, row), secondary: '' }
  }
  if (row?.unit === 'ms') {
    return formatDurationDisplay(value, { precisionMs: precision, precisionSeconds: 2, includeRawMs: false })
  }
  const text = formatTableNumber(value, precision)
  return row?.unit ? { primary: `${text} ${row.unit}`, secondary: '' } : { primary: text, secondary: '' }
}

function formatWorkspaceDeltaValue(row) {
  if (!row || row.delta == null) return { primary: '-', secondary: '' }
  const precision = Number.isFinite(row.precision) ? row.precision : 3
  if (hasScaledMetricDisplay(row)) {
    return { primary: formatMetricDisplayWithUnit(row.delta, row, { signed: true }), secondary: '' }
  }
  if (row.unit === 'ms') {
    return formatSignedDurationDisplay(row.delta, { precisionMs: precision, precisionSeconds: 2, includeRawMs: false })
  }
  const signed = formatSignedTableNumber(row.delta, precision)
  return row.unit ? { primary: `${signed} ${row.unit}`, secondary: '' } : { primary: signed, secondary: '' }
}

function formatWorkspaceDeltaRate(value) {
  if (value == null || !Number.isFinite(Number(value))) return '-'
  return `${formatSignedTableNumber(value, 1)}%`
}

function buildWorkspaceChangeInsight(row) {
  if (!row || row.outcome === 'na') {
    return {
      summary: '비교 데이터 없음',
      detail: 'A/B 값 없음',
      tone: 'na',
    }
  }
  const deltaDisplay = formatWorkspaceDeltaValue(row)
  if (row.outcome === 'tie' || row.delta === 0) {
    return {
      summary: '변화 없음',
      detail: `차이 ${deltaDisplay.primary}`,
      tone: 'tie',
    }
  }
  const improved = row.outcome === 'right'
  if (row.unit === 'ms' || row.groupKey === 'performance') {
    const speedWord = improved ? '빠름' : '느림'
    const ratio = (row.left != null && row.right != null && row.left > 0 && row.right > 0)
      ? (improved ? row.left / row.right : row.right / row.left)
      : null
    let summary = speedWord
    if (ratio != null && ratio >= 1.2) {
      summary = `${formatTableNumber(ratio, 2)}x ${speedWord}`
    } else if (row.deltaRate != null) {
      summary = `${formatTableNumber(Math.abs(row.deltaRate), 1)}% ${speedWord}`
    }
    const detailParts = [`차이 ${deltaDisplay.primary}`]
    if (row.deltaRate != null && !summary.includes('%')) {
      detailParts.push(`${formatTableNumber(Math.abs(row.deltaRate), 1)}% ${speedWord}`)
    }
    return {
      summary,
      detail: detailParts.join(' '),
      tone: improved ? 'right' : 'left',
      speedTone: improved ? 'fast' : 'slow',
    }
  }
  const changeWord = row.trend === 'higher'
    ? (improved ? '상승' : '하락')
    : (improved ? '하락' : '상승')
  const summary = row.deltaRate != null ? `${formatTableNumber(Math.abs(row.deltaRate), 1)}% ${changeWord}` : changeWord
  const detailParts = [`차이 ${deltaDisplay.primary}`]
  return {
    summary,
    detail: detailParts.join(' '),
    tone: improved ? 'right' : 'left',
  }
}

function buildDeltaInterpretation(row) {
  if (!row || row.outcome === 'na') {
    return {
      headline: '비교 데이터 없음',
      detail: 'A/B 값 없음',
      tone: 'na',
    }
  }
  const deltaDisplay = formatTableDeltaDisplay(row)
  if (row.outcome === 'tie' || row.delta === 0) {
    const tieDetail = [`Δ ${deltaDisplay.main}`]
    if (deltaDisplay.sub) tieDetail.push(`raw ${deltaDisplay.sub}`)
    return {
      headline: '변화 없음',
      detail: tieDetail.join(' | '),
      tone: 'tie',
    }
  }
  const improved = row.outcome === 'right'
  const isLatencyLike = row.groupKey === 'performance' || row.unit === 'ms'
  const symbol = improved ? '▲' : '▼'
  let headline = ''
  if (isLatencyLike) {
    const speedWord = improved ? '빠름' : '느림'
    const ratio = (row.left != null && row.right != null && row.left > 0 && row.right > 0)
      ? (improved ? row.left / row.right : row.right / row.left)
      : null
    if (ratio != null && ratio >= 1.2) {
      headline = `${symbol} ${formatTableNumber(ratio, 2)}x ${speedWord}`
    } else if (row.deltaRate != null) {
      headline = `${symbol} ${formatTableNumber(Math.abs(row.deltaRate), 1)}% ${speedWord}`
    } else {
      headline = `${symbol} ${formatTableDeltaMagnitude(row)} ${speedWord}`
    }
  } else {
    const qualityWord = improved ? '개선' : '하락'
    headline = row.deltaRate != null
      ? `${symbol} ${formatTableNumber(Math.abs(row.deltaRate), 1)}% ${qualityWord}`
      : `${symbol} ${formatTableDeltaMagnitude(row)} ${qualityWord}`
  }
  const detail = []
  detail.push(`Δ ${deltaDisplay.main}`)
  if (row.deltaRate != null && !headline.includes('%')) {
    detail.push(formatTableDeltaRate(row.deltaRate))
  }
  if (deltaDisplay.sub) detail.push(`raw ${deltaDisplay.sub}`)
  return {
    headline,
    detail: detail.join(' | '),
    tone: improved ? 'right' : 'left',
  }
}

function buildResultLabel(row, leftLabel = 'A', rightLabel = 'B') {
  if (!row || row.outcome === 'na') return { main: '비교 불가', sub: '원본 지표 확인' }
  if (row.outcome === 'tie') return { main: '변화 없음', sub: METRIC_TREND_LABEL[row.trend] || '' }
  if (row.outcome === 'right') return { main: compareOutcomeLabel(row.outcome, leftLabel, rightLabel), sub: METRIC_TREND_LABEL[row.trend] || '' }
  return { main: compareOutcomeLabel(row.outcome, leftLabel, rightLabel), sub: METRIC_TREND_LABEL[row.trend] || '' }
}

function summarizeGroupRows(rows) {
  return rows.reduce((acc, row) => {
    if (row.outcome === 'right') acc.right += 1
    if (row.outcome === 'left') acc.left += 1
    if (row.outcome === 'tie') acc.tie += 1
    if (row.outcome === 'na') acc.na += 1
    return acc
  }, { right: 0, left: 0, tie: 0, na: 0 })
}

function compareTableRowSorter(left, right) {
  const leftCore = left.priority === 'core' ? 0 : 1
  const rightCore = right.priority === 'core' ? 0 : 1
  if (leftCore !== rightCore) return leftCore - rightCore
  return (left.metricOrder || 0) - (right.metricOrder || 0)
}

function compactText(value, maxLength = 46) {
  if (!value) return '-'
  const normalized = String(value).trim()
  if (!normalized) return '-'
  if (normalized.length <= maxLength) return normalized
  return `${normalized.slice(0, maxLength - 3)}...`
}

function formatRunIdForTable(value) {
  if (!value) return '-'
  const raw = String(value)
  if (raw.length <= 20) return raw
  return `${raw.slice(0, 8)}...${raw.slice(-8)}`
}

function isGeneratedRagRunLabel(value) {
  const raw = String(value || '').trim()
  if (!raw) return true
  return /^RAG\s*테스트\s+/i.test(raw)
}

function resolveCompareRunPrimaryLabel(run, fallbackTitle) {
  const runLabelRaw = String(run?.runLabel || '').trim()
  if (runLabelRaw && !isGeneratedRagRunLabel(runLabelRaw)) {
    return compactText(runLabelRaw, 40)
  }
  if (run?.ragTestRunId) {
    return compactText(`레거시 RAG 테스트 ${shortId(run.ragTestRunId)}`, 40)
  }
  const methodLabel = compactText(formatGenerationMethodLabel(run?.generationMethodCodes), 40)
  return methodLabel === '-' ? fallbackTitle : methodLabel
}

function compareOutcomeLabel(outcome, leftLabel = 'A', rightLabel = 'B') {
  if (outcome === 'right') return `${compactText(rightLabel, 24)} 우세`
  if (outcome === 'left') return `${compactText(leftLabel, 24)} 우세`
  if (outcome === 'tie') return '변화 없음'
  return '데이터 없음'
}

function resolveCompareRunSecondaryLabel(run) {
  const pieces = [run?.datasetName, fmtTime(run?.finishedAt || run?.startedAt)]
    .map((value) => String(value || '').trim())
    .filter((value) => value && value !== '-')
  return compactText(pieces.join(' | '), 52)
}

function formatGenerationMethodLabel(methodCodes) {
  if (!Array.isArray(methodCodes) || methodCodes.length === 0) return '합성 질의 제외'
  return methodCodes.join(', ')
}

function listGenerationMethodCodes(methodCodes) {
  if (!Array.isArray(methodCodes) || methodCodes.length === 0) return []
  return methodCodes
    .map((code) => String(code || '').trim().toUpperCase())
    .filter(Boolean)
}

function toHistoryTag(kind, icon, text, title = null) {
  return {
    kind,
    icon: String(icon || '').trim() || '·',
    text: String(text || '-'),
    title: String(title || text || '-'),
  }
}

function buildGenerationMethodTags(methodCodes) {
  const codes = listGenerationMethodCodes(methodCodes)
  if (!codes.length) return [toHistoryTag('method', 'SF', 'synthetic-free')]
  return codes.map((code) => toHistoryTag('method', 'M', `${code} method`, `Method ${code}`))
}

function buildGatingTags(run) {
  if (!run?.gatingApplied) return [toHistoryTag('gating-off', 'NG', 'ungated')]
  const normalizedPreset = String(run?.gatingPreset || 'full_gating').trim().toLowerCase()
  const labelMap = {
    ungated: 'ungated',
    rule_only: 'rule only',
    rule_plus_llm: 'rule + llm',
    full_gating: 'full gating',
  }
  const iconMap = {
    ungated: 'NG',
    rule_only: 'R',
    rule_plus_llm: 'RL',
    full_gating: 'FG',
  }
  return [toHistoryTag('gating-on', iconMap[normalizedPreset] || 'GT', labelMap[normalizedPreset] || normalizedPreset)]
}

function buildStageCutoffTags(run) {
  const metricsPayload = parseMetricsNode(run?.metricsJson)
  const memoryPayload = parseMetricsNode(metricsPayload.memory || metricsPayload.metrics_json?.memory)
  const stageCutoffEnabled = run?.stageCutoffEnabled != null
    ? Boolean(run.stageCutoffEnabled)
    : Boolean(memoryPayload.stage_cutoff_enabled)
  if (!stageCutoffEnabled) return [toHistoryTag('cutoff-off', 'ST', 'off')]
  const normalizedLevel = String(run?.stageCutoffLevel || memoryPayload.stage_cutoff_level || 'full_gating')
    .trim()
    .toLowerCase()
  return [
    toHistoryTag('cutoff-on', 'ST', 'on'),
    toHistoryTag('cutoff-level', 'LV', normalizedLevel.replaceAll('_', ' ')),
  ]
}

function resolveRewriteMode(run) {
  if (!run?.rewriteEnabled) return { kind: 'rewrite-off', icon: 'RW', label: 'off' }
  if (!run?.selectiveRewrite) return { kind: 'rewrite-off', icon: 'RW', label: 'raw-only' }
  if (run?.useSessionContext) return { kind: 'rewrite-session', icon: 'RS', label: 'selective + session' }
  return { kind: 'rewrite-selective', icon: 'SL', label: 'selective' }
}

function resolveRewriteAnchorEnabled(run) {
  if (!run?.rewriteEnabled) return false
  if (run?.rewriteAnchorInjectionEnabled == null) return false
  return Boolean(run.rewriteAnchorInjectionEnabled)
}

function resolveMultiSourceAnchorEnabled(run) {
  if (!resolveRewriteAnchorEnabled(run)) return false
  return Boolean(run?.multiSourceAnchorExpansionEnabled)
}

function buildRewriteTags(run) {
  const mode = resolveRewriteMode(run)
  const anchorEnabled = resolveRewriteAnchorEnabled(run)
  const multiSourceEnabled = resolveMultiSourceAnchorEnabled(run)
  return [
    toHistoryTag(mode.kind, mode.icon, mode.label),
    toHistoryTag(
      anchorEnabled ? 'anchor-on' : 'anchor-off',
      anchorEnabled ? 'AN' : 'AX',
      anchorEnabled ? 'anchor on' : 'anchor off',
      anchorEnabled ? 'rewrite anchor injection enabled' : 'rewrite anchor injection disabled',
    ),
    toHistoryTag(
      multiSourceEnabled ? 'multi-source-anchor-on' : 'multi-source-anchor-off',
      multiSourceEnabled ? 'MS' : 'MX',
      multiSourceEnabled ? 'multi-source on' : 'multi-source off',
      multiSourceEnabled ? 'multi_source_anchor_hints enabled' : 'multi_source_anchor_hints disabled',
    ),
  ]
}

function buildCoreMetricTags(metrics) {
  const queryEvalLatency = formatTableMetricValue(
    metrics?.avg_query_eval_total_latency_ms,
    PERFORMANCE_METRIC_DEFS[0],
  ).main
  return [
    toHistoryTag('metric', 'R5', `Recall@5 ${formatMetric(metrics?.recall_at_5)}`),
    toHistoryTag('metric', 'ND', `nDCG@10 ${formatMetric(metrics?.ndcg_at_10)}`),
    metrics?.legacy_performance
      ? toHistoryTag('metric', 'LG', 'Latency legacy')
      : toHistoryTag('metric', 'QE', `Eval ${queryEvalLatency}`),
  ]
}

function formatMetricSampleBasis(metricDef, metrics) {
  if (!metricDef?.sampleCountKey) return ''
  const count = toMetricNumber(metrics?.[metricDef.sampleCountKey])
  const label = metricDef.sampleCountLabel || 'sample'
  return count == null ? `${label} n=-` : `${label} n=${formatTableNumber(count, 0)}`
}

function metricSupportText(row) {
  return row?.supportText || ''
}

function renderPerformanceCards(metrics) {
  if (metrics?.legacy_performance) {
    return <div className="empty-state">{metrics.legacy_performance_message || LEGACY_PERFORMANCE_MESSAGE}</div>
  }
  return (
    <section className="run-mode-compare">
      <div className="run-mode-compare__header">
        <div>
          <strong>성능</strong>
          <div className="run-mode-compare__subtitle">샘플 기준 지연 시간 지표</div>
        </div>
      </div>
      <div className="summary-grid">
        {PERFORMANCE_METRIC_DEFS.map((metricDef) => {
          const value = formatTableMetricValue(metrics?.[metricDef.key], metricDef)
          return (
            <article key={metricDef.key} className="summary-card" title={metricDef.description}>
              <div className="summary-card__label">{metricDef.label}</div>
              <div className="summary-card__value">{value.main}</div>
              <div className="summary-card__meta">{metricDef.description}</div>
              <div className="summary-card__meta">{formatMetricSampleBasis(metricDef, metrics)}</div>
            </article>
          )
        })}
      </div>
    </section>
  )
}

function resolveMetricOutcome(metricDef, left, right) {
  if (left == null || right == null) return 'na'
  const delta = right - left
  if (delta === 0) return 'tie'
  const rightIsBetter = metricDef.trend === 'lower' ? delta < 0 : delta > 0
  return rightIsBetter ? 'right' : 'left'
}

function metricScaleMax(metricDef, left, right) {
  if (metricDef.max != null) return metricDef.max
  return Math.max(1, left || 0, right || 0)
}

function averageMetric(values) {
  const normalized = values.filter((value) => value != null && Number.isFinite(value))
  if (normalized.length === 0) return null
  return normalized.reduce((sum, value) => sum + value, 0) / normalized.length
}

export function RagPage({ notify, domainId = null }) {
  const historyPageSize = 3
  const [methods, setMethods] = useState([])
  const [datasets, setDatasets] = useState([])
  const [tests, setTests] = useState([])
  const [gatingBatches, setGatingBatches] = useState([])
  const [rewriteLogs, setRewriteLogs] = useState([])
  const [rewriteLogsLoaded, setRewriteLogsLoaded] = useState(false)
  const [rewriteLogsLoading, setRewriteLogsLoading] = useState(false)
  const [llmJobs, setLlmJobs] = useState([])
  const [llmJobsLoaded, setLlmJobsLoaded] = useState(false)
  const [llmJobsLoading, setLlmJobsLoading] = useState(false)
  const [runtimeOptions, setRuntimeOptions] = useState({
    llmModels: [],
    defaultLlmModel: '',
    denseEmbeddingModels: [],
    defaultDenseEmbeddingModel: '',
    retrievalBackends: [],
    defaultRetrievalBackend: 'local',
    retrieverModes: [],
    defaultRetrieverMode: '',
    retrieverModeDefaults: {},
    rewriteFailurePolicies: [],
    rewriteQueryProfiles: [],
    defaultRewriteQueryProfile: 'compact_anchor',
    defaultParameterRanges: {},
  })
  const [historyPage, setHistoryPage] = useState(0)
  const [modal, setModal] = useState(null)
  const [selectedMethods, setSelectedMethods] = useState([])
  const [compareRunIds, setCompareRunIds] = useState([])
  const [activeCompareMetricKey, setActiveCompareMetricKey] = useState('')
  const [deletingRunId, setDeletingRunId] = useState('')
  const [deletingDatasetId, setDeletingDatasetId] = useState('')
  const [chunkEmbeddingStatus, setChunkEmbeddingStatus] = useState(null)
  const [chunkEmbeddingStatusLoading, setChunkEmbeddingStatusLoading] = useState(false)
  const [chunkEmbeddingMaterializing, setChunkEmbeddingMaterializing] = useState(false)

  const [form, setForm] = useState({
    datasetId: '',
    evalQueryLanguage: 'ko',
    runName: '',
    runDiscipline: 'exploratory',
    officialComparisonType: 'rewrite_effect',
    gatingPreset: 'full_gating',
    sourceGatingBatchId: '',
    officialGatingUngatedBatchId: '',
    officialGatingRuleOnlyBatchId: '',
    officialGatingFullGatingBatchId: '',
    llmModel: '',
    rewriteLlmModel: '',
    threshold: '',
    retrievalTopK: '',
    rerankTopN: '',
    retrievalBackend: '',
    ...retrieverPresetForMode('', ''),
    syntheticFreeBaseline: false,
    gatingApplied: true,
    stageCutoffEnabled: false,
    stageCutoffLevel: 'rule_only',
    rewriteEnabled: true,
    selectiveRewrite: true,
    useSessionContext: false,
    rewriteAnchorInjectionEnabled: false,
    multiSourceAnchorExpansionEnabled: false,
    rewriteQueryProfile: 'compact_anchor',
    rewriteFailurePolicy: 'fail_run',
  })

  const loadMethods = async () => {
    const rows = await requestJson(appendQuery('/api/admin/console/synthetic/methods', { domain_id: domainId }))
    const normalized = Array.isArray(rows) ? rows : []
    setMethods(normalized)
    if (normalized.length > 0 && selectedMethods.length === 0) {
      setSelectedMethods([normalized[0].methodCode])
    }
  }

  const loadRuntimeOptions = async () => {
    const payload = await requestJson('/api/admin/console/runtime/options')
    const llmModels = Array.isArray(payload.llmModels) ? payload.llmModels.filter(Boolean) : []
    const denseEmbeddingModels = Array.isArray(payload.denseEmbeddingModels)
      ? payload.denseEmbeddingModels.filter(Boolean)
      : []
    const retrievalBackends = Array.isArray(payload.retrievalBackends)
      ? payload.retrievalBackends.filter(Boolean)
      : []
    const retrieverModes = Array.isArray(payload.retrieverModes)
      ? payload.retrieverModes.filter(Boolean)
      : []
    const rewriteFailurePolicies = Array.isArray(payload.rewriteFailurePolicies)
      ? payload.rewriteFailurePolicies.filter(Boolean)
      : []
    const rewriteQueryProfiles = Array.isArray(payload.rewriteQueryProfiles)
      ? payload.rewriteQueryProfiles.filter(Boolean)
      : []
    const defaultParameterRanges = payload.defaultParameterRanges && typeof payload.defaultParameterRanges === 'object'
      ? payload.defaultParameterRanges
      : {}
    const retrieverModeDefaults = payload.retrieverModeDefaults && typeof payload.retrieverModeDefaults === 'object'
      ? payload.retrieverModeDefaults
      : {}
    const defaultLlmModel = payload.defaultLlmModel || llmModels[0] || ''
    const defaultDenseEmbeddingModel = payload.defaultDenseEmbeddingModel || denseEmbeddingModels[0] || ''
    const defaultRetrievalBackend = payload.defaultRetrievalBackend || retrievalBackends[0] || 'local'
    const defaultRetrieverMode = payload.defaultRetrieverMode || retrieverModes[0] || ''
    const defaultRewriteQueryProfile = payload.defaultRewriteQueryProfile || rewriteQueryProfiles[0] || 'compact_anchor'
    const resolvedRetrieverMode = retrieverModes.includes(form.retrieverMode)
      ? form.retrieverMode
      : (defaultRetrieverMode || form.retrieverMode || '')
    const defaultThreshold = parameterDefault(defaultParameterRanges, 'rewrite_threshold', '')
    const defaultRetrievalTopK = parameterDefault(defaultParameterRanges, 'retrieval_top_k', '')
    const defaultRerankTopN = parameterDefault(defaultParameterRanges, 'rerank_top_n', '')
    setRuntimeOptions({
      llmModels,
      defaultLlmModel,
      denseEmbeddingModels,
      defaultDenseEmbeddingModel,
      retrievalBackends,
      defaultRetrievalBackend,
      retrieverModes,
      defaultRetrieverMode,
      retrieverModeDefaults,
      rewriteFailurePolicies,
      rewriteQueryProfiles,
      defaultRewriteQueryProfile,
      defaultParameterRanges,
    })
    setForm((prev) => {
      const nextRetrieverModeRaw = serverDefaultValue(prev.retrieverMode, '', resolvedRetrieverMode)
      const nextRetrieverMode = retrieverModes.includes(nextRetrieverModeRaw)
        ? nextRetrieverModeRaw
        : resolvedRetrieverMode
      return {
        ...prev,
        llmModel: prev.llmModel || defaultLlmModel,
        rewriteLlmModel: llmModels.includes(prev.rewriteLlmModel) ? prev.rewriteLlmModel : '',
        threshold: serverDefaultValue(prev.threshold, '', defaultThreshold),
        retrievalTopK: serverDefaultValue(prev.retrievalTopK, '', defaultRetrievalTopK),
        rerankTopN: serverDefaultValue(prev.rerankTopN, '', defaultRerankTopN),
        retrievalBackend: retrievalBackends.includes(prev.retrievalBackend)
          ? prev.retrievalBackend
          : defaultRetrievalBackend,
        denseEmbeddingModel: prev.denseEmbeddingModel || defaultDenseEmbeddingModel,
        ...retrieverPresetForMode(
          nextRetrieverMode,
          prev.denseEmbeddingModel || defaultDenseEmbeddingModel,
          retrieverModeDefaults,
        ),
        rewriteFailurePolicy: rewriteFailurePolicies.includes(prev.rewriteFailurePolicy)
          ? prev.rewriteFailurePolicy
          : (rewriteFailurePolicies[0] || ''),
        rewriteQueryProfile: rewriteQueryProfiles.includes(prev.rewriteQueryProfile)
          ? prev.rewriteQueryProfile
          : defaultRewriteQueryProfile,
      }
    })
  }

  const loadDatasets = async () => {
    const rows = await requestJson(appendQuery('/api/admin/console/rag/datasets', { domain_id: domainId }))
    const normalized = Array.isArray(rows) ? rows : []
    setDatasets(normalized)
    setForm((prev) => {
      const currentStillExists = normalized.some((item) => item.datasetId === prev.datasetId)
      const datasetId = currentStillExists ? prev.datasetId : (normalized[0]?.datasetId || '')
      const dataset = normalized.find((item) => item.datasetId === datasetId)
      return {
        ...prev,
        datasetId,
        evalQueryLanguage: dataset ? resolveDatasetQueryLanguage(dataset) : prev.evalQueryLanguage,
      }
    })
  }

  const handleDatasetChange = (datasetId) => {
    const dataset = datasets.find((item) => item.datasetId === datasetId)
    setForm((prev) => ({
      ...prev,
      datasetId,
      evalQueryLanguage: dataset ? resolveDatasetQueryLanguage(dataset) : prev.evalQueryLanguage,
    }))
  }

  const loadTests = async () => {
    const rows = await requestJson(appendQuery('/api/admin/console/rag/tests', { domain_id: domainId }))
    setTests(Array.isArray(rows) ? rows : [])
  }

  const loadGatingBatches = async () => {
    const rows = await requestJson(appendQuery('/api/admin/console/gating/batches?limit=100', { domain_id: domainId }))
    setGatingBatches(Array.isArray(rows) ? rows : [])
  }

  const loadRewriteLogs = async () => {
    setRewriteLogsLoading(true)
    try {
      const rows = await requestJson('/api/admin/console/rewrite/logs?limit=100')
      setRewriteLogs(Array.isArray(rows) ? rows : [])
      setRewriteLogsLoaded(true)
    } finally {
      setRewriteLogsLoading(false)
    }
  }

  const loadLlmJobs = async () => {
    setLlmJobsLoading(true)
    try {
      const rows = await requestJson('/api/admin/console/llm-jobs?limit=120')
      const filtered = (Array.isArray(rows) ? rows : []).filter(
        (job) => job.jobType === 'RUN_RAG_TEST' || job.jobType === 'MATERIALIZE_CHUNK_EMBEDDINGS' || job.ragTestRunId,
      )
      setLlmJobs(filtered)
      setLlmJobsLoaded(true)
    } finally {
      setLlmJobsLoading(false)
    }
  }

  const loadChunkEmbeddingStatus = async (embeddingModel) => {
    if (!embeddingModel) {
      setChunkEmbeddingStatus(null)
      return
    }
    setChunkEmbeddingStatusLoading(true)
    try {
      const status = await requestJson(`/api/admin/console/rag/chunk-embeddings/status?embedding_model=${encodeURIComponent(embeddingModel)}`)
      setChunkEmbeddingStatus(status && typeof status === 'object' ? status : null)
    } finally {
      setChunkEmbeddingStatusLoading(false)
    }
  }

  const materializeChunkEmbeddings = async () => {
    if (!form.denseEmbeddingModel) {
      notify('Dense 임베딩 모델을 먼저 선택하세요.', 'error')
      return
    }
    setChunkEmbeddingMaterializing(true)
    try {
      await requestJson('/api/admin/console/rag/chunk-embeddings/materialize', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          embeddingModel: form.denseEmbeddingModel,
          createdBy: null,
        }),
      })
      await Promise.all([loadChunkEmbeddingStatus(form.denseEmbeddingModel), loadLlmJobs()])
      notify('청크 임베딩 생성 작업을 등록했습니다.')
    } catch (error) {
      notify(error.message, 'error')
    } finally {
      setChunkEmbeddingMaterializing(false)
    }
  }

  useEffect(() => {
    Promise.all([loadMethods(), loadDatasets(), loadTests(), loadGatingBatches(), loadRuntimeOptions()])
      .catch((error) => notify(error.message, 'error'))
  }, [])

  useEffect(() => {
    if (form.retrievalBackend !== 'db_ann') {
      setChunkEmbeddingStatus(null)
      setChunkEmbeddingStatusLoading(false)
      return
    }
    if (!form.denseEmbeddingModel) return
    loadChunkEmbeddingStatus(form.denseEmbeddingModel).catch((error) => notify(error.message, 'error'))
  }, [form.retrievalBackend, form.denseEmbeddingModel])

  useEffect(() => {
    if (!form.datasetId) return
    const dataset = datasets.find((item) => item.datasetId === form.datasetId)
    if (!dataset) return
    const preferredLanguage = resolveDatasetQueryLanguage(dataset)
    if (form.evalQueryLanguage === preferredLanguage) return
    setForm((prev) => ({ ...prev, evalQueryLanguage: preferredLanguage }))
  }, [datasets, form.datasetId, form.evalQueryLanguage])

  const selectedDataset = useMemo(
    () => datasets.find((dataset) => dataset.datasetId === form.datasetId) || null,
    [datasets, form.datasetId],
  )
  const datasetAllowedMethodCodes = useMemo(
    () => resolveDatasetAllowedMethodCodes(selectedDataset),
    [selectedDataset],
  )
  const effectiveAllowedMethodCodes = useMemo(
    () => (
      datasetAllowedMethodCodes
        ? datasetAllowedMethodCodes.filter((methodCode) => methodMatchesEvalLanguage(methodCode, form.evalQueryLanguage))
        : null
    ),
    [datasetAllowedMethodCodes, form.evalQueryLanguage],
  )
  const datasetAllowedMethodSet = useMemo(
    () => (effectiveAllowedMethodCodes ? new Set(effectiveAllowedMethodCodes) : null),
    [effectiveAllowedMethodCodes],
  )
  const selectableMethods = useMemo(
    () => (
      methods.filter((method) => {
        const methodCode = normalizeStrategyMethodCode(method.methodCode)
        return (!datasetAllowedMethodSet || datasetAllowedMethodSet.has(methodCode))
          && methodMatchesEvalLanguage(methodCode, form.evalQueryLanguage)
      })
    ),
    [methods, datasetAllowedMethodSet, form.evalQueryLanguage],
  )
  const selectableMethodCodes = useMemo(
    () => selectableMethods.map((method) => normalizeStrategyMethodCode(method.methodCode)).filter(Boolean),
    [selectableMethods],
  )

  useEffect(() => {
    if (form.syntheticFreeBaseline) return
    setSelectedMethods((prev) => {
      const filtered = prev
        .map(normalizeStrategyMethodCode)
        .filter((methodCode) => selectableMethodCodes.includes(methodCode))
      const next = filtered.length > 0 ? filtered : (selectableMethodCodes[0] ? [selectableMethodCodes[0]] : [])
      const current = prev.map(normalizeStrategyMethodCode)
      if (next.length === current.length && next.every((methodCode, index) => methodCode === current[index])) {
        return prev
      }
      return next
    })
  }, [selectableMethodCodes, form.syntheticFreeBaseline])

  useEffect(() => {
    const totalPages = Math.max(1, Math.ceil(tests.length / historyPageSize))
    if (historyPage > totalPages - 1) {
      setHistoryPage(totalPages - 1)
    }
  }, [tests, historyPage])

  const selectedSnapshot = useMemo(
    () => gatingBatches.find((batch) => batch.gatingBatchId === form.sourceGatingBatchId) || null,
    [gatingBatches, form.sourceGatingBatchId],
  )
  const snapshotMethodCode = selectedSnapshot?.methodCode ? String(selectedSnapshot.methodCode).toUpperCase() : null
  const selectedSnapshotAllowedForDataset = isSnapshotAllowedForMethodSet(selectedSnapshot, datasetAllowedMethodSet)
    && snapshotMatchesEvalLanguage(selectedSnapshot, form.evalQueryLanguage)
  const methodSelectionLocked = !form.syntheticFreeBaseline
    && Boolean(form.sourceGatingBatchId && snapshotMethodCode && selectedSnapshotAllowedForDataset)

  useEffect(() => {
    if (!methodSelectionLocked || !snapshotMethodCode) return
    if (selectedMethods.length === 1 && selectedMethods[0] === snapshotMethodCode) return
    setSelectedMethods([snapshotMethodCode])
  }, [methodSelectionLocked, snapshotMethodCode, selectedMethods])

  useEffect(() => {
    if (!form.rewriteEnabled && (form.selectiveRewrite || form.useSessionContext)) {
      setForm((prev) => ({ ...prev, selectiveRewrite: false, useSessionContext: false, multiSourceAnchorExpansionEnabled: false }))
    }
    if (form.rewriteEnabled && !form.selectiveRewrite && form.useSessionContext) {
      setForm((prev) => ({ ...prev, useSessionContext: false }))
    }
    if ((!form.rewriteEnabled || !form.rewriteAnchorInjectionEnabled) && form.multiSourceAnchorExpansionEnabled) {
      setForm((prev) => ({ ...prev, multiSourceAnchorExpansionEnabled: false }))
    }
  }, [form.rewriteEnabled, form.selectiveRewrite, form.useSessionContext, form.rewriteAnchorInjectionEnabled, form.multiSourceAnchorExpansionEnabled])

  useEffect(() => {
    if (!form.syntheticFreeBaseline) return
    setForm((prev) => {
      if (
        prev.runDiscipline === 'exploratory'
        && !prev.sourceGatingBatchId
        && !prev.officialGatingUngatedBatchId
        && !prev.officialGatingRuleOnlyBatchId
        && !prev.officialGatingFullGatingBatchId
        && !prev.gatingApplied
        && !prev.stageCutoffEnabled
        && !prev.rewriteEnabled
        && !prev.selectiveRewrite
        && !prev.useSessionContext
        && !prev.rewriteAnchorInjectionEnabled
        && !prev.multiSourceAnchorExpansionEnabled
      ) {
        return prev
      }
      return {
        ...prev,
        runDiscipline: 'exploratory',
        sourceGatingBatchId: '',
        officialGatingUngatedBatchId: '',
        officialGatingRuleOnlyBatchId: '',
        officialGatingFullGatingBatchId: '',
        gatingApplied: false,
        stageCutoffEnabled: false,
        stageCutoffLevel: 'rule_only',
        rewriteEnabled: false,
        selectiveRewrite: false,
        useSessionContext: false,
        rewriteAnchorInjectionEnabled: false,
        multiSourceAnchorExpansionEnabled: false,
      }
    })
  }, [form.syntheticFreeBaseline])

  useEffect(() => {
    if (!form.stageCutoffEnabled) return
    if (!form.gatingApplied || form.syntheticFreeBaseline || form.runDiscipline === 'official') {
      setForm((prev) => ({ ...prev, stageCutoffEnabled: false }))
    }
  }, [form.stageCutoffEnabled, form.gatingApplied, form.syntheticFreeBaseline, form.runDiscipline])

  const effectiveGatingPreset = form.gatingApplied ? form.gatingPreset : 'ungated'
  const stageCutoffEnabledForRun = !form.syntheticFreeBaseline && form.gatingApplied && Boolean(form.stageCutoffEnabled)
  const runGatingPreset = form.syntheticFreeBaseline
    ? 'ungated'
    : (stageCutoffEnabledForRun ? 'full_gating' : effectiveGatingPreset)
  const sourceSnapshotExpectedPreset = stageCutoffEnabledForRun ? 'full_gating' : effectiveGatingPreset
  const snapshotBatches = useMemo(
    () => gatingBatches.filter((batch) => batch && String(batch.status || '').toLowerCase() === 'completed'),
    [gatingBatches],
  )
  const datasetSnapshotBatches = useMemo(
    () => snapshotBatches.filter((batch) => (
      isSnapshotAllowedForMethodSet(batch, datasetAllowedMethodSet)
      && snapshotMatchesEvalLanguage(batch, form.evalQueryLanguage)
    )),
    [snapshotBatches, datasetAllowedMethodSet, form.evalQueryLanguage],
  )
  const sourceSnapshotOptions = useMemo(
    () => datasetSnapshotBatches.filter((batch) => batch.gatingPreset === sourceSnapshotExpectedPreset),
    [datasetSnapshotBatches, sourceSnapshotExpectedPreset],
  )
  const rawMethodCodesForRun = form.syntheticFreeBaseline
    ? []
    : (methodSelectionLocked && snapshotMethodCode ? [snapshotMethodCode] : selectedMethods)
  const methodCodesForRun = form.syntheticFreeBaseline
    ? []
    : rawMethodCodesForRun
      .map(normalizeStrategyMethodCode)
      .filter((methodCode) => (!datasetAllowedMethodSet || datasetAllowedMethodSet.has(methodCode))
        && methodMatchesEvalLanguage(methodCode, form.evalQueryLanguage))

  useEffect(() => {
    if (!form.sourceGatingBatchId) return
    const batch = gatingBatches.find((item) => item.gatingBatchId === form.sourceGatingBatchId)
    if (
      batch
      && isSnapshotAllowedForMethodSet(batch, datasetAllowedMethodSet)
      && snapshotMatchesEvalLanguage(batch, form.evalQueryLanguage)
    ) return
    setForm((prev) => ({ ...prev, sourceGatingBatchId: '' }))
  }, [form.sourceGatingBatchId, gatingBatches, datasetAllowedMethodSet, form.evalQueryLanguage])

  useEffect(() => {
    if (form.runDiscipline !== 'official' || form.officialComparisonType !== 'gating_effect') return
    if (!form.sourceGatingBatchId) return
    setForm((prev) => ({ ...prev, sourceGatingBatchId: '' }))
  }, [form.runDiscipline, form.officialComparisonType, form.sourceGatingBatchId])

  useEffect(() => {
    const resolveAllowedSnapshotId = (snapshotId) => {
      if (!snapshotId) return ''
      const batch = gatingBatches.find((item) => item.gatingBatchId === snapshotId)
      return batch
        && isSnapshotAllowedForMethodSet(batch, datasetAllowedMethodSet)
        && snapshotMatchesEvalLanguage(batch, form.evalQueryLanguage)
        ? snapshotId
        : ''
    }
    const nextUngated = resolveAllowedSnapshotId(form.officialGatingUngatedBatchId)
    const nextRuleOnly = resolveAllowedSnapshotId(form.officialGatingRuleOnlyBatchId)
    const nextFullGating = resolveAllowedSnapshotId(form.officialGatingFullGatingBatchId)
    if (
      nextUngated === form.officialGatingUngatedBatchId
      && nextRuleOnly === form.officialGatingRuleOnlyBatchId
      && nextFullGating === form.officialGatingFullGatingBatchId
    ) {
      return
    }
    setForm((prev) => ({
      ...prev,
      officialGatingUngatedBatchId: nextUngated,
      officialGatingRuleOnlyBatchId: nextRuleOnly,
      officialGatingFullGatingBatchId: nextFullGating,
    }))
  }, [
    form.officialGatingUngatedBatchId,
    form.officialGatingRuleOnlyBatchId,
    form.officialGatingFullGatingBatchId,
    gatingBatches,
    datasetAllowedMethodSet,
    form.evalQueryLanguage,
  ])

  useEffect(() => {
    setCompareRunIds((prev) => {
      const next = prev.filter((id) => tests.some((run) => run.ragTestRunId === id)).slice(0, 2)
      if (next.length === prev.length && next.every((item, index) => item === prev[index])) return prev
      return next
    })
  }, [tests])

  function isSnapshotCompatible(batch, gatingPreset, methodCodes) {
    if (!batch) return false
    if (batch.gatingPreset !== gatingPreset) return false
    if (!snapshotMatchesEvalLanguage(batch, form.evalQueryLanguage)) return false
    if (!Array.isArray(methodCodes) || methodCodes.length === 0) return true
    if (!batch.methodCode) return true
    return methodCodes.includes(String(batch.methodCode).toUpperCase())
  }

  function snapshotOptionLabel(batch, expectedPreset = null, methodCodes = methodCodesForRun) {
    const compatible = expectedPreset
      ? isSnapshotCompatible(batch, expectedPreset, methodCodes)
      : isSnapshotCompatible(batch, sourceSnapshotExpectedPreset, methodCodes)
    const runnable = Boolean(batch?.sourceGatingRunId)
    return `${shortId(batch.gatingBatchId)} | ${batch.gatingPreset} | ${batch.methodCode || '-'} | ${fmtTime(batch.finishedAt)}${runnable ? '' : ' | unavailable(no source run)'}${compatible ? '' : ' | incompatible'}`
  }

  const handleToggleMethod = (methodCode, checked) => {
    const normalizedMethodCode = normalizeStrategyMethodCode(methodCode)
    if (datasetAllowedMethodSet && !datasetAllowedMethodSet.has(normalizedMethodCode)) return
    if (!methodMatchesEvalLanguage(normalizedMethodCode, form.evalQueryLanguage)) return
    if (methodSelectionLocked) return
    setSelectedMethods((prev) => {
      const normalized = prev.map(normalizeStrategyMethodCode)
      if (checked) return normalized.includes(normalizedMethodCode) ? prev : [...normalized, normalizedMethodCode]
      return normalized.filter((value) => value !== normalizedMethodCode)
    })
  }

  const runRag = async (event) => {
    event.preventDefault()
    const syntheticFreeBaseline = Boolean(form.syntheticFreeBaseline)
    const usingDbAnn = form.retrievalBackend === 'db_ann'
    if (!syntheticFreeBaseline && methodCodesForRun.length === 0) {
      notify('최소 1개 생성 전략을 선택해야 합니다.', 'error')
      return
    }
    const languageMismatchedMethod = methodCodesForRun.find(
      (methodCode) => !methodMatchesEvalLanguage(methodCode, form.evalQueryLanguage),
    )
    if (!syntheticFreeBaseline && languageMismatchedMethod) {
      notify(`evalQueryLanguage=${form.evalQueryLanguage} and method ${languageMismatchedMethod} do not match.`, 'error')
      return
    }
    if (!form.llmModel) {
      notify('LLM 모델을 선택하세요.', 'error')
      return
    }
    if (!form.denseEmbeddingModel) {
      notify('Dense 임베딩 모델을 선택하세요.', 'error')
      return
    }
    if (usingDbAnn && form.retrieverMode === 'bm25_only') {
      notify('db-ann 검색 엔진은 dense_only 또는 hybrid 모드가 필요합니다.', 'error')
      return
    }
    if (usingDbAnn && chunkEmbeddingStatusLoading) {
      notify('청크 임베딩 상태를 확인 중입니다. 잠시 후 다시 시도하세요.', 'error')
      return
    }
    if (usingDbAnn && (!chunkEmbeddingStatus || !chunkEmbeddingStatus.ready)) {
      notify('db-ann 실행 전 청크 임베딩 생성이 필요합니다.', 'error')
      return
    }
    const officialRun = !syntheticFreeBaseline && form.runDiscipline === 'official'
    const requiresExplicitSourceSnapshot = !syntheticFreeBaseline
      && !(officialRun && form.officialComparisonType === 'gating_effect')
    const stageCutoffEnabled = !syntheticFreeBaseline && Boolean(form.stageCutoffEnabled)
    const stageCutoffLevel = stageCutoffEnabled ? (form.stageCutoffLevel || 'full_gating') : null
    if (syntheticFreeBaseline && form.runDiscipline === 'official') {
      notify('합성 질의 제외 baseline은 exploratory 실행에서만 지원됩니다.', 'error')
      return
    }
    const runGatingPreset = syntheticFreeBaseline ? 'ungated' : (stageCutoffEnabled ? 'full_gating' : effectiveGatingPreset)
    const sourceSnapshotPreset = stageCutoffEnabled ? 'full_gating' : runGatingPreset
    if (stageCutoffEnabled && officialRun) {
      notify('스테이지 컷오프는 exploratory 실행에서만 사용할 수 있습니다.', 'error')
      return
    }
    if (stageCutoffEnabled && !form.gatingApplied) {
      notify('스테이지 컷오프 사용 시 게이팅 적용이 필요합니다.', 'error')
      return
    }
    if (requiresExplicitSourceSnapshot && !form.sourceGatingBatchId) {
      notify('이 RAG 실행에는 소스 스냅샷 선택이 필요합니다.', 'error')
      return
    }
    if (stageCutoffEnabled && !form.sourceGatingBatchId) {
      notify('스테이지 컷오프 사용 시 full_gating 소스 스냅샷을 선택해야 합니다.', 'error')
      return
    }
    if (!syntheticFreeBaseline && officialRun && form.officialComparisonType === 'rewrite_effect' && !form.sourceGatingBatchId) {
      notify('공식 rewrite-effect 실행은 소스 스냅샷 선택이 필수입니다.', 'error')
      return
    }
    if (!syntheticFreeBaseline && officialRun && form.officialComparisonType === 'gating_effect') {
      if (!form.officialGatingUngatedBatchId || !form.officialGatingRuleOnlyBatchId || !form.officialGatingFullGatingBatchId) {
        notify('공식 gating-effect 실행은 ungated/rule_only/full_gating 스냅샷 3개가 모두 필요합니다.', 'error')
        return
      }
      const requiredSnapshots = [
        { id: form.officialGatingUngatedBatchId, preset: 'ungated', label: 'ungated' },
        { id: form.officialGatingRuleOnlyBatchId, preset: 'rule_only', label: 'rule_only' },
        { id: form.officialGatingFullGatingBatchId, preset: 'full_gating', label: 'full_gating' },
      ]
      for (const required of requiredSnapshots) {
        const snapshot = snapshotBatches.find((batch) => batch.gatingBatchId === required.id)
        if (!snapshot) {
          notify(`공식 gating-effect ${required.label} 스냅샷을 찾을 수 없습니다.`, 'error')
          return
        }
        if (!snapshot.sourceGatingRunId) {
          notify(`공식 gating-effect ${required.label} 스냅샷에 source_gating_run_id가 없습니다.`, 'error')
          return
        }
        if (!isSnapshotCompatible(snapshot, required.preset, methodCodesForRun)) {
          notify(`공식 gating-effect ${required.label} 스냅샷이 preset/전략과 호환되지 않습니다.`, 'error')
          return
        }
      }
    }
    if (requiresExplicitSourceSnapshot) {
      const snapshot = selectedSnapshot
      if (!snapshot) {
        notify('선택한 스냅샷을 찾을 수 없습니다. 목록을 새로고침하세요.', 'error')
        return
      }
      if (!snapshot.sourceGatingRunId) {
        notify('선택한 스냅샷에는 source_gating_run_id가 없어 실행할 수 없습니다.', 'error')
        return
      }
      if (!isSnapshotCompatible(snapshot, sourceSnapshotPreset, methodCodesForRun)) {
        notify('선택한 스냅샷이 현재 게이팅 preset/전략 조건과 호환되지 않습니다.', 'error')
        return
      }
    }
    try {
      const gatingApplied = syntheticFreeBaseline ? false : Boolean(form.gatingApplied)
      const rewriteEnabled = syntheticFreeBaseline ? false : Boolean(form.rewriteEnabled)
      const selectiveRewrite = syntheticFreeBaseline ? false : Boolean(form.selectiveRewrite)
      const useSessionContext = syntheticFreeBaseline ? false : Boolean(form.useSessionContext)
      const rewriteAnchorInjectionEnabled = syntheticFreeBaseline
        ? false
        : rewriteEnabled && Boolean(form.rewriteAnchorInjectionEnabled)
      const multiSourceAnchorExpansionEnabled = syntheticFreeBaseline
        ? false
        : rewriteEnabled && rewriteAnchorInjectionEnabled && Boolean(form.multiSourceAnchorExpansionEnabled)
      const created = await requestJson('/api/admin/console/rag/tests/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          datasetId: form.datasetId,
          domainId: domainId || null,
          evalQueryLanguage: form.evalQueryLanguage,
          runName: form.runName || null,
          methodCodes: syntheticFreeBaseline ? [] : methodCodesForRun,
          syntheticFreeBaseline,
          gatingPreset: runGatingPreset,
          sourceGatingBatchId: syntheticFreeBaseline
            ? null
            : officialRun && form.officialComparisonType === 'gating_effect'
              ? null
              : form.sourceGatingBatchId || null,
          comparisonGatingBatchIds: syntheticFreeBaseline
            ? null
            : officialRun && form.officialComparisonType === 'gating_effect'
              ? {
                  ungated: form.officialGatingUngatedBatchId || null,
                  rule_only: form.officialGatingRuleOnlyBatchId || null,
                  full_gating: form.officialGatingFullGatingBatchId || null,
                }
              : null,
          officialRun,
          officialComparisonType: officialRun ? form.officialComparisonType : null,
          gatingApplied,
          stageCutoffEnabled: stageCutoffEnabled || null,
          stageCutoffLevel,
          rewriteEnabled,
          selectiveRewrite,
          useSessionContext,
          rewriteAnchorInjectionEnabled,
          multiSourceAnchorExpansionEnabled,
          rewriteQueryProfile: rewriteEnabled ? (form.rewriteQueryProfile || runtimeOptions.defaultRewriteQueryProfile || 'compact_anchor') : 'compact_anchor',
          rewriteFailurePolicy: form.rewriteFailurePolicy || 'fail_run',
          llmModel: form.llmModel || runtimeOptions.defaultLlmModel || null,
          rewriteLlmModel: form.rewriteLlmModel || null,
          threshold: toNumber(form.threshold),
          retrievalBackend: form.retrievalBackend,
          retrievalTopK: toNumber(form.retrievalTopK),
          rerankTopN: toNumber(form.rerankTopN),
          retrieverConfig: {
            retrieverMode: form.retrieverMode,
            denseEmbeddingModel: form.denseEmbeddingModel,
            denseEmbeddingRequired: form.denseEmbeddingRequired == null ? null : Boolean(form.denseEmbeddingRequired),
            denseFallbackEnabled: form.denseFallbackEnabled == null ? null : Boolean(form.denseFallbackEnabled),
            rerankEnabled: form.retrieverRerankEnabled == null ? null : Boolean(form.retrieverRerankEnabled),
            candidatePoolK: toNumber(form.retrieverCandidatePoolK),
            denseWeight: toNumber(form.retrieverDenseWeight),
            bm25Weight: toNumber(form.retrieverBm25Weight),
            technicalWeight: toNumber(form.retrieverTechnicalWeight),
          },
        }),
      })
      const refreshTasks = [loadTests(), loadGatingBatches()]
      if (rewriteLogsLoaded) {
        refreshTasks.push(loadRewriteLogs())
      }
      if (llmJobsLoaded) {
        refreshTasks.push(loadLlmJobs())
      }
      await Promise.all(refreshTasks)
      notify('RAG 테스트를 실행했습니다.')
      openRunDetail(created.ragTestRunId)
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const executeLlmAction = async (jobId, action) => {
    try {
      await requestJson(`/api/admin/console/llm-jobs/${jobId}/${action}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })
      await loadLlmJobs()
      notify(`JOB ${action} 요청을 전송했습니다.`)
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const toggleCompareRun = (runId) => {
    const already = compareRunIds.includes(runId)
    if (!already && compareRunIds.length >= 2) {
      notify('비교 대상은 최대 2개까지 선택할 수 있습니다.', 'error')
      return
    }
    setCompareRunIds((prev) => (prev.includes(runId) ? prev.filter((id) => id !== runId) : [...prev, runId]))
  }

  const clearCompareRuns = () => {
    setCompareRunIds([])
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
            <DetailCard label="command / experiment" value={`${job.commandName || '-'} / ${job.experimentName || '-'}`} />
            <DetailCard
              label="progress"
              value={`${job.processedItems ?? 0} / ${job.totalItems ?? 0} (${job.progressPct == null ? '-' : `${Number(job.progressPct).toFixed(1)}%`})`}
            />
            <DetailCard label="job_items" value={JSON.stringify(items || [], null, 2)} />
          </div>
        ),
      })
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const openDatasetItems = async (datasetOrId) => {
    const datasetId = typeof datasetOrId === 'object' ? datasetOrId?.datasetId : datasetOrId
    const dataset = typeof datasetOrId === 'object'
      ? datasetOrId
      : datasets.find((item) => item.datasetId === datasetId)
    try {
      const rows = await requestJson(`/api/admin/console/rag/datasets/${datasetId}/items`)
      setModal({
        title: `평가 데이터셋 상세 · ${dataset?.datasetName || '이름 없음'}`,
        body: <EvalDatasetDetail dataset={dataset} items={Array.isArray(rows) ? rows : []} />,
      })
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const openRunDetail = async (runId) => {
    try {
      const payload = await requestJson(`/api/admin/console/rag/tests/${runId}?detail_limit=100`)
      const runRow = payload.run || {}
      const summary = payload.summary || {}
      const anchorSummary = payload.anchorSummary || summary.anchor_evaluation || summary.anchorEvaluation || {}
      const metricsJson = parseMetricsNode(summary.metrics_json)
      const runMetrics = extractRunMetrics(metricsJson)
      const performance = parseMetricsNode(metricsJson.performance)
      const retrievalPayload = parseMetricsNode(metricsJson.retrieval)
      const retrievalSummaryRows = Array.isArray(retrievalPayload.summary)
        ? retrievalPayload.summary.filter((row) => row && typeof row === 'object')
        : []
      const details = Array.isArray(payload.details) ? payload.details : []
      const rewriteMode = resolveRewriteMode(runRow)
      const anchorEnabled = resolveRewriteAnchorEnabled(runRow)
      const multiSourceEnabled = resolveMultiSourceAnchorEnabled(runRow)
      setModal({
        title: resolveRunDetailModalTitle(runRow),
        body: (
          <RagRunDetailModalBody
            details={details}
            runMetrics={runMetrics}
            anchorSummary={anchorSummary}
            retrievalSummaryRows={retrievalSummaryRows}
            performance={performance}
            rewriteMode={rewriteMode}
            anchorEnabled={anchorEnabled}
            multiSourceEnabled={multiSourceEnabled}
          />
        ),
      })
    } catch (error) {
      notify(error.message, 'error')
    }
  }
  const deleteRun = async (runId) => {
    if (!window.confirm('선택한 RAG 테스트 이력과 결과를 삭제할까요?')) return
    setDeletingRunId(runId)
    try {
      await requestJson(`/api/admin/console/rag/tests/${runId}`, { method: 'DELETE' })
      setCompareRunIds((prev) => prev.filter((id) => id !== runId))
      const refreshTasks = [loadTests()]
      if (rewriteLogsLoaded) {
        refreshTasks.push(loadRewriteLogs())
      }
      if (llmJobsLoaded) {
        refreshTasks.push(loadLlmJobs())
      }
      await Promise.all(refreshTasks)
      notify('RAG 테스트 이력 및 결과를 삭제했습니다.')
    } catch (error) {
      notify(error.message, 'error')
    } finally {
      setDeletingRunId('')
    }
  }

  const deleteDataset = async (datasetId) => {
    if (!window.confirm('선택한 평가 데이터셋과 연결된 RAG 테스트 이력을 삭제할까요? 실행 중인 테스트가 있으면 삭제되지 않습니다.')) return
    setDeletingDatasetId(datasetId)
    try {
      await requestJson(`/api/admin/console/rag/datasets/${datasetId}`, { method: 'DELETE' })
      setCompareRunIds([])
      await Promise.all([loadDatasets(), loadTests()])
      notify('평가 데이터셋을 삭제했습니다.')
    } catch (error) {
      notify(error.message, 'error')
    } finally {
      setDeletingDatasetId('')
    }
  }

  const openRewriteDetail = async (rewriteLogId) => {
    try {
      const payload = await requestJson(`/api/admin/console/rewrite/logs/${rewriteLogId}`)
      setModal({
        title: `재작성 로그 상세 · ${shortId(rewriteLogId)}`,
        body: (
          <div className="detail-grid detail-grid--single">
            <DetailCard label="원본 / 최종" value={`${payload.rewrite?.rawQuery || '-'}\n${payload.rewrite?.finalQuery || '-'}`} />
            <DetailCard label="결정" value={`${payload.rewrite?.decisionReason || '-'} / delta=${payload.rewrite?.confidenceDelta == null ? '-' : Number(payload.rewrite.confidenceDelta).toFixed(4)}`} />
            <DetailCard label="memory_retrievals" value={JSON.stringify(payload.memoryRetrievals || [], null, 2)} />
            <DetailCard label="candidate_logs" value={JSON.stringify(payload.candidateLogs || [], null, 2)} />
          </div>
        ),
      })
    } catch (error) {
      notify(error.message, 'error')
    }
  }

  const runMetricsMap = useMemo(() => {
    const map = new Map()
    for (const run of tests) {
      map.set(run.ragTestRunId, extractRunMetrics(run.metricsJson))
    }
    return map
  }, [tests])

  const compareRuns = useMemo(() => compareRunIds.map((id) => tests.find((run) => run.ragTestRunId === id)).filter(Boolean), [compareRunIds, tests])
  const compareRunMeta = useMemo(
    () => compareRuns.map((run, index) => {
      const slot = index === 0 ? 'A' : 'B'
      const displayName = resolveCompareRunPrimaryLabel(run, `RAG Test ${slot}`)
      return {
        key: index === 0 ? 'a' : 'b',
        slot,
        title: displayName,
        shortId: shortId(run.ragTestRunId),
        fullId: run.ragTestRunId,
        tableId: formatRunIdForTable(run.ragTestRunId),
        tableLabel: displayName,
        tableSubLabel: resolveCompareRunSecondaryLabel(run),
        datasetName: run.datasetName || '-',
        methodLabel: formatGenerationMethodLabel(run.generationMethodCodes),
        finishedAt: fmtTime(run.finishedAt || run.startedAt),
      }
    }),
    [compareRuns],
  )
  const compareMetricGroups = useMemo(() => {
    if (compareRuns.length !== 2) return []
    const [leftRun, rightRun] = compareRuns
    const leftMetrics = runMetricsMap.get(leftRun.ragTestRunId) || {}
    const rightMetrics = runMetricsMap.get(rightRun.ragTestRunId) || {}
    return METRIC_GROUP_DEFS.map((group) => {
      const rows = group.metrics.map((metricDef, metricOrder) => {
        const metricMeta = METRIC_META_MAP[metricDef.key] || metricDef
        const left = leftMetrics[metricDef.key]
        const right = rightMetrics[metricDef.key]
        const delta = left != null && right != null ? right - left : null
        const deltaRate = (left != null && right != null && left !== 0) ? ((right - left) / Math.abs(left)) * 100 : null
        return {
          ...metricMeta,
          groupKey: group.key,
          groupLabel: group.label,
          metricOrder,
          priority: KPI_METRIC_KEYS.has(metricDef.key) ? 'core' : metricMeta.priority,
          left,
          right,
          leftSampleCount: metricDef.sampleCountKey ? leftMetrics[metricDef.sampleCountKey] : null,
          rightSampleCount: metricDef.sampleCountKey ? rightMetrics[metricDef.sampleCountKey] : null,
          delta,
          deltaRate,
          outcome: resolveMetricOutcome(metricDef, left, right),
          scaleMax: metricScaleMax(metricDef, left, right),
        }
      }).filter((row) => row.left != null || row.right != null)
      return {
        key: group.key,
        label: group.label,
        description: group.description,
        rows,
      }
    }).filter((group) => group.rows.length > 0)
  }, [compareRuns, runMetricsMap])
  const compareMetricRows = useMemo(() => compareMetricGroups.flatMap((group) => group.rows), [compareMetricGroups])
  const compareWorkspaceGroups = useMemo(() => compareMetricGroups.map((group) => ({
    ...group,
    rows: [...group.rows].sort(compareTableRowSorter),
  })), [compareMetricGroups])
  const compareTableGroups = useMemo(() => compareMetricGroups.map((group) => {
    const rows = [...group.rows].sort(compareTableRowSorter)
    const outcomeSummary = summarizeGroupRows(rows)
    const leftLabel = compareRunMeta[0]?.tableLabel || 'A'
    const rightLabel = compareRunMeta[1]?.tableLabel || 'B'
    const summaryParts = [
      `${compactText(rightLabel, 24)} 우세 ${outcomeSummary.right}`,
      `${compactText(leftLabel, 24)} 우세 ${outcomeSummary.left}`,
      `변화 없음 ${outcomeSummary.tie}`,
    ]
    if (outcomeSummary.na > 0) {
      summaryParts.push(`데이터 없음 ${outcomeSummary.na}`)
    }
    return {
      ...group,
      rows,
      outcomeSummary,
      summaryLabel: summaryParts.join(' | '),
    }
  }), [compareMetricGroups, compareRunMeta])
  const compareSummary = useMemo(() => {
    if (compareMetricRows.length === 0 || compareRunMeta.length !== 2) return null
    const comparableRows = compareMetricRows.filter((row) => row.outcome !== 'na')
    if (comparableRows.length === 0) return null
    const leftLabel = compareRunMeta[0]?.tableLabel || 'A'
    const rightLabel = compareRunMeta[1]?.tableLabel || 'B'
    const compactLeftLabel = compactText(leftLabel, 28)
    const compactRightLabel = compactText(rightLabel, 28)
    let scoreA = 0
    let scoreB = 0
    for (const row of comparableRows) {
      const weight = row.priority === 'core' ? 2 : 1
      if (row.outcome === 'left') scoreA += weight
      if (row.outcome === 'right') scoreB += weight
    }
    const overallWinner = scoreA === scoreB ? 'tie' : scoreB > scoreA ? 'right' : 'left'
    const retrievalRows = comparableRows.filter((row) => COMPARE_FOCUS_RETRIEVAL_KEYS.includes(row.key))
    const retrievalAvgA = averageMetric(retrievalRows.map((row) => row.left))
    const retrievalAvgB = averageMetric(retrievalRows.map((row) => row.right))
    const retrievalDelta = retrievalAvgA != null && retrievalAvgB != null ? retrievalAvgB - retrievalAvgA : null
    const focusLatencyRows = compareMetricRows.filter((row) => COMPARE_FOCUS_LATENCY_KEYS.includes(row.key))
    const queryEvalLatencyRow = focusLatencyRows.find((row) => row.key === 'avg_query_eval_total_latency_ms') || null
    const finalRewriteLatencyRow = focusLatencyRows.find((row) => row.key === 'avg_final_rewrite_latency_ms') || null
    const pureRewriteLatencyRow = focusLatencyRows.find((row) => row.key === 'avg_pure_rewrite_latency_ms') || null
    const headline = overallWinner === 'tie'
      ? '뚜렷한 우세 없음'
      : overallWinner === 'right'
      ? `${compactRightLabel} 우세`
      : `${compactLeftLabel} 우세`

    const buildLatencyCard = (label, row) => {
      const insight = row ? buildWorkspaceChangeInsight(row) : null
      return {
        label,
        value: insight ? insight.summary : '-',
        tone: insight?.speedTone || 'neutral',
      }
    }
    const retrievalTone = retrievalDelta == null || retrievalDelta === 0 ? 'neutral' : retrievalDelta > 0 ? 'right' : 'left'

    return {
      headline,
      cards: [
        {
          label: '종합 우세',
          value: overallWinner === 'tie' ? '동률' : overallWinner === 'right' ? compactRightLabel : compactLeftLabel,
          tone: overallWinner === 'tie' ? 'neutral' : overallWinner,
        },
        {
          label: '검색 핵심 차이',
          value: retrievalDelta == null ? '-' : formatDelta(retrievalDelta, { precision: 3 }),
          tone: retrievalTone,
        },
        buildLatencyCard('질의 전체 평가 평균 시간', queryEvalLatencyRow),
        buildLatencyCard('최종 재작성 확정 평균 시간', finalRewriteLatencyRow),
        buildLatencyCard('순수 질의 재작성 평균 시간', pureRewriteLatencyRow),
      ],
    }
  }, [compareMetricRows, compareRunMeta])

  const historyTotalPages = Math.max(1, Math.ceil(tests.length / historyPageSize))
  const currentHistoryPage = Math.min(historyPage, historyTotalPages - 1)
  const pagedTests = tests.slice(currentHistoryPage * historyPageSize, (currentHistoryPage + 1) * historyPageSize)

  const latestSummaryCards = useMemo(() => {
    const completedRuns = tests.filter((run) => String(run.status || '').toLowerCase() === 'completed')
    const lastRun = completedRuns[0]
    const lastMetrics = lastRun ? extractRunMetrics(lastRun.metricsJson) : {}
    const latestQueryEval = formatTableMetricValue(lastMetrics.avg_query_eval_total_latency_ms, PERFORMANCE_METRIC_DEFS[0]).main
    const latestFinalRewrite = formatTableMetricValue(lastMetrics.avg_final_rewrite_latency_ms, PERFORMANCE_METRIC_DEFS[1]).main
    const latestQueryEvalMeta = lastRun
      ? (lastMetrics.legacy_performance ? LEGACY_PERFORMANCE_MESSAGE : formatMetricSampleBasis(PERFORMANCE_METRIC_DEFS[0], lastMetrics))
      : '-'
    const latestFinalRewriteMeta = lastRun
      ? (lastMetrics.legacy_performance ? LEGACY_PERFORMANCE_MESSAGE : formatMetricSampleBasis(PERFORMANCE_METRIC_DEFS[1], lastMetrics))
      : '-'
    return [
      { label: '완료 실행', value: String(completedRuns.length), meta: '전체 이력' },
      { label: '비교 선택', value: String(compareRunIds.length), meta: compareRunIds.length === 2 ? '비교 가능' : '2개 선택 필요' },
      { label: '최근 Recall@5', value: formatMetric(lastMetrics.recall_at_5), meta: lastRun ? `run ${shortId(lastRun.ragTestRunId)}` : '완료 실행 없음' },
      { label: '최근 nDCG@10', value: formatMetric(lastMetrics.ndcg_at_10), meta: lastRun ? fmtTime(lastRun.finishedAt || lastRun.startedAt) : '-' },
      {
        label: '최근 평가 시간',
        value: lastRun ? (lastMetrics.legacy_performance ? '레거시' : latestQueryEval) : '-',
        meta: latestQueryEvalMeta,
      },
      {
        label: '최근 재작성 시간',
        value: lastRun ? (lastMetrics.legacy_performance ? '레거시' : latestFinalRewrite) : '-',
        meta: latestFinalRewriteMeta,
      },
    ]
  }, [tests, compareRunIds])

  const selectedMethodSummary = methodCodesForRun.length > 0 ? methodCodesForRun.join(', ') : '합성 질의 제외'
  const selectedSnapshotSummary = selectedSnapshot
    ? `${shortId(selectedSnapshot.gatingBatchId)} / ${selectedSnapshot.gatingPreset} / ${selectedSnapshot.methodCode || '-'}`
    : (form.syntheticFreeBaseline ? '미사용' : '필수')
  const retrievalBalanceItems = [
    { label: 'Dense', value: form.retrieverDenseWeight, tone: 'blue' },
    { label: 'BM25', value: form.retrieverBm25Weight, tone: 'green' },
    { label: '기술', value: form.retrieverTechnicalWeight, tone: 'amber' },
  ]
  const runPreviewItems = [
    { label: '데이터셋', value: selectedDataset ? `${selectedDataset.datasetName} (${selectedDataset.totalItems})` : '-' },
    { label: '언어', value: form.evalQueryLanguage },
    { label: '실행 규칙', value: form.syntheticFreeBaseline ? '탐색 baseline' : form.runDiscipline },
    { label: '전략', value: selectedMethodSummary },
    { label: '스냅샷', value: selectedSnapshotSummary },
    { label: '게이팅', value: runGatingPreset },
    { label: '재작성', value: form.rewriteEnabled ? (form.selectiveRewrite ? '선택적' : '항상') : '꺼짐' },
    { label: '재작성 프로필', value: form.rewriteEnabled ? form.rewriteQueryProfile : 'compact_anchor' },
    { label: 'Rewrite LLM', value: form.rewriteLlmModel || form.llmModel || runtimeOptions.defaultLlmModel || '-' },
    { label: '검색', value: `${form.retrievalBackend} / ${retrieverModeLabel(form.retrieverMode)}` },
  ]

  return (
    <>
      <section className="admin-card experiment-builder-card">
        <div className="table-title">RAG 품질/성능 테스트 실행</div>
        <p className="panel-subtitle">
          스냅샷 기반 재현성, 재작성 전략, 검색 파라미터를 실험 단위로 비교합니다.
        </p>
        <SectionHeader
          eyebrow="실험 빌더"
          title="RAG 품질/성능 실행"
        />
        <form className="experiment-builder-form" onSubmit={runRag}>
          <div className="experiment-builder-layout">
            <div className="experiment-builder-main">
              <ExperimentSection
                title="실험 개요"
                description="데이터셋, 실행 규칙, 비교 방식, 스냅샷, 생성 전략을 선택합니다."
                badge={form.evalQueryLanguage}
              >
                <div className="form-grid form-grid--2">
            <label className="filter-field">평가 데이터셋
              <select value={form.datasetId} onChange={(event) => handleDatasetChange(event.target.value)}>
                {datasets.map((dataset) => <option key={dataset.datasetId} value={dataset.datasetId}>{dataset.datasetName} ({dataset.totalItems})</option>)}
              </select>
              <span className="field-hint">테스트 입력 샘플 집합입니다.</span>
            </label>
            <label className="filter-field">평가 질의 언어
              <select value={form.evalQueryLanguage} onChange={(event) => setForm((prev) => ({ ...prev, evalQueryLanguage: event.target.value }))}>
                <option value="ko">ko</option>
                <option value="en">en</option>
              </select>
              <span className="field-hint">재작성/검색 입력 언어</span>
            </label>
            <label className="filter-field">테스트 이름
              <input
                value={form.runName}
                maxLength={120}
                placeholder="Hybrid v1"
                onChange={(event) => setForm((prev) => ({ ...prev, runName: event.target.value }))}
              />
              <span className="field-hint">run_label</span>
            </label>
            <label className="filter-field">실행 규칙
              <select
                value={form.runDiscipline}
                disabled={form.syntheticFreeBaseline}
                onChange={(event) => setForm((prev) => ({ ...prev, runDiscipline: event.target.value }))}
              >
                <option value="exploratory">exploratory</option>
                <option value="official">official</option>
              </select>
              <span className="field-hint">official은 스냅샷/비교 조건을 검증합니다.</span>
            </label>
            <label className="filter-field">공식 비교 유형
              <select
                value={form.officialComparisonType}
                disabled={form.syntheticFreeBaseline || form.runDiscipline !== 'official'}
                onChange={(event) => setForm((prev) => ({ ...prev, officialComparisonType: event.target.value }))}
              >
                <option value="rewrite_effect">rewrite_effect</option>
                <option value="gating_effect">gating_effect</option>
              </select>
              <span className="field-hint">official은 비교축 하나만 허용합니다.</span>
            </label>
            {!form.syntheticFreeBaseline && form.runDiscipline === 'official' && form.officialComparisonType === 'gating_effect' && (
              <>
                <label className="filter-field">공식 스냅샷 (ungated)
                  <select value={form.officialGatingUngatedBatchId} onChange={(event) => setForm((prev) => ({ ...prev, officialGatingUngatedBatchId: event.target.value }))}>
                    <option value="">ungated 스냅샷 선택</option>
                    {datasetSnapshotBatches
                      .filter((batch) => isSnapshotCompatible(batch, 'ungated', methodCodesForRun))
                      .map((batch) => (
                        <option key={batch.gatingBatchId} value={batch.gatingBatchId}>
                          {snapshotOptionLabel(batch, 'ungated')}
                        </option>
                      ))}
                  </select>
                  <span className="field-hint">gating-effect 비교용 ungated 스냅샷</span>
                </label>
                <label className="filter-field">공식 스냅샷 (rule_only)
                  <select value={form.officialGatingRuleOnlyBatchId} onChange={(event) => setForm((prev) => ({ ...prev, officialGatingRuleOnlyBatchId: event.target.value }))}>
                    <option value="">rule_only 스냅샷 선택</option>
                    {datasetSnapshotBatches
                      .filter((batch) => isSnapshotCompatible(batch, 'rule_only', methodCodesForRun))
                      .map((batch) => (
                        <option key={batch.gatingBatchId} value={batch.gatingBatchId}>
                          {snapshotOptionLabel(batch, 'rule_only')}
                        </option>
                      ))}
                  </select>
                  <span className="field-hint">gating-effect 비교용 rule_only 스냅샷</span>
                </label>
                <label className="filter-field">공식 스냅샷 (full_gating)
                  <select value={form.officialGatingFullGatingBatchId} onChange={(event) => setForm((prev) => ({ ...prev, officialGatingFullGatingBatchId: event.target.value }))}>
                    <option value="">full_gating 스냅샷 선택</option>
                    {datasetSnapshotBatches
                      .filter((batch) => isSnapshotCompatible(batch, 'full_gating', methodCodesForRun))
                      .map((batch) => (
                        <option key={batch.gatingBatchId} value={batch.gatingBatchId}>
                          {snapshotOptionLabel(batch, 'full_gating')}
                        </option>
                      ))}
                  </select>
                  <span className="field-hint">gating-effect 비교용 full_gating 스냅샷</span>
                </label>
              </>
            )}
            <label className="filter-field">게이팅 스냅샷
              <select
                value={form.sourceGatingBatchId}
                disabled={form.syntheticFreeBaseline || (form.runDiscipline === 'official' && form.officialComparisonType === 'gating_effect')}
                onChange={(event) => setForm((prev) => ({ ...prev, sourceGatingBatchId: event.target.value }))}
              >
                <option value="">
                  {form.syntheticFreeBaseline
                    ? '합성 질의 제외 baseline에서는 미사용'
                    : form.runDiscipline === 'official'
                    ? (form.officialComparisonType === 'gating_effect' ? 'official gating-effect에서는 미사용' : '스냅샷 선택 필수')
                    : '스냅샷 선택 필수'}
                </option>
                {sourceSnapshotOptions.map((batch) => {
                  return (
                    <option key={batch.gatingBatchId} value={batch.gatingBatchId}>
                      {snapshotOptionLabel(batch, null, [])}
                    </option>
                  )
                })}
              </select>
              <span className="field-hint">
                {form.runDiscipline === 'official' && form.officialComparisonType === 'gating_effect'
                  ? 'official gating-effect는 위 3개 스냅샷을 사용합니다.'
                  : '완료된 게이팅 배치만 표시합니다.'}
              </span>
            </label>
            <label className="filter-field">게이팅 프리셋
              <select
                value={runGatingPreset}
                disabled={form.syntheticFreeBaseline || !form.gatingApplied || stageCutoffEnabledForRun}
                onChange={(event) => setForm((prev) => ({ ...prev, gatingPreset: event.target.value }))}
              >
                <option value="ungated">ungated</option>
                <option value="rule_only">rule_only</option>
                <option value="rule_plus_llm">rule_plus_llm</option>
                <option value="full_gating">full_gating</option>
              </select>
              <span className="field-hint">
                {form.gatingApplied ? '검색 후보 게이팅 단계를 선택합니다.' : '게이팅 미반영 시 ungated로 고정됩니다.'}
              </span>
            </label>
            <label className="filter-field">생성 전략
              <div className="method-row">
                {selectableMethods.map((method) => {
                  const methodCode = normalizeStrategyMethodCode(method.methodCode)
                  return (
                    <label
                      key={methodCode}
                      className={`check-pill ${methodCodesForRun.includes(methodCode) ? 'is-active' : ''} ${(form.syntheticFreeBaseline || methodSelectionLocked) ? 'is-disabled' : ''}`}
                    >
                      <input
                        type="checkbox"
                        checked={methodCodesForRun.includes(methodCode)}
                        disabled={form.syntheticFreeBaseline || methodSelectionLocked}
                        onChange={(event) => handleToggleMethod(methodCode, event.target.checked)}
                      />
                      <span className="check-pill__box" aria-hidden="true">{methodCodesForRun.includes(methodCode) ? '✓' : ''}</span>
                      <span className="check-pill__text">{methodCode}</span>
                    </label>
                  )
                })}
              </div>
              <span className="field-hint">
                {form.syntheticFreeBaseline
                  ? '합성 질의 제외 baseline에서는 비활성화됩니다.'
                  : methodSelectionLocked
                  ? `스냅샷 전략(${snapshotMethodCode}) 기준으로 고정됩니다.`
                  : '스냅샷 미선택 또는 legacy 스냅샷에서는 수동 선택이 필요합니다.'}
              </span>
            </label>
                </div>
              </ExperimentSection>

              <ExperimentSection
                title="재작성 전략"
                description="재작성, 선택 적용, 컷오프, 후보 수를 설정합니다."
                badge={form.rewriteEnabled ? '재작성 켬' : '재작성 끔'}
              >
                <div className="form-grid form-grid--3">
            <label className="filter-field filter-field--small">컷오프 단계
              <select
                value={form.stageCutoffLevel}
                disabled={form.syntheticFreeBaseline || !form.gatingApplied || !form.stageCutoffEnabled || form.runDiscipline === 'official'}
                onChange={(event) => setForm((prev) => ({ ...prev, stageCutoffLevel: event.target.value }))}
              >
                <option value="rule_only">rule_only</option>
                <option value="rule_plus_llm">rule_plus_llm</option>
                <option value="utility">utility</option>
                <option value="diversity">diversity</option>
                <option value="full_gating">full_gating</option>
              </select>
              <span className="field-hint">full_gating 배치 기준 컷오프 단계</span>
            </label>
            <label className="filter-field">LLM 모델
              <select value={form.llmModel} onChange={(event) => setForm((prev) => ({ ...prev, llmModel: event.target.value }))}>
                <option value="" disabled>LLM 모델 선택</option>
                {runtimeOptions.llmModels.map((model) => <option key={model} value={model}>{model}</option>)}
              </select>
              <span className="field-hint">생성/평가/재작성 공통 모델</span>
            </label>
            <label className="filter-field">Rewrite LLM
              <select
                value={form.rewriteLlmModel}
                disabled={!form.rewriteEnabled}
                onChange={(event) => setForm((prev) => ({ ...prev, rewriteLlmModel: event.target.value }))}
              >
                <option value="">공통 LLM 사용</option>
                {runtimeOptions.llmModels.map((model) => <option key={model} value={model}>{model}</option>)}
              </select>
              <span className="field-hint">쿼리 재작성 단계만 별도 모델로 실행</span>
            </label>
            <label className="filter-field filter-field--small">재작성 프로필
              <select
                value={form.rewriteQueryProfile}
                disabled={!form.rewriteEnabled}
                onChange={(event) => setForm((prev) => ({ ...prev, rewriteQueryProfile: event.target.value }))}
              >
                {(runtimeOptions.rewriteQueryProfiles.length > 0
                  ? runtimeOptions.rewriteQueryProfiles
                  : [form.rewriteQueryProfile || runtimeOptions.defaultRewriteQueryProfile || 'compact_anchor']
                ).filter(Boolean).map((profile) => (
                  <option key={profile} value={profile}>{profile}</option>
                ))}
              </select>
              <span className="field-hint">compact_anchor / detailed_intent</span>
            </label>
            <label className="filter-field filter-field--small">재작성 임계값
              <div className="slider-field">
                <input
                  type="range"
                  min="0"
                  max="1"
                  step="0.01"
                  value={form.threshold}
                  disabled={!form.rewriteEnabled || !form.selectiveRewrite}
                  onChange={(event) => setForm((prev) => ({ ...prev, threshold: event.target.value }))}
                  aria-label="재작성 임계값 슬라이더"
                />
                <input
                  type="number"
                  min="0"
                  max="1"
                  step="0.01"
                  value={form.threshold}
                  disabled={!form.rewriteEnabled || !form.selectiveRewrite}
                  onChange={(event) => setForm((prev) => ({ ...prev, threshold: event.target.value }))}
                />
              </div>
              <span className="field-hint">selective 모드의 후보 질의 채택 기준</span>
            </label>
            <label className="filter-field filter-field--small">검색 Top-K
              <input type="number" min="1" value={form.retrievalTopK} onChange={(event) => setForm((prev) => ({ ...prev, retrievalTopK: event.target.value }))} />
              <span className="field-hint">검색 단계 후보 청크 수</span>
            </label>
            <label className="filter-field filter-field--small">재정렬 Top-N
              <input type="number" min="1" value={form.rerankTopN} onChange={(event) => setForm((prev) => ({ ...prev, rerankTopN: event.target.value }))} />
              <span className="field-hint">답변 평가 전 최종 재정렬 개수</span>
            </label>
                </div>
              </ExperimentSection>

              <ExperimentSection
                title="검색 설정"
                description="검색 엔진, 모드, 후보 수, 가중치를 설정합니다."
                badge={retrieverModeLabel(form.retrieverMode)}
              >
                <div className="form-grid form-grid--3">
            <label className="filter-field filter-field--small">검색 엔진
              <select
                value={form.retrievalBackend}
                onChange={(event) => setForm((prev) => ({ ...prev, retrievalBackend: event.target.value }))}
              >
                {(runtimeOptions.retrievalBackends.length > 0 ? runtimeOptions.retrievalBackends : [form.retrievalBackend])
                  .filter(Boolean)
                  .map((backend) => (
                    <option key={backend} value={backend}>{backend}</option>
                  ))}
              </select>
              <span className="field-hint">local / db-ann 선택</span>
            </label>
            <label className="filter-field filter-field--small">검색 모드
              <select value={form.retrieverMode} onChange={(event) => setForm((prev) => ({
                ...prev,
                ...retrieverPresetForMode(
                  event.target.value,
                  prev.denseEmbeddingModel || runtimeOptions.defaultDenseEmbeddingModel,
                  runtimeOptions.retrieverModeDefaults,
                ),
              }))}>
                {(runtimeOptions.retrieverModes.length > 0 ? runtimeOptions.retrieverModes : [form.retrieverMode]).filter(Boolean).map((mode) => (
                  <option key={mode} value={mode}>{retrieverModeLabel(mode)}</option>
                ))}
              </select>
              <span className="field-hint">BM25/Dense/Hybrid 랭킹</span>
            </label>
            <label className="filter-field">Dense 모델
              <select
                value={form.denseEmbeddingModel}
                disabled={form.retrieverMode === 'bm25_only'}
                onChange={(event) => setForm((prev) => ({ ...prev, denseEmbeddingModel: event.target.value }))}
              >
                {runtimeOptions.denseEmbeddingModels.map((model) => <option key={model} value={model}>{model}</option>)}
              </select>
              <span className="field-hint">Dense 검색용 임베딩 모델</span>
            </label>
            <label className="filter-field filter-field--small">검색 후보 수
              <input type="number" min="1" value={form.retrieverCandidatePoolK} disabled readOnly />
              <span className="field-hint">로컬 랭킹 후보 풀</span>
            </label>
            <label className="filter-field filter-field--small">Dense 가중치
              <input type="number" min="0" max="1" step="0.01" value={form.retrieverDenseWeight} disabled readOnly />
            </label>
            <label className="filter-field filter-field--small">BM25 가중치
              <input type="number" min="0" max="1" step="0.01" value={form.retrieverBm25Weight} disabled readOnly />
            </label>
            <label className="filter-field filter-field--small">기술어 가중치
              <input type="number" min="0" max="1" step="0.01" value={form.retrieverTechnicalWeight} disabled readOnly />
            </label>
                </div>
                <BalanceBar items={retrievalBalanceItems} />

                {form.retrievalBackend === 'db_ann' && (
            <div className="state-note">
              <strong>DB ANN 준비 상태:</strong>{' '}
              {chunkEmbeddingStatusLoading
                ? '확인 중'
                : chunkEmbeddingStatus
                  ? `${chunkEmbeddingStatus.embeddingModel} / ${chunkEmbeddingStatus.materializedChunkCount} of ${chunkEmbeddingStatus.totalChunkCount} chunks / ${chunkEmbeddingStatus.ready ? '준비됨' : '생성 필요'}`
                  : '알 수 없음'}
              <div className="form-actions" style={{ marginTop: 8 }}>
                <button
                  type="button"
                  className="button"
                  onClick={materializeChunkEmbeddings}
                  disabled={chunkEmbeddingMaterializing || chunkEmbeddingStatusLoading || (chunkEmbeddingStatus && chunkEmbeddingStatus.ready)}
                >
                  {chunkEmbeddingMaterializing ? '생성 중...' : '청크 임베딩 생성'}
                </button>
                <button
                  type="button"
                  className="button"
                  onClick={() => loadChunkEmbeddingStatus(form.denseEmbeddingModel).catch((error) => notify(error.message, 'error'))}
                  disabled={chunkEmbeddingStatusLoading || !form.denseEmbeddingModel}
                >
                  상태 새로고침
                </button>
              </div>
            </div>
                )}
              </ExperimentSection>

              <ExperimentSection
                title="고급 옵션"
                description="Baseline, 게이팅 반영, 재작성 검색, 실패 정책"
                badge="기본 접힘"
                collapsible
                defaultOpen={false}
              >
                <div className="checkbox-row">
            <label className={`check-pill ${form.syntheticFreeBaseline ? 'is-active' : ''}`}>
              <input type="checkbox" checked={form.syntheticFreeBaseline} onChange={(event) => setForm((prev) => ({ ...prev, syntheticFreeBaseline: event.target.checked }))} />
              <span className="check-pill__box" aria-hidden="true">{form.syntheticFreeBaseline ? '✓' : ''}</span>
              <span className="check-pill__text">합성 질의 제외</span>
            </label>
            <label className={`check-pill ${form.stageCutoffEnabled ? 'is-active' : ''} ${(form.syntheticFreeBaseline || !form.gatingApplied || form.runDiscipline === 'official') ? 'is-disabled' : ''}`}>
              <input type="checkbox" checked={form.stageCutoffEnabled} disabled={form.syntheticFreeBaseline || !form.gatingApplied || form.runDiscipline === 'official'} onChange={(event) => setForm((prev) => ({ ...prev, stageCutoffEnabled: event.target.checked }))} />
              <span className="check-pill__box" aria-hidden="true">{form.stageCutoffEnabled ? '✓' : ''}</span>
              <span className="check-pill__text">스테이지 컷오프</span>
            </label>
            <label><input type="checkbox" checked={form.gatingApplied} disabled={form.syntheticFreeBaseline} onChange={(event) => setForm((prev) => ({ ...prev, gatingApplied: event.target.checked }))} />게이팅 반영</label>
            <label><input type="checkbox" checked={form.rewriteEnabled} disabled={form.syntheticFreeBaseline} onChange={(event) => setForm((prev) => ({ ...prev, rewriteEnabled: event.target.checked }))} />재작성 사용</label>
            <label><input type="checkbox" checked={form.selectiveRewrite} disabled={form.syntheticFreeBaseline || !form.rewriteEnabled} onChange={(event) => setForm((prev) => ({ ...prev, selectiveRewrite: event.target.checked, useSessionContext: event.target.checked ? prev.useSessionContext : false }))} />선택 적용</label>
            <label><input type="checkbox" checked={form.useSessionContext} disabled={form.syntheticFreeBaseline || !form.rewriteEnabled || !form.selectiveRewrite} onChange={(event) => setForm((prev) => ({ ...prev, useSessionContext: event.target.checked }))} />세션 문맥</label>
            <label><input type="checkbox" checked={form.rewriteAnchorInjectionEnabled} disabled={form.syntheticFreeBaseline || !form.rewriteEnabled} onChange={(event) => setForm((prev) => ({ ...prev, rewriteAnchorInjectionEnabled: event.target.checked }))} />앵커 주입</label>
            <label><input type="checkbox" checked={form.multiSourceAnchorExpansionEnabled} disabled={form.syntheticFreeBaseline || !form.rewriteEnabled || !form.rewriteAnchorInjectionEnabled} onChange={(event) => setForm((prev) => ({ ...prev, multiSourceAnchorExpansionEnabled: event.target.checked }))} />multi-source hints</label>
            <label className="rewrite-strategy-field">
              <span className="rewrite-strategy-field__label">재작성 실패 정책</span>
              <div className="rewrite-strategy-field__control">
                <select
                  value={form.rewriteFailurePolicy}
                  disabled={form.syntheticFreeBaseline || !form.rewriteEnabled}
                  onChange={(event) => setForm((prev) => ({ ...prev, rewriteFailurePolicy: event.target.value }))}
                >
                  {runtimeOptions.rewriteFailurePolicies.map((policy) => (
                    <option key={policy} value={policy}>{policy}</option>
                  ))}
                </select>
              </div>
            </label>
          </div>

          <div className="state-note">
            <strong>현재 RAG rewrite 파이프라인:</strong> Synthetic memory는 LLM 재작성 예시로만 사용됩니다.
            Synthetic memory 자체로 별도 검색하거나 raw 검색 결과와 병합하지 않습니다.
            Rewrite 결과가 raw baseline보다 개선될 때만 최종 쿼리로 채택됩니다.
            최종 평가는 raw query 또는 채택된 rewritten query 중 하나의 retrieval 결과만 기준으로 계산됩니다.
          </div>
          <div className="state-note">
            <strong>평가 단계:</strong> A. Raw Query Retrieval baseline을 먼저 계산합니다. B. raw query로 유사 synthetic query examples를 찾고 LLM prompt에만 넣습니다. C. LLM Query Rewrite가 final rewritten query 후보를 만듭니다. D. Rewrite Adoption은 raw baseline retrieval과 rewrite retrieval을 비교합니다. E. Final Evaluation은 채택된 final query 하나의 Recall@5, Hit@5, MRR@10, nDCG@10만 계산합니다.
          </div>

          {selectedSnapshot && !form.syntheticFreeBaseline && (
            <div className="state-note">
              <strong>선택 스냅샷:</strong> {shortId(selectedSnapshot.gatingBatchId)} / preset {selectedSnapshot.gatingPreset} / 전략 {selectedSnapshot.methodCode || '-'} / source run {selectedSnapshot.sourceGatingRunId ? '사용 가능' : '없음'}
            </div>
          )}

              </ExperimentSection>

              <div className="form-actions form-actions--end">
                <button type="submit" className="button button--success">테스트 실행</button>
                <button type="button" className="button" onClick={() => Promise.all([loadTests(), loadGatingBatches()]).catch((error) => notify(error.message, 'error'))}>목록 새로고침</button>
              </div>
            </div>
            <ConfigSummaryCard title="실험 요약" items={runPreviewItems} />
          </div>
        </form>
      </section>

      <section className="summary-grid">
        {latestSummaryCards.map((card) => (
          <article key={card.label} className="summary-card">
            <div className="summary-card__label">{card.label}</div>
            <div className="summary-card__value">{card.value}</div>
            <div className="summary-card__meta">{card.meta}</div>
          </article>
        ))}
      </section>

      <section className="table-shell compare-shell">
        <div className="table-header">
          <div className="table-title">실험 비교 워크스페이스</div>
        </div>
        <div className="compare-body">
          <div className="compare-header-subtitle">카드와 테이블에서 동일 metric을 연결해 읽을 수 있습니다.</div>
          {compareRuns.length !== 2 && (
            <div className="empty-state">
              비교할 테스트 2개를 아래 실행 이력 테이블에서 선택하세요.
            </div>
          )}
          {compareRuns.length === 2 && (
            <div className="compare-workspace">
              <div className="compare-run-grid">
                {compareRunMeta.map((run) => (
                  <article key={run.key} className={`compare-run-card compare-run-card--${run.key}`}>
                    <div className="compare-run-card__title-row">
                      <span className={`compare-run-dot compare-run-dot--${run.key}`} aria-hidden="true" />
                      <span className="compare-run-card__tag">{run.slot}</span>
                    </div>
                    <div className="compare-run-card__name" title={run.tableLabel}>{run.tableLabel}</div>
                    <div className="compare-run-card__id" title={run.fullId}>{run.tableId}</div>
                    <div className="compare-run-card__meta">{run.tableSubLabel}</div>
                    <div className="compare-run-card__meta">{run.methodLabel}</div>
                  </article>
                ))}
              </div>

              {compareSummary && (
                <section className="compare-overview">
                  <div className="compare-overview__headline">{compareSummary.headline}</div>
                  <div className="compare-overview__cards">
                    {compareSummary.cards.map((card) => (
                      <article key={card.label} className="compare-overview-card" data-tone={card.tone || 'neutral'}>
                        <div className="compare-overview-card__label">{card.label}</div>
                        <div className="compare-overview-card__value">{card.value}</div>
                        {card.meta && <div className="compare-overview-card__meta">{card.meta}</div>}
                      </article>
                    ))}
                  </div>
                </section>
              )}

              {compareWorkspaceGroups.map((group) => (
                <section key={group.key} className={`compare-group-section compare-group-section--${group.key}`}>
                  <header className="compare-group-section__header">
                    <h3>{group.label}</h3>
                    <p>{group.description}</p>
                  </header>
                  <div className="compare-card-grid">
                    {group.rows.map((row) => {
                      const leftPct = row.left == null ? 0 : row.left === 0 ? 0 : Math.max(8, Math.min(100, (row.left / row.scaleMax) * 100))
                      const rightPct = row.right == null ? 0 : row.right === 0 ? 0 : Math.max(8, Math.min(100, (row.right / row.scaleMax) * 100))
                      const linkedClass = activeCompareMetricKey === row.key ? 'is-linked' : ''
                      const coreClass = row.priority === 'core' ? 'is-core' : ''
                      const leftValue = formatWorkspaceMetricValue(row.left, row)
                      const rightValue = formatWorkspaceMetricValue(row.right, row)
                      const deltaValue = formatWorkspaceDeltaValue(row)
                      const deltaRateText = formatWorkspaceDeltaRate(row.deltaRate)
                      const changeInsight = buildWorkspaceChangeInsight(row)
                      const leftLeadingClass = row.outcome === 'left' ? 'is-leading' : ''
                      const rightLeadingClass = row.outcome === 'right' ? 'is-leading' : ''
                      const leftRunLabel = compareRunMeta[0]?.tableLabel || 'A'
                      const rightRunLabel = compareRunMeta[1]?.tableLabel || 'B'
                      const supportText = metricSupportText(row)
                      return (
                        <article
                          key={row.key}
                          className={`compare-metric-card ${coreClass} ${linkedClass}`}
                          onMouseEnter={() => setActiveCompareMetricKey(row.key)}
                          onMouseLeave={() => setActiveCompareMetricKey('')}
                          onFocus={() => setActiveCompareMetricKey(row.key)}
                          onBlur={() => setActiveCompareMetricKey('')}
                          tabIndex={0}
                        >
                          <div className="compare-metric-card__title-row">
                            <div className="compare-metric-card__title">{row.label}</div>
                            <div className="compare-metric-card__badges">
                              {row.priority === 'core' && <span className="metric-chip metric-chip--core">핵심 KPI</span>}
                              <span className={`metric-chip metric-chip--${row.outcome}`}>{compareOutcomeLabel(row.outcome, leftRunLabel, rightRunLabel)}</span>
                            </div>
                          </div>
                          {supportText && <div className="compare-metric-card__hint">{supportText}</div>}
                          <div className={`compare-metric-card__decision compare-metric-card__decision--${changeInsight.tone}`}>
                            <strong className="compare-metric-card__decision-main">{changeInsight.summary}</strong>
                            <span className="compare-metric-card__decision-detail">{changeInsight.detail}</span>
                          </div>
                          <div className="compare-metric-card__bars">
                            <div className={`compare-metric-card__bar-row ${leftLeadingClass}`}>
                              <span className="compare-metric-card__run">A</span>
                              <div className="compare-metric-card__track">
                                <span className="compare-metric-card__bar compare-metric-card__bar--a" style={{ width: `${leftPct}%` }} />
                              </div>
                              <span className="compare-metric-card__value">
                                <strong>{leftValue.primary}</strong>
                                {leftValue.secondary && <small>{leftValue.secondary}</small>}
                              </span>
                            </div>
                            <div className={`compare-metric-card__bar-row ${rightLeadingClass}`}>
                              <span className="compare-metric-card__run">B</span>
                              <div className="compare-metric-card__track">
                                <span className="compare-metric-card__bar compare-metric-card__bar--b" style={{ width: `${rightPct}%` }} />
                              </div>
                              <span className="compare-metric-card__value">
                                <strong>{rightValue.primary}</strong>
                                {rightValue.secondary && <small>{rightValue.secondary}</small>}
                              </span>
                            </div>
                          </div>
                          <div className="compare-metric-card__delta-line">
                            <span>차이 (B-A)</span>
                            <strong>{deltaValue.primary}</strong>
                            <span className="compare-metric-card__delta-rate">{deltaRateText}</span>
                          </div>
                          {deltaValue.secondary && <div className="compare-metric-card__delta-sub">{deltaValue.secondary}</div>}
                        </article>
                      )
                    })}
                  </div>
                </section>
              ))}
            </div>
          )}
        </div>
      </section>

      <section className="table-shell">
        <div className="table-header">
          <div className="table-title">품질/성능 상세 비교 테이블</div>
        </div>
        <div className="table-wrap">
          {compareRuns.length !== 2 && (
            <div className="empty-state">
              실행 이력에서 2개를 선택하면 품질/성능 지표를 함께 비교할 수 있습니다.
            </div>
          )}
          {compareRuns.length === 2 && (
            <table className="data-table compare-data-table">
              <thead>
                <tr>
                  <th className="compare-data-table__metric-col">지표</th>
                  <th className="compare-data-table__run-col">
                    <span>{compareRunMeta[0].slot}</span>
                    <strong title={compareRunMeta[0].tableLabel}>{compareRunMeta[0].tableLabel}</strong>
                    <small className="compare-data-table__run-sub" title={compareRunMeta[0].tableSubLabel}>{compareRunMeta[0].tableSubLabel}</small>
                    <small className="compare-data-table__run-id" title={compareRunMeta[0].fullId}>{compareRunMeta[0].tableId}</small>
                  </th>
                  <th className="compare-data-table__run-col">
                    <span>{compareRunMeta[1].slot}</span>
                    <strong title={compareRunMeta[1].tableLabel}>{compareRunMeta[1].tableLabel}</strong>
                    <small className="compare-data-table__run-sub" title={compareRunMeta[1].tableSubLabel}>{compareRunMeta[1].tableSubLabel}</small>
                    <small className="compare-data-table__run-id" title={compareRunMeta[1].fullId}>{compareRunMeta[1].tableId}</small>
                  </th>
                  <th className="compare-data-table__delta-col">차이 / 변화</th>
                  <th className="compare-data-table__result-col">결과</th>
                </tr>
              </thead>
              {compareTableGroups.map((group, groupIndex) => (
                <tbody key={group.key} className={`compare-data-table__section compare-data-table__section--${group.key}`}>
                  {groupIndex > 0 && (
                    <tr className="compare-data-table__section-spacer" aria-hidden="true">
                      <td colSpan="5" />
                    </tr>
                  )}
                  <tr className="compare-data-table__section-row">
                    <th colSpan={5}>
                      <div className="compare-data-table__section-head">
                        <span className={`compare-group-pill compare-group-pill--${group.key}`}>{group.label}</span>
                        <span className="compare-data-table__section-summary">{group.summaryLabel}</span>
                      </div>
                      <div className="compare-data-table__section-desc">{group.description}</div>
                    </th>
                  </tr>
                  {group.rows.map((row) => {
                    const leftValue = formatTableMetricValue(row.left, row)
                    const rightValue = formatTableMetricValue(row.right, row)
                    const deltaInfo = buildDeltaInterpretation(row)
                    const resultInfo = buildResultLabel(row, compareRunMeta[0]?.tableLabel, compareRunMeta[1]?.tableLabel)
                    const trendLabel = metricSupportText(row)
                    return (
                      <tr
                        key={`${row.groupKey}:${row.key}`}
                        className={`compare-data-table__row ${row.priority === 'core' ? 'is-core' : ''} ${activeCompareMetricKey === row.key ? 'is-linked' : ''}`}
                        onMouseEnter={() => setActiveCompareMetricKey(row.key)}
                        onMouseLeave={() => setActiveCompareMetricKey('')}
                        onFocus={() => setActiveCompareMetricKey(row.key)}
                        onBlur={() => setActiveCompareMetricKey('')}
                        tabIndex={0}
                      >
                        <td className="compare-data-table__metric">
                          <div className="compare-data-table__metric-main">
                            <span>{row.label}</span>
                            {row.priority === 'core' && <span className="metric-chip metric-chip--core compare-data-table__kpi-chip">KPI</span>}
                          </div>
                          {trendLabel && <div className="compare-data-table__metric-sub">{trendLabel}</div>}
                        </td>
                        <td className="compare-data-table__num">
                          <strong>{leftValue.main}</strong>
                          {leftValue.sub && <small>{leftValue.sub}</small>}
                        </td>
                        <td className="compare-data-table__num">
                          <strong>{rightValue.main}</strong>
                          {rightValue.sub && <small>{rightValue.sub}</small>}
                        </td>
                        <td className={`compare-data-table__delta compare-data-table__delta--${deltaInfo.tone}`}>
                          <span className="compare-data-table__delta-main">{deltaInfo.headline}</span>
                          <small className="compare-data-table__delta-sub">{deltaInfo.detail}</small>
                        </td>
                        <td className={`compare-data-table__result compare-data-table__result--${row.outcome}`}>
                          <span className={`compare-result-chip compare-result-chip--${row.outcome}`}>{resultInfo.main}</span>
                          {resultInfo.sub && <small>{resultInfo.sub}</small>}
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              ))}
            </table>
          )}
        </div>
      </section>

      <section className="table-shell">
        <div className="table-header"><div className="table-title">평가 데이터셋</div></div>
        <div className="table-wrap">
          <table className="data-table">
            <thead><tr><th>데이터셋 ID</th><th>이름</th><th>버전</th><th>문항 수</th><th>생성 일시</th><th>상세</th><th>삭제</th></tr></thead>
            <tbody>
              {datasets.map((dataset) => (
                <tr key={dataset.datasetId}>
                  <td><IdBadge value={dataset.datasetId} /></td>
                  <td>{dataset.datasetName}</td>
                  <td>{dataset.version || '-'}</td>
                  <td>{dataset.totalItems ?? 0}</td>
                  <td>{fmtTime(dataset.createdAt)}</td>
                  <td><button type="button" className="button button--ghost" onClick={() => openDatasetItems(dataset)}>상세 조회</button></td>
                  <td>
                    <button
                      type="button"
                      className="button button--danger-ghost"
                      disabled={dataset.datasetKey === 'human_eval_default' || deletingDatasetId === dataset.datasetId}
                      onClick={() => deleteDataset(dataset.datasetId)}
                    >
                      {dataset.datasetKey === 'human_eval_default' ? '자동' : deletingDatasetId === dataset.datasetId ? '삭제 중...' : '삭제'}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="table-shell">
        <div className="table-header">
          <div className="table-title">RAG 테스트 실행 이력</div>
          <button type="button" className="button" onClick={() => Promise.all([loadTests(), loadGatingBatches()]).catch((error) => notify(error.message, 'error'))}>새로고침</button>
        </div>
        <div className="table-wrap">
          <table className="data-table rag-history-table">
            <thead>
              <tr>
                <th>비교</th>
                <th>실행</th>
                <th>생성 방식</th>
                <th>게이팅</th>
                <th>스테이지 컷오프</th>
                <th>재작성 모드</th>
                <th>핵심 지표</th>
                <th>상세</th>
                <th>삭제</th>
              </tr>
            </thead>
            <tbody>
              {pagedTests.map((run) => {
                const metrics = runMetricsMap.get(run.ragTestRunId) || {}
                const generationTags = buildGenerationMethodTags(run.generationMethodCodes)
                const gatingTags = buildGatingTags(run)
                const stageCutoffTags = buildStageCutoffTags(run)
                const rewriteTags = buildRewriteTags(run)
                const coreMetricTags = buildCoreMetricTags(metrics)
                return (
                  <tr key={run.ragTestRunId}>
                    <td>
                      <label className={`compare-check ${compareRunIds.includes(run.ragTestRunId) ? 'is-selected' : ''}`}>
                        <input
                          type="checkbox"
                          checked={compareRunIds.includes(run.ragTestRunId)}
                          onChange={() => toggleCompareRun(run.ragTestRunId)}
                        />
                        <span className="compare-check__box" aria-hidden="true">
                          {compareRunIds.includes(run.ragTestRunId) ? '✓' : ''}
                        </span>
                        <span className="compare-check__text">
                          {compareRunIds.includes(run.ragTestRunId) ? '선택됨' : '선택'}
                        </span>
                      </label>
                    </td>
                    <td className="run-history-exec">
                      <div className="run-history-exec__title" title={resolveCompareRunPrimaryLabel(run, 'RAG 테스트')}>
                        {resolveCompareRunPrimaryLabel(run, 'RAG 테스트')}
                      </div>
                      <div className="run-history-exec__meta">{resolveCompareRunSecondaryLabel(run)}</div>
                      <div className="run-history-exec__badges">
                        <StatusBadge value={run.status} />
                        <IdBadge value={run.ragTestRunId} />
                      </div>
                      <RemainingEta
                        remainingSeconds={run.estimatedRemainingSeconds}
                        secondsPerUnit={run.estimatedSecondsPerStage}
                        completedCount={run.completedStageCount}
                        totalCount={run.totalStageCount}
                        unitLabel="단계"
                        status={run.status}
                        compact
                        startedAt={run.startedAt}
                        finishedAt={run.finishedAt}
                        showCompletedElapsed
                      />
                    </td>
                    <td>
                      <div className="run-history-token-list">
                        {generationTags.map((tag, index) => (
                          <span key={`gen-${run.ragTestRunId}-${index}`} className="token-badge run-history-token" data-kind={`history-${tag.kind}`} title={tag.title}>
                            <span className="token-badge__icon" aria-hidden="true">{tag.icon}</span>
                            <span className="token-badge__text">{tag.text}</span>
                          </span>
                        ))}
                      </div>
                    </td>
                    <td>
                      <div className="run-history-token-list">
                        {gatingTags.map((tag, index) => (
                          <span key={`gating-${run.ragTestRunId}-${index}`} className="token-badge run-history-token" data-kind={`history-${tag.kind}`} title={tag.title}>
                            <span className="token-badge__icon" aria-hidden="true">{tag.icon}</span>
                            <span className="token-badge__text">{tag.text}</span>
                          </span>
                        ))}
                      </div>
                    </td>
                    <td>
                      <div className="run-history-token-list">
                        {stageCutoffTags.map((tag, index) => (
                          <span key={`cutoff-${run.ragTestRunId}-${index}`} className="token-badge run-history-token" data-kind={`history-${tag.kind}`} title={tag.title}>
                            <span className="token-badge__icon" aria-hidden="true">{tag.icon}</span>
                            <span className="token-badge__text">{tag.text}</span>
                          </span>
                        ))}
                      </div>
                    </td>
                    <td>
                      <div className="run-history-token-list">
                        {rewriteTags.map((tag, index) => (
                          <span key={`rewrite-${run.ragTestRunId}-${index}`} className="token-badge run-history-token" data-kind={`history-${tag.kind}`} title={tag.title}>
                            <span className="token-badge__icon" aria-hidden="true">{tag.icon}</span>
                            <span className="token-badge__text">{tag.text}</span>
                          </span>
                        ))}
                      </div>
                    </td>
                    <td>
                      <div className="run-history-token-list">
                        {coreMetricTags.map((tag, index) => (
                          <span key={`metric-${run.ragTestRunId}-${index}`} className="token-badge run-history-token" data-kind={`history-${tag.kind}`} title={tag.title}>
                            <span className="token-badge__icon" aria-hidden="true">{tag.icon}</span>
                            <span className="token-badge__text">{tag.text}</span>
                          </span>
                        ))}
                      </div>
                    </td>
                    <td><button type="button" className="button button--ghost" onClick={() => openRunDetail(run.ragTestRunId)}>상세 조회</button></td>
                    <td>
                      <button
                        type="button"
                        className="button button--danger-ghost"
                        disabled={deletingRunId === run.ragTestRunId}
                        onClick={() => deleteRun(run.ragTestRunId)}
                      >
                        {deletingRunId === run.ragTestRunId ? '삭제 중...' : '삭제'}
                      </button>
                    </td>
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
            disabled={currentHistoryPage === 0}
            onClick={() => setHistoryPage(0)}
          >처음</button>
          <button
            type="button"
            className="button"
            disabled={currentHistoryPage === 0}
            onClick={() => setHistoryPage((prev) => Math.max(0, prev - 1))}
          >이전</button>
          <label className="pagination__jump">
            <span>페이지</span>
            <input
              type="number"
              min="1"
              max={historyTotalPages}
              value={currentHistoryPage + 1}
              onChange={(event) => {
                const nextPage = Number(event.target.value)
                if (!Number.isFinite(nextPage)) return
                setHistoryPage(Math.min(historyTotalPages - 1, Math.max(0, nextPage - 1)))
              }}
            />
            <span>/ {historyTotalPages}</span>
          </label>
          <button
            type="button"
            className="button"
            disabled={currentHistoryPage + 1 >= historyTotalPages}
            onClick={() => setHistoryPage((prev) => Math.min(historyTotalPages - 1, prev + 1))}
          >다음</button>
          <button
            type="button"
            className="button"
            disabled={currentHistoryPage + 1 >= historyTotalPages}
            onClick={() => setHistoryPage(historyTotalPages - 1)}
          >마지막</button>
        </div>
      </section>

      <LlmJobsTable
        jobs={llmJobs}
        onAction={executeLlmAction}
        onDetail={openJobDetail}
        loaded={llmJobsLoaded}
        loading={llmJobsLoading}
        onLoad={() => loadLlmJobs().catch((error) => notify(error.message, 'error'))}
      />

      <section className="table-shell">
        <div className="table-header">
          <div className="table-title">재작성 디버그 로그</div>
          <button
            type="button"
            className="button"
            disabled={rewriteLogsLoading}
            onClick={() => loadRewriteLogs().catch((error) => notify(error.message, 'error'))}
          >
            {rewriteLogsLoading ? '불러오는 중...' : (rewriteLogsLoaded ? '새로고침' : '불러오기')}
          </button>
        </div>
        {!rewriteLogsLoaded && !rewriteLogsLoading ? (
          <div className="empty-state">재작성 로그는 필요할 때 불러옵니다.</div>
        ) : (
          <div className="table-wrap">
            <table className="data-table">
              <thead><tr><th>재작성 로그 ID</th><th>원본 질의</th><th>최종 질의</th><th>전략</th><th>적용</th><th>차이</th><th>결정 사유</th><th>상세</th></tr></thead>
              <tbody>
                {rewriteLogs.map((row) => (
                  <tr key={row.rewriteLogId}>
                    <td><IdBadge value={row.rewriteLogId} /></td>
                    <td>{row.rawQuery || '-'}</td>
                    <td>{row.finalQuery || '-'}</td>
                    <td>{row.rewriteStrategy || '-'}</td>
                    <td>{row.rewriteApplied ? <StatusBadge value="success" label="적용" /> : <StatusBadge value="failed" label="미적용" />}</td>
                    <td>{row.confidenceDelta == null ? '-' : Number(row.confidenceDelta).toFixed(4)}</td>
                    <td>{row.decisionReason || row.rejectionReason || '-'}</td>
                    <td><button type="button" className="button button--ghost" onClick={() => openRewriteDetail(row.rewriteLogId)}>상세 조회</button></td>
                  </tr>
                ))}
                {rewriteLogsLoaded && rewriteLogs.length === 0 && (
                  <tr>
                    <td colSpan={8}>재작성 로그가 없습니다.</td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {compareRuns.length > 0 && (
        <aside className="rag-compare-selection-dock" aria-live="polite">
          <div className="rag-compare-selection-dock__status">
            <span>테스트 결과 비교</span>
            <strong>{compareRuns.length} / 2 선택</strong>
          </div>
          <div className="rag-compare-selection-dock__items">
            {compareRuns.map((run, index) => (
              <button
                type="button"
                key={run.ragTestRunId}
                className="rag-compare-selection-pill"
                onClick={() => toggleCompareRun(run.ragTestRunId)}
                title="선택 해제"
              >
                <span className={`rag-compare-selection-pill__index rag-compare-selection-pill__index--${index + 1}`}>{index + 1}</span>
                <span className="rag-compare-selection-pill__body">
                  <strong>{resolveCompareRunPrimaryLabel(run, 'RAG 테스트')}</strong>
                  <small>{resolveCompareRunSecondaryLabel(run)}</small>
                </span>
                <span className="rag-compare-selection-pill__remove">해제</span>
              </button>
            ))}
          </div>
          <button type="button" className="button button--ghost rag-compare-selection-dock__clear" onClick={clearCompareRuns}>
            전체 해제
          </button>
        </aside>
      )}

      <Modal data={modal} onClose={() => setModal(null)} />
    </>
  )
}


