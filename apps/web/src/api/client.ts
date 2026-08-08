import type { ClientErrorCode, ErrorBody, ErrorCode } from './types'

/** Vercel rewrites 프록시를 타므로 절대 URL을 쓰지 않는다. */
const BASE_URL = '/api'

/**
 * 인증 정보를 요청에 싣는 유일한 지점.
 *
 * 세션 방식(서버 세션 쿠키 vs JWT)은 #17에서 아직 결정되지 않았다. 지금은 쿠키 세션을
 * 가정하고 `credentials: 'include'`만 붙인다. JWT로 뒤집히면 이 함수만 고친다 —
 * 다른 파일에 쿠키/헤더 분기를 흘리지 말 것.
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

function isErrorBody(value: unknown): value is ErrorBody {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as { code?: unknown }).code === 'string'
  )
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
      `${BASE_URL}${path}`,
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
  if (response.status === 204) {
    return undefined as T
  }
  try {
    return (await response.json()) as T
  } catch (cause) {
    throw new ApiError(
      'INVALID_RESPONSE',
      response.status,
      '서버 응답을 해석하지 못했습니다.',
      { cause },
    )
  }
}

/** 값이 없는 파라미터는 쿼리에서 뺀다. */
export function toQuery(
  params: Record<string, string | number | undefined>,
): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined) {
      search.set(key, String(value))
    }
  }
  const query = search.toString()
  return query === '' ? '' : `?${query}`
}
