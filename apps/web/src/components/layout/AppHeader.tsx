import { Link, NavLink } from 'react-router-dom'
import type { Role } from '@/api/types'
import { useSession } from '@/auth/session'
import { useLogout } from '@/auth/useLogout'
import { Button } from '@/components/ui/button'
import { CLUB } from '@/features/landing/content'
import { lookup } from '@/lib/lookup'
import { cn } from '@/lib/utils'

/**
 * 헤더는 하나다. 관리자용 헤더를 따로 만들지 않고 role로 메뉴만 가른다 —
 * 관리자 라우트가 2개뿐이라 헤더를 복제할 이유가 없다.
 *
 * **메뉴 노출은 권한 통제가 아니다** (spec §3-1-7). 감춘 메뉴를 주소창으로 직접 열어도
 * #36의 라우트 가드가 막고, 서버가 다시 검증한다. 여기가 담당하는 것은 노출뿐이다.
 */
const MENUS = {
  USER: [{ to: '/notices', label: '공지사항' }],
  ADMIN: [
    { to: '/notices', label: '공지사항' },
    { to: '/admin/members', label: '회원 관리' },
  ],
} satisfies Record<Role, { to: string; label: string }[]>

/*
 * `/admin/notices` 계열 라우트는 App.tsx에 살아 있고 가드도 그대로다.
 * 진입 위치가 아직 정해지지 않아 **메뉴에서만 뺐다** — 죽은 라우트가 아니니 지우지 말 것.
 */

export function AppHeader() {
  const { state } = useSession()
  // 로그아웃 로직은 랜딩 헤더와 함께 쓴다. 복사하지 않는다.
  const { logout, failed } = useLogout('/login')

  // PENDING은 공지도 볼 수 없으므로 메뉴가 없다. 띄워봤자 눌러도 가드가 되돌린다.
  // 서버 응답도 신뢰 경계다. 계약에 없는 role이 오면 프로토타입 키에 걸려 죽지 않고
  // 메뉴가 비는 쪽으로 떨어진다.
  const menus =
    state.kind === 'active' ? (lookup(MENUS, state.user.role) ?? []) : []

  return (
    <header className="border-b border-border bg-background">
      <div className="mx-auto flex h-14 w-full max-w-[1152px] items-center gap-8 px-6">
        {/*
         * 부원이 로고를 눌렀는데 소개 페이지가 나오면 어색하다. 랜딩은 푸터 링크로 간다.
         *
         * 랜딩 헤더와 **같은 가로 락업이되 잉크만 반대**다 — 여기는 라이트 배경이라
         * 검정을 쓴다. 배경이 채워진 `-on-white`가 아니라 투명 배경이어야 한다.
         *
         * 높이는 랜딩보다 작다. 이 헤더가 `h-14`(56px)로 랜딩(`h-20`, 80px)보다 낮아,
         * 같은 32px를 쓰면 로고가 헤더를 꽉 채워 답답해진다. 비율을 맞춰 24px로 둔다.
         */}
        <Link to="/notices" className="shrink-0">
          <img
            src="/brand/lockup-horizontal-black-512.png"
            alt={CLUB.name}
            width={512}
            height={104}
            className="h-6 w-auto"
          />
        </Link>

        <nav className="flex items-center gap-1" aria-label="주요 메뉴">
          {menus.map((menu) => (
            <NavLink
              key={menu.to}
              to={menu.to}
              end
              className={({ isActive }) =>
                cn(
                  'rounded-md px-3 py-1.5 text-sm transition-colors hover:bg-accent hover:text-accent-foreground',
                  isActive ? 'text-foreground' : 'text-muted-foreground',
                )
              }
            >
              {menu.label}
            </NavLink>
          ))}
        </nav>

        <div className="ml-auto flex items-center gap-3">
          {/* 토스트 같은 알림 수단이 아직 없다. 사용자가 실패를 알고 다시 누를 수 있으면 충분하다. */}
          {failed && (
            <p role="alert" className="text-sm text-muted-foreground">
              로그아웃하지 못했습니다. 다시 시도해 주세요.
            </p>
          )}
          <Button variant="ghost" size="sm" onClick={logout}>
            로그아웃
          </Button>
        </div>
      </div>
    </header>
  )
}
