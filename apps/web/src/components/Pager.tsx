import { Fragment } from 'react'
import {
  Pagination,
  PaginationContent,
  PaginationEllipsis,
  PaginationItem,
  PaginationLink,
  PaginationNext,
  PaginationPrevious,
} from '@/components/ui/pagination'
import { cn } from '@/lib/utils'

/**
 * 페이지 번호 줄.
 *
 * **규칙은 `NoticeListPage`가 먼저 정한 것과 같다** — 첫·마지막·현재와 그 양옆 한 칸을
 * 보여주고, 건너뛰는 자리에만 생략 부호를 넣는다. 번호 5개 + 생략 2개라 **총 페이지 수와
 * 무관하게 7칸을 넘지 않는다.**
 *
 * 공지·자료·사진·게시글 목록이 이 창 계산과 아래의 예약된 pager 자리를 함께 쓴다.
 */
export function pageWindow(page: number, totalPages: number): number[] {
  const last = totalPages - 1
  const wanted = [0, last, page - 1, page, page + 1]
  const shown = [...new Set(wanted)]
    .filter((number) => number >= 0 && number <= last)
    .sort((a, b) => a - b)

  // 건너뛰는 페이지가 딱 하나면 생략 부호 대신 그 번호를 넣는다. 생략 부호가 번호와 같은
  // 자리를 차지하므로 하나를 감춰봤자 이득이 없고 보기만 어색하다.
  const filled: number[] = []
  for (const number of shown) {
    const previous = filled.at(-1)
    if (previous !== undefined && number - previous === 2)
      filled.push(previous + 1)
    filled.push(number)
  }
  return filled
}

/**
 * `page`를 주소에 쓴다 — **0페이지는 파라미터를 지운다.**
 *
 * <p>{@link parsePage}의 반대편이다. 읽는 쪽이 없는 값을 0으로 보므로, 쓰는 쪽도 0을 없는 값으로
 * 써야 같은 화면이 같은 주소를 갖는다.
 *
 * 규약이 갈리면 **어떻게 도착했느냐에 따라 주소가 달라진다** — 페이지 링크로 1페이지에 가면
 * `?category=EXAM`인데 범위 초과 클램프를 타고 오면 `?category=EXAM&page=0`이 되고, 그 주소가
 * 그대로 공유·북마크된다. 화면마다 손으로 적으면 한 곳만 어긋나므로 여기 하나만 둔다 (#283).
 */
export function writePage(params: URLSearchParams, next: number): void {
  if (next <= 0) params.delete('page')
  else params.set('page', String(next))
}

/**
 * `page`를 0 이상 정수로 수렴시킨다.
 *
 * `Math.max(0, Number(...))`만으로는 `?page=1.5`가 그대로 통과해 API의 정수 계약을 깨고,
 * 어느 정수 페이지 링크에도 `aria-current`가 붙지 않는다.
 */
export function parsePage(raw: string | null): number {
  const value = Number(raw ?? '0')
  if (!Number.isFinite(value)) return 0
  return Math.max(0, Math.floor(value))
}

/**
 * @param hrefFor 실제 대상 주소. `href="#"`이면 새 탭으로 열거나 주소를 복사할 때 엉뚱한
 *   곳이 열린다 — 페이지 번호를 URL에 두는 설계와 앞뒤가 맞지 않는다.
 * @param onGo 클릭 처리. 링크는 `preventDefault` 후 이걸 타므로 전체 새로고침이 나지 않는다.
 */
export function Pager({
  page,
  totalPages,
  hrefFor,
  onGo,
  className,
}: {
  page: number
  totalPages: number
  hrefFor: (page: number) => string
  onGo: (page: number) => void
  className?: string
}) {
  return (
    <div
      className={cn('flex min-h-10 items-start justify-center', className)}
      data-pager-slot="true"
    >
      {totalPages > 1 ? (
        <Pagination>
          <PaginationContent>
            <PaginationItem>
              <PaginationPrevious
                href={hrefFor(Math.max(0, page - 1))}
                aria-disabled={page === 0}
                className={page === 0 ? 'pointer-events-none opacity-50' : ''}
                onClick={(event) => {
                  event.preventDefault()
                  if (page > 0) onGo(page - 1)
                }}
              />
            </PaginationItem>

            {pageWindow(page, totalPages).map((number, index, shown) => (
              <Fragment key={number}>
                {index > 0 && number - shown[index - 1] > 1 && (
                  <PaginationItem>
                    <PaginationEllipsis />
                  </PaginationItem>
                )}
                <PaginationItem>
                  <PaginationLink
                    href={hrefFor(number)}
                    isActive={number === page}
                    onClick={(event) => {
                      event.preventDefault()
                      onGo(number)
                    }}
                  >
                    {number + 1}
                  </PaginationLink>
                </PaginationItem>
              </Fragment>
            ))}

            <PaginationItem>
              <PaginationNext
                href={hrefFor(Math.min(totalPages - 1, page + 1))}
                aria-disabled={page >= totalPages - 1}
                className={
                  page >= totalPages - 1 ? 'pointer-events-none opacity-50' : ''
                }
                onClick={(event) => {
                  event.preventDefault()
                  if (page < totalPages - 1) onGo(page + 1)
                }}
              />
            </PaginationItem>
          </PaginationContent>
        </Pagination>
      ) : null}
    </div>
  )
}
