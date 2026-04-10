import { shortId, statusTone } from '../lib/format.js'

export function StatusBadge({ value, label }) {
  return <span className="status-badge" data-status={statusTone(value)}>{label || String(value || '-')}</span>
}

export function IdBadge({ value, plain = false }) {
  if (!value) return <span className="plain-badge">-</span>
  return (
    <span className={plain ? 'plain-badge mono-text' : 'id-badge mono-text'} title={String(value)}>
      {shortId(value)}
      {!plain && <CopyButton value={String(value)} />}
    </span>
  )
}

export function CopyButton({ value }) {
  return (
    <button type="button" className="copy-button" onClick={() => navigator.clipboard?.writeText(value)}>
      복사
    </button>
  )
}

export function DetailCard({ label, value, mono = true }) {
  return (
    <article className="detail-card">
      <div className="detail-item__label">{label}</div>
      <pre className={mono ? 'detail-item__value detail-item__value--mono' : 'detail-item__value'}>{value || '-'}</pre>
    </article>
  )
}

export function Modal({ data, onClose }) {
  if (!data) return null
  return (
    <div className="modal-backdrop" onClick={onClose} role="presentation">
      <div className="modal" onClick={(event) => event.stopPropagation()} role="dialog" aria-modal="true">
        <header className="modal__header">
          <h2 className="modal__title">{data.title}</h2>
          <button type="button" className="button button--ghost" onClick={onClose}>닫기</button>
        </header>
        <div className="modal__body">{data.body}</div>
      </div>
    </div>
  )
}

export function NumberInput({ label, value, onChange, step = '1' }) {
  return (
    <label className="filter-field">
      {label}
      <input type="number" step={step} value={value} onChange={(event) => onChange(event.target.value)} />
    </label>
  )
}

export function StageCard({ title, checked, onToggle, children }) {
  return (
    <article className="stage-card">
      <div className="stage-card__header">
        <div className="stage-card__title">{title}</div>
        <label className="stage-card__switch">
          <input type="checkbox" checked={checked} onChange={(event) => onToggle(event.target.checked)} />
          사용
        </label>
      </div>
      <div className="stage-card__body">{children}</div>
    </article>
  )
}
