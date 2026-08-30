import { fireEvent, render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { User } from '@/api/types'
import { SessionProvider } from '@/auth/session'
import { CLUB } from '@/features/landing/content'
import { MemoryRouter, useLocation, useNavigate } from '@/test/TestRouter'
import { AppHeader } from './AppHeader'

const ACTIVE: User = {
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

const auth = vi.hoisted(() => ({
  me: (): Promise<User> =>
    Promise.reject(new Error('테스트가 지정하지 않았다')),
}))

vi.mock('@/api/auth', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/auth')>()
  return {
    ...actual,
    getMe: () => auth.me(),
    logout: () => Promise.resolve(),
  }
})

function renderHeader(initialEntry = '/notices') {
  render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <SessionProvider>
        <AppHeader />
      </SessionProvider>
    </MemoryRouter>,
  )
}

function NavigateOutsideHeader() {
  const { pathname } = useLocation()
  const navigate = useNavigate()

  return (
    <>
      <output aria-label="현재 경로">{pathname}</output>
      <button type="button" onClick={() => navigate('/notes')}>
        다른 화면으로 이동
      </button>
    </>
  )
}

function renderHeaderWithNavigation() {
  render(
    <MemoryRouter initialEntries={['/notices']}>
      <SessionProvider>
        <AppHeader />
        <NavigateOutsideHeader />
      </SessionProvider>
    </MemoryRouter>,
  )
}

const MEMBER_LINKS: [string, string][] = [
  ['공지사항', '/notices'],
  ['자료게시판', '/notes'],
  ['자유게시판', '/posts'],
  ['갤러리', '/photos'],
]

beforeEach(() => {
  auth.me = () => Promise.resolve(ACTIVE)
})

