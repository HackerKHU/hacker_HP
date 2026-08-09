import { useState } from 'react'
import { Link, NavLink, useNavigate } from 'react-router-dom'
import { logout } from '@/api/auth'
import { ApiError } from '@/api/client'
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
    { to: '/admin/members', label: '회원 관리' },
  ],
} satisfies Record<Role, { to: string; label: string }[]>

/*
 * `/admin/notices` 계열 라우트는 App.tsx에 살아 있고 가드도 그대로다.
 * 진입 위치가 아직 정해지지 않아 **메뉴에서만 뺐다** — 죽은 라우트가 아니니 지우지 말 것.
 */

/**
 * 서버가 세션을 지웠다고 확인해 준 경우만 로그아웃 성공이다.
 *
 * 401 `UNAUTHENTICATED`도 성공으로 친다 — 지울 세션이 이미 없다는 뜻이다. 이걸 실패로 다루면
 * 만료된 세션에서는 로그아웃이 영영 안 된다. 403이 여러 코드를 공유하듯 여기서도
 * status가 아니라 `ApiError.code`로 판별한다.
 */
function isLoggedOut(error: unknown): boolean {
  return error instanceof ApiError && error.code === 'UNAUTHENTICATED'
}

export function AppHeader() {
  const { state, setUser } = useSession()
  const navigate = useNavigate()
  const [failed, setFailed] = useState(false)

  // PENDING은 공지도 볼 수 없으므로 메뉴가 없다. 띄워봤자 눌러도 가드가 되돌린다.
  const menus = state.kind === 'active' ? MENUS[state.user.role] : []

  async function handleLogout() {
    try {
      await logout()
    } catch (error: unknown) {
      if (!isLoggedOut(error)) {
        /*
         * 실패했으면 세션을 비우지도, 이동하지도 않는다.
         *
         * 서버 세션 쿠키는 HttpOnly라 브라우저에서 지울 수 없다. 화면만 로그아웃된 척하면
         * 사용자는 안전하다고 믿고 자리를 뜨는데 서버 세션은 살아 있다. 공용 PC에서
         * 다음 사람이 사이트를 열면 getMe()가 성공해 남의 계정으로 들어가진다.
         * 로그인 화면을 보여주는 것이 오히려 위험한 경우다.
         */
        setFailed(true)
        return
      }
    }
    setFailed(false)
    setUser(null)
    navigate('/login', { replace: true })
  }

  return (
    <header className="border-b border-border bg-background">
      <div className="mx-auto flex h-14 w-full max-w-[1152px] items-center gap-8 px-6">
        <Link to="/" className="font-semibold tracking-tight">
          HACKER
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
          <Button variant="ghost" size="sm" onClick={handleLogout}>
            로그아웃
          </Button>
        </div>
      </div>
    </header>
  )
}
