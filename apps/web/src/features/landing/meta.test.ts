import { createServer } from 'vite'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
// Vite의 `?raw`로 읽는다. `node:fs`를 쓰면 앱 프로젝트에 node 타입을 열어야 하는데,
// 화면 코드가 파일 시스템을 만질 수 있게 되는 대가가 크다.
import rawHtml from '../../../index.html?raw'

/**
 * T-62 — 서빙되는 `index.html`의 `<head>`.
 *
 * 링크 미리보기 봇은 JS를 실행하지 않으므로 **서빙되는 HTML 자체**에 태그가 있어야 한다
 * (spec 3-3 결정 8). 런타임에 `document.title`을 바꾸는 방식으로는 통과하지 못한다.
 *
 * **치환을 테스트가 직접 하지 않는다.** 예전에는 이 파일이 `%SITE_ORIGIN%`을 스스로
 * 바꿔놓고 "자리표시자가 없다"를 단언했다 — 치환 플러그인을 통째로 지워도 통과하는,
 * 자기 자신을 검증하는 테스트였다. 그래서 **Vite를 실제로 띄워 그 결과를 본다.**
 * 플러그인이 빠지거나 오작동하면 여기서 깨진다.
 */
let html: string
let server: Awaited<ReturnType<typeof createServer>>

beforeAll(async () => {
  // 설정 파일은 프로젝트 루트에서 자동으로 찾는다 — `vite.config.ts`가 그대로 적용된다.
  server = await createServer({ server: { middlewareMode: true } })
  html = await server.transformIndexHtml('/', rawHtml)
}, 30_000)

afterAll(async () => {
  await server?.close()
})

function content(property: string): string | undefined {
  return html.match(
    new RegExp(`property="${property}"[^>]*content="([^"]+)"`, 's'),
  )?.[1]
}

describe('index.html 메타 태그', () => {
  it('미리보기에 필요한 태그가 모두 있다', () => {
    expect(html.match(/<title>([^<]+)<\/title>/)?.[1]?.trim()).toBeTruthy()
    expect(
      html.match(/name="description"[^>]*content="([^"]+)"/s)?.[1],
    ).toBeTruthy()
    for (const property of ['og:title', 'og:description', 'og:image']) {
      expect(content(property), `${property}가 없다`).toBeTruthy()
    }
  })

  it('og:image와 og:url이 절대 URL이다', () => {
    for (const property of ['og:image', 'og:url']) {
      const value = content(property)
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

  /*
   * **태그가 있는 것과 그림이 뜨는 것은 다르다.** 태그 존재 검사만으로는 미리보기 이미지가
   * 404인 채로 T-62를 만족한다 — 로고가 없던 동안 실제로 그 상태였고, 이 단언은 그때
   * `.skip`으로 꺼져 있었다. 로고가 확정되어 파일이 들어왔으므로 켠다.
   */
  it('og:image가 가리키는 파일이 실제로 있다', () => {
    // `import.meta.glob`은 빌드 시점에 파일 목록으로 펼쳐진다 — node:fs가 필요 없다.
    const files = Object.keys(
      import.meta.glob('../../../public/**/*', { eager: false }),
    ).map((key) => key.replace('../../../public', ''))

    const url = content('og:image')
    expect(url).toBeTruthy()
    const assetPath = new URL(String(url)).pathname

    // 태그만 있고 파일이 없으면 미리보기에 그림이 비어 나온다. 존재 검사는 통과하는데
    // 실제로는 404인 상태라, 태그 검사만으로는 이 경로를 잡지 못한다.
    expect(files, `${assetPath}가 public/에 없다`).toContain(assetPath)
  })
})
