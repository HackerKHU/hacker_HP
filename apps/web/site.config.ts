/**
 * 배포 도메인. `og:url`과 `og:image`가 같은 값을 쓰므로 **여기 한 곳에서만 관리한다.**
 *
 * `vite.config.ts`가 `index.html`의 `%SITE_ORIGIN%`을 이 값으로 치환하고,
 * 메타 태그 테스트도 같은 값을 읽어 최종 결과를 검증한다 — 빌드와 검증이 같은 출처를 본다.
 *
 * ⚠️ TODO: 실제 도메인으로 교체한다 (#47). 도메인은 아직 미정이다
 * (spec 3-3 결정 5 — 도메인 없이 Vercel 프록시로 운영 중).
 * **`example.com`이 그대로 배포되면 링크 미리보기가 엉뚱한 곳을 가리킨다.**
 */
export const SITE_ORIGIN = 'https://example.com'
