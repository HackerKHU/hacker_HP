import { fireEvent, render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/App'
import { ApiError } from '@/api/client'
import type { User } from '@/api/types'
import { SessionProvider } from '@/auth/session'
import { MemoryRouter } from '@/test/TestRouter'

/**
 * 헤더의 계정 메뉴 (#178).
 *
 * **앱을 통째로 띄운다.** 이 메뉴는 헤더 안에 있고 헤더는 `AppLayout` 안에 있어, 컴포넌트만
 * 렌더하면 "어떤 세션에서 무엇이 보이는가"를 볼 수 없다.
 */

const auth = vi.hoisted(() => ({
  me: (): Promise<User> =>
    Promise.reject(new Error('테스트가 지정하지 않았다')),
  logoutError: null as unknown,
}))

vi.mock('@/api/auth', () => ({
  getMe: () => auth.me(),
  logout: () =>
    auth.logoutError ? Promise.reject(auth.logoutError) : Promise.resolve(),
}))

// 이 파일은 헤더만 본다. 공지 화면이 실제 요청을 내보내면 로딩 실패 alert가 섞인다.
vi.mock('@/api/notices', () => ({
  list: () =>
    Promise.resolve({
      content: [],
      page: { size: 10, number: 0, totalElements: 0, totalPages: 0 },
    }),
  get: () => Promise.reject(new Error('이 파일에서는 쓰지 않는다')),
  togglePin: () => Promise.reject(new Error('이 파일에서는 쓰지 않는다')),
  create: () => Promise.reject(new Error('이 파일에서는 쓰지 않는다')),
  update: () => Promise.reject(new Error('이 파일에서는 쓰지 않는다')),
  remove: () => Promise.reject(new Error('이 파일에서는 쓰지 않는다')),
}))

const MEMBER: User = {
  id: 7,
  email: 'member@khu.ac.kr',
  studentNo: '2021123456',
  name: '홍길동',
  department: '컴퓨터공학과',
  role: 'USER',
  status: 'ACTIVE',
  createdAt: '2026-03-02T09:00:00Z',
  appliedAt: '2026-03-05T09:10:00Z',
  approvedAt: '2026-03-07T09:00:00Z',
}

function renderAt(path = '/notices') {
  render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  )
}

/**
 * 메뉴를 연다. Radix는 `pointerdown`으로 열린다 — `click`만 쏘면 안 열린다. jsdom에
 * `PointerEvent`가 없어 `MouseEvent`로 대신 만든다 (`MemberListPage.test.tsx`와 같은 방식).
 */
async function openMenu() {
  const trigger = await screen.findByRole('button', { name: '계정 메뉴' })
  fireEvent.pointerDown(
    trigger,
    new MouseEvent('pointerdown', { bubbles: true, button: 0 }),
  )
  return await screen.findByRole('menu')
}

beforeEach(() => {
  auth.me = () => Promise.resolve(MEMBER)
  auth.logoutError = null
})

describe('계정 메뉴', () => {
  /*
   * **아이콘 하나라 이름을 글자로 주어야 한다** — 스크린리더에는 `<svg>`가 읽히지 않는다.
   * `aria-label`이 없으면 "버튼"으로만 읽혀 무엇이 열리는지 알 수 없다.
   *
   * **`<button>`이라 Tab으로 닿고 Enter로 열린다.** 키보드 조작은 Radix가 맡는다 —
   * `<div onClick>`으로 만들면 그 전부가 사라진다.
   */
  it('이름이 붙은 아이콘 버튼 하나로 들어간다', async () => {
    renderAt()

    const trigger = await screen.findByRole('button', { name: '계정 메뉴' })
    expect(trigger.tagName).toBe('BUTTON')
    expect(trigger).toHaveAttribute('aria-haspopup', 'menu')

    // 닫혀 있는 동안에는 항목이 문서에 없다.
    expect(screen.queryByRole('menuitem')).not.toBeInTheDocument()
  })

  it('Enter로 열리고 Escape로 닫힌다', async () => {
    renderAt()
    const trigger = await screen.findByRole('button', { name: '계정 메뉴' })

    fireEvent.keyDown(trigger, { key: 'Enter' })
    const menu = await screen.findByRole('menu')

    fireEvent.keyDown(menu, { key: 'Escape' })
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  /*
   * **항목은 둘뿐이다.** 내 정보는 마이페이지가 그리고 여기는 그리로 가는 길만 준다 —
   * 같은 정보를 두 곳에 두면 한쪽만 고쳐진다.
   */
  it('마이페이지와 로그아웃 두 항목이다', async () => {
    renderAt()
    const menu = await openMenu()

    expect(
      within(menu)
        .getAllByRole('menuitem')
        .map((item) => item.textContent),
    ).toEqual(['마이페이지', '로그아웃'])
    expect(
      within(menu).getByRole('menuitem', { name: '마이페이지' }),
    ).toHaveAttribute('href', '/me')
  })

  /*
   * 마이페이지는 `PENDING`에게 닫혀 있다 (spec §3-1-3 매트릭스). **메뉴 자체는 열린다** —
   * 로그아웃이 그 안에 있고 매트릭스에서 로그아웃은 `PENDING`도 `O`다. 통째로 감추면
   * 승인을 기다리는 사람이 로그아웃할 자리를 잃는다.
   */
  it('PENDING에게는 로그아웃만 보인다', async () => {
    auth.me = () =>
      Promise.resolve({ ...MEMBER, status: 'PENDING', approvedAt: null })
    renderAt('/pending')

    await screen.findByRole('heading', { name: '승인 대기 중' })
    const menu = await openMenu()

    expect(
      within(menu)
        .getAllByRole('menuitem')
        .map((item) => item.textContent),
    ).toEqual(['로그아웃'])
  })

  it('로그아웃 실패는 레이아웃 밖 fixed live alert 하나로 알린다', async () => {
    auth.logoutError = new Error('network')
    renderAt()
    const menu = await openMenu()

    fireEvent.click(within(menu).getByRole('menuitem', { name: '로그아웃' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('로그아웃하지 못했습니다')
    expect(alert.closest('[data-live-alert-viewport="true"]')).not.toBeNull()
    expect(screen.getByRole('banner')).not.toContainElement(alert)
    expect(screen.getAllByRole('alert')).toHaveLength(1)

    fireEvent.click(screen.getByRole('button', { name: '알림 닫기' }))
    expect(screen.queryByRole('alert')).toBeNull()
  })

  /* 비로그인에게는 헤더 자체가 없다 — 로그인 화면은 `AppLayout` 밖이다. */
  it('비로그인에게는 계정 메뉴가 없다', async () => {
    auth.me = () =>
      Promise.reject(
        new ApiError('UNAUTHENTICATED', 401, '로그인이 필요합니다.'),
      )
    renderAt()

    await screen.findByRole('heading', { name: '로그인' })
    expect(
      screen.queryByRole('button', { name: '계정 메뉴' }),
    ).not.toBeInTheDocument()
  })
})
