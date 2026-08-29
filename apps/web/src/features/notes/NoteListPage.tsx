import { Star } from 'lucide-react'
import { type FormEvent, useCallback, useEffect, useState } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import {
  bookmarks,
  type Category,
  type ExamType,
  filters as fetchFilters,
  list,
  type NoteFilterOptions,
  type NoteSummary,
  setBookmark,
} from '@/api/notes'
import type { Page } from '@/api/types'
import { isInactive, useSession } from '@/auth/session'
import { clampedOutOfRange } from '@/components/clampPage'
import { SELECT_CLASS, SelectArrow } from '@/components/native-select'
import { Pager, parsePage, writePage } from '@/components/Pager'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { cn } from '@/lib/utils'
import {
  CATEGORY_LABEL,
  categoryFromParam,
  categoryPath,
  EXAM_TYPE_LABEL,
  noteErrorText,
  SEMESTER_LABEL,
  semesterFromParam,
} from './labels'
import { NoteTable } from './NoteTable'

const PAGE_SIZE = 20

/** 정렬 선택지. 값은 계약의 `sort` 파라미터 그대로다 (spec §3-2-4). */
const SORTS = [
  { value: 'latest', label: '최신순' },
  { value: 'title', label: '제목순' },
] as const

/** 다음 URL을 만들 때 계약에 없는 학기 값이 검색·페이지 상태에 따라붙지 않게 한다. */
function normalizedParams(
  current: URLSearchParams,
  semester: ReturnType<typeof semesterFromParam>,
): URLSearchParams {
  const next = new URLSearchParams(current)
  if (semester === undefined) next.delete('semester')
  return next
}

/**
 * 자료게시판. **시험 정리본과 과목 정리본이 한 화면이고 갈래는 탭으로 가른다.**
 *
 * 처음에는 라우트를 둘로 나눴다(`/notes/exam`·`/notes/subject`). 두 화면은 `category`
 * 하나만 다르고 검색·필터·정렬·페이지네이션 규칙이 전부 같은데(spec §2-1-1), **화면을
 * 나누면 헤더 메뉴도 둘이 되어 "자료를 보러 간다"는 한 가지 일이 두 갈래로 갈린다.**
 * 갈래는 그 안에서 고르는 조건이지 다른 목적지가 아니다.
 *
 * **갈래는 URL 쿼리에 둔다** (`?category=`) — 새로고침·뒤로가기·링크 공유에 살아남아야
 * 하고(`apps/web/AGENTS.md`), 이름도 서버 파라미터 그대로다. 경로 조각(`/notes/:category`)
 * 으로 두지 않는 이유는 그 패턴이 `/notes/123`(상세)까지 삼키기 때문이다.
 *
 * **시험 구분 필터만 `EXAM` 탭에 나온다** — 그 갈래에만 있는 값이다.
 */
