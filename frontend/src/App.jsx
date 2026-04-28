import { Fragment, startTransition, useDeferredValue, useEffect, useMemo, useState } from 'react'
import { ChatPage } from './pages/ChatPage.jsx'
import { GatingPage } from './pages/GatingPage.jsx'
import { PipelinePage } from './pages/PipelinePage.jsx'
import { RagPage } from './pages/RagPage.jsx'
import { SyntheticPage } from './pages/SyntheticPage.jsx'

export const ADMIN_PAGE_META = {
  pipeline: {
    title: '문서 파이프라인 관리',
    subtitle: '수집부터 청킹, 용어 추출까지 문서 파이프라인 상태를 운영 관점으로 모니터링합니다.',
    path: '/admin/pipeline',
  },
  synthetic: {
    title: '합성 질의 생성 관리',
    subtitle: 'A/B/C/D/E 방식의 생성 배치 이력과 품질 메타데이터를 추적합니다.',
    path: '/admin/synthetic-queries',
  },
  gating: {
    title: 'Quality Gating 운영',
    subtitle: 'Rule/LLM/Utility/Diversity 단계별 게이트를 프리셋과 파라미터로 제어합니다.',
    path: '/admin/quality-gating',
  },
  rag: {
    title: 'RAG 품질·성능 테스트',
    subtitle: '게이팅 스냅샷, 리라이트, 검색 파라미터를 조합해 실험을 반복 비교합니다.',
    path: '/admin/rag-tests',
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
      {deferredPath.startsWith('/admin') ? <AdminApp path={deferredPath} navigate={navigate} notify={notify} /> : <ChatPage navigate={navigate} notify={notify} />}
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
    { key: 'pipeline', label: 'Pipeline Ops', path: ADMIN_PAGE_META.pipeline.path },
    { key: 'synthetic', label: 'Synthetic Query', path: ADMIN_PAGE_META.synthetic.path },
    { key: 'gating', label: 'Quality Gating', path: ADMIN_PAGE_META.gating.path },
    { key: 'rag', label: 'RAG Eval Lab', path: ADMIN_PAGE_META.rag.path },
  ]

  return (
    <div className={`admin-shell ${sidebarOpen ? 'is-sidebar-open' : ''}`}>
      <aside className="admin-sidebar">
        <div className="admin-sidebar__brand">
          <div className="admin-sidebar__logo">QF</div>
          <div>
            <div className="admin-sidebar__title">Query Forge Console</div>
            <div className="admin-sidebar__subtitle">Research Backoffice</div>
          </div>
        </div>
        <div className="admin-sidebar__badge">Production-like Experiment UI</div>
        <nav className="admin-nav">
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
              {item.label}
            </button>
          ))}
        </nav>
        <div className="admin-sidebar__section">
          <div className="admin-sidebar__section-title">Quick Link</div>
          <button type="button" className="admin-nav__link" onClick={() => navigate('/')}>Chat 화면</button>
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
            <button type="button" className="button button--primary" onClick={() => navigate(ADMIN_PAGE_META.rag.path)}>RAG Eval</button>
          </div>
        </header>
        <main className="admin-content">
          <section className="page-header">
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
