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
async function classesOf(
  header: React.ReactNode,
): Promise<{ container: string; logo: string; src: string }> {
  const { unmount } = render(
    <MemoryRouter>
      <SessionProvider>{header}</SessionProvider>
    </MemoryRouter>,
  )
  const logo = await screen.findByAltText(CLUB.name)
  // 로고의 가로 위치를 정하는 것은 링크를 감싼 줄(헤더 안쪽 컨테이너)이다.
  const container = logo.closest('a')?.parentElement
  if (!container) throw new Error('로고를 감싼 컨테이너를 찾지 못했다')

  const found = {
    container: container.className,
    logo: logo.className,
    src: logo.getAttribute('src') ?? '',
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
