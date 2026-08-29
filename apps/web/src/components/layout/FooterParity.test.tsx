import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { AppLayout } from './AppLayout'

/**
 * **두 화면의 푸터가 같은가** (#304).
 *
 * `HeaderAlignment.test.tsx`가 두 헤더에 대해 하는 일을 푸터에 대해 한다. 그쪽은 로고
 * 자리가 8px 미끄러진 것(#247)을 잡으려고 생겼는데, 푸터는 더 크게 갈려 있었다 — 랜딩은
 * 주소와 링크 일곱 개, 내부는 링크 세 개짜리 한 줄이었다. **각자 markup을 들고 있으면
 * 한쪽만 고쳐진다.**
 *
 * 지금은 둘 다 `SiteFooter` 하나를 그리므로 이 파일은 **그 상태가 유지되는지**를 본다.
 * 누군가 한쪽에 markup을 되살리면 렌더 결과가 갈리고 여기서 걸린다.
 */

vi.mock('./AppHeader', () => ({
  AppHeader: () => <header />,
}))

/*
 * 랜딩은 헤더가 세션을 읽는다. 이 파일이 보는 것은 푸터뿐이라 통째로 세운다 —
 * `SessionProvider` 없이 렌더하기 위해서이기도 하다.
 */
vi.mock('@/features/landing/PublicHeader', () => ({
  PublicHeader: () => <header />,
}))

/**
 * 푸터의 HTML을 뽑는다. **뽑고 나면 DOM을 버린다** — 두 화면을 한 번에 그리면
 * `contentinfo`가 둘이 되어 `getByRole`이 실패한다.
 */
function footerHtmlOf(screenNode: React.ReactNode): string {
  const { unmount } = render(
    <MemoryRouter initialEntries={['/x']}>{screenNode}</MemoryRouter>,
  )
  const html = screen.getByRole('contentinfo').outerHTML
  unmount()
  return html
}

describe('두 화면의 푸터', () => {
  it('랜딩과 내부 화면이 같은 푸터를 그린다', async () => {
    /*
     * 랜딩은 무겁고 다크 크롬 훅까지 타므로 이 파일에서만 늦게 가져온다. 위 mock이
     * 걸린 뒤에 불러와야 헤더가 실제로 대체된다.
     */
    const { LandingPage } = await import('@/features/landing/LandingPage')

    const landing = footerHtmlOf(<LandingPage />)
    const app = footerHtmlOf(
      <Routes>
        <Route element={<AppLayout />}>
          <Route path="/x" element={<p>내용</p>} />
        </Route>
      </Routes>,
    )

    // 빈 문자열끼리 같은 것을 통과로 읽지 않는다.
    expect(landing).toContain('이용약관')
    expect(app).toBe(landing)
  })
})
