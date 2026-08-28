import { UserRound } from 'lucide-react'
import { Link } from 'react-router-dom'
import { useLogout } from '@/auth/useLogout'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'

/**
 * 팝오버 폭. **320px에서도 넘치지 않아야 한다** (#249).
 *
 * `12rem`(192px)을 원하되 화면이 그보다 좁으면 좌우 여백 32px을 뺀 만큼으로 줄인다.
 * 항목이 둘뿐이라 넓힐 이유가 없고, 고정 폭만 주면 좁은 화면에서 오른쪽 끝이 잘린다.
 */
const CONTENT_WIDTH = 'w-[min(12rem,calc(100vw-2rem))]'

/**
 * 계정 메뉴 (#178). 헤더 오른쪽 끝의 사람 아이콘 하나가 여는 드롭다운이다.
 *
 * **글자 버튼 둘을 아이콘 하나로 접었다.** `내 정보` 링크와 `로그아웃` 버튼이 나란히 있었는데,
 * 주요 메뉴가 넷(관리자는 다섯)이라 헤더 한 줄이 이미 빡빡했다 — 좁은 화면에서 먼저
 * 무너지는 자리가 여기다 (#249).
 *
 * **메뉴만 담는다.** 내 정보는 마이페이지(`/me`)가 그리고 여기는 그리로 가는 길만 준다 —
 * 같은 정보를 두 곳에 두면 한쪽만 고쳐진다.
 *
 * **`PENDING`에게도 열린다.** 로그아웃이 이 안에 있기 때문이다 — 권한 매트릭스에서
 * 로그아웃은 `PENDING`도 `O`이고, 마이페이지만 `X`다 (spec
 * [§3-1-3](../../../../spec/3-1-DESIGN-ARCHITECTURE.md)). 통째로 감추면 승인을 기다리는
 * 사람이 로그아웃할 자리를 잃는다. 마이페이지 항목은 `ACTIVE`에게만 보인다 — 띄워봤자
 * 눌러도 가드가 되돌린다.
 */
export function AccountMenu({ showMyPage }: { showMyPage: boolean }) {
  // 로그아웃 로직은 랜딩 헤더와 함께 쓴다. 복사하지 않는다.
  const { logout, failed } = useLogout('/login')

  return (
    <>
      {/* 토스트 같은 알림 수단이 아직 없다. 사용자가 실패를 알고 다시 누를 수 있으면 충분하다. */}
      {failed && (
        <p role="alert" className="text-sm text-muted-foreground">
          로그아웃하지 못했습니다. 다시 시도해 주세요.
        </p>
      )}

      {/*
       * **`modal={false}`다.** 기본값(모달)은 열려 있는 동안 페이지 나머지에 `aria-hidden`을
       * 걸고 스크롤을 잠근다 — 항목 둘짜리 메뉴에는 과하고, 스크린리더에는 그동안 화면이
       * 통째로 사라진다.
       */}
      <DropdownMenu modal={false}>
        {/*
          **아이콘 하나라 이름을 글자로 주어야 한다.** 스크린리더에는 `<svg>`가 읽히지 않고,
          `aria-label`이 없으면 "버튼"으로만 읽혀 무엇이 열리는지 알 수 없다.

          키보드 조작은 Radix가 맡는다 — `<button>`이라 Tab으로 닿고, Enter·Space·↓로 열리며
          열린 뒤 화살표로 항목을 옮기고 Escape로 닫는다. 직접 만들지 않는 이유가 이것이다.
        */}
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="icon" aria-label="계정 메뉴">
            <UserRound />
          </Button>
        </DropdownMenuTrigger>

        <DropdownMenuContent align="end" className={CONTENT_WIDTH}>
          {showMyPage && (
            <DropdownMenuItem asChild>
              <Link to="/me">마이페이지</Link>
            </DropdownMenuItem>
          )}
          <DropdownMenuItem onSelect={logout}>로그아웃</DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </>
  )
}
