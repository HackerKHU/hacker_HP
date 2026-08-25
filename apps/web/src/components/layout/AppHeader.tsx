import { Link, NavLink } from 'react-router-dom'
import type { Role } from '@/api/types'
import { useSession } from '@/auth/session'
import { useLogout } from '@/auth/useLogout'
import { HEADER_ACTION, HEADER_NAV_ITEM } from '@/components/header-nav'
import { Button } from '@/components/ui/button'
import { CLUB } from '@/features/landing/content'
import { lookup } from '@/lib/lookup'
import { cn } from '@/lib/utils'

/**
 * 헤더는 하나다. 관리자용 헤더를 따로 만들지 않고 role로 메뉴만 가른다 —
 * 관리자 라우트가 2개뿐이라 헤더를 복제할 이유가 없다.
 *
 * **메뉴 노출은 권한 통제가 아니다** (spec §3-1-7). 감춘 메뉴를 주소창으로 직접 열어도
 * #36의 라우트 가드가 막고, 서버가 다시 검증한다. 여기가 담당하는 것은 노출뿐이다.
 */
const MEMBER_MENUS = [
  { to: '/notices', label: '공지사항' },
  /*
   * 자료는 **메뉴 하나**다. 갈래(시험·과목)는 그 화면 안의 탭으로 가른다 — 자료를 보러
   * 가는 것은 한 가지 일이고, 갈래는 거기서 고르는 조건이지 다른 목적지가 아니다.
   * 탭은 URL에 남으므로(`/notes?category=`) 링크 공유도 그대로 된다.
   */
  { to: '/notes', label: '자료게시판' },
  { to: '/posts', label: '자유 게시판' },
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
} satisfies Record<Role, { to: string; label: string }[]>

/*
 * `/admin/notices` 계열 라우트는 App.tsx에 살아 있고 가드도 그대로다.
 * 진입 위치가 아직 정해지지 않아 **메뉴에서만 뺐다** — 죽은 라우트가 아니니 지우지 말 것.
 */

export function AppHeader() {
  const { state } = useSession()
  // 로그아웃 로직은 랜딩 헤더와 함께 쓴다. 복사하지 않는다.
  const { logout, failed } = useLogout('/login')

  // PENDING은 공지도 볼 수 없으므로 메뉴가 없다. 띄워봤자 눌러도 가드가 되돌린다.
  // 서버 응답도 신뢰 경계다. 계약에 없는 role이 오면 프로토타입 키에 걸려 죽지 않고
  // 메뉴가 비는 쪽으로 떨어진다.
  const menus =
    state.kind === 'active' ? (lookup(MENUS, state.user.role) ?? []) : []

  return (
    <header className="border-b border-border bg-background">
      {/*
       * **`PublicHeader`와 같은 컨테이너다** (#247). 높이·폭·패딩·간격이 전부 같아야
       * 랜딩과 앱을 오갈 때 로고가 제자리에 있다.
       *
       * 모바일에서 간격을 줄이는 것도 같이 따른다 — 메뉴가 셋으로 늘어(#59) 좁은 화면에서
       * 랜딩 헤더와 같은 압박을 받는다.
       */}
      <div className="mx-auto flex h-20 w-full max-w-[1152px] items-center gap-4 px-6 md:gap-8">
        {/*
         * **로고는 랜딩으로 간다.** 헤더 로고는 사이트의 홈으로 가는 자리다 — `/notices`를
         * 가리키던 때는 바로 옆 주요 메뉴의 `공지사항`과 목적지가 겹쳤고, 로그인한 부원이
         * 랜딩으로 돌아갈 헤더 경로가 아예 없었다.
         *
         * 랜딩 헤더와 **같은 가로 락업이되 잉크만 반대**다 — 여기는 라이트 배경이라
         * 검정을 쓴다. 배경이 채워진 `-on-white`가 아니라 투명 배경이어야 한다.
         *
         * 높이·로고 크기 모두 랜딩(`h-20` + 32px 로고)과 같다. 한때 이 헤더만 `h-14`로
         * 낮췄지만, 랜딩에서 넘어올 때마다 상단이 24px 주저앉아 같은 사이트로 읽히지 않았다.
         */}
        <Link to="/" className="shrink-0">
          <img
            src="/brand/lockup-horizontal-black-512.png"
            alt={CLUB.name}
            width={512}
            height={104}
            className="h-8 w-auto"
          />
        </Link>

        <nav className="flex items-center gap-1" aria-label="주요 메뉴">
          {menus.map((menu) => (
            <NavLink
              key={menu.to}
              to={menu.to}
              end
              className={({ isActive }) =>
                /*
                 * 모양은 `PublicHeader`와 **같은 상수에서 온다** (#261 검수) — 화면을
                 * 오갈 때 메뉴 크기가 달라지지 않아야 한다. 여기만 현재 위치를 색으로
                 * 드러낸다.
                 */
                cn(
                  HEADER_NAV_ITEM,
                  isActive ? 'text-foreground' : 'text-muted-foreground',
                )
              }
            >
              {menu.label}
            </NavLink>
          ))}
        </nav>

        <div className="ml-auto flex items-center gap-3">
          {/* 토스트 같은 알림 수단이 아직 없다. 사용자가 실패를 알고 다시 누를 수 있으면 충분하다. */}
          {failed && (
            <p role="alert" className="text-sm text-muted-foreground">
              로그아웃하지 못했습니다. 다시 시도해 주세요.
            </p>
          )}
          <Button variant="ghost" className={HEADER_ACTION} onClick={logout}>
            로그아웃
          </Button>
        </div>
      </div>
    </header>
  )
}
