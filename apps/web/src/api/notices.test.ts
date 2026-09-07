import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearCookies, setCookie } from '@/test/cookies'
import {
  create,
  get,
  list,
  remove,
  setNoticeLike,
  togglePin,
  update,
} from './notices'

/**
 * 공지 API 어댑터가 **실제로 어떤 method와 경로로 나가는지.**
 *
 * 화면 테스트는 이 모듈을 통째로 mock한다 — 화면이 함수를 부르는지가 관심사라 그게 맞다.
 * 다만 그러면 `POST`를 `PATCH`로 바꾸거나 경로를 틀려도 화면 테스트는 전부 통과한다.
 * **mock 뒤쪽은 여기서 본다** — 계약(spec §3-2-5)과 코드가 어긋나면 이 파일이 깨진다.
 */
function stubFetch(body: unknown = { id: 1 }) {
  const fetchMock = vi.fn(async () => Response.json(body, { status: 200 }))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

/** 마지막 요청의 (경로, method). method를 안 주면 fetch 기본값인 GET이다. */
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

describe('공지 API method와 경로', () => {
  it('목록은 GET /api/v1/notices이고 페이지 파라미터를 쿼리로 붙인다', async () => {
    const fetchMock = stubFetch({ content: [], page: {} })

    await list({ page: 2, size: 10 })

    expect(lastCall(fetchMock)).toEqual([
      '/api/v1/notices?page=2&size=10',
      'GET',
    ])
  })

  it('상세는 GET /api/v1/notices/{id}', async () => {
    const fetchMock = stubFetch()

    await get(7)

    expect(lastCall(fetchMock)).toEqual(['/api/v1/notices/7', 'GET'])
  })

  it('등록은 POST /api/v1/notices이고 본문을 JSON으로 보낸다', async () => {
    const fetchMock = stubFetch()

    await create({ title: '제목', content: '본문' })

    expect(lastCall(fetchMock)).toEqual(['/api/v1/notices', 'POST'])
    const [, init] = fetchMock.mock.calls.at(-1) as unknown as [
      string,
      RequestInit,
    ]
    expect(JSON.parse(String(init.body))).toEqual({
      title: '제목',
      content: '본문',
    })
  })

  // PATCH다. PUT으로 바꾸면 준 필드만 갱신한다는 계약이 깨진다.
  it('수정은 PATCH /api/v1/notices/{id}', async () => {
    const fetchMock = stubFetch()

    await update(7, { title: '고친 제목' })

    expect(lastCall(fetchMock)).toEqual(['/api/v1/notices/7', 'PATCH'])
  })

  it('삭제는 DELETE /api/v1/notices/{id}', async () => {
    const fetchMock = stubFetch()

    await remove(7)

    expect(lastCall(fetchMock)).toEqual(['/api/v1/notices/7', 'DELETE'])
  })

  // 고정은 수정과 다른 엔드포인트다. /notices/{id}로 보내면 본문을 덮어쓴다.
  it('고정 토글은 PATCH /api/v1/notices/{id}/pin', async () => {
    const fetchMock = stubFetch()

    await togglePin(7)

    expect(lastCall(fetchMock)).toEqual(['/api/v1/notices/7/pin', 'PATCH'])
  })

  /*
   * **좋아요는 토글이 아니다** (계약 §3-2-5 MUST). 하나의 엔드포인트에 method 둘이라
   * 방향이 뒤집히면 재시도가 방금 누른 것을 조용히 뗀다 — 여기서 잡는다.
   */
  it('좋아요는 POST /api/v1/notices/{id}/like', async () => {
    const fetchMock = stubFetch()

    await setNoticeLike(7, true)

    expect(lastCall(fetchMock)).toEqual(['/api/v1/notices/7/like', 'POST'])
  })

  it('좋아요 취소는 DELETE /api/v1/notices/{id}/like', async () => {
    const fetchMock = stubFetch()

    await setNoticeLike(7, false)

    expect(lastCall(fetchMock)).toEqual(['/api/v1/notices/7/like', 'DELETE'])
  })

  it('절대 URL을 만들지 않는다 — Vercel rewrites 프록시를 타야 한다', async () => {
    const fetchMock = stubFetch()

    await create({ title: '제목', content: '본문' })

    expect(lastCall(fetchMock)[0]).not.toMatch(/^https?:\/\//)
  })
})
