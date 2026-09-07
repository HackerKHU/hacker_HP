import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import { SessionProvider } from '@/auth/session'
import { MemoryRouter, Route, Routes } from '@/test/TestRouter'
import { NoticeDetailPage } from './NoticeDetailPage'

const api = vi.hoisted(() => ({
  likeCount: 4,
  likedByMe: false,
  /** 화면이 어떤 방향으로 보냈는지. 토글이 아니므로 방향이 곧 계약이다. */
  likes: [] as { id: number; liked: boolean }[],
  likeFails: false,
}))

vi.mock('@/api/notices', () => ({
  get: (id: number) =>
    id === 1
      ? Promise.resolve({
          id: 1,
          title: '있는 공지',
          content: '첫 줄\n둘째 줄',
          isPinned: false,
          createdAt: '2026-08-05T09:00:00Z',
          updatedAt: '2026-08-05T09:00:00Z',
          likeCount: api.likeCount,
          likedByMe: api.likedByMe,
        })
      : Promise.reject(
          new ApiError('NOT_FOUND', 404, '공지를 찾을 수 없습니다.'),
        ),
  setNoticeLike: (id: number, liked: boolean) => {
    if (api.likeFails) return Promise.reject(new Error('network'))
    api.likes.push({ id, liked })
    return Promise.resolve()
  },
}))

beforeEach(() => {
  api.likeCount = 4
  api.likedByMe = false
  api.likes = []
  api.likeFails = false
})

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
  it('제목과 본문을 렌더하고 줄바꿈을 보존한다', async () => {
    renderDetail('1')

    expect(
      document.querySelector('[data-detail-surface="notice"]'),
    ).toBeInTheDocument()
    expect(
      await screen.findByRole('heading', { name: '있는 공지' }),
    ).toBeInTheDocument()
    const heading = screen.getByRole('heading', { name: '있는 공지' })
    expect(heading.className).toContain('line-clamp-2')
    expect(heading.className).toContain('break-all')
    expect(heading).toHaveAttribute('title', '있는 공지')

    // 본문은 평문이다. 줄바꿈만 살리고 마크업으로 해석하지 않는 것이 이 화면의 계약이다.
    const body = screen.getByText(/첫 줄/)
    expect(body).toHaveTextContent('첫 줄')
    expect(body).toHaveTextContent('둘째 줄')
    expect(body.textContent).toContain('\n')
    expect(body.className).toContain('whitespace-pre-wrap')
  })

  /*
   * 좋아요는 **방향을 정해 보낸다** (계약 §3-2-5 MUST — 토글이 아니다). 응답이 `204`라
   * 최신 개수가 오지 않으므로 화면이 직접 세는 것이 낙관적 업데이트의 전제다 (#348 D2).
   */
  it.each([
    { initial: false, label: '좋아요 4', next: true, after: '좋아요 5' },
    { initial: true, label: '좋아요 4', next: false, after: '좋아요 3' },
  ])(
    'likedByMe=$initial에서 누르면 개수를 바로 고치고 $next를 보낸다',
    async ({ initial, label, next, after }) => {
      api.likedByMe = initial

      renderDetail('1')
      const button = await screen.findByRole('button', { name: label })
      expect(button).toHaveAttribute('aria-pressed', String(initial))

      fireEvent.click(button)

      // 응답을 기다리지 않고 먼저 바뀐다 — 그게 낙관적 업데이트다.
      const changed = screen.getByRole('button', { name: after })
      expect(changed).toHaveAttribute('aria-pressed', String(next))
      await waitFor(() => {
        expect(api.likes).toEqual([{ id: 1, liked: next }])
      })
    },
  )

  it('좋아요 실패는 누르기 전 개수·상태로 되돌리고 알린다', async () => {
    api.likeFails = true

    renderDetail('1')
    fireEvent.click(await screen.findByRole('button', { name: '좋아요 4' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '좋아요를 바꾸지 못했습니다',
    )
    const button = screen.getByRole('button', { name: '좋아요 4' })
    expect(button).toHaveAttribute('aria-pressed', 'false')
  })

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
