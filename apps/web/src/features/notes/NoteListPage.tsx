import { type FormEvent, useCallback, useEffect, useState } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import {
  type Category,
  type ExamType,
  filters as fetchFilters,
  list,
  type NoteFilterOptions,
  type NoteSummary,
  type Semester,
  setBookmark,
} from '@/api/notes'
import type { Page } from '@/api/types'
import { useSession } from '@/auth/session'
import { SELECT_CLASS, SelectArrow } from '@/components/native-select'
import { Pager, parsePage } from '@/components/Pager'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { CATEGORY_LABEL, EXAM_TYPE_LABEL, SEMESTER_LABEL } from './labels'
import { NoteTable } from './NoteTable'

const PAGE_SIZE = 20

/** 정렬 선택지. 값은 계약의 `sort` 파라미터 그대로다 (spec §3-2-4). */
const SORTS = [
  { value: 'latest', label: '최신순' },
  { value: 'title', label: '제목순' },
] as const

/**
 * 자료 목록 화면. **시험 정리본과 과목 정리본이 한 컴포넌트다.**
 *
 * 두 화면은 `category` 하나만 다르고 검색·필터·정렬·페이지네이션 규칙이 전부 같다
 * (spec §2-1-1). 갈라두면 필터 조합 규칙이 두 벌이 되고 한쪽만 고치는 날이 온다.
 * **시험 구분 필터만 `EXAM`에 노출된다** — 그 갈래에만 있는 값이다.
 *
 * **갈래는 라우트가 prop으로 준다.** 주소에서 읽으면(`/notes/:category`) 그 패턴이
 * `/notes/123`(상세)까지 삼켜, 자료 id가 갈래 이름으로 해석된다.
 */
