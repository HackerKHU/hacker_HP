/**
 * 백엔드가 없는 동안 화면을 만들기 위한 더미 응답 계층.
 *
 * **서버가 붙으면 이 파일을 통째로 지우고 api 함수의 `USE_FIXTURES` 분기도 함께 제거한다.**
 * 임시 파일이므로 여기에 화면 로직을 두지 않는다 — 응답만 만든다.
 *
 * 반환 타입은 반드시 `src/api/types.ts`의 실제 계약 타입으로 선언한다.
 * 타입이 계약을 강제하는 것이 이 파일의 존재 이유다.
 */
import { ApiError } from './client'
import type { User } from './types'

export const USE_FIXTURES = import.meta.env.VITE_USE_FIXTURES === 'true'

/**
 * 어떤 사용자로 볼지 / 어떤 실패를 볼지 고르는 스위치. `.env.local`에서 바꾼다.
 *
 * 성공 응답만 나오면 오류 화면(#37)과 PENDING 리다이렉트(#38)를 만들 수 없으므로
 * 실패 시나리오를 같은 스위치에 둔다. 새 실패 경로가 필요하면 여기에 값을 추가한다.
 *
 * - `user`      ACTIVE / USER
 * - `admin`     ACTIVE / ADMIN
 * - `pending`   PENDING — 대기중 안내 화면만 접근 가능
 * - `guest`     세션 없음. getMe가 401 UNAUTHENTICATED, login이 401로 실패
 * - `suspended` 로그인 자체가 403 SUSPENDED로 차단 (spec 3-1-2)
 * - `blocked`   세션은 있으나 서버가 403 PENDING_APPROVAL로 막는 상태 (spec 3-1-6)
 */
type Scenario = 'user' | 'admin' | 'pending' | 'guest' | 'suspended' | 'blocked'

const SCENARIO = (import.meta.env.VITE_FIXTURE_SCENARIO ?? 'user') as Scenario

const BASE = {
  id: 1,
  email: 'member@khu.ac.kr',
  studentNo: '2021123456',
  name: '홍길동',
  createdAt: '2026-03-02T09:00:00Z',
} as const

const USERS: Record<'user' | 'admin' | 'pending' | 'blocked', User> = {
  user: {
    ...BASE,
    role: 'USER',
    status: 'ACTIVE',
    approvedAt: '2026-03-03T09:00:00Z',
  },
  admin: {
    ...BASE,
    id: 2,
    email: 'admin@khu.ac.kr',
    name: '김관리',
    role: 'ADMIN',
    status: 'ACTIVE',
    approvedAt: '2026-03-03T09:00:00Z',
  },
  pending: {
    ...BASE,
    id: 3,
    role: 'USER',
    status: 'PENDING',
    approvedAt: null,
  },
  blocked: {
    ...BASE,
    id: 4,
    role: 'USER',
    status: 'PENDING',
    approvedAt: null,
  },
}

export function fixtureMe(): Promise<User> {
  if (SCENARIO === 'guest' || SCENARIO === 'suspended') {
    return Promise.reject(
      new ApiError('UNAUTHENTICATED', 401, '로그인이 필요합니다.'),
    )
  }
  if (SCENARIO === 'blocked') {
    return Promise.reject(
      new ApiError('PENDING_APPROVAL', 403, '가입 승인 대기 중입니다.'),
    )
  }
  return Promise.resolve(USERS[SCENARIO])
}

/** 로그인은 본문을 반환하지 않는다 (계약 §3-2-3). 픽스처도 사용자를 지어내지 않는다. */
export function fixtureLogin(): Promise<void> {
  if (SCENARIO === 'suspended') {
    return Promise.reject(
      new ApiError('SUSPENDED', 403, '이용이 정지된 계정입니다.'),
    )
  }
  if (SCENARIO === 'guest') {
    return Promise.reject(
      new ApiError(
        'UNAUTHENTICATED',
        401,
        '이메일 또는 비밀번호가 올바르지 않습니다.',
      ),
    )
  }
  return Promise.resolve()
}
