import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearCookies, setCookie } from '@/test/cookies'
import { countCodePoints, create, get, list, remove } from './posts'

/**
 * 게시판 API 래퍼.
 *
 * **화면 테스트는 `@/api/posts`를 통째로 mock하므로 이 계층은 그 뒤에 가려져 있다.**
 * 여기서 지키는 것은 **경로·메서드·본문이 계약대로 나가는가**다 — 어긋나면 픽스처로도
 * 화면 테스트로도 드러나지 않고 서버가 붙는 날 처음 보게 된다.
 */

const fetchMock = vi.fn()

beforeEach(() => {
  vi.stubEnv('VITE_USE_FIXTURES', 'false')
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
  /*
   * 등록은 쓰기라 `request()`가 CSRF 토큰을 싣는다. 쿠키가 없으면 `GET /auth/csrf`부터
   * 나가 이 파일이 보려는 것과 무관한 실패가 난다 — 그 경로는 `client.test.ts`가 본다.
   */
  setCookie('XSRF-TOKEN', 'test-token')
})

afterEach(() => {
  clearCookies()
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

/** `request()`가 쓰는 fetch 응답. */
function jsonResponse(body: unknown): Response {
  return {
    ok: true,
    status: 200,
    text: () => Promise.resolve(JSON.stringify(body)),
  } as unknown as Response
}

const DETAIL = {
  id: 701,
  title: '제목',
  content: '내용',
  author: { id: 1, name: '홍길동' },
  createdAt: '2026-08-01T09:00:00Z',
  updatedAt: '2026-08-01T09:00:00Z',
}

describe('게시판 API 경로', () => {
  it('목록은 GET /posts에 페이지 조건만 싣는다', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse({
        content: [],
        page: { size: 20, number: 0, totalElements: 0, totalPages: 0 },
      }),
    )

    await list({ page: 2, size: 20 })

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/v1/posts?page=2&size=20')
    // **정렬을 보내지 않는다** (spec §2-1-8 MUST) — 서버가 최신순으로 고정한다.
    expect(url).not.toContain('sort')
    expect(init.method ?? 'GET').toBe('GET')
  })

  /* 조건이 없으면 쿼리스트링도 붙지 않는다 — `toQuery`가 빈 값을 뺀다. */
  it('조건이 없으면 쿼리 없이 부른다', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse({
        content: [],
        page: { size: 20, number: 0, totalElements: 0, totalPages: 0 },
      }),
    )

    await list()

    expect(fetchMock.mock.calls[0][0]).toBe('/api/v1/posts')
  })

  it('상세는 GET /posts/{id}다', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(DETAIL))

    await get(701)

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/v1/posts/701')
    expect(init.method ?? 'GET').toBe('GET')
  })

  /*
   * **본문을 다듬지 않고 그대로 싣는다** (계약 §3-2-5 MUST — 서버가 본문을 trim하지
   * 않는다). 래퍼가 중간에서 털면 화면이 원문을 보내도 소용이 없다.
   */
  it('등록은 POST /posts에 제목·본문을 원문 그대로 싣는다', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(DETAIL))

    await create({ title: '  제목  ', content: '\n  들여쓴 줄\n' })

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/v1/posts')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body)).toEqual({
      title: '  제목  ',
      content: '\n  들여쓴 줄\n',
    })
  })

  /* **작성자를 본문에 담지 않는다** (계약 §3-2-5 MUST) — 인증 주체로만 정해진다. */
  it('등록 본문에 작성자가 없다', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(DETAIL))

    await create({ title: '제목', content: '내용' })

    const body = JSON.parse(fetchMock.mock.calls[0][1].body)
    expect(Object.keys(body)).toEqual(['title', 'content'])
  })

  it('삭제는 CSRF를 실은 DELETE /posts/{id}다', async () => {
    fetchMock.mockResolvedValueOnce({
      ok: true,
      status: 204,
      text: () => Promise.resolve(''),
    } as unknown as Response)

    await remove(701)

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/v1/posts/701')
    expect(init.method).toBe('DELETE')
    expect(init.headers.get('X-XSRF-TOKEN')).toBe('test-token')
  })

  /*
   * **수정 함수는 없다.** 삭제는 관리자·작성자에게 열렸지만 수정은 여전히 범위 밖이다.
   */
  it('수정 함수를 내보내지 않는다', async () => {
    const posts = await import('./posts')

    expect(posts).not.toHaveProperty('update')
    expect(posts).toHaveProperty('remove')
  })
})

describe('코드 포인트 계수', () => {
  /*
   * 서버가 `codePointCount`로 잰다(`CodePointSizeValidator`). `String.length`는 UTF-16
   * 단위라 이모지 하나가 2로 잡혀 **서버가 받아줄 글을 화면이 먼저 막는다.**
   */
  it.each([
    ['가나다', 3],
    ['🎉', 1],
    ['🎉🎉🎉', 3],
    ['a🎉b', 3],
    ['', 0],
    // 공백 2 + '앞뒤' 2 + 공백 1 + '공백' 2 + 공백 2 = 9. 앞뒤 공백도 센다.
    ['  앞뒤 공백  ', 9],
  ])('%s는 %d자다', (text, expected) => {
    expect(countCodePoints(text)).toBe(expected)
  })

  /* UTF-16으로 세면 다른 값이 나온다 — 그 차이가 이 함수의 존재 이유다. */
  it('String.length와 다르다', () => {
    expect('🎉🎉🎉'.length).toBe(6)
    expect(countCodePoints('🎉🎉🎉')).toBe(3)
  })
})
