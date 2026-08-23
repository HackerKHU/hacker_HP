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

/**
 * 페이지 번호 줄.
 *
 * **규칙은 `NoticeListPage`가 먼저 정한 것과 같다** — 첫·마지막·현재와 그 양옆 한 칸을
 * 보여주고, 건너뛰는 자리에만 생략 부호를 넣는다. 번호 5개 + 생략 2개라 **총 페이지 수와
 * 무관하게 7칸을 넘지 않는다.**
 *
 * ponytail: 공지 목록은 아직 자기 안에 같은 로직을 들고 있다. 그 화면은 이미 테스트가
 * 붙어 있어 이 이슈에서 건드리지 않았다 — 다음에 손댈 때 이걸로 옮긴다.
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
  if (totalPages <= 1) return null

  return (
    <Pagination className={className}>
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
  )
}
