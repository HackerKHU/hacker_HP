import { request } from './client'
import { fixtureDepartments } from './fixtures'

/**
 * 학과 고정 목록 (spec [3-2 §3-2-3](../../../../spec/3-2-DESIGN-CONTRACT.md), #166).
 * 인증 없이 열려 있고 응답은 문자열 배열이다 — `Department.ALL`의 순서 그대로다.
 *
 * **화면이 목록을 갖지 않는다.** 예전에는 `features/auth/departments.ts`가 서버의 목록을
 * 복사해 갖고 있었는데(#165), 한쪽만 고치면 그 학과 지원자의 가입이 조용히 막혔다 —
 * 웹에만 있으면 `400`, 서버에만 있으면 고를 자리가 없다.
 */
export function getDepartments(): Promise<string[]> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true') return fixtureDepartments()
  return request<string[]>('/departments')
}
