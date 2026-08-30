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
 * `maxNumbers` 이하는 모두 보여준다. 그보다 많으면 첫·마지막과 현재 주변의 연속된 창을
 * 보여주며, 실제로 감춘 페이지가 있는 자리에만 생략 부호를 넣는다.
 *
 * 공지·자료·사진·게시글·회원 목록이 이 계산을 공유한다. 767px 이하는 5개, `md`(768px)
 * 이상은 10개를 넘기지 않는다. viewport를 JavaScript로 읽지 않고 아래 `Pager`가 두 창의
 * 합집합을 CSS로 반응형 표시한다.
 */
export function pageWindow(
  page: number,
  totalPages: number,
  maxNumbers = 5,
): number[] {
  if (totalPages <= 0) return []

  const limit = Math.max(3, Math.floor(maxNumbers))
  if (totalPages <= limit) {
    return Array.from({ length: totalPages }, (_, index) => index)
  }

  const last = totalPages - 1
  const interiorCount = limit - 2
  const current = Math.min(last, Math.max(0, page))
  const centeredStart = current - Math.floor((interiorCount - 1) / 2)
  const start = Math.max(1, Math.min(centeredStart, last - interiorCount))
  const interior = Array.from(
    { length: interiorCount },
    (_, index) => start + index,
  )
  return [0, ...interior, last]
}

export type PagerLabels = {
  previous: string
  next: string
  previousAriaLabel: string
  nextAriaLabel: string
  pageAriaLabel: (page: number) => string
}

/** 현재 제품 화면의 공통 언어. 다섯 목록이 같은 낱말과 accessible name을 쓴다. */
export const KOREAN_PAGER_LABELS: PagerLabels = {
  previous: '이전',
  next: '다음',
  previousAriaLabel: '이전 페이지로 이동',
  nextAriaLabel: '다음 페이지로 이동',
  pageAriaLabel: (page) => `${page}페이지로 이동`,
}

function responsiveClass(mobile: boolean, desktop: boolean): string {
  if (mobile && desktop) return ''
  return mobile ? 'md:hidden' : 'hidden md:list-item'
}

function hasGapBefore(numbers: number[], number: number): boolean {
  const index = numbers.indexOf(number)
  return index > 0 && number - numbers[index - 1] > 1
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
  labels = KOREAN_PAGER_LABELS,
}: {
  page: number
  totalPages: number
  hrefFor: (page: number) => string
  onGo: (page: number) => void
  className?: string
  labels?: PagerLabels
}) {
  const mobileNumbers = pageWindow(page, totalPages, 5)
  const desktopNumbers = pageWindow(page, totalPages, 10)
  const numbers = [...new Set([...mobileNumbers, ...desktopNumbers])].sort(
    (a, b) => a - b,
  )

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
                label={labels.previous}
                aria-label={labels.previousAriaLabel}
                aria-disabled={page === 0}
                className={page === 0 ? 'pointer-events-none opacity-50' : ''}
                onClick={(event) => {
                  event.preventDefault()
                  if (page > 0) onGo(page - 1)
                }}
              />
            </PaginationItem>

            {numbers.map((number) => {
              const mobile = mobileNumbers.includes(number)
              const desktop = desktopNumbers.includes(number)
              const mobileGap = hasGapBefore(mobileNumbers, number)
              const desktopGap = hasGapBefore(desktopNumbers, number)
              return (
                <Fragment key={number}>
                  {(mobileGap || desktopGap) && (
                    <PaginationItem
                      className={responsiveClass(mobileGap, desktopGap)}
                      data-pager-mobile-visible={mobileGap || undefined}
                      data-pager-desktop-visible={desktopGap || undefined}
                    >
                      <PaginationEllipsis />
                    </PaginationItem>
                  )}
                  <PaginationItem
                    className={responsiveClass(mobile, desktop)}
                    data-pager-page={number + 1}
                    data-pager-mobile-visible={mobile || undefined}
                    data-pager-desktop-visible={desktop || undefined}
                  >
                    <PaginationLink
                      href={hrefFor(number)}
                      isActive={number === page}
                      aria-label={labels.pageAriaLabel(number + 1)}
                      onClick={(event) => {
                        event.preventDefault()
                        onGo(number)
                      }}
                    >
                      {number + 1}
                    </PaginationLink>
                  </PaginationItem>
                </Fragment>
              )
            })}

            <PaginationItem>
              <PaginationNext
                href={hrefFor(Math.min(totalPages - 1, page + 1))}
                label={labels.next}
                aria-label={labels.nextAriaLabel}
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