export function NoteListPage({ category }: { category: Category }) {
  const [searchParams, setSearchParams] = useSearchParams()
  const { pathname } = useLocation()
  const { state, reportApiError } = useSession()

  const page = parsePage(searchParams.get('page'))
  const q = searchParams.get('q') ?? ''
  const subject = searchParams.get('subject') ?? ''
  const professor = searchParams.get('professor') ?? ''
  const year = searchParams.get('year') ?? ''
  const semester = searchParams.get('semester') ?? ''
  const examType = searchParams.get('examType') ?? ''
  const sort = searchParams.get('sort') === 'title' ? 'title' : 'latest'

  /** 입력 중인 검색어. **제출해야 URL에 들어간다** — 한 글자마다 조회하지 않는다. */
  const [draft, setDraft] = useState(q)
  const [data, setData] = useState<Page<NoteSummary> | null>(null)
  const [failed, setFailed] = useState(false)
  const [options, setOptions] = useState<NoteFilterOptions | null>(null)
  const [pending, setPending] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [reloadKey, setReloadKey] = useState(0)

  /** 주소가 바뀌어 화면이 다시 그려지면 입력 칸도 그 값에서 시작한다. */
  useEffect(() => {
    setDraft(q)
  }, [q])

  // biome-ignore lint/correctness/useExhaustiveDependencies: reloadKey는 본문에서 읽지 않고 재조회 트리거로만 쓴다.
  useEffect(() => {
    let alive = true
    setData(null)
    setFailed(false)
    list({
      category,
      q: q || undefined,
      subject: subject || undefined,
      professor: professor || undefined,
      year: year === '' ? undefined : Number(year),
      semester: (semester || undefined) as Semester | undefined,
      // 시험 구분은 `EXAM`에만 붙인다. 과목 정리본에 걸면 결과가 늘 0건이다.
      examType:
        category === 'EXAM'
          ? ((examType || undefined) as ExamType | undefined)
          : undefined,
      sort,
      page,
      size: PAGE_SIZE,
    })
      .then((result) => {
        if (alive) setData(result)
      })
      .catch((caught: unknown) => {
        if (!alive) return
        // #36 계약 — 403 PENDING_APPROVAL이면 가드가 대기 화면으로 되돌린다.
        reportApiError(caught)
        setFailed(true)
      })
    return () => {
      alive = false
    }
  }, [
    category,
    q,
    subject,
    professor,
    year,
    semester,
    examType,
    sort,
    page,
    reloadKey,
    reportApiError,
  ])

  /**
   * 필터 옵션은 **한 번만** 받는다. 검색·페이지마다 다시 받으면 요청이 두 배가 되는데,
   * 등록된 과목 목록은 그 사이에 바뀌지 않는다.
   *
   * 실패해도 화면을 막지 않는다 — 옵션이 없으면 `<select>`가 "전체"만 남고 검색은 된다.
   */
  useEffect(() => {
    let alive = true
    fetchFilters()
      .then((result) => {
        if (alive) setOptions(result)
      })
      .catch((caught: unknown) => {
        if (alive) reportApiError(caught)
      })
    return () => {
      alive = false
    }
  }, [reportApiError])

  /**
   * F-2 — 범위를 넘은 `page`로 들어오면 마지막 유효 페이지로 되돌린다. 그냥 두면 자료가
   * 있는데도 "자료가 없습니다"가 뜬다. `totalPages`가 0이면 되돌릴 곳이 없어 움직이지 않는다.
   */
  useEffect(() => {
    if (!data) return
    const { totalPages } = data.page
    if (totalPages >= 1 && page >= totalPages) {
      const next = new URLSearchParams(searchParams)
      next.set('page', String(totalPages - 1))
      setSearchParams(next, { replace: true })
    }
  }, [data, page, searchParams, setSearchParams])

  /**
   * 조회 조건을 URL에 쓰는 **유일한 지점** (`apps/web/AGENTS.md` — 뒤로가기·새로고침·링크
   * 공유에 살아남아야 한다). **서버 파라미터 이름을 그대로 쓴다** — 합성 값을 쓰면 주소만
   * 보고는 무엇을 조회하는지 알 수 없다.
   *
   * **조건을 바꾸면 페이지를 0으로 되돌린다.** 3페이지에서 과목을 바꾸면 결과가 1페이지뿐일
   * 수 있는데, 그때 빈 화면을 보여주고 위 effect가 뒤늦게 되돌리면 화면이 두 번 튄다.
   */
  const setParam = useCallback(
    (key: string, value: string) => {
      const next = new URLSearchParams(searchParams)
      if (value === '') next.delete(key)
      else next.set(key, value)
      next.delete('page')
      setSearchParams(next)
    },
    [searchParams, setSearchParams],
  )

  function submitSearch(event: FormEvent) {
    event.preventDefault()
    setParam('q', draft.trim())
  }

  function pageHref(next: number): string {
    const params = new URLSearchParams(searchParams)
    if (next === 0) params.delete('page')
    else params.set('page', String(next))
    const query = params.toString()
    return query === '' ? pathname : `${pathname}?${query}`
  }

  function goToPage(next: number) {
    const params = new URLSearchParams(searchParams)
    if (next === 0) params.delete('page')
    else params.set('page', String(next))
    setSearchParams(params)
  }

  /**
   * 담기·빼기. **낙관적으로 바꾸지 않는다** — 실패했는데 별표가 켜진 채로 남으면 사용자는
   * 담긴 줄 안다. 성공하면 목록을 다시 읽어 서버가 준 `bookmarked`로 그린다.
   */
  async function toggleBookmark(note: NoteSummary) {
    setPending(true)
    setNotice(null)
    try {
      await setBookmark(note.id, !note.bookmarked)
      setReloadKey((key) => key + 1)
    } catch (caught: unknown) {
      reportApiError(caught)
      setNotice('즐겨찾기를 바꾸지 못했습니다. 다시 시도해 주세요.')
    } finally {
      setPending(false)
    }
  }

  const filtered =
    q !== '' ||
    subject !== '' ||
    professor !== '' ||
    year !== '' ||
    semester !== '' ||
    examType !== ''

  return (
    <section>
      <div className="flex items-center justify-between gap-4">
        <h1 className="text-2xl font-semibold tracking-tight">
          {CATEGORY_LABEL[category]}
        </h1>
        {/*
         * 등록은 `ACTIVE`면 누구나 할 수 있다 (spec §3-1-3 매트릭스 — 자료 업로드는
         * USER·ADMIN 모두 `O`). 관리자 전용이 아니므로 `/admin` 아래에 두지 않는다.
         */}
        <Button variant="outline" size="sm" asChild>
          <Link to={`/notes/new?category=${category}`}>자료 올리기</Link>
        </Button>
      </div>

      {/*
       * **검색어와 필터는 AND로 함께 걸린다** (spec §2-1-1 MUST). 하나의 폼 안에 두어
       * 서로 배타적인 것이 아님을 화면으로 보여준다.
       */}
      <form
        onSubmit={submitSearch}
        className="mt-6 flex flex-wrap items-end gap-3"
      >
        <div className="grow space-y-2 sm:grow-0">
          <Label htmlFor="note-search">검색</Label>
          {/* **통합 검색이다** — 제목·과목·교수를 한 칸으로 받는다 (§2-1-1 MUST). */}
          <Input
            id="note-search"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            placeholder="제목 · 과목 · 교수"
            className="sm:w-64"
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="note-subject">과목</Label>
          <select
            id="note-subject"
            value={subject}
            onChange={(event) => setParam('subject', event.target.value)}
            className={SELECT_CLASS}
            style={SelectArrow}
          >
            <option value="">전체</option>
            {/* **실제 등록된 값만 고를 수 있다** (§3-2-4 MUST) — 없는 과목은 늘 0건이다. */}
            {options?.subjects.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
        </div>

        <div className="space-y-2">
          <Label htmlFor="note-professor">교수</Label>
          <select
            id="note-professor"
            value={professor}
            onChange={(event) => setParam('professor', event.target.value)}
            className={SELECT_CLASS}
            style={SelectArrow}
          >
            <option value="">전체</option>
            {options?.professors.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
        </div>

        <div className="space-y-2">
          <Label htmlFor="note-year">연도</Label>
          <select
            id="note-year"
            value={year}
            onChange={(event) => setParam('year', event.target.value)}
            className={SELECT_CLASS}
            style={SelectArrow}
          >
            <option value="">전체</option>
            {options?.years.map((value) => (
              <option key={value} value={value}>
                {value}년
              </option>
            ))}
          </select>
        </div>

        <div className="space-y-2">
          <Label htmlFor="note-semester">학기</Label>
          {/* 학기·시험 구분은 값이 enum으로 고정이라 서버가 옵션을 내려주지 않는다 (§3-2-4). */}
          <select
            id="note-semester"
            value={semester}
            onChange={(event) => setParam('semester', event.target.value)}
            className={SELECT_CLASS}
            style={SelectArrow}
          >
            <option value="">전체</option>
            {Object.entries(SEMESTER_LABEL).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </div>

        {/* **시험 구분은 `EXAM`에만 노출된다** (spec §2-1-1 필터 표). */}
        {category === 'EXAM' && (
          <div className="space-y-2">
            <Label htmlFor="note-exam-type">시험 구분</Label>
            <select
              id="note-exam-type"
              value={examType}
              onChange={(event) => setParam('examType', event.target.value)}
              className={SELECT_CLASS}
              style={SelectArrow}
            >
              <option value="">전체</option>
              {Object.entries(EXAM_TYPE_LABEL).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </div>
        )}

        <div className="space-y-2">
          <Label htmlFor="note-sort">정렬</Label>
          <select
            id="note-sort"
            value={sort}
            onChange={(event) => setParam('sort', event.target.value)}
            className={SELECT_CLASS}
            style={SelectArrow}
          >
            {SORTS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>

        <Button type="submit" variant="outline">
          검색
        </Button>
      </form>

      {notice && (
        <p role="alert" className="mt-6 text-sm text-muted-foreground">
          {notice}
        </p>
      )}

      {data === null && !failed && (
        <p className="mt-8 text-sm text-muted-foreground">불러오는 중</p>
      )}

      {failed && (
        <p role="alert" className="mt-8 text-sm text-muted-foreground">
          자료를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
        </p>
      )}

      {data !== null && data.content.length === 0 && (
        /*
         * **조건이 걸려 0건인 것과 아예 없는 것을 가른다.** 검색해서 없는 사람에게
         * "등록된 자료가 없습니다"라고 하면 검색어를 지워볼 생각을 못 한다.
         */
        <p className="mt-8 text-sm text-muted-foreground">
          {filtered
            ? '조건에 맞는 자료가 없습니다. 검색어나 필터를 바꿔 보세요.'
            : '등록된 자료가 없습니다.'}
        </p>
      )}

      {data !== null && data.content.length > 0 && (
        <>
          <p className="mt-6 text-sm text-muted-foreground">
            전체 {data.page.totalElements}건
          </p>
          <NoteTable
            notes={data.content}
            showCategory={false}
            onToggleBookmark={toggleBookmark}
            busy={pending || state.kind !== 'active'}
          />
          <Pager
            className="mt-8"
            page={page}
            totalPages={data.page.totalPages}
            hrefFor={pageHref}
            onGo={goToPage}
          />
        </>
      )}
    </section>
  )
}
