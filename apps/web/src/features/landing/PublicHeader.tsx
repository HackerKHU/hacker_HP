import { Link } from 'react-router-dom'
import { homePath, useSession } from '@/auth/session'
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
            {session.state.kind === 'active' ||
            session.state.kind === 'pending' ? (
              <>
                {/*
                 * 로그인한 사람이 랜딩에 왔을 때 들어갈 문과 나갈 문이 모두 필요하다.
                 * **PENDING에게 "승인 대기 중"을 보여주는 것이 중요하다** — 신청해놓고
                 * 기다리는 사람이 자기 상태를 여기서 알 수 있어야 한다.
                 */}
                <Button asChild variant="outline" size="sm">
                  <Link to={homePath(session)}>
                    {session.state.kind === 'pending'
                      ? '승인 대기 중'
                      : '공지사항'}
                  </Link>
                </Button>
                {failed && (
                  <p role="alert" className="text-sm text-muted-foreground">
                    로그아웃하지 못했습니다. 다시 시도해 주세요.
                  </p>
                )}
                <Button variant="ghost" size="sm" onClick={logout}>
                  로그아웃
                </Button>
              </>
            ) : (
              <>
                {/*
                 * 두 버튼은 가는 곳이 완전히 다르다.
                 * **지원하기 = 동아리 가입** — 외부 모집 폼으로 나간다. 아직 부원이 아닌 사람용.
                 * **로그인 = 이 사이트** — 이미 부원인 사람용.
                 * 강조(흰색)를 지원하기에 준 것은 랜딩을 처음 보는 사람이 대부분이기 때문이다.
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
            )}
          </div>
        )}
      </div>
    </header>
  )
}
