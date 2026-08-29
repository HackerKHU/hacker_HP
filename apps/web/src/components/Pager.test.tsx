import { describe, expect, it } from 'vitest'
import { parsePage, writePage } from './Pager'

/**
 * `page` 파라미터의 읽기·쓰기 규약 (#283).
 *
 * **0페이지는 파라미터가 없는 상태로 표현한다.** 읽는 쪽(`parsePage`)이 없는 값을 0으로 보므로,
 * 쓰는 쪽도 0을 없는 값으로 써야 **같은 화면이 같은 주소를 갖는다.**
 *
 * 규약이 갈리면 어떻게 도착했느냐에 따라 주소가 달라진다 — 페이지 링크로 1페이지에 가면
 * `?category=EXAM`인데 범위 초과 클램프를 타고 오면 `?category=EXAM&page=0`이 되고, 그 주소가
 * 그대로 공유·북마크된다. 실제로 그 불일치가 화면 두 곳에 있었고 CI를 간헐적으로 무너뜨렸다.
 */
describe('page 파라미터 규약', () => {
  function query(init: string, next: number): string {
    const params = new URLSearchParams(init)
    writePage(params, next)
    return params.toString()
  }

  it('0페이지는 파라미터를 지운다 — 붙이지 않는다', () => {
    expect(query('category=EXAM&page=3', 0)).toBe('category=EXAM')
    expect(query('category=EXAM', 0)).toBe('category=EXAM')
  })

  /** 클램프가 마지막 유효 페이지를 0으로 계산하는 경우다 (`totalPages - 1`). */
  it('되돌릴 곳이 0페이지여도 마찬가지다', () => {
    expect(query('page=5', 0)).toBe('')
  })

  it('음수는 0과 같게 다룬다 — 주소에 남기지 않는다', () => {
    expect(query('page=5', -1)).toBe('')
  })

  it('1페이지부터는 그대로 쓴다', () => {
    expect(query('category=EXAM', 1)).toBe('category=EXAM&page=1')
    expect(query('page=1', 4)).toBe('page=4')
  })

  it('다른 조회 조건은 건드리지 않는다', () => {
    expect(query('q=중간&subject=운영체제&page=2', 0)).toBe(
      'q=%EC%A4%91%EA%B0%84&subject=%EC%9A%B4%EC%98%81%EC%B2%B4%EC%A0%9C',
    )
  })

  /**
   * **읽기와 쓰기가 서로의 반대여야 한다.** 한쪽만 고치면 규약이 다시 갈린다 — 이 왕복이
   * 그것을 잡는다.
   */
  it('쓴 값을 다시 읽으면 같은 페이지다', () => {
    for (const page of [0, 1, 2, 17]) {
      const params = new URLSearchParams('category=EXAM')
      writePage(params, page)
      expect(parsePage(params.get('page'))).toBe(page)
    }
  })
})
