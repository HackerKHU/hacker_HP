import { Link } from 'react-router-dom'
import { hasApplied, homePath, useSession } from '@/auth/session'
import { useLogout } from '@/auth/useLogout'
import { Button } from '@/components/ui/button'
import { CLUB, SECTIONS } from './content'

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
  const isActive = session.state.kind === 'active'
  // PENDING일 때만 의미가 있다. `null`이면 403으로만 알아내 신청 여부를 모르는 상태다.
  const applied = hasApplied(session)

  return (
    <header className="sticky top-0 z-10 border-b border-border bg-background/90 backdrop-blur">
      <div className="mx-auto flex h-20 w-full max-w-[1152px] items-center gap-8 px-6">
        <a href="#top" className="text-xl font-semibold tracking-tight">
          {CLUB.name}
        </a>

        <nav className="flex items-center gap-1" aria-label="섹션 이동">
          {SECTIONS.map((section) => (
            <a
              key={section.id}
              href={`#${section.id}`}
              className="rounded-md px-3 py-2 text-base text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
            >
              {section.label}
            </a>
          ))}
        </nav>

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
                <Button asChild size="sm">
                  <a
                    href={CLUB.applyUrl}
                    target="_blank"
                    rel="noreferrer noopener"
                  >
                    지원하기
                  </a>
                </Button>
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
                 */}
                {(isActive || applied !== null) && (
                  <Button asChild variant="outline" size="sm">
                    <Link to={homePath(session)}>
                      {isActive
                        ? '공지사항'
                        : applied
                          ? '승인 대기 중'
                          : '지원하기'}
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
      </div>
    </header>
  )
}
