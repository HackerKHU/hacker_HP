import { MenuIcon, XIcon } from 'lucide-react'
import { Fragment } from 'react'
import { Link } from 'react-router-dom'
import {
  hasApplied,
  homePath,
  type SessionState,
  useSession,
} from '@/auth/session'
import {
  HEADER_ACTION,
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
import { Button } from '@/components/ui/button'
import { useHeaderMenu } from '@/components/useHeaderMenu'
import { AccountMenu } from '@/features/account/AccountMenu'
import { cn } from '@/lib/utils'
import { CLUB, SECTIONS } from './content'

/**
 * 왼쪽 메뉴 한 칸. 앵커와 라우트 링크가 같은 무게로 읽히도록 클래스를 공유한다.
 *
 * 모양은 `AppHeader`와 **같은 상수에서 온다** — 화면을 오갈 때 메뉴 크기가 달라지지
 * 않아야 한다. 색만 여기서 붙인다 (랜딩에는 현재 위치 표시가 없다).
 */
const NAV_ITEM = cn(HEADER_NAV_ITEM, 'text-muted-foreground')

type LandingNavigation =
  | { kind: 'hidden' }
  | { kind: 'public' }
  | { kind: 'member'; menus: HeaderMenu[] }

/**
 * 세션 상태에서 랜딩 헤더의 왼쪽 내비게이션을 한 번만 결정한다.
 *
 * `active`는 세션 유니온의 이름이지 계정의 `ACTIVE` status만 뜻하지 않는다.
 * `INACTIVE` 부원도 이 갈래에 들어오므로 공개 메뉴가 아니라 부원 메뉴를 본다.
 */
function landingNavigation(state: SessionState): LandingNavigation {
  switch (state.kind) {
    case 'loading':
      return { kind: 'hidden' }
    case 'active':
      return { kind: 'member', menus: headerMenus(state.user.role) }
    default:
      return { kind: 'public' }
  }
}

function HeaderNavigation({
  navigation,
  stacked = false,
  onNavigate,
}: {
  navigation: Exclude<LandingNavigation, { kind: 'hidden' }>
  stacked?: boolean
  onNavigate?: () => void
}) {
  const className = stacked ? 'flex flex-col' : 'flex items-center gap-1'

  if (navigation.kind === 'public') {
    return (
      <nav className={className} aria-label="섹션 이동">
        {SECTIONS.map((section) => (
          <a
            key={section.id}
            href={`#${section.id}`}
            className={cn(NAV_ITEM, stacked && 'flex min-h-11 items-center')}
            onClick={onNavigate}
          >
            {section.label}
          </a>
        ))}
      </nav>
    )
  }

  return (
    <nav className={className} aria-label="주요 메뉴">
      {navigation.menus.map((menu) => (
        <Fragment key={menu.to}>
          {menu.apart ? (
            stacked ? (
              <div aria-hidden="true" className={HEADER_NAV_DIVIDER_STACKED} />
            ) : (
              <span aria-hidden="true" className={HEADER_NAV_DIVIDER} />
            )
          ) : null}
          <Link
            to={menu.to}
            className={cn(NAV_ITEM, stacked && 'flex min-h-11 items-center')}
            onClick={onNavigate}
          >
            {menu.label}
          </Link>
        </Fragment>
      ))}
    </nav>
  )
}

/**
 * 랜딩 전용 헤더. `AppHeader`와 별개 컴포넌트다 — 배경이 검정이고, 방문자에게는 섹션
 * 앵커를 보여 주는 공개 페이지의 역할이 남아 있다. 부원 메뉴 목록은 `header-nav.ts`에서
 * 공유하되 어느 목록을 보여 줄지는 이 헤더가 세션 상태로 결정한다.
 */
export function PublicHeader() {
  const session = useSession()
  /*
   * 모바일 메뉴. 항목을 누르면 닫는다 — 앵커는 페이지를 안 바꾸므로 저절로 닫히지 않고,
   * 열린 채 두면 이동한 섹션을 메뉴가 가린다.
   */
  const mobileMenu = useHeaderMenu()
  const hasMemberSession = session.state.kind === 'active'
  const navigation = landingNavigation(session.state)
  // PENDING일 때만 의미가 있다. `null`이면 403으로만 알아내 신청 여부를 모르는 상태다.
  const applied = hasApplied(session)

  return (
    <header className="sticky top-0 z-10 border-b border-border bg-background/90 backdrop-blur">
      {/*
       * **`AppHeader`와 같은 컨테이너다** (#247, #249). 높이·폭·패딩·간격이 같아야
       * 랜딩에서 로그인해 넘어올 때 로고가 움직이지 않는다.
       *
       * **가로 패딩은 줄이지 않는다**: 랜딩 본문(`CONTAINER`)과 푸터가 `px-6`이라 좁은
       * 화면에서도 헤더 로고가 같은 정렬선에 있어야 한다.
       */}
      <div className={HEADER_CONTAINER}>
        {/*
         * 1024px 미만에서는 32px 심볼로 폭을 줄이고 `lg`부터 가로 락업(심볼 + `HACKER`)을
         * 쓴다. 데스크톱 헤더는 가로로 긴 자리라 세로 락업을 넣으면 높이가 눌려 글자가
         * 안 읽힌다.
         *
         * 랜딩은 `.dark`라 **흰 잉크**를 쓴다. 배경이 채워진 `-on-black`이 아니라
         * 투명 배경이어야 헤더의 반투명 배경 위에서 네모가 안 비친다.
         *
         * 두 자산 모두 높이를 32px로 고정하고 각 원본 비율을 지킨다.
         */}
        <a href="#top" className={HEADER_LOGO_LINK}>
          <picture>
            <source
              media="(max-width: 1023px)"
              srcSet="/brand/mark-white-512.png"
            />
            <img
              src="/brand/lockup-horizontal-white-512.png"
              alt={CLUB.name}
              width={512}
              height={104}
              className={HEADER_LOGO}
            />
          </picture>
        </a>

        {/*
         * 공개 앵커와 부원 메뉴는 **서로 배타적이다** (#305). 세션에서 고른 `navigation`
         * 하나를 데스크톱과 모바일이 같이 써서 한쪽만 다른 메뉴를 보이는 일을 막는다.
         * `loading`이면 이 묶음 자체를 그리지 않아 공개 메뉴가 먼저 번쩍이지 않는다.
         *
         * **`lg` 미만에서는 통째로 접힌다.** ADMIN의 다섯 링크와 구분선까지 768px에서
         * 펼쳐 한 줄을 밀지 않는다 — 태블릿 폭에서도 모든 목적지를 햄버거로 제공한다.
         */}
        {navigation.kind !== 'hidden' && (
          <div className="hidden items-center gap-1 lg:flex">
            <HeaderNavigation navigation={navigation} />
          </div>
        )}

        {/*
         * 세션을 확인하는 동안에는 아무것도 그리지 않는다. "로그인"을 먼저 그렸다가
         * 곧바로 다른 것으로 바뀌면 깜빡인다. 320px에서는 guest의 보조 동작인 로그인만
         * 모바일 메뉴에도 두고 첫 줄에서는 숨긴다. 지원하기와 나머지 상태 조작은 한 번의
         * 탭 뒤로 숨기지 않는다.
         */}
        {session.state.kind !== 'loading' && (
          <div className="col-start-2 row-start-1 flex min-w-0 items-center justify-end gap-2 lg:col-auto lg:row-auto lg:ml-auto">
            {session.state.kind === 'guest' ||
            session.state.kind === 'suspended' ? (
              <>
                {/*
                 * 아직 부원이 아닌 사람이다. **지원은 이 사이트에서 받는다** — 구글
                 * 로그인 뒤 신청서(학번·학과)를 내면 그것이 동아리 지원이고,
                 * 관리자 승인까지가 한 흐름이다. 외부 모집 폼을 두지 않는다.
                 *
                 * 그래서 목적지는 로그인과 같지만 **버튼을 합치지 않는다.** 처음 온
                 * 사람에게 "로그인"은 이미 계정이 있는 사람의 말이라, 지원하러 온 사람이
                 * 자기 자리를 못 찾는다. 강조(흰색)를 준 것도 랜딩을 처음 보는 사람이
                 * 대부분이기 때문이다.
                 */}
                {/*
                 * **정지된 계정에는 지원하기를 보이지 않는다.** 그 계정은 로그인 자체가
                 * 막혀 있어 눌러도 정지 안내만 뜬다 — 목적을 못 이루는 CTA를 강조색으로
                 * 두면 "여기를 누르면 된다"는 거짓말이 된다. 옆의 로그인과 목적지도 겹친다.
                 */}
                {session.state.kind === 'guest' && (
                  <Button asChild className={HEADER_ACTION}>
                    <Link to="/login">지원하기</Link>
                  </Button>
                )}
                <Button
                  asChild
                  variant="outline"
                  className={cn(
                    HEADER_ACTION,
                    session.state.kind === 'guest' && 'hidden lg:inline-flex',
                  )}
                >
                  <Link to="/login">로그인</Link>
                </Button>
              </>
            ) : (
              <>
                {/*
                 * 로그인한 사람이다. **PENDING 안에 두 상태가 있다** (spec §3-1-4) —
                 * 구글 로그인만 한 계정과 신청서를 낸 계정은 다르다. 승인 대상은 신청서를
                 * 낸 쪽으로 한정되므로(MUST), 신청도 안 한 사람에게 "승인 대기 중"은 거짓말이다.
                 *
                 * 판별은 신청서 제출 시각(`appliedAt`)으로 한다 — 승인 목록 포함 여부를
                 * 가르는 값과 같아야 서버와 어긋나지 않는다.
                 *
                 * 신청 전이면 지원하기가 **사이트 신청 화면**으로 간다. 로그인까지 한
                 * 사람에게 외부 모집 폼을 다시 보여줄 이유가 없다.
                 *
                 * `hasApplied`가 `null`이면 403으로만 알아낸 경우라 신청 여부를 모른다.
                 * 그때는 아무 주장도 하지 않고 로그아웃만 남긴다.
                 *
                 * 부원 세션은 여기 없다. 부원의 공지사항은 **목적지**라 왼쪽 메뉴로 옮겼고,
                 * 이 자리는 내 상태와 다음 행동만 남긴다 — 로그인·로그아웃과 같은 성격이다.
                 */}
                {!hasMemberSession && applied !== null && (
                  <Button asChild variant="outline" className={HEADER_ACTION}>
                    <Link to={homePath(session)}>
                      {applied ? '승인 대기 중' : '지원하기'}
                    </Link>
                  </Button>
                )}
                {/*
                 * **앱 헤더와 같은 계정 메뉴다** (#178). 복사하지 않고 그 컴포넌트를 그대로
                 * 쓴다 — 로그인한 사람이 랜딩에 들렀다가 부원 화면으로 넘어갈 때 같은 자리에
                 * 같은 아이콘이 있어야 한다. 두 헤더가 각자 들고 있으면 한쪽만 고쳐진다
                 * (로고 정렬을 #247·#258·#264에서 세 번 맞춘 것이 그 종류의 어긋남이다).
                 *
                 * **로그아웃 뒤 이동하지 않는다** — `redirectTo`를 주지 않았다. 랜딩은
                 * 비로그인도 볼 수 있어 굳이 옮길 이유가 없고, 보던 자리에 남는 편이 덜
                 * 놀랍다. 세션이 비면 이 헤더가 비로그인 모습으로 다시 그려져 로그아웃된
                 * 것이 화면에 드러난다.
                 */}
                <AccountMenu showMyPage={hasMemberSession} />
              </>
            )}
          </div>
        )}

        {/*
         * 세션 확인 전에는 공개 메뉴를 먼저 열 수 없게 버튼 자체를 그리지 않는다.
         * 액션 뒤에 두어 모바일의 시각 순서와 키보드 탭 순서가 같다.
         */}
        {navigation.kind !== 'hidden' && (
          <button
            ref={mobileMenu.triggerRef}
            type="button"
            className={cn(
              NAV_ITEM,
              HEADER_MENU_BUTTON,
              'col-start-3 row-start-1',
            )}
            aria-expanded={mobileMenu.open}
            aria-controls="public-mobile-menu"
            aria-label={mobileMenu.open ? '메뉴 닫기' : '메뉴 열기'}
            onClick={mobileMenu.toggle}
          >
            {mobileMenu.open ? (
              <XIcon className="size-5" aria-hidden="true" />
            ) : (
              <MenuIcon className="size-5" aria-hidden="true" />
            )}
          </button>
        )}
      </div>

      {/*
       * 데스크톱과 같은 `navigation`을 쓴다. 관리자의 회원 관리 앞 구분선만 세로 목록에
       * 맞는 가로선으로 바뀌고, 링크 집합과 순서는 그대로다.
       *
       * **세로로 쌓이므로 항목이 늘어도 가로 폭을 먹지 않는다.** 좁은 화면에서 헤더 한 줄이
       * 받는 압박(#249)은 로고·액션·햄버거가 정하고, 이 목록은 거기 들어가지 않는다.
       */}
      {navigation.kind !== 'hidden' && mobileMenu.open && (
        <div
          id="public-mobile-menu"
          className="border-t border-border px-6 pb-4 pt-2 lg:hidden"
        >
          <HeaderNavigation
            navigation={navigation}
            stacked
            onNavigate={mobileMenu.close}
          />
          {session.state.kind === 'guest' ? (
            <>
              <div aria-hidden="true" className={HEADER_NAV_DIVIDER_STACKED} />
              <nav aria-label="계정">
                <Link
                  to="/login"
                  className={cn(NAV_ITEM, 'flex min-h-11 items-center')}
                  onClick={mobileMenu.close}
                >
                  로그인
                </Link>
              </nav>
            </>
          ) : null}
        </div>
      )}
    </header>
  )
}
