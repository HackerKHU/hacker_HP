import { Link, Outlet } from 'react-router-dom'
import { AppHeader } from './AppHeader'

/**
 * 로그인 이후 화면이 공유하는 레이아웃. 상단 헤더 + 본문이고 사이드바는 없다.
 *
 * /login에는 붙이지 않는다(헤더의 로그아웃이 의미가 없다).
 * /pending에는 붙인다 — 로그아웃이 필요하고, 그 화면이 PENDING에게 유일하게 열린 화면이다.
 *
 * **`<main>`이 상하 여백(`py-8`)을 준다.** 이 안에 들어오는 화면은 자기 상하 여백을 갖지
 * 않는다 — 주면 겹친다. 화면별 폭 규칙(읽는 글 / 짧은 폼 / 표)은 `apps/web/README.md`의
 * "화면 폭과 여백"에 있다. 레이아웃 밖 화면(랜딩·개인정보처리방침·로그인)까지 함께
 * 다루므로 이 파일이 아니라 그쪽에 적었다.
 *
 * 데스크톱 전용이라 반응형은 범위 밖이다 (spec §1-2).
 */
export function AppLayout() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <AppHeader />
      <main className="mx-auto w-full max-w-[1152px] px-6 py-8">
        <Outlet />
      </main>

      {/*
        헤더 로고는 부원 홈(/notices)으로 간다. 공개 페이지로 나가는 길은 여기 남긴다 —
        로고를 눌렀는데 소개 페이지가 나오면 부원 입장에서 어색하다.
      */}
      <footer className="mt-16 border-t border-border">
        <div className="mx-auto flex w-full max-w-[1152px] gap-4 px-6 py-6 text-sm text-muted-foreground">
          <Link to="/" className="transition-colors hover:text-foreground">
            동아리 소개
          </Link>
          <Link
            to="/privacy"
            className="transition-colors hover:text-foreground"
          >
            개인정보처리방침
          </Link>
        </div>
      </footer>
    </div>
  )
}
