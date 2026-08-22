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
import { CLUB } from '@/features/landing/content'

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
  department: '컴퓨터공학과',
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

/**
 * 로그인 화면이 그려졌다는 증거. 세션 확인이 끝난 뒤에만 존재한다.
 *
 * 문구는 **승인된 CTA `Continue with Google`의 한글판**이다 (spec §3-1-5, T-117).
 * 글씨가 실제 텍스트라 버튼 이름이 여기서 그대로 나온다.
 */
const BUTTON_NAME = 'Google로 계속하기'

/**
 * 공식 배포본의 G 글리프 path. **테스트가 값을 들고 있어야** 누가 로고를 바꿔도 잡힌다 —
 * 컴포넌트에서 읽어오면 컴포넌트가 틀려도 같이 틀린 값을 비교하게 된다.
 */
const OFFICIAL_G_PATH =
  'M29.3987 18.1814H19.9849V22.0445H25.3598C25.1286 23.294 24.4294 24.3596 23.3676 25.0712C22.4746 25.6716 21.3266 26.0211 19.9849 26.0211C17.3864 26.0211 15.1823 24.2666 14.3947 21.9004C14.1952 21.2989 14.0853 20.6599 14.0853 19.9983C14.0853 19.3367 14.1952 18.6966 14.3947 18.0962C15.1823 15.7311 17.3864 13.9755 19.9849 13.9755C21.4524 13.9755 22.767 14.4816 23.8039 15.4713L26.6653 12.6057C24.936 10.9908 22.6786 10 19.9849 10C16.0832 10 12.705 12.2414 11.0618 15.5076C10.383 16.8592 10 18.3834 10 19.9994C10 21.6155 10.383 23.1396 11.0618 24.4913C12.705 27.7597 16.0832 30 19.9849 30C22.6797 30 24.9485 29.1137 26.6018 27.5861C28.4887 25.8452 29.5732 23.2702 29.5732 20.2275C29.5732 19.5182 29.5131 18.835 29.3987 18.1825V18.1814Z'

