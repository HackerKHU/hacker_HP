import type { Role } from '@/api/types'
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
 * **랜딩은 좁은 화면에서 이 값과 무관하다.** 메뉴 묶음이 `hidden md:flex`라 768px
 * 미만에서는 그려지지 않고 햄버거 뒤로 접힌다 — 그 폭에서 헤더 한 줄이 받는 압박(#249)은
 * 로고·액션 버튼·햄버거가 정하고 여기는 거기 들어가지 않는다.
 *
 * **앱 헤더는 접히지 않는다.** 메뉴가 늘 한 줄에 있어 키운 만큼 가로로 넓어진다 — 앱
 * 화면은 데스크톱 전용이라(spec §1-2 — 반응형은 범위 밖) 지금은 문제가 아니지만,
 * 모바일까지 받기로 하면 **여기가 아니라 그 헤더에 접는 규칙을 넣어야 한다.**
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
export const HEADER_ACTION = 'text-base'

/** 헤더 메뉴 한 칸. */
export interface HeaderMenu {
  to: string
  label: string
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
  ADMIN: [...MEMBER_MENUS, { to: '/admin/members', label: '회원 관리' }],
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
