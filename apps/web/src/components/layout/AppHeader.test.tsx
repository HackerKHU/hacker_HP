import { render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { User } from '@/api/types'
import { SessionProvider } from '@/auth/session'
import { CLUB } from '@/features/landing/content'
import { MemoryRouter } from '@/test/TestRouter'
import { AppHeader } from './AppHeader'

/*
 * `AppLayout.test.tsx`는 이 헤더를 통째로 mock으로 지운다 — 그 파일이 보는 것은 뼈대뿐이다.
 * 그래서 헤더 자체의 계약은 **여기서** 지킨다. 없으면 로고가 바뀌어도 아무도 못 잡는다.
 */

const session = vi.hoisted(() => ({ role: 'USER' as 'USER' | 'ADMIN' }))

const ACTIVE: User = {
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

vi.mock('@/api/auth', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/auth')>()
  return {
    ...actual,
    getMe: () => Promise.resolve({ ...ACTIVE, role: session.role }),
    logout: () => Promise.resolve(),
  }
})

function renderHeader() {
  render(
    <MemoryRouter>
      <SessionProvider>
        <AppHeader />
      </SessionProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  session.role = 'USER'
})

describe('AppHeader', () => {
  it('fixed 결과 알림보다 위에 쌓이되 sticky로 바뀌지 않는다', async () => {
    renderHeader()

    const header = await screen.findByRole('banner')
    expect(header).toHaveClass('relative', 'z-40')
    expect(header).not.toHaveClass('sticky')
  })

  /*
   * 랜딩 헤더와 **같은 가로 락업이되 잉크만 반대**다. 내부 화면은 라이트 배경이라 검정을
   * 쓴다 — 흰 잉크로 바뀌면 배경에 묻혀 아무것도 안 보이는데, 화면을 안 열어보면 모른다.
   *
   * 배경이 채워진 `-on-white`도 안 된다. 헤더 배경 위에 흰 네모가 비친다.
   */
  /*
   * **관리자 전용 화면 앞에서 끊는다** (#307).
   *
   * `회원 관리`는 관리자에게만 뜨는 화면이라(spec §3-1-3 매트릭스) 나머지 넷과 성격이
   * 다른데, 같은 간격으로 붙어 있으면 모양으로는 구별되지 않는다.
   *
   * **선은 스크린리더에 읽히지 않아야 한다** — 눈으로 읽는 것이고, 읽히면 메뉴 사이에
   * 빈 항목이 하나 끼는 것으로 들린다. 그래서 `aria-hidden`을 함께 본다.
   */
  it('관리자 메뉴 앞에 구분선이 있다', async () => {
    session.role = 'ADMIN'
    renderHeader()

    const nav = await screen.findByRole('navigation', { name: '주요 메뉴' })
    const admin = await screen.findByRole('link', { name: '회원 관리' })

    const divider = admin.previousElementSibling
    expect(divider?.tagName).toBe('SPAN')
    expect(divider).toHaveAttribute('aria-hidden', 'true')
    // 선이 링크로 읽히지 않는다 — 메뉴는 여전히 다섯 칸이다.
    expect(within(nav).getAllByRole('link')).toHaveLength(5)
  })

  /* 일반 부원에게는 가를 것이 없다. 선만 남으면 없는 경계를 그린다. */
  it('일반 부원 헤더에는 구분선이 없다', async () => {
    session.role = 'USER'
    renderHeader()

    const nav = await screen.findByRole('navigation', { name: '주요 메뉴' })
    expect(within(nav).queryByRole('link', { name: '회원 관리' })).toBeNull()
    expect(nav.querySelector('[aria-hidden="true"]')).toBeNull()
  })

  it('라이트 배경에 맞는 검정 잉크 락업을 쓴다', async () => {
    renderHeader()

    const logo = await screen.findByAltText(CLUB.name)
    expect(logo).toHaveAttribute(
      'src',
      '/brand/lockup-horizontal-black-512.png',
    )
  })

  /*
   * 로고는 장식이 아니라 이 사이트가 무엇인지 말하는 요소다. `alt`가 비면 스크린리더
   * 사용자에게 그 정보가 통째로 사라진다.
   */
  it('로고에 대체 텍스트가 있다', async () => {
    renderHeader()

    expect(await screen.findByAltText(CLUB.name)).toBeInTheDocument()
  })

  /*
   * **헤더 로고는 사이트의 홈으로 간다.** `/notices`를 가리키던 때는 바로 옆 주요 메뉴의
   * `공지사항`과 목적지가 겹쳤고, 로그인한 부원에게는 랜딩으로 돌아갈 헤더 경로가 없었다.
   */
  it('로고를 누르면 랜딩으로 간다', async () => {
    renderHeader()

    const link = (await screen.findByAltText(CLUB.name)).closest('a')
    expect(link).toHaveAttribute('href', '/')
  })
})
