import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import App from '@/App'
import { ApiError } from '@/api/client'
import { SessionProvider } from '@/auth/session'

vi.mock('@/api/auth', () => ({
  getMe: () =>
    Promise.reject(
      new ApiError('UNAUTHENTICATED', 401, '로그인이 필요합니다.'),
    ),
}))

vi.mock('@/api/notices', () => ({
  list: () => Promise.reject(new Error('이 화면에서는 쓰지 않는다')),
  get: () => Promise.reject(new Error('이 화면에서는 쓰지 않는다')),
  togglePin: () => Promise.reject(new Error('이 화면에서는 쓰지 않는다')),
}))

describe('개인정보처리방침', () => {
  // 랜딩과 같은 공개 페이지다. 가드 아래로 들어가면 비로그인이 못 본다.
  it('비로그인 상태에서 열린다', async () => {
    render(
      <MemoryRouter initialEntries={['/privacy']}>
        <SessionProvider>
          <App />
        </SessionProvider>
      </MemoryRouter>,
    )

    expect(
      await screen.findByRole('heading', {
        name: '개인정보처리방침',
        level: 1,
      }),
    ).toBeInTheDocument()
    // 미완성 항목이 있다는 안내가 보여야 한다.
    expect(screen.getByRole('note')).toHaveTextContent(
      '초안이며 검토가 필요합니다',
    )
  })
})
