import { Fragment, startTransition, useCallback, useDeferredValue, useEffect, useMemo, useState } from 'react'
import { ChatPage } from './pages/ChatPage.jsx'
import { DomainHomePage } from './pages/DomainHomePage.jsx'
import { GatingPage } from './pages/GatingPage.jsx'
import { PipelinePage } from './pages/PipelinePage.jsx'
import { PromptStudioPage } from './pages/PromptStudioPage.jsx'
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
  domains: {
    title: 'Domain Atlas',
    subtitle: '기술 문서 도메인을 선택하고 새 도메인 작업 공간을 시작합니다.',
    path: '/admin',
    icon: 'DM',
  },
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
  prompts: {
    title: 'Prompt Studio',
    subtitle: '도메인이 공유하는 합성 질의 생성 및 RAG rewrite 프롬프트를 관리합니다.',
    path: '/admin/prompts',
    icon: 'PR',
  },
}

function parseAdminRoute(path) {
  const segments = path.split('/').filter(Boolean)
  if (segments.length === 1 && segments[0] === 'admin') {
    return { pageKey: 'domains', domainKey: null, domainBase: null }
  }
  if (segments[0] === 'admin' && segments[1] === 'domains' && segments[2]) {
    return {
      pageKey: segments[3] || 'pipeline',
      domainKey: segments[2],
      domainBase: `/admin/domains/${segments[2]}`,
    }
  }
  if (segments[0] === 'admin' && segments[1] === 'prompts') {
    return { pageKey: 'prompts', domainKey: null, domainBase: null }
  }
  if (path.startsWith('/admin/synthetic-queries')) return { pageKey: 'synthetic', domainKey: null, domainBase: null }
  if (path.startsWith('/admin/quality-gating')) return { pageKey: 'gating', domainKey: null, domainBase: null }
  if (path.startsWith('/admin/rag-tests')) return { pageKey: 'rag', domainKey: null, domainBase: null }
  return { pageKey: 'pipeline', domainKey: null, domainBase: null }
}

