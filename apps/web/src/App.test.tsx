import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { User } from './api/types'
import { SessionProvider } from './auth/session'

const session = vi.hoisted(() => ({ user: null as User | null }))

vi.mock('./api/auth', () => ({
  getMe: () => Promise.resolve(session.user),
}))

const BASE: User = {
  id: 1,
  email: 'member@khu.ac.kr',
  studentNo: '2021123456',
  name: '홍길동',
  role: 'USER',
  status: 'ACTIVE',
  createdAt: '2026-03-02T09:00:00Z',
  approvedAt: '2026-03-03T09:00:00Z',
}

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  session.user = null
})

describe('라우트 가드', () => {
  it('PENDING 사용자가 보호 라우트에 가면 대기중 안내로 되돌린다', async () => {
    session.user = { ...BASE, status: 'PENDING', approvedAt: null }

    renderAt('/notices')

    expect(
      await screen.findByRole('heading', { name: '승인 대기' }),
    ).toBeInTheDocument()
  })

  it('ACTIVE USER가 관리자 라우트에 가면 차단하고 부원 홈으로 되돌린다', async () => {
    session.user = BASE

    renderAt('/admin/members')

    expect(
      await screen.findByRole('heading', { name: '공지 목록' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '회원 관리' })).toBeNull()
  })

  // 회귀 — ACTIVE로 로그인한 뒤 관리자가 정지시키면(#31) 세션은 살아 있고 status만 바뀐다.
  // 정지 계정을 세션에 넣으면 homePath → RequireActive → GuestOnly가 서로를 밀며 무한히 돈다.
  it('SUSPENDED 사용자가 보호 라우트에 가면 순환 없이 로그인 화면에 닿는다', async () => {
    session.user = { ...BASE, status: 'SUSPENDED' }

    renderAt('/notices')

    expect(
      await screen.findByRole('heading', { name: '로그인' }),
    ).toBeInTheDocument()
  })
})
