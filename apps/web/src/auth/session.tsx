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
/**
 * 세션에 들어갈 수 있는 사용자. `status`를 리터럴로 고정한다.
 *
 * `User`를 그대로 쓰면 `{ kind: 'active', user: 정지된_사용자 }`가 타입상 만들어진다 —
 * 나쁜 상태를 표현할 수 없게 만든다는 이 유니온의 명분에 구멍이 남는다.
 */
export type ActiveUser = Omit<User, 'status'> & { status: 'ACTIVE' }

/**
 * 승인 대기 사용자. `PENDING`은 신청 전과 신청 후를 모두 포함하므로(3-1 §3-1-2)
 * 화면이 신청 폼과 대기 안내를 가르려면 `appliedAt`이 필요하다 — 상태를 `pending`
 * 하나로 뭉개고 사용자를 버리면 두 화면을 구분할 근거가 사라진다.
 */
export type PendingUser = Omit<User, 'status'> & { status: 'PENDING' }

export type SessionState =
  | { kind: 'loading' }
  | { kind: 'guest' }
  /** `user`가 `null`이면 403 PENDING_APPROVAL로만 알아낸 상태라 신청 여부를 모른다. */
  | { kind: 'pending'; user: PendingUser | null }
  | { kind: 'suspended' }
  | { kind: 'active'; user: ActiveUser }

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
  /**
   * 서버에서 사용자 정보를 다시 읽어 세션을 갱신한다.
   *
   * `pending`이면서 `user`가 `null`인 상태(403으로만 알아낸 경우)를 푸는 유일한 경로다.
   * 그대로 두면 신청 화면이 폼과 대기 안내 중 무엇을 보일지 영영 정하지 못한다.
   * 신청서를 제출한 뒤 화면을 다시 그릴 때도 쓴다.
   *
   * **상태를 알아내지 못하면 거부한다** (spec §3-1-5). 네트워크 오류·5xx처럼 상태를
   * 알려주지 않는 실패에서는 세션을 바꾸지 않고 오류를 그대로 올린다 — 호출부가
   * "다시 시도해 달라"를 보여줄 수 있어야 한다.
   */
  refresh: () => Promise<void>
}

/**
 * 계약에 없는 계정 상태. `UserStatus`에 값이 추가되면 이 인자가 `never`가 아니게 되어
 * **빌드가 깨진다.** 새 상태가 조용히 `active`로 취급되는 일을 막는 것이 목적이다 —
 * 계약이 소리 없이 어긋나서 이미 두 번(SUSPENDED 루프, 403 비대칭) 당했다.
 */
function unknownStatus(status: never): never {
  throw new Error(`알 수 없는 계정 상태: ${String(status)}`)
}

