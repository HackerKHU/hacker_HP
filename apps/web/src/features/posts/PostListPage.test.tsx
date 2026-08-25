import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { PostSummary } from '@/api/posts'
import type { User } from '@/api/types'
import { SessionProvider } from '@/auth/session'
import { PostListPage } from './PostListPage'

/**
 * 자유 게시판 목록.
 *
 * 이 화면만의 규칙을 본다 — **정렬을 보내지 않고**(서버 고정), **본문을 그리지 않는다**
 * (목록 응답에 아예 없다). 둘 다 계약이 정한 것이라 화면이 어기면 조용히 어긋난다.
 */

const api = vi.hoisted(() => ({
  rows: [] as PostSummary[],
  calls: [] as { page?: number; size?: number }[],
  total: 0,
  totalPages: 1,
}))

const POST: PostSummary = {
  id: 701,
  title: '이번 학기 스터디 모집합니다',
  author: { id: 1, name: '홍길동' },
  createdAt: '2026-08-01T09:00:00Z',
}

vi.mock('@/api/posts', () => ({
  list: (query: { page?: number; size?: number }) => {
    api.calls.push(query)
    return Promise.resolve({
      content: api.rows,
      page: {
        size: 20,
        number: query.page ?? 0,
        totalElements: api.total,
        totalPages: api.totalPages,
      },
    })
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

function renderList(path = '/posts') {
  render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <PostListPage />
      </SessionProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  api.rows = [POST, { ...POST, id: 702, title: '세미나 자료 공유' }]
  api.calls = []
  api.total = 2
  api.totalPages = 1
})

describe('자유 게시판 목록', () => {
  it('글 제목과 작성자를 보여주고 상세로 잇는다', async () => {
    renderList()

    expect(await screen.findByText(POST.title)).toBeVisible()
    expect(screen.getByText(POST.title).closest('a')).toHaveAttribute(
      'href',
      '/posts/701',
    )
    expect(screen.getAllByText('홍길동')).toHaveLength(2)
  })

  /*
   * **정렬 선택지가 없다** (spec §2-1-8 MUST). 서버가 `created_at DESC, id DESC`로
   * 고정한다 — 받지 않으면 이상한 값으로 서버가 터질 자리도 없다 (#52의 `sort=bogus`).
   */
  it('정렬을 고르지 않고 페이지 조건만 보낸다', async () => {
    renderList()
    await screen.findByText(POST.title)

    expect(screen.queryByLabelText('정렬')).toBeNull()
    expect(Object.keys(api.calls[0])).toEqual(['page', 'size'])
  })

  /*
   * **목록은 본문을 담지 않는다** (계약 §3-2-5 MUST) — 상한이 10,000자라 20건이면 그것만
   * 으로 응답이 200KB가 된다. 화면이 미리보기를 그리기 시작하면 그 필드를 요구하게 된다.
   */
  it('본문 미리보기를 그리지 않는다', async () => {
    renderList()
    await screen.findByText(POST.title)

    // 목록 응답에 `content`가 없으므로 화면도 그것을 쓰지 않는다.
    expect(screen.queryByText(/매주 수요일/)).toBeNull()
  })

  /* 작성자가 탈퇴한 글도 목록에서 깨지지 않는다 (#237 완료 조건). */
  it('탈퇴한 회원의 글도 작성자 이름이 보인다', async () => {
    api.rows = [{ ...POST, author: { id: null, name: '탈퇴한 회원' } }]
    api.total = 1

    renderList()

    expect(await screen.findByText('탈퇴한 회원')).toBeVisible()
  })

  it('글쓰기 진입점이 있다', async () => {
    renderList()

    expect(await screen.findByRole('link', { name: '글쓰기' })).toHaveAttribute(
      'href',
      '/posts/new',
    )
  })

  /* 주소의 페이지를 그대로 조회에 싣는다 — 새로고침·링크 공유에 살아남아야 한다. */
  it('주소의 page를 조회에 싣는다', async () => {
    api.totalPages = 3

    renderList('/posts?page=2')

    await screen.findByText(POST.title)
    expect(api.calls[0].page).toBe(2)
  })

  it('글이 없으면 안내가 뜬다', async () => {
    api.rows = []
    api.total = 0
    api.totalPages = 0

    renderList()

    expect(await screen.findByText(/아직 올라온 글이 없습니다/)).toBeVisible()
  })
})
