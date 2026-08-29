import { Link, NavLink } from 'react-router-dom'
import { useSession } from '@/auth/session'
import { HEADER_NAV_ITEM, headerMenus } from '@/components/header-nav'
import { AccountMenu } from '@/features/account/AccountMenu'
import { CLUB } from '@/features/landing/content'
import { cn } from '@/lib/utils'

/**
 * 헤더는 하나다. 관리자용 헤더를 따로 만들지 않고 role로 메뉴만 가른다 —
 * 관리자 라우트가 2개뿐이라 헤더를 복제할 이유가 없다.
 *
 * **메뉴 노출은 권한 통제가 아니다** (spec §3-1-7). 감춘 메뉴를 주소창으로 직접 열어도
 * #36의 라우트 가드가 막고, 서버가 다시 검증한다. 여기가 담당하는 것은 노출뿐이다.
 *
 * **목록은 `header-nav.ts`에 있다** (#306). 랜딩 헤더가 같은 목록을 쓰므로 여기에 두면
 * 화면이 늘 때 한쪽만 고쳐진다.
 */

export function AppHeader() {
  const { state } = useSession()

  const menus = headerMenus(state.kind === 'active' ? state.user.role : null)

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

        {/*
         * **계정은 아이콘 하나다** (#178). 마이페이지와 로그아웃이 그 안에 있다 — 글자
         * 버튼 둘을 헤더 오른쪽에 늘어놓으면 주요 메뉴가 넷(관리자는 다섯)인 지금 한 줄이
         * 빡빡하고, 좁은 화면에서 먼저 무너지는 자리가 여기다 (#249).
         *
         * **`PENDING`에게도 그린다.** 로그아웃은 그쪽도 쓰기 때문이다 (spec §3-1-3 매트릭스).
         * 마이페이지 항목만 `ACTIVE`에게 준다 — 띄워봤자 눌러도 가드가 되돌린다.
         */}
        <div className="ml-auto flex items-center gap-3">
          <AccountMenu
            showMyPage={state.kind === 'active'}
            redirectTo="/login"
          />
        </div>
      </div>
    </header>
  )
}
