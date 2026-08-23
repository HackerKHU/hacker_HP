/**
 * 배포 도메인. `og:url`과 `og:image`가 같은 값을 쓰므로 **여기 한 곳에서만 관리한다.**
 *
 * `vite.config.ts`가 `index.html`의 `%SITE_ORIGIN%`을 이 값으로 치환하고,
 * 메타 태그 테스트도 같은 값을 읽어 최종 결과를 검증한다 — 빌드와 검증이 같은 출처를 본다.
 *
 * 미리보기 봇은 상대경로를 못 읽어 절대 URL이어야 한다. 그래서 이 값이 틀리면 카카오톡·
 * 인스타그램 미리보기가 엉뚱한 곳을 가리키거나 그림이 비어 나온다.
 */
export const SITE_ORIGIN = 'https://www.khuhacker.com'
