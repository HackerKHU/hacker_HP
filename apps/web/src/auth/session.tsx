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
  setUser: (user: User | null) => void
  /** 화면의 catch에서 호출한다. PENDING_APPROVAL이면 가드가 대기중 안내로 되돌린다. */
  reportApiError: (error: unknown) => void
}

const SessionContext = createContext<Session | null>(null)

export function SessionProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState(true)
  const [pendingApproval, setPendingApproval] = useState(false)

  const reportApiError = useCallback((error: unknown) => {
    if (error instanceof ApiError && error.code === 'PENDING_APPROVAL') {
      setPendingApproval(true)
    }
  }, [])

  useEffect(() => {
    let alive = true
    getMe()
      .then((me) => {
        if (alive) setUser(me)
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
  }, [reportApiError])

  const value = useMemo<Session>(
    () => ({
      user,
      loading,
      pendingApproval,
      setUser: (next) => {
        setUser(next)
        setPendingApproval(next?.status === 'PENDING')
      },
      reportApiError,
    }),
    [user, loading, pendingApproval, reportApiError],
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