describe('AppHeader', () => {
  it('fixed 결과 알림보다 위에 쌓이되 sticky로 바뀌지 않는다', async () => {
    renderHeader()

    const header = await screen.findByRole('banner')
    expect(header).toHaveClass('relative', 'z-40')
    expect(header).not.toHaveClass('sticky')
  })

  it('데스크톱 lockup과 모바일 심볼이 같은 32px 크기를 쓴다', async () => {
    renderHeader()

    const logo = await screen.findByAltText(CLUB.name)
    expect(logo).toHaveAttribute(
      'src',
      '/brand/lockup-horizontal-black-512.png',
    )
    expect(logo.className).toContain('h-8')
    expect(logo.className).toContain('w-[27px]')
    expect(logo.className).toContain('lg:w-auto')

    const source = logo.closest('picture')?.querySelector('source')
    expect(source).toHaveAttribute('media', '(max-width: 1023px)')
    expect(source).toHaveAttribute('srcset', '/brand/mark-black-256.png')
  })

  it('로고에 대체 텍스트가 있고 누르면 랜딩으로 간다', async () => {
    renderHeader()

    const logo = await screen.findByAltText(CLUB.name)
    const link = logo.closest('a')
    expect(link).toHaveAttribute('href', '/')
    expect(link?.className).toContain('size-11')
    expect(link?.className).toContain('lg:h-auto')
    expect(link?.className).toContain('lg:w-auto')
  })

  it('모바일의 계정과 메뉴 버튼이 모두 44px 터치 영역을 쓴다', async () => {
    renderHeader()

    const account = await screen.findByRole('button', { name: '계정 메뉴' })
    const navigation = screen.getByRole('button', { name: '메뉴 열기' })
    expect(account.className).toContain('size-11')
    expect(account.className).toContain('lg:size-9')
    expect(navigation.className).toContain('size-11')
    expect(navigation.className).toContain('lg:hidden')
  })

  it('1024px부터만 데스크톱 nav로 전환한다', async () => {
    renderHeader()

    const navigation = await screen.findByRole('navigation', {
      name: '주요 메뉴',
    })
    expect(navigation.parentElement?.className).toContain('hidden')
    expect(navigation.parentElement?.className).toContain('lg:flex')
    expect(navigation.parentElement?.className).not.toContain('md:flex')

    fireEvent.click(screen.getByRole('button', { name: '메뉴 열기' }))
    const mobileMenu = document.getElementById('app-mobile-menu')
    expect(mobileMenu?.className).toContain('lg:hidden')
    expect(mobileMenu?.className).not.toContain('md:hidden')
  })

  it.each([
    ['ACTIVE USER', ACTIVE, MEMBER_LINKS],
    ['INACTIVE USER', { ...ACTIVE, status: 'INACTIVE' as const }, MEMBER_LINKS],
    [
      'ACTIVE ADMIN',
      { ...ACTIVE, role: 'ADMIN' as const },
      [...MEMBER_LINKS, ['회원 관리', '/admin/members']] as [string, string][],
    ],
  ])(
    '%s의 모바일 메뉴가 권한에 맞는 항목만 보인다',
    async (_label, user, links) => {
      auth.me = () => Promise.resolve(user)
      renderHeader()

      fireEvent.click(await screen.findByRole('button', { name: '메뉴 열기' }))
      const mobileMenu = document.getElementById(
        'app-mobile-menu',
      ) as HTMLElement
      const navigation = within(mobileMenu).getByRole('navigation', {
        name: '주요 메뉴',
      })

      expect(
        within(navigation)
          .getAllByRole('link')
          .map((link) => [link.textContent, link.getAttribute('href')]),
      ).toEqual(links)
      for (const link of within(navigation).getAllByRole('link')) {
        expect(link.className).toContain('min-h-11')
      }
    },
  )

  it.each([
    ['loading', () => new Promise<User>(() => undefined)],
    [
      'PENDING',
      () =>
        Promise.resolve({
          ...ACTIVE,
          status: 'PENDING' as const,
          approvedAt: null,
        }),
    ],
    ['guest', () => Promise.reject(new Error('비로그인'))],
  ])('%s에는 빈 모바일 메뉴 버튼을 그리지 않는다', async (_label, getMe) => {
    auth.me = getMe
    renderHeader()

    await screen.findByAltText(CLUB.name)
    expect(screen.queryByRole('button', { name: '메뉴 열기' })).toBeNull()
    expect(document.getElementById('app-mobile-menu')).toBeNull()
  })

  it('관리자 모바일 메뉴의 회원 관리 앞에 가로 구분선이 있다', async () => {
    auth.me = () => Promise.resolve({ ...ACTIVE, role: 'ADMIN' })
    renderHeader()

    fireEvent.click(await screen.findByRole('button', { name: '메뉴 열기' }))
    const mobileMenu = document.getElementById('app-mobile-menu') as HTMLElement
    const admin = within(mobileMenu).getByRole('link', { name: '회원 관리' })
    const divider = admin.previousElementSibling

    expect(divider?.tagName).toBe('DIV')
    expect(divider).toHaveAttribute('aria-hidden', 'true')
    expect(within(mobileMenu).getAllByRole('link')).toHaveLength(5)
  })

  it('일반 부원 모바일 메뉴에는 관리자 링크와 구분선이 없다', async () => {
    renderHeader()

    fireEvent.click(await screen.findByRole('button', { name: '메뉴 열기' }))
    const mobileMenu = document.getElementById('app-mobile-menu') as HTMLElement

    expect(
      within(mobileMenu).queryByRole('link', { name: '회원 관리' }),
    ).toBeNull()
    expect(mobileMenu.querySelector('[aria-hidden="true"]')).toBeNull()
  })

  it('현재 링크를 표시하고 선택하면 모바일 메뉴를 닫는다', async () => {
    renderHeader('/notices')

    fireEvent.click(await screen.findByRole('button', { name: '메뉴 열기' }))
    const mobileMenu = document.getElementById('app-mobile-menu') as HTMLElement
    const current = within(mobileMenu).getByRole('link', { name: '공지사항' })

    expect(current.className).toContain('text-foreground')
    expect(current).toHaveAttribute('aria-current', 'page')
    fireEvent.click(current)
    expect(document.getElementById('app-mobile-menu')).toBeNull()
  })

  it('다른 컴포넌트의 navigate로 위치가 바뀌면 초점을 옮기지 않고 닫는다', async () => {
    renderHeaderWithNavigation()

    fireEvent.click(await screen.findByRole('button', { name: '메뉴 열기' }))
    expect(document.getElementById('app-mobile-menu')).not.toBeNull()

    const navigate = screen.getByRole('button', { name: '다른 화면으로 이동' })
    navigate.focus()
    fireEvent.click(navigate)

    expect(screen.getByLabelText('현재 경로')).toHaveTextContent('/notes')
    expect(document.getElementById('app-mobile-menu')).toBeNull()
    expect(navigate).toHaveFocus()
    expect(screen.getByRole('button', { name: '메뉴 열기' })).not.toHaveFocus()
  })

  it('재토글과 Escape로 닫고 Escape 뒤에는 버튼으로 초점을 돌린다', async () => {
    renderHeader()

    const open = await screen.findByRole('button', { name: '메뉴 열기' })
    expect(open).toHaveAttribute('aria-controls', 'app-mobile-menu')
    expect(open).toHaveAttribute('aria-expanded', 'false')

    fireEvent.click(open)
    const close = screen.getByRole('button', { name: '메뉴 닫기' })
    expect(close).toHaveAttribute('aria-expanded', 'true')
    fireEvent.click(close)
    expect(document.getElementById('app-mobile-menu')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: '메뉴 열기' }))
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(document.getElementById('app-mobile-menu')).toBeNull()
    expect(screen.getByRole('button', { name: '메뉴 열기' })).toHaveFocus()
  })
})
