/**
 * 네이티브 `<select>`의 모양.
 *
 * **shadcn `Select`를 들이지 않는다.** 과목·연도처럼 항목이 수십 개인 목록은 모바일에서
 * OS 기본 피커가 커스텀 리스트박스보다 낫고(휠·검색·한 손 조작), radix 의존성도 늘지
 * 않는다. 모양만 `Input`과 맞춰 폼 안에서 결이 어긋나지 않게 한다.
 *
 * `appearance-none`으로 기본 화살표를 지우고 배경 이미지로 직접 그린다. 지우지 않으면
 * 브라우저마다 다른 화살표가 붙어 다른 입력들과 높이·여백이 어긋난다.
 *
 * 신청 화면이 이걸 쓴다 (#98에서 옮겼다 — 사본으로 두었더니 공용 쪽만 16px로 올라가고
 * 그 화면은 14px로 남았다).
 *
 * ponytail: 회원 관리의 select는 아직 각자 들고 있다. 화살표 없는 다른 모양이라 여기 합치려면
 * 변형을 하나 만들어야 해서 남겼다 — 그 화면을 다시 손댈 때 옮긴다.
 */
export const SELECT_CLASS =
  'h-9 w-full min-w-0 appearance-none rounded-md border border-input bg-transparent bg-no-repeat px-3 py-1 pr-9 text-base shadow-xs transition-[color,box-shadow] outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:pointer-events-none disabled:cursor-not-allowed disabled:opacity-50 dark:bg-input/30'

/**
 * 화살표. `currentColor`를 쓸 수 없어(배경 이미지다) 무채색 팔레트의 중간 값을 직접 넣는다.
 *
 * **위치·크기도 여기서 준다.** Tailwind 임의값(`bg-[position:...]`)으로 쓰면 밑줄
 * 이스케이프를 한 글자만 틀려도 값이 조용히 버려져 화살표가 왼쪽 끝에 붙는다.
 */
export const SelectArrow = {
  backgroundImage:
    "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16' fill='none' stroke='%23888' stroke-width='1.5'%3E%3Cpath d='M4 6l4 4 4-4'/%3E%3C/svg%3E\")",
  backgroundPosition: 'right 0.625rem center',
  backgroundSize: '1rem',
} as const
