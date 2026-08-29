import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/App'
import { ApiError } from '@/api/client'
import type { User } from '@/api/types'
import { SessionProvider } from '@/auth/session'

/**
 * 마이페이지 (#178, spec [2-1 §2-1-9](../../../../spec/2-1-USER-STORIES.md)).
 *
 * **컴포넌트를 직접 렌더하지 않는다.** `/me`로 앱을 띄워 `RequireActive` 가드를 실제로
 * 태운다 — 이 화면의 절반은 "누가 못 들어오는가"이고, 컴포넌트만 렌더하면 그쪽이 통째로
 * 빠진다.
 */

const auth = vi.hoisted(() => ({
  me: (): Promise<User> =>
    Promise.reject(new Error('테스트가 지정하지 않았다')),
}))

vi.mock('@/api/auth', () => ({
  getMe: () => auth.me(),
  logout: () => Promise.resolve(),
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

function Address() {
  const { pathname } = useLocation()
  return <div data-testid="pathname">{pathname}</div>
}

function renderAt(path = '/me') {
  render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <Address />
        <App />
      </SessionProvider>
    </MemoryRouter>,
  )
}

function pathname(): string {
  return screen.getByTestId('pathname').textContent ?? ''
}

/**
 * 헤더의 계정 메뉴를 연다. Radix는 `pointerdown`으로 열린다 — `click`만 쏘면 안 열린다.
 * jsdom에 `PointerEvent`가 없어 `MouseEvent`로 대신 만든다.
 */
async function openAccountMenu() {
  const trigger = await screen.findByRole('button', { name: '계정 메뉴' })
  fireEvent.pointerDown(
    trigger,
    new MouseEvent('pointerdown', { bubbles: true, button: 0 }),
  )
  await screen.findByRole('menu')
}

/** 라벨이 붙은 값 한 줄. `<dl>`이라 `dt`의 형제 `dd`를 읽는다. */
function field(label: string): string {
  const term = screen.getByText(label)
  return term.nextElementSibling?.textContent ?? ''
}

beforeEach(() => {
  auth.me = () => Promise.resolve(MEMBER)
})

describe('마이페이지', () => {
  /* T-387 — 일곱 값이 전부 보인다. */
  it('내 이름·이메일·학번·학과·상태·신청일·승인일을 보여준다', async () => {
    renderAt()

    await screen.findByRole('heading', { name: '내 정보' })

    expect(field('이름')).toBe('홍길동')
    expect(field('이메일')).toBe('member@khu.ac.kr')
    expect(field('학번')).toBe('2021123456')
    expect(field('학과')).toBe('컴퓨터공학과')
    expect(field('상태')).toBe('활동중')
    expect(field('가입 신청일')).toBe('2026. 03. 05.')
    expect(field('승인일')).toBe('2026. 03. 07.')
  })

  /*
   * **T-387의 핵심이다.** 보기 전용은 기능을 덜 만든 상태가 아니라 결정이고
   * (2-1 §2-1-9 MUST), 나중에 입력란이 하나 생기면 이 사례가 깨져야 한다. 승인 뒤에
   * 본인이 학번·학과를 갈아치우면 관리자가 심사한 내용과 저장된 내용이 달라진다.
   */
  it('고칠 수 있는 입력란이 없다', async () => {
    renderAt()

    const page = await screen.findByRole('heading', { name: '내 정보' })
    const section = page.closest('section')
    expect(section).not.toBeNull()
    expect(section?.querySelectorAll('input, select, textarea')).toHaveLength(0)
    /*
     * 버튼은 탈퇴 하나뿐이다 (#226). 저장·수정처럼 값을 바꾸는 조작이 하나라도 늘면
     * 여기서 걸린다 — 탈퇴는 정보를 고치는 것이 아니라 계정을 지우는 것이다.
     */
    expect(
      within(section as HTMLElement)
        .getAllByRole('button')
        .map((button) => button.textContent),
    ).toEqual(['회원 탈퇴'])
  })

  /*
   * 신청일·승인일은 이 필드들이 생기기 전에 승인된 회원에게 없다 (spec §3-2-2).
   * 줄을 통째로 숨기면 무엇이 비었는지 안 보여 문의할 거리조차 모른다.
   */
  it('비어 있는 값은 줄을 지우지 않고 —로 보여준다', async () => {
    auth.me = () =>
      Promise.resolve({
        ...MEMBER,
        studentNo: null,
        department: null,
        appliedAt: null,
        approvedAt: null,
      })
    renderAt()

    await screen.findByRole('heading', { name: '내 정보' })

    expect(field('학번')).toBe('—')
    expect(field('학과')).toBe('—')
    expect(field('가입 신청일')).toBe('—')
    expect(field('승인일')).toBe('—')
  })

  /*
   * 마이페이지는 `PENDING`에게 닫혀 있다 (spec §3-1-3 매트릭스). 여는 순간 `PENDING`이
   * 볼 수 있는 인증 화면이 둘이 되어 §3-1-6의 "유일한 화면"이 무너진다.
   */
  it('PENDING은 들어갈 수 없다', async () => {
    auth.me = () =>
      Promise.resolve({ ...MEMBER, status: 'PENDING', approvedAt: null })
    renderAt()

    await waitFor(() => expect(pathname()).toBe('/pending'))
    expect(
      screen.queryByRole('heading', { name: '내 정보' }),
    ).not.toBeInTheDocument()
  })

  it('비로그인은 로그인 화면으로 간다', async () => {
    auth.me = () =>
      Promise.reject(
        new ApiError('UNAUTHENTICATED', 401, '로그인이 필요합니다.'),
      )
    renderAt()

    await waitFor(() => expect(pathname()).toBe('/login'))
  })

  /* 들어갈 길이 없으면 화면이 있어도 아무도 못 온다 (#178 작업 내용). */
  it('헤더의 계정 메뉴에서 들어가는 길이 있다', async () => {
    renderAt()
    await openAccountMenu()

    expect(
      screen.getByRole('menuitem', { name: '마이페이지' }),
    ).toHaveAttribute('href', '/me')
  })

  /* `PENDING`도 같은 헤더를 쓴다 — 거기서는 보이면 안 된다. */
  it('PENDING에게는 그 길이 보이지 않는다', async () => {
    auth.me = () =>
      Promise.resolve({ ...MEMBER, status: 'PENDING', approvedAt: null })
    renderAt('/pending')

    await waitFor(() => expect(pathname()).toBe('/pending'))
    await openAccountMenu()
    expect(
      screen.queryByRole('menuitem', { name: '마이페이지' }),
    ).not.toBeInTheDocument()
  })
})
