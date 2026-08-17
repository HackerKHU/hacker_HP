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
 * 오가므로 `credentials: 'include'`만 붙인다. 그 둘은 JS로 읽지 않는다 —
 * `localStorage` 저장은 금지다.
 *
 * **CSRF 토큰만 예외다.** `XSRF-TOKEN` 쿠키는 `httpOnly`가 아니고(계약 §3-2-3), 클라이언트가
 * 읽어 헤더에 실어야 한다. 그 처리도 이 파일 안에서 끝난다 — 호출부(`auth.ts`·`notices.ts`·
 * `adminUsers.ts`)에 쿠키·헤더 분기를 흘리지 않는다.
 */
function withAuth(init: RequestInit, csrfToken: string | null): RequestInit {
  return {
    ...init,
    headers: buildHeaders(init, csrfToken),
    credentials: 'include',
  }
}

/**
 * 나갈 헤더를 조립하는 **유일한 지점.**
 *
 * `RequestInit.headers`는 plain object만이 아니라 `Headers` 인스턴스와 `[string, string][]`도
 * 받는다. 객체 스프레드(`{ ...init.headers }`)는 뒤의 둘을 펼치지 못하고 **조용히 버린다** —
 * 호출부가 `new Headers(...)`로 넘긴 헤더가 요청에서 사라진다. `Headers`에 한 번 담으면
 * 세 형태를 모두 받고 이름 대소문자까지 정규화된다.
 */
function buildHeaders(init: RequestInit, csrfToken: string | null): Headers {
  const headers = new Headers(init.headers)

  // 본문이 있으면 JSON으로 본다. 호출부가 직접 정했으면 그 값을 존중한다.
  if (init.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  if (csrfToken !== null) {
    /*
     * **덮어쓴다. 덧붙이지 않는다.**
     *
     * 호출부가 같은 헤더를 직접 넣었더라도 유효한 값은 **쿠키에서 읽은 것뿐이다** —
     * 서버는 쿠키와 헤더가 같은지로 판정하므로(§3-2-3) 다른 값이 이기면 반드시 거부된다.
     * `append`를 쓰면 둘이 `a, b`로 합쳐져 그것도 불일치가 된다.
     */
    headers.set(CSRF_HEADER, csrfToken)
  }
  return headers
}

/** 계약 §3-2-3. 쿠키는 `httpOnly`가 아니고 헤더 이름은 여기 고정이다. */
const CSRF_COOKIE = 'XSRF-TOKEN'
const CSRF_HEADER = 'X-XSRF-TOKEN'
const CSRF_PATH = '/auth/csrf'

/**
 * 상태를 바꾸지 않는 메서드. **여기에 없으면 전부 CSRF 대상이다.**
 *
 * 계약이 "`POST`, `PATCH`, `DELETE` 등 상태를 바꾸는 모든 요청"이라고 열어 두었으므로
 * 대상을 나열하지 않고 안전한 쪽을 나열한다 — `PUT`이 생겨도 자동으로 걸린다.
 */
const SAFE_METHODS = ['GET', 'HEAD', 'OPTIONS']

function isSafeMethod(init: RequestInit): boolean {
  // `method: 'post'`처럼 소문자로 불러도 같은 판정이어야 한다. fetch는 대소문자를 가리지 않는다.
  return SAFE_METHODS.includes((init.method ?? 'GET').toUpperCase())
}

/**
 * `XSRF-TOKEN` 쿠키 값. 없으면 `null`.
 *
 * **이 쿠키 하나만 읽는다.** 인증 쿠키는 `httpOnly`라 어차피 안 보이지만, 쿠키를 훑는
 * 코드가 다른 이름까지 건드리지 않게 이름을 정확히 맞춘다.
 */
function readCsrfToken(): string | null {
  for (const part of document.cookie.split(';')) {
    const separator = part.indexOf('=')
    if (separator === -1) continue
    if (part.slice(0, separator).trim() !== CSRF_COOKIE) continue

    // 값에 `=`가 들어갈 수 있다(base64 패딩). 첫 `=`만 구분자로 본다.
    const raw = part.slice(separator + 1).trim()
    if (raw === '') return null
    try {
      // 서버·브라우저가 값을 퍼센트 인코딩할 수 있다. 디코딩을 빠뜨리면 헤더와 쿠키가
      // 달라져 서버가 토큰 불일치로 거부한다.
      return decodeURIComponent(raw)
    } catch {
      // 인코딩이 깨진 값이라면 원문 그대로 보낸다. 여기서 예외를 올리면 쿠키 하나 때문에
      // 요청 경로 전체가 죽는다 — 판정은 서버가 한다.
      return raw
    }
  }
  return null
}

/**
 * 진행 중인 발급 요청. **동시에 나가는 쓰기 요청들이 이 하나를 공유한다.**
 *
 * 쿠키가 없는 상태에서 쓰기 셋이 동시에 출발하면 `GET /auth/csrf`가 세 번 나간다.
 * 늦게 온 응답이 먼저 발급된 토큰을 덮으면 이미 헤더에 실린 값이 무효가 될 수도 있다.
 */
let issuing: Promise<unknown> | null = null

/**
 * 쓰기 요청에 실을 토큰을 얻는다. **쿠키가 이미 있으면 아무것도 하지 않는다.**
 *
 * 앱 시작 시 미리 부르지 않는다. 계약이 "첫 상태 변경 요청 전에"라고 쓰고(§3-2-3 MUST),
 * 무엇보다 **랜딩에서 나가는 요청은 앱 셸의 세션 확인뿐이어야 한다**(5-TESTING T-60).
 * 앱 시작에 발급을 부르면 T-60이 깨진다.
 */
async function ensureCsrfToken(): Promise<string> {
  const existing = readCsrfToken()
  if (existing !== null) return existing

  if (issuing === null) {
    // 이 요청은 GET이라 다시 이 경로로 들어오지 않는다.
    issuing = request(CSRF_PATH).finally(() => {
      issuing = null
    })
  }
  /*
   * **발급이 실패하면 그대로 올린다. 쓰기 요청을 보내지 않는다.**
   *
   * 토큰 없이 보내면 서버가 403 FORBIDDEN을 주고, 화면은 그 메시지를 "권한이 없습니다"로
   * 보여준다 — 원인이 토큰인데 사용자는 권한 문제로 읽는다. 실패가 확정된 요청을 굳이
   * 보내는 것이기도 하다. 여기서 올리면 "서버에 연결하지 못했습니다"처럼 원인에 가까운
   * 메시지가 그대로 화면에 뜬다.
   */
  await issuing

  const issued = readCsrfToken()
  if (issued === null) {
    // 발급 요청은 성공했는데 쿠키가 없다 — 서버가 계약대로 내려주지 않은 것이다.
    throw new ApiError(
      'INVALID_RESPONSE',
      0,
      'CSRF 토큰을 받지 못했습니다. 잠시 후 다시 시도해 주세요.',
    )
  }
  return issued
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
  // 상태를 바꾸는 요청에만 토큰을 붙인다. 실패하면 여기서 끝난다 — 아래로 내려가지 않는다.
  const csrfToken = isSafeMethod(init) ? null : await ensureCsrfToken()

  let response: Response
  try {
    response = await fetch(
      apiPath(path),
      // 헤더 조립은 `buildHeaders()` 한 곳에서 끝난다 — 여기서 미리 합치지 않는다.
      withAuth(init, csrfToken),
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
  params: Record<string, string | number | boolean | undefined>,
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
