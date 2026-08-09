import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { ApiError } from './api/client'
import type { User } from './api/types'
import { SessionProvider, useSession } from './auth/session'

const auth = vi.hoisted(() => ({
  me: (): Promise<User> =>
    Promise.reject(new Error('테스트가 지정하지 않았다')),
  logout: (): Promise<void> => Promise.resolve(),
}))

vi.mock('./api/auth', () => ({
  getMe: () => auth.me(),
  logout: () => auth.logout(),
}))

// 이 파일은 라우트 가드와 헤더만 본다. 공지 화면이 실제 요청을 내보내면
// 로딩 실패 alert가 생겨 로그아웃 alert와 섞인다.
vi.mock('./api/notices', () => ({
  list: () =>
    Promise.resolve({
      content: [],
      page: { size: 10, number: 0, totalElements: 0, totalPages: 0 },
    }),
  get: () => Promise.reject(new Error('이 파일에서는 쓰지 않는다')),
}))

const BASE: User = {
  id: 1,
  email: 'member@khu.ac.kr',
  studentNo: '2021123456',
  name: '홍길동',
  role: 'USER',
  status: 'ACTIVE',
  createdAt: '2026-03-02T09:00:00Z',
  approvedAt: '2026-03-03T09:00:00Z',
}

/** 화면이 아직 없어서, 보호 API가 403을 주는 상황을 이 버튼으로 대신 일으킨다. */
function ReportError({ error }: { error: unknown }) {
  const { reportApiError } = useSession()
  return (
    <button type="button" onClick={() => reportApiError(error)}>
      오류 발생
    </button>
  )
}

function renderAt(path: string, extra?: React.ReactNode) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        {extra}
        <App />
      </SessionProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  auth.me = () =>
    Promise.reject(new ApiError('UNAUTHENTICATED', 401, '로그인이 필요합니다.'))
  auth.logout = () => Promise.resolve()
})

/** 메뉴 링크 이름 목록. 플레이스홀더 화면 제목과 겹치므로 범위를 nav 안으로 좁힌다. */
function menuLabels() {
  const nav = screen.getByRole('navigation', { name: '주요 메뉴' })
  return within(nav)
    .queryAllByRole('link')
    .map((link) => link.textContent)
}

describe('라우트 가드', () => {
  it('PENDING 사용자가 보호 라우트에 가면 대기중 안내로 되돌린다', async () => {
    auth.me = () =>
      Promise.resolve({ ...BASE, status: 'PENDING', approvedAt: null })

    renderAt('/notices')

    expect(
      await screen.findByRole('heading', { name: '승인 대기' }),
    ).toBeInTheDocument()
  })

  it('ACTIVE USER가 관리자 라우트에 가면 차단하고 부원 홈으로 되돌린다', async () => {
    auth.me = () => Promise.resolve(BASE)

    renderAt('/admin/members')

    expect(
      await screen.findByRole('heading', { name: '공지 목록' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '회원 관리' })).toBeNull()
  })

  // 회귀 — 정지 계정을 세션에 넣으면 homePath → RequireActive → GuestOnly가
  // 서로를 밀며 무한히 돈다(Maximum update depth exceeded).
  it('getMe가 SUSPENDED 사용자를 주면 세션을 만들지 않고 순환 없이 로그인 화면에 닿는다', async () => {
    auth.me = () => Promise.resolve({ ...BASE, status: 'SUSPENDED' })

    renderAt('/notices')

    expect(
      await screen.findByRole('heading', { name: '로그인' }),
    ).toBeInTheDocument()
  })

  // 회귀 — 회원가입 신청은 비로그인 전용이다(spec §3-1-3). 정지 계정에게는 로그인 화면만 연다.
  it('SUSPENDED가 가입 화면에 가면 로그인 화면으로 보낸다', async () => {
    auth.me = () => Promise.resolve({ ...BASE, status: 'SUSPENDED' })

    renderAt('/signup')

    expect(
      await screen.findByRole('heading', { name: '로그인' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '가입 신청' })).toBeNull()
  })

  // 회귀 — 세션 도중 관리자가 정지시키면(#31) 이후 보호 API가 403 SUSPENDED로 실패한다.
  // 이 코드를 무시하면 ACTIVE 세션이 남아 화면은 열려 있고 요청만 전부 실패한다.
  it('보호 API가 403 SUSPENDED를 주면 ACTIVE 세션을 정리하고 로그인 화면으로 보낸다', async () => {
    auth.me = () => Promise.resolve(BASE)

    renderAt(
      '/notices',
      <ReportError
        error={new ApiError('SUSPENDED', 403, '이용이 정지된 계정입니다.')}
      />,
    )

    expect(
      await screen.findByRole('heading', { name: '공지 목록' }),
    ).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '오류 발생' }))

    expect(
      await screen.findByRole('heading', { name: '로그인' }),
    ).toBeInTheDocument()
  })
})

describe('헤더 메뉴 노출', () => {
  it('ADMIN에게는 관리 메뉴까지 보인다', async () => {
    auth.me = () => Promise.resolve({ ...BASE, role: 'ADMIN' })

    renderAt('/admin/notices')
    await screen.findByRole('heading', { name: '공지 관리' })

    expect(menuLabels()).toEqual(['공지', '공지 관리', '회원 관리'])
  })

  it('ACTIVE USER에게는 공지만 보이고 관리 메뉴는 없다', async () => {
    auth.me = () => Promise.resolve(BASE)

    renderAt('/notices')
    await screen.findByRole('heading', { name: '공지 목록' })

    expect(menuLabels()).toEqual(['공지'])
  })

  it('PENDING에게는 메뉴가 없고 로그아웃만 있다', async () => {
    auth.me = () =>
      Promise.resolve({ ...BASE, status: 'PENDING', approvedAt: null })

    renderAt('/pending')
    await screen.findByRole('heading', { name: '승인 대기' })

    expect(menuLabels()).toEqual([])
    expect(screen.getByRole('button', { name: '로그아웃' })).toBeInTheDocument()
  })
})

describe('로그아웃', () => {
  it('성공하면 세션을 비우고 로그인 화면으로 보낸다', async () => {
    auth.me = () => Promise.resolve(BASE)

    renderAt('/notices')
    fireEvent.click(await screen.findByRole('button', { name: '로그아웃' }))

    expect(
      await screen.findByRole('heading', { name: '로그인' }),
    ).toBeInTheDocument()
  })

  // 회귀 — 실패를 성공처럼 처리하면 서버 세션(HttpOnly 쿠키)이 살아 있는데 사용자는
  // 로그아웃됐다고 믿는다. 공용 PC에서 다음 사람이 남의 계정으로 들어가진다.
  it('서버 오류로 실패하면 세션을 유지하고 이동하지 않는다', async () => {
    auth.me = () => Promise.resolve(BASE)
    auth.logout = () =>
      Promise.reject(
        new ApiError('NETWORK_ERROR', 0, '서버에 연결하지 못했습니다.'),
      )

    renderAt('/notices')
    fireEvent.click(await screen.findByRole('button', { name: '로그아웃' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '로그아웃하지 못했습니다',
    )
    expect(
      screen.getByRole('heading', { name: '공지 목록' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '로그인' })).toBeNull()
  })
})
