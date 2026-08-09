import { describe, expect, it } from 'vitest'
// Vite의 `?raw`로 읽는다. `node:fs`를 쓰면 앱 프로젝트에 node 타입을 열어야 하는데,
// 화면 코드가 파일 시스템을 만질 수 있게 되는 대가가 크다.
import rawHtml from '../../../index.html?raw'
import { SITE_ORIGIN } from '../../../site.config'

/**
 * T-62 — 서빙되는 `index.html`의 `<head>`.
 *
 * 링크 미리보기 봇은 JS를 실행하지 않으므로 **서빙되는 HTML 자체**에 태그가 있어야 한다
 * (spec 3-3 결정 8). 런타임에 `document.title`을 바꾸는 방식으로는 통과하지 못한다.
 *
 * **존재 검사만으로는 부족하다.** 상대 URL은 봇이 읽지 못해 미리보기에 그림이 비는데도
 * 존재 검사는 통과한다 — 실제로 그렇게 새어나간 적이 있다. 그래서 `vite.config.ts`가
 * 하는 것과 같은 치환을 적용해 **최종 결과**를 본다.
 */
const html = rawHtml.replaceAll('%SITE_ORIGIN%', SITE_ORIGIN)

function content(selector: RegExp): string | undefined {
  return html.match(selector)?.[1]
}

describe('index.html 메타 태그', () => {
  it('미리보기에 필요한 태그가 모두 있다', () => {
    expect(content(/<title>([^<]+)<\/title>/)?.trim()).toBeTruthy()
    for (const name of ['description']) {
      expect(
        content(new RegExp(`name="${name}"[^>]*content="([^"]+)"`, 's')),
      ).toBeTruthy()
    }
    for (const property of ['og:title', 'og:description', 'og:image']) {
      expect(
        content(
          new RegExp(`property="${property}"[^>]*content="([^"]+)"`, 's'),
        ),
      ).toBeTruthy()
    }
  })

  it('og:image와 og:url이 절대 URL이다', () => {
    for (const property of ['og:image', 'og:url']) {
      const value = content(
        new RegExp(`property="${property}"[^>]*content="([^"]+)"`, 's'),
      )
      expect(value, `${property}가 비어 있다`).toBeTruthy()
      // 봇은 대부분 상대경로를 읽지 못한다.
      expect(value, `${property}가 절대 URL이 아니다: ${value}`).toMatch(
        /^https?:\/\//,
      )
    }
  })

  it('자리표시자가 치환되지 않은 채 남지 않는다', () => {
    expect(html).not.toContain('%SITE_ORIGIN%')
  })
})