/** 사용자 정보로부터 세션 상태를 정한다. */
function fromUser(me: User | null): SessionState {
  if (!me) return { kind: 'guest' }
  switch (me.status) {
    case 'PENDING':
      // ACTIVE와 같은 이유로 캐스트가 아니라 리터럴로 다시 세운다.
      return { kind: 'pending', user: { ...me, status: 'PENDING' } }
    case 'SUSPENDED':
      // 정지 계정은 세션에 넣지 않는다. 넣으면 homePath → RequireActive → GuestOnly가
      // 서로를 밀며 무한히 돈다. 로그인 화면이 정지 안내를 띄운다 (#37).
      return { kind: 'suspended' }
    case 'ACTIVE':
      // 캐스트가 아니라 새 객체 리터럴이다. `User`는 유니온이 아니라 인터페이스라
      // `status === 'ACTIVE'` 검사로 객체가 좁혀지지 않는다. 리터럴로 다시 세우면
      // 문맥 타입이 그대로 붙어 `as`가 필요 없다.
      return { kind: 'active', user: { ...me, status: 'ACTIVE' } }
    default:
      return unknownStatus(me.status)
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
      // 이 코드는 "승인 대기"만 알려준다. 신청 여부는 알 수 없다.
      return { kind: 'pending', user: null }
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
    setState((prev) => {
      const next = fromApiError(error)
      if (!next) return prev
      // 403 PENDING_APPROVAL은 신청 여부를 알려주지 않는다. 이미 알고 있으면 지우지 않는다 —
      // 덮어쓰면 신청서를 낸 사람에게 폼이 다시 뜬다.
      if (next.kind === 'pending' && prev.kind === 'pending') return prev
      return next
    })
  }, [])

  const refresh = useCallback(
    () =>
      getMe()
        .then((me) => setState(fromUser(me)))
        .catch((error: unknown) => {
          /*
           * **상태를 알려주는 실패만 세션을 바꾼다** (spec §3-1-5 MUST).
           *
           * "서버에 못 닿았다"에서 "로그아웃됐다"를 추론하지 않는다. 네트워크가 잠깐
           * 끊긴 것만으로 세션을 버리면, 승인을 기다리며 "다시 확인"을 누르던 지원자가
           * 로그인 화면으로 튕기고 자기가 무엇을 잘못했는지 알 수 없다.
           *
           * 알 수 없는 실패는 **호출부로 올린다.** 세션은 그대로 두고 화면이 "다시
           * 시도해 달라"를 보여준다 — 여기서 삼키면 화면은 아무 일도 없었다고 믿는다.
           */
          const next = fromApiError(error)
          if (!next) throw error
          setState(next)
        }),
    [],
  )

  useEffect(() => {
    let alive = true
    getMe()
      .then((me) => {
        if (alive) setState(fromUser(me))
      })
      .catch((error: unknown) => {
        /*
         * **최초 확인만 예외다** (spec §3-1-5). 여기서는 버릴 세션이 없고, 어느 쪽으로도
         * 정해지지 않으면 화면이 영영 `loading`에 갇힌다. 알 수 없는 실패면 비로그인으로
         * 두되 이는 "로그아웃됐다"가 아니라 **"아직 로그인으로 확인된 바 없다"**는 뜻이고,
         * 사용자는 로그인 화면에서 다시 시도할 수 있다.
         */
        if (alive) setState(fromApiError(error) ?? { kind: 'guest' })
      })
    return () => {
      alive = false
    }
  }, [])

  const value = useMemo<Session>(
    () => ({ state, setUser, reportApiError, refresh }),
    [state, setUser, reportApiError, refresh],
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
    /*
     * ACTIVE는 role과 무관하게 공지 목록이다. **관리자 전용 홈을 두지 않는다** —
     * spec §2-1-9 화면 목록에 목록형 "공지 관리" 화면이 없고, 관리 기능(새 공지·고정)은
     * 같은 공지 목록 안에 있다. 예전에 `/admin/notices`를 돌려주던 시절이 있었는데,
     * 그 라우트를 지우면서 여기를 안 고쳐 관리자가 로그인하면 wildcard에 걸려 공개
     * 랜딩으로 떨어졌다. **여기서 돌려주는 경로는 실재하는 라우트여야 한다** —
     * `App.test.tsx`의 "로그인 후 도착 경로"가 그것을 지킨다.
     */
    case 'active':
      return '/notices'
    default:
      // guest·suspended·loading. 정지 안내도 로그인 화면이 띄운다.
      return '/login'
  }
}

export function isPending({ state }: Session): boolean {
  return state.kind === 'pending'
}

/**
 * 승인 대기 화면이 신청 폼과 대기 안내 중 무엇을 보일지 가른다 (3-1 §3-1-6).
 *
 * `null`은 "승인 대기인 건 알지만 신청 여부는 모른다" — 403으로만 알아낸 경우다.
 * 화면은 이때 `getMe()` 결과를 기다린다. 폼을 섣불리 띄우면 이미 낸 사람이 다시 쓴다.
 */
export function hasApplied({ state }: Session): boolean | null {
  if (state.kind !== 'pending' || !state.user) return null
  return state.user.appliedAt !== null
}
