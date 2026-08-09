/**
 * 라우트 가드.
 *
 * **가드는 편의장치다. 권한의 근거는 서버다** (spec/3-1-DESIGN-ARCHITECTURE.md §3-1-7).
 * 여기서 화면을 감추는 것만으로 권한을 통제하지 않는다 — 모든 보호 API는 서버가 다시 검증하고,
 * 가드는 그 결과를 사용자가 헤매지 않게 미리 반영해줄 뿐이다. 가드가 뚫려도 서버가 막아야 한다.
 *
 * 권한 매트릭스 원본은 spec §3-1-3이다. 이 파일과 표가 어긋나면 표가 맞다.
 */
import { Navigate, Outlet, useLocation } from 'react-router-dom'
import type { Role } from '../api/types'
import { homePath, isPending, useSession } from '../auth/session'

/** 첫 getMe가 끝나기 전에는 아무 쪽으로도 보내지 않는다. */
function Loading() {
  return <p>불러오는 중</p>
}

/** 비로그인 전용 — /login, /signup. 이미 로그인했으면 각자 홈으로 되돌린다. */
export function GuestOnly() {
  const session = useSession()
  if (session.loading) return <Loading />
  if (session.user || isPending(session)) {
    return <Navigate to={homePath(session)} replace />
  }
  return <Outlet />
}

/** PENDING 전용 — 대기중 안내 화면. PENDING이 접근 가능한 유일한 화면이다 (spec §3-1-6). */
export function PendingOnly() {
  const session = useSession()
  if (session.loading) return <Loading />
  if (isPending(session)) return <Outlet />
  if (!session.user) return <Navigate to="/login" replace />
  return <Navigate to={homePath(session)} replace />
}

/**
 * ACTIVE 전용. `role`을 주면 그 역할까지 요구한다.
 *
 * SUSPENDED용 라우트도 분기도 두지 않는다 (spec §3-1-2 — 접근 가능 범위 "없음").
 * `SessionProvider`가 정지된 계정을 세션에서 빼기 때문에 여기서는 비로그인과 구별되지 않고,
 * 로그인 화면이 정지 안내를 띄운다(#37). 여기에 분기를 더하면 리다이렉트가 순환한다.
 */
export function RequireActive({ requiredRole }: { requiredRole?: Role }) {
  const session = useSession()
  const location = useLocation()

  if (session.loading) return <Loading />
  if (isPending(session)) return <Navigate to="/pending" replace />
  if (session.user?.status !== 'ACTIVE') {
    // 어디로 가려 했는지 남겨둔다 — 로그인 후 복귀는 #37에서 쓴다.
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  if (requiredRole && session.user.role !== requiredRole) {
    return <Navigate to={homePath(session)} replace />
  }
  return <Outlet />
}
