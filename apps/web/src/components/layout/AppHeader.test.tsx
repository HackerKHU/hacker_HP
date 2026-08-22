import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import type { User } from '@/api/types'
import { SessionProvider } from '@/auth/session'
import { CLUB } from '@/features/landing/content'
import { AppHeader } from './AppHeader'

/*
 * `AppLayout.test.tsx`는 이 헤더를 통째로 mock으로 지운다 — 그 파일이 보는 것은 뼈대뿐이다.
 * 그래서 헤더 자체의 계약은 **여기서** 지킨다. 없으면 로고가 바뀌어도 아무도 못 잡는다.
 */

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
    getMe: () => Promise.resolve(ACTIVE),
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

describe('AppHeader', () => {
  /*
   * 랜딩 헤더와 **같은 가로 락업이되 잉크만 반대**다. 내부 화면은 라이트 배경이라 검정을
   * 쓴다 — 흰 잉크로 바뀌면 배경에 묻혀 아무것도 안 보이는데, 화면을 안 열어보면 모른다.
   *
   * 배경이 채워진 `-on-white`도 안 된다. 헤더 배경 위에 흰 네모가 비친다.
   */
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
   * **부원이 로고를 눌렀는데 소개 페이지가 나오면 어색하다.** 랜딩은 푸터 링크로 간다 —
   * 로그인한 사람에게 이 헤더의 홈은 공지사항이다.
   */
  it('로고를 누르면 공지사항으로 간다', async () => {
    renderHeader()

    const link = (await screen.findByAltText(CLUB.name)).closest('a')
    expect(link).toHaveAttribute('href', '/notices')
  })
})
