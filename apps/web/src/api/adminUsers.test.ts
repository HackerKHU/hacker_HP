import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearCookies, setCookie } from '@/test/cookies'
import {
  approve,
  bulkUpdateStatus,
  contentSummary,
  deactivate,
  list,
  reactivate,
  reject,
  remove,
  updateRole,
  updateStatus,
} from './adminUsers'

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

  it('거부는 POST로 userIds만 보낸다', async () => {
    const fetchMock = stubFetch({ rejected: [], failed: [] })

    await reject([1, 2])

    expect(lastCall(fetchMock)).toEqual(['/api/v1/admin/users/reject', 'POST'])
    const [, init] = fetchMock.mock.calls.at(-1) as unknown as [
      string,
      RequestInit,
    ]
    expect(JSON.parse(String(init.body))).toEqual({ userIds: [1, 2] })
  })

  it('선택 비활성화는 POST /deactivate에 userIds만 보낸다', async () => {
    const fetchMock = stubFetch({ deactivated: [1], failed: [] })

    await deactivate([1, 2])

    expect(lastCall(fetchMock)).toEqual([
      '/api/v1/admin/users/deactivate',
      'POST',
    ])
    const [, init] = fetchMock.mock.calls.at(-1) as unknown as [
      string,
      RequestInit,
    ]
    expect(JSON.parse(String(init.body))).toEqual({ userIds: [1, 2] })
  })

  it('선택 비활성화의 200 혼합 결과를 순서 그대로 돌려준다', async () => {
    stubFetch({
      deactivated: [2],
      failed: [
        { userId: 1, reason: 'NOT_ACTIVE_USER' },
        { userId: 999, reason: 'NOT_FOUND' },
      ],
    })

    await expect(deactivate([1, 2, 999])).resolves.toEqual({
      deactivated: [2],
      failed: [
        { userId: 1, reason: 'NOT_ACTIVE_USER' },
        { userId: 999, reason: 'NOT_FOUND' },
      ],
    })
  })

  it.each([
    ['ACTIVE', [1, 2]],
    ['SUSPENDED', [5, 3]],
  ] as const)(
    '선택 상태 변경은 PATCH /status에 userIds와 %s를 한 번 보낸다',
    async (status, userIds) => {
      const fetchMock = stubFetch({
        targetStatus: status,
        processed: userIds,
        failed: [],
      })

      await bulkUpdateStatus([...userIds], status)

      expect(lastCall(fetchMock)).toEqual([
        '/api/v1/admin/users/status',
        'PATCH',
      ])
      const [, init] = fetchMock.mock.calls.at(-1) as unknown as [
        string,
        RequestInit,
      ]
      expect(JSON.parse(String(init.body))).toEqual({ userIds, status })
      expect(fetchMock).toHaveBeenCalledTimes(1)
    },
  )

  it('선택 상태 변경의 멱등·부분 실패 200 응답을 그대로 돌려준다', async () => {
    stubFetch({
      targetStatus: 'ACTIVE',
      processed: [4, 6],
      failed: [
        { userId: 3, reason: 'NOT_APPLIED' },
        { userId: 999, reason: 'NOT_FOUND' },
      ],
    })

    await expect(bulkUpdateStatus([4, 3, 6, 999], 'ACTIVE')).resolves.toEqual({
      targetStatus: 'ACTIVE',
      processed: [4, 6],
      failed: [
        { userId: 3, reason: 'NOT_APPLIED' },
        { userId: 999, reason: 'NOT_FOUND' },
      ],
    })
  })

  it.each([
    [400, 'VALIDATION_ERROR'],
    [403, 'FORBIDDEN'],
    [500, 'INVALID_RESPONSE'],
  ] as const)(
    '상태 변경의 %i 응답을 성공으로 바꾸지 않는다',
    async (status, code) => {
      const fetchMock = vi.fn(async () =>
        Response.json(
          code === 'INVALID_RESPONSE'
            ? { code: 'INTERNAL_ERROR', message: '세션 반영 실패' }
            : { code, message: '요청을 처리할 수 없습니다.' },
          { status },
        ),
      )
      vi.stubGlobal('fetch', fetchMock)

      await expect(bulkUpdateStatus([1], 'SUSPENDED')).rejects.toMatchObject({
        status,
      })
    },
  )

  /*
   * **복구는 반대로 id를 싣는다** (2-2 §2-2-3). 올라올 사람은 매번 다르므로 조건으로
   * 전원을 올리면 비활성화가 무의미해진다 — 두 경로의 요청 모양이 다른 것이 그 규칙이다.
   */
  it('일괄 복구는 POST /api/v1/admin/users/reactivate에 userIds를 담는다', async () => {
    const fetchMock = stubFetch({ reactivated: [1], failed: [] })

    await reactivate([1, 6])

    expect(lastCall(fetchMock)).toEqual([
      '/api/v1/admin/users/reactivate',
      'POST',
    ])
    const [, init] = fetchMock.mock.calls.at(-1) as unknown as [
      string,
      RequestInit,
    ]
    expect(JSON.parse(String(init.body))).toEqual({ userIds: [1, 6] })
  })

  it('일괄 복구 응답의 reactivated·failed를 그대로 돌려준다', async () => {
    stubFetch({
      reactivated: [1],
      failed: [{ userId: 6, reason: 'NOT_INACTIVE' }],
    })

    // 부분 실패를 안내하려면(T-365) 사유가 화면까지 와야 한다.
    await expect(reactivate([1, 6])).resolves.toEqual({
      reactivated: [1],
      failed: [{ userId: 6, reason: 'NOT_INACTIVE' }],
    })
  })

  it('제거는 DELETE이고 본문을 보내지 않는다', async () => {
    const fetchMock = stubFetch()

    await remove(7)

    expect(lastCall(fetchMock)).toEqual(['/api/v1/admin/users/7', 'DELETE'])
    const [, init] = fetchMock.mock.calls.at(-1) as unknown as [
      string,
      RequestInit,
    ]
    expect(init.body).toBeUndefined()
  })

  it('콘텐츠 건수는 GET으로 읽는다', async () => {
    const fetchMock = stubFetch({ notes: 0, notices: 0, photos: 0 })

    await contentSummary(7)

    expect(lastCall(fetchMock)).toEqual([
      '/api/v1/admin/users/7/content-summary',
      'GET',
    ])
  })

  it('권한 변경은 PATCH로 role만 보낸다', async () => {
    const fetchMock = stubFetch({ id: 7, role: 'ADMIN' })

    await updateRole(7, 'ADMIN')

    expect(lastCall(fetchMock)).toEqual(['/api/v1/admin/users/7/role', 'PATCH'])
    const [, init] = fetchMock.mock.calls.at(-1) as unknown as [
      string,
      RequestInit,
    ]
    expect(JSON.parse(String(init.body))).toEqual({ role: 'ADMIN' })
  })
})
