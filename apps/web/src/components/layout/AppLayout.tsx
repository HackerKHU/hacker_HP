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
 * **푸터를 화면 바닥에 붙인다.** 세로 flex에서 `<main>`이 `flex-1`로 남는 높이를 다 먹으니,
 * 내용이 짧아도 푸터가 맨 아래에 있고 길면 자연스럽게 아래로 밀린다. 전에는 푸터에
 * 위쪽 여백을 줘서 가렸는데 그건 붙인 것이 아니라 **덜 어색해 보이게 한 것**이라
 * 함께 걷었다 — 역할이 겹치는 것을 남기면 다음 사람이 어느 쪽이 진짜인지 모른다.
 * 내용과 푸터 사이 간격은 `<main>`의 `py-8`이 그대로 준다.
 *
 * **`<main>`을 세로 flex로 둔다.** 화면이 세로 가운데 정렬을 원하면 자기 컨테이너에
 * `my-auto`를 붙이면 된다 (`PendingPage`가 그렇게 한다). 여기서 `items-center`나
 * `justify-center`를 주지 않는 이유는 로그인 화면 주석에 적은 것과 같다 — 자식이 화면보다
 * 커지는 순간 위쪽이 밖으로 밀려 **스크롤로 닿을 수 없게** 된다. `margin: auto`는 남는
 * 공간이 있을 때만 나눠 갖는다.
 *
 * **가운데로 보내는 것은 화면의 판단이다.** 목록·표·읽는 글은 위에서 시작해야 한다 —
 * 가운데로 두면 행 수나 글 길이에 따라 시작 위치가 위아래로 움직인다.
 *
 * 데스크톱 전용이라 반응형은 범위 밖이다 (spec §1-2).
 */
export function AppLayout() {
  return (
    <div className="flex min-h-screen flex-col bg-background text-foreground">
      <AppHeader />
      <main className="mx-auto flex w-full max-w-[1152px] flex-1 flex-col px-6 py-8">
        <Outlet />
      </main>

      {/*
        헤더 로고는 부원 홈(/notices)으로 간다. 공개 페이지로 나가는 길은 여기 남긴다 —
        로고를 눌렀는데 소개 페이지가 나오면 부원 입장에서 어색하다.
      */}
      <footer className="border-t border-border">
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
