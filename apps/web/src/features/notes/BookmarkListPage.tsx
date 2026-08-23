import { useEffect, useState } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { bookmarks, type NoteSummary, setBookmark } from '@/api/notes'
import type { Page } from '@/api/types'
import { useSession } from '@/auth/session'
import { Pager, parsePage } from '@/components/Pager'
import { NoteTable } from './NoteTable'

const PAGE_SIZE = 20

/**
 * 내 즐겨찾기 목록 (spec §2-1-5).
 *
 * **표는 자료 목록과 같은 컴포넌트를 쓴다.** 계약이 `GET /bookmarks`의 응답을
 * `GET /notes`와 같은 형태로 맞춰 둔 이유가 그것이다 (§3-2-4).
 *
 * **검색·필터가 없다.** 이미 본인이 추린 목록이라 서버도 받지 않는다.
 * **갈래 열은 보여준다** — 시험·과목이 섞여 있어 구분이 필요하다.
 */
export function BookmarkListPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const { pathname } = useLocation()
  const { state, reportApiError } = useSession()

  const page = parsePage(searchParams.get('page'))
  const [data, setData] = useState<Page<NoteSummary> | null>(null)
  const [failed, setFailed] = useState(false)
  const [pending, setPending] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [reloadKey, setReloadKey] = useState(0)

  // biome-ignore lint/correctness/useExhaustiveDependencies: reloadKey는 본문에서 읽지 않고 재조회 트리거로만 쓴다.
  useEffect(() => {
    let alive = true
    setData(null)
    setFailed(false)
    bookmarks({ page, size: PAGE_SIZE })
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
  }, [page, reloadKey, reportApiError])

  /**
   * 마지막 페이지의 마지막 항목을 빼면 그 페이지가 사라진다. 되돌리지 않으면 자료가 남아
   * 있는데도 "담아둔 자료가 없습니다"가 뜬다.
   */
  useEffect(() => {
    if (!data) return
    const { totalPages } = data.page
    if (totalPages >= 1 && page >= totalPages) {
      setSearchParams({ page: String(totalPages - 1) }, { replace: true })
    }
  }, [data, page, setSearchParams])

  function pageParams(next: number): Record<string, string> {
    return next === 0 ? {} : { page: String(next) }
  }

  function pageHref(next: number): string {
    const query = new URLSearchParams(pageParams(next)).toString()
    return query === '' ? pathname : `${pathname}?${query}`
  }

  /**
   * 여기서 빼면 **목록에서 사라진다** — 담긴 것만 있는 화면이라 그것이 맞는 결과다.
   * 서버가 받아들인 뒤 다시 읽어 그린다 (낙관적으로 지우지 않는다).
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

  return (
    <section>
      <h1 className="text-2xl font-semibold tracking-tight">즐겨찾기</h1>
      <p className="mt-3 text-sm text-muted-foreground">
        담아둔 자료입니다. 별표를 다시 누르면 목록에서 빠집니다.
      </p>

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
          즐겨찾기를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
        </p>
      )}

      {data !== null && data.content.length === 0 && (
        /* 빈 화면에 갈 곳을 준다 — "없습니다"만 두면 어디서 담는지 알 수 없다. */
        <p className="mt-8 text-sm text-muted-foreground">
          담아둔 자료가 없습니다.{' '}
          <Link
            to="/notes/exam"
            className="underline underline-offset-4 transition-colors hover:text-foreground"
          >
            자료 목록
          </Link>
          에서 별표를 눌러 담아보세요.
        </p>
      )}

      {data !== null && data.content.length > 0 && (
        <>
          <NoteTable
            notes={data.content}
            showCategory
            onToggleBookmark={toggleBookmark}
            busy={pending || state.kind !== 'active'}
          />
          <Pager
            className="mt-8"
            page={page}
            totalPages={data.page.totalPages}
            hrefFor={pageHref}
            onGo={(next) => setSearchParams(pageParams(next))}
          />
        </>
      )}
    </section>
  )
}
