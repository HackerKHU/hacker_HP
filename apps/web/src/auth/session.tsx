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

/**
 * 세션 상태. 불리언 여러 개가 아니라 판별 유니온으로 둔다.
 *
 * `user`·`suspended`·`pendingApproval`을 따로 들고 있으면 "ACTIVE user인데 pendingApproval"
 * 같은 모순 조합이 만들어질 수 있고, 실제로 그 뿌리에서 무한 리다이렉트와 상태 불일치가
 * 연달아 났다. 갱신 지점을 늘어놓고 "셋을 같이 세우자"로 막는 대신,
 * **나쁜 상태를 타입 차원에서 표현할 수 없게** 만든다. `user`는 `active`일 때만 존재한다.
 */
export type SessionState =
  | { kind: 'loading' }
  | { kind: 'guest' }
  | { kind: 'pending' }
  | { kind: 'suspended' }
  | { kind: 'active'; user: User }

interface Session {
  /** `loading` 동안 가드는 판단을 미룬다 — 새로고침마다 /login으로 튀지 않게. */
  state: SessionState
  /** 로그인 직후 등 사용자 정보를 세션에 반영한다. */
  setUser: (user: User | null) => void
  /**
   * 화면의 catch에서 호출한다. 서버가 알려준 상태로 세션을 정리한다.
   * 403은 PENDING_APPROVAL·SUSPENDED·FORBIDDEN이 모두 쓰므로 status가 아니라 code로 가른다.
   */
  reportApiError: (error: unknown) => void
}

/** 사용자 정보로부터 세션 상태를 정한다. */
function fromUser(me: User | null): SessionState {
  if (!me) return { kind: 'guest' }
  switch (me.status) {
    case 'PENDING':
      return { kind: 'pending' }
    case 'SUSPENDED':
      // 정지 계정은 세션에 넣지 않는다. 넣으면 homePath → RequireActive → GuestOnly가
      // 서로를 밀며 무한히 돈다. 로그인 화면이 정지 안내를 띄운다 (#37).
      return { kind: 'suspended' }
    default:
      return { kind: 'active', user: me }
  }
}

/**
 * API 오류로부터 세션 상태를 정한다. 해당 없으면 `null` — 세션을 건드리지 않는다.
 *
 * SUSPENDED를 PENDING_APPROVAL과 대칭으로 다루는 것이 핵심이다. 세션 도중 관리자가
 * 정지시키면(#31) 이후 모든 보호 API가 403 SUSPENDED로 실패하는데, 이걸 무시하면
 * ACTIVE 세션이 그대로 남아 화면은 열려 있고 요청만 전부 실패한다.
 */
function fromApiError(error: unknown): SessionState | null {
  if (!(error instanceof ApiError)) return null
  switch (error.code) {
    case 'PENDING_APPROVAL':
      return { kind: 'pending' }
    case 'SUSPENDED':
      return { kind: 'suspended' }
    case 'UNAUTHENTICATED':
      return { kind: 'guest' }
    default:
      return null
  }
}

const SessionContext = createContext<Session | null>(null)

export function SessionProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<SessionState>({ kind: 'loading' })

  const setUser = useCallback((me: User | null) => setState(fromUser(me)), [])

  const reportApiError = useCallback((error: unknown) => {
    const next = fromApiError(error)
    if (next) setState(next)
  }, [])

  useEffect(() => {
    let alive = true
    getMe()
      .then((me) => {
        if (alive) setState(fromUser(me))
      })
      .catch((error: unknown) => {
        // 실패는 곧 비로그인이다. 코드가 상태를 알려주면 그쪽을 쓴다.
        if (alive) setState(fromApiError(error) ?? { kind: 'guest' })
      })
    return () => {
      alive = false
    }
  }, [])

  const value = useMemo<Session>(
    () => ({ state, setUser, reportApiError }),
    [state, setUser, reportApiError],
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
export function homePath({ state }: Session): string {
  switch (state.kind) {
    case 'pending':
      return '/pending'
    case 'active':
      return state.user.role === 'ADMIN' ? '/admin/notices' : '/notices'
    default:
      // guest·suspended·loading. 정지 안내도 로그인 화면이 띄운다.
      return '/login'
  }
}

export function isPending({ state }: Session): boolean {
  return state.kind === 'pending'
}