export function NoteListPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const { pathname } = useLocation()
  const session = useSession()
  const { state, reportApiError } = session

  const category = categoryFromParam(searchParams.get('category'))
  /**
   * **담아둔 것만 볼지** (#261). 별도 화면이 아니라 이 목록을 추리는 조건이라 여기 있고,
   * 갈래와 같은 이유로 URL에 남긴다 — 새로고침·뒤로가기·링크 공유에 살아남아야 한다.
   */
  const onlyBookmarked = searchParams.get('bookmarked') === 'true'
  const page = parsePage(searchParams.get('page'))
  const q = searchParams.get('q') ?? ''
  const subject = searchParams.get('subject') ?? ''
  const professor = searchParams.get('professor') ?? ''
  const year = searchParams.get('year') ?? ''
  const rawSemester = searchParams.get('semester')
  const semester = semesterFromParam(rawSemester)
  const hasInvalidSemester = rawSemester !== null && semester === undefined
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
    setData(null)
    setFailed(false)
    /*
     * **주소가 조회 조건의 단일 원천이다.** 모르는 학기를 화면과 API에서만 전체로 취급하고
     * 주소에 남겨 두면 공유 URL은 실제 조회와 다른 말을 한다. 다른 조건은 보존하고 학기만
     * 제거해 replace한다. 이 렌더에서는 조회하지 않아 canonical URL로 다시 그린 뒤 한 번만
     * 요청한다 — 여기서 계속하면 같은 전체 목록을 두 번 부르게 된다.
     */
    if (hasInvalidSemester) {
      setSearchParams(normalizedParams(searchParams, semester), {
        replace: true,
      })
      return
    }

    let alive = true
    /*
     * **두 API를 갈라 부른다** (#261). `GET /notes`에는 `bookmarked` 필터가 없고
     * `GET /bookmarks`는 검색·필터를 받지 않는다 (계약 §3-2-4 — "이미 본인이 추린
     * 목록이다"). 계약이 **두 응답의 형태를 같게 맞춰 두어** 표는 한 벌로 충분하다.
     *
     * 서버에 `bookmarked` 파라미터가 생기면 이 분기만 걷어내면 된다.
     */
    const query = onlyBookmarked
      ? bookmarks({ page, size: PAGE_SIZE })
      : list({
          category,
          q: q || undefined,
          subject: subject || undefined,
          professor: professor || undefined,
          year: year === '' ? undefined : Number(year),
          semester,
          // 시험 구분은 `EXAM`에만 붙인다. 과목 정리본에 걸면 결과가 늘 0건이다.
          examType:
            category === 'EXAM'
              ? ((examType || undefined) as ExamType | undefined)
              : undefined,
          sort,
          page,
          size: PAGE_SIZE,
        })
    query
      .then((result) => {
        /*
         * **이 조회가 아직 유효할 때만 손댄다.** 조건이 바뀌면 정리 함수가 `alive`를
         * 내리므로 여기를 건너뛴다 — 그 가드가 아래 되돌리기의 안전장치다.
         */
        if (!alive) return
        if (
          clampedOutOfRange(
            result,
            page,
            normalizedParams(searchParams, semester),
            setSearchParams,
          )
        )
          return
        setData(result)
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
    onlyBookmarked,
    category,
    q,
    subject,
    professor,
    year,
    semester,
    hasInvalidSemester,
    examType,
    sort,
    page,
    reloadKey,
    reportApiError,
    searchParams,
    setSearchParams,
  ])

  /**
   * 필터 옵션은 **한 번만** 받는다. 검색·페이지마다 다시 받으면 요청이 두 배가 되는데,
   * 등록된 과목 목록은 그 사이에 바뀌지 않는다.
   *
   * 실패해도 화면을 막지 않는다 — 옵션이 없으면 `<select>`가 "전체"만 남고 검색은 된다.
   */
  useEffect(() => {
    // 잘못된 URL을 정규화하는 렌더에서는 옵션도 조회하지 않는다. canonical URL 뒤 한 번만 받는다.
    if (hasInvalidSemester) return

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
  }, [hasInvalidSemester, reportApiError])

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
      const next = normalizedParams(searchParams, semester)
      if (value === '') next.delete(key)
      else next.set(key, value)
      writePage(next, 0)
      setSearchParams(next)
    },
    [searchParams, semester, setSearchParams],
  )

  function submitSearch(event: FormEvent) {
    event.preventDefault()
    setParam('q', draft.trim())
  }

  function pageHref(next: number): string {
    const params = normalizedParams(searchParams, semester)
    writePage(params, next)
    const query = params.toString()
    return query === '' ? pathname : `${pathname}?${query}`
  }

  function goToPage(next: number) {
    const params = normalizedParams(searchParams, semester)
    writePage(params, next)
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
    semester !== undefined ||
    examType !== ''

  return (
    <section>
      <div className="flex items-center justify-between gap-4">
        <h1 className="text-2xl font-semibold tracking-tight">자료게시판</h1>
        {/*
         * 등록은 `ACTIVE`면 누구나 할 수 있다 (spec §3-1-3 매트릭스 — 자료 업로드는
         * USER·ADMIN 모두 `O`). 관리자 전용이 아니므로 `/admin` 아래에 두지 않는다.
         */}
        <Button variant="outline" size="sm" asChild>
          <Link to={`/notes/new?category=${category}`}>업로드</Link>
        </Button>
      </div>

      {/*
       * 갈래 탭.
       *
       * **버튼이 아니라 링크다.** 주소가 바뀌는 이동이므로 새 탭으로 열거나 주소를 복사하는
       * 것이 성립해야 한다 — `<button>`으로 만들면 그 셋이 전부 사라진다.
       *
       * **ARIA `tablist`를 쓰지 않는다.** 그 역할은 "같은 화면 안에서 패널을 바꾼다"는
       * 뜻인데 여기는 실제로 페이지를 이동한다. 스크린리더에게 거짓말이 되므로 평범한
       * 내비게이션으로 두고 현재 위치만 `aria-current`로 알린다.
       *
       * **탭을 바꾸면 검색·필터가 딸려가지 않는다.** 시험 자료를 "중간"으로 걸러 보다가
       * 과목 탭으로 넘어가면 그 조건은 뜻을 잃는다 — 갈래마다 고를 수 있는 값이 다르고,
       * 특히 시험 구분은 `SUBJECT`에 걸면 결과가 늘 0건이다.
       */}
      <div className="mt-6 flex items-end justify-between gap-4 border-b border-border">
        {/*
         * **담아둔 것만 보는 중에는 갈래 탭을 감춘다** (#261). `GET /bookmarks`는 갈래를
         * 가리지 않고 섞어 내려주므로, 탭을 남겨 두면 눌러도 아무 일이 없다 — 화면이
         * 거짓말을 한다. 대신 아래 표가 갈래 열을 보여준다.
         */}
        {onlyBookmarked ? (
          <span />
        ) : (
          <nav aria-label="자료 카테고리" className="flex gap-1">
            {(Object.keys(CATEGORY_LABEL) as Category[]).map((value) => (
              <Link
                key={value}
                to={categoryPath(value)}
                aria-current={value === category ? 'page' : undefined}
                className={cn(
                  '-mb-px border-b-2 px-4 py-2 text-sm transition-colors',
                  value === category
                    ? 'border-foreground font-medium text-foreground'
                    : 'border-transparent text-muted-foreground hover:text-foreground',
                )}
              >
                {CATEGORY_LABEL[value]}
              </Link>
            ))}
          </nav>
        )}

        {/*
         * **즐겨찾기는 목적지가 아니라 이 목록을 추리는 조건이다** (#261). 별도 화면이던
         * 것을 여기로 접었다 — 갈래를 탭으로 접은 것과 같은 판단이다 (#59).
         *
         * **버튼이 아니라 링크다.** 주소가 바뀌는 이동이라 새 탭으로 열거나 주소를
         * 복사하는 것이 성립해야 한다.
         *
         * **켜면 검색·필터를 떨군다.** `GET /bookmarks`가 그것을 받지 않으므로 들고 가면
         * 조건이 걸린 것처럼 보이는데 실제로는 무시된다.
         */}
        <Link
          to={
            onlyBookmarked ? categoryPath(category) : '/notes?bookmarked=true'
          }
          aria-pressed={onlyBookmarked}
          className={cn(
            'mb-2 flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm transition-colors',
            onlyBookmarked
              ? 'bg-accent font-medium text-foreground'
              : 'text-muted-foreground hover:text-foreground',
          )}
        >
          <Star
            className={cn('size-4', onlyBookmarked && 'fill-current')}
            aria-hidden="true"
          />
          즐겨찾기만 보기
        </Link>
      </div>

      {/*
       * **검색어와 필터는 AND로 함께 걸린다** (spec §2-1-1 MUST). 하나의 폼 안에 두어
       * 서로 배타적인 것이 아님을 화면으로 보여준다.
       *
       * **담아둔 것만 보는 중에는 통째로 감춘다** (#261). `GET /bookmarks`는 검색·필터를
       * 받지 않는다 (계약 §3-2-4) — 남겨 두고 눌러도 안 먹으면 화면이 거짓말을 한다.
       */}
      {!onlyBookmarked && (
        <form
          onSubmit={submitSearch}
          className="mt-4 flex flex-wrap items-end gap-3"
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
              value={semester ?? ''}
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
      )}

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
          {noteErrorText(isInactive(session))}
        </p>
      )}

      {data !== null && data.content.length === 0 && (
        /*
         * **조건이 걸려 0건인 것과 아예 없는 것을 가른다.** 검색해서 없는 사람에게
         * "등록된 자료가 없습니다"라고 하면 검색어를 지워볼 생각을 못 한다.
         */
        <p className="mt-8 text-sm text-muted-foreground">
          {onlyBookmarked
            ? '담아둔 자료가 없습니다. 목록에서 별표를 눌러 담아보세요.'
            : filtered
              ? '조건에 맞는 자료가 없습니다. 검색어나 필터를 바꿔 보세요.'
              : '등록된 자료가 없습니다.'}
        </p>
      )}

      {data !== null && data.content.length > 0 && (
        <>
          <p className="mt-6 text-sm text-muted-foreground">
            {onlyBookmarked ? '담아둔 자료' : '전체'} {data.page.totalElements}
            건
          </p>
          <NoteTable
            notes={data.content}
            /*
             * 담아둔 목록에는 시험·과목이 섞여 오므로 갈래를 보여준다. 탭으로 가른
             * 목록에서는 전부 같은 값이라 감춘다.
             */
            showCategory={onlyBookmarked}
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
