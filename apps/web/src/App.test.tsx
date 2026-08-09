import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import App from './App'
import type { User } from './api/types'
import { SessionProvider } from './auth/session'

const pendingUser: User = {
  id: 1,
  email: 'member@khu.ac.kr',
  studentNo: '2021123456',
  name: '홍길동',
  role: 'USER',
  status: 'PENDING',
  createdAt: '2026-03-02T09:00:00Z',
  approvedAt: null,
}

vi.mock('./api/auth', () => ({
  getMe: () => Promise.resolve(pendingUser),
}))

describe('라우트 가드', () => {
  it('PENDING 사용자가 보호 라우트에 가면 대기중 안내로 되돌린다', async () => {
    render(
      <MemoryRouter initialEntries={['/notices']}>
        <SessionProvider>
          <App />
        </SessionProvider>
      </MemoryRouter>,
    )

    expect(
      await screen.findByRole('heading', { name: '승인 대기' }),
    ).toBeInTheDocument()
  })
})
