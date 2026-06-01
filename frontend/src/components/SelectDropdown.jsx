import { useEffect, useMemo, useRef, useState } from 'react'

export function SelectDropdown({
  value,
  options,
  onChange,
  placeholder = '항목 선택',
  clearLabel = '전체',
  emptyLabel = '선택 가능한 항목이 없습니다.',
  searchPlaceholder = '검색어 입력...',
  disabled = false,
  onOpen = null,
  allowClear = true,
}) {
  const shellRef = useRef(null)
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')

  const selected = useMemo(
    () => options.find((option) => option.value === value) || null,
    [options, value]
  )

  const optionBadges = (option) => {
    if (!option) return []
    if (Array.isArray(option.badges)) {
      return option.badges.filter((badge) => badge && badge.label)
    }
    return option.badgeLabel ? [{ label: option.badgeLabel, tone: option.badgeTone || 'neutral' }] : []
  }

  const filteredOptions = useMemo(() => {
    const normalized = query.trim().toLowerCase()
    if (!normalized) return options
    return options.filter((option) => {
      const text = `${option.label || ''} ${option.meta || ''}`.toLowerCase()
      return text.includes(normalized)
    })
  }, [options, query])

  useEffect(() => {
    if (!open) return undefined
    const onPointerDown = (event) => {
      if (!shellRef.current?.contains(event.target)) {
        setOpen(false)
        setQuery('')
      }
    }
    const onKeyDown = (event) => {
      if (event.key === 'Escape') {
        setOpen(false)
        setQuery('')
      }
    }
    document.addEventListener('mousedown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('mousedown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open])

  const close = () => {
    setOpen(false)
    setQuery('')
  }

  const choose = (nextValue) => {
    onChange(nextValue)
    close()
  }

  const toggleOpen = () => {
    const nextOpen = !open
    if (nextOpen && typeof onOpen === 'function') {
      onOpen()
    }
    if (!nextOpen) {
      setQuery('')
    }
    setOpen(nextOpen)
  }

  return (
    <div className={`custom-dropdown ${open ? 'is-open' : ''}`} ref={shellRef}>
      <button
        type="button"
        className="custom-dropdown__trigger"
        onClick={toggleOpen}
        disabled={disabled}
      >
        <span className="custom-dropdown__trigger-label">{selected?.label || placeholder}</span>
        {optionBadges(selected).length > 0 && (
          <span className="custom-dropdown__badges">
            {optionBadges(selected).map((badge) => (
              <span key={badge.label} className="custom-dropdown__badge" data-tone={badge.tone || 'neutral'}>
                {badge.label}
              </span>
            ))}
          </span>
        )}
        <span className="custom-dropdown__caret" aria-hidden="true">▾</span>
      </button>
      {open && (
        <div className="custom-dropdown__menu">
          <input
            className="custom-dropdown__search"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder={searchPlaceholder}
          />
          <div className="custom-dropdown__options">
            {allowClear && (
              <button
                type="button"
                className={`custom-dropdown__option ${!value ? 'is-selected' : ''}`}
                onClick={() => choose('')}
              >
                <span className="custom-dropdown__option-label">{clearLabel}</span>
              </button>
            )}
            {filteredOptions.length === 0 ? (
              <div className="custom-dropdown__empty">{emptyLabel}</div>
            ) : (
              filteredOptions.map((option) => (
                <button
                  key={option.value}
                  type="button"
                  className={`custom-dropdown__option ${option.value === value ? 'is-selected' : ''}`}
                  onClick={() => choose(option.value)}
                >
                  <span className="custom-dropdown__option-main">
                    <span className="custom-dropdown__option-label">{option.label}</span>
                    {optionBadges(option).length > 0 && (
                      <span className="custom-dropdown__badges">
                        {optionBadges(option).map((badge) => (
                          <span key={badge.label} className="custom-dropdown__badge" data-tone={badge.tone || 'neutral'}>
                            {badge.label}
                          </span>
                        ))}
                      </span>
                    )}
                  </span>
                  {option.meta && <span className="custom-dropdown__option-meta">{option.meta}</span>}
                </button>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  )
}
