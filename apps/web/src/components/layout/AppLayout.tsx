import { Outlet } from 'react-router-dom'
import { AppHeader } from './AppHeader'

/**
 * 로그인 이후 화면이 공유하는 레이아웃. 상단 헤더 + 본문이고 사이드바는 없다.
 *
 * /login에는 붙이지 않는다(헤더의 로그아웃이 의미가 없다).
 * /pending에는 붙인다 — 로그아웃이 필요하고, 그 화면이 PENDING에게 유일하게 열린 화면이다.
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
    </div>
  )
}
