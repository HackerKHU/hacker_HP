import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, useLocation, useNavigate } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/App'
import { ApiError } from '@/api/client'
import type { User } from '@/api/types'
import { SessionProvider } from '@/auth/session'
import { CLUB, FAQS } from './content'
import { PublicHeader } from './PublicHeader'

const auth = vi.hoisted(() => ({
  me: (): Promise<User> =>
    Promise.reject(new Error('테스트가 지정하지 않았다')),
}))

vi.mock('@/api/auth', () => ({
  getMe: () => auth.me(),
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

/**
 * `LandingPage`를 직접 그리지 않고 **`/`로 앱을 띄운다.**
 *
 * 컴포넌트를 직접 렌더하면 라우트 배선을 건너뛴다 — `/`가 가드 뒤로 들어가도
 * 테스트는 계속 통과한다. T-57~T-61이 확인하려는 건 "가드에 걸리지 않는다"이므로
 * 실제 경로로 도달하는지까지 봐야 의미가 있다.
 */
function renderLanding() {
  render(
    <MemoryRouter initialEntries={['/']}>
      <SessionProvider>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  )
}

function HistoryOutsideHeader() {
  const { pathname } = useLocation()
  const navigate = useNavigate()

  return (
    <>
      <output aria-label="현재 경로">{pathname}</output>
      <button type="button" onClick={() => navigate(-1)}>
        브라우저 뒤로
      </button>
      <button type="button" onClick={() => navigate(1)}>
        브라우저 앞으로
      </button>
    </>
  )
}

function renderPublicHeaderWithHistory() {
  render(
    <MemoryRouter initialEntries={['/previous', '/']} initialIndex={1}>
      <SessionProvider>
        <PublicHeader />
        <HistoryOutsideHeader />
      </SessionProvider>
    </MemoryRouter>,
  )
}

let fetchSpy: ReturnType<typeof vi.fn>

beforeEach(() => {
  auth.me = () => Promise.reject(new Error('비로그인'))
  /*
   * 이 파일은 `@/api/auth`를 목킹하므로 세션 확인조차 나가지 않는다. 따라서 여기서는
   * fetch가 **0건**이어야 하고, 불리면 목킹을 우회한 요청이라는 뜻이라 즉시 실패시킨다.
   * T-60의 실제 검증(세션 확인 1회만 허용)은 목킹하지 않는 `LandingNetwork.test.tsx`가 한다.
   */
  fetchSpy = vi.fn(() =>
    Promise.reject(new Error('랜딩은 fetch를 부르지 않는다')),
  )
  vi.stubGlobal('fetch', fetchSpy)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

/**
 * 랜딩 헤더의 계정 메뉴를 연다. **앱 헤더와 같은 컴포넌트다** (#178).
 *
 * Radix 메뉴는 `pointerdown`으로 열린다 — `click`만 쏘면 안 열린다. jsdom에 `PointerEvent`가
 * 없어 `MouseEvent`로 대신 만든다 (`MemberListPage.test.tsx`와 같은 방식).
 */
async function openAccountMenu() {
  const trigger = await screen.findByRole('button', { name: '계정 메뉴' })
  fireEvent.pointerDown(
    trigger,
    new MouseEvent('pointerdown', { bubbles: true, button: 0 }),
  )
  return await screen.findByRole('menu')
}

describe('공개 랜딩', () => {
  /*
   * T-57~T-59, T-61 — 어느 세션 상태에서도 가드에 걸리지 않고 그대로 렌더된다.
   *
   * SUSPENDED가 특히 중요하다. 프론트엔드는 정지된 세션을 **세션 없음으로 수렴시켜**
   * 로그인 화면으로 보내는데(#36), 그 정책이 랜딩까지 적용되면 정지된 사람이 공개
   * 페이지조차 못 본다 (spec 5-TESTING).
   */
  it.each([
    ['비로그인', null],
    ['PENDING', { ...BASE, status: 'PENDING' as const, approvedAt: null }],
    ['ACTIVE', BASE],
    ['SUSPENDED', { ...BASE, status: 'SUSPENDED' as const }],
  ])('%s 상태에서 랜딩이 렌더된다', async (_label, user) => {
    auth.me = () =>
      user ? Promise.resolve(user) : Promise.reject(new Error('비로그인'))

    renderLanding()

    // 제목은 두 줄이 의도된 줄바꿈이라 블록 두 개다. 공백 처리에 기대지 않고
    // 두 줄이 모두 들어 있는지로 본다.
    const heading = await screen.findByRole('heading', { level: 1 })
    for (const line of CLUB.headline) {
      expect(heading).toHaveTextContent(line)
    }
    expect(screen.getByRole('heading', { name: '소개' })).toBeInTheDocument()
    // 가드에 걸려 로그인 화면으로 튕기지 않았는지도 본다.
    expect(screen.queryByRole('heading', { name: '로그인' })).toBeNull()
  })

  /*
   * 근사치와 확정값이 화면에서 구분되어야 한다. 전부 `+`가 붙으면 확정된 창립 연차까지
   * 추정처럼 읽히고, 아무 데도 안 붙으면 근사치가 정확한 집계처럼 읽힌다.
   */
  /*
   * #174 — 기록 그리드와 푸터는 모바일에서 접힌다. jsdom은 레이아웃을 계산하지 않으므로
   * 실제 줄바꿈은 못 보고, 반응형 클래스가 빠지는 회귀만 지킨다 — 이 클래스가 사라지면
   * 390px에서 숫자 네 개가 4열에 눌려 잘린다.
   */
  it('기록 그리드와 푸터에 모바일 대응 클래스가 있다', async () => {
    renderLanding()

    const grid = (await screen.findByText('함께한 시간')).closest('dl')
    expect(grid?.className).toContain('grid-cols-2')
    expect(grid?.className).toContain('md:grid-cols-4')

    const footer = screen.getByRole('contentinfo').firstElementChild
    expect(footer?.className).toContain('flex-col')
    expect(footer?.className).toContain('sm:flex-row')
  })

  /*
   * **푸터에 섹션 앵커를 두지 않는다** (#304). 헤더가 이미 같은 다섯을 그려서, 한 페이지
   * 안에 같은 앵커가 위아래로 두 벌이었다. 푸터까지 내려온 사람은 그 섹션들을 지나온
   * 사람이라 되돌아갈 길을 여기서 다시 줄 이유가 적다.
   *
   * 주소·인스타그램·법적 문서는 남는다 — 어느 화면에서든 있어야 하는 것들이다.
   */
  it('푸터가 내부 화면과 같은 링크 셋을 그린다', async () => {
    renderLanding()

    await screen.findByRole('heading', { name: '소개' })
    const footer = screen.getByRole('contentinfo')

    expect(
      within(footer)
        .getAllByRole('link')
        .map((link) => link.textContent),
    ).toEqual(['인스타그램', '개인정보처리방침', '이용약관'])
    // 주소는 링크가 아니라 글이라 위 목록에 없다. 사라지지 않았는지는 따로 본다.
    expect(footer).toHaveTextContent(CLUB.fullName)
  })

  it('근사 표기를 켠 수치에만 +가 붙는다', async () => {
    renderLanding()

    const cumulative = (await screen.findByText('누적 활동 회원')).closest(
      'div',
    )
    expect(cumulative).toHaveTextContent('2000+명')

    const years = screen.getByText('함께한 시간').closest('div')
    expect(years).toHaveTextContent('39년')
    expect(years).not.toHaveTextContent('+')
  })

  // 주소가 없는 mailto는 빈 메일 창만 띄운다. 위와 같은 이유로 조건 분기 없이 못박는다.
  /*
   * 주소가 들어왔으므로 잠금이 풀렸다 (#194). 잠긴 버튼을 기대하던 자리다.
   *
   * **주소를 `SUPPORT.email`에서 읽어 비교하지 않는다** — 구현과 같은 값을 보면 상수가
   * 자리표시자로 되돌아가도 테스트가 따라가서 회귀를 못 잡는다. 지금 값으로 못박는다.
   */
  it('후원 문의하기가 공식 메일로 열린다', async () => {
    renderLanding()

    const link = await screen.findByRole('link', { name: '후원 문의하기' })
    // 부분 일치로 보면 주소 뒤에 뭐가 붙어도 통과한다. 주소 자체를 못박는다.
    expect(link.getAttribute('href')).toMatch(
      /^mailto:hacker19870101@gmail\.com(\?|$)/,
    )
    expect(screen.queryByRole('button', { name: '후원 문의하기' })).toBeNull()
  })

  it('FAQ 항목을 누르면 답이 펼쳐진다', async () => {
    renderLanding()

    const first = FAQS[0]
    const trigger = await screen.findByRole('button', { name: first.question })
    expect(screen.queryByText(first.answer)).toBeNull()

    fireEvent.click(trigger)

    expect(await screen.findByText(first.answer)).toBeInTheDocument()
  })
})
/**
 * 부원 화면 링크 — **[라벨, 주소] 쌍이다.**
 *
 * `PublicHeader`에서 가져오지 않는다. 화면 코드가 목록을 잘못 바꿔도 같이 틀린 값을
 * 비교하게 되어 아무것도 잡지 못한다 — 여기 적힌 것이 계약이고, 어긋나면 이 파일이 진다.
 */
const MEMBER_LINKS: [string, string][] = [
  ['공지사항', '/notices'],
  ['자료게시판', '/notes'],
  ['자유게시판', '/posts'],
  ['갤러리', '/photos'],
]

const PUBLIC_LINKS: [string, string][] = [
  ['소개', '#about'],
  ['활동', '#activities'],
  ['기록', '#stats'],
  ['FAQ', '#faq'],
  ['후원', '#support'],
]

const ADMIN_LINKS: [string, string][] = [
  ...MEMBER_LINKS,
  ['회원 관리', '/admin/members'],
]

function expectNavigationLinks(
  navigation: HTMLElement,
  expectedLinks: [string, string][],
) {
  expect(
    within(navigation)
      .getAllByRole('link')
      .map((link) => [link.textContent, link.getAttribute('href')]),
  ).toEqual(expectedLinks)
}

describe('랜딩 헤더 상태별 진입점', () => {
  /*
   * 정지된 계정에는 지원하기를 보이지 않는다 (#194 검수). 그 계정은 로그인이 막혀 있어
   * 눌러도 정지 안내만 뜬다 — 목적을 못 이루는 CTA를 강조색으로 두면 거짓말이 된다.
   */
  it('정지된 계정에는 지원하기가 없고 로그인만 남는다', async () => {
    auth.me = () =>
      Promise.reject(new ApiError('SUSPENDED', 403, '정지된 계정입니다.'))

    renderLanding()

    expect(
      await screen.findByRole('link', { name: '로그인' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '지원하기' })).toBeNull()
    expect(screen.queryByRole('button', { name: '지원하기' })).toBeNull()
  })

  it('비로그인에게는 지원하기와 로그인만 보인다', async () => {
    auth.me = () => Promise.reject(new Error('비로그인'))

    renderLanding()
    const login = await screen.findByRole('link', {
      name: '로그인',
      hidden: true,
    })
    expect(login.className).toContain('hidden')
    expect(login.className).toContain('md:inline-flex')

    /*
     * **지원은 이 사이트에서 받는다** (#194). 외부 모집 폼을 두지 않으므로 잠긴 버튼도
     * 새 탭도 아니고, 로그인과 같은 곳으로 가는 라우트 링크다.
     *
     * 목적지가 같아도 **버튼은 둘이어야 한다** — 처음 온 사람에게 "로그인"은 계정이
     * 있는 사람의 말이라, 지원하러 온 사람이 자기 자리를 못 찾는다.
     */
    const apply = screen.getByRole('link', { name: '지원하기' })
    expect(apply).toHaveAttribute('href', '/login')
    expect(apply).not.toHaveAttribute('target')
    expect(apply.className).toContain('h-11')
    expect(apply.className).toContain('px-3')
    expect(screen.queryByRole('button', { name: '지원하기' })).toBeNull()
    // 비로그인 헤더는 그대로다 — 계정 메뉴는 로그인한 사람에게만 있다.
    expect(screen.queryByRole('button', { name: '계정 메뉴' })).toBeNull()

    // guest 로그인만 접고 지원하기는 가운데 열의 첫 줄에 남긴다.
    const actions = apply.parentElement
    expect(actions?.className).toContain('row-start-1')
    expect(actions?.className).toContain('col-start-2')
    expect(actions?.nextElementSibling).toBe(
      screen.getByRole('button', { name: '메뉴 열기' }),
    )
  })

  // spec §3-1-4 — PENDING 안에 두 상태가 있다. 신청도 안 한 사람에게
  // "승인 대기 중"은 거짓말이다. 그 사람은 기다리는 게 아니라 아직 아무것도 안 했다.
  it('로그인만 하고 신청하지 않았으면 사이트 신청 화면으로 보낸다', async () => {
    auth.me = () =>
      Promise.resolve({
        ...BASE,
        status: 'PENDING' as const,
        studentNo: null,
        appliedAt: null,
        approvedAt: null,
      })

    renderLanding()

    expect(
      await screen.findByRole('link', { name: '지원하기' }),
    ).toHaveAttribute('href', '/pending')
    expect(screen.queryByRole('link', { name: '승인 대기 중' })).toBeNull()
    /*
     * `PENDING`에게는 마이페이지 항목이 없다 (spec §3-1-3 매트릭스). 메뉴 자체는 열린다 —
     * 로그아웃이 그 안에 있고, 매트릭스에서 로그아웃은 `PENDING`도 `O`다.
     */
    const menu = await openAccountMenu()
    expect(
      within(menu)
        .getAllByRole('menuitem')
        .map((item) => item.textContent),
    ).toEqual(['로그아웃'])
  })

  it('신청서를 낸 PENDING에게는 승인 대기 중이 보인다', async () => {
    auth.me = () =>
      Promise.resolve({
        ...BASE,
        status: 'PENDING' as const,
        approvedAt: null,
      })

    renderLanding()

    expect(
      await screen.findByRole('link', { name: '승인 대기 중' }),
    ).toHaveAttribute('href', '/pending')
    expect(screen.queryByRole('link', { name: '지원하기' })).toBeNull()
    const menu = await openAccountMenu()
    expect(
      within(menu)
        .getAllByRole('menuitem')
        .map((item) => item.textContent),
    ).toEqual(['로그아웃'])
  })

  /*
   * 공지사항은 부원이 오가는 **목적지**라 섹션 메뉴와 한 덩어리로 읽혀야 한다. 로그아웃
   * 옆으로 돌아가면 계정 조작 사이에 목적지가 끼어 보인다 (#148).
   *
   * 다만 `섹션 이동` 내비 안에 넣지는 않는다 — 그 이름 아래 라우트 링크가 들어가면
   * 스크린리더에게 거짓말이 된다. 묶이되 안에 들어가지는 않는다는 두 가지를 함께 본다.
   */
  /*
   * #176 — 모바일에서는 섹션 메뉴가 햄버거 뒤로 접힌다. jsdom은 뷰포트가 없으므로
   * "무엇이 보이는가"가 아니라 열림·닫힘 동작만 본다. 항목을 누르면 닫혀야 한다 —
   * 앵커는 페이지를 안 바꿔서 저절로 닫히지 않고, 열린 채 두면 이동한 섹션을 가린다.
   */
  /*
   * #192 — 랜딩에 있는 동안만 html 배경·theme-color가 다크다. jsdom은 CSS를 계산하지
   * 않아 실제 색은 못 읽으므로 getComputedStyle을 대역으로 세워 마운트/언마운트 대칭만
   * 본다 — 되돌리지 않으면 라이트인 내부 화면까지 검은 크롬을 물려받는다.
   */
  /*
   * #192 — 랜딩에 있는 동안만 `html`이 다크다. 처음 고칠 때 `html` 배경만 인라인으로
   * 칠했더니 흰 띠가 그대로였다 — `body`가 `:root`(라이트) 토큰을 읽어 덮었기 때문이다.
   * 그래서 클래스를 걸어 토큰째 뒤집는다. 되돌리지 않으면 라이트인 내부 화면까지
   * 검은 크롬을 물려받는다.
   */
  /*
   * 원래 다크였거나 theme-color가 이미 있던 문서는 **우리가 만든 것이 아니므로 남긴다.**
   * 무조건 지우면 남의 설정을 랜딩 한 번 들렀다고 날린다.
   */
  it('원래 있던 다크와 theme-color는 건드리지 않는다', () => {
    document.documentElement.classList.add('dark')
    const mine = document.createElement('meta')
    mine.name = 'theme-color'
    mine.content = '#123456'
    document.head.appendChild(mine)

    const { unmount } = render(
      <MemoryRouter initialEntries={['/']}>
        <SessionProvider>
          <App />
        </SessionProvider>
      </MemoryRouter>,
    )
    unmount()

    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(
      document
        .querySelector('meta[name="theme-color"]')
        ?.getAttribute('content'),
    ).toBe('#123456')

    document.documentElement.classList.remove('dark')
    mine.remove()
  })

  it('랜딩을 떠나면 html의 다크와 theme-color를 되돌린다', () => {
    expect(document.documentElement.classList.contains('dark')).toBe(false)

    const { unmount } = render(
      <MemoryRouter initialEntries={['/']}>
        <SessionProvider>
          <App />
        </SessionProvider>
      </MemoryRouter>,
    )
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(document.querySelector('meta[name="theme-color"]')).not.toBeNull()

    unmount()
    expect(document.documentElement.classList.contains('dark')).toBe(false)
    expect(document.querySelector('meta[name="theme-color"]')).toBeNull()
  })

  /*
   * 헤더는 가로로 긴 자리라 **가로 락업**이다. 세로 락업을 넣으면 높이가 눌려 글자가
   * 안 읽힌다. 랜딩은 `.dark`라 잉크는 흰색이어야 하고, 바뀌면 배경에 묻힌다.
   */
  it('헤더에 가로 락업을 쓴다', async () => {
    renderLanding()

    const logo = await screen.findByAltText(CLUB.name)
    expect(logo).toHaveAttribute(
      'src',
      '/brand/lockup-horizontal-white-512.png',
    )
    expect(logo.className).toContain('h-8')
    expect(logo.className).toContain('w-[27px]')
    expect(logo.className).toContain('md:w-auto')

    const source = logo.closest('picture')?.querySelector('source')
    expect(source).toHaveAttribute('media', '(max-width: 767px)')
    expect(source).toHaveAttribute('srcset', '/brand/mark-white-512.png')
  })

  it('햄버거가 섹션 메뉴를 열고 항목을 누르면 닫는다', async () => {
    renderLanding()

    const toggle = await screen.findByRole('button', { name: '메뉴 열기' })
    expect(toggle.className).toContain('size-11')
    expect(toggle).toHaveAttribute('aria-controls', 'public-mobile-menu')
    expect(toggle).toHaveAttribute('aria-expanded', 'false')

    fireEvent.click(toggle)
    const menu = document.getElementById('public-mobile-menu')
    expect(menu).not.toBeNull()
    expect(screen.getByRole('button', { name: '메뉴 닫기' })).toHaveAttribute(
      'aria-expanded',
      'true',
    )
    expect(
      within(menu as HTMLElement).getByRole('link', { name: '로그인' }),
    ).toHaveAttribute('href', '/login')

    fireEvent.click(
      within(menu as HTMLElement).getByRole('link', { name: '소개' }),
    )
    expect(document.getElementById('public-mobile-menu')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: '메뉴 열기' }))
    fireEvent.click(screen.getByRole('button', { name: '메뉴 닫기' }))
    expect(document.getElementById('public-mobile-menu')).toBeNull()

    const reopen = screen.getByRole('button', { name: '메뉴 열기' })
    fireEvent.click(reopen)
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(document.getElementById('public-mobile-menu')).toBeNull()
    expect(reopen).toHaveFocus()
  })

  it('history 이동으로 위치가 바뀌면 초점을 옮기지 않고 닫는다', async () => {
    renderPublicHeaderWithHistory()

    fireEvent.click(await screen.findByRole('button', { name: '메뉴 열기' }))
    expect(document.getElementById('public-mobile-menu')).not.toBeNull()

    const back = screen.getByRole('button', { name: '브라우저 뒤로' })
    back.focus()
    fireEvent.click(back)

    expect(screen.getByLabelText('현재 경로')).toHaveTextContent('/previous')
    expect(document.getElementById('public-mobile-menu')).toBeNull()
    expect(back).toHaveFocus()
    expect(screen.getByRole('button', { name: '메뉴 열기' })).not.toHaveFocus()

    const forward = screen.getByRole('button', { name: '브라우저 앞으로' })
    forward.focus()
    fireEvent.click(forward)

    expect(screen.getByLabelText('현재 경로')).toHaveTextContent(/^\/$/)
    expect(document.getElementById('public-mobile-menu')).toBeNull()
    expect(forward).toHaveFocus()
    expect(screen.getByRole('button', { name: '메뉴 열기' })).not.toHaveFocus()
  })

  /*
   * #305 — 공개 앵커와 부원 메뉴를 고르는 판단은 세션 상태 하나에서 시작하고, 데스크톱과
   * 모바일은 그 결과를 같이 쓴다. 이 표가 상태 하나를 빠뜨리면 `active` 유니온에 함께
   * 들어오는 `INACTIVE`처럼 이름과 실제 계정 상태가 다른 갈래에서 회귀가 남는다.
   */
  it.each([
    {
      label: 'loading',
      getMe: () => new Promise<User>(() => undefined),
      expectedLinks: null,
      navigationName: null,
    },
    {
      label: 'guest',
      getMe: () => Promise.reject(new Error('비로그인')),
      expectedLinks: PUBLIC_LINKS,
      navigationName: '섹션 이동',
    },
    {
      label: 'pending 신청 전',
      getMe: () =>
        Promise.resolve({
          ...BASE,
          status: 'PENDING' as const,
          studentNo: null,
          appliedAt: null,
          approvedAt: null,
        }),
      expectedLinks: PUBLIC_LINKS,
      navigationName: '섹션 이동',
    },
    {
      label: 'pending 신청 후',
      getMe: () =>
        Promise.resolve({
          ...BASE,
          status: 'PENDING' as const,
          approvedAt: null,
        }),
      expectedLinks: PUBLIC_LINKS,
      navigationName: '섹션 이동',
    },
    {
      label: 'pending 사용자 정보 없음',
      getMe: () =>
        Promise.reject(
          new ApiError('PENDING_APPROVAL', 403, '승인 대기 중인 계정입니다.'),
        ),
      expectedLinks: PUBLIC_LINKS,
      navigationName: '섹션 이동',
    },
    {
      label: 'suspended',
      getMe: () =>
        Promise.reject(new ApiError('SUSPENDED', 403, '정지된 계정입니다.')),
      expectedLinks: PUBLIC_LINKS,
      navigationName: '섹션 이동',
    },
    {
      label: 'inactive USER',
      getMe: () => Promise.resolve({ ...BASE, status: 'INACTIVE' as const }),
      expectedLinks: MEMBER_LINKS,
      navigationName: '주요 메뉴',
    },
    {
      label: 'active USER',
      getMe: () => Promise.resolve(BASE),
      expectedLinks: MEMBER_LINKS,
      navigationName: '주요 메뉴',
    },
    {
      label: 'active ADMIN',
      getMe: () => Promise.resolve({ ...BASE, role: 'ADMIN' as const }),
      expectedLinks: ADMIN_LINKS,
      navigationName: '주요 메뉴',
    },
  ])(
    '$label의 데스크톱과 모바일 메뉴가 같은 배타적 목록을 쓴다',
    async ({ label, getMe, expectedLinks, navigationName }) => {
      auth.me = getMe

      renderLanding()
      await screen.findByAltText(CLUB.name)

      if (expectedLinks === null || navigationName === null) {
        expect(
          screen.queryByRole('navigation', { name: '섹션 이동' }),
        ).not.toBeInTheDocument()
        expect(
          screen.queryByRole('navigation', { name: '주요 메뉴' }),
        ).not.toBeInTheDocument()
        expect(
          screen.queryByRole('button', { name: '메뉴 열기' }),
        ).not.toBeInTheDocument()
        // 오른쪽의 기존 loading 처리도 그대로여야 한다.
        expect(screen.queryByRole('link', { name: '로그인' })).toBeNull()
        expect(screen.queryByRole('button', { name: '계정 메뉴' })).toBeNull()
        return
      }

      const toggle = await screen.findByRole('button', { name: '메뉴 열기' })
      const desktopNavigation = screen.getByRole('navigation', {
        name: navigationName,
      })
      expectNavigationLinks(desktopNavigation, expectedLinks)

      const otherNavigationName =
        navigationName === '섹션 이동' ? '주요 메뉴' : '섹션 이동'
      expect(
        screen.queryByRole('navigation', { name: otherNavigationName }),
      ).not.toBeInTheDocument()

      fireEvent.click(toggle)
      const mobileMenu = document.getElementById(
        'public-mobile-menu',
      ) as HTMLElement
      const mobileNavigation = within(mobileMenu).getByRole('navigation', {
        name: navigationName,
      })
      expectNavigationLinks(mobileNavigation, expectedLinks)

      const desktopAdmin = within(desktopNavigation).queryByRole('link', {
        name: '회원 관리',
      })
      const mobileAdmin = within(mobileNavigation).queryByRole('link', {
        name: '회원 관리',
      })

      if (label === 'active ADMIN') {
        expect(desktopAdmin?.previousElementSibling?.tagName).toBe('SPAN')
        expect(desktopAdmin?.previousElementSibling).toHaveAttribute(
          'aria-hidden',
          'true',
        )
        expect(mobileAdmin?.previousElementSibling?.tagName).toBe('DIV')
        expect(mobileAdmin?.previousElementSibling).toHaveAttribute(
          'aria-hidden',
          'true',
        )
      } else {
        expect(desktopAdmin).toBeNull()
        expect(mobileAdmin).toBeNull()
        expect(
          desktopNavigation.querySelector('[aria-hidden="true"]'),
        ).toBeNull()
        expect(
          mobileNavigation.querySelector('[aria-hidden="true"]'),
        ).toBeNull()
      }
    },
  )

  /*
   * **모바일 메뉴가 데스크톱과 같은 목록을 그린다** (#251). 두 곳에 따로 적으면 화면이
   * 늘 때마다 한쪽만 고치게 되고, 그 어긋남은 좁은 화면에서만 드러나 오래 남는다 —
   * 자료게시판·갤러리가 생기고도 랜딩에는 공지사항만 있던 것이 그 예다.
   */
  it('부원이면 모바일 메뉴에도 내부 메뉴가 전부 있다', async () => {
    auth.me = () => Promise.resolve(BASE)

    renderLanding()
    // 세션 확인이 끝나 데스크톱 헤더에 링크가 뜬 뒤에 연다.
    await screen.findAllByRole('link', { name: '공지사항' })

    fireEvent.click(screen.getByRole('button', { name: '메뉴 열기' }))
    const menu = document.getElementById('public-mobile-menu') as HTMLElement

    for (const [label, href] of MEMBER_LINKS) {
      expect(within(menu).getByRole('link', { name: label })).toHaveAttribute(
        'href',
        href,
      )
    }
    // 데스크톱과 같은 규칙 — 부원에게는 섹션 앵커를 그리지 않는다 (#306).
    expect(
      within(menu).queryByRole('navigation', { name: '섹션 이동' }),
    ).not.toBeInTheDocument()
  })

  /*
   * **비로그인과 `PENDING`은 그대로 섹션 앵커다** (#306).
   *
   * 그 다섯이 이 페이지를 읽는 순서이고, 부원 화면 링크는 눌러도 가드가 로그인·대기
   * 화면으로 되돌린다 (spec §3-1-3) — **누르기 전에는 그렇게 될 줄 모른다.**
   */
  it.each([
    ['비로그인', null],
    ['PENDING', { ...BASE, status: 'PENDING' as const, approvedAt: null }],
  ])('%s에게는 섹션 앵커 다섯 개가 그대로 있다', async (_label, user) => {
    auth.me = () =>
      user ? Promise.resolve(user) : Promise.reject(new Error('비로그인'))

    renderLanding()

    const nav = await screen.findByRole('navigation', { name: '섹션 이동' })
    expect(
      within(nav)
        .getAllByRole('link')
        .map((link) => link.textContent),
    ).toEqual(['소개', '활동', '기록', 'FAQ', '후원'])

    // 내부 메뉴는 그리지 않는다.
    for (const [label] of MEMBER_LINKS) {
      expect(
        screen.queryByRole('link', { name: label }),
      ).not.toBeInTheDocument()
    }
  })

  /*
   * **부원에게는 내부 화면과 같은 메뉴만 보여준다** (#306).
   *
   * 로그인한 부원에게 랜딩은 읽을 페이지가 아니라 지나가는 자리다. 섹션 앵커 다섯 개
   * 뒤에 부원 화면 넷이 밀려 있으면, 두 화면을 오갈 때 헤더가 아홉 칸에서 넷으로 줄어든다.
   *
   * **`nav`의 이름도 내부 헤더와 같다** — `섹션 이동`이 아니라 `주요 메뉴`다. 라우트
   * 링크가 `섹션 이동` 아래 들어가면 스크린리더에게 거짓말이 된다.
   */
  it('부원에게는 섹션 앵커 대신 내부 메뉴가 뜬다', async () => {
    auth.me = () => Promise.resolve(BASE)

    renderLanding()
    await screen.findAllByRole('link', { name: '공지사항' })

    expect(
      screen.queryByRole('navigation', { name: '섹션 이동' }),
    ).not.toBeInTheDocument()

    const nav = screen.getByRole('navigation', { name: '주요 메뉴' })
    expect(
      within(nav)
        .getAllByRole('link')
        .map((link) => link.textContent),
    ).toEqual(MEMBER_LINKS.map(([label]) => label))
  })

  /*
   * **구분선이 랜딩에도 따라온다** (#307). 두 헤더가 같은 목록을 쓰므로(#306) 관리자가
   * 랜딩에서 보는 메뉴도 같고, 구분선을 목록에 얹었으니 여기서도 그려져야 한다 —
   * 랜딩 쪽에 따로 넣었다면 그 둘은 한쪽만 고쳐진다.
   */
  it('관리자에게는 랜딩에서도 회원 관리 앞에 구분선이 있다', async () => {
    auth.me = () => Promise.resolve({ ...BASE, role: 'ADMIN' as const })

    renderLanding()

    const admin = await screen.findByRole('link', { name: '회원 관리' })
    const divider = admin.previousElementSibling
    expect(divider).toHaveAttribute('aria-hidden', 'true')
  })

  it('ACTIVE에게는 부원 화면 링크와 계정 메뉴가 보인다', async () => {
    auth.me = () => Promise.resolve(BASE)

    renderLanding()
    await screen.findByRole('link', { name: '공지사항' })

    for (const [label, href] of MEMBER_LINKS) {
      expect(screen.getByRole('link', { name: label })).toHaveAttribute(
        'href',
        href,
      )
    }
    expect(screen.queryByRole('link', { name: '지원하기' })).toBeNull()

    /*
     * **앱 헤더와 같은 계정 메뉴다** (#178). 랜딩에서 부원 화면으로 넘어갈 때 같은 자리에
     * 같은 아이콘·같은 항목이 있어야 한다 — 복사해 두면 한쪽만 고쳐진다.
     */
    const menu = await openAccountMenu()
    expect(
      within(menu)
        .getAllByRole('menuitem')
        .map((item) => item.textContent),
    ).toEqual(['마이페이지', '로그아웃'])
    expect(
      within(menu).getByRole('menuitem', { name: '마이페이지' }),
    ).toHaveAttribute('href', '/me')
  })

  /*
   * **비로그인에게는 하나도 보이지 않는다** (#251). 셋 다 `ACTIVE` 전용 화면이라
   * (spec §3-1-3) 누르면 가드가 로그인으로 보내는데, **누르기 전에는 그렇게 될 줄
   * 모른다.** 랜딩을 처음 보는 사람의 다음 행동은 지원하기·로그인이고, 튕겨 나갈 링크를
   * 그 옆에 늘어놓으면 무엇을 눌러야 하는지 흐려진다.
   */
  it('비로그인에게는 부원 화면 링크가 하나도 없다', async () => {
    auth.me = () => Promise.reject(new Error('비로그인'))

    renderLanding()
    await screen.findByRole('link', { name: '지원하기' })

    for (const [label] of MEMBER_LINKS) {
      expect(screen.queryByRole('link', { name: label })).toBeNull()
    }
  })

  /* `PENDING`도 마찬가지다 — 그 계정이 볼 수 있는 인증 화면은 신청·대기뿐이다 (§3-1-6). */
  it('PENDING에게도 부원 화면 링크가 없다', async () => {
    auth.me = () =>
      Promise.resolve({
        ...BASE,
        status: 'PENDING' as const,
        approvedAt: null,
      })

    renderLanding()
    await screen.findByRole('link', { name: '승인 대기 중' })

    for (const [label] of MEMBER_LINKS) {
      expect(screen.queryByRole('link', { name: label })).toBeNull()
    }
  })
})
