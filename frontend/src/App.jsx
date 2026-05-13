import { Fragment, startTransition, useDeferredValue, useEffect, useMemo, useState } from 'react'
import { ChatPage } from './pages/ChatPage.jsx'
import { GatingPage } from './pages/GatingPage.jsx'
import { PipelinePage } from './pages/PipelinePage.jsx'
import { RagPage } from './pages/RagPage.jsx'
import { SyntheticPage } from './pages/SyntheticPage.jsx'

export const ADMIN_PAGE_META = {
  pipeline: {
    title: '파이프라인 운영',
    subtitle: '수집, 전처리, 청킹, 용어 추출, 코퍼스 적재 실행을 관리합니다.',
    path: '/admin/pipeline',
    icon: 'PL',
  },
  synthetic: {
    title: '합성 질의 스튜디오',
    subtitle: 'A~G 생성 전략, 프롬프트 버전, 배치 진행률, 질의 목록을 운영합니다.',
    path: '/admin/synthetic-queries',
    icon: 'SQ',
  },
  gating: {
    title: '품질 게이팅',
    subtitle: '규칙, LLM, utility, diversity gate와 재현 가능한 런타임 파라미터를 관리합니다.',
    path: '/admin/quality-gating',
    icon: 'GT',
  },
  rag: {
    title: 'RAG 평가 랩',
    subtitle: '스냅샷 기반 검색, 재작성, 답변 품질/성능 실험을 실행합니다.',
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
    { key: 'pipeline', label: '파이프라인', meta: '코퍼스 실행', path: ADMIN_PAGE_META.pipeline.path, icon: ADMIN_PAGE_META.pipeline.icon },
    { key: 'synthetic', label: '합성 질의', meta: '전략 배치', path: ADMIN_PAGE_META.synthetic.path, icon: ADMIN_PAGE_META.synthetic.icon },
    { key: 'gating', label: '품질 게이팅', meta: '스냅샷 제어', path: ADMIN_PAGE_META.gating.path, icon: ADMIN_PAGE_META.gating.icon },
    { key: 'rag', label: 'RAG 평가', meta: '품질/지연', path: ADMIN_PAGE_META.rag.path, icon: ADMIN_PAGE_META.rag.icon },
  ]

  return (
    <div className={`admin-shell ${sidebarOpen ? 'is-sidebar-open' : ''}`}>
      <aside className="admin-sidebar">
        <div className="admin-sidebar__brand">
          <div className="admin-sidebar__logo">QF</div>
          <div>
            <div className="admin-sidebar__title">Query Forge 콘솔</div>
            <div className="admin-sidebar__subtitle">RAG 운영 백오피스</div>
          </div>
        </div>
        <div className="admin-sidebar__badge">스냅샷 기반 실험 운영</div>
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
          <div className="admin-sidebar__section-title">작업 영역</div>
          <button type="button" className="admin-nav__link" onClick={() => navigate('/')}>
            <span className="admin-nav__icon" aria-hidden="true">CH</span>
            <span className="admin-nav__copy">
              <span>채팅 화면</span>
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
            <button type="button" className="button button--success" onClick={() => navigate(ADMIN_PAGE_META.rag.path)}>RAG 평가 실행</button>
          </div>
        </header>
        <main className="admin-content">
          <section className="page-header">
            <div className="page-header__eyebrow">관리자 콘솔</div>
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
