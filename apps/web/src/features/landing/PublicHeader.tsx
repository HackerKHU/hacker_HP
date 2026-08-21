import { MenuIcon, XIcon } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { hasApplied, homePath, useSession } from '@/auth/session'
import { useLogout } from '@/auth/useLogout'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { CLUB, isPlaceholder, SECTIONS } from './content'

/** 왼쪽 메뉴 한 칸. 앵커와 라우트 링크가 같은 무게로 읽히도록 클래스를 공유한다. */
const NAV_ITEM =
  'rounded-md px-3 py-2 text-base text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground'

/**
 * 랜딩 전용 헤더. `AppHeader`와 별개 컴포넌트다 — 배경이 검정이고 메뉴가 라우트가 아니라
 * **섹션 앵커**라서 성격이 다르다. 하나로 합치면 두 성격이 조건문으로 뒤엉킨다.
 */
export function PublicHeader() {
  const session = useSession()
  /*
   * 로그아웃 후 이동하지 않는다. 랜딩은 비로그인도 볼 수 있는 페이지라 굳이 옮길 이유가
   * 없고, 보던 자리에 그대로 남는 편이 덜 놀랍다. 세션이 비면 헤더가 비로그인 상태로
   * 다시 그려져 로그아웃된 것이 화면에 드러난다.
   */
  const { logout, failed } = useLogout()
  /*
   * 모바일 메뉴. 항목을 누르면 닫는다 — 앵커는 페이지를 안 바꾸므로 저절로 닫히지 않고,
   * 열린 채 두면 이동한 섹션을 메뉴가 가린다.
   */
  const [menuOpen, setMenuOpen] = useState(false)
  const isActive = session.state.kind === 'active'
  // PENDING일 때만 의미가 있다. `null`이면 403으로만 알아내 신청 여부를 모르는 상태다.
  const applied = hasApplied(session)

  return (
    <header className="sticky top-0 z-10 border-b border-border bg-background/90 backdrop-blur">
      {/* 모바일은 간격을 줄인다 — 320px에서 "승인 대기 중"+로그아웃+햄버거가 gap-8을 못 버틴다. */}
      <div className="mx-auto flex h-20 w-full max-w-[1152px] items-center gap-4 px-4 md:gap-8 md:px-6">
        <a href="#top" className="text-xl font-semibold tracking-tight">
          {CLUB.name}
        </a>

        {/*
         * 섹션 앵커와 공지사항을 **한 덩어리로 묶되 구분선으로 가른다.**
         *
         * 둘은 하는 일이 다르다 — 앞쪽은 이 페이지 안에서 움직이고(`#about`), 공지사항은
         * 다른 화면으로 전환한다(`/notices`). 그대로 붙이면 눌렀을 때 페이지가 바뀌는지
         * 아닌지 예측할 수 없고, 멀리 떼어 놓으면 목적지 메뉴가 계정 조작(로그아웃) 옆에
         * 끼어 보인다. 묶어서 보여주고 성격은 선으로 가른다.
         *
         * `nav[aria-label="섹션 이동"]` 안에 넣지 않는 이유도 같다. 라우트 링크가 그
         * 이름 아래 들어가면 스크린리더에게 거짓말이 된다.
         */}
        <div className="hidden items-center gap-1 md:flex">
          <nav className="flex items-center gap-1" aria-label="섹션 이동">
            {SECTIONS.map((section) => (
              <a key={section.id} href={`#${section.id}`} className={NAV_ITEM}>
                {section.label}
              </a>
            ))}
          </nav>

          {/*
           * 부원에게만 보인다. `isActive`는 세션 확인이 끝나야 참이 되므로, 아래 오른쪽
           * 묶음처럼 `loading`을 따로 거를 필요가 없다 — 확인 전에는 그려지지 않는다.
           */}
          {isActive && (
            <>
              <span aria-hidden="true" className="mx-2 h-4 w-px bg-border" />
              <Link to={homePath(session)} className={NAV_ITEM}>
                공지사항
              </Link>
            </>
          )}
        </div>

        {/*
         * 세션을 확인하는 동안에는 아무것도 그리지 않는다. "로그인"을 먼저 그렸다가
         * 곧바로 다른 것으로 바뀌면 깜빡인다.
         */}
        {session.state.kind !== 'loading' && (
          <div className="ml-auto flex items-center gap-2">
            {failed && (
              <p role="alert" className="text-sm text-muted-foreground">
                로그아웃하지 못했습니다. 다시 시도해 주세요.
              </p>
            )}

            {session.state.kind === 'guest' ||
            session.state.kind === 'suspended' ? (
              <>
                {/*
                 * 아직 부원이 아닌 사람이다. 지원하기는 **외부 모집 폼**으로 나간다 —
                 * 이 사이트 로그인이 아니라 동아리 가입이라 가는 곳이 다르다.
                 * 강조(흰색)를 준 것은 랜딩을 처음 보는 사람이 대부분이기 때문이다.
                 */}
                {isPlaceholder(CLUB.applyUrl) ? (
                  /*
                   * 모집 폼 주소가 아직 없다. 링크를 살려두면 눌러서 `example.com`으로
                   * 나간다 — 자리표시자가 아니라 **고장난 링크**다. 잠가서 티를 낸다.
                   */
                  <Button
                    size="sm"
                    disabled
                    title="모집 폼 주소가 아직 없습니다"
                  >
                    지원하기
                  </Button>
                ) : (
                  <Button asChild size="sm">
                    <a
                      href={CLUB.applyUrl}
                      target="_blank"
                      rel="noreferrer noopener"
                    >
                      지원하기
                    </a>
                  </Button>
                )}
                <Button asChild variant="outline" size="sm">
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
                  <Button asChild variant="outline" size="sm">
                    <Link to={homePath(session)}>
                      {applied ? '승인 대기 중' : '지원하기'}
                    </Link>
                  </Button>
                )}
                <Button variant="ghost" size="sm" onClick={logout}>
                  로그아웃
                </Button>
              </>
            )}
          </div>
        )}

        {/*
         * 섹션 메뉴만 여기로 접는다. 지원하기·로그인은 밖에 남긴다 — 랜딩을 처음 보는
         * 사람의 다음 행동이라 한 번의 탭 뒤로 숨기지 않는다. 버튼이 `size="sm"`이라
         * 390px에서도 로고와 함께 들어간다.
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
       * 데스크톱과 같은 구분 원칙이다 — 섹션 앵커는 nav 안, 공지사항(라우트)은 밖.
       * 헤더 안에 두어 sticky를 같이 탄다.
       */}
      {menuOpen && (
        <div
          id="mobile-menu"
          className="border-t border-border px-4 pb-4 pt-2 md:hidden"
        >
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
          {isActive && (
            <>
              <div aria-hidden="true" className="my-2 h-px bg-border" />
              <Link
                to={homePath(session)}
                className={cn(NAV_ITEM, 'block')}
                onClick={() => setMenuOpen(false)}
              >
                공지사항
              </Link>
            </>
          )}
        </div>
      )}
    </header>
  )
}
