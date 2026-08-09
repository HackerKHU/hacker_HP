import { request } from './client'
import { fixtureLogin, fixtureMe } from './fixtures'
import type { User } from './types'

/*
 * 픽스처 분기는 `import.meta.env.VITE_USE_FIXTURES === 'true'`를 함수 안에서 그대로 쓴다.
 * 상수로 빼거나 다른 모듈의 export를 참조하지 않는다.
 *
 * Vite가 빌드 시 이 표현식을 문자열 리터럴로 치환하므로 꺼져 있으면 `if (false)`가 되고,
 * 분기와 그 안의 픽스처 참조가 통째로 죽어 더미 데이터가 번들에서 빠진다.
 * 모듈 경계를 넘는 상수를 참조하면 번들러가 접기를 확신하지 못해 더미 데이터가
 * 프로덕션 번들에 그대로 실린다 — 실제로 그랬다.
 */

export interface SignupRequest {
  email: string
  studentNo: string
  name: string
  password: string
}

export interface LoginRequest {
  email: string
  password: string
}

export function signup(body: SignupRequest): Promise<void> {
  return request('/auth/signup', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

/**
 * 로그인은 세션만 만들고 본문을 돌려주지 않는다 (spec/3-2-DESIGN-CONTRACT.md §3-2-3).
 * 신원은 `getMe()`로만 조회한다 — 새로고침했을 때와 같은 경로를 쓴다.
 */
export function login(body: LoginRequest): Promise<void> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true') return fixtureLogin()
  return request('/auth/login', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function logout(): Promise<void> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true') return Promise.resolve()
  return request('/auth/logout', { method: 'POST' })
}

export function getMe(): Promise<User> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true') return fixtureMe()
  return request<User>('/auth/me')
}
