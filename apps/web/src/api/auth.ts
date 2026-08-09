import { apiPath, request } from './client'
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

/**
 * 로그인·가입은 구글 OAuth 리다이렉트다 (3-3 결정 13). `fetch`로 호출할 수 없으므로
 * 이 모듈에 함수가 없다 — 로그인 버튼이 이 경로로 브라우저를 이동시킨다.
 *
 * 자체 비밀번호를 쓰지 않으므로 `POST /auth/signup`·`POST /auth/login`은 계약에서 사라졌다.
 * 실제 연결은 #26에서 한다.
 */
export const GOOGLE_LOGIN_PATH = apiPath('/oauth2/authorization/google')

export interface ApplicationRequest {
  studentNo: string
  name: string
}

/**
 * 승인 심사에 필요한 정보를 낸다. `PENDING` 전용이며 승인 전까지 다시 내 고칠 수 있다
 * (spec/3-1-DESIGN-ARCHITECTURE.md §3-1-4).
 */
export function submitApplication(body: ApplicationRequest): Promise<void> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true') return fixtureLogin()
  return request('/auth/application', {
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
