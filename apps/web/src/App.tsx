import { Navigate, Route, Routes } from 'react-router-dom'
import { AppLayout } from './components/layout/AppLayout'
import { LandingPage } from './features/landing/LandingPage'
import { PrivacyPage } from './features/legal/PrivacyPage'
import { NoticeDetailPage } from './features/notices/NoticeDetailPage'
import { NoticeListPage } from './features/notices/NoticeListPage'
import { GuestOnly, PendingOnly, RequireActive } from './routes/guards'

/** 화면은 아직 없다. 이름만 렌더한다 — 각 화면은 자기 이슈에서 만든다. */
function Placeholder({ title }: { title: string }) {
  return <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
}

function App() {
  return (
    <Routes>
      {/*
        공개 랜딩. **가드를 붙이지 않는다** — 어느 세션 상태에서도 열려야 한다
        (spec 5-TESTING T-21~T-25). PendingOnly나 RequireActive 아래로 옮기지 말 것.
      */}
      <Route path="/" element={<LandingPage />} />
      {/* 개인정보처리방침도 공개다. 랜딩과 같은 취급이라 가드를 붙이지 않는다. */}
      <Route path="/privacy" element={<PrivacyPage />} />

      {/*
        비로그인 진입점은 /login 하나다. 가입도 같은 구글 버튼으로 하므로
        별도 /signup은 없다 (2-1 §2-1-8, 3-3 결정 13).
      */}
      <Route element={<GuestOnly />}>
        <Route path="/login" element={<Placeholder title="로그인" />} />
      </Route>

      {/* 여기부터 AppLayout(헤더 + 본문)을 쓴다. /login에는 붙이지 않는다. */}
      <Route element={<PendingOnly />}>
        <Route element={<AppLayout />}>
          <Route path="/pending" element={<Placeholder title="승인 대기" />} />
        </Route>
      </Route>

      {/* 부원 화면 — ACTIVE(USER·ADMIN) */}
      <Route element={<RequireActive />}>
        <Route element={<AppLayout />}>
          <Route path="/notices" element={<NoticeListPage />} />
          <Route path="/notices/:id" element={<NoticeDetailPage />} />
        </Route>
      </Route>

      {/* 관리자 화면 — 부원 라우트와 /admin 접두사로 분리한다 */}
      <Route element={<RequireActive requiredRole="ADMIN" />}>
        <Route element={<AppLayout />}>
          <Route
            path="/admin/notices"
            element={<Placeholder title="공지 관리" />}
          />
          <Route
            path="/admin/notices/new"
            element={<Placeholder title="공지 작성" />}
          />
          <Route
            path="/admin/notices/:id/edit"
            element={<Placeholder title="공지 수정" />}
          />
          <Route
            path="/admin/members"
            element={<Placeholder title="회원 관리" />}
          />
        </Route>
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

export default App
