import { render, screen } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/App'
import { SessionProvider } from '@/auth/session'
import { MemoryRouter } from '@/test/TestRouter'
import { CLUB } from './content'

/**
 * T-60 — 랜딩은 백엔드에 의존하지 않는다.
 *
 * **`@/api/auth`를 mock하지 않는다.** 통째로 갈아끼우면 `getMe()`가 네트워크 계층까지
 * 가지 않아, 검증한다고 주장하는 것을 실제로는 검증하지 못한다. 그래서 이 파일만 따로
 * 두고 **네트워크 계층(`fetch`·`XMLHttpRequest`)을 감시한다** — 모듈 mock은 파일 단위라
 * 같은 파일에서 켜고 끌 수 없다.
 *
 * 허용되는 것은 **앱 셸의 세션 확인 하나뿐**이다 (spec 5-TESTING T-60). 랜딩 컴포넌트가
 * 부르는 API는 0건이다. 그 외 요청이 하나라도 나가면 랜딩이 백엔드에 의존하기 시작한 것이다.
 *
 * **횟수는 세지 않는다.** 실제 엔트리(`main.tsx`)가 `StrictMode`를 씌우므로 개발에서는
 * effect가 두 번 돌아 세션 확인이 2회 나간다 — 개발 모드 산물이지 백엔드 의존의 신호가
 * 아니다. 그래서 여기서도 **`StrictMode`를 그대로 씌우고**, 나간 요청이 전부 세션 확인인지만
 * 본다. 씌우지 않으면 실제 엔트리와 구조가 달라 이 경로를 아예 검증하지 못한다.
 */
const SESSION_CHECK = '/api/v1/auth/me'

let requests: string[]

/**
 * 세션 확인에 무엇으로 답할지. 사례마다 바꾼다.
 *
 * 기본값은 **비로그인**이다 — 서버는 세션이 없으면 `204`로 답한다 (#190). 오류가 아니라
 * "비로그인"이라는 답이므로, `401`로 흉내내면 실제 서버와 다른 것을 검증하게 된다.
 */
let answerSessionCheck: () => Promise<Response>

beforeEach(() => {
  requests = []
  answerSessionCheck = () =>
    Promise.resolve(new Response(null, { status: 204 }))

  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL) => {
      requests.push(String(input))
      return answerSessionCheck()
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

/** 실제 엔트리(`main.tsx`)와 같은 구조로 띄운다 — `StrictMode`까지 포함해서. */
function renderLanding() {
  render(
    <StrictMode>
      <MemoryRouter initialEntries={['/']}>
        <SessionProvider>
          <App />
        </SessionProvider>
      </MemoryRouter>
    </StrictMode>,
  )
}

describe('랜딩 네트워크 사용', () => {
  it('세션 확인 한 번 말고는 아무 요청도 하지 않는다', async () => {
    renderLanding()

    // 세션 확인이 끝난 뒤의 화면까지 기다린다 (비로그인이면 204다, #190).
    expect(
      await screen.findByRole('link', { name: '로그인' }),
    ).toBeInTheDocument()

    /*
     * 나간 요청이 **전부** 세션 확인인지 본다. 개수는 보지 않는다 — StrictMode에서 몇 번
     * 나가든 상관없고, 세션 확인이 아닌 것이 하나라도 섞이면 실패한다.
     */
    expect(requests.length).toBeGreaterThan(0)
    expect(requests.filter((url) => url !== SESSION_CHECK)).toEqual([])
  })

  /**
   * **백엔드가 죽어도 랜딩은 뜬다** (3-3 결정 8).
   *
   * 세션 확인이 성공(`204`)하는 대역으로는 이것을 확인할 수 없다 — `SessionProvider`의
   * `catch`가 아예 실행되지 않아, 실패 처리 중 랜딩이 숨겨지는 회귀가 생겨도 통과한다.
   * 그래서 여기서만 실제로 실패시킨다.
   */
  it('세션 확인이 실패해도 랜딩 콘텐츠는 그대로 렌더된다', async () => {
    answerSessionCheck = () => Promise.reject(new TypeError('Failed to fetch'))

    renderLanding()

    // 콘텐츠는 `content.ts`에서 온다 — 백엔드가 죽어도 보인다 (3-3 결정 8).
    const heading = await screen.findByRole('heading', { level: 1 })
    for (const line of CLUB.headline) {
      expect(heading).toHaveTextContent(line)
    }
    expect(screen.getByRole('heading', { name: '소개' })).toBeInTheDocument()
  })

  /** 서버가 5xx로 답해도 같다. 네트워크가 끊긴 것과 다른 경로를 탄다. */
  it('세션 확인이 5xx로 끝나도 랜딩 콘텐츠는 그대로 렌더된다', async () => {
    answerSessionCheck = () =>
      Promise.resolve(new Response('{}', { status: 500 }))

    renderLanding()

    const heading = await screen.findByRole('heading', { level: 1 })
    for (const line of CLUB.headline) {
      expect(heading).toHaveTextContent(line)
    }
  })
})
