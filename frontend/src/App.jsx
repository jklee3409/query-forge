import { Fragment, startTransition, useDeferredValue, useEffect, useMemo, useState } from 'react'
import { ChatPage } from './pages/ChatPage.jsx'
import { GatingPage } from './pages/GatingPage.jsx'
import { PipelinePage } from './pages/PipelinePage.jsx'
import { RagPage } from './pages/RagPage.jsx'
import { SyntheticPage } from './pages/SyntheticPage.jsx'

export const ADMIN_PAGE_META = {
  pipeline: {
    title: 'Pipeline Operations',
    subtitle: 'Monitor collection, preprocessing, chunking, glossary extraction, and corpus import runs.',
    path: '/admin/pipeline',
    icon: 'PL',
  },
  synthetic: {
    title: 'Synthetic Query Studio',
    subtitle: 'Operate A/B/C/D/E/F/G generation strategies, prompt versions, batch progress, and query inventory.',
    path: '/admin/synthetic-queries',
    icon: 'SQ',
  },
  gating: {
    title: 'Quality Gating',
    subtitle: 'Control rule, LLM, utility, and diversity gates with reproducible runtime parameters.',
    path: '/admin/quality-gating',
    icon: 'GT',
  },
  rag: {
    title: 'RAG Evaluation Lab',
    subtitle: 'Run snapshot-pinned quality and performance experiments for retrieval, rewrite, and answer evaluation.',
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

  const notify = (message, tone = 'success') => {
    const id = crypto.randomUUID()
    setToasts((prev) => [...prev, { id, message, tone }])
    window.setTimeout(() => setToasts((prev) => prev.filter((item) => item.id !== id)), 2600)
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
        ? <AdminApp path={deferredPath} navigate={navigate} notify={notify} />
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

function AdminApp({ path, navigate, notify }) {
  const [sidebarOpen, setSidebarOpen] = useState(false)
  const pageKey = useMemo(() => {
    if (path.startsWith('/admin/synthetic-queries')) return 'synthetic'
    if (path.startsWith('/admin/quality-gating')) return 'gating'
    if (path.startsWith('/admin/rag-tests')) return 'rag'
    return 'pipeline'
  }, [path])
  const meta = ADMIN_PAGE_META[pageKey]
  const navItems = [
    { key: 'pipeline', label: 'Pipeline Ops', meta: 'Corpus runtime', path: ADMIN_PAGE_META.pipeline.path, icon: ADMIN_PAGE_META.pipeline.icon },
    { key: 'synthetic', label: 'Synthetic Studio', meta: 'Strategy batches', path: ADMIN_PAGE_META.synthetic.path, icon: ADMIN_PAGE_META.synthetic.icon },
    { key: 'gating', label: 'Quality Gating', meta: 'Snapshot control', path: ADMIN_PAGE_META.gating.path, icon: ADMIN_PAGE_META.gating.icon },
    { key: 'rag', label: 'RAG Eval Lab', meta: 'Quality + latency', path: ADMIN_PAGE_META.rag.path, icon: ADMIN_PAGE_META.rag.icon },
  ]

  return (
    <div className={`admin-shell ${sidebarOpen ? 'is-sidebar-open' : ''}`}>
      <aside className="admin-sidebar">
        <div className="admin-sidebar__brand">
          <div className="admin-sidebar__logo">QF</div>
          <div>
            <div className="admin-sidebar__title">Query Forge Console</div>
            <div className="admin-sidebar__subtitle">RAG observability backoffice</div>
          </div>
        </div>
        <div className="admin-sidebar__badge">Snapshot-safe experiment ops</div>
        <nav className="admin-nav" aria-label="Admin navigation">
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
              <small>Operator query UI</small>
            </span>
          </button>
        </div>
      </aside>
      <section className="admin-main">
        <header className="admin-topbar">
          <button type="button" className="admin-topbar__menu" onClick={() => setSidebarOpen((prev) => !prev)}>Menu</button>
          <div className="admin-topbar__meta">
            <div className="admin-topbar__title">{meta.title}</div>
            <div className="admin-topbar__subtitle">{meta.subtitle}</div>
          </div>
          <div className="admin-topbar__actions">
            <button type="button" className="button button--primary" onClick={() => navigate(ADMIN_PAGE_META.rag.path)}>Run RAG Eval</button>
          </div>
        </header>
        <main className="admin-content">
          <section className="page-header">
            <div className="page-header__eyebrow">Admin Console</div>
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
