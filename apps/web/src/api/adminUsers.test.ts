import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearCookies, setCookie } from '@/test/cookies'
import { approve, list, updateStatus } from './adminUsers'

/**
 * 회원 관리 API 어댑터가 **실제로 어떤 method와 경로로 나가는지.**
 *
 * 화면 테스트는 이 모듈을 통째로 mock한다 — 화면이 함수를 부르는지가 관심사라 그게 맞다.
 * 다만 그러면 경로나 method를 틀려도 화면 테스트는 전부 통과한다. **mock 뒤쪽은 여기서
 * 본다** — 계약(§3-2-6)과 코드가 어긋나면 이 파일이 깨진다.
 */
function stubFetch(body: unknown = { id: 1 }) {
  const fetchMock = vi.fn(async () => Response.json(body, { status: 200 }))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function lastCall(fetchMock: ReturnType<typeof stubFetch>): [string, string] {
  const [url, init] = fetchMock.mock.calls.at(-1) as unknown as [
    string,
    RequestInit | undefined,
  ]
  return [url, init?.method ?? 'GET']
}

/*
 * 이 파일은 method와 경로를 본다. **쓰기 요청은 CSRF 토큰을 요구하므로**(계약 §3-2-3)
 * 브라우저가 이미 토큰을 들고 있는 상태를 만들어 둔다 — 발급 흐름 자체는
 * `csrf.test.ts`가 따로 본다.
 */
beforeEach(() => {
  clearCookies()
  setCookie('XSRF-TOKEN', 'test-token')
})

afterEach(() => {
  clearCookies()
  vi.unstubAllGlobals()
})

describe('회원 관리 API method와 경로', () => {
  it('목록은 GET /api/v1/admin/users이고 조건을 쿼리로 붙인다', async () => {
    const fetchMock = stubFetch({ content: [], page: {} })

    await list({ page: 1, size: 20, q: '홍길동', status: 'PENDING' })

    const [url, method] = lastCall(fetchMock)
    expect(method).toBe('GET')
    expect(url.startsWith('/api/v1/admin/users?')).toBe(true)
    // 검색어는 인코딩되어 실린다.
    for (const part of ['page=1', 'size=20', 'status=PENDING']) {
      expect(url).toContain(part)
    }
    expect(url).toContain(`q=${encodeURIComponent('홍길동')}`)
  })

  it('빈 조건은 쿼리에 실리지 않는다', async () => {
    const fetchMock = stubFetch({ content: [], page: {} })

    await list()

    expect(lastCall(fetchMock)[0]).toBe('/api/v1/admin/users')
  })

  it('승인은 POST /api/v1/admin/users/approve이고 userIds를 담는다', async () => {
    const fetchMock = stubFetch({ approved: [1], failed: [] })

    await approve([1, 2])

    expect(lastCall(fetchMock)).toEqual(['/api/v1/admin/users/approve', 'POST'])
    const [, init] = fetchMock.mock.calls.at(-1) as unknown as [
      string,
      RequestInit,
    ]
    expect(JSON.parse(String(init.body))).toEqual({ userIds: [1, 2] })
  })

  it('승인 응답의 approved·failed를 그대로 돌려준다', async () => {
    stubFetch({
      approved: [1],
      failed: [{ userId: 2, reason: 'NOT_APPLIED' }],
    })

    // 건수를 안내하려면 이 값이 화면까지 와야 한다 (2-2 §2-2-2 MUST).
    await expect(approve([1, 2])).resolves.toEqual({
      approved: [1],
      failed: [{ userId: 2, reason: 'NOT_APPLIED' }],
    })
  })

  it('상태 변경은 PATCH /api/v1/admin/users/{id}/status', async () => {
    const fetchMock = stubFetch()

    await updateStatus(7, 'SUSPENDED')

    expect(lastCall(fetchMock)).toEqual([
      '/api/v1/admin/users/7/status',
      'PATCH',
    ])
    const [, init] = fetchMock.mock.calls.at(-1) as unknown as [
      string,
      RequestInit,
    ]
    expect(JSON.parse(String(init.body))).toEqual({ status: 'SUSPENDED' })
  })

  it('절대 URL을 만들지 않는다 — Vercel rewrites 프록시를 타야 한다', async () => {
    const fetchMock = stubFetch({ content: [], page: {} })

    await list()

    expect(lastCall(fetchMock)[0]).not.toMatch(/^https?:\/\//)
  })

  /*
   * 제외 범위 확인 (Post Launch) — 가입 거부·회원 제거·권한 변경은 이번에 만들지 않는다.
   * 계약에는 엔드포인트가 있지만 어댑터에 함수를 두면 화면이 곧 부르게 된다.
   */
  it('거부·제거·권한 변경 함수를 두지 않는다', async () => {
    const module = await import('./adminUsers')

    for (const name of ['reject', 'remove', 'updateRole']) {
      expect(module, `${name}은 Post Launch다`).not.toHaveProperty(name)
    }
  })
})
