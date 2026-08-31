import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import type { NoteQuery, NoteSummary, Semester } from '@/api/notes'
import type { User } from '@/api/types'
import { SessionProvider } from '@/auth/session'
import {
  MemoryRouter,
  useNavigationType,
  useSearchParams,
} from '@/test/TestRouter'
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
  filterCalls: 0,
  urlRenders: [] as {
    query: string
    navigationType: string
    listCalls: number
    filterCalls: number
  }[],
  content: [] as NoteSummary[],
  /** `GET /bookmarks`로 나간 조회. 토글이 어느 API를 부르는지 가른다. */
  bookmarkCalls: [] as { page?: number; size?: number }[],
  bookmarked: [] as { id: number; next: boolean }[],
  fail: false,
  /** `fail`일 때 던질 오류. 비우면 평범한 서버 오류다. */
  failWith: null as unknown,
  /** 세션 사용자. 비활동 부원으로 갈아끼워 안내 문구가 갈리는지 본다. */
  status: 'ACTIVE' as 'ACTIVE' | 'INACTIVE',
  /** 켜면 `list()`가 응답을 붙들고 있는다. 늦게 도착하는 응답을 만들 때 쓴다. */
  hold: false,
  /** 붙들린 응답의 resolver. 부르는 쪽이 원하는 순간에 완료시킨다. */
  held: [] as ((value: unknown) => void)[],
  totalElements: 1,
  totalPages: 1,
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
  viewCount: 12_345,
  bookmarked: false,
  createdAt: '2026-08-01T09:00:00Z',
}

