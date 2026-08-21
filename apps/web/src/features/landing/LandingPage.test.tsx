import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/App'
import type { User } from '@/api/types'
import { SessionProvider } from '@/auth/session'
import { CLUB, FAQS } from './content'

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
    expect(link).toHaveAttribute(
      'href',
      expect.stringContaining('mailto:hacker19870101@gmail.com'),
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
describe('랜딩 헤더 상태별 진입점', () => {
  it('비로그인에게는 지원하기와 로그인만 보인다', async () => {
    auth.me = () => Promise.reject(new Error('비로그인'))

    renderLanding()
    expect(
      await screen.findByRole('link', { name: '로그인' }),
    ).toBeInTheDocument()

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
    expect(screen.queryByRole('button', { name: '지원하기' })).toBeNull()
    expect(screen.queryByRole('button', { name: '로그아웃' })).toBeNull()
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
    expect(screen.getByRole('button', { name: '로그아웃' })).toBeInTheDocument()
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
    expect(screen.getByRole('button', { name: '로그아웃' })).toBeInTheDocument()
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
  it('랜딩을 떠나면 html 배경과 theme-color를 되돌린다', () => {
    const spy = vi.spyOn(window, 'getComputedStyle').mockReturnValue({
      backgroundColor: 'rgb(0, 0, 0)',
    } as CSSStyleDeclaration)

    const { unmount } = render(
      <MemoryRouter initialEntries={['/']}>
        <SessionProvider>
          <App />
        </SessionProvider>
      </MemoryRouter>,
    )
    expect(document.documentElement.style.backgroundColor).toBe('rgb(0, 0, 0)')
    expect(
      document
        .querySelector('meta[name="theme-color"]')
        ?.getAttribute('content'),
    ).toBe('rgb(0, 0, 0)')

    unmount()
    expect(document.documentElement.style.backgroundColor).toBe('')
    expect(document.querySelector('meta[name="theme-color"]')).toBeNull()

    spy.mockRestore()
  })

  it('햄버거가 섹션 메뉴를 열고 항목을 누르면 닫는다', async () => {
    renderLanding()

    const toggle = await screen.findByRole('button', { name: '메뉴 열기' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')

    fireEvent.click(toggle)
    const menu = document.getElementById('mobile-menu')
    expect(menu).not.toBeNull()
    expect(screen.getByRole('button', { name: '메뉴 닫기' })).toHaveAttribute(
      'aria-expanded',
      'true',
    )

    fireEvent.click(
      within(menu as HTMLElement).getByRole('link', { name: '소개' }),
    )
    expect(document.getElementById('mobile-menu')).toBeNull()
  })

  it('부원이면 모바일 메뉴에도 공지사항이 있다', async () => {
    auth.me = () => Promise.resolve(BASE)

    renderLanding()
    // 세션 확인이 끝나 데스크톱 헤더에 공지사항이 뜬 뒤에 연다.
    await screen.findByRole('link', { name: '공지사항' })

    fireEvent.click(screen.getByRole('button', { name: '메뉴 열기' }))
    const menu = document.getElementById('mobile-menu') as HTMLElement
    expect(
      within(menu).getByRole('link', { name: '공지사항' }),
    ).toBeInTheDocument()
    // 데스크톱과 같은 원칙 — 라우트 링크는 섹션 nav 밖이다 (#148).
    expect(
      within(menu).getByRole('navigation', { name: '섹션 이동' }),
    ).not.toContainElement(within(menu).getByRole('link', { name: '공지사항' }))
  })

  it('공지사항이 섹션 메뉴와 같은 묶음에 놓인다', async () => {
    auth.me = () => Promise.resolve(BASE)

    renderLanding()

    const notices = await screen.findByRole('link', { name: '공지사항' })
    const sectionNav = screen.getByRole('navigation', { name: '섹션 이동' })

    expect(notices.parentElement).toContainElement(sectionNav)
    expect(sectionNav).not.toContainElement(notices)
  })

  it('ACTIVE에게는 공지사항 링크와 로그아웃이 보인다', async () => {
    auth.me = () => Promise.resolve(BASE)

    renderLanding()

    expect(
      await screen.findByRole('link', { name: '공지사항' }),
    ).toHaveAttribute('href', '/notices')
    expect(screen.getByRole('button', { name: '로그아웃' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '지원하기' })).toBeNull()
  })
})
