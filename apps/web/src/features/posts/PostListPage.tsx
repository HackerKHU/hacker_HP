import { useEffect, useState } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { list, type PostSummary } from '@/api/posts'
import type { Page } from '@/api/types'
import { useSession } from '@/auth/session'
import { Pager, parsePage } from '@/components/Pager'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
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
export function PostListPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const { pathname } = useLocation()
  const { reportApiError } = useSession()

  const page = parsePage(searchParams.get('page'))
  const [data, setData] = useState<Page<PostSummary> | null>(null)
  const [failed, setFailed] = useState(false)

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
  }, [page, reportApiError])

  /**
   * F-2 — 범위를 넘은 `page`로 들어오면 마지막 유효 페이지로 되돌린다. 그냥 두면 글이
   * 있는데도 "글이 없습니다"가 뜬다. `totalPages`가 0이면 되돌릴 곳이 없어 움직이지 않는다.
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

  return (
    <section>
      <div className="flex items-center justify-between gap-4">
        <h1 className="text-2xl font-semibold tracking-tight">자유 게시판</h1>
        {/*
         * 글쓰기는 `ACTIVE`면 누구나 한다 (spec §3-1-3 매트릭스 — 게시판 작성은 USER·ADMIN
         * 모두 `O`). 관리자 전용이 아니므로 `/admin` 아래에 두지 않는다.
         */}
        <Button variant="outline" size="sm" asChild>
          <Link to="/posts/new">글쓰기</Link>
        </Button>
      </div>

      {data === null && !failed && (
        <p className="mt-8 text-sm text-muted-foreground">불러오는 중</p>
      )}

      {failed && (
        <p role="alert" className="mt-8 text-sm text-muted-foreground">
          글을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
        </p>
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
          <Card className="mt-6 overflow-hidden rounded-none py-0">
            <ul>
              {data.content.map((post) => (
                <li
                  key={post.id}
                  className="border-b border-border last:border-b-0"
                >
                  <Link
                    to={`/posts/${post.id}`}
                    className="flex items-center gap-4 px-4 py-3 transition-colors hover:bg-accent outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:ring-inset"
                  >
                    <span className="min-w-0 flex-1 truncate text-sm">
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
          </Card>

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
