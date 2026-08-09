import { fireEvent, render, screen } from '@testing-library/react'
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

  // 주소가 없는 mailto는 빈 메일 창만 띄운다. 위와 같은 이유로 조건 분기 없이 못박는다.
  it('후원 문의 주소가 자리표시자인 동안 버튼이 잠겨 있다', async () => {
    renderLanding()

    expect(
      await screen.findByRole('button', { name: '후원 문의하기' }),
    ).toBeDisabled()
    expect(screen.queryByRole('link', { name: '후원 문의하기' })).toBeNull()
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
  it('비로그인에게는 외부 모집 폼과 로그인만 보인다', async () => {
    auth.me = () => Promise.reject(new Error('비로그인'))

    renderLanding()
    expect(
      await screen.findByRole('link', { name: '로그인' }),
    ).toBeInTheDocument()

    /*
     * 모집 폼 주소가 **아직 자리표시자라서** 링크가 아니라 잠긴 버튼이어야 한다.
     *
     * `isPlaceholder(CLUB.applyUrl)`로 기대값을 분기하지 않는다 — 구현과 같은 조건을
     * 보면 판별이 망가져 링크가 살아나도 테스트가 같이 따라가서 회귀를 못 잡는다.
     * 지금 값 기준으로 못박아 두고, 실제 주소가 들어오는 날 이 테스트를 같이 고친다.
     */
    expect(screen.getByRole('button', { name: '지원하기' })).toBeDisabled()
    expect(screen.queryByRole('link', { name: '지원하기' })).toBeNull()
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
