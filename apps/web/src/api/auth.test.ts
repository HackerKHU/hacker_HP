import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearCookies, setCookie } from '@/test/cookies'
import { myContentSummary, withdraw } from './auth'

/**
 * 탈퇴 경로의 메서드와 주소 (#226, spec §3-2-3).
 *
 * **화면 테스트는 이 모듈을 통째로 mock한다.** 그러면 경로나 메서드가 틀려도 아무 데서도
 * 안 걸리고, 서버가 붙는 날 처음 드러난다 — 되돌릴 수 없는 조작이라 그때 드러나는 것이
 * 특히 나쁘다. 여기서 나가는 요청만 본다.
 */

const fetchMock = vi.fn()

beforeEach(() => {
  vi.stubEnv('VITE_USE_FIXTURES', 'false')
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
  /*
   * 탈퇴는 쓰기라 `request()`가 CSRF 토큰을 싣는다. 쿠키가 없으면 `GET /auth/csrf`부터
   * 나가 이 파일이 보려는 것과 무관한 요청이 섞인다 — 그 경로는 `client.test.ts`가 본다.
   */
  setCookie('XSRF-TOKEN', 'test-token')
})

afterEach(() => {
  clearCookies()
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

describe('탈퇴하면 남을 것', () => {
  it('GET /api/v1/auth/me/content-summary를 부르고 네 값을 그대로 돌려준다', async () => {
    const summary = { notes: 3, notices: 0, photos: 5, posts: 2 }
    fetchMock.mockResolvedValue(
      new Response(JSON.stringify(summary), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )

    await expect(myContentSummary()).resolves.toEqual(summary)

    const [path, init] = fetchMock.mock.calls[0]
    /*
     * **관리자용 경로(`/admin/users/{id}/content-summary`)가 아니다.** 그쪽을 부르면 일반
     * 부원은 `403`을 받고, 관리자는 **남의 건수를 세어 보게 된다** (계약 MUST).
     */
    expect(path).toBe('/api/v1/auth/me/content-summary')
    expect((init as RequestInit).method ?? 'GET').toBe('GET')
  })
})

describe('회원 탈퇴', () => {
  it('DELETE /api/v1/auth/me를 부른다', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))

    await expect(withdraw()).resolves.toBeUndefined()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [path, init] = fetchMock.mock.calls[0]
    expect(path).toBe('/api/v1/auth/me')
    expect((init as RequestInit).method).toBe('DELETE')
    /*
     * 상태를 바꾸는 요청이므로 CSRF 토큰이 실린다 (§3-2-3 MUST). 빠지면 서버가 거부해
     * **탈퇴가 통째로 안 된다.**
     */
    expect(new Headers((init as RequestInit).headers).get('X-XSRF-TOKEN')).toBe(
      'test-token',
    )
  })
})
