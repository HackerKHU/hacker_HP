import { Navigate, Route, Routes } from 'react-router-dom'
import { homePath, useSession } from './auth/session'
import { GuestOnly, PendingOnly, RequireActive } from './routes/guards'

/** 화면은 아직 없다. 이름만 렌더한다 — 스타일은 #39에서 확정한다. */
function Placeholder({ title }: { title: string }) {
  return <h1>{title}</h1>
}

/** 진입점. 로그인 상태에 따라 각자 홈으로 보낸다. */
function Index() {
  const session = useSession()
  if (session.loading) return <p>불러오는 중</p>
  return <Navigate to={homePath(session)} replace />
}

function App() {
  return (
    <Routes>
      <Route path="/" element={<Index />} />

      <Route element={<GuestOnly />}>
        <Route path="/login" element={<Placeholder title="로그인" />} />
        <Route path="/signup" element={<Placeholder title="가입 신청" />} />
      </Route>

      <Route element={<PendingOnly />}>
        <Route path="/pending" element={<Placeholder title="승인 대기" />} />
      </Route>

      {/* 부원 화면 — ACTIVE(USER·ADMIN) */}
      <Route element={<RequireActive />}>
        <Route path="/notices" element={<Placeholder title="공지 목록" />} />
        <Route
          path="/notices/:id"
          element={<Placeholder title="공지 상세" />}
        />
      </Route>

      {/* 관리자 화면 — 부원 라우트와 /admin 접두사로 분리한다 */}
      <Route element={<RequireActive requiredRole="ADMIN" />}>
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

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

export default App
