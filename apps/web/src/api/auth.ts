import { request } from './client'
import { fixtureLogin, fixtureMe, USE_FIXTURES } from './fixtures'
import type { User } from './types'

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
  if (USE_FIXTURES) return fixtureLogin()
  return request('/auth/login', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function logout(): Promise<void> {
  if (USE_FIXTURES) return Promise.resolve()
  return request('/auth/logout', { method: 'POST' })
}

export function getMe(): Promise<User> {
  if (USE_FIXTURES) return fixtureMe()
  return request<User>('/auth/me')
}
