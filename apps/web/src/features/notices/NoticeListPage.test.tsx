import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Notice } from '@/api/notices'
import type { Page } from '@/api/types'
import { SessionProvider } from '@/auth/session'
import { NoticeListPage } from './NoticeListPage'

const api = vi.hoisted(() => ({
  calls: [] as (number | undefined)[],
}))

function notice(id: number, title: string, isPinned: boolean): Notice {
  return {
    id,
    title,
    content: '본문',
    isPinned,
    createdAt: '2026-08-05T09:00:00Z',
    updatedAt: '2026-08-05T09:00:00Z',
  }
}

/** 서버가 이미 `is_pinned DESC, created_at DESC`로 정렬해 내려준 상태를 흉내낸다. */
const PAGES: Page<Notice>[] = [
  {
    content: [notice(1, '고정된 공지', true), notice(2, '일반 공지', false)],
    page: { size: 10, number: 0, totalElements: 12, totalPages: 2 },
  },
  {
    content: [notice(3, '둘째 장 공지', false)],
    page: { size: 10, number: 1, totalElements: 12, totalPages: 2 },
  },
]

vi.mock('@/api/notices', () => ({
  list: ({ page }: { page?: number }) => {
    api.calls.push(page)
    return Promise.resolve(PAGES[page ?? 0])
  },
}))

// 세션은 이 화면의 관심사가 아니다. reportApiError만 있으면 된다.
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

function LocationProbe() {
  const { search } = useLocation()
  return <output data-testid="search">{search}</output>
}

function renderList() {
  render(
    <MemoryRouter initialEntries={['/notices']}>
      <SessionProvider>
        <NoticeListPage />
        <LocationProbe />
      </SessionProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  api.calls = []
})

describe('공지 목록', () => {
  it('고정 공지를 일반 공지와 구분해 렌더한다', async () => {
    renderList()

    const pinned = await screen.findByRole('link', { name: /고정된 공지/ })
    const normal = screen.getByRole('link', { name: /일반 공지/ })

    // 무채색 팔레트라 색이 아니라 좌측 바와 제목 굵기로 가른다.
    expect(pinned.className).toContain('border-l-primary')
    expect(normal.className).not.toContain('border-l-primary')
    expect(within(pinned).getByText('고정')).toBeInTheDocument()
    expect(within(normal).queryByText('고정')).toBeNull()
  })

  it('페이지를 옮기면 URL이 바뀌고 목록을 다시 불러온다', async () => {
    renderList()
    await screen.findByRole('link', { name: /고정된 공지/ })
    expect(screen.getByTestId('search')).toHaveTextContent('')

    // 링크 라벨은 사람이 읽는 1-기반, URL과 API 파라미터는 0-기반이다 (spec §3-2-8).
    fireEvent.click(screen.getByRole('link', { name: '2' }))

    expect(
      await screen.findByRole('link', { name: /둘째 장 공지/ }),
    ).toBeInTheDocument()
    expect(screen.getByTestId('search')).toHaveTextContent('page=1')
    expect(api.calls).toEqual([0, 1])
  })
})
