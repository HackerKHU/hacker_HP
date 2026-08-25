/**
 * 헤더 메뉴 한 칸의 모양. **두 헤더가 이 값을 같이 쓴다** (#261 검수).
 *
 * 랜딩(`PublicHeader`)과 내부 화면(`AppHeader`)이 각자 클래스를 들고 있어 **글씨 크기와
 * 세로 패딩이 갈려 있었다** — 랜딩이 `text-base py-2`(16px), 앱이 `text-sm py-1.5`(14px)라
 * 화면을 오갈 때 메뉴가 커졌다 작아졌다 했다.
 *
 * **앱 쪽(`text-sm`)으로 맞췄다.** 근거 둘:
 *
 * - `text-sm`이 이 앱에서 **조작 요소의 기본 크기**다 — 버튼(`size="sm"`), 표 셀, 대부분의
 *   본문이 그렇다. 헤더만 한 단계 큰 것이 예외였다.
 * - 메뉴가 늘었다. 앱은 넷(관리자는 다섯), 랜딩은 섹션 앵커까지 여덟이다 — **키우면 그만큼
 *   가로로 밀리고**, 랜딩 헤더가 좁은 화면에서 넘치는 문제(#249)가 아직 열려 있다.
 *
 * **색은 각자 붙인다.** 앱은 현재 위치를 `text-foreground`로 드러내야 하고 랜딩은 그런
 * 구분이 없다 — 여기에 색까지 넣으면 그 차이가 조건문으로 되돌아온다.
 */
export const HEADER_NAV_ITEM =
  'rounded-md px-3 py-1.5 text-sm transition-colors hover:bg-accent hover:text-accent-foreground'
