import type { Role } from '@/api/types'
import { PAGE_CONTAINER } from '@/components/page-container'
import { lookup } from '@/lib/lookup'

/**
 * 헤더 메뉴 한 칸의 모양. **두 헤더가 이 값을 같이 쓴다** (#261 검수).
 *
 * 랜딩(`PublicHeader`)과 내부 화면(`AppHeader`)이 각자 클래스를 들고 있어 **글씨 크기와
 * 세로 패딩이 갈려 있었다** — 랜딩이 `text-base py-2`(16px), 앱이 `text-sm py-1.5`(14px)라
 * 화면을 오갈 때 메뉴가 커졌다 작아졌다 했다.
 *
 * **랜딩 쪽(`text-base py-2`)으로 맞췄다.** 한때 앱 쪽(`text-sm`)으로 줄였는데 **화면에서
 * 보니 메뉴 전체가 작아 읽기 불편했다** — `text-sm`이 이 앱 조작 요소의 기본 크기라는 것은
 * 맞지만, 헤더는 손이 아니라 눈이 먼저 닿는 자리라 기준이 다르다. 화면을 직접 보고 내린
 * 판단이 규칙의 일관성보다 우선한다.
 *
 * 두 헤더 모두 `lg` 미만에서는 메뉴 묶음을 햄버거 뒤로 접는다 (#249). ADMIN의 다섯
 * 메뉴와 구분선까지 한 줄에 놓아도 안전한 1024px부터만 데스크톱 nav를 쓴다. 이 값은
 * 펼친 세로 메뉴에서도 그대로 써서 데스크톱과 모바일의 글씨 크기·터치 영역이 갈리지 않는다.
 *
 * **색은 각자 붙인다.** 앱은 현재 위치를 `text-foreground`로 드러내야 하고 랜딩은 그런
 * 구분이 없다 — 여기에 색까지 넣으면 그 차이가 조건문으로 되돌아온다.
 */
export const HEADER_NAV_ITEM =
  'rounded-md px-3 py-2 text-base transition-colors hover:bg-accent hover:text-accent-foreground'

/**
 * 헤더의 액션 버튼(로그아웃·로그인·지원하기·승인 대기 중).
 *
 * **메뉴와 같은 글씨 크기를 쓴다.** `Button`의 기본은 `text-sm`이라 메뉴만 키우면 바로
 * 옆 버튼이 한 단계 작아 보인다 — 같은 줄에 놓인 것들이라 크기가 갈리면 그것부터 눈에 띈다.
 *
 * **`size="sm"`(h-8)을 쓰지 않는다.** 16px 글자가 32px 상자에 들어가면 위아래가 눌린다.
 * 기본 크기(h-9)가 메뉴 한 칸(24px 글줄 + `py-2` = 40px)과도 덜 어긋난다.
 *
 * 색과 테두리는 각자 `variant`가 정한다 — 여기는 크기만 맞춘다.
 */
export const HEADER_ACTION = 'h-11 px-3 text-base lg:h-9 lg:px-4'

/**
 * 헤더 메뉴 한 칸.
 *
 * `apart`는 **앞에 구분선을 둔다**는 뜻이다 (#307). 지금 붙는 것은 회원 관리 하나뿐이지만,
 * 어느 칸 앞이라고 자리를 박아 두면 항목이 늘 때 렌더 쪽을 다시 고쳐야 한다.
 */
export interface HeaderMenu {
  to: string
  label: string
  apart?: boolean
}

/**
 * 부원이면 누구나 보는 메뉴. **두 헤더가 이 목록 하나를 같이 쓴다** (#306).
 *
 * 예전에는 `AppHeader`의 `MEMBER_MENUS`와 `PublicHeader`의 `MEMBER_LINKS`가 따로 있었다.
 * 화면이 늘 때 한쪽만 고쳐지는 자리였고, 실제로 자료게시판·갤러리가 생기고도(#59·#60)
 * 랜딩에는 공지사항만 있었다.
 */
const MEMBER_MENUS: HeaderMenu[] = [
  { to: '/notices', label: '공지사항' },
  /*
   * 자료는 **메뉴 하나**다. 갈래(시험·과목)는 그 화면 안의 탭으로 가른다 — 자료를 보러
   * 가는 것은 한 가지 일이고, 갈래는 거기서 고르는 조건이지 다른 목적지가 아니다.
   * 탭은 URL에 남으므로(`/notes?category=`) 링크 공유도 그대로 된다.
   */
  { to: '/notes', label: '자료게시판' },
  { to: '/posts', label: '자유게시판' },
  /*
   * 갤러리는 `ACTIVE`면 누구나 본다 — 업로드만 ADMIN이라 그 진입점은 갤러리 안에 둔다
   * (spec §3-1-3 매트릭스). 메뉴를 관리자에게만 보이면 부원이 사진을 볼 길이 없다.
   *
   * **글이 오가는 화면을 앞에 모으고 갤러리를 끝에 둔다.** 공지·자료·게시판은 읽고 쓰러
   * 오는 자리고, 갤러리는 둘러보는 자리다.
   */
  { to: '/photos', label: '갤러리' },
]

