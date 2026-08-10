import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/App'
import { GOOGLE_LOGIN_PATH } from '@/api/auth'
import { ApiError } from '@/api/client'
import type { User } from '@/api/types'
import { SessionProvider } from '@/auth/session'

/**
 * 로그인 화면 (#37).
 *
 * **컴포넌트를 직접 렌더하지 않는다.** `/login?error=...`로 앱을 띄워 `GuestOnly` 가드를
 * 실제로 태운다 — 컴포넌트를 직접 그리면 이미 로그인한 사용자를 돌려보내는 동작이
 * 검증되지 않는다.
 */
const auth = vi.hoisted(() => ({
  me: (): Promise<User> =>
    Promise.reject(new Error('테스트가 지정하지 않았다')),
}))

vi.mock('@/api/auth', async (importOriginal) => {
  // 경로 상수는 실제 값을 써야 한다. 테스트가 따로 적으면 상수가 바뀌어도 통과한다.
  const actual = await importOriginal<typeof import('@/api/auth')>()
  return { ...actual, getMe: () => auth.me(), logout: () => Promise.resolve() }
})

vi.mock('@/api/notices', () => ({
  list: () =>
    Promise.resolve({
      content: [],
      page: { size: 10, number: 0, totalElements: 0, totalPages: 0 },
    }),
  get: () => Promise.reject(new Error('이 파일에서는 쓰지 않는다')),
  togglePin: () => Promise.reject(new Error('이 파일에서는 쓰지 않는다')),
  create: () => Promise.reject(new Error('이 파일에서는 쓰지 않는다')),
  update: () => Promise.reject(new Error('이 파일에서는 쓰지 않는다')),
  remove: () => Promise.reject(new Error('이 파일에서는 쓰지 않는다')),
}))

const BASE: User = {
  id: 1,
  email: 'member@khu.ac.kr',
  studentNo: '2021123456',
  name: '홍길동',
  role: 'USER',
  status: 'ACTIVE',
  createdAt: '2026-03-02T09:00:00Z',
  appliedAt: '2026-03-02T09:10:00Z',
  approvedAt: '2026-03-03T09:00:00Z',
}

function Address() {
  const { pathname } = useLocation()
  return <div data-testid="pathname">{pathname}</div>
}

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <Address />
        <App />
      </SessionProvider>
    </MemoryRouter>,
  )
}

function pathname(): string {
  return screen.getByTestId('pathname').textContent ?? ''
}

/** 로그인 화면이 그려졌다는 증거. 세션 확인이 끝난 뒤에만 존재한다. */
function loaded() {
  return screen.findByRole('button', { name: '구글 계정으로 로그인' })
}

const GUEST = new ApiError('UNAUTHENTICATED', 401, '로그인이 필요합니다.')

beforeEach(() => {
  auth.me = () => Promise.reject(GUEST)
})

describe('실패 코드 안내', () => {
  /*
   * 계약 §3-2-3의 네 코드. **각각 다른 문구여야 한다** — 같은 문구를 쓰면 도메인이 틀린
   * 사람과 정지된 사람이 같은 안내를 보고 같은 조치를 시도한다.
   */
  it.each([
    ['domain', /khu\.ac\.kr/],
    ['unverified', /이메일 인증/],
    ['suspended', /정지된 계정/],
    ['failed', /로그인하지 못했습니다/],
  ])('%s는 그 코드에 맞는 안내를 띄운다', async (code, expected) => {
    renderAt(`/login?error=${code}`)
    await loaded()

    expect(await screen.findByRole('alert')).toHaveTextContent(expected)
  })

  it('네 코드의 문구가 서로 다르다', async () => {
    const messages: string[] = []
    for (const code of ['domain', 'unverified', 'suspended', 'failed']) {
      renderAt(`/login?error=${code}`)
      const alert = await screen.findByRole('alert')
      messages.push(alert.textContent ?? '')
      /*
       * **`cleanup()`으로 걷는다. DOM만 직접 비우지 않는다.**
       * DOM을 지워도 React는 언마운트된 줄 모르고, 진행 중이던 세션 확인이 끝나며
       * 사라진 트리에 상태를 넣는다 — 실행 순서에 따라 터지는 플레이키가 된다.
       */
      cleanup()
    }

    expect(new Set(messages).size).toBe(4)
  })

  it('쿼리가 없으면 아무 안내도 띄우지 않는다', async () => {
    renderAt('/login')
    await loaded()

    expect(screen.queryByRole('alert')).toBeNull()
  })

  /*
   * 표에 없는 값에는 아무것도 띄우지 않는다. 일반 오류를 띄우면 주소를 잘못 친 사람에게
   * **없는 문제를 알리는 셈**이다. 받은 값을 그대로 그리지도 않는다 — 계약이 쿼리에
   * 이메일·토큰을 담지 않기로 했지만(MUST), 화면이 그 약속에 기대면 깨진 날 그대로 샌다.
   */
  /*
   * **프로토타입 키를 반드시 넣는다.** `bogus`·이메일·스크립트 문자열은 전부 프로토타입에
   * 없는 키라 이 경우를 못 잡는다. `__proto__`는 선언한 적 없는데도 `Object.prototype`을
   * 돌려주고, truthy라 React가 객체를 자식으로 렌더하려다 예외를 낸다 — **URL 하나로
   * 공개 로그인 진입점이 통째로 죽는다.**
   */
  it.each([
    'bogus',
    'user@khu.ac.kr',
    '<script>alert(1)</script>',
    '__proto__',
    'constructor',
    'toString',
    'hasOwnProperty',
    'valueOf',
  ])(
    '표에 없는 %s에는 안내를 띄우지 않고 값을 그리지도 않는다',
    async (value) => {
      renderAt(`/login?error=${encodeURIComponent(value)}`)
      await loaded()

      // 화면이 살아 있는지부터 본다. 크래시하면 버튼조차 없다.
      expect(screen.queryByRole('alert')).toBeNull()
      expect(document.body.textContent).not.toContain(value)
      expect(document.body.textContent).not.toContain('[object Object]')
    },
  )
})