function App() {
  const [path, setPath] = useState(() => {
    return window.location.pathname
  })
  const deferredPath = useDeferredValue(path)
  const [toasts, setToasts] = useState([])
  const [theme, setTheme] = useState(readInitialTheme)

  const notify = useCallback((message, tone = 'success') => {
    const id = crypto.randomUUID()
    setToasts((prev) => [...prev, { id, message, tone }])
    window.setTimeout(() => setToasts((prev) => prev.filter((item) => item.id !== id)), 2600)
  }, [])

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
      setPath(window.location.pathname)
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
  const [domainSummary, setDomainSummary] = useState(null)
  const route = useMemo(() => parseAdminRoute(path), [path])
  const pageKey = route.pageKey
  const domainId = route.domainKey ? domainSummary?.domainId || null : null
  const metaKey = pageKey === 'synthetic-queries' ? 'synthetic'
    : pageKey === 'quality-gating' ? 'gating'
      : pageKey === 'rag-tests' ? 'rag'
        : pageKey
  const meta = ADMIN_PAGE_META[metaKey] || ADMIN_PAGE_META.pipeline
  const domainNavItems = [
    { key: 'pipeline', label: 'Pipeline Monitor', meta: '코퍼스 ingest', path: `${route.domainBase || '/admin'}/pipeline`, icon: ADMIN_PAGE_META.pipeline.icon },
    { key: 'synthetic-queries', label: 'Synthetic Query Studio', meta: 'A~G 전략 배치', path: `${route.domainBase || '/admin'}/synthetic-queries`, icon: ADMIN_PAGE_META.synthetic.icon },
    { key: 'quality-gating', label: 'Quality Gate', meta: '스냅샷 제어', path: `${route.domainBase || '/admin'}/quality-gating`, icon: ADMIN_PAGE_META.gating.icon },
    { key: 'rag-tests', label: 'Retrieval Eval', meta: '품질/latency', path: `${route.domainBase || '/admin'}/rag-tests`, icon: ADMIN_PAGE_META.rag.icon },
  ]
  const activeNavKey = pageKey === 'synthetic' ? 'synthetic-queries'
    : pageKey === 'gating' ? 'quality-gating'
      : pageKey === 'rag' ? 'rag-tests'
        : pageKey
  const themeLabel = theme === 'dark' ? 'Dark' : 'Light'
  const workspacePending = Boolean(route.domainKey && pageKey !== 'domains' && !domainId)
  const pageInstanceKey = `${pageKey}:${route.domainKey || 'global'}:${domainId || 'none'}`

  useEffect(() => {
    setDomainSummary(null)
  }, [route.domainKey])

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
          <button
            type="button"
            className={`admin-nav__link ${pageKey === 'domains' ? 'is-active' : ''}`}
            onClick={() => {
              setSidebarOpen(false)
              navigate('/admin')
            }}
          >
            <span className="admin-nav__icon" aria-hidden="true">DM</span>
            <span className="admin-nav__copy">
              <span>Domain Atlas</span>
              <small>도메인 선택</small>
            </span>
          </button>
          {route.domainKey && domainNavItems.map((item) => (
            <button
              key={item.key}
              type="button"
              className={`admin-nav__link ${item.key === activeNavKey ? 'is-active' : ''}`}
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
          <div className="admin-sidebar__section-title">Common</div>
          <button
            type="button"
            className={`admin-nav__link ${pageKey === 'prompts' ? 'is-active' : ''}`}
            onClick={() => navigate('/admin/prompts')}
          >
            <span className="admin-nav__icon" aria-hidden="true">PR</span>
            <span className="admin-nav__copy">
              <span>Prompt Studio</span>
              <small>공통 프롬프트</small>
            </span>
          </button>
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
            <button
              type="button"
              className="button button--success"
              onClick={() => navigate(route.domainBase ? `${route.domainBase}/rag-tests` : '/admin')}
            >
              Run Retrieval Eval
            </button>
          </div>
        </header>
        <main className="admin-content">
          {pageKey !== 'domains' && route.domainKey && (
            <DomainWorkspaceBanner domainKey={route.domainKey} notify={notify} onSummary={setDomainSummary} />
          )}
          <section className="page-header">
            <div className="page-header__eyebrow">
              {route.domainKey ? `Domain Workspace / ${route.domainKey}` : 'AI Operations Console'}
            </div>
            <h1 className="page-header__title">{meta.title}</h1>
            <p className="page-header__subtitle">{meta.subtitle}</p>
          </section>
          {pageKey === 'domains' && <DomainHomePage navigate={navigate} notify={notify} />}
          {pageKey === 'prompts' && <PromptStudioPage path={path} notify={notify} />}
          {workspacePending && (
            <section className="empty-state">
              <h2>도메인 컨텍스트 로딩 중</h2>
            </section>
          )}
          {!workspacePending && pageKey === 'pipeline' && <PipelinePage key={pageInstanceKey} notify={notify} domainKey={route.domainKey} domainId={domainId} />}
          {!workspacePending && pageKey === 'synthetic-queries' && <SyntheticPage key={pageInstanceKey} notify={notify} domainKey={route.domainKey} domainId={domainId} />}
          {!workspacePending && pageKey === 'quality-gating' && <GatingPage key={pageInstanceKey} notify={notify} domainKey={route.domainKey} domainId={domainId} />}
          {!workspacePending && pageKey === 'rag-tests' && <RagPage key={pageInstanceKey} notify={notify} domainKey={route.domainKey} domainId={domainId} />}
          {pageKey === 'synthetic' && <SyntheticPage notify={notify} />}
          {pageKey === 'gating' && <GatingPage notify={notify} />}
          {pageKey === 'rag' && <RagPage notify={notify} />}
        </main>
      </section>
    </div>
  )
}

function DomainWorkspaceBanner({ domainKey, notify, onSummary }) {
  const [summary, setSummary] = useState(null)

  useEffect(() => {
    let cancelled = false
    fetch(`/api/admin/domains/${domainKey}/summary`)
      .then(async (response) => {
        const text = await response.text()
        const payload = text ? JSON.parse(text) : null
        if (!response.ok) throw new Error(payload?.message || payload?.error || `요청 실패 (${response.status})`)
        return payload
      })
      .then((payload) => {
        if (!cancelled) {
          setSummary(payload)
          onSummary?.(payload)
        }
      })
      .catch((error) => {
        if (!cancelled) {
          setSummary(null)
          onSummary?.(null)
        }
        notify(error.message, 'danger')
      })
    return () => {
      cancelled = true
    }
  }, [domainKey, notify, onSummary])

  return (
    <section className="domain-workspace-banner">
      <div>
        <span>Selected Domain</span>
        <strong>{summary?.displayName || domainKey}</strong>
      </div>
      <dl>
        <div>
          <dt>Sources</dt>
          <dd>{summary?.sourceCount ?? 0}</dd>
        </div>
        <div>
          <dt>Docs</dt>
          <dd>{summary?.activeDocumentCount ?? 0}</dd>
        </div>
        <div>
          <dt>Chunks</dt>
          <dd>{summary?.activeChunkCount ?? 0}</dd>
        </div>
        <div>
          <dt>RAG</dt>
          <dd>{summary?.ragTestRunCount ?? 0}</dd>
        </div>
      </dl>
    </section>
  )
}

export default App
