import {
  createContext,
  type ReactNode,
  use,
  useCallback,
  useEffect,
  useMemo,
  useState,
} from 'react'
import { getMe } from '../api/auth'
import { ApiError } from '../api/client'
import type { User } from '../api/types'

interface Session {
  user: User | null
  /** 첫 getMe가 끝나기 전. 이 동안 가드는 판단을 미룬다 — 새로고침마다 /login으로 튀지 않게. */
  loading: boolean
  /**
   * 서버가 403 `PENDING_APPROVAL`로 막은 상태. `user.status === 'PENDING'`과 같게 취급한다.
   * 403은 SUSPENDED·FORBIDDEN도 쓰므로 status가 아니라 `ApiError.code`로만 판별한다.
   */
  pendingApproval: boolean
  /**
   * 정지된 계정. `user`는 `null`이므로 가드는 비로그인과 똑같이 다루고 로그인 화면이 그려진다.
   * 그 화면이 정지 안내를 띄운다 (#37) — spec §3-1-2의 "접근 가능 범위: 없음(정지 안내 표시)".
   */
  suspended: boolean
  setUser: (user: User | null) => void
  /** 화면의 catch에서 호출한다. PENDING_APPROVAL이면 가드가 대기중 안내로 되돌린다. */
  reportApiError: (error: unknown) => void
}

const SessionContext = createContext<Session | null>(null)

export function SessionProvider({ children }: { children: ReactNode }) {
  const [user, setUserState] = useState<User | null>(null)
  const [loading, setLoading] = useState(true)
  const [pendingApproval, setPendingApproval] = useState(false)
  const [suspended, setSuspended] = useState(false)

  /**
   * 사용자 정보를 세션에 반영하는 유일한 지점. 로그인 직후든 새로고침이든 여기로 모인다.
   *
   * SUSPENDED는 세션에 넣지 않는다. 넣으면 무한 리다이렉트가 돈다 —
   * `homePath()`가 role만 보고 `/notices`로 보내고, `RequireActive`가 ACTIVE가 아니라며
   * `/login`으로 보내고, `GuestOnly`가 로그인 상태라며 다시 `homePath()`로 보낸다.
   * ACTIVE로 로그인한 뒤 관리자가 정지시키면(#31) 실제로 이 상태가 만들어진다.
   *
   * 세션 없음 + `suspended` 플래그로 수렴시키면 로그인 시점의 403 SUSPENDED와
   * 세션 중간 정지가 같은 종착점(로그인 화면의 정지 안내)에 도착한다.
   */
  const applySession = useCallback((me: User | null) => {
    setSuspended(me?.status === 'SUSPENDED')
    setPendingApproval(me?.status === 'PENDING')
    setUserState(me?.status === 'SUSPENDED' ? null : me)
  }, [])

  const reportApiError = useCallback((error: unknown) => {
    if (error instanceof ApiError && error.code === 'PENDING_APPROVAL') {
      setPendingApproval(true)
    }
  }, [])

  useEffect(() => {
    let alive = true
    getMe()
      .then((me) => {
        if (alive) applySession(me)
      })
      .catch((error: unknown) => {
        // 실패는 곧 비로그인이다. PENDING_APPROVAL만 예외로 세션이 있는 대기 상태다.
        if (alive) reportApiError(error)
      })
      .finally(() => {
        if (alive) setLoading(false)
      })
    return () => {
      alive = false
    }
  }, [applySession, reportApiError])

  const value = useMemo<Session>(
    () => ({
      user,
      loading,
      pendingApproval,
      suspended,
      setUser: applySession,
      reportApiError,
    }),
    [user, loading, pendingApproval, suspended, applySession, reportApiError],
  )

  return <SessionContext value={value}>{children}</SessionContext>
}

export function useSession(): Session {
  const session = use(SessionContext)
  if (!session) {
    throw new Error('useSession은 SessionProvider 안에서만 쓸 수 있다.')
  }
  return session
}

/** 로그인 상태에서의 첫 화면. 로그인·가입 화면에 이미 로그인한 사용자가 오면 여기로 보낸다. */
export function homePath(session: Session): string {
  if (isPending(session)) return '/pending'
  if (!session.user) return '/login'
  return session.user.role === 'ADMIN' ? '/admin/notices' : '/notices'
}

export function isPending({ user, pendingApproval }: Session): boolean {
  return pendingApproval || user?.status === 'PENDING'
}
