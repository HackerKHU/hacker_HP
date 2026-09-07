import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearCookies, setCookie } from '@/test/cookies'
import { list, NOTE_SORTS, setNoteLike } from './notes'

/**
 * 자료 API 래퍼의 실제 경로. 화면 테스트는 이 모듈을 mock하므로
 * `sort=views`가 쿼리스트링에 실리는지는 이 계층에서 확인한다.
 */

const fetchMock = vi.fn<typeof fetch>()

beforeEach(() => {
  vi.stubEnv('VITE_USE_FIXTURES', 'false')
  fetchMock.mockReset()
  fetchMock.mockResolvedValue(
    Response.json({
      content: [],
      page: { size: 20, number: 0, totalElements: 0, totalPages: 0 },
    }),
  )
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  clearCookies()
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

describe('자료 API 경로', () => {
  it('조회수순을 GET /notes의 sort=views로 그대로 전달한다', async () => {
    await list({ category: 'SUBJECT', sort: 'views', page: 2, size: 20 })

    const call = fetchMock.mock.calls[0]
    if (!call) throw new Error('자료 목록 요청이 없다')
    const [url, init] = call
    expect(url).toBe('/api/v1/notes?category=SUBJECT&sort=views&page=2&size=20')
    expect(init?.method ?? 'GET').toBe('GET')
  })

  it('정렬 계약에 views가 포함된다', () => {
    expect(NOTE_SORTS).toEqual(['latest', 'title', 'views'])
  })
})

// 화면 mock을 통과해도 실제 요청의 POST/DELETE 방향이 뒤집히면 여기서 실패한다.
it.each([
  [true, 'POST'],
  [false, 'DELETE'],
] as const)(
  '좋아요 %s는 %s /notes/{id}/like로 보낸다',
  async (liked, method) => {
    setCookie('XSRF-TOKEN', 'test-csrf')
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))
    await setNoteLike(301, liked)
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/notes/301/like',
      expect.objectContaining({ method }),
    )
  },
)

it.each([true, false, undefined])(
  'liked=%s는 true일 때만 목록 쿼리에 담긴다',
  async (liked) => {
    const requestMock = vi.fn<typeof fetch>().mockResolvedValue(
      Response.json({
        content: [],
        page: { number: 2, size: 10, totalElements: 0, totalPages: 0 },
      }),
    )
    vi.stubGlobal('fetch', requestMock)
    vi.stubEnv('VITE_USE_FIXTURES', 'false')
    await list({ page: 2, size: 10, liked })
    const url = new URL(
      String(requestMock.mock.calls[0][0]),
      'https://test.local',
    )
    expect(url.pathname).toBe('/api/v1/notes')
    expect(url.searchParams.get('liked')).toBe(liked ? 'true' : null)
    expect(url.searchParams.get('page')).toBe('2')
    expect(url.searchParams.get('size')).toBe('10')
  },
)
