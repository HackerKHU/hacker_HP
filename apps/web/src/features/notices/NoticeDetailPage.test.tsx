import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import { SessionProvider } from '@/auth/session'
import { NoticeDetailPage } from './NoticeDetailPage'

vi.mock('@/api/notices', () => ({
  get: (id: number) =>
    id === 1
      ? Promise.resolve({
          id: 1,
          title: '있는 공지',
          content: '본문',
          isPinned: false,
          createdAt: '2026-08-05T09:00:00Z',
          updatedAt: '2026-08-05T09:00:00Z',
        })
      : Promise.reject(
          new ApiError('NOT_FOUND', 404, '공지를 찾을 수 없습니다.'),
        ),
}))

vi.mock('@/api/auth', () => ({
  getMe: () =>
    Promise.resolve({
      id: 1,
      email: 'member@khu.ac.kr',
      studentNo: '2021123456',
      name: '홍길동',
      role: 'USER',
      status: 'ACTIVE',
      createdAt: '2026-03-02T09:00:00Z',
      approvedAt: '2026-03-03T09:00:00Z',
    }),
}))

function renderDetail(id: string) {
  render(
    <MemoryRouter initialEntries={[`/notices/${id}`]}>
      <SessionProvider>
        <Routes>
          <Route path="/notices/:id" element={<NoticeDetailPage />} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

describe('공지 상세', () => {
  // 회귀 — 없는 id로 들어와도 화면이 깨지지 않고 빠져나갈 길이 있어야 한다.
  it('없는 공지면 안내를 띄우고 목록으로 돌아갈 링크를 남긴다', async () => {
    renderDetail('999')

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '공지를 찾을 수 없습니다',
    )
    expect(screen.getByRole('link', { name: /공지 목록/ })).toHaveAttribute(
      'href',
      '/notices',
    )
  })
})
