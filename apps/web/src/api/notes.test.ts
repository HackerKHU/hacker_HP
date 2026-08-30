import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { list, NOTE_SORTS } from './notes'

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
