import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { User } from '@/api/types'
import { SessionProvider } from '@/auth/session'
import { CLUB, FAQS } from './content'
import { LandingPage } from './LandingPage'

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

function renderLanding() {
  render(
    <MemoryRouter initialEntries={['/']}>
      <SessionProvider>
        <LandingPage />
      </SessionProvider>
    </MemoryRouter>,
  )
}

let fetchSpy: ReturnType<typeof vi.fn>

beforeEach(() => {
  auth.me = () => Promise.reject(new Error('비로그인'))
  // T-24 — 랜딩이 fetch/XHR을 부르는지 감시한다. 실제로 불리면 테스트가 잡는다.
  fetchSpy = vi.fn(() =>
    Promise.reject(new Error('랜딩은 fetch를 부르지 않는다')),
  )
  vi.stubGlobal('fetch', fetchSpy)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('공개 랜딩', () => {
  // T-21~T-23 — 어느 세션 상태에서도 가드에 걸리지 않고 그대로 렌더된다.
  it.each([
    ['비로그인', null],
    ['PENDING', { ...BASE, status: 'PENDING' as const, approvedAt: null }],
    ['ACTIVE', BASE],
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
  })

  // T-24 — 랜딩은 정적이다 (spec 3-3 결정 8).
  it('렌더 중 fetch를 한 번도 부르지 않는다', async () => {
    renderLanding()
    await screen.findByRole('heading', { level: 1 })

    expect(fetchSpy).not.toHaveBeenCalled()
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

    // 이 사이트 로그인이 아니라 동아리 가입이라 외부 폼으로 나간다.
    expect(screen.getByRole('link', { name: '지원하기' })).toHaveAttribute(
      'href',
      CLUB.applyUrl,
    )
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
