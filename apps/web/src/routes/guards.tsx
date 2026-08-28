/**
 * 라우트 가드.
 *
 * **가드는 편의장치다. 권한의 근거는 서버다** (spec/3-1-DESIGN-ARCHITECTURE.md §3-1-7).
 * 여기서 화면을 감추는 것만으로 권한을 통제하지 않는다 — 모든 보호 API는 서버가 다시 검증하고,
 * 가드는 그 결과를 사용자가 헤매지 않게 미리 반영해줄 뿐이다. 가드가 뚫려도 서버가 막아야 한다.
 *
 * 권한 매트릭스 원본은 spec §3-1-3이다. 이 파일과 표가 어긋나면 표가 맞다.
 * 분기는 모두 `SessionState.kind`로 한다 — 불리언 조합이 아니라 한 값이라 빠뜨릴 수 없다.
 */
import { Navigate, Outlet, useLocation } from 'react-router-dom'
import type { Role } from '../api/types'
import { homePath, isPending, useSession } from '../auth/session'

/** 첫 getMe가 끝나기 전에는 아무 쪽으로도 보내지 않는다. */
function Loading() {
  return <p>불러오는 중</p>
}

/** 비로그인 전용 — /login. 이미 로그인했으면 각자 홈으로 되돌린다. */
export function GuestOnly() {
  const session = useSession()
  const location = useLocation()
  const { kind } = session.state

  if (kind === 'loading') return <Loading />
  if (kind === 'guest') return <Outlet />
  /*
   * 정지 계정에게는 로그인 화면만 연다. 회원가입 신청은 비로그인 전용이고(spec §3-1-3 매트릭스)
   * 정지 상태의 접근 범위는 "없음"이다(§3-1-2). 서버가 student_no UNIQUE로 재가입을 막지만
   * 화면이 매트릭스와 어긋나선 안 된다.
   * 이미 /login이면 그대로 렌더한다 — 여기서 리다이렉트하면 순환한다. 정지 안내는 #37이 띄운다.
   */
  if (kind === 'suspended') {
    return location.pathname === '/login' ? (
      <Outlet />
    ) : (
      <Navigate to="/login" replace />
    )
  }
  return <Navigate to={homePath(session)} replace />
}

/** PENDING 전용 — 대기중 안내 화면. PENDING이 접근 가능한 유일한 화면이다 (spec §3-1-6). */
export function PendingOnly() {
  const session = useSession()

  if (session.state.kind === 'loading') return <Loading />
  if (isPending(session)) return <Outlet />
  return <Navigate to={homePath(session)} replace />
}

/**
 * 로그인해 이용 중인 세션 전용. `role`을 주면 그 역할까지 요구한다.
 *
 * SUSPENDED용 라우트도 분기도 두지 않는다 (spec §3-1-2 — 접근 가능 범위 "없음").
 * 정지 계정은 세션 유니온의 `suspended`로 남아 `GuestOnly`가 따로 분기하지만, 이 가드
 * 입장에서는 `active`가 아니라는 점만 중요해 로그인 화면으로 보낸다.
 *
 * **`INACTIVE`는 통과한다** (#231, spec §3-1-3 매트릭스의 `USER (INACTIVE)` 열 — 자료 네 행만
 * `ACTIVE`와 다르고 나머지 열여덟은 같다). 그래서 여기에 비활동 분기를 두지 않는다: 이
 * 가드가 지키는 화면 중 비활동 부원에게 닫힌 것이 하나도 없다. **자료는 서버가 `403
 * INACTIVE`로 막고** 화면은 그 사유를 보여준다 (§3-1-5) — 가드로 앞질러 막으면 그 안내가
 * 뜰 자리가 사라지고, 여기 조건을 하나 더 얹는 만큼 매트릭스와 어긋날 자리도 늘어난다.
 */
export function RequireActive({ requiredRole }: { requiredRole?: Role }) {
  const session = useSession()
  const location = useLocation()
  const { state } = session

  if (state.kind === 'loading') return <Loading />
  if (state.kind === 'pending') return <Navigate to="/pending" replace />
  if (state.kind !== 'active') {
    // 어디로 가려 했는지 남겨둔다 — 로그인 후 복귀는 #37에서 쓴다.
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  if (requiredRole && state.user.role !== requiredRole) {
    return <Navigate to={homePath(session)} replace />
  }
  return <Outlet />
}
