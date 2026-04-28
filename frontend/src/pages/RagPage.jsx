import { useEffect, useMemo, useState } from 'react'
import { DetailCard, IdBadge, Modal, StatusBadge } from '../components/Common.jsx'
import { LlmJobsTable } from '../components/LlmJobsTable.jsx'
import { requestJson, toNumber } from '../lib/api.js'
import { fmtTime, shortId } from '../lib/format.js'

const FIXED_DENSE_EMBEDDING_MODEL = 'intfloat/multilingual-e5-small'

const RETRIEVER_MODE_PRESETS = {
  bm25_only: {
    denseEmbeddingRequired: false,
    denseFallbackEnabled: false,
    retrieverRerankEnabled: false,
    retrieverCandidatePoolK: '50',
    retrieverDenseWeight: '0.00',
    retrieverBm25Weight: '1.00',
    retrieverTechnicalWeight: '0.00',
  },
  dense_only: {
    denseEmbeddingRequired: true,
    denseFallbackEnabled: false,
    retrieverRerankEnabled: false,
    retrieverCandidatePoolK: '50',
    retrieverDenseWeight: '1.00',
    retrieverBm25Weight: '0.00',
    retrieverTechnicalWeight: '0.00',
  },
  hybrid: {
    denseEmbeddingRequired: true,
    denseFallbackEnabled: false,
    retrieverRerankEnabled: false,
    retrieverCandidatePoolK: '50',
    retrieverDenseWeight: '0.60',
    retrieverBm25Weight: '0.32',
    retrieverTechnicalWeight: '0.08',
  },
}

function retrieverPresetForMode(mode) {
  const normalizedMode = RETRIEVER_MODE_PRESETS[mode] ? mode : 'bm25_only'
  return {
    retrieverMode: normalizedMode,
    denseEmbeddingModel: FIXED_DENSE_EMBEDDING_MODEL,
    ...RETRIEVER_MODE_PRESETS[normalizedMode],
  }
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
  { key: 'hallucination_rate', label: 'Hallucination Rate (환각 비율)', max: 1, precision: 3, trend: 'lower' },
  { key: 'answer_relevance', label: 'Answer Relevance (응답 관련성)', max: 1, precision: 3, trend: 'higher' },
  { key: 'faithfulness', label: 'Faithfulness (사실 충실도)', max: 1, precision: 3, trend: 'higher' },
  { key: 'context_recall', label: 'Context Recall (문맥 재현율)', max: 1, precision: 3, trend: 'higher' },
]

const PERFORMANCE_METRIC_DEFS = [
  { key: 'total_duration_ms', label: 'Total Duration (총 소요 시간)', precision: 0, unit: 'ms', trend: 'lower' },
  { key: 'build_memory_ms', label: 'Build-Memory Stage (메모리 구축 단계)', precision: 0, unit: 'ms', trend: 'lower' },
  { key: 'eval_retrieval_ms', label: 'Eval-Retrieval Stage (검색 평가 단계)', precision: 0, unit: 'ms', trend: 'lower' },
  { key: 'eval_answer_ms', label: 'Eval-Answer Stage (답변 평가 단계)', precision: 0, unit: 'ms', trend: 'lower' },
  { key: 'orchestration_overhead_ms', label: 'Orchestration Overhead (오케스트레이션 오버헤드)', precision: 0, unit: 'ms', trend: 'lower' },
  { key: 'latency_avg_ms', label: 'Research Mode Avg Latency', precision: 2, unit: 'ms', trend: 'lower', priority: 'core' },
  { key: 'latency_p95_ms', label: 'Research Mode P95 Latency', precision: 2, unit: 'ms', trend: 'lower', priority: 'core' },
  { key: 'rewrite_overhead_avg_latency_ms', label: 'Rewrite Overhead (Avg) (리라이트 오버헤드 평균)', precision: 2, unit: 'ms', trend: 'lower' },
]

