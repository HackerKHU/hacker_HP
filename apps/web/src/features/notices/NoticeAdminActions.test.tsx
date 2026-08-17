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
import type { Notice } from '@/api/notices'
import type { User } from '@/api/types'
import { SessionProvider } from '@/auth/session'

/**
 * 관리자 진입점과 삭제 (#41).
 *
 * 여기서도 앱을 실제 경로로 띄운다 — 진입점은 링크라 **가리키는 주소가 맞는지**가
 * 핵심인데, 컴포넌트를 직접 그리면 그 주소로 실제로 갈 수 있는지는 확인되지 않는다.
 *
 * **부재를 단언하기 전에 화면이 다 그려질 때까지 기다린다.** 아직 안 그려진 것을 두고
 * "없다"고 하면 무엇이든 통과한다 — 목록이나 상세가 도착했다는 증거를 먼저 잡는다.
 */
const NOTICE: Notice = {
  id: 1,
  title: '지울 공지',
  content: '본문',
  isPinned: false,
  createdAt: '2026-08-05T09:00:00Z',
  updatedAt: '2026-08-05T09:00:00Z',
}

const api = vi.hoisted(() => ({
  removed: [] as number[],
  removeFails: false,
}))

vi.mock('@/api/notices', () => ({
  list: () =>
    Promise.resolve({
      content: [
        {
          id: 1,
          title: '지울 공지',
          content: '본문',
          isPinned: false,
          createdAt: '2026-08-05T09:00:00Z',
          updatedAt: '2026-08-05T09:00:00Z',
        },
      ],
      page: { size: 10, number: 0, totalElements: 1, totalPages: 1 },
    }),
  get: () => Promise.resolve(NOTICE),
  remove: (id: number) => {
    if (api.removeFails) {
      return Promise.reject(new ApiError('FORBIDDEN', 403, '권한이 없습니다.'))
    }
    api.removed.push(id)
    return Promise.resolve()
  },
  togglePin: () => Promise.resolve(NOTICE),
  create: () => Promise.resolve(NOTICE),
  update: () => Promise.resolve(NOTICE),
}))

const auth = vi.hoisted(() => ({ role: 'ADMIN' as 'USER' | 'ADMIN' }))

const BASE: User = {
  id: 1,
  email: 'admin@khu.ac.kr',
  studentNo: '2021123456',
  name: '김관리',
  role: 'ADMIN',
  status: 'ACTIVE',
  createdAt: '2026-03-02T09:00:00Z',
  appliedAt: '2026-03-02T09:10:00Z',
  approvedAt: '2026-03-03T09:00:00Z',
}

vi.mock('@/api/auth', () => ({
  getMe: () => Promise.resolve({ ...BASE, role: auth.role }),
  logout: () => Promise.resolve(),
}))

function Address() {
  const { pathname } = useLocation()
  return <div data-testid="pathname">{pathname}</div>
}

function renderAt(path: string) {
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

beforeEach(() => {
  api.removed = []
  api.removeFails = false
  auth.role = 'ADMIN'
})

describe('관리자 진입점 노출', () => {
  it('ADMIN에게는 목록에 글쓰기가 보인다', async () => {
    renderAt('/notices')

    // 목록이 도착했는지부터 확인한다 — 진입점 유무는 그 뒤에 본다.
    await screen.findByRole('link', { name: /지울 공지/ })
    expect(screen.getByRole('link', { name: '글쓰기' })).toHaveAttribute(
      'href',
      '/admin/notices/new',
    )
  })

  it('ADMIN에게는 상세에 수정·삭제가 보인다', async () => {
    renderAt('/notices/1')

    expect(await screen.findByRole('link', { name: '수정' })).toHaveAttribute(
      'href',
      '/admin/notices/1/edit',
    )
    expect(screen.getByRole('button', { name: '삭제' })).toBeInTheDocument()
  })

  /*
   * 일반 부원에게는 보이지 않는다. **노출 제어일 뿐 권한 통제가 아니다** (spec §3-1-7) —
   * 실제 차단은 `NoticeFormPage.test.tsx`의 가드 테스트가 확인한다.
   *
   * 한 테스트에서 두 화면을 이어 렌더하지 않는다. 앞 화면이 마운트된 채로 남아 다음
   * 조회가 어느 쪽을 집었는지 흐려지고, 부재 단언은 그런 상태에서 특히 못 미덥다.
   */
  it('USER에게는 목록에 글쓰기가 없다', async () => {
    auth.role = 'USER'

    renderAt('/notices')

    await screen.findByRole('link', { name: /지울 공지/ })
    expect(screen.queryByRole('link', { name: '글쓰기' })).toBeNull()
  })

  it('USER에게는 상세에 수정·삭제가 없다', async () => {
    auth.role = 'USER'

    renderAt('/notices/1')

    await screen.findByRole('heading', { name: '지울 공지' })
    expect(screen.queryByRole('link', { name: '수정' })).toBeNull()
    expect(screen.queryByRole('button', { name: '삭제' })).toBeNull()
  })
})

describe('공지 삭제', () => {
  it('확인 전에는 삭제되지 않고, 다이얼로그가 무엇을 지우는지 보여준다', async () => {
    renderAt('/notices/1')

    fireEvent.click(await screen.findByRole('button', { name: '삭제' }))

    const dialog = await screen.findByRole('alertdialog')
    // "이 항목을 삭제할까요?"로는 무엇을 지우는지 확인할 수 없다.
    expect(dialog).toHaveTextContent('지울 공지')

    // 아직 아무것도 지우지 않았다. 취소하면 그대로 남는다.
    expect(api.removed).toEqual([])
    fireEvent.click(screen.getByRole('button', { name: '취소' }))
    await waitFor(() => {
      expect(screen.queryByRole('alertdialog')).toBeNull()
    })
    expect(api.removed).toEqual([])
    expect(pathname()).toBe('/notices/1')
  })

  it('확인하면 삭제하고 목록으로 보낸다', async () => {
    renderAt('/notices/1')

    fireEvent.click(await screen.findByRole('button', { name: '삭제' }))
    const dialog = await screen.findByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: '삭제' }))

    await waitFor(() => {
      expect(pathname()).toBe('/notices')
    })
    expect(api.removed).toEqual([1])
  })

  // 실패했는데 목록으로 보내면 지워진 줄 안다.
  it('삭제에 실패하면 그 자리에 남고 안내가 나온다', async () => {
    api.removeFails = true

    renderAt('/notices/1')
    fireEvent.click(await screen.findByRole('button', { name: '삭제' }))
    const dialog = await screen.findByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: '삭제' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '공지를 삭제하지 못했습니다',
    )
    expect(pathname()).toBe('/notices/1')
  })
})
