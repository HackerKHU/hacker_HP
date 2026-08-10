import { Navigate, Route, Routes } from 'react-router-dom'
import { AppLayout } from './components/layout/AppLayout'
import { LoginPage } from './features/auth/LoginPage'
import { LandingPage } from './features/landing/LandingPage'
import { PrivacyPage } from './features/legal/PrivacyPage'
import { MemberListPage } from './features/members/MemberListPage'
import { NoticeDetailPage } from './features/notices/NoticeDetailPage'
import { NoticeFormPage } from './features/notices/NoticeFormPage'
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
        (spec 5-TESTING T-57~T-61). PendingOnly나 RequireActive 아래로 옮기지 말 것.
      */}
      <Route path="/" element={<LandingPage />} />
      {/* 개인정보처리방침도 공개다. 랜딩과 같은 취급이라 가드를 붙이지 않는다. */}
      <Route path="/privacy" element={<PrivacyPage />} />

      {/*
        비로그인 진입점은 /login 하나다. 가입도 같은 구글 버튼으로 하므로
        별도 /signup은 없다 (2-1 §2-1-8, 3-3 결정 13).
      */}
      <Route element={<GuestOnly />}>
        <Route path="/login" element={<LoginPage />} />
      </Route>

      {/*
        저장된 `/signup` 링크로 들어온 사람을 로그인으로 보낸다 (2-1 §2-1-8).
        아래 wildcard에 맡기면 랜딩으로 가는데, 가입하러 온 사람이 길을 다시 찾아야 한다.
      */}
      <Route path="/signup" element={<Navigate to="/login" replace />} />

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
          {/*
            공지 작성·수정은 한 화면이 맡는다. 목록형 "공지 관리" 화면은 두지 않는다 —
            spec §2-1-8 화면 목록에도 없고, 고정 토글은 공지 목록의 관리 모드에 있다.
          */}
          <Route path="/admin/notices/new" element={<NoticeFormPage />} />
          <Route path="/admin/notices/:id/edit" element={<NoticeFormPage />} />
          <Route path="/admin/members" element={<MemberListPage />} />
        </Route>
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

export default App
