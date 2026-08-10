import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearCookies, setCookie } from '@/test/cookies'
import { ApiError, request } from './client'

/**
 * CSRF 토큰 전송 (#86, 계약 §3-2-3).
 *
 * **`request()`를 mock하지 않는다.** 그걸 갈아끼우면 헤더가 실제로 실렸는지 알 수 없고,
 * "검증한다고 주장하는 것"을 검증하지 못한다. 전역 `fetch`를 감시해 **나간 요청의 헤더와
 * 경로를 직접 본다.**
 *
 * 쿠키는 매 테스트 앞뒤로 지운다 — jsdom의 `document.cookie`는 파일 안에서 계속 남아,
 * 격리하지 않으면 "쿠키가 없을 때"의 동작이 실행 순서에 따라 검증되기도 하고 건너뛰어지기도
 * 한다.
 */
const CSRF_PATH = '/api/v1/auth/csrf'

type Call = { url: string; method: string; token: string | undefined }

let calls: Call[]

/**
 * `fetch` 감시자. `issue`가 참이면 `/auth/csrf` 응답이 **실제로 쿠키를 심는다** —
 * 브라우저가 `Set-Cookie`로 하는 일을 jsdom에서 흉내내는 유일한 방법이다.
 */
function spyFetch(options: { issue?: boolean; issueFails?: boolean } = {}) {
  const { issue = true, issueFails = false } = options
  const fetchMock = vi.fn(async (url: string, init: RequestInit = {}) => {
    const headers = new Headers(init.headers)
    calls.push({
      url,
      method: (init.method ?? 'GET').toUpperCase(),
      token: headers.get('X-XSRF-TOKEN') ?? undefined,
    })

    if (url === CSRF_PATH) {
      if (issueFails) throw new TypeError('network down')
      /*
       * **한 틱 미룬다.** 동기적으로 쿠키를 심으면, 동시에 출발한 두 번째 요청이 이미
       * 심어진 쿠키를 보고 일찍 빠져나간다 — 발급 공유를 없애도 호출이 한 번으로 보여
       * 그 회귀를 잡지 못한다. 실제 네트워크는 즉시 끝나지 않는다.
       */
      await new Promise((resolve) => setTimeout(resolve, 0))
      if (issue) setCookie('XSRF-TOKEN', 'issued-token')
      return new Response('', { status: 200 })
    }
    return Response.json({ ok: true }, { status: 200 })
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function writes(): Call[] {
  return calls.filter((call) => call.url !== CSRF_PATH)
}

function issueCalls(): Call[] {
  return calls.filter((call) => call.url === CSRF_PATH)
}

beforeEach(() => {
  calls = []
  clearCookies()
})

afterEach(() => {
  clearCookies()
  vi.unstubAllGlobals()
})

describe('상태를 바꾸는 요청', () => {
  it.each([
    ['POST', undefined],
    ['PATCH', JSON.stringify({ a: 1 })],
    ['DELETE', undefined],
  ])('%s에는 쿠키 값을 헤더로 싣는다', async (method, body) => {
    setCookie('XSRF-TOKEN', 'cookie-token')
    spyFetch()

    await request('/notices', { method, body })

    expect(writes()).toEqual([
      { url: '/api/v1/notices', method, token: 'cookie-token' },
    ])
  })

  // fetch는 메서드 대소문자를 가리지 않는다. 판정도 가리면 안 된다.
  it('소문자 method로 불러도 헤더가 실린다', async () => {
    setCookie('XSRF-TOKEN', 'cookie-token')
    spyFetch()

    await request('/notices', { method: 'post' })

    expect(writes()[0].token).toBe('cookie-token')
  })

  /*
   * 반대 방향이 더 위험하다. 대소문자를 가리면 소문자 `get`이 안전 목록에서 빠져
   * **조회에도 토큰이 붙고 발급까지 나간다** — 그 발급 요청이 T-60을 깬다.
   */
  it.each(['get', 'head', 'options'])(
    '소문자 %s는 안전한 메서드로 본다',
    async (method) => {
      setCookie('XSRF-TOKEN', 'cookie-token')
      spyFetch()

      await request('/notices', { method })

      expect(writes()[0].token).toBeUndefined()
      expect(issueCalls()).toEqual([])
    },
  )

  /*
   * 계약이 "POST·PATCH·DELETE 등"이라고 열어 두었으므로 대상을 나열하지 않고 안전한 쪽을
   * 나열했다. 계약에 없는 메서드가 생겨도 자동으로 걸려야 한다.
   */
  it('계약에 나열되지 않은 PUT에도 붙는다', async () => {
    setCookie('XSRF-TOKEN', 'cookie-token')
    spyFetch()

    await request('/notices/1', { method: 'PUT' })

    expect(writes()[0].token).toBe('cookie-token')
  })
})

describe('안전한 메서드', () => {
  it.each(['GET', 'HEAD', 'OPTIONS'])(
    '%s에는 헤더를 싣지 않는다',
    async (method) => {
      setCookie('XSRF-TOKEN', 'cookie-token')
      spyFetch()

      await request('/notices', { method })

      expect(writes()[0].token).toBeUndefined()
    },
  )

  it('method를 주지 않으면 GET으로 보고 헤더를 싣지 않는다', async () => {
    setCookie('XSRF-TOKEN', 'cookie-token')
    spyFetch()

    await request('/notices')

    expect(writes()[0]).toEqual({
      url: '/api/v1/notices',
      method: 'GET',
      token: undefined,
    })
  })

  // 조회만 하는 화면에서 발급 요청이 나가면 T-60이 깨진다.
  it('안전한 메서드는 쿠키가 없어도 발급을 부르지 않는다', async () => {
    spyFetch()

    await request('/auth/me')

    expect(issueCalls()).toEqual([])
  })
})

describe('토큰 발급', () => {
  it('쿠키가 없으면 발급을 먼저 부르고 받은 값을 싣는다', async () => {
    spyFetch()

    await request('/notices', { method: 'POST' })

    // 발급이 쓰기보다 먼저다. 순서가 뒤바뀌면 헤더가 빈 채로 나간다.
    expect(calls.map((call) => call.url)).toEqual([
      CSRF_PATH,
      '/api/v1/notices',
    ])
    expect(issueCalls()[0].method).toBe('GET')
    expect(writes()[0].token).toBe('issued-token')
  })

  it('쿠키가 이미 있으면 발급을 부르지 않는다', async () => {
    setCookie('XSRF-TOKEN', 'cookie-token')
    spyFetch()

    await request('/notices', { method: 'POST' })

    expect(issueCalls()).toEqual([])
    expect(writes()[0].token).toBe('cookie-token')
  })

  it('한 번 발급받으면 다음 쓰기는 다시 부르지 않는다', async () => {
    spyFetch()

    await request('/notices', { method: 'POST' })
    await request('/notices/1', { method: 'DELETE' })

    expect(issueCalls()).toHaveLength(1)
    expect(writes().map((call) => call.token)).toEqual([
      'issued-token',
      'issued-token',
    ])
  })

  /*
   * 쿠키가 없는 상태에서 쓰기 셋이 동시에 출발해도 발급은 하나여야 한다.
   * 진행 중인 요청을 공유하지 않으면 `/auth/csrf`가 세 번 나간다.
   */
  it('동시에 나가는 쓰기 요청이 발급을 공유한다', async () => {
    spyFetch()

    await Promise.all([
      request('/notices', { method: 'POST' }),
      request('/notices/1', { method: 'PATCH' }),
      request('/notices/2', { method: 'DELETE' }),
    ])

    expect(issueCalls()).toHaveLength(1)
    expect(writes()).toHaveLength(3)
    for (const call of writes()) {
      expect(call.token).toBe('issued-token')
    }
  })

  it('디코딩해서 싣는다 — 인코딩된 값을 그대로 보내면 서버가 거부한다', async () => {
    setCookie('XSRF-TOKEN', encodeURIComponent('a+b/c=='))
    spyFetch()

    await request('/notices', { method: 'POST' })

    expect(writes()[0].token).toBe('a+b/c==')
  })

  /*
   * **접두사가 같은 쿠키를 앞에 둔다.** 이름을 `startsWith`로 비교하면 `XSRF-TOKEN-BACKUP`이
   * 먼저 걸려 엉뚱한 값이 실린다 — 정확히 일치하는 이름만 읽어야 한다.
   */
  it('이름이 비슷한 쿠키가 앞에 있어도 XSRF-TOKEN만 읽는다', async () => {
    setCookie('OTHER', 'nope')
    setCookie('XSRF-TOKEN-BACKUP', 'wrong')
    setCookie('XSRF-TOKEN', 'right')
    spyFetch()

    await request('/notices', { method: 'POST' })

    expect(writes()[0].token).toBe('right')
  })
})

describe('발급 실패', () => {
  /*
   * **발급이 실패하면 쓰기 요청을 보내지 않고 그대로 올린다.**
   *
   * 토큰 없이 보내면 서버가 403 FORBIDDEN을 주고 화면은 "권한이 없습니다"를 띄운다 —
   * 원인이 토큰인데 사용자는 권한 문제로 읽는다. 실패가 확정된 요청이기도 하다.
   */
  it('발급이 네트워크 오류면 쓰기 요청이 나가지 않는다', async () => {
    spyFetch({ issueFails: true })

    const error = await request('/notices', { method: 'POST' }).catch(
      (caught: unknown) => caught,
    )

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe('NETWORK_ERROR')
    expect(writes()).toEqual([])
  })

  // 발급 요청은 성공했는데 쿠키가 없다 — 서버가 계약대로 내려주지 않은 것이다.
  it('발급이 성공해도 쿠키가 없으면 쓰기 요청이 나가지 않는다', async () => {
    spyFetch({ issue: false })

    const error = await request('/notices', { method: 'POST' }).catch(
      (caught: unknown) => caught,
    )

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).message).toContain('CSRF 토큰을 받지 못했습니다')
    expect(writes()).toEqual([])
  })

  it('발급이 실패해도 다음 시도에서 다시 부른다', async () => {
    spyFetch({ issueFails: true })
    await request('/notices', { method: 'POST' }).catch(() => null)
    expect(issueCalls()).toHaveLength(1)

    // 진행 중 요청을 붙잡고 있으면 이후 시도가 영영 같은 실패를 재사용한다.
    spyFetch()
    await request('/notices', { method: 'POST' })

    expect(writes()).toHaveLength(1)
    expect(writes()[0].token).toBe('issued-token')
  })
})
