import { Fragment, startTransition, useDeferredValue, useEffect, useMemo, useState } from 'react'
import { ChatPage } from './pages/ChatPage.jsx'
import { GatingPage } from './pages/GatingPage.jsx'
import { PipelinePage } from './pages/PipelinePage.jsx'
import { RagPage } from './pages/RagPage.jsx'
import { SyntheticPage } from './pages/SyntheticPage.jsx'

const THEME_STORAGE_KEY = 'query-forge-theme'

function resolveSystemTheme() {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return 'dark'
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

function readInitialTheme() {
  if (typeof window === 'undefined') return 'dark'
  try {
    const stored = window.localStorage.getItem(THEME_STORAGE_KEY)
    if (stored === 'light' || stored === 'dark') return stored
  } catch {
    // localStorage can be unavailable in restricted browser contexts.
  }
  return resolveSystemTheme()
}

function applyTheme(theme) {
  if (typeof document === 'undefined') return
  document.documentElement.dataset.theme = theme
  document.documentElement.style.colorScheme = theme
}

function withThemeTransition() {
  if (typeof document === 'undefined') return
  const root = document.documentElement
  root.dataset.themeTransition = 'true'
  window.setTimeout(() => {
    delete root.dataset.themeTransition
  }, 360)
}

export const ADMIN_PAGE_META = {
  pipeline: {
    title: 'Pipeline Monitor',
    subtitle: '수집부터 코퍼스 적재까지 ingest pipeline 상태와 실행 흐름을 관제합니다.',
    path: '/admin/pipeline',
    icon: 'PL',
  },
  synthetic: {
    title: 'Synthetic Query Studio',
    subtitle: 'A~G generation strategy, prompt version, batch 진행률과 질의 품질을 운영합니다.',
    path: '/admin/synthetic-queries',
    icon: 'SQ',
  },
  gating: {
    title: 'Quality Gate',
    subtitle: 'Rule, LLM, utility, diversity gate를 재현 가능한 runtime config로 제어합니다.',
    path: '/admin/quality-gating',
    icon: 'GT',
  },
  rag: {
    title: 'Retrieval Eval Lab',
    subtitle: 'Snapshot 기반 retrieval, rewrite, answer quality와 latency 실험을 실행합니다.',
    path: '/admin/rag-tests',
    icon: 'RG',
  },
}

function App() {
  const [path, setPath] = useState(() => {
    const current = window.location.pathname
    if (current === '/admin') {
      window.history.replaceState({}, '', ADMIN_PAGE_META.pipeline.path)
      return ADMIN_PAGE_META.pipeline.path
    }
    return current
  })
  const deferredPath = useDeferredValue(path)
  const [toasts, setToasts] = useState([])
  const [theme, setTheme] = useState(readInitialTheme)

  const notify = (message, tone = 'success') => {
    const id = crypto.randomUUID()
    setToasts((prev) => [...prev, { id, message, tone }])
    window.setTimeout(() => setToasts((prev) => prev.filter((item) => item.id !== id)), 2600)
  }

  useEffect(() => {
    applyTheme(theme)
  }, [theme])

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return undefined
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    const onSystemThemeChange = () => {
      try {
        if (window.localStorage.getItem(THEME_STORAGE_KEY)) return
      } catch {
        // If localStorage is blocked, keep following the system preference.
      }
      setTheme(resolveSystemTheme())
    }
    media.addEventListener?.('change', onSystemThemeChange)
    return () => media.removeEventListener?.('change', onSystemThemeChange)
  }, [])

  const toggleTheme = () => {
    const nextTheme = theme === 'dark' ? 'light' : 'dark'
    withThemeTransition()
    setTheme(nextTheme)
    try {
      window.localStorage.setItem(THEME_STORAGE_KEY, nextTheme)
    } catch {
      // Theme still changes for the current session if persistence is blocked.
    }
  }

  useEffect(() => {
    const handlePopState = () => {
      const current = window.location.pathname
      if (current === '/admin') {
        window.history.replaceState({}, '', ADMIN_PAGE_META.pipeline.path)
        setPath(ADMIN_PAGE_META.pipeline.path)
        return
      }
      setPath(current)
    }
    window.addEventListener('popstate', handlePopState)
    return () => window.removeEventListener('popstate', handlePopState)
  }, [])

  const navigate = (nextPath) => {
    if (!nextPath || nextPath === path) return
    window.history.pushState({}, '', nextPath)
    startTransition(() => setPath(nextPath))
  }

  return (
    <Fragment>
      {deferredPath.startsWith('/admin')
        ? <AdminApp path={deferredPath} navigate={navigate} notify={notify} theme={theme} onToggleTheme={toggleTheme} />
        : <ChatPage navigate={navigate} notify={notify} />}
      <div className="toast-stack">
        {toasts.map((toast) => (
          <div key={toast.id} className={`toast toast--${toast.tone}`}>
            {toast.message}
          </div>
        ))}
      </div>
    </Fragment>
  )
}