describe('정지된 세션', () => {
  /*
   * **이번 스펙 수정의 핵심** (spec §3-1-5).
   *
   * 이용 중 관리자가 정지시키면 다음 요청이 403 SUSPENDED로 막히고 세션이 정지로
   * 정리되어 가드가 로그인 화면으로 보낸다 — **주소에 쿼리가 없다.** 이 경우를 빠뜨리면
   * 설명 없는 로그인 화면으로 한 번 튕긴다 — 다시 로그인하면 ①이 안내를 주지만,
   * 그 한 번을 없애는 것이 ②의 몫이다.
   */
  it('쿼리가 없어도 정지 안내를 띄운다', async () => {
    auth.me = () => Promise.resolve({ ...BASE, status: 'SUSPENDED' })

    renderAt('/login')
    await loaded()

    expect(await screen.findByRole('alert')).toHaveTextContent(/정지된 계정/)
  })

  // 두 경로의 문구는 같아야 한다 (MUST). 사용자에게는 같은 사실이다.
  it('세션으로 온 안내와 ?error=suspended 안내가 같다', async () => {
    auth.me = () => Promise.resolve({ ...BASE, status: 'SUSPENDED' })
    renderAt('/login')
    const fromSession = (await screen.findByRole('alert')).textContent
    cleanup()

    auth.me = () => Promise.reject(GUEST)
    renderAt('/login?error=suspended')
    const fromQuery = (await screen.findByRole('alert')).textContent

    expect(fromSession).toBe(fromQuery)
  })

  // 정지된 사람도 랜딩은 봐야 한다 (T-61). 로그인 화면으로 밀지 않는다.
  it('정지된 세션도 랜딩은 그대로 본다', async () => {
    auth.me = () => Promise.resolve({ ...BASE, status: 'SUSPENDED' })

    renderAt('/')

    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument()
    expect(pathname()).toBe('/')
  })
})

describe('로그인 버튼', () => {
  /*
   * **브라우저 전체를 이동시킨다. `fetch`가 아니다.** OAuth는 리다이렉트 흐름이라
   * `fetch`로는 성립하지 않는다 — 구글 화면이 떠야 하는데 응답만 받아온다.
   */
  it('구글 로그인 경로로 브라우저를 옮긴다', async () => {
    const assign = vi.fn()
    vi.spyOn(window, 'location', 'get').mockReturnValue({
      ...window.location,
      assign,
    })
    const fetchSpy = vi.fn(() => Promise.reject(new Error('부르면 안 된다')))
    vi.stubGlobal('fetch', fetchSpy)

    renderAt('/login')
    fireEvent.click(await loaded())

    expect(assign).toHaveBeenCalledWith(GOOGLE_LOGIN_PATH)
    // 경로가 계약대로인지도 본다 — 상수만 비교하면 상수가 틀려도 통과한다.
    expect(GOOGLE_LOGIN_PATH).toBe('/api/v1/oauth2/authorization/google')
    expect(fetchSpy).not.toHaveBeenCalled()

    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })
})

describe('이미 로그인한 사용자', () => {
  it.each([
    ['ACTIVE', BASE, '/notices'],
    [
      'PENDING',
      { ...BASE, status: 'PENDING' as const, approvedAt: null },
      '/pending',
    ],
  ])('%s가 /login에 오면 %s로 되돌린다', async (_label, user, expected) => {
    auth.me = () => Promise.resolve(user)

    renderAt('/login')

    await waitFor(() => {
      expect(pathname()).toBe(expected)
    })
    expect(
      screen.queryByRole('button', { name: '구글 계정으로 로그인' }),
    ).toBeNull()
  })

  it('없어진 /signup은 로그인 화면으로 보낸다', async () => {
    renderAt('/signup')

    expect(await loaded()).toBeInTheDocument()
    expect(pathname()).toBe('/login')
  })
})
