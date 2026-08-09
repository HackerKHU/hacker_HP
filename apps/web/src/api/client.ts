import type { ClientErrorCode, ErrorBody, ErrorCode } from './types'
import { ERROR_CODES } from './types'

/**
 * Vercel rewrites 프록시를 타므로 절대 URL을 쓰지 않는다.
 *
 * 버전은 경로에 붙인다(3-3 결정 9). 호환을 깨는 변경이 필요해지면 이 값을 고치지 않고
 * `/api/v2`를 새로 연다.
 */
const BASE_URL = '/api/v1'

/**
 * base URL을 붙인 절대 경로를 만든다. **base URL의 원본은 이 파일 하나다.**
 *
 * `request()`가 쓰지 못하는 경로도 이 함수를 거친다 — 구글 로그인처럼 브라우저를
 * 통째로 이동시켜야 하는 경우다. 호출부가 `/api/v1`을 직접 적으면 원본이 둘이 되고,
 * 버전이나 프록시 접두사를 바꿀 때 한쪽만 따라가 로그인만 깨진다.
 */
export function apiPath(path: string): string {
  return `${BASE_URL}${path}`
}

/**
 * 인증 정보를 요청에 싣는 유일한 지점.
 *
 * 신원은 JWT, 인가 상태는 서버 세션이 담당한다(3-3 결정 11). 둘 다 `httpOnly` 쿠키로
 * 오가므로 여기서는 `credentials: 'include'`만 붙인다. 토큰을 JS로 읽지 않는다 —
 * `localStorage` 저장은 금지다.
 *
 * CSRF 토큰 전송은 아직 붙이지 않았다. 서버가 인증을 구현할 때(#26) 이 함수에서만
 * 처리한다. 다른 파일에 쿠키/헤더 분기를 흘리지 말 것.
 */
function withAuth(init: RequestInit): RequestInit {
  return { ...init, credentials: 'include' }
}

export class ApiError extends Error {
  readonly code: ErrorCode | ClientErrorCode
  /** 응답을 받지 못한 경우(네트워크 실패)에는 0. */
  readonly status: number

  constructor(
    code: ErrorCode | ClientErrorCode,
    status: number,
    message: string,
    options?: ErrorOptions,
  ) {
    super(message, options)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

/**
 * 서버 응답은 신뢰 경계다. `code`가 문자열이라는 것만으로 통과시키면 오타나 미문서화 코드가
 * `ErrorCode`로 선언된 자리에 그대로 들어앉는다 — 유니온은 런타임에 아무것도 막지 못한다.
 * 계약(§3-2-7)에 없는 코드는 INVALID_RESPONSE로 격리한다.
 */
function isErrorBody(value: unknown): value is ErrorBody {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const { code, message } = value as { code?: unknown; message?: unknown }
  if (typeof code !== 'string' || typeof message !== 'string') {
    return false
  }
  return ERROR_CODES.some((known) => known === code)
}

/**
 * 401·403을 포함한 모든 실패 응답을 여기 한 곳에서 ApiError로 바꾼다.
 * api 계층은 화면을 이동시키지 않는다 — 라우팅 판단은 호출부(#36) 몫이다.
 * 호출부는 `error.code === 'PENDING_APPROVAL'`로 다른 403(SUSPENDED, FORBIDDEN)과 구분한다.
 */
async function toApiError(response: Response): Promise<ApiError> {
  const body: unknown = await response.json().catch(() => null)
  if (!isErrorBody(body)) {
    return new ApiError(
      'INVALID_RESPONSE',
      response.status,
      '서버 응답을 해석하지 못했습니다.',
    )
  }
  return new ApiError(body.code, response.status, body.message)
}

export async function request<T>(
  path: string,
  init: RequestInit = {},
): Promise<T> {
  let response: Response
  try {
    response = await fetch(
      apiPath(path),
      withAuth({
        ...init,
        headers:
          init.body === undefined
            ? init.headers
            : { 'Content-Type': 'application/json', ...init.headers },
      }),
    )
  } catch (cause) {
    throw new ApiError('NETWORK_ERROR', 0, '서버에 연결하지 못했습니다.', {
      cause,
    })
  }

  if (!response.ok) {
    throw await toApiError(response)
  }
  try {
    // 무본문 응답을 status로 가르지 않는다 — 서버가 logout·DELETE를 204로 줄지
    // 200 + 빈 본문으로 줄지 아직 확정되지 않았다.
    const text = await response.text()
    return text === '' ? (undefined as T) : (JSON.parse(text) as T)
  } catch (cause) {
    throw new ApiError(
      'INVALID_RESPONSE',
      response.status,
      '서버 응답을 해석하지 못했습니다.',
      { cause },
    )
  }
}

/** 값이 없는 파라미터는 쿼리에서 뺀다. 빈 문자열도 뺀다 — 0은 남긴다(page=0이 첫 페이지). */
export function toQuery(
  params: Record<string, string | number | undefined>,
): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') {
      search.set(key, String(value))
    }
  }
  const query = search.toString()
  return query === '' ? '' : `?${query}`
}
