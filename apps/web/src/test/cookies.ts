/**
 * 테스트에서 쿠키를 다루는 공용 도구.
 *
 * **jsdom의 `document.cookie`는 테스트 사이에 남는다.** 한 테스트가 심은 `XSRF-TOKEN`이
 * 다음 테스트에 그대로 보이면, 실행 순서에 따라 "쿠키가 없을 때"의 동작이 검증되기도 하고
 * 건너뛰어지기도 한다 — 순서에 기대는 플레이키가 된다. 그래서 심는 것과 지우는 것을
 * 한 곳에 모아 각 파일이 `beforeEach`에서 명시적으로 상태를 정한다.
 */

/** 쿠키 하나를 심는다. 값은 그대로 들어간다(인코딩하지 않는다). */
export function setCookie(name: string, value: string) {
  // biome-ignore lint/suspicious/noDocumentCookie: jsdom에 Cookie Store API가 없다. 테스트에서 쿠키를 심는 수단은 이것뿐이다.
  document.cookie = `${name}=${value}; path=/`
}

/** 지금 문서에 있는 쿠키를 전부 지운다. */
export function clearCookies() {
  for (const part of document.cookie.split(';')) {
    const name = part.split('=')[0]?.trim()
    if (name) {
      // biome-ignore lint/suspicious/noDocumentCookie: 위와 같다 — jsdom에서 쿠키를 지우는 방법이 이것뿐이다.
      document.cookie = `${name}=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT`
    }
  }
}
