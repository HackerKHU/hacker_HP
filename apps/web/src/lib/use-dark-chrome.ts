import { useEffect } from 'react'

/**
 * `rgb(r, g, b)` → `#rrggbb`. Safari가 `theme-color`에서 함수 표기를 무시하는 경우가 있어
 * 16진수로 바꿔 넣는다.
 *
 * **정수 세 채널인 `rgb()`/`rgba()`만 바꾼다.** 계산된 색은 소수 채널을 돌려줄 수 있고
 * (`rgb(146.06, 107.46, 131.2)`), `oklch()` 같은 다른 표기도 온다 — 숫자만 긁어 모으면
 * 엉뚱한 색이 된다. 확신이 없으면 원본을 그대로 넘긴다. 브라우저가 못 읽으면 `theme-color`가
 * 무시될 뿐이지만, 틀린 색을 넣으면 크롬이 엉뚱하게 칠해진다.
 */
function toHex(color: string): string {
  const rgb = color.match(/^rgba?\((\d+),\s*(\d+),\s*(\d+)(?:[,)])/)
  if (!rgb) return color
  return `#${rgb
    .slice(1, 4)
    .map((part) => Number(part).toString(16).padStart(2, '0'))
    .join('')}`
}

/**
 * 다크 화면에서 **브라우저 크롬까지** 검게 만든다 (#192).
 *
 * **`.dark`를 `html`에 건다.** 처음에는 `html`의 배경만 인라인으로 칠했는데 흰 띠가
 * 그대로였다 — `body`가 `@apply bg-background`로 `:root`(라이트)를 읽어 **흰색으로
 * 덮고 있었기 때문**이다. iOS Safari가 상태바·툴바 영역에 쓰는 색이 그 body 배경이다.
 * `html`에 클래스를 걸면 토큰이 뒤집혀 body까지 검게 따라오므로, 색을 손으로 칠하는
 * 대신 이미 있는 팔레트가 그대로 적용된다.
 *
 * **부르는 쪽의 안쪽 `.dark`는 남겨 둔다.** 이 effect는 첫 페인트 뒤에 도는데, 그때까지
 * 본문이 라이트로 한 프레임 번쩍이면 안 된다.
 *
 * 전역 CSS로 못 박지 않는 이유 — 로그인 이후 화면은 라이트라서, 다크 화면에 있는 동안만
 * 유효해야 한다. 그래서 정리 함수가 원래 상태를 되돌린다.
 */
export function useDarkChrome() {
  useEffect(() => {
    const html = document.documentElement
    const hadDark = html.classList.contains('dark')
    html.classList.add('dark')

    /*
     * `theme-color`도 함께 준다. 색은 뒤집힌 팔레트에서 읽되 **16진수로 바꿔 넣는다** —
     * Safari가 `rgb()` 표기를 그대로 받아주지 않는 경우가 있다.
     */
    const meta = document.createElement('meta')
    meta.name = 'theme-color'
    meta.content = toHex(getComputedStyle(document.body).backgroundColor)
    const existing = document.querySelector<HTMLMetaElement>(
      'meta[name="theme-color"]',
    )
    const previous = existing?.content
    if (existing) existing.content = meta.content
    else document.head.appendChild(meta)

    return () => {
      if (!hadDark) html.classList.remove('dark')
      if (existing && previous !== undefined) existing.content = previous
      else meta.remove()
    }
  }, [])
}