function loaded() {
  return screen.findByRole('button', { name: BUTTON_NAME })
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

describe('로고', () => {
  /*
   * 자리표시자를 걷고 확정 로고를 넣었다. **배경에 맞는 잉크를 골라야 한다** — 왼쪽
   * 패널은 `.dark`라 흰색, 좁은 화면 자리는 라이트라 검정이다. 바뀌면 마크가 배경에
   * 묻혀 안 보이는데, 화면을 안 열어보면 모른다.
   *
   * 파일 존재는 `meta.test.ts`가 `og:image`에 대해 하는 것과 같은 종류의 검사다 —
   * 태그만 있고 그림이 없으면 화면에 깨진 아이콘이 뜬다.
   */
  it('배경에 맞는 잉크의 마크를 쓴다', async () => {
    renderAt('/login')

    const dark = await screen.findByAltText('해커')
    expect(dark).toHaveAttribute('src', '/brand/mark-white-512.png')

    const light = screen.getByAltText(CLUB.fullName)
    expect(light).toHaveAttribute('src', '/brand/mark-black-256.png')
  })

  /*
   * 로고는 장식이 아니라 이 화면이 어디인지 말하는 요소다. `alt`가 비면 스크린리더
   * 사용자에게는 그 정보가 통째로 사라진다.
   */
  it('마크에 대체 텍스트가 있다', async () => {
    renderAt('/login')

    for (const img of await screen.findAllByRole('img')) {
      expect(img).toHaveAccessibleName()
    }
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

describe('구글 버튼', () => {
  /*
   * T-117 — 가이드라인이 승인한 CTA는 셋뿐이고 그 현지화가 허용된다. 임의 표현을 쓰면
   * 승인 범위 밖이다. 이 버튼은 로그인과 가입을 겸하므로 `Continue with Google`의
   * 한글판을 쓴다 (spec §3-1-5, 3-3 결정 13).
   */
  it('문구가 승인된 CTA의 한글판이다', async () => {
    renderAt('/login')

    const button = await loaded()
    expect(button).toHaveAccessibleName('Google로 계속하기')
    // 승인 목록에 없는 표현이나 `Google` 단독은 쓰지 않는다.
    expect(button.textContent).not.toMatch(/시작하기|로그인하기/)
    expect(button.textContent?.trim()).not.toBe('Google')
  })

  /*
   * T-118 — **로고는 우리가 그리는 것이 아니다.** 공식 배포본에서 뽑은 모양 그대로여야
   * 하고, 값이 바뀌었다는 것은 누가 손댔다는 뜻이다. 눈으로는 알기 어려워 값으로 박는다.
   *
   * 아래 `d`는 `signin-assets.zip`의 `Theme=Light, Show text=No, Shape=Square`에 있는
   * G 글리프 path다.
   */
  it('로고 모양이 공식 배포본 값 그대로다', async () => {
    renderAt('/login')
    await loaded()

    const path = document.querySelector('svg[aria-hidden="true"] mask path')
    expect(path?.getAttribute('d')).toBe(OFFICIAL_G_PATH)
  })

  /*
   * T-118 — **"공식 배포본의 모양 그대로"를 항목별로 확인하지 않는다.** 색만 보던 때는
   * `stdDeviation`(흐림 세기)이나 clip path 좌표를 바꿔도 전부 통과했다. 항목을 나열하면
   * 나열에서 빠진 것이 생긴다.
   *
   * 그래서 **로고 마크업 전체를 고정한다.** 아래 문자열은 `signin-assets.zip`의
   * `Theme=Light, Show text=No, Shape=Square`에서 40x40 버튼 껍데기 두 줄(흰 배경 path,
   * `#747775` 테두리 path)과 `<svg>` 껍데기만 걷어낸 나머지 전부다 — 공식 파일에서 그대로
   * 뽑았고 **컴포넌트에서 가져오지 않았다.**
   *
   * **문자열로 비교하지 않고 정규 형태로 비교한다** (`canonical()`). 그림을 정하는 것만
   * 본다 — 요소 이름과 중첩, 속성 이름과 값. 요소 사이의 공백과 속성이 적힌 순서는 그림을
   * 바꾸지 않으므로 버린다. 문자열 그대로 비교하면 **속성 순서만 바꾼 소스 정리나 포매터의
   * 재정렬까지 브랜딩 회귀로 오인한다.**
   */
  /*
   * ── T-118이 보증하는 범위 ────────────────────────────────────────────────
   *
   * 로고 마크업이 공식 배포본과 같은지, 그리고 로고와 버튼에 직접 건 표시 속성 일부가
   * 기본값인지 본다. **정확히 무엇을 보는지는 아래 테스트가 원본이다** — 말로 옮겨 적으면
   * 검사와 설명이 어긋난다. 실제로 세 번 어긋났고, 그때마다 설명 쪽이 넓었다.
   *
   * **CSS로 최종 표시를 바꾸는 경로를 전부 막지는 못한다.** 그 경로는 열거할 수 없어서,
   * 하나를 막으면 다른 하나가 나오고 목록이 길어질수록 정당한 변경까지 막을 위험만 커진다.
   *
   * **그래서 이 테스트를 통과한 것이 가이드라인 준수의 증명은 아니다.** 로고 주변의 CSS를
   * 새로 건드리는 변경은 사람이 화면을 보고 확인한다 (spec 5-TESTING T-118).
   *
   * 이 주석에 클래스 이름을 그대로 적지 않는다 — **Tailwind가 주석까지 훑어 실제 CSS로
   * 내보낸다.** 설명하려고 적은 이름이 번들에 규칙으로 남았던 적이 있다.
   * ────────────────────────────────────────────────────────────────────────
   */
  it('로고 마크업이 공식 배포본 그대로다', async () => {
    renderAt('/login')
    await loaded()

    const svg = document.querySelector('svg[aria-hidden="true"]')
    /*
     * **`toBe`가 아니라 `toEqual`이다.** 문자열 한 덩이가 아니라 중첩 배열을 비교하므로,
     * 값 안의 공백이나 `=`가 요소·속성의 경계를 넘지 못한다.
     */
    expect(canonical(svg?.innerHTML ?? '')).toEqual(
      canonical(OFFICIAL_LOGO_MARKUP),
    )

    /*
     * **`viewBox`는 위 비교에 안 들어간다.** `innerHTML`을 견주므로 바깥 `<svg>` 자신의
     * 속성은 빠진다 — 그런데 이 값이 공식 40x40 그림에서 **로고 부분만 잘라내는 창**이라,
     * 바뀌면 걷어낸 버튼 껍데기가 다시 보이거나 로고가 잘린다. 여기서 따로 못 박는다.
     */
    expect(svg?.getAttribute('viewBox')).toBe('10 10 20 20')
    /*
     * `fill="none"`도 같은 이유로 여기 있다. 그라디언트를 그리는 path에는 `fill`이 없어
     * **바깥에서 물려받는데**, 이 값이 바뀌면 그 자리가 단색으로 덮인다. 공식 파일의
     * 바깥 `<svg>`가 갖고 있던 값 그대로다.
     */
    expect(svg?.getAttribute('fill')).toBe('none')
  })

  /*
   * T-118 — **가이드라인이 이름을 지어 금지하는 것이 색 변경이다.** 구조만 보면
   * (`foreignObject`가 있다 / 타원이 하나 이상이다) 타원 하나를 검정으로 칠해도 통과한다.
   * 실제로 그 변이가 초록으로 지나갔다.
   *
   * 그래서 **색 값 자체를 고정한다.** 아래 두 목록은 `signin-assets.zip`의
   * `Theme=Light, Show text=No, Shape=Square`에서 그대로 뽑은 것이다 — **컴포넌트에서
   * import하지 않는다.** 가져다 쓰면 컴포넌트가 틀려도 같이 틀린 값을 비교하게 된다
   * (`OFFICIAL_G_PATH`를 테스트가 들고 있는 것과 같은 이유다).
   */
  it('로고 색이 공식 배포본 값 그대로다', async () => {
    renderAt('/login')
    await loaded()

    const svg = document.querySelector('svg[aria-hidden="true"]')

    /*
     * 칠해진 도형의 색. 문서 순서까지 본다 — 순서가 달라졌다는 것은 도형이 바뀌었다는
     * 뜻이다. 첫 값은 mask path이고 나머지 여섯은 흐림 타원이다.
     */
    const fills = [...(svg?.querySelectorAll('[fill]') ?? [])].map((el) =>
      el.getAttribute('fill'),
    )
    expect(fills).toEqual([
      '#E94FFF',
      '#3186FF',
      '#3186FF',
      '#FF4641',
      '#FF5B8B',
      '#3186FF',
      '#FF4641',
    ])

    /*
     * 2025 개편판의 본체는 단색이 아니라 `foreignObject` 안 CSS 원뿔 그라디언트다.
     * 정지점 색이 여기서 온다 — `style` 속성을 문자열 그대로 읽는다(jsdom이 파싱하지
     * 못하는 `conic-gradient`라 `.style.background`로는 비어 있다).
     */
    const gradient =
      svg?.querySelector('foreignObject div')?.getAttribute('style') ?? ''
    const stops = [...new Set(gradient.match(/rgba\([^)]*\)/g) ?? [])]
    expect(stops).toEqual([
      'rgba(255, 70, 65, 1)',
      'rgba(49, 134, 255, 1)',
      'rgba(0, 165, 183, 1)',
      'rgba(14, 188, 95, 1)',
      'rgba(108, 196, 0, 1)',
      'rgba(255, 204, 0, 1)',
      'rgba(255, 211, 20, 1)',
      'rgba(255, 106, 43, 1)',
      'rgba(253, 70, 65, 1)',
    ])
  })

  /*
   * T-118 — **마크업이 공식 그대로여도 CSS로 그림을 바꿀 수 있다** (spec 5-TESTING:275·283).
   * 위 비교는 `innerHTML`만 보므로 바깥에서 씌우는 투명도·필터가 안 잡힌다. 실제로 이 PR의
   * 초기 커밋에 버튼이 호버에서 흐려지는 유틸리티를 달고 있었다 — 가정이 아니라
   * 있었던 회귀다.
   *
   * **경계는 "보이는 그림 자체를 바꾸는가"다.** 색·투명도·필터·블렌드는 금지고, **크기는
   * 아니다** — 가이드라인이 비율 유지 확대를 허용한다(§3-1-5). 크기까지 막으면 정당한
   * 변경을 막는 셈이다.
   *
   * **두 갈래로 본다. 한 갈래로는 절반만 잡힌다.**
   *
   * | 무엇을 | 어떻게 | 왜 |
   * |---|---|---|
   * | 인라인 `style`·물려받은 값 | `getComputedStyle` | 계산값에 그대로 나온다 |
   * | Tailwind 클래스 | 클래스 목록 | 이 환경에는 스타일시트가 없어 **계산값이 늘 기본값**이다 |
   *
   * 두 번째를 재서 확인했다 — 투명도를 낮추는 클래스를 붙여도 jsdom의 계산값은 `1`이다.
   * 클래스를 따로 보지 않으면 그 회귀가 통째로 지나간다.
   */
  it('로고에 색·투명도·필터를 걸지 않는다', async () => {
    renderAt('/login')
    const button = await loaded()
    const svg = document.querySelector('svg[aria-hidden="true"]')
    if (svg === null) throw new Error('로고가 없다')

    // ① 최종 계산값. 버튼도 함께 본다 — 로고가 버튼의 투명도를 물려받는다.
    for (const element of [svg, button]) {
      const style = getComputedStyle(element)
      expect(style.opacity).toBe('1')
      expect(style.filter).toBe('none')
      expect(style.mixBlendMode).toBe('normal')
    }

    /*
     * ② 로고의 클래스는 **크기 유틸리티만** 허용한다.
     *
     * 금지 목록이 아니라 허용 목록이다. 금지 목록은 새 유틸리티가 생길 때마다 새고,
     * 그 사이에 들어온 것은 영영 안 잡힌다. 나중에 크기 아닌 클래스가 정말 필요해지면
     * 이 단언이 깨지는 것이 맞다 — 그때 **그 클래스가 그림을 바꾸지 않는지 확인하고**
     * 여기 적으면 된다.
     */
    const logoClasses = (svg.getAttribute('class') ?? '')
      .split(/\s+/)
      .filter(Boolean)
    expect(logoClasses).toEqual([expect.stringMatching(/^size-/)])

    /*
     * 버튼은 레이아웃·규격 클래스를 여럿 갖는다. 허용 목록을 만들 자리가 아니라,
     * **그림을 바꾸는 것만** 막는다. 색(`border-[#747775]`·`text-[#1f1f1f]`)은 가이드라인이
     * 정한 값이라 금지 대상이 아니다.
     *
     * **비활성 상태만 뺀다** (아래 `startsWith` 참조). shadcn `Button`이 모든 버튼에 그
     * 상태의 투명도를 주는데, 이 버튼은 비활성으로 그려지는 경로가 없고(누르면 브라우저가
     * 이동할 뿐이다) 막으려면 공용 컴포넌트를 갈라야 한다. 실제로 있었던 회귀는 호버 쪽이고
     * 그것은 그대로 걸린다 — 평소 보이는 상태에서 로고가 흐려지는 것이 금지 대상이다.
     */
    const appearance =
      /(^|:)(opacity|grayscale|invert|sepia|saturate|brightness|contrast|hue-rotate|blur|mix-blend|backdrop)-/
    const risky = button.className
      .split(/\s+/)
      .filter(
        (token) => appearance.test(token) && !token.startsWith('disabled:'),
      )
    expect(risky).toEqual([])
  })

  // 자체 제작 아이콘·단색화 금지. 색은 공식 그라디언트에서 온다.
  it('로고를 단색으로 칠하지 않는다', async () => {
    renderAt('/login')
    await loaded()

    const svg = document.querySelector('svg[aria-hidden="true"]')
    expect(svg?.querySelector('foreignObject')).not.toBeNull()
    expect(svg?.querySelectorAll('ellipse').length).toBeGreaterThan(0)
  })

  // 이름은 버튼 텍스트가 담당한다. 로고가 이름에 끼면 스크린리더가 두 번 읽는다.
  it('로고는 접근성 트리에서 숨긴다', async () => {
    renderAt('/login')
    await loaded()

    expect(
      document.querySelector('svg[aria-hidden="true"]'),
    ).toBeInTheDocument()
  })

  // 테두리·문구 없이 로고만 두지 않는다 (가이드라인 금지 사항).
  it('버튼에 테두리와 문구가 함께 있다', async () => {
    renderAt('/login')
    const button = await loaded()

    expect(button.className).toContain('border-[#747775]')
    expect(button).toHaveTextContent('Google로 계속하기')
  })

  /*
   * 버튼을 키우더라도 **비율은 하나의 배율로 지킨다** (spec §3-1-5 MUST). 치수를 px로
   * 적으면 항목마다 반올림이 갈려 조용히 어긋난다 — 실제로 로고만 ×1.15, 간격은 ×1.143로
   * 어긋나 있었다. `em`으로 적으면 배율이 글씨 크기 한 곳에만 남는다.
   *
   * 규격 글씨가 14px이므로 각 값은 `규격 ÷ 14`다. 여기 적힌 수는 규격표에서 나온 것이지
   * 코드에서 가져온 것이 아니다.
   */
  it('버튼 치수가 글씨 크기에 상대다 — 한 배율로만 커진다', async () => {
    renderAt('/login')
    const button = await loaded()

    /*
     * **기준점부터 고정한다.** 아래 비율들은 글씨 크기에 상대라, 글씨만 `text-sm`으로
     * 낮추면 비율은 그대로인 채 버튼 전체가 1배로 줄어든다 — "한 배율" 규칙은 안 깨지지만
     * 스펙 §3-1-5가 못 박은 **배율 `16/14`와 표의 실제 치수**를 못 지킨다. 비율만 보고
     * 기준을 안 보면 그 축소가 조용히 지나간다.
     *
     * 규격 글씨가 14px이고 배율이 `16/14`이므로 여기 글씨는 16px(`text-base`)이다.
     */
    expect(button.className).toContain('text-base')
    expect(button.className).not.toMatch(/text-(xs|sm|lg|xl)\b/)

    // 20/14, 40/14, 12/14, 14/14 — 전부 같은 기준(글씨 14px)에서 나온 비율이다.
    expect(button.className).toContain('leading-[1.4286]')
    expect(button.className).toContain('h-[2.8571em]')
    expect(button.className).toContain('px-[0.8571em]')
    /*
     * shadcn `Button`이 아이콘 든 버튼에 `has-[>svg]:px-3`을 건다. 같은 변이로 덮지
     * 않으면 특이도로 그쪽이 이겨 여백만 규격 1배(12px)로 남는다.
     */
    expect(button.className).toContain('has-[>svg]:px-[0.8571em]')
    expect(button.className).toContain('gap-[1em]')
    expect(
      document.querySelector('svg[aria-hidden="true"]')?.getAttribute('class'),
    ).toContain('size-[1.4286em]')

    // 한 곳(글씨 크기)만 배율을 정한다. px로 박아두면 나머지가 따라오지 않는다.
    expect(button.className).not.toMatch(/h-\[\d+px\]|px-\[\d+px\]|gap-\d/)
  })
})

describe('화면 세로 정렬', () => {
  /*
   * **`items-center`로 가운데를 잡지 않는다.** 자식이 화면보다 커지는 순간 위쪽이
   * 컨테이너 밖으로 밀려나는데 그 영역은 `scrollHeight`에 잡히지 않아 **스크롤로 닿을
   * 수 없다.** 창이 낮거나 브라우저 글꼴을 키우면 제목이 잘린 채 복구가 안 된다.
   * `margin: auto`는 남는 공간이 있을 때만 나눠 갖고 없으면 0이 되어 위에서 시작한다.
   *
   * **이것은 클래스 단언이고, 그 사실을 숨기지 않는다.** jsdom에는 레이아웃 엔진이 없어
   * `getBoundingClientRect()`가 전부 0이라 "위쪽이 잘렸다"를 여기서 잴 수 없다. 실제
   * 기하는 프로덕션 빌드를 띄워 쟀다(1280×420에서 닿을 수 없는 위쪽 0px, 125px 스크롤).
   * 이 테스트가 막는 것은 **그 측정 없이 조용히 옛 구조로 되돌아가는 것**이다.
   */
  it('가운데 정렬을 정렬 속성이 아니라 자식의 auto 여백으로 잡는다', async () => {
    renderAt('/login')
    await loaded()

    const card = document.querySelector('.max-w-4xl')
    const outer = card?.parentElement

    expect(outer?.className).toContain('min-h-screen')
    /*
     * 가로 가운데(`justify-center`)는 그대로 둔다 — 가로 축은 넘쳐도 잘리지 않는다.
     * 막는 것은 **세로(교차 축) 정렬**이다. 이 컨테이너가 `flex` 행이라 세로를 잡는 것은
     * `align-items` 쪽이고, 그것이 넘칠 때 위쪽을 스크롤 밖으로 밀어내는 속성이다.
     */
    expect(outer?.className).not.toMatch(
      /items-center|place-items-center|content-center/,
    )
    expect(card?.className).toContain('my-auto')
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
    expect(screen.queryByRole('button', { name: BUTTON_NAME })).toBeNull()
  })

  it('없어진 /signup은 로그인 화면으로 보낸다', async () => {
    renderAt('/signup')

    expect(await loaded()).toBeInTheDocument()
    expect(pathname()).toBe('/login')
  })
})

/**
 * 공식 배포본의 로고 부분 **전체**. 위 `로고 마크업이 공식 배포본 그대로다`가 쓴다.
 *
 * 원본: `signin-assets.zip` → `Android + Web/SVG/Light/Theme=Light, Show text=No,
 * Shape=Square, Platform=Android+Web.svg`. 그 파일에서 걷어낸 것은 세 줄뿐이다 —
 * `<svg>` 껍데기, 흰 배경 path, `#747775` 테두리 path. 나머지는 한 글자도 고치지 않았다.
 *
 * **컴포넌트에서 import하지 않는다.** 가져다 쓰면 컴포넌트가 틀려도 같이 틀린 값을
 * 비교하게 된다.
 */
const OFFICIAL_LOGO_MARKUP = `<mask id="mask0_1298_12516" style="mask-type:alpha" maskUnits="userSpaceOnUse" x="10" y="10" width="20" height="20">
<path d="M29.3987 18.1814H19.9849V22.0445H25.3598C25.1286 23.294 24.4294 24.3596 23.3676 25.0712C22.4746 25.6716 21.3266 26.0211 19.9849 26.0211C17.3864 26.0211 15.1823 24.2666 14.3947 21.9004C14.1952 21.2989 14.0853 20.6599 14.0853 19.9983C14.0853 19.3367 14.1952 18.6966 14.3947 18.0962C15.1823 15.7311 17.3864 13.9755 19.9849 13.9755C21.4524 13.9755 22.767 14.4816 23.8039 15.4713L26.6653 12.6057C24.936 10.9908 22.6786 10 19.9849 10C16.0832 10 12.705 12.2414 11.0618 15.5076C10.383 16.8592 10 18.3834 10 19.9994C10 21.6155 10.383 23.1396 11.0618 24.4913C12.705 27.7597 16.0832 30 19.9849 30C22.6797 30 24.9485 29.1137 26.6018 27.5861C28.4887 25.8452 29.5732 23.2702 29.5732 20.2275C29.5732 19.5182 29.5131 18.835 29.3987 18.1825V18.1814Z" fill="#E94FFF"/>
</mask>
<g mask="url(#mask0_1298_12516)">
<g filter="url(#filter0_f_1298_12516)">
<g clip-path="url(#paint0_angular_1298_12516_clip_path)" data-figma-skip-parse="true"><g transform="matrix(0.00804129 -0.00805186 0.00804128 0.00805186 19.6819 19.7927)"><foreignObject x="-2105.64" y="-2105.64" width="4211.29" height="4211.29"><div xmlns="http://www.w3.org/1999/xhtml" style="background:conic-gradient(from 90deg,rgba(255, 70, 65, 1) 0deg,rgba(255, 70, 65, 1) 4.14555deg,rgba(49, 134, 255, 1) 39.154deg,rgba(49, 134, 255, 1) 72.0044deg,rgba(0, 165, 183, 1) 96.7463deg,rgba(14, 188, 95, 1) 120.897deg,rgba(14, 188, 95, 1) 154.722deg,rgba(108, 196, 0, 1) 179.136deg,rgba(255, 204, 0, 1) 203.588deg,rgba(255, 211, 20, 1) 226.915deg,rgba(255, 204, 0, 1) 251.688deg,rgba(255, 106, 43, 1) 273.129deg,rgba(253, 70, 65, 1) 289.305deg,rgba(255, 70, 65, 1) 359.593deg,rgba(255, 70, 65, 1) 360deg);height:100%;width:100%;opacity:1"></div></foreignObject></g></g><path d="M7.25922 19.7927C7.25922 12.6759 13.0209 6.90668 20.1283 6.90668C27.2357 6.90668 32.9973 12.6759 32.9973 19.7927C32.9973 26.9094 27.2357 32.6786 20.1283 32.6786C13.0209 32.6786 7.25921 26.9094 7.25922 19.7927Z" data-figma-gradient-fill="{&#34;type&#34;:&#34;GRADIENT_ANGULAR&#34;,&#34;stops&#34;:[{&#34;color&#34;:{&#34;r&#34;:1.0,&#34;g&#34;:0.27450981736183167,&#34;b&#34;:0.25490197539329529,&#34;a&#34;:1.0},&#34;position&#34;:0.011515417136251926},{&#34;color&#34;:{&#34;r&#34;:0.19215686619281769,&#34;g&#34;:0.52549022436141968,&#34;b&#34;:1.0,&#34;a&#34;:1.0},&#34;position&#34;:0.10876122117042542},{&#34;color&#34;:{&#34;r&#34;:0.19215686619281769,&#34;g&#34;:0.52549022436141968,&#34;b&#34;:1.0,&#34;a&#34;:1.0},&#34;position&#34;:0.20001229643821716},{&#34;color&#34;:{&#34;r&#34;:0.0,&#34;g&#34;:0.64705884456634521,&#34;b&#34;:0.71764707565307617,&#34;a&#34;:1.0},&#34;position&#34;:0.26873961091041565},{&#34;color&#34;:{&#34;r&#34;:0.054901961237192154,&#34;g&#34;:0.73725491762161255,&#34;b&#34;:0.37254902720451355,&#34;a&#34;:1.0},&#34;position&#34;:0.33582508563995361},{&#34;color&#34;:{&#34;r&#34;:0.054901961237192154,&#34;g&#34;:0.73725491762161255,&#34;b&#34;:0.37254902720451355,&#34;a&#34;:1.0},&#34;position&#34;:0.42978334426879883},{&#34;color&#34;:{&#34;r&#34;:0.42528781294822693,&#34;g&#34;:0.77231442928314209,&#34;b&#34;:0.0,&#34;a&#34;:1.0},&#34;position&#34;:0.49760133028030396},{&#34;color&#34;:{&#34;r&#34;:1.0,&#34;g&#34;:0.80000001192092896,&#34;b&#34;:0.0,&#34;a&#34;:1.0},&#34;position&#34;:0.56552332639694214},{&#34;color&#34;:{&#34;r&#34;:1.0,&#34;g&#34;:0.82745099067687988,&#34;b&#34;:0.078431375324726105,&#34;a&#34;:1.0},&#34;position&#34;:0.63031959533691406},{&#34;color&#34;:{&#34;r&#34;:1.0,&#34;g&#34;:0.80000001192092896,&#34;b&#34;:0.0,&#34;a&#34;:1.0},&#34;position&#34;:0.69913208484649658},{&#34;color&#34;:{&#34;r&#34;:1.0,&#34;g&#34;:0.41842123866081238,&#34;b&#34;:0.16917318105697632,&#34;a&#34;:1.0},&#34;position&#34;:0.75869029760360718},{&#34;color&#34;:{&#34;r&#34;:0.99215686321258545,&#34;g&#34;:0.27450981736183167,&#34;b&#34;:0.25490197539329529,&#34;a&#34;:1.0},&#34;position&#34;:0.80362409353256226},{&#34;color&#34;:{&#34;r&#34;:1.0,&#34;g&#34;:0.27450981736183167,&#34;b&#34;:0.25490197539329529,&#34;a&#34;:1.0},&#34;position&#34;:0.99887031316757202}],&#34;stopsVar&#34;:[{&#34;color&#34;:{&#34;r&#34;:1.0,&#34;g&#34;:0.27450981736183167,&#34;b&#34;:0.25490197539329529,&#34;a&#34;:1.0},&#34;position&#34;:0.011515417136251926},{&#34;color&#34;:{&#34;r&#34;:0.19215686619281769,&#34;g&#34;:0.52549022436141968,&#34;b&#34;:1.0,&#34;a&#34;:1.0},&#34;position&#34;:0.10876122117042542},{&#34;color&#34;:{&#34;r&#34;:0.19215686619281769,&#34;g&#34;:0.52549022436141968,&#34;b&#34;:1.0,&#34;a&#34;:1.0},&#34;position&#34;:0.20001229643821716},{&#34;color&#34;:{&#34;r&#34;:0.0,&#34;g&#34;:0.64705884456634521,&#34;b&#34;:0.71764707565307617,&#34;a&#34;:1.0},&#34;position&#34;:0.26873961091041565},{&#34;color&#34;:{&#34;r&#34;:0.054901961237192154,&#34;g&#34;:0.73725491762161255,&#34;b&#34;:0.37254902720451355,&#34;a&#34;:1.0},&#34;position&#34;:0.33582508563995361},{&#34;color&#34;:{&#34;r&#34;:0.054901961237192154,&#34;g&#34;:0.73725491762161255,&#34;b&#34;:0.37254902720451355,&#34;a&#34;:1.0},&#34;position&#34;:0.42978334426879883},{&#34;color&#34;:{&#34;r&#34;:0.42528781294822693,&#34;g&#34;:0.77231442928314209,&#34;b&#34;:0.0,&#34;a&#34;:1.0},&#34;position&#34;:0.49760133028030396},{&#34;color&#34;:{&#34;r&#34;:1.0,&#34;g&#34;:0.80000001192092896,&#34;b&#34;:0.0,&#34;a&#34;:1.0},&#34;position&#34;:0.56552332639694214},{&#34;color&#34;:{&#34;r&#34;:1.0,&#34;g&#34;:0.82745099067687988,&#34;b&#34;:0.078431375324726105,&#34;a&#34;:1.0},&#34;position&#34;:0.63031959533691406},{&#34;color&#34;:{&#34;r&#34;:1.0,&#34;g&#34;:0.80000001192092896,&#34;b&#34;:0.0,&#34;a&#34;:1.0},&#34;position&#34;:0.69913208484649658},{&#34;color&#34;:{&#34;r&#34;:1.0,&#34;g&#34;:0.41842123866081238,&#34;b&#34;:0.16917318105697632,&#34;a&#34;:1.0},&#34;position&#34;:0.75869029760360718},{&#34;color&#34;:{&#34;r&#34;:0.99215686321258545,&#34;g&#34;:0.27450981736183167,&#34;b&#34;:0.25490197539329529,&#34;a&#34;:1.0},&#34;position&#34;:0.80362409353256226},{&#34;color&#34;:{&#34;r&#34;:1.0,&#34;g&#34;:0.27450981736183167,&#34;b&#34;:0.25490197539329529,&#34;a&#34;:1.0},&#34;position&#34;:0.99887031316757202}],&#34;transform&#34;:{&#34;m00&#34;:16.082571029663086,&#34;m01&#34;:16.082569122314453,&#34;m02&#34;:3.5993347167968750,&#34;m10&#34;:-16.103721618652344,&#34;m11&#34;:16.103721618652344,&#34;m12&#34;:19.792665481567383},&#34;opacity&#34;:1.0,&#34;blendMode&#34;:&#34;NORMAL&#34;,&#34;visible&#34;:true}"/>
</g>
<g filter="url(#filter1_f_1298_12516)">
<ellipse cx="20.0496" cy="20.2413" rx="5.39634" ry="2.83537" transform="rotate(24.4473 20.0496 20.2413)" fill="#3186FF"/>
</g>
<g filter="url(#filter2_f_1298_12516)">
<ellipse cx="33.3538" cy="18.2155" rx="7.43918" ry="3.09357" fill="#3186FF"/>
</g>
<g filter="url(#filter3_f_1298_12516)">
<ellipse cx="25.2744" cy="16.2195" rx="7.40854" ry="2.37805" fill="#FF4641"/>
</g>
<g filter="url(#filter4_f_1298_12516)">
<ellipse cx="29.5427" cy="12.9268" rx="7.40854" ry="2.37805" fill="#FF5B8B"/>
</g>
<g filter="url(#filter5_f_1298_12516)">
<ellipse cx="24.4817" cy="19.878" rx="8.5061" ry="3.10976" fill="#3186FF"/>
</g>
<g filter="url(#filter6_f_1298_12516)">
<ellipse cx="25.1842" cy="14.0197" rx="4.53882" ry="2.37805" transform="rotate(-28.6599 25.1842 14.0197)" fill="#FF4641"/>
</g>
</g>
<defs>
<filter id="filter0_f_1298_12516" x="5.25922" y="4.90668" width="29.7381" height="29.772" filterUnits="userSpaceOnUse" color-interpolation-filters="sRGB">
<feFlood flood-opacity="0" result="BackgroundImageFix"/>
<feBlend mode="normal" in="SourceGraphic" in2="BackgroundImageFix" result="shape"/>
<feGaussianBlur stdDeviation="1" result="effect1_foregroundBlur_1298_12516"/>
</filter>
<clipPath id="paint0_angular_1298_12516_clip_path"><path d="M7.25922 19.7927C7.25922 12.6759 13.0209 6.90668 20.1283 6.90668C27.2357 6.90668 32.9973 12.6759 32.9973 19.7927C32.9973 26.9094 27.2357 32.6786 20.1283 32.6786C13.0209 32.6786 7.25921 26.9094 7.25922 19.7927Z"/></clipPath><filter id="filter1_f_1298_12516" x="12.9977" y="14.828" width="14.1038" height="10.8265" filterUnits="userSpaceOnUse" color-interpolation-filters="sRGB">
<feFlood flood-opacity="0" result="BackgroundImageFix"/>
<feBlend mode="normal" in="SourceGraphic" in2="BackgroundImageFix" result="shape"/>
<feGaussianBlur stdDeviation="1" result="effect1_foregroundBlur_1298_12516"/>
</filter>
<filter id="filter2_f_1298_12516" x="23.9146" y="13.1219" width="18.8784" height="10.1871" filterUnits="userSpaceOnUse" color-interpolation-filters="sRGB">
<feFlood flood-opacity="0" result="BackgroundImageFix"/>
<feBlend mode="normal" in="SourceGraphic" in2="BackgroundImageFix" result="shape"/>
<feGaussianBlur stdDeviation="1" result="effect1_foregroundBlur_1298_12516"/>
</filter>
<filter id="filter3_f_1298_12516" x="15.8659" y="11.8415" width="18.8171" height="8.7561" filterUnits="userSpaceOnUse" color-interpolation-filters="sRGB">
<feFlood flood-opacity="0" result="BackgroundImageFix"/>
<feBlend mode="normal" in="SourceGraphic" in2="BackgroundImageFix" result="shape"/>
<feGaussianBlur stdDeviation="1" result="effect1_foregroundBlur_1298_12516"/>
</filter>
<filter id="filter4_f_1298_12516" x="20.1341" y="8.54878" width="18.8171" height="8.7561" filterUnits="userSpaceOnUse" color-interpolation-filters="sRGB">
<feFlood flood-opacity="0" result="BackgroundImageFix"/>
<feBlend mode="normal" in="SourceGraphic" in2="BackgroundImageFix" result="shape"/>
<feGaussianBlur stdDeviation="1" result="effect1_foregroundBlur_1298_12516"/>
</filter>
<filter id="filter5_f_1298_12516" x="13.9756" y="14.7683" width="21.0122" height="10.2195" filterUnits="userSpaceOnUse" color-interpolation-filters="sRGB">
<feFlood flood-opacity="0" result="BackgroundImageFix"/>
<feBlend mode="normal" in="SourceGraphic" in2="BackgroundImageFix" result="shape"/>
<feGaussianBlur stdDeviation="1" result="effect1_foregroundBlur_1298_12516"/>
</filter>
<filter id="filter6_f_1298_12516" x="19.0404" y="9.00419" width="12.2878" height="10.0309" filterUnits="userSpaceOnUse" color-interpolation-filters="sRGB">
<feFlood flood-opacity="0" result="BackgroundImageFix"/>
<feBlend mode="normal" in="SourceGraphic" in2="BackgroundImageFix" result="shape"/>
<feGaussianBlur stdDeviation="1" result="effect1_foregroundBlur_1298_12516"/>
</filter>
</defs>`

/**
 * 로고 마크업의 **정규 형태**. 그림이 같으면 같은 자료구조가 나와야 한다.
 *
 * 무엇이 그림을 정하는가로 갈랐다.
 *
 * | 정한다 → 비교한다 | 안 정한다 → 버린다 |
 * |---|---|
 * | 요소 이름과 중첩 순서 | 요소 **사이의 공백**(줄바꿈·들여쓰기) |
 * | 속성 **이름과 값** | 속성이 적힌 **순서** |
 * | 눈에 보이는 텍스트 | 자기 닫는 태그 표기(`<path/>` vs `<path></path>`) |
 *
 * **문자열로 잇지 않는다. 경계를 자료구조가 갖는다.**
 *
 * 전에는 속성을 `이름=값`으로 만들어 공백으로 이었는데, 그러면 **값 안에 구분자를 넣어
 * 경계를 위조할 수 있다.** 예를 들어 `cx="20.0496" cy="20.2413"`를
 * `cx="20.0496 cy=20.2413"` 한 개로 바꾸면 — `cy`가 사라지고 `cx`도 무효값이 되어 타원이
 * 딴 데로 가는데 — 이어 붙인 문자열은 원본과 **똑같아진다.**
 *
 * escape로는 못 막는다. 무엇을 escape 문자로 정하든 그 문자로 다시 위조할 수 있다.
 * 태그·속성 튜플·자식 배열을 중첩 배열로 두고 `toEqual()`로 비교하면 값 안의 공백·`=`·`,`가
 * 경계를 넘지 못한다 — 경계가 문자열이 아니라 배열의 칸이기 때문이다.
 *
 * 속성 **값**은 한 글자도 손대지 않는다 — `d`의 좌표, `fill`의 색, `stdDeviation`의 흐림
 * 세기, `style` 안의 그라디언트가 전부 값이라 바뀌면 그대로 드러난다.
 *
 * 양쪽을 같은 파서에 태우므로 jsdom이 판올림돼도 둘이 같이 달라져 거짓 실패가 없다.
 */
type LogoNode =
  | { text: string }
  | { tag: string; attributes: [string, string][]; children: LogoNode[] }

function canonical(markup: string): LogoNode[] {
  const host = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
  host.innerHTML = markup

  const walk = (node: Node): LogoNode | null => {
    if (node.nodeType === Node.TEXT_NODE) {
      // 공백뿐인 줄바꿈·들여쓰기는 그림을 바꾸지 않는다.
      const text = node.textContent?.trim() ?? ''
      return text === '' ? null : { text }
    }
    if (node.nodeType !== Node.ELEMENT_NODE) return null

    const element = node as Element
    return {
      tag: element.tagName,
      attributes: [...element.attributes]
        .map((attribute): [string, string] => [attribute.name, attribute.value])
        .sort(([left], [right]) => (left < right ? -1 : left > right ? 1 : 0)),
      children: [...element.childNodes].map(walk).filter(isNode),
    }
  }

  return [...host.childNodes].map(walk).filter(isNode)
}

function isNode(node: LogoNode | null): node is LogoNode {
  return node !== null
}
