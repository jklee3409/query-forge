import { Fragment, startTransition, useDeferredValue, useEffect, useMemo, useState } from 'react'
import { ChatPage } from './pages/ChatPage.jsx'
import { GatingPage } from './pages/GatingPage.jsx'
import { PipelinePage } from './pages/PipelinePage.jsx'
import { RagPage } from './pages/RagPage.jsx'
import { SyntheticPage } from './pages/SyntheticPage.jsx'

export const ADMIN_PAGE_META = {
  pipeline: {
    title: '문서 파이프라인 관리',
    subtitle: '수집, 정제, 청킹, 용어 추출 파이프라인 실행과 문서 상태를 통합 관리합니다.',
    path: '/admin/pipeline',
  },
  synthetic: {
    title: '합성 질의 생성/조회',
    subtitle: '생성 방식별 배치 실행과 합성 질의/출처 데이터 조회를 관리합니다.',
    path: '/admin/synthetic-queries',
  },
  gating: {
    title: 'Quality Gating 관리',
    subtitle: 'Rule/LLM/Utility/Diversity 단계와 가중치 조합을 기반으로 품질 게이팅을 운영합니다.',
    path: '/admin/quality-gating',
  },
  rag: {
    title: 'RAG 성능/회귀 테스트',
    subtitle: '평가 데이터셋 기반으로 RAG 지표와 rewrite 로그를 점검합니다.',
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
    { key: 'pipeline', label: '문서 파이프라인', path: ADMIN_PAGE_META.pipeline.path },
    { key: 'synthetic', label: '합성 질의 생성/조회', path: ADMIN_PAGE_META.synthetic.path },
    { key: 'gating', label: 'Quality Gating', path: ADMIN_PAGE_META.gating.path },
    { key: 'rag', label: 'RAG 테스트', path: ADMIN_PAGE_META.rag.path },
  ]

  return (
    <div className={`admin-shell ${sidebarOpen ? 'is-sidebar-open' : ''}`}>
      <aside className="admin-sidebar">
        <div className="admin-sidebar__brand">
          <div className="admin-sidebar__logo">QF</div>
          <div>
            <div className="admin-sidebar__title">Query Forge</div>
            <div className="admin-sidebar__subtitle">연구 관리자 콘솔</div>
          </div>
        </div>
        <nav className="admin-nav">
          {navItems.map((item) => (
            <button key={item.key} type="button" className={`admin-nav__link ${item.key === pageKey ? 'is-active' : ''}`} onClick={() => { setSidebarOpen(false); navigate(item.path) }}>
              {item.label}
            </button>
          ))}
        </nav>
        <div className="admin-sidebar__section">
          <div className="admin-sidebar__section-title">바로 이동</div>
          <button type="button" className="admin-nav__link" onClick={() => navigate('/')}>채팅 화면</button>
        </div>
      </aside>
      <section className="admin-main">
        <header className="admin-topbar">
          <button type="button" className="admin-topbar__menu" onClick={() => setSidebarOpen((prev) => !prev)}>메뉴</button>
          <div className="admin-topbar__meta">
            <div className="admin-topbar__title">{meta.title}</div>
            <div className="admin-topbar__subtitle">{meta.subtitle}</div>
          </div>
          <button type="button" className="button button--primary" onClick={() => navigate(ADMIN_PAGE_META.pipeline.path)}>기본 페이지</button>
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
