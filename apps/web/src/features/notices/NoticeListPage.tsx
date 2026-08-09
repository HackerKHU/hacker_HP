import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { list, type Notice } from '@/api/notices'
import type { Page } from '@/api/types'
import { useSession } from '@/auth/session'
import {
  Pagination,
  PaginationContent,
  PaginationItem,
  PaginationLink,
  PaginationNext,
  PaginationPrevious,
} from '@/components/ui/pagination'
import { cn } from '@/lib/utils'

const PAGE_SIZE = 10

/** 서버는 UTC로 내려준다. 목록에서는 날짜까지만 보여준다. */
function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
}

export function NoticeListPage() {
  // 페이지 번호는 URL에 둔다. 컴포넌트 state에만 두면 새로고침·뒤로가기로 돌아왔을 때
  // 보던 페이지가 날아간다.
  //
  // URL의 `page`는 API 파라미터와 같은 **0-기반**이다 (spec §3-2-8). 화면 라벨만 1을
  // 더해 보여준다 — URL과 API 사이에 변환을 두면 그 자리가 off-by-one이 사는 곳이 된다.
  const [searchParams, setSearchParams] = useSearchParams()
  const page = Math.max(0, Number(searchParams.get('page') ?? '0') || 0)

  const { reportApiError } = useSession()
  const [data, setData] = useState<Page<Notice> | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let alive = true
    setData(null)
    setFailed(false)
    list({ page, size: PAGE_SIZE })
      .then((result) => {
        if (alive) setData(result)
      })
      .catch((error: unknown) => {
        if (!alive) return
        // #36 계약 — 403 PENDING_APPROVAL이면 가드가 대기 화면으로 되돌린다.
        reportApiError(error)
        setFailed(true)
      })
    return () => {
      alive = false
    }
  }, [page, reportApiError])

  // 페이지 번호는 그 자체가 식별자다. 배열 인덱스를 key로 쓰지 않는다.
  const pageNumbers = Array.from(
    { length: data?.page.totalPages ?? 0 },
    (_, index) => index,
  )

  function goTo(next: number) {
    setSearchParams(next === 0 ? {} : { page: String(next) })
  }

  return (
    <section>
      <h1 className="text-2xl font-semibold tracking-tight">공지 목록</h1>

      {data === null && !failed && (
        <p className="mt-8 text-sm text-muted-foreground">불러오는 중</p>
      )}

      {failed && (
        <p role="alert" className="mt-8 text-sm text-muted-foreground">
          공지를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
        </p>
      )}

      {data !== null && data.content.length === 0 && (
        <p className="mt-8 text-sm text-muted-foreground">
          등록된 공지가 없습니다.
        </p>
      )}

      {data !== null && data.content.length > 0 && (
        <ul className="mt-6 border-t border-border">
          {data.content.map((notice) => (
            <li key={notice.id} className="border-b border-border">
              <Link
                to={`/notices/${notice.id}`}
                className={cn(
                  'flex items-center gap-4 py-3 pr-2 transition-colors hover:bg-accent',
                  // 무채색 팔레트라 색으로 구분할 수 없다. 고정 공지는 좌측 세로 바와
                  // 순검정 제목으로 가른다. 나란히 놓으면 차이가 보인다.
                  notice.isPinned
                    ? 'border-l-[3px] border-l-primary pl-3'
                    : 'border-l-[3px] border-l-transparent pl-3',
                )}
              >
                <span
                  className={cn(
                    'flex-1 truncate text-sm',
                    notice.isPinned
                      ? 'font-medium text-primary'
                      : 'text-foreground',
                  )}
                >
                  {notice.isPinned && (
                    <span className="mr-2 text-xs text-muted-foreground">
                      고정
                    </span>
                  )}
                  {notice.title}
                </span>
                <time
                  dateTime={notice.createdAt}
                  className="shrink-0 text-sm text-muted-foreground"
                >
                  {formatDate(notice.createdAt)}
                </time>
              </Link>
            </li>
          ))}
        </ul>
      )}

      {data !== null && data.page.totalPages > 1 && (
        <Pagination className="mt-8">
          <PaginationContent>
            <PaginationItem>
              <PaginationPrevious
                href="#"
                aria-disabled={page === 0}
                className={page === 0 ? 'pointer-events-none opacity-50' : ''}
                onClick={(event) => {
                  event.preventDefault()
                  if (page > 0) goTo(page - 1)
                }}
              />
            </PaginationItem>

            {pageNumbers.map((number) => (
              <PaginationItem key={number}>
                <PaginationLink
                  href="#"
                  isActive={number === page}
                  onClick={(event) => {
                    event.preventDefault()
                    goTo(number)
                  }}
                >
                  {number + 1}
                </PaginationLink>
              </PaginationItem>
            ))}

            <PaginationItem>
              <PaginationNext
                href="#"
                aria-disabled={page >= data.page.totalPages - 1}
                className={
                  page >= data.page.totalPages - 1
                    ? 'pointer-events-none opacity-50'
                    : ''
                }
                onClick={(event) => {
                  event.preventDefault()
                  if (page < data.page.totalPages - 1) goTo(page + 1)
                }}
              />
            </PaginationItem>
          </PaginationContent>
        </Pagination>
      )}
    </section>
  )
}
