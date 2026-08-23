import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { NoteSummary } from '@/api/notes'
import type { User } from '@/api/types'
import { SessionProvider } from '@/auth/session'
import { BookmarkListPage } from './BookmarkListPage'

/**
 * 내 즐겨찾기 목록 (spec §2-1-5).
 *
 * 계약이 `GET /bookmarks`를 `GET /notes`와 같은 형태로 맞춰 둔 덕에 표는 같은 컴포넌트다
 * (§3-2-4). 여기서 보는 것은 **이 화면만의 규칙** — 검색·필터를 보내지 않고, 뺀 항목이
 * 목록에서 사라지는가.
 */

const api = vi.hoisted(() => ({
  rows: [] as NoteSummary[],
  calls: [] as { page?: number; size?: number }[],
  bookmarked: [] as { id: number; next: boolean }[],
}))

const ROW: NoteSummary = {
  id: 301,
  category: 'EXAM',
  title: '운영체제 중간고사 정리본',
  subjectName: '운영체제',
  professor: '김교수',
  year: 2026,
  semester: 'SPRING',
  examType: 'MIDTERM',
  uploader: { id: 1, name: '홍길동' },
  fileCount: 1,
  // **이 목록의 `bookmarked`는 언제나 참이다** (계약 §3-2-4 MUST).
  bookmarked: true,
  createdAt: '2026-08-01T09:00:00Z',
}

vi.mock('@/api/notes', () => ({
  bookmarks: (query: { page?: number; size?: number }) => {
    api.calls.push(query)
    return Promise.resolve({
      content: api.rows,
      page: {
        size: 20,
        number: 0,
        totalElements: api.rows.length,
        totalPages: api.rows.length === 0 ? 0 : 1,
      },
    })
  },
  setBookmark: (id: number, next: boolean) => {
    api.bookmarked.push({ id, next })
    // 뺐으면 다음 조회에서 사라진다 — 서버가 그렇게 답한다.
    if (!next) api.rows = api.rows.filter((row) => row.id !== id)
    return Promise.resolve()
  },
}))

const BASE: User = {
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

vi.mock('@/api/auth', () => ({
  getMe: () => Promise.resolve(BASE),
  logout: () => Promise.resolve(),
}))

function renderBookmarks() {
  render(
    <MemoryRouter initialEntries={['/bookmarks']}>
      <SessionProvider>
        <BookmarkListPage />
      </SessionProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  api.rows = [ROW]
  api.calls = []
  api.bookmarked = []
})

describe('즐겨찾기 목록', () => {
  it('담아둔 자료를 보여준다', async () => {
    renderBookmarks()

    expect(await screen.findByText('운영체제 중간고사 정리본')).toBeVisible()
  })

  /* **검색·필터를 받지 않는다** (계약 §3-2-4) — 이미 본인이 추린 목록이다. */
  it('검색·필터 입력이 없고 조회에도 실리지 않는다', async () => {
    renderBookmarks()
    await screen.findByText('운영체제 중간고사 정리본')

    expect(screen.queryByLabelText('검색')).toBeNull()
    expect(screen.queryByLabelText('과목')).toBeNull()
    expect(Object.keys(api.calls[0])).toEqual(['page', 'size'])
  })

  /*
   * **갈래 열을 보여준다.** 자료 목록과 달리 시험·과목이 섞여 있어, 없으면 무엇을 담았는지
   * 제목만 보고 가려야 한다.
   */
  it('갈래 열이 보인다', async () => {
    renderBookmarks()
    await screen.findByText('운영체제 중간고사 정리본')

    expect(screen.getByRole('columnheader', { name: '갈래' })).toBeVisible()
  })

  /* 여기서 빼면 목록에서 사라진다 — 담긴 것만 있는 화면이라 그것이 맞는 결과다. */
  it('별표를 다시 누르면 빼기 요청이 나가고 목록에서 사라진다', async () => {
    renderBookmarks()
    await screen.findByText('운영체제 중간고사 정리본')

    fireEvent.click(screen.getByRole('button', { name: /즐겨찾기 해제/ }))

    await waitFor(() => {
      expect(api.bookmarked).toEqual([{ id: 301, next: false }])
    })
    expect(await screen.findByText(/담아둔 자료가 없습니다/)).toBeVisible()
  })

  /* 빈 화면에 갈 곳을 준다 — "없습니다"만 두면 어디서 담는지 알 수 없다. */
  it('비어 있으면 자료 목록으로 가는 링크를 준다', async () => {
    api.rows = []

    renderBookmarks()

    expect(
      await screen.findByRole('link', { name: '자료게시판' }),
    ).toHaveAttribute('href', '/notes')
  })
})
