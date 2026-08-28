import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getDepartments } from './departments'

/**
 * 학과 목록 (#166).
 *
 * **화면 테스트는 이 모듈을 통째로 mock한다.** 그러면 경로가 틀려도 아무 데서도 안 걸리고,
 * 신청 폼의 `<select>`가 서버 붙는 날 처음으로 비어 뜬다 — 학과는 필수라 그 순간 가입이
 * 통째로 막힌다. 여기서 경로와 인증 방식만 본다.
 */

const fetchMock = vi.fn()

beforeEach(() => {
  vi.stubEnv('VITE_USE_FIXTURES', 'false')
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

describe('학과 목록', () => {
  it('GET /api/v1/departments를 부르고 문자열 배열을 그대로 돌려준다', async () => {
    const departments = ['컴퓨터공학과', '인공지능학과']
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify(departments), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )

    await expect(getDepartments()).resolves.toEqual(departments)

    const [path, init] = fetchMock.mock.calls[0]
    expect(path).toBe('/api/v1/departments')
    // 읽기라 CSRF 발급이 앞서지 않는다 — 요청은 이 하나뿐이다.
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect((init as RequestInit).method ?? 'GET').toBe('GET')
  })
})
