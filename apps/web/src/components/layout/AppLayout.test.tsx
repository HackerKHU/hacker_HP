import { render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { CLUB } from '@/features/landing/content'
import { MemoryRouter, Route, Routes } from '@/test/TestRouter'
import { AppLayout } from './AppLayout'

/*
 * 헤더는 세션을 읽고 로그아웃을 부른다. 이 파일이 보는 것은 뼈대뿐이라 통째로 세운다.
 */
vi.mock('./AppHeader', () => ({
  AppHeader: () => <header />,
}))

/**
 * `LoginPage.test.tsx`가 `AppLayout` **밖** 화면에 대해 지키는 계약을, 안쪽에 대해 지킨다.
 * 둘이 같은 이유로 깨지므로 가드도 같은 모양이다.
 */
function renderLayout() {
  const { container } = render(
    <MemoryRouter initialEntries={['/x']}>
      <Routes>
        <Route element={<AppLayout />}>
          <Route path="/x" element={<p>내용</p>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
  const root = container.firstElementChild
  return { root, main: root?.querySelector('main') }
}

describe('AppLayout', () => {
  it('푸터를 바닥에 붙인다 — 뿌리가 화면 높이를 채우고 main이 남는 높이를 먹는다', () => {
    const { root, main } = renderLayout()

    /*
     * 셋이 한 벌이다. 하나라도 빠지면 내용이 짧은 화면에서 푸터가 중간에 뜬다.
     * 푸터에 위쪽 여백을 줘서 가리던 방식으로 되돌아가지 않게 여기서 막는다.
     */
    expect(root?.className).toContain('min-h-screen')
    expect(root?.className).toContain('flex-col')
    expect(main?.className).toContain('flex-1')
  })

  /*
   * 로그인 이후 화면에서 법적 문서로 가는 유일한 길이다. 랜딩 푸터와 로그인 카드는
   * 로그인한 사람이 다시 갈 일이 없으므로, 여기서 빠지면 사실상 닿을 수 없어진다.
   */
  it.each([
    ['개인정보처리방침', '/privacy'],
    ['이용약관', '/terms'],
  ])('푸터에서 %s로 갈 수 있다', (name, href) => {
    renderLayout()

    expect(screen.getByRole('link', { name })).toHaveAttribute('href', href)
  })

  /*
   * **랜딩과 같은 푸터다** (#304, `SiteFooter`). 목록도 모양도 같아야 한다 — 랜딩 쪽과
   * 나란히 재는 사례는 `FooterParity.test.tsx`에 있다.
   *
   * 링크 하나가 아니라 **목록 전체**를 단정한다: 하나만 보면 다른 길이 다시 늘어나도
   * 통과한다. `동아리 소개`가 있었는데 **헤더 로고가 이미 `/`로 간다** — 같은 곳으로 가는
   * 길이 둘이었다.
   */
  it('푸터가 랜딩과 같은 링크 셋을 그린다', () => {
    renderLayout()

    const footer = screen.getByRole('contentinfo')
    expect(
      within(footer)
        .getAllByRole('link')
        .map((link) => link.textContent),
    ).toEqual(['인스타그램', '개인정보처리방침', '이용약관'])
    // 동아리 이름·주소도 함께 온다. 내부 화면에만 없을 이유가 없다.
    expect(footer).toHaveTextContent(CLUB.fullName)
  })

  it('세로 가운데를 정렬 속성으로 잡지 않는다 — 넘치면 위쪽이 스크롤 밖으로 밀린다', () => {
    const { main } = renderLayout()

    /*
     * `main`이 세로 flex라 세로 축을 잡는 것은 `justify-*` 쪽이다. 자식이 화면보다
     * 커지는 순간 위쪽을 컨테이너 밖으로 밀어내고, 그 영역은 스크롤로 닿을 수 없다.
     * 가운데로 둘 화면은 자기 자신에게 `my-auto`를 준다 (신청·대기).
     */
    expect(main?.className).not.toMatch(
      /justify-center|place-items-center|content-center|items-center/,
    )
  })
})
