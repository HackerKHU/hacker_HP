import { MenuIcon, XIcon } from 'lucide-react'
import { Fragment } from 'react'
import { Link, NavLink } from 'react-router-dom'
import { useSession } from '@/auth/session'
import {
  HEADER_CONTAINER,
  HEADER_LOGO,
  HEADER_LOGO_LINK,
  HEADER_MENU_BUTTON,
  HEADER_NAV_DIVIDER,
  HEADER_NAV_DIVIDER_STACKED,
  HEADER_NAV_ITEM,
  type HeaderMenu,
  headerMenus,
} from '@/components/header-nav'
import { useHeaderMenu } from '@/components/useHeaderMenu'
import { AccountMenu } from '@/features/account/AccountMenu'
import { CLUB } from '@/features/landing/content'
import { cn } from '@/lib/utils'

function AppNavigation({
  menus,
  stacked = false,
  onNavigate,
}: {
  menus: HeaderMenu[]
  stacked?: boolean
  onNavigate?: () => void
}) {
  return (
    <nav
      className={stacked ? 'flex flex-col' : 'flex items-center gap-1'}
      aria-label="주요 메뉴"
    >
      {menus.map((menu) => (
        <Fragment key={menu.to}>
          {menu.apart ? (
            stacked ? (
              <div aria-hidden="true" className={HEADER_NAV_DIVIDER_STACKED} />
            ) : (
              <span aria-hidden="true" className={HEADER_NAV_DIVIDER} />
            )
          ) : null}
          <NavLink
            to={menu.to}
            end
            className={({ isActive }) =>
              cn(
                HEADER_NAV_ITEM,
                stacked && 'flex min-h-11 items-center',
                isActive ? 'text-foreground' : 'text-muted-foreground',
              )
            }
            onClick={onNavigate}
          >
            {menu.label}
          </NavLink>
        </Fragment>
      ))}
    </nav>
  )
}

/**
 * 헤더는 하나다. 관리자용 헤더를 따로 만들지 않고 role로 메뉴만 가른다 —
 * 관리자 라우트가 2개뿐이라 헤더를 복제할 이유가 없다.
 *
 * 메뉴 노출은 권한 통제가 아니다 (spec §3-1-7). 감춘 메뉴를 주소창으로 직접 열어도
 * 라우트 가드와 서버가 다시 막는다. 여기가 담당하는 것은 노출과 이동뿐이다.
 */
export function AppHeader() {
  const { state } = useSession()
  const mobileMenu = useHeaderMenu()
  const menus = headerMenus(state.kind === 'active' ? state.user.role : null)

  return (
    <header className="relative z-40 border-b border-border bg-background">
      {/*
       * **헤더의 레이어만 fixed 결과 알림(z-30) 위에 둔다.**
       * `relative`는 쌓임 맥락을 만들 뿐 헤더를 sticky로 바꾸지 않는다. 내부 모바일
       * 메뉴가 펼쳐져도 알림이 링크를 가리거나 클릭을 가로채지 않게 하려는 계약이다.
       */}
      <div className={HEADER_CONTAINER}>
        {/*
         * 1024px 미만에서는 32px 심볼을 써서 워드마크의 157px 폭을 강요하지 않는다.
         * `lg`부터 기존 가로 락업으로 돌아가므로 데스크톱의 브랜드 모양은 바뀌지 않는다.
         * 두 자산은 같은 원본에서 만든 배포 사본이고 잉크만 라이트 배경에 맞는 검정이다.
         */}
        <Link to="/" className={HEADER_LOGO_LINK}>
          <picture>
            <source
              media="(max-width: 1023px)"
              srcSet="/brand/mark-black-256.png"
            />
            <img
              src="/brand/lockup-horizontal-black-512.png"
              alt={CLUB.name}
              width={512}
              height={104}
              className={HEADER_LOGO}
            />
          </picture>
        </Link>

        {/* 데스크톱은 기존 한 줄 메뉴와 현재 위치 표시를 유지한다. */}
        <div className="hidden items-center gap-1 lg:flex">
          <AppNavigation menus={menus} />
        </div>

        <div className="col-start-3 row-start-1 ml-auto flex items-center gap-2 lg:ml-auto lg:gap-3">
          {/*
           * 계정 메뉴는 PENDING에서도 로그아웃을 제공하므로 기존처럼 항상 남긴다.
           * 보호 화면에서는 로그아웃 뒤 `/login`으로 이동한다.
           */}
          <AccountMenu
            showMyPage={state.kind === 'active'}
            redirectTo="/login"
          />

          {/* 메뉴가 없는 loading·guest·PENDING에는 빈 햄버거를 그리지 않는다. */}
          {menus.length > 0 ? (
            <button
              ref={mobileMenu.triggerRef}
              type="button"
              className={cn(HEADER_NAV_ITEM, HEADER_MENU_BUTTON)}
              aria-expanded={mobileMenu.open}
              aria-controls="app-mobile-menu"
              aria-label={mobileMenu.open ? '메뉴 닫기' : '메뉴 열기'}
              onClick={mobileMenu.toggle}
            >
              {mobileMenu.open ? (
                <XIcon className="size-5" aria-hidden="true" />
              ) : (
                <MenuIcon className="size-5" aria-hidden="true" />
              )}
            </button>
          ) : null}
        </div>
      </div>

      {menus.length > 0 && mobileMenu.open ? (
        <div
          id="app-mobile-menu"
          className="border-t border-border px-6 pb-4 pt-2 lg:hidden"
        >
          <AppNavigation menus={menus} stacked onNavigate={mobileMenu.close} />
        </div>
      ) : null}
    </header>
  )
}
