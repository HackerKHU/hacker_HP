import { describe, expect, it, vi } from 'vitest'
import { clampedOutOfRange } from './clampPage'

/**
 * 범위를 넘은 `page`를 되돌릴 때 **다른 조회 조건을 잃지 않는가** (#283 후속).
 *
 * 화면 통합 테스트는 이 되돌리기가 필터 변경과 겹치는 순간에만 깨져 **실행마다 결과가
 * 달랐다.** 판단과 되돌리기를 순수 함수로 떼어 두면 그 조합을 직접 부를 수 있다.
 */
function page(totalPages: number) {
  return {
    content: [],
    page: { size: 20, number: 0, totalElements: 0, totalPages },
  }
}

describe('범위를 넘은 page 되돌리기', () => {
  it('마지막 유효 페이지로 되돌리고 다른 조건은 지킨다', () => {
    const setParams = vi.fn()
    const params = new URLSearchParams('category=EXAM&subject=운영체제&page=9')

    expect(clampedOutOfRange(page(3), 9, params, setParams)).toBe(true)

    const [next, options] = setParams.mock.calls[0]
    expect(next.get('page')).toBe('2')
    expect(next.get('category')).toBe('EXAM')
    expect(next.get('subject')).toBe('운영체제')
    expect(options).toEqual({ replace: true })
  })

  /**
   * 되돌릴 곳이 0페이지면 `page`를 **지운다** — 0페이지는 파라미터가 없는 상태로 표현한다
   * (#283). 여기서 `page=0`을 쓰면 그 규약이 다시 갈린다.
   */
  it('0페이지로 되돌아가면 page를 지우고 나머지는 남긴다', () => {
    const setParams = vi.fn()
    const params = new URLSearchParams('category=EXAM&page=5')

    expect(clampedOutOfRange(page(1), 5, params, setParams)).toBe(true)

    const [next] = setParams.mock.calls[0]
    expect(next.toString()).toBe('category=EXAM')
  })

  it('범위 안이면 아무것도 하지 않는다', () => {
    const setParams = vi.fn()

    expect(
      clampedOutOfRange(page(3), 1, new URLSearchParams('page=1'), setParams),
    ).toBe(false)
    expect(setParams).not.toHaveBeenCalled()
  })

  /**
   * 결과가 0건이면 되돌릴 유효 페이지가 없다. 움직이면 <b>무한히 오간다</b> — 되돌린 주소가
   * 다시 범위를 넘는다.
   */
  it('결과가 없으면 움직이지 않는다', () => {
    const setParams = vi.fn()

    expect(
      clampedOutOfRange(page(0), 3, new URLSearchParams('page=3'), setParams),
    ).toBe(false)
    expect(setParams).not.toHaveBeenCalled()
  })

  /**
   * **되돌리기의 기준은 그 조회를 낸 주소다.**
   *
   * 예전에는 이 판단이 `useEffect`에 있어, 그 effect가 늦게 실행되면 **이미 바뀐 조건을
   * 낡은 주소로 덮어썼다** — 3페이지에서 필터를 바꾸면 방금 고른 필터가 사라졌다. 지금은
   * 조회의 `alive` 가드 안에서 불리므로 조건이 바뀌면 아예 불리지 않는다.
   *
   * 그 계약을 여기서 못 박는다: **넘겨준 주소만 손대고 그 밖의 것은 만들어내지 않는다.**
   */
  it('넘겨준 주소에 없던 값을 만들어내지 않는다', () => {
    const setParams = vi.fn()
    const params = new URLSearchParams('semester=FALL')

    clampedOutOfRange(page(1), 2, params, setParams)

    const [next] = setParams.mock.calls[0]
    expect(next.toString()).toBe('semester=FALL')
  })
})
