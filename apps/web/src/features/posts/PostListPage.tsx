import { useEffect, useState } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { list, type PostSummary } from '@/api/posts'
import type { Page } from '@/api/types'
import { useSession } from '@/auth/session'
import { ListSurface } from '@/components/ListSurface'
import {
  KOREAN_PAGER_LABELS,
  Pager,
  parsePage,
  writePage,
} from '@/components/Pager'
import { Button } from '@/components/ui/button'
import { formatDate } from './format'

const PAGE_SIZE = 20

/**
 * 자유 게시판 목록 (spec §2-1-8).
 *
 * **정렬 선택지가 없다** (MUST). 서버가 `created_at DESC, id DESC`로 고정하므로 화면이
 * 정렬을 고르지도, 다시 정렬하지도 않는다 — 받지 않으면 이상한 값으로 서버가 터질 자리도
 * 없다 (자료 목록이 `sort=bogus` 하나로 `500`이 났던 적이 있다, #52).
 *
 * **본문 미리보기도 없다.** 목록 응답에 `content`가 아예 없다 (§3-2-5) — 자를 위치를
 * 서버가 정하게 되고, 길이를 바꾸면 계약이 바뀐다.
 */
/**
 * 이 화면의 주소에는 `page` 말고 다른 조회 조건이 없다. 그래서 매번 새로 만든다.
 *
 * <b>컴포넌트 밖에 둔다</b> — 안에 두면 렌더마다 새 함수가 되어, 이것을 쓰는 effect가 매 렌더
 * 다시 돌거나 의존성에서 빠진 채 남는다.
 */
function pageParams(next: number): URLSearchParams {
  const params = new URLSearchParams()
  writePage(params, next)
  return params
}

export function PostListPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const { pathname } = useLocation()
  const { reportApiError } = useSession()

  const page = parsePage(searchParams.get('page'))
  const [data, setData] = useState<Page<PostSummary> | null>(null)
  const [failed, setFailed] = useState(false)
  const [reloadKey, setReloadKey] = useState(0)

  // biome-ignore lint/correctness/useExhaustiveDependencies: reloadKey는 재조회 트리거다.
  useEffect(() => {
    let alive = true
    setData(null)
    setFailed(false)
    list({ page, size: PAGE_SIZE })
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
   * F-2 — 범위를 넘은 `page`로 들어오면 마지막 유효 페이지로 되돌린다. 그냥 두면 글이
   * 있는데도 "글이 없습니다"가 뜬다. `totalPages`가 0이면 되돌릴 곳이 없어 움직이지 않는다.
   */
  useEffect(() => {
    if (!data) return
    const { totalPages } = data.page
    if (totalPages >= 1 && page >= totalPages) {
      setSearchParams(pageParams(totalPages - 1), { replace: true })
    }
  }, [data, page, setSearchParams])

  function pageHref(next: number): string {
    const query = pageParams(next).toString()
    return query === '' ? pathname : `${pathname}?${query}`
  }

  return (
    <section>
      <div className="flex items-center justify-between gap-4">
        <h1 className="text-2xl font-semibold tracking-tight">자유게시판</h1>
        {/*
         * 글쓰기는 `ACTIVE`면 누구나 한다 (spec §3-1-3 매트릭스 — 게시판 작성은 USER·ADMIN
         * 모두 `O`). 관리자 전용이 아니므로 `/admin` 아래에 두지 않는다.
         */}
        <Button variant="outline" size="sm" asChild>
          <Link to="/posts/new">글쓰기</Link>
        </Button>
      </div>

      <div className="mt-6 min-h-72" data-list-surface="posts">
        {data === null && !failed && (
          <p className="mt-8 text-sm text-muted-foreground">불러오는 중</p>
        )}

        {failed && (
          <div className="mt-8 space-y-4">
            <p role="alert" className="text-sm text-muted-foreground">
              글을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
            </p>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setReloadKey((key) => key + 1)}
            >
              다시 시도
            </Button>
          </div>
        )}

        {data !== null && data.content.length === 0 && (
          <p className="mt-8 text-sm text-muted-foreground">
            아직 올라온 글이 없습니다. 첫 글을 써보세요.
          </p>
        )}

        {data !== null && data.content.length > 0 && (
          <>
            {/*
             * 표가 아니라 목록이다 — 열이 제목·작성자·날짜 셋뿐이라 표로 그리면 헤더가
             * 내용보다 무거워진다. 공지 목록과 같은 모양을 쓴다.
             *
             * 래퍼는 각지게 둔다 (#218) — 행이 직선으로 쌓이는데 래퍼만 둥글면 네 모서리에서
             * 행이 잘려 보인다.
             */}
            <ListSurface>
              <ul>
                {data.content.map((post) => (
                  <li
                    key={post.id}
                    className="border-b border-border last:border-b-0"
                  >
                    <Link
                      to={`/posts/${post.id}`}
                      className="flex min-w-0 items-center gap-4 px-4 py-3 transition-colors hover:bg-accent outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:ring-inset"
                    >
                      <span
                        className="min-w-0 flex-1 truncate text-sm"
                        title={post.title}
                      >
                        {post.title}
                      </span>
                      {/* 작성자 이름은 절대 비지 않는다 — 제거되면 "탈퇴한 회원"이다 (§2-1-8). */}
                      <span className="shrink-0 text-sm text-muted-foreground">
                        {post.author.name}
                      </span>
                      <time
                        dateTime={post.createdAt}
                        className="w-24 shrink-0 text-right text-sm text-muted-foreground"
                      >
                        {formatDate(post.createdAt)}
                      </time>
                    </Link>
                  </li>
                ))}
              </ul>
            </ListSurface>
          </>
        )}
      </div>

      <Pager
        className="mt-8"
        page={page}
        totalPages={data?.page.totalPages ?? 0}
        hrefFor={pageHref}
        onGo={(next) => setSearchParams(pageParams(next))}
        labels={KOREAN_PAGER_LABELS}
      />
    </section>
  )
}
