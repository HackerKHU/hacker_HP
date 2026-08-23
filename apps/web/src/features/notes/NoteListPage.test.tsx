import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import { MemoryRouter, useSearchParams } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { NoteQuery, NoteSummary } from '@/api/notes'
import type { User } from '@/api/types'
import { SessionProvider } from '@/auth/session'
import { NoteListPage } from './NoteListPage'

/**
 * 자료 목록 화면.
 *
 * 여기서 지키는 것은 **화면이 서버에 무엇을 묻는가**다 — 검색어와 필터가 함께 실리는지
 * (#59 완료 조건), 갈래에 맞는 파라미터만 나가는지, 조건이 URL에 남는지.
 * 서버가 무엇을 돌려주는지는 계약과 API 테스트가 본다.
 */

const api = vi.hoisted(() => ({
  /** 나간 조회 요청. 마지막 것이 지금 화면이 보고 있는 조건이다. */
  queries: [] as NoteQuery[],
  bookmarked: [] as { id: number; next: boolean }[],
  fail: false,
}))

const NOTE: NoteSummary = {
  id: 301,
  category: 'EXAM',
  title: '운영체제 중간고사 정리본',
  subjectName: '운영체제',
  professor: '김교수',
  year: 2026,
  semester: 'SPRING',
  examType: 'MIDTERM',
  uploader: { id: 1, name: '홍길동' },
  fileCount: 2,
  bookmarked: false,
  createdAt: '2026-08-01T09:00:00Z',
}