const MENUS = {
  USER: MEMBER_MENUS,
  ADMIN: [
    ...MEMBER_MENUS,
    /*
     * **관리자에게만 뜨는 화면이라 앞에서 끊는다** (#307, spec §3-1-3 매트릭스). 나머지
     * 넷은 부원이면 누구나 보는 곳이고 이것만 성격이 다른데, 같은 간격으로 붙어 있으면
     * 모양으로는 구별되지 않는다 — 관리자가 부원에게 화면을 보여주며 "그건 네 화면에는
     * 없다"를 말로만 해야 한다.
     */
    { to: '/admin/members', label: '회원 관리', apart: true },
  ],
} satisfies Record<Role, HeaderMenu[]>

/*
 * `/admin/notices` 계열 라우트는 App.tsx에 살아 있고 가드도 그대로다.
 * 진입 위치가 아직 정해지지 않아 **메뉴에서만 뺐다** — 죽은 라우트가 아니니 지우지 말 것.
 */

/**
 * 이 사람에게 보일 메뉴. **`ACTIVE`가 아니면 비어 있다** — `PENDING`은 공지도 볼 수 없어
 * (spec §3-1-3 매트릭스) 띄워봤자 눌러도 가드가 되돌린다. 비로그인은 랜딩에서 섹션 앵커를
 * 본다.
 *
 * 서버 응답도 신뢰 경계다. 계약에 없는 `role`이 오면 프로토타입 키에 걸려 죽지 않고
 * 메뉴가 비는 쪽으로 떨어진다.
 */
export function headerMenus(role: Role | null): HeaderMenu[] {
  if (role === null) return []
  return lookup(MENUS, role) ?? []
}

/**
 * 성격이 다른 메뉴를 가르는 선. **가로로 늘어선 헤더에서 쓴다.**
 *
 * 랜딩이 섹션 앵커와 부원 화면 링크를 가르는 데 쓰던 모양 그대로다 — 새 모양을 만들지
 * 않고 한 곳에 두어 두 헤더가 같이 쓴다. 색이나 아이콘을 더하지 않는 것도 의도다: 이
 * 화면에서 관리자가 주로 하는 일은 공지·자료를 보는 것이지 회원 관리가 아니다.
 *
 * **`aria-hidden`으로 그린다.** 선은 눈으로 읽는 것이고, 스크린리더에는 메뉴 사이에
 * 빈 항목이 하나 끼는 것으로만 들린다.
 */
export const HEADER_NAV_DIVIDER = 'mx-2 h-4 w-px bg-border'

/**
 * 같은 구분선의 세로 목록판. **모바일 메뉴는 세로로 쌓이므로 가로선이어야 한다** —
 * 세로선을 그으면 아무것도 가르지 못한다.
 */
export const HEADER_NAV_DIVIDER_STACKED = 'my-2 h-px bg-border'

/**
 * 두 헤더의 안쪽 컨테이너. 모바일에서는 심볼·상태 조작·메뉴 버튼을 세 열로 받고,
 * 1024px부터 기존 한 줄 flex로 돌아간다. 768px에서 전환하면 ACTIVE ADMIN의 다섯 메뉴와
 * 구분선·계정 조작이 768~약 816px의 실제 가용 폭을 넘어 서로 겹친다 (#319 검수).
 *
 * 320px 뷰포트의 고정 스크롤바 환경은 실제 콘텐츠 폭이 305px뿐이다. `gap-x-2`면
 * `px-6` 안의 257px에 44px 로고 링크·44px 메뉴 버튼·상태 조작이 함께 들어간다. 심볼은
 * 링크 안에서 27px 폭을 유지한다.
 *
 * **폭과 좌우 여백은 `PAGE_CONTAINER`가 정한다** (#389) — 랜딩 본문·푸터와 같은 정렬선
 * 위에 서야 하므로 여기서 따로 적지 않는다 (#247, #249).
 */
export const HEADER_CONTAINER = `${PAGE_CONTAINER} grid h-20 grid-cols-[auto_1fr_auto] items-center gap-x-2 lg:flex lg:flex-nowrap lg:gap-8`

/** 1024px 미만에서는 심볼, 그 이상에서는 기존 가로 락업을 쓸 때의 공통 크기다. */
export const HEADER_LOGO = 'h-8 w-[27px] lg:w-auto'

/** 심볼 자체는 27×32px지만 홈 링크의 모바일 터치 영역은 44×44px로 넓힌다. */
export const HEADER_LOGO_LINK =
  'col-start-1 inline-flex size-11 shrink-0 items-center lg:h-auto lg:w-auto'

/** 아이콘 하나인 모바일 메뉴 버튼도 44×44px 터치 영역과 보이는 포커스를 갖는다. */
export const HEADER_MENU_BUTTON =
  'inline-flex size-11 shrink-0 items-center justify-center p-0 lg:hidden'
