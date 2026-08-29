import { MenuIcon, XIcon } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { hasApplied, homePath, useSession } from '@/auth/session'
import {
  HEADER_ACTION,
  HEADER_NAV_ITEM,
  headerMenus,
} from '@/components/header-nav'
import { Button } from '@/components/ui/button'
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

/**
 * 랜딩 전용 헤더. `AppHeader`와 별개 컴포넌트다 — 배경이 검정이고 메뉴가 라우트가 아니라
 * **섹션 앵커**라서 성격이 다르다. 하나로 합치면 두 성격이 조건문으로 뒤엉킨다.
 */
export function PublicHeader() {
  const session = useSession()
  /*
   * 모바일 메뉴. 항목을 누르면 닫는다 — 앵커는 페이지를 안 바꾸므로 저절로 닫히지 않고,
   * 열린 채 두면 이동한 섹션을 메뉴가 가린다.
   */
  const [menuOpen, setMenuOpen] = useState(false)
  const isActive = session.state.kind === 'active'
  /**
   * 부원 화면 메뉴. **내부 헤더와 같은 목록이다** (#306, `header-nav.ts`) — 두 화면을
   * 오가는 사람에게 헤더가 바뀌면 로고가 8px 미끄러졌던 것(#247)과 같은 종류로 어긋난다.
   */
  const menus = headerMenus(
    // `isActive`로 좁히지 않는다 — 불리언은 유니온을 좁히지 못해 `state.user`가 안 보인다.
    session.state.kind === 'active' ? session.state.user.role : null,
  )
  // PENDING일 때만 의미가 있다. `null`이면 403으로만 알아내 신청 여부를 모르는 상태다.
  const applied = hasApplied(session)

  return (
    <header className="sticky top-0 z-10 border-b border-border bg-background/90 backdrop-blur">
      {/*
       * **`AppHeader`와 같은 컨테이너다** (#247). 높이·폭·패딩·간격이 전부 같아야 랜딩에서
       * 로그인해 넘어올 때 로고가 움직이지 않는다.
       *
       * 모바일은 **간격만** 줄인다 — 320px에서 "승인 대기 중"+로그아웃+햄버거가 `gap-8`을
       * 못 버틴다. **가로 패딩은 줄이지 않는다**: 한때 `px-4`였는데 그것은 위 근거가 아니라
       * 간격과 함께 딸려온 값이었고, 랜딩 본문(`CONTAINER`)과 푸터가 `px-6`이라 **좁은
       * 화면에서 헤더 로고만 본문보다 8px 왼쪽에 섰다.**
       */}
      <div className="mx-auto flex h-20 w-full max-w-[1152px] items-center gap-4 px-6 md:gap-8">
        {/*
         * 가로 락업(심볼 + `HACKER`). 헤더는 가로로 긴 자리라 세로 락업을 넣으면 높이가
         * 눌려 글자가 안 읽힌다 (`brand/README.md` — 가로 락업은 그래서 워드마크를
         * 원작 비율의 2.4배로 키운 조합이다).
         *
         * 랜딩은 `.dark`라 **흰 잉크**를 쓴다. 배경이 채워진 `-on-black`이 아니라
         * 투명 배경이어야 헤더의 반투명 배경 위에서 네모가 안 비친다.
         *
         * 높이를 고정하고 폭을 `auto`로 둔다 — 원본 512×104의 비율이 그대로 지켜진다.
         */}
        <a href="#top" className="shrink-0">
          <img
            src="/brand/lockup-horizontal-white-512.png"
            alt={CLUB.name}
            width={512}
            height={104}
            className="h-8 w-auto"
          />
        </a>

        {/*
         * 섹션 앵커와 부원 화면 링크를 **한 덩어리로 묶되 구분선으로 가른다** (#155).
         *
         * 둘은 하는 일이 다르다 — 앞쪽은 이 페이지 안에서 움직이고(`#about`), 뒤쪽은
         * 다른 화면으로 전환한다(`/notices`·`/notes`·`/photos`). 그대로 붙이면 눌렀을 때
         * 페이지가 바뀌는지 아닌지 예측할 수 없고, 멀리 떼어 놓으면 목적지 메뉴가 계정
         * 조작(로그아웃) 옆에 끼어 보인다. 묶어서 보여주고 성격은 선으로 가른다.
         *
         * `nav[aria-label="섹션 이동"]` 안에 넣지 않는 이유도 같다. 라우트 링크가 그
         * 이름 아래 들어가면 스크린리더에게 거짓말이 된다.
         *
         * **`md` 미만에서는 통째로 접힌다.** 늘어난 링크가 좁은 화면의 한 줄을 더 밀지
         * 않는다 — 320px 압박(#249)과 무관하게 두려는 것이다.
         */}
        <div className="hidden items-center gap-1 md:flex">
          {isActive ? (
            /*
             * **부원에게는 내부 화면과 같은 메뉴만 보여준다** (#306).
             *
             * 로그인한 부원에게 랜딩은 읽을 페이지가 아니라 **지나가는 자리**다. 이미 아는
             * 소개를 다시 읽지 않고, 헤더에서 찾는 것은 공지와 자료다. 섹션 앵커 다섯 개
             * 뒤에 그 넷이 밀려 있으면 두 화면을 오갈 때 메뉴가 아홉 칸에서 넷으로 줄어든다.
             *
             * 섹션으로 가는 길을 잃지만, 페이지를 내려 읽으면 그대로 있다 — 로고를 눌러
             * 랜딩에 온 사람은 대개 그 아래를 스크롤한다.
             *
             * **`nav`의 이름은 `주요 메뉴`다** — 내부 헤더가 쓰는 그 이름이다. `섹션 이동`
             * 아래에 라우트 링크를 넣으면 스크린리더에게 거짓말이 되고, 이름까지 같아야
             * 두 화면을 오갈 때 같은 메뉴로 읽힌다.
             */
            <nav className="flex items-center gap-1" aria-label="주요 메뉴">
              {menus.map((menu) => (
                <Link key={menu.to} to={menu.to} className={NAV_ITEM}>
                  {menu.label}
                </Link>
              ))}
            </nav>
          ) : (
            /*
             * **비로그인과 `PENDING`은 섹션 앵커다.** 그 다섯이 이 페이지를 읽는 순서이고,
             * 부원 화면 링크는 눌러도 가드가 로그인으로 되돌린다 (spec §3-1-3) — 누르기
             * 전에는 그렇게 될 줄 모른다.
             */
            <nav className="flex items-center gap-1" aria-label="섹션 이동">
              {SECTIONS.map((section) => (
                <a
                  key={section.id}
                  href={`#${section.id}`}
                  className={NAV_ITEM}
                >
                  {section.label}
                </a>
              ))}
            </nav>
          )}
        </div>

        {/*
         * 세션을 확인하는 동안에는 아무것도 그리지 않는다. "로그인"을 먼저 그렸다가
         * 곧바로 다른 것으로 바뀌면 깜빡인다.
         */}
        {session.state.kind !== 'loading' && (
          <div className="ml-auto flex items-center gap-2">
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
                <Button asChild variant="outline" className={HEADER_ACTION}>
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
                 * ACTIVE는 여기 없다. 부원의 공지사항은 **목적지**라 왼쪽 메뉴로 옮겼고,
                 * 이 자리는 내 상태와 다음 행동만 남긴다 — 로그인·로그아웃과 같은 성격이다.
                 */}
                {!isActive && applied !== null && (
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
                <AccountMenu showMyPage={isActive} />
              </>
            )}
          </div>
        )}

        {/*
         * 섹션 메뉴만 여기로 접는다. 지원하기·로그인은 밖에 남긴다 — 랜딩을 처음 보는
         * 사람의 다음 행동이라 한 번의 탭 뒤로 숨기지 않는다.
         *
         * ⚠️ **버튼이 커졌다.** 한때 `size="sm"`(h-8·14px)이라 390px에서도 로고와 함께
         * 들어간다고 적혀 있었는데, 메뉴 글씨에 맞춰 기본 크기(h-9·16px)로 올리면서
         * 가로로 더 넓어졌다. **좁은 화면에서 이 줄이 넘치는지는 실측이 필요하다** —
         * #249가 그것을 기다리고 있고, 그 값이 여기서 한 번 더 나빠졌다.
         *
         * 세션 확인 중에도 그린다 — 섹션 이동은 세션과 무관하다.
         */}
        <button
          type="button"
          className={cn(
            NAV_ITEM,
            'px-2 md:hidden',
            // 액션 묶음이 없을 때(세션 확인 중)도 오른쪽에 붙도록. 있으면 그 옆이다.
            session.state.kind === 'loading' && 'ml-auto',
          )}
          aria-expanded={menuOpen}
          aria-controls="mobile-menu"
          aria-label={menuOpen ? '메뉴 닫기' : '메뉴 열기'}
          onClick={() => setMenuOpen((open) => !open)}
        >
          {menuOpen ? (
            <XIcon className="size-5" aria-hidden="true" />
          ) : (
            <MenuIcon className="size-5" aria-hidden="true" />
          )}
        </button>
      </div>

      {/*
       * 데스크톱과 같은 구분 원칙이다 — 섹션 앵커는 nav 안, 부원 화면(라우트)은 밖.
       * 헤더 안에 두어 sticky를 같이 탄다.
       *
       * **세로로 쌓이므로 항목이 늘어도 가로 폭을 먹지 않는다.** 좁은 화면에서 헤더 한 줄이
       * 받는 압박(#249)은 로고·액션·햄버거가 정하고, 이 목록은 거기 들어가지 않는다.
       */}
      {menuOpen && (
        <div
          id="mobile-menu"
          className="border-t border-border px-4 pb-4 pt-2 md:hidden"
        >
          {/* 데스크톱과 같은 규칙이다 (#306) — 부원에게는 라우트 메뉴, 그 밖에는 섹션 앵커. */}
          {isActive ? (
            <nav className="flex flex-col" aria-label="주요 메뉴">
              {menus.map((menu) => (
                <Link
                  key={menu.to}
                  to={menu.to}
                  className={cn(NAV_ITEM, 'block')}
                  onClick={() => setMenuOpen(false)}
                >
                  {menu.label}
                </Link>
              ))}
            </nav>
          ) : (
            <nav className="flex flex-col" aria-label="섹션 이동">
              {SECTIONS.map((section) => (
                <a
                  key={section.id}
                  href={`#${section.id}`}
                  className={NAV_ITEM}
                  onClick={() => setMenuOpen(false)}
                >
                  {section.label}
                </a>
              ))}
            </nav>
          )}
        </div>
      )}
    </header>
  )
}