vi.mock('@/api/notes', () => ({
  list: (query: NoteQuery) => {
    api.queries.push(query)
    if (api.fail) return Promise.reject(new Error('서버 오류'))
    return Promise.resolve({
      content: [NOTE],
      page: { size: 20, number: 0, totalElements: 1, totalPages: 1 },
    })
  },
  filters: () =>
    Promise.resolve({
      subjects: ['운영체제', '자료구조'],
      professors: ['김교수'],
      years: [2026, 2025],
    }),
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

vi.mock('@/api/auth', () => ({
  getMe: () => Promise.resolve(BASE),
  logout: () => Promise.resolve(),
}))

/** 지금 쿼리스트링을 드러내 조회 조건이 URL에 남는지 단언할 수 있게 한다. */
function Address() {
  const [params] = useSearchParams()
  return <div data-testid="query">{params.toString()}</div>
}

/** 갈래는 주소가 정한다 — 빠져 있으면 시험 정리본이다. */
function renderList(path = '/notes') {
  render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <Address />
        <NoteListPage />
      </SessionProvider>
    </MemoryRouter>,
  )
}

/** 마지막으로 나간 조회 조건. */
function lastQuery(): NoteQuery {
  return api.queries[api.queries.length - 1]
}

beforeEach(() => {
  api.queries = []
  api.bookmarked = []
  api.fail = false
})

describe('자료 목록', () => {
  /* 제목은 갈래와 무관하게 하나다 — 갈래는 그 안의 탭이다. */
  it('제목이 자료게시판이고 갈래 탭이 둘 있다', async () => {
    renderList()

    expect(
      await screen.findByRole('heading', { name: '자료게시판' }),
    ).toBeVisible()
    const tabs = screen.getByRole('navigation', { name: '자료 갈래' })
    expect(
      within(tabs)
        .getAllByRole('link')
        .map((tab) => tab.textContent),
    ).toEqual(['시험 정리본', '과목 정리본'])
  })

  /*
   * **갈래가 URL에 남는다.** 탭을 경로가 아니라 쿼리에 두는 이유는 `/notes/:category`가
   * `/notes/123`(상세)까지 삼키기 때문이고, URL에 두는 이유는 새로고침·링크 공유에
   * 살아남아야 하기 때문이다 (`apps/web/AGENTS.md`).
   */
  it('주소의 갈래를 읽어 조회 조건에 싣는다', async () => {
    renderList('/notes?category=SUBJECT')

    expect(await screen.findByText('운영체제 중간고사 정리본')).toBeVisible()
    expect(lastQuery().category).toBe('SUBJECT')
  })

  it('갈래가 빠져 있으면 시험 정리본으로 본다', async () => {
    renderList()

    expect(await screen.findByText('운영체제 중간고사 정리본')).toBeVisible()
    expect(lastQuery().category).toBe('EXAM')
  })

  /* 지금 탭이 어디인지 스크린리더도 알아야 한다. */
  it('현재 갈래 탭에 aria-current가 붙는다', async () => {
    renderList('/notes?category=SUBJECT')
    await screen.findByText('운영체제 중간고사 정리본')

    expect(screen.getByRole('link', { name: '과목 정리본' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(
      screen.getByRole('link', { name: '시험 정리본' }),
    ).not.toHaveAttribute('aria-current')
  })

  /*
   * **탭을 바꾸면 검색·필터가 딸려가지 않는다.** 갈래마다 고를 수 있는 값이 다르고,
   * 특히 시험 구분은 `SUBJECT`에 걸면 결과가 늘 0건이다.
   */
  it('갈래 탭 링크에는 검색·필터가 붙지 않는다', async () => {
    renderList('/notes?category=EXAM&q=중간&examType=MIDTERM')
    await screen.findByText('운영체제 중간고사 정리본')

    expect(screen.getByRole('link', { name: '과목 정리본' })).toHaveAttribute(
      'href',
      '/notes?category=SUBJECT',
    )
  })

  /*
   * **#59 완료 조건 — "검색어와 필터가 함께 적용된다."** 계약도 AND라고 못 박았다
   * (spec §2-1-1 MUST). 한쪽이 다른 쪽을 지우면 여기서 잡힌다.
   */
  it('검색어와 필터가 함께 실린다', async () => {
    renderList()
    await screen.findByText('운영체제 중간고사 정리본')

    fireEvent.change(screen.getByLabelText('과목'), {
      target: { value: '운영체제' },
    })
    fireEvent.change(screen.getByLabelText('검색'), {
      target: { value: '중간' },
    })
    fireEvent.click(screen.getByRole('button', { name: '검색' }))

    await waitFor(() => {
      expect(lastQuery().q).toBe('중간')
    })
    expect(lastQuery().subject).toBe('운영체제')
  })

  /*
   * 조회 조건은 URL에 둔다 (`apps/web/AGENTS.md`) — 뒤로가기·새로고침·링크 공유에
   * 살아남아야 한다. **서버 파라미터 이름을 그대로 쓴다.**
   */
  it('조회 조건이 URL에 남는다', async () => {
    renderList()
    await screen.findByText('운영체제 중간고사 정리본')

    fireEvent.change(screen.getByLabelText('연도'), {
      target: { value: '2025' },
    })

    await waitFor(() => {
      expect(screen.getByTestId('query')).toHaveTextContent('year=2025')
    })
  })

  /* 조건을 바꾸면 페이지를 되돌린다 — 3페이지에서 필터를 바꾸면 빈 화면이 뜬다. */
  it('필터를 바꾸면 page 파라미터가 빠진다', async () => {
    renderList('/notes?page=2')
    await screen.findByText('운영체제 중간고사 정리본')

    fireEvent.change(screen.getByLabelText('학기'), {
      target: { value: 'FALL' },
    })

    await waitFor(() => {
      expect(screen.getByTestId('query')).not.toHaveTextContent('page=')
    })
    expect(screen.getByTestId('query')).toHaveTextContent('semester=FALL')
  })

  /*
   * **시험 구분은 `EXAM`에만 있다** (spec §2-1-1 필터 표). 과목 정리본에 걸면 서버는
   * `400`이 아니라 0건을 주므로(계약 §3-2-4), 화면이 실수해도 조용히 빈 목록만 뜬다 —
   * 그래서 화면 쪽에서 잡아야 한다.
   */
  it('시험 구분 필터는 시험 정리본에만 나온다', async () => {
    renderList('/notes?category=SUBJECT')
    await screen.findByText('운영체제 중간고사 정리본')

    expect(screen.queryByLabelText('시험 구분')).toBeNull()
  })

  it('시험 정리본에는 시험 구분 필터가 나온다', async () => {
    renderList()

    expect(await screen.findByLabelText('시험 구분')).toBeVisible()
  })

  /* 과목 정리본은 `examType`을 아예 보내지 않는다 — 보내면 결과가 늘 0건이다. */
  it('과목 정리본 조회에는 examType이 실리지 않는다', async () => {
    renderList('/notes?category=SUBJECT&examType=MIDTERM')

    await screen.findByText('운영체제 중간고사 정리본')
    expect(lastQuery().examType).toBeUndefined()
  })

  /* 필터 옵션은 등록된 값에서 온다 (계약 §3-2-4 MUST) — 손으로 적어두지 않는다. */
  it('과목·교수·연도 옵션을 서버에서 받아 그린다', async () => {
    renderList()

    const subject = await screen.findByLabelText('과목')
    expect(
      within(subject).getByRole('option', { name: '자료구조' }),
    ).toBeTruthy()
  })

  /*
   * 목록에서도 담고 뺀다 (spec §2-1-5). **토글이 아니라 방향을 정해 보낸다**
   * (계약 §3-2-4 MUST) — 지금 `bookmarked`가 거짓이면 담기다.
   */
  it('별표를 누르면 담기 요청이 나간다', async () => {
    renderList()
    await screen.findByText('운영체제 중간고사 정리본')

    fireEvent.click(
      screen.getByRole('button', { name: '운영체제 중간고사 정리본 즐겨찾기' }),
    )

    await waitFor(() => {
      expect(api.bookmarked).toEqual([{ id: 301, next: true }])
    })
  })

  it('불러오지 못하면 안내가 뜬다', async () => {
    api.fail = true

    renderList()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '자료를 불러오지 못했습니다',
    )
  })
})