const METRIC_GROUP_DEFS = [
  { key: 'retrieval', label: 'Retrieval Quality', description: '검색 품질 핵심 지표', metrics: RETRIEVAL_METRIC_DEFS },
  { key: 'answer', label: 'Answer Quality', description: '답변 신뢰도 및 정합성 지표', metrics: ANSWER_METRIC_DEFS },
  { key: 'performance', label: 'Performance', description: '응답 지연 및 단계별 실행 시간', metrics: PERFORMANCE_METRIC_DEFS },
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
const COMPARE_FOCUS_LATENCY_KEYS = ['latency_avg_ms', 'latency_p95_ms']
const KPI_METRIC_KEYS = new Set([
  'recall_at_5',
  'hit_at_5',
  'mrr_at_10',
  'ndcg_at_10',
  'latency_avg_ms',
  'latency_p95_ms',
])

const REWRITE_RETRIEVAL_OPTION_META = {
  replace: {
    label: 'replace',
    description: '재작성 결과로 교체',
  },
  interleave: {
    label: 'interleave',
    description: '원본/재작성 번갈아 결합',
  },
  max_score: {
    label: 'max_score',
    description: '두 결과에서 점수 우선 결합',
  },
}

const METRIC_TREND_LABEL = {
  higher: 'Higher is better',
  lower: 'Lower is better',
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

const RESEARCH_MODE_PRIORITY = [
  'selective_rewrite_with_session',
  'selective_rewrite',
  'rewrite_always',
  'raw_only',
  'memory_only_full_gating',
  'memory_only_gated',
  'memory_only_rule_only',
  'memory_only_ungated',
]

function pickResearchMode(byMode) {
  for (const mode of RESEARCH_MODE_PRIORITY) {
    if (byMode?.[mode]) return mode
  }
  const fallback = Object.keys(byMode || {})[0]
  return fallback || ''
}

function extractRunMetrics(metricsJson) {
  const payload = parseMetricsNode(metricsJson)
  const retrievalPayload = parseMetricsNode(payload.retrieval || payload.metrics_json?.retrieval || payload)
  const answerPayload = parseMetricsNode(payload.answer || payload.metrics_json?.answer)
  const performancePayload = parseMetricsNode(payload.performance || payload.metrics_json?.performance)
  const stageDurationPayload = parseMetricsNode(performancePayload.stage_duration_ms)
  const latencyByModePayload = parseMetricsNode(performancePayload.latency_by_mode_ms || payload.latency_by_mode || payload.latency_by_mode_ms)
  const answerSummary = parseMetricsNode(answerPayload.summary || answerPayload)
  const summaryRaw = Array.isArray(retrievalPayload.summary) ? retrievalPayload.summary : []
  const byMode = summaryRaw.reduce((acc, row) => {
    const mode = String(row?.mode || '')
    if (!mode) return acc
    acc[mode] = row
    return acc
  }, {})
  const researchMode = pickResearchMode(byMode)
  const summary = byMode[researchMode] || {}
  const latencyForMode = parseMetricsNode(latencyByModePayload[researchMode])
  return {
    representative_mode: summary.mode || researchMode || '-',
    research_mode: summary.mode || researchMode || '-',
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
    total_duration_ms: firstMetricNumber([performancePayload.total_duration_ms]),
    build_memory_ms: firstMetricNumber([stageDurationPayload.build_memory_ms]),
    eval_retrieval_ms: firstMetricNumber([stageDurationPayload.eval_retrieval_ms]),
    eval_answer_ms: firstMetricNumber([stageDurationPayload.eval_answer_ms]),
    orchestration_overhead_ms: firstMetricNumber([performancePayload.orchestration_overhead_ms]),
    latency_avg_ms: firstMetricNumber([latencyForMode.avg_latency_ms, performancePayload.representative_mode_latency_avg_ms, payload.latency_avg_ms]),
    latency_p95_ms: firstMetricNumber([latencyForMode.p95_latency_ms, performancePayload.representative_mode_latency_p95_ms]),
    rewrite_overhead_avg_latency_ms: firstMetricNumber([performancePayload.rewrite_overhead_avg_latency_ms]),
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
          <strong>Retrieval Summary by Mode</strong>
          <div className="run-mode-compare__subtitle">All displayed values are mode-level metrics, not representative KPI.</div>
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
  const comparisonLabel = isQueryRewriteMode(rewriteMode) ? 'Raw vs query rewrite' : 'Raw vs synthetic memory'
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
          <div className="detail-item__label">{baselineOnly ? 'Baseline mode' : comparisonLabel}</div>
          <div className="run-mode-compare__subtitle">{baselineOnly ? 'raw_only' : `raw_only / ${rewriteMode}`}</div>
        </div>
        <span className="metric-chip metric-chip--core">{baselineOnly ? 'raw_only' : rewriteMode}</span>
      </div>
      <div className="run-mode-compare__table-wrap">
        <table className="run-mode-compare__table">
          <thead>
            <tr>
              <th>Metric</th>
              <th>raw_only</th>
              {!baselineOnly && <th>{rewriteMode}</th>}
              {!baselineOnly && <th>Delta</th>}
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

function formatMetricWithDef(value, def) {
  if (value == null) return '-'
  const precision = Number.isFinite(def?.precision) ? def.precision : 3
  const text = Number(value).toFixed(precision)
  return def?.unit ? `${text} ${def.unit}` : text
}

function formatDelta(value, def) {
  if (value == null) return '-'
  const precision = Number.isFinite(def?.precision) ? def.precision : 3
  const sign = value > 0 ? '+' : ''
  const text = `${sign}${Number(value).toFixed(precision)}`
  return def?.unit ? `${text} ${def.unit}` : text
}

function formatDeltaRate(value) {
  if (value == null) return '-'
  const sign = value > 0 ? '+' : ''
  return `${sign}${Number(value).toFixed(1)}%`
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
  if (row?.unit === 'ms') {
    return formatDurationDisplay(value, { precisionMs: precision, precisionSeconds: 2, includeRawMs: false })
  }
  const text = formatTableNumber(value, precision)
  return row?.unit ? { primary: `${text} ${row.unit}`, secondary: '' } : { primary: text, secondary: '' }
}

function formatWorkspaceDeltaValue(row) {
  if (!row || row.delta == null) return { primary: '-', secondary: '' }
  const precision = Number.isFinite(row.precision) ? row.precision : 3
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
      summary: 'No comparable data',
      detail: 'A/B values unavailable',
      tone: 'na',
    }
  }
  const deltaDisplay = formatWorkspaceDeltaValue(row)
  if (row.outcome === 'tie' || row.delta === 0) {
    return {
      summary: 'No change',
      detail: `Delta ${deltaDisplay.primary}`,
      tone: 'tie',
    }
  }
  const improved = row.outcome === 'right'
  if (row.unit === 'ms' || row.groupKey === 'performance') {
    const speedWord = improved ? 'faster' : 'slower'
    const ratio = (row.left != null && row.right != null && row.left > 0 && row.right > 0)
      ? (improved ? row.left / row.right : row.right / row.left)
      : null
    let summary = speedWord
    if (ratio != null && ratio >= 1.2) {
      summary = `${formatTableNumber(ratio, 2)}x ${speedWord}`
    } else if (row.deltaRate != null) {
      summary = `${formatTableNumber(Math.abs(row.deltaRate), 1)}% ${speedWord}`
    }
    const detailParts = [`Delta ${deltaDisplay.primary}`]
    if (row.deltaRate != null && !summary.includes('%')) {
      detailParts.push(`${formatTableNumber(Math.abs(row.deltaRate), 1)}% ${speedWord}`)
    }
    return {
      summary,
      detail: detailParts.join(' '),
      tone: improved ? 'right' : 'left',
    }
  }
  const changeWord = row.trend === 'higher'
    ? (improved ? 'higher' : 'lower')
    : (improved ? 'lower' : 'higher')
  const summary = row.deltaRate != null ? `${formatTableNumber(Math.abs(row.deltaRate), 1)}% ${changeWord}` : changeWord
  const detailParts = [`Delta ${deltaDisplay.primary}`]
  return {
    summary,
    detail: detailParts.join(' '),
    tone: improved ? 'right' : 'left',
  }
}

function buildDeltaInterpretation(row) {
  if (!row || row.outcome === 'na') {
    return {
      headline: 'No comparable data',
      detail: 'A/B values unavailable',
      tone: 'na',
    }
  }
  const deltaDisplay = formatTableDeltaDisplay(row)
  if (row.outcome === 'tie' || row.delta === 0) {
    const tieDetail = [`Δ ${deltaDisplay.main}`]
    if (deltaDisplay.sub) tieDetail.push(`raw ${deltaDisplay.sub}`)
    return {
      headline: 'No change',
      detail: tieDetail.join(' | '),
      tone: 'tie',
    }
  }
  const improved = row.outcome === 'right'
  const isLatencyLike = row.groupKey === 'performance' || row.unit === 'ms'
  const symbol = improved ? '▲' : '▼'
  let headline = ''
  if (isLatencyLike) {
    const speedWord = improved ? 'faster' : 'slower'
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
    const qualityWord = improved ? 'improved' : 'regressed'
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
  if (!row || row.outcome === 'na') return { main: 'Not Comparable', sub: 'Check source metrics' }
  if (row.outcome === 'tie') return { main: 'No Change', sub: METRIC_TREND_LABEL[row.trend] || '' }
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
    return compactText(`Legacy RAG Test ${shortId(run.ragTestRunId)}`, 40)
  }
  const methodLabel = compactText(formatGenerationMethodLabel(run?.generationMethodCodes), 40)
  return methodLabel === '-' ? fallbackTitle : methodLabel
}

function compareOutcomeLabel(outcome, leftLabel = 'A', rightLabel = 'B') {
  if (outcome === 'right') return `${compactText(rightLabel, 24)} Better`
  if (outcome === 'left') return `${compactText(leftLabel, 24)} Better`
  if (outcome === 'tie') return 'No Change'
  return 'No Data'
}

function resolveCompareRunSecondaryLabel(run) {
  const pieces = [run?.datasetName, fmtTime(run?.finishedAt || run?.startedAt)]
    .map((value) => String(value || '').trim())
    .filter((value) => value && value !== '-')
  return compactText(pieces.join(' | '), 52)
}

function formatGenerationMethodLabel(methodCodes) {
  if (!Array.isArray(methodCodes) || methodCodes.length === 0) return 'synthetic-free baseline'
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
  return codes.map((code) => toHistoryTag('method', 'M', `Method ${code}`))
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
  if (!run?.selectiveRewrite) return { kind: 'rewrite-always', icon: 'RA', label: 'always' }
  if (run?.useSessionContext) return { kind: 'rewrite-session', icon: 'RS', label: 'selective + session' }
  return { kind: 'rewrite-selective', icon: 'SL', label: 'selective' }
}

function resolveRewriteAnchorEnabled(run) {
  if (!run?.rewriteEnabled) return false
  if (run?.rewriteAnchorInjectionEnabled == null) return true
  return Boolean(run.rewriteAnchorInjectionEnabled)
}

function buildRewriteTags(run) {
  const mode = resolveRewriteMode(run)
  const anchorEnabled = resolveRewriteAnchorEnabled(run)
  return [
    toHistoryTag(mode.kind, mode.icon, mode.label),
    toHistoryTag(
      anchorEnabled ? 'anchor-on' : 'anchor-off',
      anchorEnabled ? 'AN' : 'AX',
      anchorEnabled ? 'anchor on' : 'anchor off',
      anchorEnabled ? 'rewrite anchor injection enabled' : 'rewrite anchor injection disabled',
    ),
  ]
}

function buildCoreMetricTags(metrics) {
  const totalDuration = formatDurationDisplay(metrics?.total_duration_ms, {
    precisionMs: 0,
    precisionSeconds: 2,
    includeRawMs: false,
  }).primary
  return [
    toHistoryTag('metric', 'R5', `Recall@5 ${formatMetric(metrics?.recall_at_5)}`),
    toHistoryTag('metric', 'ND', `nDCG@10 ${formatMetric(metrics?.ndcg_at_10)}`),
    toHistoryTag('metric', 'TM', `Total ${totalDuration}`),
  ]
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

export function RagPage({ notify }) {
  const historyPageSize = 3
  const [methods, setMethods] = useState([])
  const [datasets, setDatasets] = useState([])
  const [tests, setTests] = useState([])
  const [gatingBatches, setGatingBatches] = useState([])
  const [rewriteLogs, setRewriteLogs] = useState([])
  const [llmJobs, setLlmJobs] = useState([])
  const [historyPage, setHistoryPage] = useState(0)
  const [modal, setModal] = useState(null)
  const [selectedMethods, setSelectedMethods] = useState([])
  const [compareRunIds, setCompareRunIds] = useState([])
  const [activeCompareMetricKey, setActiveCompareMetricKey] = useState('')
  const [deletingRunId, setDeletingRunId] = useState('')

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
    threshold: '0.14',
    retrievalTopK: '10',
    rerankTopN: '5',
    ...retrieverPresetForMode('bm25_only'),
    syntheticFreeBaseline: false,
    gatingApplied: true,
    stageCutoffEnabled: false,
    stageCutoffLevel: 'rule_only',
    rewriteEnabled: true,
    selectiveRewrite: true,
    useSessionContext: false,
    rewriteRetrievalStrategy: 'replace',
    rewriteAnchorInjectionEnabled: true,
  })

  const loadMethods = async () => {
    const rows = await requestJson('/api/admin/console/synthetic/methods')
    const normalized = Array.isArray(rows) ? rows : []
    setMethods(normalized)
    if (normalized.length > 0 && selectedMethods.length === 0) {
      setSelectedMethods([normalized[0].methodCode])
    }
  }

  const loadDatasets = async () => {
    const rows = await requestJson('/api/admin/console/rag/datasets')
    const normalized = Array.isArray(rows) ? rows : []
    setDatasets(normalized)
    setForm((prev) => ({ ...prev, datasetId: prev.datasetId || normalized[0]?.datasetId || '' }))
  }

  const loadTests = async () => {
    const rows = await requestJson('/api/admin/console/rag/tests?limit=50')
    setTests(Array.isArray(rows) ? rows : [])
  }

  const loadGatingBatches = async () => {
    const rows = await requestJson('/api/admin/console/gating/batches?limit=100')
    setGatingBatches(Array.isArray(rows) ? rows : [])
  }

  const loadRewriteLogs = async () => {
    const rows = await requestJson('/api/admin/console/rewrite/logs?limit=100')
    setRewriteLogs(Array.isArray(rows) ? rows : [])
  }

  const loadLlmJobs = async () => {
    const rows = await requestJson('/api/admin/console/llm-jobs?limit=120')
    const filtered = (Array.isArray(rows) ? rows : []).filter((job) => job.jobType === 'RUN_RAG_TEST' || job.ragTestRunId)
    setLlmJobs(filtered)
  }

  useEffect(() => {
    Promise.all([loadMethods(), loadDatasets(), loadTests(), loadGatingBatches(), loadRewriteLogs(), loadLlmJobs()]).catch((error) => notify(error.message, 'error'))
  }, [])

  useEffect(() => {
    if (!form.datasetId) return
    const dataset = datasets.find((item) => item.datasetId === form.datasetId)
    if (!dataset) return
    const preferredLanguage = String(dataset.datasetKey || '').toLowerCase().endsWith('_en') ? 'en' : 'ko'
    if (form.evalQueryLanguage === preferredLanguage) return
    setForm((prev) => ({ ...prev, evalQueryLanguage: preferredLanguage }))
  }, [datasets, form.datasetId, form.evalQueryLanguage])

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
  const methodSelectionLocked = !form.syntheticFreeBaseline && Boolean(form.sourceGatingBatchId && snapshotMethodCode)

  useEffect(() => {
    if (!methodSelectionLocked || !snapshotMethodCode) return
    if (selectedMethods.length === 1 && selectedMethods[0] === snapshotMethodCode) return
    setSelectedMethods([snapshotMethodCode])
  }, [methodSelectionLocked, snapshotMethodCode, selectedMethods])

  useEffect(() => {
    if (!form.rewriteEnabled && (form.selectiveRewrite || form.useSessionContext)) {
      setForm((prev) => ({ ...prev, selectiveRewrite: false, useSessionContext: false }))
    }
    if (form.rewriteEnabled && !form.selectiveRewrite && form.useSessionContext) {
      setForm((prev) => ({ ...prev, useSessionContext: false }))
    }
  }, [form.rewriteEnabled, form.selectiveRewrite, form.useSessionContext])

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
  const sourceSnapshotOptions = useMemo(
    () => (stageCutoffEnabledForRun ? snapshotBatches.filter((batch) => batch.gatingPreset === 'full_gating') : snapshotBatches),
    [snapshotBatches, stageCutoffEnabledForRun],
  )
  const methodCodesForRun = form.syntheticFreeBaseline
    ? []
    : (methodSelectionLocked && snapshotMethodCode ? [snapshotMethodCode] : selectedMethods)

  useEffect(() => {
    if (!form.sourceGatingBatchId) return
    const exists = gatingBatches.some((batch) => batch.gatingBatchId === form.sourceGatingBatchId)
    if (exists) return
    setForm((prev) => ({ ...prev, sourceGatingBatchId: '' }))
  }, [form.sourceGatingBatchId, gatingBatches])

  useEffect(() => {
    if (form.runDiscipline !== 'official' || form.officialComparisonType !== 'gating_effect') return
    if (!form.sourceGatingBatchId) return
    setForm((prev) => ({ ...prev, sourceGatingBatchId: '' }))
  }, [form.runDiscipline, form.officialComparisonType, form.sourceGatingBatchId])

  useEffect(() => {
    const hasUngated = gatingBatches.some((batch) => batch.gatingBatchId === form.officialGatingUngatedBatchId)
    const hasRuleOnly = gatingBatches.some((batch) => batch.gatingBatchId === form.officialGatingRuleOnlyBatchId)
    const hasFullGating = gatingBatches.some((batch) => batch.gatingBatchId === form.officialGatingFullGatingBatchId)
    if (hasUngated && hasRuleOnly && hasFullGating) return
    const nextUngated = hasUngated ? form.officialGatingUngatedBatchId : ''
    const nextRuleOnly = hasRuleOnly ? form.officialGatingRuleOnlyBatchId : ''
    const nextFullGating = hasFullGating ? form.officialGatingFullGatingBatchId : ''
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
    if (!Array.isArray(methodCodes) || methodCodes.length === 0) return true
    if (!batch.methodCode) return true
    return methodCodes.includes(String(batch.methodCode).toUpperCase())
  }

  function snapshotOptionLabel(batch, expectedPreset = null) {
    const compatible = expectedPreset
      ? isSnapshotCompatible(batch, expectedPreset, methodCodesForRun)
      : isSnapshotCompatible(batch, sourceSnapshotExpectedPreset, methodCodesForRun)
    const runnable = Boolean(batch?.sourceGatingRunId)
    return `${shortId(batch.gatingBatchId)} | ${batch.gatingPreset} | ${batch.methodCode || '-'} | ${fmtTime(batch.finishedAt)}${runnable ? '' : ' | unavailable(no source run)'}${compatible ? '' : ' | incompatible'}`
  }

  const handleToggleMethod = (methodCode, checked) => {
    if (methodSelectionLocked) return
    setSelectedMethods((prev) => {
      if (checked) return prev.includes(methodCode) ? prev : [...prev, methodCode]
      return prev.filter((value) => value !== methodCode)
    })
  }

  const runRag = async (event) => {
    event.preventDefault()
    const syntheticFreeBaseline = Boolean(form.syntheticFreeBaseline)
    if (!syntheticFreeBaseline && methodCodesForRun.length === 0) {
      notify('최소 1개 생성 방식을 선택해야 합니다.', 'error')
      return
    }
    const officialRun = !syntheticFreeBaseline && form.runDiscipline === 'official'
    const stageCutoffEnabled = !syntheticFreeBaseline && Boolean(form.stageCutoffEnabled)
    const stageCutoffLevel = stageCutoffEnabled ? (form.stageCutoffLevel || 'full_gating') : null
    if (syntheticFreeBaseline && form.runDiscipline === 'official') {
      notify('Synthetic-free baseline은 exploratory 실행에서만 지원됩니다.', 'error')
      return
    }
    const runGatingPreset = syntheticFreeBaseline ? 'ungated' : (stageCutoffEnabled ? 'full_gating' : effectiveGatingPreset)
    const sourceSnapshotPreset = stageCutoffEnabled ? 'full_gating' : runGatingPreset
    if (stageCutoffEnabled && officialRun) {
      notify('stage-cutoff은 exploratory 실행에서만 사용할 수 있습니다.', 'error')
      return
    }
    if (stageCutoffEnabled && !form.gatingApplied) {
      notify('stage-cutoff 사용 시 gating 적용이 필요합니다.', 'error')
      return
    }
    if (stageCutoffEnabled && !form.sourceGatingBatchId) {
      notify('stage-cutoff 사용 시 full_gating source snapshot을 선택해야 합니다.', 'error')
      return
    }
    if (!syntheticFreeBaseline && officialRun && form.officialComparisonType === 'rewrite_effect' && !form.sourceGatingBatchId) {
      notify('공식 rewrite-effect 실행은 source snapshot 선택이 필수입니다.', 'error')
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
          notify(`공식 gating-effect ${required.label} snapshot을 찾을 수 없습니다.`, 'error')
          return
        }
        if (!snapshot.sourceGatingRunId) {
          notify(`공식 gating-effect ${required.label} snapshot에 source_gating_run_id가 없습니다.`, 'error')
          return
        }
        if (!isSnapshotCompatible(snapshot, required.preset, methodCodesForRun)) {
          notify(`공식 gating-effect ${required.label} snapshot이 preset/method와 호환되지 않습니다.`, 'error')
          return
        }
      }
    }
    if (!syntheticFreeBaseline && form.sourceGatingBatchId) {
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
        notify('선택한 스냅샷이 현재 게이팅 preset/method 조건과 호환되지 않습니다.', 'error')
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
      const created = await requestJson('/api/admin/console/rag/tests/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          datasetId: form.datasetId,
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
          rewriteRetrievalStrategy: form.rewriteRetrievalStrategy,
          rewriteAnchorInjectionEnabled,
          threshold: toNumber(form.threshold),
          retrievalTopK: toNumber(form.retrievalTopK),
          rerankTopN: toNumber(form.rerankTopN),
          retrieverConfig: {
            retrieverMode: form.retrieverMode,
            denseEmbeddingModel: form.denseEmbeddingModel,
            denseEmbeddingRequired: Boolean(form.denseEmbeddingRequired),
            denseFallbackEnabled: Boolean(form.denseFallbackEnabled),
            rerankEnabled: Boolean(form.retrieverRerankEnabled),
            candidatePoolK: toNumber(form.retrieverCandidatePoolK),
            denseWeight: toNumber(form.retrieverDenseWeight),
            bm25Weight: toNumber(form.retrieverBm25Weight),
            technicalWeight: toNumber(form.retrieverTechnicalWeight),
          },
        }),
      })
      await Promise.all([loadTests(), loadRewriteLogs(), loadLlmJobs(), loadGatingBatches()])
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

  const openDatasetItems = async (datasetId) => {
    try {
      const rows = await requestJson(`/api/admin/console/rag/datasets/${datasetId}/items?limit=30`)
      const preview = (Array.isArray(rows) ? rows : [])
        .map((row) => {
          const method = row.targetMethod ? `[${row.targetMethod}] ` : ''
          const focus = Array.isArray(row.evaluationFocus) && row.evaluationFocus.length > 0 ? ` (${row.evaluationFocus.join(',')})` : ''
          const queryText = row.queryLanguage === 'en'
            ? (row.userQueryEn || row.userQueryKo || '')
            : (row.userQueryKo || row.userQueryEn || '')
          return `[${row.sampleId}] [${row.queryLanguage || 'ko'}] ${method}${row.queryCategory} - ${queryText}${focus}`
        })
        .join('\n')
      setModal({
        title: `평가 문항 미리보기 · ${shortId(datasetId)}`,
        body: <DetailCard label="samples" value={preview} />,
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
      const metricsJson = parseMetricsNode(summary.metrics_json)
      const performance = parseMetricsNode(metricsJson.performance)
      const retrievalByMode = parseMetricsNode(metricsJson.retrieval_by_mode)
      const retrievalByModeRows = Object.values(retrievalByMode).filter((row) => row && typeof row === 'object')
      const details = Array.isArray(payload.details) ? payload.details : []
      const rewriteMode = resolveRewriteMode(runRow)
      const anchorEnabled = resolveRewriteAnchorEnabled(runRow)
      setModal({
        title: `RAG 실행 상세 · ${shortId(runId)}`,
        body: (
          <div className="detail-grid detail-grid--single">
            {renderRunDetailModeSummary(retrievalByModeRows)}
            {renderRunDetailModeComparison(retrievalByModeRows)}
            <DetailCard
              label="run_profile"
              value={JSON.stringify(
                {
                  rewrite_mode: rewriteMode.label,
                  rewrite_anchor_injection_enabled: anchorEnabled,
                },
                null,
                2,
              )}
            />
            <DetailCard label="performance" value={JSON.stringify(performance, null, 2)} />
            <DetailCard label="retrieval_by_mode" value={JSON.stringify(retrievalByModeRows, null, 2)} />
            <DetailCard label="detail_rows" value={JSON.stringify(details, null, 2)} />
          </div>
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
      await Promise.all([loadTests(), loadRewriteLogs(), loadLlmJobs()])
      notify('RAG 테스트 이력 및 결과를 삭제했습니다.')
    } catch (error) {
      notify(error.message, 'error')
    } finally {
      setDeletingRunId('')
    }
  }

  const openRewriteDetail = async (rewriteLogId) => {
    try {
      const payload = await requestJson(`/api/admin/console/rewrite/logs/${rewriteLogId}`)
      setModal({
        title: `Rewrite 로그 상세 · ${shortId(rewriteLogId)}`,
        body: (
          <div className="detail-grid detail-grid--single">
            <DetailCard label="raw / final" value={`${payload.rewrite?.rawQuery || '-'}\n${payload.rewrite?.finalQuery || '-'}`} />
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
      `${compactText(rightLabel, 24)} better ${outcomeSummary.right}`,
      `${compactText(leftLabel, 24)} better ${outcomeSummary.left}`,
      `No change ${outcomeSummary.tie}`,
    ]
    if (outcomeSummary.na > 0) {
      summaryParts.push(`No data ${outcomeSummary.na}`)
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
    const retrievalDeltaRate = (retrievalAvgA != null && retrievalAvgA !== 0 && retrievalDelta != null)
      ? (retrievalDelta / Math.abs(retrievalAvgA)) * 100
      : null
    const focusLatencyRows = compareMetricRows.filter((row) => COMPARE_FOCUS_LATENCY_KEYS.includes(row.key))
    const avgLatencyRow = focusLatencyRows.find((row) => row.key === 'latency_avg_ms') || null
    const p95LatencyRow = focusLatencyRows.find((row) => row.key === 'latency_p95_ms') || null
    const avgLatencyInsight = avgLatencyRow ? buildWorkspaceChangeInsight(avgLatencyRow) : null
    const p95LatencyInsight = p95LatencyRow ? buildWorkspaceChangeInsight(p95LatencyRow) : null
    const headline = overallWinner === 'tie'
      ? `No clear winner. Weighted score ${compactLeftLabel} ${scoreA} : ${compactRightLabel} ${scoreB}.`
      : overallWinner === 'right'
      ? `${compactRightLabel} is ahead in weighted KPI score (${scoreB} vs ${scoreA}).`
      : `${compactLeftLabel} is ahead in weighted KPI score (${scoreA} vs ${scoreB}).`
    return {
      headline,
      cards: [
        {
          label: 'Overall Winner',
          value: overallWinner === 'tie' ? 'Tie' : overallWinner === 'right' ? compactRightLabel : compactLeftLabel,
          meta: `Weighted score ${compactLeftLabel} ${scoreA} / ${compactRightLabel} ${scoreB}`,
        },
        {
          label: 'Retrieval Core Delta',
          value: retrievalDelta == null ? '-' : formatDelta(retrievalDelta, { precision: 3 }),
          meta: retrievalDelta == null ? 'No comparable data' : `${compactRightLabel} vs ${compactLeftLabel} (${formatDeltaRate(retrievalDeltaRate)})`,
        },
        {
          label: 'Representative Avg Latency',
          value: avgLatencyInsight ? avgLatencyInsight.summary : '-',
          meta: avgLatencyInsight
            ? `${avgLatencyInsight.detail} | ${compareOutcomeLabel(avgLatencyRow.outcome, compactLeftLabel, compactRightLabel)}`
            : 'No comparable data',
        },
        {
          label: 'Representative P95 Latency',
          value: p95LatencyInsight ? p95LatencyInsight.summary : '-',
          meta: p95LatencyInsight
            ? `${p95LatencyInsight.detail} | ${compareOutcomeLabel(p95LatencyRow.outcome, compactLeftLabel, compactRightLabel)}`
            : 'No comparable data',
        },
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
    const latestTotalDuration = formatDurationDisplay(lastMetrics.total_duration_ms, {
      precisionMs: 0,
      precisionSeconds: 2,
      includeRawMs: false,
    }).primary
    const latestAvgLatency = formatDurationDisplay(lastMetrics.latency_avg_ms, {
      precisionMs: 2,
      precisionSeconds: 2,
      includeRawMs: false,
    }).primary
    return [
      { label: '완료된 테스트 수', value: String(completedRuns.length), meta: '최근 50개 실행 기준' },
      { label: '선택된 비교 대상', value: String(compareRunIds.length), meta: compareRunIds.length === 2 ? '비교 준비 완료' : '2개 선택 시 비교 차트 표시' },
      { label: '최근 Recall@5', value: formatMetric(lastMetrics.recall_at_5), meta: lastRun ? `run ${shortId(lastRun.ragTestRunId)}` : '완료된 실행 없음' },
      { label: '최근 nDCG@10', value: formatMetric(lastMetrics.ndcg_at_10), meta: lastRun ? fmtTime(lastRun.finishedAt || lastRun.startedAt) : '-' },
      { label: 'Latest Total Duration', value: latestTotalDuration, meta: 'RAG end-to-end' },
      { label: 'Latest Avg Latency', value: latestAvgLatency, meta: `research mode (${lastMetrics.research_mode || '-'})` },
    ]
  }, [tests, compareRunIds])

  return (
    <>
      <section className="panel panel--hero">
        <div className="table-title">RAG 품질/성능 테스트 실행</div>
        <p className="panel-subtitle">
          스냅샷 기반 재현성, Rewrite 전략, 검색 파라미터를 실험 단위로 비교할 수 있도록 구성했습니다.
        </p>
        <form className="filter-bar filter-bar--stack" onSubmit={runRag}>
          <div className="form-grid form-grid--2">
            <label className="filter-field">평가 데이터셋
              <select value={form.datasetId} onChange={(event) => setForm((prev) => ({ ...prev, datasetId: event.target.value }))}>
                {datasets.map((dataset) => <option key={dataset.datasetId} value={dataset.datasetId}>{dataset.datasetName} ({dataset.totalItems})</option>)}
              </select>
              <span className="field-hint">테스트 입력 샘플 집합입니다.</span>
            </label>
            <label className="filter-field">Eval Query Language
              <select value={form.evalQueryLanguage} onChange={(event) => setForm((prev) => ({ ...prev, evalQueryLanguage: event.target.value }))}>
                <option value="ko">ko</option>
                <option value="en">en</option>
              </select>
              <span className="field-hint">eval runtime rewrite/retrieval input language</span>
            </label>
            <label className="filter-field">Test Name
              <input
                value={form.runName}
                maxLength={120}
                placeholder="Dense Hybrid v1"
                onChange={(event) => setForm((prev) => ({ ...prev, runName: event.target.value }))}
              />
              <span className="field-hint">run_label</span>
            </label>
            <label className="filter-field">Run Discipline
              <select
                value={form.runDiscipline}
                disabled={form.syntheticFreeBaseline}
                onChange={(event) => setForm((prev) => ({ ...prev, runDiscipline: event.target.value }))}
              >
                <option value="exploratory">exploratory</option>
                <option value="official">official</option>
              </select>
              <span className="field-hint">official 실행은 snapshot/비교 조건 강제 검증이 적용됩니다.</span>
            </label>
            <label className="filter-field">Official Comparison Type
              <select
                value={form.officialComparisonType}
                disabled={form.syntheticFreeBaseline || form.runDiscipline !== 'official'}
                onChange={(event) => setForm((prev) => ({ ...prev, officialComparisonType: event.target.value }))}
              >
                <option value="rewrite_effect">rewrite_effect</option>
                <option value="gating_effect">gating_effect</option>
              </select>
              <span className="field-hint">official 전용: 한 번에 하나의 비교축만 허용합니다.</span>
            </label>
            {!form.syntheticFreeBaseline && form.runDiscipline === 'official' && form.officialComparisonType === 'gating_effect' && (
              <>
                <label className="filter-field">Official Snapshot (ungated)
                  <select value={form.officialGatingUngatedBatchId} onChange={(event) => setForm((prev) => ({ ...prev, officialGatingUngatedBatchId: event.target.value }))}>
                    <option value="">Select ungated snapshot</option>
                    {snapshotBatches
                      .filter((batch) => batch.gatingPreset === 'ungated')
                      .map((batch) => (
                        <option key={batch.gatingBatchId} value={batch.gatingBatchId}>
                          {snapshotOptionLabel(batch, 'ungated')}
                        </option>
                      ))}
                  </select>
                  <span className="field-hint">공식 gating-effect 비교용 ungated snapshot입니다.</span>
                </label>
                <label className="filter-field">Official Snapshot (rule_only)
                  <select value={form.officialGatingRuleOnlyBatchId} onChange={(event) => setForm((prev) => ({ ...prev, officialGatingRuleOnlyBatchId: event.target.value }))}>
                    <option value="">Select rule_only snapshot</option>
                    {snapshotBatches
                      .filter((batch) => batch.gatingPreset === 'rule_only')
                      .map((batch) => (
                        <option key={batch.gatingBatchId} value={batch.gatingBatchId}>
                          {snapshotOptionLabel(batch, 'rule_only')}
                        </option>
                      ))}
                  </select>
                  <span className="field-hint">공식 gating-effect 비교용 rule_only snapshot입니다.</span>
                </label>
                <label className="filter-field">Official Snapshot (full_gating)
                  <select value={form.officialGatingFullGatingBatchId} onChange={(event) => setForm((prev) => ({ ...prev, officialGatingFullGatingBatchId: event.target.value }))}>
                    <option value="">Select full_gating snapshot</option>
                    {snapshotBatches
                      .filter((batch) => batch.gatingPreset === 'full_gating')
                      .map((batch) => (
                        <option key={batch.gatingBatchId} value={batch.gatingBatchId}>
                          {snapshotOptionLabel(batch, 'full_gating')}
                        </option>
                      ))}
                  </select>
                  <span className="field-hint">공식 gating-effect 비교용 full_gating snapshot입니다.</span>
                </label>
              </>
            )}
            <label className="filter-field">Gating Snapshot
              <select
                value={form.sourceGatingBatchId}
                disabled={form.syntheticFreeBaseline || (form.runDiscipline === 'official' && form.officialComparisonType === 'gating_effect')}
                onChange={(event) => setForm((prev) => ({ ...prev, sourceGatingBatchId: event.target.value }))}
              >
                <option value="">
                  {form.syntheticFreeBaseline
                    ? 'Not used for synthetic-free baseline'
                    : form.runDiscipline === 'official'
                    ? (form.officialComparisonType === 'gating_effect' ? 'Not used for official gating-effect' : 'Select snapshot (required)')
                    : form.stageCutoffEnabled
                    ? 'Select full_gating snapshot (required)'
                    : 'Auto (latest matching)'}
                </option>
                {sourceSnapshotOptions.map((batch) => {
                  return (
                    <option key={batch.gatingBatchId} value={batch.gatingBatchId}>
                      {snapshotOptionLabel(batch)}
                    </option>
                  )
                })}
              </select>
              <span className="field-hint">
                {form.runDiscipline === 'official' && form.officialComparisonType === 'gating_effect'
                  ? 'official gating-effect에서는 위 3개 전용 snapshot을 사용합니다.'
                  : '완료된 게이팅 배치 전체를 표시합니다. 실행 시 호환성 검증을 수행합니다.'}
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
                {form.gatingApplied ? '메모리 조회 대상 게이팅 단계를 선택합니다.' : '게이팅 미반영 상태이므로 ungated로 고정됩니다.'}
              </span>
            </label>
            <label className="filter-field">생성 방식
              <div className="method-row">
                {methods.map((method) => (
                  <label
                    key={method.methodCode}
                    className={`check-pill ${methodCodesForRun.includes(method.methodCode) ? 'is-active' : ''} ${(form.syntheticFreeBaseline || methodSelectionLocked) ? 'is-disabled' : ''}`}
                  >
                    <input
                      type="checkbox"
                      checked={methodCodesForRun.includes(method.methodCode)}
                      disabled={form.syntheticFreeBaseline || methodSelectionLocked}
                      onChange={(event) => handleToggleMethod(method.methodCode, event.target.checked)}
                    />
                    <span className="check-pill__box" aria-hidden="true">{methodCodesForRun.includes(method.methodCode) ? '✓' : ''}</span>
                    <span className="check-pill__text">{method.methodCode}</span>
                  </label>
                ))}
              </div>
              <span className="field-hint">
                {form.syntheticFreeBaseline
                  ? 'Synthetic-free baseline에서는 생성 방식 선택이 비활성화됩니다.'
                  : methodSelectionLocked
                  ? `스냅샷 method(${snapshotMethodCode}) 기준으로 자동 고정되어 중복 선택을 제거했습니다.`
                  : '스냅샷 미선택 또는 legacy 스냅샷에서는 수동 선택이 필요합니다.'}
              </span>
            </label>
          </div>

          <div className="form-grid form-grid--3">
            <label className="filter-field filter-field--small">Stage Cutoff Level
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
              <span className="field-hint">full_gating 배치 기준 stage cutoff 레벨입니다.</span>
            </label>
            <label className="filter-field filter-field--small">Rewrite Threshold
              <input
                type="number"
                min="0"
                max="1"
                step="0.01"
                value={form.threshold}
                disabled={!form.rewriteEnabled || !form.selectiveRewrite}
                onChange={(event) => setForm((prev) => ({ ...prev, threshold: event.target.value }))}
              />
              <span className="field-hint">`rewrite_threshold`: selective 모드에서 후보 쿼리 채택 임계값</span>
            </label>
            <label className="filter-field filter-field--small">Retrieval Top-K
              <input type="number" min="1" value={form.retrievalTopK} onChange={(event) => setForm((prev) => ({ ...prev, retrievalTopK: event.target.value }))} />
              <span className="field-hint">`retrieval_top_k`: 검색 단계에서 가져오는 후보 청크 수</span>
            </label>
            <label className="filter-field filter-field--small">Rerank Top-N
              <input type="number" min="1" value={form.rerankTopN} onChange={(event) => setForm((prev) => ({ ...prev, rerankTopN: event.target.value }))} />
              <span className="field-hint">`rerank_top_n`: answer eval에서 최종 재정렬에 쓰는 개수</span>
            </label>
          </div>

          <div className="form-grid form-grid--3">
            <label className="filter-field filter-field--small">Retriever Mode
              <select value={form.retrieverMode} onChange={(event) => setForm((prev) => ({ ...prev, ...retrieverPresetForMode(event.target.value) }))}>
                <option value="bm25_only">BM25 Only</option>
                <option value="dense_only">Dense Only</option>
                <option value="hybrid">Hybrid</option>
              </select>
              <span className="field-hint">BM25/Dense/Hybrid ranking mode</span>
            </label>
            <label className="filter-field">Dense Model
              <input
                value={form.denseEmbeddingModel}
                disabled
                readOnly
              />
              <span className="field-hint">intfloat/multilingual-e5-small</span>
            </label>
            <label className="filter-field filter-field--small">Candidate Pool
              <input type="number" min="1" value={form.retrieverCandidatePoolK} disabled readOnly />
              <span className="field-hint">local rank 후보 풀 크기</span>
            </label>
            <label className="filter-field filter-field--small">Dense Weight
              <input type="number" min="0" max="1" step="0.01" value={form.retrieverDenseWeight} disabled readOnly />
            </label>
            <label className="filter-field filter-field--small">BM25 Weight
              <input type="number" min="0" max="1" step="0.01" value={form.retrieverBm25Weight} disabled readOnly />
            </label>
            <label className="filter-field filter-field--small">Technical Weight
              <input type="number" min="0" max="1" step="0.01" value={form.retrieverTechnicalWeight} disabled readOnly />
            </label>
          </div>

          <div className="checkbox-row">
            <label className={`check-pill ${form.syntheticFreeBaseline ? 'is-active' : ''}`}>
              <input type="checkbox" checked={form.syntheticFreeBaseline} onChange={(event) => setForm((prev) => ({ ...prev, syntheticFreeBaseline: event.target.checked }))} />
              <span className="check-pill__box" aria-hidden="true">{form.syntheticFreeBaseline ? '✓' : ''}</span>
              <span className="check-pill__text">Synthetic-free baseline</span>
            </label>
            <label className={`check-pill ${form.stageCutoffEnabled ? 'is-active' : ''} ${(form.syntheticFreeBaseline || !form.gatingApplied || form.runDiscipline === 'official') ? 'is-disabled' : ''}`}>
              <input type="checkbox" checked={form.stageCutoffEnabled} disabled={form.syntheticFreeBaseline || !form.gatingApplied || form.runDiscipline === 'official'} onChange={(event) => setForm((prev) => ({ ...prev, stageCutoffEnabled: event.target.checked }))} />
              <span className="check-pill__box" aria-hidden="true">{form.stageCutoffEnabled ? '✓' : ''}</span>
              <span className="check-pill__text">Stage Cutoff</span>
            </label>
            <label><input type="checkbox" checked={form.gatingApplied} disabled={form.syntheticFreeBaseline} onChange={(event) => setForm((prev) => ({ ...prev, gatingApplied: event.target.checked }))} />게이팅 반영</label>
            <label><input type="checkbox" checked={form.rewriteEnabled} disabled={form.syntheticFreeBaseline} onChange={(event) => setForm((prev) => ({ ...prev, rewriteEnabled: event.target.checked }))} />Rewrite 사용</label>
            <label><input type="checkbox" checked={form.selectiveRewrite} disabled={form.syntheticFreeBaseline || !form.rewriteEnabled} onChange={(event) => setForm((prev) => ({ ...prev, selectiveRewrite: event.target.checked, useSessionContext: event.target.checked ? prev.useSessionContext : false }))} />Selective</label>
            <label><input type="checkbox" checked={form.useSessionContext} disabled={form.syntheticFreeBaseline || !form.rewriteEnabled || !form.selectiveRewrite} onChange={(event) => setForm((prev) => ({ ...prev, useSessionContext: event.target.checked }))} />Session Context</label>
            <label><input type="checkbox" checked={form.rewriteAnchorInjectionEnabled} disabled={form.syntheticFreeBaseline || !form.rewriteEnabled} onChange={(event) => setForm((prev) => ({ ...prev, rewriteAnchorInjectionEnabled: event.target.checked }))} />Anchor Injection</label>
            <label className="rewrite-strategy-field">
              <span className="rewrite-strategy-field__label">Rewrite Retrieval</span>
              <div className="rewrite-strategy-field__control">
                <select
                  value={form.rewriteRetrievalStrategy}
                  disabled={form.syntheticFreeBaseline || !form.rewriteEnabled}
                  onChange={(event) => setForm((prev) => ({ ...prev, rewriteRetrievalStrategy: event.target.value }))}
                >
                  <option value="replace">replace</option>
                  <option value="interleave">interleave</option>
                  <option value="max_score">max_score</option>
                </select>
                <span className="rewrite-strategy-field__chip">
                  {REWRITE_RETRIEVAL_OPTION_META[form.rewriteRetrievalStrategy]?.description || '전략 선택'}
                </span>
              </div>
            </label>
          </div>

          <div className="state-note">
            <strong>옵션 의미:</strong> 게이팅 반영=메모리 후보를 게이팅 결과로 제한, Rewrite 사용=질의 재작성 활성화,
            Selective=매번 Rewrite하지 않고 품질 개선 가능성 있을 때만 적용, Session Context=대화 문맥을 Rewrite 후보 생성에 투입.
          </div>

          {selectedSnapshot && !form.syntheticFreeBaseline && (
            <div className="state-note">
              <strong>선택 스냅샷:</strong> {shortId(selectedSnapshot.gatingBatchId)} / preset {selectedSnapshot.gatingPreset} / method {selectedSnapshot.methodCode || '-'} / source run {selectedSnapshot.sourceGatingRunId ? 'available' : 'missing'}
            </div>
          )}

          <div className="form-actions">
            <button type="submit" className="button button--primary">테스트 실행</button>
            <button type="button" className="button" onClick={() => Promise.all([loadTests(), loadRewriteLogs(), loadLlmJobs(), loadGatingBatches()]).catch((error) => notify(error.message, 'error'))}>목록 새로고침</button>
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
                      <article key={card.label} className="compare-overview-card">
                        <div className="compare-overview-card__label">{card.label}</div>
                        <div className="compare-overview-card__value">{card.value}</div>
                        <div className="compare-overview-card__meta">{card.meta}</div>
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
                              {row.priority === 'core' && <span className="metric-chip metric-chip--core">Core KPI</span>}
                              <span className={`metric-chip metric-chip--${row.outcome}`}>{compareOutcomeLabel(row.outcome, leftRunLabel, rightRunLabel)}</span>
                            </div>
                          </div>
                          <div className="compare-metric-card__hint">{METRIC_TREND_LABEL[row.trend] || METRIC_TREND_LABEL.higher}</div>
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
                            <span>Delta (B-A)</span>
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
                  <th className="compare-data-table__metric-col">Metric</th>
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
                  <th className="compare-data-table__delta-col">Delta / Change</th>
                  <th className="compare-data-table__result-col">Result</th>
                </tr>
              </thead>
              {compareTableGroups.map((group) => (
                <tbody key={group.key} className={`compare-data-table__section compare-data-table__section--${group.key}`}>
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
                    const trendLabel = METRIC_TREND_LABEL[row.trend] || METRIC_TREND_LABEL.higher
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
                          <div className="compare-data-table__metric-sub">{trendLabel}</div>
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
                          <small>{resultInfo.sub}</small>
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
            <thead><tr><th>데이터셋 ID</th><th>이름</th><th>버전</th><th>문항 수</th><th>생성 일시</th><th>상세</th></tr></thead>
            <tbody>
              {datasets.map((dataset) => (
                <tr key={dataset.datasetId}>
                  <td><IdBadge value={dataset.datasetId} /></td>
                  <td>{dataset.datasetName}</td>
                  <td>{dataset.version || '-'}</td>
                  <td>{dataset.totalItems ?? 0}</td>
                  <td>{fmtTime(dataset.createdAt)}</td>
                  <td><button type="button" className="button button--ghost" onClick={() => openDatasetItems(dataset.datasetId)}>상세 조회</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="table-shell">
        <div className="table-header">
          <div className="table-title">RAG 테스트 실행 이력</div>
          <button type="button" className="button" onClick={() => Promise.all([loadTests(), loadRewriteLogs(), loadLlmJobs(), loadGatingBatches()]).catch((error) => notify(error.message, 'error'))}>새로고침</button>
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
                <th>Rewrite 모드</th>
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
                      <div className="run-history-exec__title" title={resolveCompareRunPrimaryLabel(run, 'RAG Test')}>
                        {resolveCompareRunPrimaryLabel(run, 'RAG Test')}
                      </div>
                      <div className="run-history-exec__meta">{resolveCompareRunSecondaryLabel(run)}</div>
                      <div className="run-history-exec__badges">
                        <StatusBadge value={run.status} />
                        <IdBadge value={run.ragTestRunId} />
                      </div>
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
                        className="button button--ghost"
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
            onClick={() => setHistoryPage((prev) => Math.max(0, prev - 1))}
          >이전</button>
          <div className="pagination__label">페이지 {currentHistoryPage + 1}</div>
          <button
            type="button"
            className="button"
            disabled={currentHistoryPage + 1 >= historyTotalPages}
            onClick={() => setHistoryPage((prev) => Math.min(historyTotalPages - 1, prev + 1))}
          >다음</button>
        </div>
      </section>

      <LlmJobsTable jobs={llmJobs} onAction={executeLlmAction} onDetail={openJobDetail} />

      <section className="table-shell">
        <div className="table-header"><div className="table-title">Rewrite 디버그 로그</div></div>
        <div className="table-wrap">
          <table className="data-table">
            <thead><tr><th>rewrite_log_id</th><th>raw_query</th><th>final_query</th><th>전략</th><th>적용</th><th>delta</th><th>결정 사유</th><th>상세</th></tr></thead>
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
            </tbody>
          </table>
        </div>
      </section>

      <Modal data={modal} onClose={() => setModal(null)} />
    </>
  )
}