function AdminApp({ path, navigate, notify, theme, onToggleTheme }) {
  const [sidebarOpen, setSidebarOpen] = useState(false)
  const pageKey = useMemo(() => {
    if (path.startsWith('/admin/synthetic-queries')) return 'synthetic'
    if (path.startsWith('/admin/quality-gating')) return 'gating'
    if (path.startsWith('/admin/rag-tests')) return 'rag'
    return 'pipeline'
  }, [path])
  const meta = ADMIN_PAGE_META[pageKey]
  const navItems = [
    { key: 'pipeline', label: 'Pipeline Monitor', meta: '코퍼스 ingest', path: ADMIN_PAGE_META.pipeline.path, icon: ADMIN_PAGE_META.pipeline.icon },
    { key: 'synthetic', label: 'Synthetic Query Studio', meta: 'A~G 전략 배치', path: ADMIN_PAGE_META.synthetic.path, icon: ADMIN_PAGE_META.synthetic.icon },
    { key: 'gating', label: 'Quality Gate', meta: '스냅샷 제어', path: ADMIN_PAGE_META.gating.path, icon: ADMIN_PAGE_META.gating.icon },
    { key: 'rag', label: 'Retrieval Eval', meta: '품질/latency', path: ADMIN_PAGE_META.rag.path, icon: ADMIN_PAGE_META.rag.icon },
  ]
  const themeLabel = theme === 'dark' ? 'Dark' : 'Light'

  return (
    <div className={`admin-shell ${sidebarOpen ? 'is-sidebar-open' : ''}`}>
      <aside className="admin-sidebar">
        <div className="admin-sidebar__brand">
          <div className="admin-sidebar__logo">QF</div>
          <div>
            <div className="admin-sidebar__title">Query Forge AI Console</div>
            <div className="admin-sidebar__subtitle">Snapshot-grounded RAG Ops</div>
          </div>
        </div>
        <div className="admin-sidebar__presence" aria-label="AI orchestration status">
          <span className="admin-sidebar__presence-signal" aria-hidden="true"><span /></span>
          <span className="admin-sidebar__presence-copy">
            <strong>AI Ops Core</strong>
            <small>Live orchestration</small>
          </span>
        </div>
        <div className="admin-sidebar__badge">스냅샷 기반 AI 실험 운영</div>
        <nav className="admin-nav" aria-label="관리자 내비게이션">
          {navItems.map((item) => (
            <button
              key={item.key}
              type="button"
              className={`admin-nav__link ${item.key === pageKey ? 'is-active' : ''}`}
              onClick={() => {
                setSidebarOpen(false)
                navigate(item.path)
              }}
            >
              <span className="admin-nav__icon" aria-hidden="true">{item.icon}</span>
              <span className="admin-nav__copy">
                <span>{item.label}</span>
                <small>{item.meta}</small>
              </span>
            </button>
          ))}
        </nav>
        <div className="admin-sidebar__section">
          <div className="admin-sidebar__section-title">Workspace</div>
          <button type="button" className="admin-nav__link" onClick={() => navigate('/')}>
            <span className="admin-nav__icon" aria-hidden="true">CH</span>
            <span className="admin-nav__copy">
              <span>Chat Surface</span>
              <small>운영 질의 UI</small>
            </span>
          </button>
        </div>
      </aside>
      <section className="admin-main">
        <header className="admin-topbar">
          <button type="button" className="admin-topbar__menu" onClick={() => setSidebarOpen((prev) => !prev)}>메뉴</button>
          <div className="admin-topbar__meta">
            <div className="admin-topbar__title">{meta.title}</div>
            <div className="admin-topbar__subtitle">{meta.subtitle}</div>
          </div>
          <div className="admin-topbar__actions">
            <button
              type="button"
              className={`theme-toggle ${theme === 'dark' ? 'is-dark' : 'is-light'}`}
              onClick={onToggleTheme}
              aria-label={`${theme === 'dark' ? 'Light' : 'Dark'} mode로 전환`}
              title={`${theme === 'dark' ? 'Light' : 'Dark'} mode`}
            >
              <span className="theme-toggle__track" aria-hidden="true"><span className="theme-toggle__thumb" /></span>
              <span className="theme-toggle__label">{themeLabel}</span>
            </button>
            <button type="button" className="button button--success" onClick={() => navigate(ADMIN_PAGE_META.rag.path)}>Run Retrieval Eval</button>
          </div>
        </header>
        <main className="admin-content">
          <section className="page-header">
            <div className="page-header__eyebrow">AI Operations Console</div>
            <h1 className="page-header__title">{meta.title}</h1>
            <p className="page-header__subtitle">{meta.subtitle}</p>
          </section>
          {pageKey === 'pipeline' && <PipelinePage notify={notify} />}
          {pageKey === 'synthetic' && <SyntheticPage notify={notify} />}
          {pageKey === 'gating' && <GatingPage notify={notify} />}
          {pageKey === 'rag' && <RagPage notify={notify} />}
        </main>
      </section>
    </div>
  )
}

export default App
