import type { Page } from '@/api/types'
import { writePage } from './Pager'

/**
 * F-2 — **범위를 넘은 `page`로 들어오면 마지막 유효 페이지로 되돌린다.**
 *
 * 그냥 두면 자료가 있는데도 "자료가 없습니다"가 뜬다. `totalPages`가 0이면 되돌릴 곳이
 * 없으므로 움직이지 않는다 — 움직이면 무한히 오간다.
 *
 * ## 왜 effect가 아니라 조회 응답 안에서 부르나
 *
 * `useEffect`로 두면 **그 effect가 늦게 실행될 때 방금 바뀐 조건을 되돌린다.** 실제로
 * 그랬다 (#283 리뷰 뒤 CI):
 *
 * ```
 * [SETPARAM] semester=FALL  from= page=2  -> semester=FALL
 * [CLAMP]    from= page=2   page=2  totalPages=1     ← 필터 변경 뒤에 실행됨
 * [CLAMP]    -> (빈 문자열)                           ← semester=FALL을 덮어씀
 * ```
 *
 * 3페이지에서 필터를 바꾸면 **방금 고른 필터가 사라진다.** `replace: true`라 히스토리에도
 * 남지 않아 사용자는 왜 풀렸는지 알 수 없다.
 *
 * **`setSearchParams`의 함수형 인자로는 못 고친다.** react-router가 그 함수에 넘기는 것도
 * 렌더 시점 값이다 (`nextInit(new URLSearchParams(searchParams))`, v7.18) — 지금 주소를
 * 받을 방법이 없다.
 *
 * 그래서 **조회의 `alive` 가드 안으로 옮겼다.** 조건이 바뀌면 정리 함수가 `alive`를 내리고
 * 여기는 아예 불리지 않는다. 되돌리기의 기준이 되는 `params`도 **그 조회를 낸 바로 그
 * 주소**라 어긋나지 않는다.
 *
 * @returns 되돌렸으면 `true` — 부르는 쪽은 데이터를 세우지 말고 빠져나간다. 곧 새 조회가
 *     돈다
 */
export function clampedOutOfRange(
  result: Page<unknown>,
  page: number,
  params: URLSearchParams,
  setParams: (next: URLSearchParams, options?: { replace?: boolean }) => void,
): boolean {
  const { totalPages } = result.page
  if (totalPages < 1 || page < totalPages) {
    return false
  }
  const next = new URLSearchParams(params)
  writePage(next, totalPages - 1)
  setParams(next, { replace: true })
  return true
}
