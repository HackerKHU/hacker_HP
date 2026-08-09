import { Link } from 'react-router-dom'
import { homePath, useSession } from '@/auth/session'
import { Button } from '@/components/ui/button'
import { CLUB, SECTIONS } from './content'

/**
 * 랜딩 전용 헤더. `AppHeader`와 별개 컴포넌트다 — 배경이 검정이고 메뉴가 라우트가 아니라
 * **섹션 앵커**라서 성격이 다르다. 하나로 합치면 두 성격이 조건문으로 뒤엉킨다.
 */
export function PublicHeader() {
  const session = useSession()

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
         * 곧바로 "공지사항 보기"로 바뀌면 깜빡인다.
         *
         * 로그인한 사람이 랜딩에 왔을 때 들어갈 문이 필요하다. 목적지는 `homePath`를
         * 그대로 쓴다 — 상태별 홈 규칙이 두 벌이 되면 어긋난다.
         */}
        {session.state.kind !== 'loading' && (
          <div className="ml-auto flex items-center gap-2">
            {session.state.kind === 'guest' ||
            session.state.kind === 'suspended' ? (
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
            ) : (
              // 이미 부원인 사람에게 지원 버튼은 의미가 없다.
              <Button asChild variant="outline" size="sm">
                <Link to={homePath(session)}>내 페이지</Link>
              </Button>
            )}
          </div>
        )}
      </div>
    </header>
  )
}
