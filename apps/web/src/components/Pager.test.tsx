import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import {
  KOREAN_PAGER_LABELS,
  Pager,
  pageWindow,
  parsePage,
  writePage,
} from './Pager'

describe('페이지 번호 창', () => {
  it.each([0, 1, 2, 5])('%d페이지 이하는 모두 표시한다', (totalPages) => {
    expect(pageWindow(0, totalPages)).toEqual(
      Array.from({ length: totalPages }, (_, index) => index),
    )
  })

  it.each([
    [0, [0, 1, 2, 3, 5]],
    [1, [0, 1, 2, 3, 5]],
    [2, [0, 1, 2, 3, 5]],
    [3, [0, 2, 3, 4, 5]],
    [4, [0, 2, 3, 4, 5]],
    [5, [0, 2, 3, 4, 5]],
  ] as const)(
    '6페이지부터 현재 0-based %d의 가장자리·가운데 창을 만든다',
    (page, expected) => {
      expect(pageWindow(page, 6)).toEqual(expected)
    },
  )

  it('큰 목록 가운데는 첫·현재±1·마지막만 중복 없이 표시한다', () => {
    expect(pageWindow(9, 20)).toEqual([0, 8, 9, 10, 19])
  })

  it('PC 창은 10페이지까지 모두 보이고 그보다 많으면 숫자 10개를 유지한다', () => {
    expect(pageWindow(0, 10, 10)).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8, 9])
    expect(pageWindow(5, 11, 10)).toEqual([0, 2, 3, 4, 5, 6, 7, 8, 9, 10])
    expect(pageWindow(9, 20, 10)).toEqual([0, 6, 7, 8, 9, 10, 11, 12, 13, 19])
  })

  it('어느 위치에서도 모바일 5개·PC 10개 숫자 상한을 넘지 않는다', () => {
    for (let page = 0; page < 100; page += 1) {
      expect(pageWindow(page, 100, 5).length).toBeLessThanOrEqual(5)
      expect(pageWindow(page, 100, 10).length).toBeLessThanOrEqual(10)
    }
  })
})

describe('반응형 Pager', () => {
  it('한 페이지만 감춰도 모바일에 실제 gap을 표시하고 PC에는 모두 보인다', () => {
    const { container } = render(
      <Pager
        page={0}
        totalPages={6}
        hrefFor={(page) => `/items?page=${page}`}
        onGo={vi.fn()}
        labels={KOREAN_PAGER_LABELS}
      />,
    )

    expect(
      container.querySelectorAll(
        '[data-pager-page][data-pager-mobile-visible="true"]',
      ),
    ).toHaveLength(5)
    expect(
      container.querySelectorAll(
        '[data-pager-page][data-pager-desktop-visible="true"]',
      ),
    ).toHaveLength(6)
    expect(
      container.querySelectorAll(
        '[data-pager-mobile-visible="true"] [data-slot="pagination-ellipsis"]',
      ),
    ).toHaveLength(1)
    expect(
      container.querySelectorAll(
        '[data-pager-desktop-visible="true"] [data-slot="pagination-ellipsis"]',
      ),
    ).toHaveLength(0)
  })

  it('링크를 한 트리로 렌더하고 767/768px 표시 계약과 현재 페이지를 구분한다', () => {
    const { container } = render(
      <Pager
        page={9}
        totalPages={20}
        hrefFor={(page) => `/items?page=${page}`}
        onGo={vi.fn()}
        labels={KOREAN_PAGER_LABELS}
      />,
    )

    const mobilePages = [
      ...container.querySelectorAll(
        '[data-pager-page][data-pager-mobile-visible="true"]',
      ),
    ].map((item) => Number(item.getAttribute('data-pager-page')))
    const desktopPages = [
      ...container.querySelectorAll(
        '[data-pager-page][data-pager-desktop-visible="true"]',
      ),
    ].map((item) => Number(item.getAttribute('data-pager-page')))

    expect(mobilePages).toEqual([1, 9, 10, 11, 20])
    expect(desktopPages).toEqual([1, 7, 8, 9, 10, 11, 12, 13, 14, 20])
    expect(container.querySelector('[data-pager-page="7"]')).toHaveClass(
      'hidden',
      'md:list-item',
    )
    expect(
      container.querySelectorAll(
        '[data-pager-mobile-visible="true"] [data-slot="pagination-ellipsis"]',
      ),
    ).toHaveLength(2)
    expect(
      container.querySelectorAll(
        '[data-pager-desktop-visible="true"] [data-slot="pagination-ellipsis"]',
      ),
    ).toHaveLength(2)
    expect(screen.getAllByRole('link', { current: 'page' })).toHaveLength(1)
    expect(
      screen.getByRole('navigation', { name: '페이지네이션' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: '10페이지로 이동', current: 'page' }),
    ).toBeInTheDocument()

    const previous = screen.getByRole('link', { name: '이전 페이지로 이동' })
    const next = screen.getByRole('link', { name: '다음 페이지로 이동' })
    expect(previous).toHaveTextContent('이전')
    expect(next).toHaveTextContent('다음')
    expect(previous.querySelector('span')).toHaveClass('hidden', 'md:block')
    expect(next.querySelector('span')).toHaveClass('hidden', 'md:block')
  })
})

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
