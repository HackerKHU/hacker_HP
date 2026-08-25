import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import type { User } from '@/api/types'
import { SessionProvider } from '@/auth/session'
import { CLUB } from '@/features/landing/content'
import { PublicHeader } from '@/features/landing/PublicHeader'
import { AppHeader } from './AppHeader'

/**
 * **두 헤더의 로고가 같은 자리에 있는가** (#247).
 *
 * #222가 높이와 로고 크기를 맞췄지만 가로 패딩(`px-4 md:px-6` vs `px-6`)이 남아,
 * 768px 미만에서 랜딩 → 앱으로 넘어갈 때 로고가 8px 미끄러졌다. **눈으로는 잘 안 보이고
 * 스냅샷으로도 안 잡히는 종류**라 클래스를 직접 견준다.
 *
 * 클래스 문자열을 단언하는 것은 보통 무른 테스트지만, 여기서 지키려는 것이 정확히
 * "두 파일의 그 값이 같은가"다 — 렌더 결과를 비교해서는 이 어긋남을 잡을 수 없다.
 */

const BASE: User = {
  id: 1,
  email: 'member@khu.ac.kr',
  studentNo: '2021123456',
  name: '홍길동',
  department: '컴퓨터공학과',
  role: 'USER',
  status: 'ACTIVE',
  createdAt: '2026-03-02T09:00:00Z',
  appliedAt: '2026-03-02T09:10:00Z',
  approvedAt: '2026-03-03T09:00:00Z',
}

vi.mock('@/api/auth', () => ({
  getMe: () => Promise.resolve(BASE),
  logout: () => Promise.resolve(),
}))

/**
 * 헤더를 그려 로고와 그 컨테이너의 클래스를 뽑는다.
 *
 * **뽑고 나면 DOM을 버린다.** 두 헤더를 한 화면에 함께 그리면 `findByAltText`가 둘을
 * 찾아 실패한다 — 둘 다 같은 `alt`를 쓰는 것이 이 파일이 지키려는 것이기도 하다.
 */
async function classesOf(header: React.ReactNode): Promise<{
  container: string
  logo: string
  src: string
  navItem: string | null
}> {
  const { unmount } = render(
    <MemoryRouter>
      <SessionProvider>{header}</SessionProvider>
    </MemoryRouter>,
  )
  const logo = await screen.findByAltText(CLUB.name)
  // 로고의 가로 위치를 정하는 것은 링크를 감싼 줄(헤더 안쪽 컨테이너)이다.
  const container = logo.closest('a')?.parentElement
  if (!container) throw new Error('로고를 감싼 컨테이너를 찾지 못했다')

  /*
   * 메뉴 한 칸의 모양. **색은 뺀다** — 앱은 현재 위치를 `text-foreground`로 드러내고
   * 랜딩에는 그런 구분이 없어서, 색까지 견주면 의도된 차이에 걸린다. 여기서 지키려는 것은
   * **글씨 크기와 여백**이다.
   */
  const link = document.querySelector('header nav a')
  const navItem =
    link === null
      ? null
      : link.className
          .split(' ')
          .filter((name) => !name.startsWith('text-foreground'))
          .filter((name) => !name.startsWith('text-muted-foreground'))
          .sort()
          .join(' ')

  const found = {
    container: container.className,
    logo: logo.className,
    src: logo.getAttribute('src') ?? '',
    navItem,
  }
  unmount()
  return found
}

describe('두 헤더의 로고 정렬', () => {
  /*
   * 높이·폭·패딩·간격이 전부 같아야 랜딩에서 로그인해 넘어올 때 로고가 움직이지 않는다.
   * `px-4` 하나가 남아 있던 것이 #247이다.
   */
  it('컨테이너의 높이·폭·패딩·간격이 같다', async () => {
    const landing = await classesOf(<PublicHeader />)
    const app = await classesOf(<AppHeader />)

    expect(app.container).toBe(landing.container)
  })

  /* #222가 맞춘 값. 되돌아가면 여기서 잡힌다. */
  it('로고 크기가 같다', async () => {
    const landing = await classesOf(<PublicHeader />)
    const app = await classesOf(<AppHeader />)

    expect(app.logo).toBe(landing.logo)
  })

  /*
   * **메뉴 글씨 크기와 여백이 같다** (#261 검수).
   *
   * 랜딩이 `text-base py-2`(16px), 앱이 `text-sm py-1.5`(14px)로 갈려 있어 화면을 오갈 때
   * 메뉴가 커졌다 작아졌다 했다. 두 파일이 각자 클래스를 들고 있던 것이 원인이라
   * **한 상수에서 가져오게** 바꿨고, 여기서 그것이 유지되는지 본다.
   *
   * 색은 견주지 않는다 — 앱만 현재 위치를 색으로 드러내는 것이 의도된 차이다.
   */
  it('메뉴 한 칸의 크기·여백이 같다', async () => {
    const landing = await classesOf(<PublicHeader />)
    const app = await classesOf(<AppHeader />)

    // 둘 다 메뉴가 그려진 상태여야 비교가 뜻을 갖는다.
    expect(landing.navItem).not.toBeNull()
    expect(app.navItem).not.toBeNull()
    expect(app.navItem).toBe(landing.navItem)
  })

  /*
   * **스크롤바 자리를 늘 비워 둔다** (#258).
   *
   * 클래스를 다 맞춰도 로고가 7.5px 어긋났다. 원인은 헤더가 아니라 **페이지 길이**였다 —
   * 랜딩처럼 긴 화면은 스크롤바(15px)가 생겨 가용 폭이 줄고, 가운데 정렬된 컨테이너가
   * 그 절반만큼 왼쪽으로 밀린다. 짧은 화면은 스크롤바가 없어 제자리다.
   *
   * **jsdom은 레이아웃도 스크롤바도 계산하지 않는다.** 그래서 이 검사가 확인할 수 있는
   * 것은 "그 규칙이 스타일시트에 선언되어 있는가"뿐이고, 정렬이 실제로 맞는지는 브라우저
   * 실측이 답한다. 그래도 남기는 이유는 **이 한 줄이 CSS 정리 중에 조용히 사라지기 쉽고,
   * 사라지면 증상이 다시 "로고가 조금 어긋난다"로만 보이기 때문이다.**
   */
  it('스타일시트가 스크롤바 자리를 고정한다', () => {
    /*
     * jsdom 환경에서는 `import.meta.url`이 `http:`라 파일 경로로 쓸 수 없다.
     * vitest는 `apps/web`을 작업 디렉터리로 돌고 CI도 같다(`working-directory: apps/web`).
     */
    const css = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf-8')

    expect(css).toContain('scrollbar-gutter: stable')
  })

  /*
   * **잉크는 달라야 한다.** 랜딩은 `.dark`라 흰 잉크, 앱은 라이트 배경이라 검정이다 —
   * 위 두 검사를 "그냥 같은 컴포넌트를 쓰자"로 통과시키면 한쪽이 배경에 묻힌다.
   */
  it('잉크는 배경에 맞게 다르다', async () => {
    const landing = await classesOf(<PublicHeader />)
    const app = await classesOf(<AppHeader />)

    expect(landing.src).toBe('/brand/lockup-horizontal-white-512.png')
    expect(app.src).toBe('/brand/lockup-horizontal-black-512.png')
  })
})