vi.mock('@/api/notes', () => ({
  NOTE_SORTS: ['latest', 'title', 'views'],
  list: (query: NoteQuery) => {
    api.queries.push(query)
    if (api.fail) return Promise.reject(api.failWith ?? new Error('서버 오류'))
    if (api.hold) {
      return new Promise((resolve) => api.held.push(resolve))
    }
    return Promise.resolve({
      content: api.content,
      page: {
        size: 20,
        number: query.page ?? 0,
        totalElements: api.totalElements,
        totalPages: api.totalPages,
      },
    })
  },
  filters: () => {
    api.filterCalls += 1
    return Promise.resolve({
      subjects: ['운영체제', '자료구조'],
      professors: ['김교수'],
      years: [2026, 2025],
    })
  },
  bookmarks: (query: { page?: number; size?: number }) => {
    api.bookmarkCalls.push(query)
    return Promise.resolve({
      content: [{ ...NOTE, category: 'SUBJECT' as const, bookmarked: true }],
      page: {
        size: 20,
        number: query.page ?? 0,
        totalElements: api.totalElements,
        totalPages: api.totalPages,
      },
    })
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

vi.mock('@/api/auth', () => ({
  getMe: () => Promise.resolve({ ...BASE, status: api.status }),
  logout: () => Promise.resolve(),
}))

/** 지금 쿼리스트링을 드러내 조회 조건이 URL에 남는지 단언할 수 있게 한다. */
function Address() {
  const [params] = useSearchParams()
  const navigationType = useNavigationType()
  api.urlRenders.push({
    query: params.toString(),
    navigationType,
    listCalls: api.queries.length,
    filterCalls: api.filterCalls,
  })
  return (
    <>
      <div data-testid="query">{params.toString()}</div>
      <div data-testid="navigation-type">{navigationType}</div>
    </>
  )
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
  api.filterCalls = 0
  api.urlRenders = []
  api.content = [NOTE]
  api.bookmarkCalls = []
  api.bookmarked = []
  api.fail = false
  api.failWith = null
  api.status = 'ACTIVE'
  api.hold = false
  api.held = []
  api.totalElements = 1
  api.totalPages = 1
})

describe('자료 목록', () => {
  it('학기 필터가 전체와 네 학기를 학사 순서대로 제공한다', async () => {
    renderList()

    const semester = await screen.findByLabelText('학기')
    expect(
      within(semester)
        .getAllByRole('option')
        .map((option) => [option.getAttribute('value'), option.textContent]),
    ).toEqual([
      ['', '전체'],
      ['SPRING', '1학기'],
      ['SUMMER', '여름학기'],
      ['FALL', '2학기'],
      ['WINTER', '겨울학기'],
    ])
  })

  it.each(['SUMMER', 'WINTER'] as const)(
    '공유 주소의 %s를 필터와 서버 요청에 그대로 반영한다',
    async (semester) => {
      renderList(`/notes?semester=${semester}`)

      expect(await screen.findByLabelText('학기')).toHaveValue(semester)
      await waitFor(() => {
        expect(api.queries).toHaveLength(1)
        expect(api.filterCalls).toBe(1)
      })
      expect(lastQuery().semester).toBe(semester)
      expect(screen.getByTestId('navigation-type')).toHaveTextContent('POP')
    },
  )

  it('잘못된 학기는 즉시 replace하고 다른 조건을 보존한 뒤 한 번만 조회한다', async () => {
    api.totalPages = 3
    renderList('/notes?category=EXAM&semester=AUTUMN')

    expect(api.urlRenders[0]).toEqual({
      query: 'category=EXAM&semester=AUTUMN',
      navigationType: 'POP',
      listCalls: 0,
      filterCalls: 0,
    })
    expect(await screen.findByLabelText('학기')).toHaveValue('')
    await waitFor(() => {
      expect(screen.getByTestId('query')).toHaveTextContent('category=EXAM')
      expect(screen.getByTestId('query')).not.toHaveTextContent('semester=')
      expect(api.queries).toHaveLength(1)
      expect(api.filterCalls).toBe(1)
    })
    expect(screen.getByTestId('navigation-type')).toHaveTextContent('REPLACE')
    expect(lastQuery()).toMatchObject({ category: 'EXAM' })
    expect(lastQuery().semester).toBeUndefined()
    expect(
      screen.getByRole('link', { name: '2페이지로 이동' }),
    ).not.toHaveAttribute('href', expect.stringContaining('semester='))

    fireEvent.change(screen.getByLabelText('검색'), {
      target: { value: '운영체제' },
    })
    fireEvent.click(screen.getByRole('button', { name: '검색' }))
    await waitFor(() => {
      expect(screen.getByTestId('query')).toHaveTextContent(
        encodeURIComponent('운영체제'),
      )
    })
    expect(screen.getByTestId('query')).not.toHaveTextContent('semester=')
  })

  it.each(['SUMMER', 'WINTER'] as const)(
    '페이지 링크가 %s 필터를 보존한다',
    async (semester) => {
      api.totalPages = 3
      renderList(`/notes?semester=${semester}`)

      await screen.findByText('운영체제 중간고사 정리본')
      const nextPage = screen.getByRole('link', { name: '2페이지로 이동' })
      expect(nextPage).toHaveAttribute(
        'href',
        expect.stringContaining(`semester=${semester}`),
      )
      fireEvent.click(nextPage)

      await waitFor(() => {
        expect(screen.getByTestId('query')).toHaveTextContent('page=1')
      })
      expect(screen.getByTestId('query')).toHaveTextContent(
        `semester=${semester}`,
      )
      expect(lastQuery()).toMatchObject({ semester, page: 1 })
    },
  )

  it('조회 중과 완료 뒤에도 목록 surface와 pager 자리를 유지한다', async () => {
    renderList()
    expect(
      document.querySelector('[data-list-surface="notes"]'),
    ).toBeInTheDocument()
    expect(
      document.querySelector('[data-pager-slot="true"]'),
    ).toBeInTheDocument()
    await screen.findByRole('table')
    expect(
      document.querySelector('[data-list-surface="notes"]'),
    ).toBeInTheDocument()
  })
  /* 제목은 갈래와 무관하게 하나다 — 갈래는 그 안의 탭이다. */
  it('제목이 자료게시판이고 갈래 탭이 둘 있다', async () => {
    renderList()

    expect(
      await screen.findByRole('heading', { name: '자료게시판' }),
    ).toBeVisible()
    const tabs = screen.getByRole('navigation', { name: '자료 카테고리' })
    expect(
      within(tabs)
        .getAllByRole('link')
        .map((tab) => tab.textContent),
    ).toEqual(['시험 정리본', '과목 정리본'])
  })

  it('모바일 카테고리 메뉴는 세 항목을 한 줄로 두고 44px 터치 높이를 지킨다', async () => {
    renderList()

    await screen.findByText('운영체제 중간고사 정리본')
    const categories = screen.getByRole('navigation', {
      name: '자료 카테고리',
    })
    expect(categories.className).toContain('min-w-0')
    expect(categories.className).toContain('flex-nowrap')
    expect(categories.className).toContain('gap-2')
    expect(categories.className).toContain('sm:gap-1')

    for (const category of within(categories).getAllByRole('link')) {
      expect(category.className).toContain('min-h-11')
      expect(category.className).toContain('shrink-0')
      expect(category.className).toContain('whitespace-nowrap')
      expect(category.className).toContain('px-1')
      expect(category.className).toContain('sm:px-4')
    }

    const favorite = screen.getByRole('link', { name: '즐겨찾기' })
    expect(favorite.className).toContain('min-h-11')
    expect(favorite.className).toContain('shrink-0')
    expect(favorite.className).toContain('whitespace-nowrap')
    expect(favorite.className).toContain('px-1')
    expect(favorite.className).toContain('sm:px-3')
    expect(favorite.className).not.toContain('mb-2')
    expect(favorite.querySelector('svg')).toHaveAttribute('aria-hidden', 'true')

    const row = categories.parentElement
    expect(row?.className).toContain('flex-nowrap')
    expect(row?.className).toContain('gap-1')
    expect(row?.className).toContain('sm:gap-4')
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

  it('sort=views를 조회수순으로 복원해 API에 전달한다', async () => {
    api.totalPages = 4
    renderList('/notes?category=SUBJECT&sort=views&page=2')

    await screen.findByText('운영체제 중간고사 정리본')
    expect(screen.getByLabelText('정렬')).toHaveValue('views')
    expect(lastQuery()).toMatchObject({
      category: 'SUBJECT',
      sort: 'views',
      page: 2,
    })
  })

  it('조회수순을 고르면 기존 조건을 보존하고 0페이지로 돌아간다', async () => {
    api.totalPages = 4
    renderList('/notes?category=SUBJECT&subject=운영체제&page=2')
    await screen.findByText('운영체제 중간고사 정리본')

    fireEvent.change(screen.getByLabelText('정렬'), {
      target: { value: 'views' },
    })

    await waitFor(() => {
      expect(screen.getByTestId('query')).toHaveTextContent('sort=views')
    })
    const query = screen.getByTestId('query').textContent
    if (query === null) throw new Error('주소 쿼리가 없다')
    const params = new URLSearchParams(query)
    expect(params.get('category')).toBe('SUBJECT')
    expect(params.get('subject')).toBe('운영체제')
    expect(params.has('page')).toBe(false)
    expect(lastQuery().page).toBe(0)
  })

  /*
   * **주소는 신뢰 경계다.** 갈래를 URL에 두면 사람이 손으로 고칠 수 있고, 옛 링크나
   * 오타(`?category=exam` 소문자, `?category=INVALID`)가 들어온다.
   *
   * 그 값을 그대로 서버에 넘기면 계약에 없는 `category`가 나가고, 화면 안에서 쓰면
   * `CATEGORY_LABEL[category]`가 `undefined`가 되어 **탭 이름이 빈 채로 그려진다.**
   * 모르는 값은 기본 갈래로 떨어뜨린다 — 오류 화면을 띄울 일이 아니다.
   */
  it.each(['INVALID', 'exam', 'subject', ''])(
    '갈래가 `%s`처럼 계약에 없는 값이면 시험 정리본으로 떨어진다',
    async (raw) => {
      renderList(`/notes?category=${raw}`)

      expect(await screen.findByText('운영체제 중간고사 정리본')).toBeVisible()
      expect(lastQuery().category).toBe('EXAM')
      // 탭 이름이 비지 않는다 — 기본 갈래가 실제로 선택된 상태여야 한다.
      expect(screen.getByRole('link', { name: '시험 정리본' })).toHaveAttribute(
        'aria-current',
        'page',
      )
    },
  )

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

  it('갈래 탭은 검색·필터를 지우되 비기본 정렬을 보존한다', async () => {
    api.totalPages = 4
    renderList('/notes?category=EXAM&q=중간&examType=MIDTERM&sort=views&page=2')
    await screen.findByText('운영체제 중간고사 정리본')

    expect(screen.getByRole('link', { name: '과목 정리본' })).toHaveAttribute(
      'href',
      '/notes?category=SUBJECT&sort=views',
    )
  })

  it('페이지 이동 링크와 요청에 category와 views 정렬을 보존한다', async () => {
    api.totalPages = 3
    renderList('/notes?category=SUBJECT&sort=views')
    await screen.findByText('운영체제 중간고사 정리본')

    const secondPage = screen.getByRole('link', { name: '2페이지로 이동' })
    expect(secondPage).toHaveAttribute(
      'href',
      '/notes?category=SUBJECT&sort=views&page=1',
    )
    fireEvent.click(secondPage)

    await waitFor(() => {
      expect(lastQuery()).toMatchObject({
        category: 'SUBJECT',
        sort: 'views',
        page: 1,
      })
    })
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

  it('페이지 번호 이동은 자료의 모든 query를 보존하고 0-based page만 바꾼다', async () => {
    api.totalElements = 400
    api.totalPages = 20
    renderList(
      '/notes?category=EXAM&bookmarked=false&q=%EC%A4%91%EA%B0%84&subject=%EC%9A%B4%EC%98%81%EC%B2%B4%EC%A0%9C&professor=%EA%B9%80%EA%B5%90%EC%88%98&year=2026&semester=SUMMER&examType=MIDTERM&sort=title&page=9',
    )
    await screen.findByRole('table')

    expect(
      screen.getByRole('link', { name: '10페이지로 이동', current: 'page' }),
    ).toBeInTheDocument()
    expect(
      document.querySelectorAll(
        '[data-pager-mobile-visible="true"] [data-slot="pagination-ellipsis"]',
      ),
    ).toHaveLength(2)
    expect(
      document.querySelectorAll(
        '[data-pager-desktop-visible="true"] [data-slot="pagination-ellipsis"]',
      ),
    ).toHaveLength(2)
    fireEvent.click(screen.getByRole('link', { name: '12페이지로 이동' }))

    await waitFor(() => expect(lastQuery().page).toBe(11))
    const params = new URLSearchParams(
      screen.getByTestId('query').textContent ?? '',
    )
    expect(Object.fromEntries(params)).toEqual({
      category: 'EXAM',
      bookmarked: 'false',
      q: '중간',
      subject: '운영체제',
      professor: '김교수',
      year: '2026',
      semester: 'SUMMER',
      examType: 'MIDTERM',
      sort: 'title',
      page: '11',
    })
    expect(lastQuery()).toMatchObject({
      category: 'EXAM',
      q: '중간',
      subject: '운영체제',
      professor: '김교수',
      year: 2026,
      semester: 'SUMMER',
      examType: 'MIDTERM',
      sort: 'title',
      page: 11,
    })
  })

  it('즐겨찾기 목록도 같은 번호 이동에서 bookmarked query를 보존한다', async () => {
    api.totalElements = 400
    api.totalPages = 20
    renderList('/notes?bookmarked=true&page=9')
    await screen.findByRole('table')

    fireEvent.click(screen.getByRole('link', { name: '12페이지로 이동' }))

    await waitFor(() => expect(api.bookmarkCalls.at(-1)?.page).toBe(11))
    expect(screen.getByTestId('query')).toHaveTextContent('bookmarked=true')
    expect(screen.getByTestId('query')).toHaveTextContent('page=11')
  })

  it.each([
    ['/notes?category=SUBJECT', 0, '이전 페이지로 이동'],
    ['/notes?category=SUBJECT&page=19', 19, '다음 페이지로 이동'],
  ] as const)(
    '경계 %s에서 비활성 방향 링크를 눌러도 query와 조회를 바꾸지 않는다',
    async (path, page, label) => {
      api.totalElements = 400
      api.totalPages = 20
      renderList(path)
      await screen.findByRole('table')
      const queries = [...api.queries]
      const query = screen.getByTestId('query').textContent

      const boundary = screen.getByRole('link', { name: label })
      expect(boundary).toHaveAttribute('aria-disabled', 'true')
      fireEvent.click(boundary)

      expect(screen.getByTestId('query').textContent).toBe(query)
      expect(api.queries).toEqual(queries)
      expect(lastQuery().page).toBe(page)
    },
  )

  it('1페이지 번호는 다른 자료 query를 보존하고 page만 지운다', async () => {
    api.totalElements = 60
    api.totalPages = 3
    renderList('/notes?category=SUBJECT&q=%EC%9A%B4%EC%98%81&page=2')
    await screen.findByRole('table')

    fireEvent.click(screen.getByRole('link', { name: '1페이지로 이동' }))

    await waitFor(() => expect(lastQuery().page).toBe(0))
    expect(screen.getByTestId('query')).toHaveTextContent('category=SUBJECT')
    expect(screen.getByTestId('query')).toHaveTextContent(
      'q=%EC%9A%B4%EC%98%81',
    )
    expect(screen.getByTestId('query')).not.toHaveTextContent('page=')
  })

  /* 조건을 바꾸면 페이지를 되돌린다 — 3페이지에서 필터를 바꾸면 빈 화면이 뜬다. */
  it.each(['SUMMER', 'WINTER'] as const)(
    '%s 필터로 바꾸면 page 파라미터가 빠진다',
    async (semester) => {
      renderList('/notes?page=2')
      await screen.findByText('운영체제 중간고사 정리본')

      fireEvent.change(screen.getByLabelText('학기'), {
        target: { value: semester },
      })

      await waitFor(() => {
        expect(screen.getByTestId('query')).not.toHaveTextContent('page=')
      })
      expect(screen.getByTestId('query')).toHaveTextContent(
        `semester=${semester}`,
      )
    },
  )

  /*
   * 범위를 넘은 주소로 들어오면 마지막 유효 페이지로 되돌린다 — **그 자리가 0페이지여도
   * `page=`를 붙이지 않는다** (#283).
   *
   * 0페이지는 파라미터가 없는 상태로 표현하기로 했고 페이지 링크·필터 초기화가 그렇게 한다.
   * 클램프만 `page=0`을 쓰면 **어떻게 도착했느냐에 따라 주소가 달라져** 그 주소가 그대로
   * 공유·북마크된다. 기존 클램프 사례들이 되돌아갈 곳을 1페이지 이상으로 잡아 이 자리를
   * 한 번도 밟지 않았다.
   */
  it('범위를 넘어 0페이지로 되돌아가도 page 파라미터가 붙지 않는다', async () => {
    renderList('/notes?category=EXAM&page=5')
    await screen.findByText('운영체제 중간고사 정리본')

    await waitFor(() => {
      expect(screen.getByTestId('query')).not.toHaveTextContent('page=')
    })
    expect(screen.getByTestId('query')).toHaveTextContent('category=EXAM')
  })

  /**
   * **늦게 도착한 조회는 주소를 건드리지 않는다** (#283 후속).
   *
   * 범위를 넘은 페이지의 응답이 오는 사이에 사용자가 필터를 바꾸면, 그 응답으로 되돌리기를
   * 하면 **방금 고른 필터가 사라진다** — 되돌리기의 기준이 그 조회를 낼 때의 낡은 주소이기
   * 때문이다. `replace`라 히스토리에도 남지 않아 왜 풀렸는지 알 수 없다.
   *
   * 조회의 `alive` 가드가 그것을 막는다. 그 가드가 빠지면 이 사례가 깨진다.
   */
  it('늦게 도착한 조회가 방금 바꾼 필터를 덮어쓰지 않는다', async () => {
    api.hold = true
    renderList('/notes?category=EXAM&page=5')

    // 아직 응답 전이지만 필터는 그려져 있다.
    fireEvent.change(screen.getByLabelText('학기'), {
      target: { value: 'WINTER' },
    })
    await waitFor(() => {
      expect(screen.getByTestId('query')).toHaveTextContent('semester=WINTER')
    })

    // 이제 낡은 조회(page=5)의 응답을 완료시킨다.
    const stale = api.held[0]
    api.hold = false
    await act(async () => {
      stale({
        content: [NOTE],
        page: { size: 20, number: 0, totalElements: 1, totalPages: 1 },
      })
    })

    expect(screen.getByTestId('query')).toHaveTextContent('semester=WINTER')
    expect(screen.getByTestId('query')).toHaveTextContent('category=EXAM')
    expect(screen.getByTestId('query')).not.toHaveTextContent('page=')
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

  it('표가 네 학기 라벨을 표시하고 기존 1·2학기 문구도 유지한다', async () => {
    const semesters: Semester[] = ['SPRING', 'SUMMER', 'FALL', 'WINTER']
    api.content = semesters.map((semester, index) => ({
      ...NOTE,
      id: NOTE.id + index,
      title: `${semester} 자료`,
      semester,
    }))

    renderList()
    await screen.findByText('SPRING 자료')

    for (const label of [
      '2026년 1학기 · 중간',
      '2026년 여름학기 · 중간',
      '2026년 2학기 · 중간',
      '2026년 겨울학기 · 중간',
    ]) {
      expect(screen.getByText(label)).toBeVisible()
    }
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
    expect(screen.getByRole('status')).toHaveTextContent(
      '즐겨찾기에 담았습니다.',
    )
  })

  it('일반 목록에 조회수 열과 숫자를 보여준다', async () => {
    renderList()
    await screen.findByText('운영체제 중간고사 정리본')

    expect(screen.getByRole('columnheader', { name: '조회수' })).toBeVisible()
    expect(screen.getByRole('cell', { name: '12345' })).toBeVisible()
  })

  /* 자료를 올리는 진입점. 문구가 바뀌면 여기서 잡힌다. */
  it('업로드 진입점이 있다', async () => {
    renderList()

    expect(await screen.findByRole('link', { name: '업로드' })).toHaveAttribute(
      'href',
      '/notes/new?category=EXAM',
    )
  })

  /*
   * **즐겨찾기는 목적지가 아니라 이 목록을 추리는 조건이다** (#261). 별도 화면이던 것을
   * 여기로 접었다 — 갈래를 탭으로 접은 것과 같은 판단이다 (#59).
   */
  it('즐겨찾기 토글이 URL에 상태를 남긴다', async () => {
    renderList()
    await screen.findByText('운영체제 중간고사 정리본')

    const favorite = screen.getByRole('link', { name: '즐겨찾기' })
    expect(favorite).toHaveAttribute('href', '/notes?bookmarked=true')
    expect(favorite).not.toHaveAttribute('aria-current')
    const star = favorite.querySelector('svg')
    expect(star).toHaveAttribute('aria-hidden', 'true')
    expect(star?.className.baseVal).not.toContain('fill-current')
  })

  /*
   * `GET /notes`에는 `bookmarked` 필터가 없으므로 **다른 API를 부른다.** 계약이 두 응답의
   * 형태를 같게 맞춰 두어(§3-2-4) 표는 한 벌로 충분하다.
   */
  it('토글이 켜지면 즐겨찾기 API를 부른다', async () => {
    renderList('/notes?bookmarked=true&sort=views')

    await screen.findByText('운영체제 중간고사 정리본')
    expect(api.bookmarkCalls).toEqual([{ page: 0, size: 20 }])
    // 목록 API는 부르지 않는다 — 두 번 조회하면 그만큼 낭비다.
    expect(api.queries).toEqual([])

    const favorite = screen.getByRole('link', { name: '즐겨찾기' })
    expect(favorite).toHaveAttribute('href', '/notes?category=EXAM')
    expect(favorite).toHaveAttribute('aria-current', 'page')
    const star = favorite.querySelector('svg')
    expect(star).toHaveAttribute('aria-hidden', 'true')
    expect(star?.className.baseVal).toContain('fill-current')
  })

  it('토글이 꺼져 있으면 목록 API만 부른다', async () => {
    renderList()

    await screen.findByText('운영체제 중간고사 정리본')
    expect(api.queries).toHaveLength(1)
    expect(api.bookmarkCalls).toEqual([])
  })

  /*
   * **`GET /bookmarks`는 검색·필터를 받지 않는다** (계약 §3-2-4 — "이미 본인이 추린
   * 목록이다"). 남겨 두고 눌러도 안 먹으면 화면이 거짓말을 한다.
   */
  it('토글이 켜지면 검색·필터·갈래 탭이 사라진다', async () => {
    renderList('/notes?bookmarked=true')
    await screen.findByText('운영체제 중간고사 정리본')

    expect(screen.queryByLabelText('검색')).toBeNull()
    expect(screen.queryByLabelText('과목')).toBeNull()
    expect(screen.queryByLabelText('정렬')).toBeNull()
    expect(
      screen.queryByRole('navigation', { name: '자료 카테고리' }),
    ).toBeNull()
  })

  /* 담아둔 목록에는 시험·과목이 섞여 오므로 갈래 열이 필요하다. */
  it('토글이 켜지면 카테고리 열이 보인다', async () => {
    renderList('/notes?bookmarked=true')
    await screen.findByText('운영체제 중간고사 정리본')

    expect(screen.getByRole('columnheader', { name: '카테고리' })).toBeVisible()
    expect(screen.getByRole('columnheader', { name: '조회수' })).toBeVisible()
    expect(screen.getByRole('cell', { name: '12345' })).toBeVisible()
  })

  it('토글이 꺼져 있으면 카테고리 열이 없다', async () => {
    renderList()
    await screen.findByText('운영체제 중간고사 정리본')

    expect(screen.queryByRole('columnheader', { name: '카테고리' })).toBeNull()
  })

  /* 토글을 끄면 갈래 탭이 있는 평소 목록으로 돌아간다. */
  it('켜진 토글은 갈래 목록으로 돌아가는 링크가 된다', async () => {
    renderList('/notes?bookmarked=true')
    await screen.findByText('운영체제 중간고사 정리본')

    expect(screen.getByRole('link', { name: '즐겨찾기' })).toHaveAttribute(
      'href',
      '/notes?category=EXAM',
    )
  })

  it('불러오지 못하면 안내가 뜬다', async () => {
    api.fail = true

    renderList()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '자료를 불러오지 못했습니다',
    )
    expect(screen.getAllByRole('alert')).toHaveLength(1)
    expect(
      document.querySelector('[data-live-alert-viewport="true"]'),
    ).not.toBeInTheDocument()
    api.fail = false
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(await screen.findByRole('table')).toBeVisible()
    expect(
      document.querySelector('[data-pager-slot="true"]'),
    ).toBeInTheDocument()
  })

  /*
   * #231 — 비활동 부원에게 **"잠시 후 다시 시도해 주세요"는 거짓말이다.** 그 사람은 이번
   * 학기 내내 같은 `403 INACTIVE`를 받는다. 로그인 화면으로 튕기지 않는 대신 왜 막혔는지가
   * 화면에 있어야 한다 (spec §3-1-5 — "세션을 INACTIVE로 정리하되 내보내지 않는다").
   *
   * 자료 화면 전체를 이 사람에게 어떻게 보여줄지는 #59가 맡는다. 여기는 그 화면이 없는
   * 동안 영문을 모르지 않게 하는 최소한이다.
   */
  it('403 INACTIVE면 다시 시도하라는 대신 사유를 알려준다', async () => {
    api.fail = true
    api.failWith = new ApiError(
      'INACTIVE',
      403,
      '이번 학기 활동 부원이 아닙니다.',
    )
    api.status = 'INACTIVE'

    renderList()

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(
      '이번 학기 비활동 부원은 자료를 이용할 수 없습니다',
    )
    expect(alert).not.toHaveTextContent('잠시 후 다시 시도')
  })
})
