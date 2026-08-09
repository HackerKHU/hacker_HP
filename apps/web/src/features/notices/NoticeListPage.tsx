import { Pin } from 'lucide-react'
import { useCallback, useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { list, type Notice, togglePin } from '@/api/notices'
import type { Page } from '@/api/types'
import { useSession } from '@/auth/session'
import { Button } from '@/components/ui/button'
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

/** 며칠 이내를 새글로 볼지. 7일로 바꾸자는 말이 나오면 여기만 고친다. */
const NEW_WITHIN_DAYS = 3
const DAY_MS = 24 * 60 * 60 * 1000

/**
 * 새글 판정은 **사용자 PC 시계 기준**이다. 시계가 틀어져 있으면 경계에 걸린 글의 표시가
 * 어긋날 수 있다. 서버가 계산해 내려주면 정확하지만 응답 필드가 하나 늘고 계약이 커진다 —
 * 동아리 사이트에서 새글 표시가 하루 어긋나는 것은 감당 가능한 수준이라고 봤다.
 */
function isNew(createdAt: string): boolean {
  return Date.now() - new Date(createdAt).getTime() < NEW_WITHIN_DAYS * DAY_MS
}

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

  const { state, reportApiError } = useSession()
  const isAdmin = state.kind === 'active' && state.user.role === 'ADMIN'

  const [data, setData] = useState<Page<Notice> | null>(null)
  const [failed, setFailed] = useState(false)
  // 관리 모드는 URL에 넣지 않는다. 페이지 번호와 달리 공유하거나 새로고침으로 유지할
  // 이유가 없는 일시적 UI 상태다.
  const [managing, setManaging] = useState(false)
  const [pinning, setPinning] = useState<number | null>(null)
  const [pinFailed, setPinFailed] = useState(false)

  const load = useCallback(
    (signal: { alive: boolean }) =>
      list({ page, size: PAGE_SIZE })
        .then((result) => {
          if (signal.alive) setData(result)
        })
        .catch((error: unknown) => {
          if (!signal.alive) return
          // #36 계약 — 403 PENDING_APPROVAL이면 가드가 대기 화면으로 되돌린다.
          reportApiError(error)
          setFailed(true)
        }),
    [page, reportApiError],
  )

  useEffect(() => {
    const signal = { alive: true }
    setData(null)
    setFailed(false)
    load(signal)
    return () => {
      signal.alive = false
    }
  }, [load])

  function goTo(next: number) {
    setSearchParams(next === 0 ? {} : { page: String(next) })
  }

  /**
   * 고정 토글.
   *
   * 낙관적 업데이트를 하지 않는다. 토글은 정렬 순서까지 바꾸므로 화면에서 미리 흉내내려면
   * 클라이언트가 정렬을 다시 구현해야 하고, 그러면 서버 정렬을 덮어쓰는 코드가 하나 더
   * 생긴다. 되돌릴 것도 없어진다.
   */
  async function handleTogglePin(id: number) {
    setPinning(id)
    setPinFailed(false)
    try {
      await togglePin(id)
      await load({ alive: true })
    } catch (error: unknown) {
      reportApiError(error)
      setPinFailed(true)
    } finally {
      setPinning(null)
    }
  }

  // 페이지 번호는 그 자체가 식별자다. 배열 인덱스를 key로 쓰지 않는다.
  const pageNumbers = Array.from(
    { length: data?.page.totalPages ?? 0 },
    (_, index) => index,
  )

  return (
    <section>
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold tracking-tight">공지사항</h1>

        {/*
         * 관리 버튼은 ADMIN에게만 보인다. **노출 제어일 뿐 권한 통제가 아니다**
         * (spec §3-1-7). 서버가 `PATCH /notices/{id}/pin`을 ADMIN으로 막는 것이 전제이고,
         * 여기를 뚫어도 서버가 거부한다.
         *
         * 평소 목록은 읽기 화면으로 깨끗하게 둔다 — 토글을 항상 노출하면 읽으러 온
         * 관리자가 실수로 누른다.
         */}
        {isAdmin && (
          <Button
            variant={managing ? 'secondary' : 'outline'}
            size="sm"
            aria-pressed={managing}
            onClick={() => setManaging((on) => !on)}
          >
            관리
          </Button>
        )}
      </div>

      {data === null && !failed && (
        <p className="mt-8 text-sm text-muted-foreground">불러오는 중</p>
      )}

      {failed && (
        <p role="alert" className="mt-8 text-sm text-muted-foreground">
          공지를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
        </p>
      )}

      {pinFailed && (
        <p role="alert" className="mt-4 text-sm text-muted-foreground">
          고정 상태를 바꾸지 못했습니다. 다시 시도해 주세요.
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
            <li
              key={notice.id}
              className="flex items-center border-b border-border"
            >
              <Link
                to={`/notices/${notice.id}`}
                className={cn(
                  'flex min-w-0 flex-1 items-center gap-4 py-3 pr-2 pl-3 transition-colors hover:bg-accent',
                  // 무채색 팔레트라 색으로 구분할 수 없다. 고정 공지는 좌측 세로 바와
                  // 핀 아이콘, 순검정 제목으로 가른다.
                  notice.isPinned
                    ? 'border-l-[3px] border-l-primary'
                    : 'border-l-[3px] border-l-transparent',
                )}
              >
                <span
                  className={cn(
                    'flex min-w-0 flex-1 items-center gap-2 text-sm',
                    notice.isPinned
                      ? 'font-medium text-primary'
                      : 'text-foreground',
                  )}
                >
                  {notice.isPinned && (
                    <>
                      <Pin className="size-3.5 shrink-0" aria-hidden="true" />
                      {/* 아이콘만 두면 스크린리더가 못 읽는다. 의미는 남긴다. */}
                      <span className="sr-only">고정</span>
                    </>
                  )}
                  <span className="truncate">{notice.title}</span>
                  {isNew(notice.createdAt) && (
                    // 고정(핀 + 세로 바)이 강한 표시라 새글은 테두리만 있는 약한 라벨로
                    // 둔다. 둘 다 채우면 위계가 사라져 아무것도 안 튄다.
                    <span className="shrink-0 rounded-sm border border-border px-1.5 py-0.5 text-[10px] leading-none font-medium tracking-wide text-muted-foreground">
                      NEW
                    </span>
                  )}
                </span>
                <time
                  dateTime={notice.createdAt}
                  className="shrink-0 text-sm text-muted-foreground"
                >
                  {formatDate(notice.createdAt)}
                </time>
              </Link>

              {managing && isAdmin && (
                <Button
                  variant="ghost"
                  size="sm"
                  className="ml-2 shrink-0"
                  disabled={pinning === notice.id}
                  onClick={() => handleTogglePin(notice.id)}
                >
                  {notice.isPinned ? '고정 해제' : '고정'}
                </Button>
              )}
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
