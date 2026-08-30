import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import type { NoteDetail } from '@/api/notes'
import type { User } from '@/api/types'
import { SessionProvider } from '@/auth/session'
import { NoteDetailPage } from './NoteDetailPage'

/**
 * 자료 상세.
 *
 * 핵심은 두 가지다 — **본인 것에만 수정·삭제가 보이는가**(#59 완료 조건),
 * **파일 URL을 미리 받지 않는가**(계약 §3-2-4 — 열어보기만 해도 발급되면 안 된다).
 */

const api = vi.hoisted(() => ({
  note: null as NoteDetail | null,
  issued: [] as number[],
  removed: [] as number[],
  bookmarked: [] as { id: number; next: boolean }[],
}))

const MINE: NoteDetail = {
  id: 301,
  category: 'EXAM',
  title: '운영체제 중간고사 정리본',
  subjectName: '운영체제',
  professor: '김교수',
  year: 2026,
  semester: 'SPRING',
  examType: 'MIDTERM',
  uploader: { id: 1, name: '홍길동' },
  files: [{ id: 1000, originalName: '정리본.pdf', sizeBytes: 1_048_576 }],
  bookmarked: false,
  createdAt: '2026-08-01T09:00:00Z',
  updatedAt: '2026-08-01T09:00:00Z',
}

vi.mock('@/api/notes', () => ({
  get: () =>
    api.note
      ? Promise.resolve(api.note)
      : Promise.reject(
          new ApiError('NOT_FOUND', 404, '자료를 찾을 수 없습니다.'),
        ),
  downloadUrl: (_noteId: number, fileId: number) => {
    api.issued.push(fileId)
    return Promise.resolve({
      url: 'blob:fixture',
      originalName: '정리본.pdf',
      expiresAt: '2026-08-01T09:01:00Z',
    })
  },
  remove: (id: number) => {
    api.removed.push(id)
    return Promise.resolve()
  },
  setBookmark: (id: number, next: boolean) => {
    api.bookmarked.push({ id, next })
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

const auth = vi.hoisted(() => ({ me: null as unknown }))

vi.mock('@/api/auth', () => ({
  getMe: () => Promise.resolve(auth.me),
  logout: () => Promise.resolve(),
}))

function renderDetail() {
  render(
    <MemoryRouter initialEntries={['/notes/301']}>
      <SessionProvider>
        <Routes>
          <Route path="/notes/:id" element={<NoteDetailPage />} />
          <Route path="/notes" element={<h1>자료게시판</h1>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  api.note = MINE
  api.issued = []
  api.removed = []
  api.bookmarked = []
  auth.me = BASE
  vi.stubGlobal('open', vi.fn())
})

describe('자료 상세', () => {
  it.each([
    ['SPRING', '1학기'],
    ['SUMMER', '여름학기'],
    ['FALL', '2학기'],
    ['WINTER', '겨울학기'],
  ] as const)('%s를 %s로 표시한다', async (semester, label) => {
    api.note = { ...MINE, semester }

    renderDetail()

    expect(await screen.findByText(`2026년 ${label} · 중간고사`)).toBeVisible()
  })

  it('메타데이터와 첨부 목록을 보여준다', async () => {
    renderDetail()

    expect(
      await screen.findByRole('heading', { name: '운영체제 중간고사 정리본' }),
    ).toBeVisible()
    expect(screen.getByText('정리본.pdf')).toBeVisible()
    expect(screen.getByText('1.0 MB')).toBeVisible()
  })

  /*
   * **상세를 여는 것만으로 URL이 발급되면 안 된다** (계약 §3-2-4). 받지도 않을 주소가
   * 응답·로그·히스토리에 남는다. 버튼을 눌러야 발급된다.
   */
  it('열어보기만 하면 내려받기 URL을 발급하지 않는다', async () => {
    renderDetail()
    await screen.findByText('정리본.pdf')

    expect(api.issued).toEqual([])
  })

  it('받기를 눌러야 그 파일의 URL을 발급한다', async () => {
    renderDetail()
    await screen.findByText('정리본.pdf')

    fireEvent.click(screen.getByRole('button', { name: '받기' }))

    await waitFor(() => {
      expect(api.issued).toEqual([1000])
    })
  })

  /*
   * **#59 완료 조건 — "본인 자료에만 수정·삭제 진입점이 보인다."** 판단은 업로더 `id`로
   * 한다 (계약 §3-2-2 MUST). 노출 제어일 뿐 통제는 서버가 한다 (§3-1-7).
   */
  it('본인 자료에는 수정·삭제가 보인다', async () => {
    renderDetail()
    await screen.findByText('정리본.pdf')

    expect(screen.getByRole('link', { name: '수정' })).toBeVisible()
    expect(screen.getByRole('button', { name: '삭제' })).toBeVisible()
  })

  it('남의 자료에는 수정·삭제가 없다', async () => {
    api.note = { ...MINE, uploader: { id: 99, name: '권승원' } }

    renderDetail()
    await screen.findByText('정리본.pdf')

    expect(screen.queryByRole('link', { name: '수정' })).toBeNull()
    expect(screen.queryByRole('button', { name: '삭제' })).toBeNull()
  })

  /*
   * **업로더가 빈 자료는 `ADMIN`만 손댈 수 있다** (계약 §3-2-4) — 주인이 없으므로 "본인"이
   * 성립하지 않는다. 이름으로 견주면 탈퇴한 회원끼리 서로의 자료를 지울 수 있다.
   */
  it('탈퇴한 회원의 자료는 일반 부원에게 수정·삭제가 없다', async () => {
    api.note = { ...MINE, uploader: { id: null, name: '탈퇴한 회원' } }

    renderDetail()
    await screen.findByText('정리본.pdf')

    expect(screen.queryByRole('link', { name: '수정' })).toBeNull()
  })

  it('ADMIN에게는 남의 자료에도 수정·삭제가 보인다', async () => {
    api.note = { ...MINE, uploader: { id: 99, name: '권승원' } }
    auth.me = { ...BASE, role: 'ADMIN' }

    renderDetail()
    await screen.findByText('정리본.pdf')

    expect(screen.getByRole('link', { name: '수정' })).toBeVisible()
  })

  /* 담기·빼기는 방향을 정해 보낸다 (계약 §3-2-4 MUST — 토글이 아니다). */
  it('담긴 자료의 별표를 누르면 빼기 요청이 나간다', async () => {
    api.note = { ...MINE, bookmarked: true }

    renderDetail()
    await screen.findByText('정리본.pdf')

    fireEvent.click(screen.getByRole('button', { name: /즐겨찾기 해제/ }))

    await waitFor(() => {
      expect(api.bookmarked).toEqual([{ id: 301, next: false }])
    })
  })

  it('없는 자료면 안내가 뜬다', async () => {
    api.note = null

    renderDetail()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '자료를 찾을 수 없습니다',
    )
  })
})
