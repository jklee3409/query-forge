export function SectionHeader({ eyebrow, title, description, actions }) {
  return (
    <div className="section-heading">
      <div className="section-heading__copy">
        {eyebrow && <div className="section-heading__eyebrow">{eyebrow}</div>}
        <h2 className="section-heading__title">{title}</h2>
        {description && <p className="section-heading__description">{description}</p>}
      </div>
      {actions && <div className="section-heading__actions">{actions}</div>}
    </div>
  )
}

export function MetricCard({ label, value, meta, tone = 'neutral' }) {
  return (
    <article className="metric-card" data-tone={tone}>
      <div className="metric-card__label">{label}</div>
      <div className="metric-card__value">{value}</div>
      {meta && <div className="metric-card__meta">{meta}</div>}
    </article>
  )
}

export function EmptyState({ title = 'No data', description, action }) {
  return (
    <div className="empty-state empty-state--panel">
      <div className="empty-state__title">{title}</div>
      {description && <div className="empty-state__description">{description}</div>}
      {action && <div className="empty-state__action">{action}</div>}
    </div>
  )
}

export function StrategyFlow({ steps }) {
  const normalized = Array.isArray(steps) ? steps.filter(Boolean) : []
  if (normalized.length === 0) return null
  return (
    <div className="strategy-flow" aria-label="strategy flow">
      {normalized.map((step, index) => (
        <span className="strategy-flow__item" key={`${step}-${index}`}>
          <span className="strategy-flow__chip">{step}</span>
          {index < normalized.length - 1 && <span className="strategy-flow__arrow" aria-hidden="true">-&gt;</span>}
        </span>
      ))}
    </div>
  )
}

export function ProgressMetric({ value, max, label, helper }) {
  const numericValue = Number(value || 0)
  const numericMax = Number(max || 0)
  const percent = numericMax > 0
    ? Math.max(0, Math.min(100, (numericValue / numericMax) * 100))
    : 0
  return (
    <div className="progress-metric">
      <div className="progress-metric__head">
        <span>{label}</span>
        <strong>{numericMax > 0 ? `${numericValue} / ${numericMax}` : String(numericValue)}</strong>
      </div>
      <div className="progress-metric__track" aria-hidden="true">
        <span className="progress-metric__bar" style={{ width: `${percent}%` }} />
      </div>
      {helper && <div className="progress-metric__helper">{helper}</div>}
    </div>
  )
}

export function BatchJobCard({ title, subtitle, statusSlot, idSlot, metrics, meta, progress, actions, children }) {
  return (
    <article className="batch-job-card">
      <div className="batch-job-card__main">
        <div className="batch-job-card__head">
          <div className="batch-job-card__title-group">
            <div className="batch-job-card__title-row">
              {statusSlot}
              <h3 className="batch-job-card__title">{title}</h3>
            </div>
            {subtitle && <div className="batch-job-card__subtitle">{subtitle}</div>}
            {idSlot && <div className="batch-job-card__id">{idSlot}</div>}
          </div>
          {actions && <div className="batch-job-card__actions">{actions}</div>}
        </div>
        {Array.isArray(meta) && meta.length > 0 && (
          <div className="batch-job-card__meta-grid">
            {meta.map((item) => (
              <div className="batch-job-card__meta" key={item.label}>
                <span>{item.label}</span>
                <strong>{item.value}</strong>
              </div>
            ))}
          </div>
        )}
        {progress}
        {children}
      </div>
      {Array.isArray(metrics) && metrics.length > 0 && (
        <div className="batch-job-card__metrics">
          {metrics.map((item) => (
            <MetricCard key={item.label} label={item.label} value={item.value} meta={item.meta} tone={item.tone} />
          ))}
        </div>
      )}
    </article>
  )
}

export function ExperimentSection({ title, description, badge, children, collapsible = false, defaultOpen = true }) {
  if (collapsible) {
    return (
      <details className="experiment-section" open={defaultOpen}>
        <summary className="experiment-section__summary">
          <span>
            <strong>{title}</strong>
            {description && <small>{description}</small>}
          </span>
          {badge && <span className="experiment-section__badge">{badge}</span>}
        </summary>
        <div className="experiment-section__body">{children}</div>
      </details>
    )
  }
  return (
    <section className="experiment-section">
      <header className="experiment-section__header">
        <div>
          <h3>{title}</h3>
          {description && <p>{description}</p>}
        </div>
        {badge && <span className="experiment-section__badge">{badge}</span>}
      </header>
      <div className="experiment-section__body">{children}</div>
    </section>
  )
}

export function ConfigSummaryCard({ title = 'Run Preview', items }) {
  return (
    <aside className="config-summary-card">
      <h3>{title}</h3>
      <div className="config-summary-card__items">
        {(items || []).map((item) => (
          <div className="config-summary-card__item" key={item.label}>
            <span>{item.label}</span>
            <strong title={String(item.value || '-')}>{item.value || '-'}</strong>
          </div>
        ))}
      </div>
    </aside>
  )
}

export function BalanceBar({ items }) {
  const normalized = (Array.isArray(items) ? items : [])
    .map((item) => ({ ...item, value: Math.max(0, Number(item.value || 0)) }))
  const total = normalized.reduce((sum, item) => sum + item.value, 0) || 1
  return (
    <div className="balance-bar">
      <div className="balance-bar__track" aria-hidden="true">
        {normalized.map((item) => (
          <span
            key={item.label}
            className="balance-bar__segment"
            data-tone={item.tone || 'neutral'}
            style={{ width: `${(item.value / total) * 100}%` }}
          />
        ))}
      </div>
      <div className="balance-bar__legend">
        {normalized.map((item) => (
          <span key={item.label}>
            <i data-tone={item.tone || 'neutral'} />
            {item.label} {Number(item.value).toFixed(2)}
          </span>
        ))}
      </div>
    </div>
  )
}

export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  loading = false,
  tone = 'danger',
  onConfirm,
  onCancel,
}) {
  if (!open) return null
  return (
    <div className="confirm-backdrop" role="presentation" onClick={onCancel}>
      <section className="confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="confirm-dialog-title" onClick={(event) => event.stopPropagation()}>
        <div className="confirm-dialog__icon" data-tone={tone} aria-hidden="true">!</div>
        <h2 id="confirm-dialog-title">{title}</h2>
        {description && <p>{description}</p>}
        <div className="confirm-dialog__actions">
          <button type="button" className="button" onClick={onCancel} disabled={loading}>{cancelLabel}</button>
          <button type="button" className={`button ${tone === 'danger' ? 'button--danger' : 'button--primary'}`} onClick={onConfirm} disabled={loading}>
            {loading ? 'Working...' : confirmLabel}
          </button>
        </div>
      </section>
    </div>
  )
}
