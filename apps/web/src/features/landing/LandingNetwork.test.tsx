import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/App'
import { SessionProvider } from '@/auth/session'
import { CLUB } from './content'

/**
 * T-60 — 랜딩은 백엔드에 의존하지 않는다.
 *
 * **`@/api/auth`를 mock하지 않는다.** 통째로 갈아끼우면 `getMe()`가 네트워크 계층까지
 * 가지 않아, 검증한다고 주장하는 것을 실제로는 검증하지 못한다. 그래서 이 파일만 따로
 * 두고 **네트워크 계층(`fetch`·`XMLHttpRequest`)을 감시한다** — 모듈 mock은 파일 단위라
 * 같은 파일에서 켜고 끌 수 없다.
 *
 * 허용되는 요청은 **앱 초기화의 세션 확인 하나뿐**이다 (spec 5-TESTING T-60 예외).
 * 그 외 요청이 하나라도 늘면 랜딩이 백엔드에 의존하기 시작한 것이다.
 */
const SESSION_CHECK = '/api/v1/auth/me'

let requests: string[]

beforeEach(() => {
  requests = []

  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL) => {
      requests.push(String(input))
      // 비로그인으로 응답한다. 세션 확인이 실패해도 랜딩은 그대로 떠야 한다.
      return Promise.resolve(
        new Response(
          JSON.stringify({
            code: 'UNAUTHENTICATED',
            message: '로그인이 필요합니다.',
          }),
          { status: 401, headers: { 'Content-Type': 'application/json' } },
        ),
      )
    }),
  )

  // T-60은 경로를 가리지 않는다. XHR도 함께 감시한다.
  vi.spyOn(XMLHttpRequest.prototype, 'open').mockImplementation(function open(
    _method: string,
    url: string | URL,
  ) {
    requests.push(String(url))
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('랜딩 네트워크 사용', () => {
  it('세션 확인 한 번 말고는 아무 요청도 하지 않는다', async () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <SessionProvider>
          <App />
        </SessionProvider>
      </MemoryRouter>,
    )

    // 세션 확인이 401로 끝난 뒤의 화면까지 기다린다.
    expect(
      await screen.findByRole('link', { name: '로그인' }),
    ).toBeInTheDocument()

    // 허용 대상이 정확히 무엇인지까지 단언한다. 개수만 세면 다른 요청이 슬쩍 들어온다.
    expect(requests).toEqual([SESSION_CHECK])
  })

  it('세션 확인이 실패해도 랜딩 콘텐츠는 그대로 렌더된다', async () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <SessionProvider>
          <App />
        </SessionProvider>
      </MemoryRouter>,
    )

    // 콘텐츠는 `content.ts`에서 온다 — 백엔드가 죽어도 보인다 (3-3 결정 8).
    const heading = await screen.findByRole('heading', { level: 1 })
    for (const line of CLUB.headline) {
      expect(heading).toHaveTextContent(line)
    }
    expect(screen.getByRole('heading', { name: '소개' })).toBeInTheDocument()
  })
})
