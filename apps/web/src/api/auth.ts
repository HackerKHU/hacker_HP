import type { ContentSummary } from './adminUsers'
import { apiPath, request } from './client'
import {
  fixtureApplication,
  fixtureMe,
  fixtureMyContentSummary,
  fixtureWithdraw,
} from './fixtures'
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
  /**
   * 정해진 목록에서 고른 값 (spec §3-2-3 MUST). 서버가 목록에 없는 값을
   * `400 VALIDATION_ERROR`로 거부한다 — 목록은 `GET /departments`가 내려준다
   * (`api/departments.ts`, #166).
   */
  department: string
}

/*
 * **`name`이 없다** (spec §3-2-3, #224). 이름은 신청서가 받는 값이 아니라 구글 계정에
 * 저장된 값이고, 화면은 읽기 전용으로 보여주기만 한다. 서버도 이 필드를 받지 않으므로
 * 여기에 두면 **보낸 값이 반영되는 줄 아는 호출부가 생긴다.**
 *
 * 이메일도 마찬가지다 — 폼에 표시만 하고 제출하지 않는다.
 */

/**
 * 승인 심사에 필요한 정보를 낸다. `PENDING` 전용이며 승인 전까지 다시 내 고칠 수 있다
 * (spec/3-1-DESIGN-ARCHITECTURE.md §3-1-4).
 */
export function submitApplication(body: ApplicationRequest): Promise<void> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true')
    return fixtureApplication(body)
  return request('/auth/application', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function logout(): Promise<void> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true') return Promise.resolve()
  return request('/auth/logout', { method: 'POST' })
}

/**
 * 세션 확인. **비로그인이면 `null`이고 오류가 아니다** (#190).
 *
 * 서버는 세션이 없으면 `204`로 답한다 — 화면은 랜딩을 포함해 최초 렌더마다 이것을 부르므로,
 * 실패로 답하면 비로그인 방문자마다 실패 응답이 하나씩 남고 브라우저가 콘솔에 남기는 그 줄은
 * 앱이 지울 수 없다.
 *
 * `request()`는 본문이 없으면 `undefined`를 돌려준다. 여기서 `null`로 맞춰 준다 —
 * `fromUser`가 이미 `null`을 "비로그인"으로 다루므로 호출부는 그대로다.
 */
export function getMe(): Promise<User | null> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true') return fixtureMe()
  return request<User | undefined>('/auth/me').then((me) => me ?? null)
}

/**
 * 탈퇴하면 무엇이 남는지 (spec §3-2-3 `GET /auth/me/content-summary`, #226).
 *
 * **모양은 관리자용 `GET /admin/users/{id}/content-summary`와 같다** (MUST) — 그래서 타입도
 * 같은 것을 쓴다. 대상이 언제나 요청자 자신이라 이쪽만 `{id}`를 받지 않는다: 받으면 일반
 * 부원이 **남의 콘텐츠 건수를 세어 볼 수 있다.**
 *
 * **네 값이 항상 담긴다.** `0`이 빠지면 화면이 "없음"과 "모름"을 가르지 못하는데, 그 둘을
 * 가르는 것이 탈퇴 확인 창의 존재 이유다.
 *
 * `PENDING`도 부를 수 있다 — 건수는 전부 `0`이지만 확인 창이 상태에 따라 갈리지 않는다.
 */
export function myContentSummary(): Promise<ContentSummary> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true')
    return fixtureMyContentSummary()
  return request<ContentSummary>('/auth/me/content-summary')
}

/**
 * 회원 탈퇴 (spec §3-2-3 `DELETE /auth/me`, 2-2 §2-2-4). **되돌릴 수 없다.**
 *
 * 자료·공지·활동사진·게시글은 남고 작성자 표시가 "탈퇴한 회원"이 된다. 즐겨찾기는 함께
 * 사라진다. 같은 구글 계정으로 다시 가입할 수 있다 — 계정 레코드를 지우므로 붙잡는 행이
 * 남지 않는다.
 *
 * **화면이 처리 순서를 흉내 내지 않는다.** 정지 선행·세션 폐기·쿠키 정리는 전부 서버 몫이다
 * (MUST) — 여기서 로그아웃을 덧붙이면 이미 끝난 세션에 한 번 더 요청을 보내게 된다.
 *
 * **마지막 활성 관리자는 `403 FORBIDDEN`이다** (2-2 §2-2-7 MUST). 계정은 그대로 남으므로
 * 호출부는 세션도 화면도 건드리지 않고 사유만 보여준다.
 */
export function withdraw(): Promise<void> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true') return fixtureWithdraw()
  return request('/auth/me', { method: 'DELETE' })
}
