import {
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import type { Notice } from '@/api/notices'
import type { Page } from '@/api/types'
import { SessionProvider } from '@/auth/session'
import { NoticeListPage } from './NoticeListPage'

const DAY_MS = 24 * 60 * 60 * 1000

const api = vi.hoisted(() => ({
  calls: [] as (number | undefined)[],
  role: 'USER' as 'USER' | 'ADMIN',
  togglePinFails: false,
}))

/** 새글 판정이 실행 시각 기준이므로 테스트 데이터도 상대 날짜로 만든다. */
function notice(
  id: number,
  title: string,
  isPinned: boolean,
  daysAgo: number,
): Notice {
  const at = new Date(Date.now() - daysAgo * DAY_MS).toISOString()
  return { id, title, content: '본문', isPinned, createdAt: at, updatedAt: at }
}

/** 서버가 이미 `is_pinned DESC, created_at DESC`로 정렬해 내려준 상태를 흉내낸다. */
const PAGES: Page<Notice>[] = [
  {
    content: [
      notice(1, '고정된 공지', true, 30),
      notice(2, '최근 공지', false, 1),
      notice(3, '일반 공지', false, 30),
    ],
    page: { size: 10, number: 0, totalElements: 12, totalPages: 2 },
  },
  {
    content: [notice(4, '둘째 장 공지', false, 40)],
    page: { size: 10, number: 1, totalElements: 12, totalPages: 2 },
  },
]

vi.mock('@/api/notices', () => ({
  list: ({ page }: { page?: number }) => {
    api.calls.push(page)
    const index = page ?? 0
    // 실제 서버는 범위를 넘은 page에도 유효한 PagedModel을 준다 — content만 비어 있다.
    return Promise.resolve(
      PAGES[index] ?? {
        content: [],
        page: { size: 10, number: index, totalElements: 12, totalPages: 2 },
      },
    )
  },
  togglePin: () =>
    api.togglePinFails
      ? Promise.reject(new ApiError('FORBIDDEN', 403, '권한이 없습니다.'))
      : Promise.resolve(PAGES[0].content[0]),
}))

// 세션은 이 화면의 관심사가 아니다. role과 reportApiError만 있으면 된다.
vi.mock('@/api/auth', () => ({
  getMe: () =>
    Promise.resolve({
      id: 1,
      email: 'member@khu.ac.kr',
      studentNo: '2021123456',
      name: '홍길동',
      role: api.role,
      status: 'ACTIVE',
      createdAt: '2026-03-02T09:00:00Z',
      approvedAt: '2026-03-03T09:00:00Z',
    }),
}))

function LocationProbe() {
  const { search } = useLocation()
  return <output data-testid="search">{search}</output>
}

function renderList(path = '/notices') {
  render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <NoticeListPage />
        <LocationProbe />
      </SessionProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  api.calls = []
  api.role = 'USER'
  api.togglePinFails = false
})

describe('공지 목록', () => {
  it('고정 공지를 일반 공지와 구분해 렌더한다', async () => {
    renderList()

    const pinned = await screen.findByRole('link', { name: /고정된 공지/ })
    const normal = screen.getByRole('link', { name: /일반 공지/ })

    // 무채색 팔레트라 색이 아니라 좌측 바와 핀 아이콘으로 가른다.
    expect(pinned.className).toContain('border-l-primary')
    expect(normal.className).not.toContain('border-l-primary')
    // 아이콘만 두면 스크린리더가 못 읽으므로 의미가 텍스트로 남아 있어야 한다.
    expect(within(pinned).getByText('고정')).toBeInTheDocument()
    expect(within(normal).queryByText('고정')).toBeNull()
  })

  it('3일 이내 공지에만 NEW를 붙인다', async () => {
    renderList()

    const recent = await screen.findByRole('link', { name: /최근 공지/ })
    const old = screen.getByRole('link', { name: /일반 공지/ })

    expect(within(recent).getByText('NEW')).toBeInTheDocument()
    expect(within(old).queryByText('NEW')).toBeNull()
  })

  it('관리 버튼은 ADMIN에게만 보인다', async () => {
    renderList()
    await screen.findByRole('link', { name: /고정된 공지/ })
    expect(screen.queryByRole('button', { name: '관리' })).toBeNull()

    api.role = 'ADMIN'
    renderList()

    expect(
      await screen.findByRole('button', { name: '관리' }),
    ).toBeInTheDocument()
  })

  it('페이지를 옮기면 URL이 바뀌고 목록을 다시 불러온다', async () => {
    renderList()
    await screen.findByRole('link', { name: /고정된 공지/ })
    expect(screen.getByTestId('search')).toHaveTextContent('')

    // 링크 라벨은 사람이 읽는 1-기반, URL과 API 파라미터는 0-기반이다 (spec §3-2-8).
    fireEvent.click(screen.getByRole('link', { name: '2' }))

    expect(
      await screen.findByRole('link', { name: /둘째 장 공지/ }),
    ).toBeInTheDocument()
    expect(screen.getByTestId('search')).toHaveTextContent('page=1')
    expect(api.calls).toEqual([0, 1])
  })

  // 회귀 — 소수는 API 정수 계약을 깨고, 범위 초과는 공지가 있는데도 없다고 말한다.
  it('비정상 page 값을 유효한 페이지로 수렴시킨다', async () => {
    renderList('/notices?page=1.5')

    // 소수는 내림해서 정수 페이지가 된다. 그래야 활성 링크에 aria-current가 붙는다.
    expect(
      await screen.findByRole('link', { name: /둘째 장 공지/ }),
    ).toBeInTheDocument()
    expect(api.calls).toEqual([1])
    expect(
      screen.getByRole('link', { name: '2', current: 'page' }),
    ).toBeInTheDocument()

    cleanup()
    api.calls = []
    renderList('/notices?page=999')

    // 범위를 넘으면 마지막 유효 페이지로 되돌린다. 빈 목록을 보여주지 않는다.
    expect(
      await screen.findByRole('link', { name: /둘째 장 공지/ }),
    ).toBeInTheDocument()
    expect(screen.queryByText('등록된 공지가 없습니다.')).toBeNull()
    expect(screen.getByTestId('search')).toHaveTextContent('page=1')
    // 되돌린 뒤에는 page가 유효해져 조건이 다시 참이 되지 않는다 — 조회는 딱 두 번이다.
    expect(api.calls).toEqual([999, 1])
  })

  // 회귀 — 토글이 실패해도 세션 계약을 지키고 화면이 남아 있어야 한다.
  it('고정 토글이 실패하면 알리고 목록은 그대로 둔다', async () => {
    api.role = 'ADMIN'
    api.togglePinFails = true

    renderList()
    fireEvent.click(await screen.findByRole('button', { name: '관리' }))
    fireEvent.click(screen.getAllByRole('button', { name: /^고정/ })[0])

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '고정 상태를 바꾸지 못했습니다',
    )
    expect(
      screen.getByRole('link', { name: /고정된 공지/ }),
    ).toBeInTheDocument()
  })
})
