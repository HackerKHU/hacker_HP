import { Link, NavLink, useNavigate } from 'react-router-dom'
import { logout } from '@/api/auth'
import type { Role } from '@/api/types'
import { useSession } from '@/auth/session'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

/**
 * 헤더는 하나다. 관리자용 헤더를 따로 만들지 않고 role로 메뉴만 가른다 —
 * 관리자 라우트가 2개뿐이라 헤더를 복제할 이유가 없다.
 *
 * **메뉴 노출은 권한 통제가 아니다** (spec §3-1-7). 감춘 메뉴를 주소창으로 직접 열어도
 * #36의 라우트 가드가 막고, 서버가 다시 검증한다. 여기가 담당하는 것은 노출뿐이다.
 */
const MENUS = {
  USER: [{ to: '/notices', label: '공지' }],
  ADMIN: [
    { to: '/notices', label: '공지' },
    { to: '/admin/notices', label: '공지 관리' },
    { to: '/admin/members', label: '회원 관리' },
  ],
} satisfies Record<Role, { to: string; label: string }[]>

export function AppHeader() {
  const { state, setUser } = useSession()
  const navigate = useNavigate()

  // PENDING은 공지도 볼 수 없으므로 메뉴가 없다. 띄워봤자 눌러도 가드가 되돌린다.
  const menus = state.kind === 'active' ? MENUS[state.user.role] : []

  async function handleLogout() {
    try {
      await logout()
    } finally {
      // 서버 호출이 실패해도 로컬 세션은 비운다. 로그아웃을 눌렀는데 남아 있으면 안 된다.
      setUser(null)
      navigate('/login', { replace: true })
    }
  }

  return (
    <header className="border-b border-border bg-background">
      <div className="mx-auto flex h-14 w-full max-w-[1152px] items-center gap-8 px-6">
        <Link to="/" className="font-semibold tracking-tight">
          HackerKHU
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

        <Button
          variant="ghost"
          size="sm"
          className="ml-auto"
          onClick={handleLogout}
        >
          로그아웃
        </Button>
      </div>
    </header>
  )
}
