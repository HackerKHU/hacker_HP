import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import type { NoteDetail } from '@/api/notes'
import type { User } from '@/api/types'
import { SessionProvider, useSession } from '@/auth/session'
import { NoteDetailPage } from './NoteDetailPage'

/**
 * 자료 상세.
 *
 * 핵심은 두 가지다 — **본인 것에만 수정·삭제가 보이는가**(#59 완료 조건),
 * **파일 URL을 미리 받지 않는가**(계약 §3-2-4 — 열어보기만 해도 발급되면 안 된다).
 */

type ControlledNote = {
  promise: Promise<NoteDetail>
  resolve: (note: NoteDetail) => void
  reject: (error: unknown) => void
}

const api = vi.hoisted(() => ({
  note: null as NoteDetail | null,
  gets: [] as number[],
  views: new Map<number, number>(),
  pending: new Map<number, ControlledNote>(),
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
  viewCount: 12_345,
  files: [{ id: 1000, originalName: '정리본.pdf', sizeBytes: 1_048_576 }],
  bookmarked: false,
  createdAt: '2026-08-01T09:00:00Z',
  updatedAt: '2026-08-01T09:00:00Z',
}

const OTHER: NoteDetail = {
  ...MINE,
  id: 302,
  title: '컴퓨터네트워크 기말고사 정리본',
  subjectName: '컴퓨터네트워크',
  uploader: { id: 99, name: '권승원' },
}

vi.mock('@/api/notes', () => ({
  get: (id: number) => {
    api.gets.push(id)
    const pending = api.pending.get(id)
    if (pending) return pending.promise
    const note = id === OTHER.id ? OTHER : api.note
    if (!note) {
      return Promise.reject(
        new ApiError('NOT_FOUND', 404, '자료를 찾을 수 없습니다.'),
      )
    }
    const viewCount = (api.views.get(id) ?? note.viewCount) + 1
    api.views.set(id, viewCount)
    return Promise.resolve({ ...note, viewCount })
  },
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

function NoteListFixture() {
  return (
    <>
      <h1>자료게시판</h1>
      <Link to="/notes/301">첫 자료 열기</Link>
      <Link to="/notes/302">다른 자료 열기</Link>
    </>
  )
}

function SessionKindProbe() {
  const { state } = useSession()
  return <output data-testid="session-kind">{state.kind}</output>
}

function DirectNavigation() {
  return (
    <nav aria-label="상세 경합 테스트 이동">
      <Link to="/notes/302">302로 직접 이동</Link>
      <Link to="/away">상세 닫기</Link>
    </nav>
  )
}

function renderDetail({ directNavigation = false } = {}) {
  render(
    <StrictMode>
      <MemoryRouter initialEntries={['/notes/301']}>
        <SessionProvider>
          <SessionKindProbe />
          {directNavigation && <DirectNavigation />}
          <Routes>
            <Route path="/notes/:id" element={<NoteDetailPage />} />
            <Route path="/notes" element={<NoteListFixture />} />
            <Route path="/away" element={<h1>다른 화면</h1>} />
          </Routes>
        </SessionProvider>
      </MemoryRouter>
    </StrictMode>,
  )
}

function controlNote(id: number): ControlledNote {
  let resolve: ControlledNote['resolve'] = () => undefined
  let reject: ControlledNote['reject'] = () => undefined
  const promise = new Promise<NoteDetail>((onResolve, onReject) => {
    resolve = onResolve
    reject = onReject
  })
  const controlled = { promise, resolve, reject }
  api.pending.set(id, controlled)
  return controlled
}

beforeEach(() => {
  api.note = MINE
  api.gets = []
  api.views = new Map()
  api.pending = new Map()
  api.issued = []
  api.removed = []
  api.bookmarked = []
  auth.me = BASE
  vi.stubGlobal('open', vi.fn())
})

describe('자료 상세', () => {
  it('StrictMode에서도 최초 상세·다른 상세·재진입을 각각 한 번만 조회한다', async () => {
    renderDetail()

    await screen.findByRole('heading', { name: MINE.title })
    expect(api.gets).toEqual([301])
    expect(api.views.get(301)).toBe(MINE.viewCount + 1)

    fireEvent.click(screen.getByRole('link', { name: '← 시험 정리본' }))
    await screen.findByRole('heading', { name: '자료게시판' })
    fireEvent.click(screen.getByRole('link', { name: '다른 자료 열기' }))
    await screen.findByRole('heading', { name: OTHER.title })
    expect(api.gets).toEqual([301, 302])
    expect(api.views.get(302)).toBe(OTHER.viewCount + 1)

    fireEvent.click(screen.getByRole('link', { name: '← 시험 정리본' }))
    await screen.findByRole('heading', { name: '자료게시판' })
    fireEvent.click(screen.getByRole('link', { name: '첫 자료 열기' }))
    await screen.findByRole('heading', { name: MINE.title })
    expect(api.gets).toEqual([301, 302, 301])
    expect(api.views.get(301)).toBe(MINE.viewCount + 2)
  })

  it('먼저 연 상세가 늦게 끝나도 현재 id의 화면을 덮어쓰지 않는다', async () => {
    const first = controlNote(301)
    const second = controlNote(302)
    renderDetail({ directNavigation: true })

    await waitFor(() => expect(api.gets).toEqual([301]))
    fireEvent.click(screen.getByRole('link', { name: '302로 직접 이동' }))
    await waitFor(() => expect(api.gets).toEqual([301, 302]))

    await act(async () => {
      second.resolve(OTHER)
      await second.promise
    })
    expect(
      await screen.findByRole('heading', { name: OTHER.title }),
    ).toBeInTheDocument()

    await act(async () => {
      first.resolve(MINE)
      await first.promise
    })
    expect(screen.getByRole('heading', { name: OTHER.title })).toBeVisible()
    expect(
      screen.queryByRole('heading', { name: MINE.title }),
    ).not.toBeInTheDocument()
  })

  it.each(['resolve', 'reject'] as const)(
    '언마운트 뒤 pending 요청의 늦은 %s가 상태나 오류를 반영하지 않는다',
    async (settle) => {
      const pending = controlNote(301)
      const unhandled = vi.fn()
      window.addEventListener('unhandledrejection', unhandled)

      try {
        renderDetail({ directNavigation: true })
        await waitFor(() => expect(api.gets).toEqual([301]))
        await waitFor(() =>
          expect(screen.getByTestId('session-kind')).toHaveTextContent(
            'active',
          ),
        )

        fireEvent.click(screen.getByRole('link', { name: '상세 닫기' }))
        await screen.findByRole('heading', { name: '다른 화면' })

        await act(async () => {
          if (settle === 'resolve') {
            pending.resolve(MINE)
          } else {
            pending.reject(new ApiError('SUSPENDED', 403, '정지된 계정입니다.'))
          }
          await pending.promise.catch(() => undefined)
        })

        expect(screen.getByRole('heading', { name: '다른 화면' })).toBeVisible()
        expect(screen.getByTestId('session-kind')).toHaveTextContent('active')
        expect(screen.queryByRole('alert')).not.toBeInTheDocument()
        expect(unhandled).not.toHaveBeenCalled()
      } finally {
        window.removeEventListener('unhandledrejection', unhandled)
      }
    },
  )

  it('메타데이터와 첨부 목록을 보여준다', async () => {
    renderDetail()

    expect(
      await screen.findByRole('heading', { name: '운영체제 중간고사 정리본' }),
    ).toBeVisible()
    expect(screen.getByText('정리본.pdf')).toBeVisible()
    expect(screen.getByText('1.0 MB')).toBeVisible()
    const viewCountLabel = screen.getByText('조회수')
    expect(viewCountLabel.tagName).toBe('DT')
    expect(viewCountLabel.nextElementSibling).toHaveTextContent('12346')
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

  /*
   * 담기·빼기는 방향을 정해 보낸다 (계약 §3-2-4 MUST — 토글이 아니다).
   * 즐겨찾기는 조회가 아니므로 성공 뒤 상세 GET을 다시 부르지 않는다.
   */
  it.each([
    { initial: false, action: '즐겨찾기', next: true },
    { initial: true, action: '즐겨찾기 해제', next: false },
  ])(
    '$action 성공 후 bookmarked만 바꾸고 상세를 재조회하지 않는다',
    async ({ initial, action, next }) => {
      api.note = { ...MINE, bookmarked: initial }

      renderDetail()
      await screen.findByText('정리본.pdf')
      expect(api.gets).toEqual([301])
      expect(api.views.get(301)).toBe(MINE.viewCount + 1)

      fireEvent.click(screen.getByRole('button', { name: action }))

      await waitFor(() => {
        expect(api.bookmarked).toEqual([{ id: 301, next }])
      })
      expect(api.gets).toEqual([301])
      expect(api.views.get(301)).toBe(MINE.viewCount + 1)
      expect(
        screen.getByRole('button', {
          name: next ? '즐겨찾기 해제' : '즐겨찾기',
        }),
      ).toBeVisible()
      const viewCountLabel = screen.getByText('조회수')
      expect(viewCountLabel.nextElementSibling).toHaveTextContent('12346')
      expect(screen.getByText('정리본.pdf')).toBeVisible()
    },
  )

  it('없는 자료면 안내가 뜬다', async () => {
    api.note = null

    renderDetail()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '자료를 찾을 수 없습니다',
    )
  })
})
