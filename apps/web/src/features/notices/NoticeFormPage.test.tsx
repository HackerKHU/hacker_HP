import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/App'
import { ApiError } from '@/api/client'
import type { Notice } from '@/api/notices'
import type { User } from '@/api/types'
import { SessionProvider } from '@/auth/session'

/**
 * 공지 작성·수정 화면 (#41).
 *
 * **컴포넌트를 직접 렌더하지 않는다.** `/admin/notices/new`로 앱을 띄워 라우트 가드를
 * 실제로 태운다 — 컴포넌트를 직접 그리면 가드를 건너뛰어, 라우트가 가드 밖으로 나가도
 * 테스트가 계속 통과한다. "일반 부원이 URL로 직접 접근하면 차단된다"가 이슈의 완료
 * 조건이므로 그 경로를 그대로 밟아야 의미가 있다.
 */
const api = vi.hoisted(() => ({
  created: [] as { title: string; content: string }[],
  updated: [] as { id: number; title: string; content: string }[],
  createFails: null as ApiError | null,
  getFails: false,
}))

const EXISTING: Notice = {
  id: 7,
  title: '기존 제목',
  content: '기존 본문',
  isPinned: false,
  createdAt: '2026-03-02T09:00:00Z',
  updatedAt: '2026-03-02T09:00:00Z',
}

vi.mock('@/api/notices', () => ({
  // 목록은 이 화면의 관심사가 아니다. 저장 후 이동 경로만 확인하면 된다.
  list: () =>
    Promise.resolve({
      content: [],
      page: { size: 10, number: 0, totalElements: 0, totalPages: 0 },
    }),
  get: (id: number) => {
    if (api.getFails) {
      return Promise.reject(new ApiError('NOT_FOUND', 404, '없습니다.'))
    }
    return Promise.resolve({ ...EXISTING, id })
  },
  create: (body: { title: string; content: string }) => {
    if (api.createFails) return Promise.reject(api.createFails)
    api.created.push(body)
    return Promise.resolve({ ...EXISTING, ...body, id: 99 })
  },
  update: (id: number, body: { title: string; content: string }) => {
    api.updated.push({ id, ...body })
    return Promise.resolve({ ...EXISTING, ...body, id })
  },
  remove: () => Promise.resolve(),
  togglePin: () => Promise.resolve(EXISTING),
}))

const auth = vi.hoisted(() => ({
  role: 'ADMIN' as 'USER' | 'ADMIN',
}))

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

/** 지금 주소를 화면에 드러내 이동 결과를 단언할 수 있게 한다. */
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
  api.created = []
  api.updated = []
  api.createFails = null
  api.getFails = false
  auth.role = 'ADMIN'
})

describe('작성·수정 화면 접근 권한', () => {
  // T-63 — 이슈 #41 완료 조건. 가드를 실제로 타는 경로다.
  it.each([
    ['/admin/notices/new', '새 공지'],
    ['/admin/notices/7/edit', '공지 수정'],
  ])('USER가 %s로 직접 들어오면 차단된다', async (path, heading) => {
    auth.role = 'USER'

    renderAt(path)

    // 공지 목록으로 되돌아간다. 폼은 그려지지 않는다.
    await waitFor(() => {
      expect(pathname()).toBe('/notices')
    })
    expect(screen.queryByRole('heading', { name: heading })).toBeNull()
  })

  it.each([
    ['/admin/notices/new', '새 공지'],
    ['/admin/notices/7/edit', '공지 수정'],
  ])('ADMIN은 %s를 연다', async (path, heading) => {
    renderAt(path)

    expect(
      await screen.findByRole('heading', { name: heading }),
    ).toBeInTheDocument()
  })
})

describe('공지 등록', () => {
  it('저장하면 새 공지 상세로 이동한다', async () => {
    renderAt('/admin/notices/new')
    await screen.findByRole('heading', { name: '새 공지' })

    fireEvent.change(screen.getByLabelText('제목'), {
      target: { value: '새 제목' },
    })
    fireEvent.change(screen.getByLabelText('내용'), {
      target: { value: '새 본문' },
    })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(pathname()).toBe('/notices/99')
    })
    expect(api.created).toEqual([{ title: '새 제목', content: '새 본문' }])
  })

  // 스키마가 둘 다 NOT NULL이다. 공백만 넣은 것도 빈 값으로 본다.
  it.each([
    ['제목이 공백뿐이면', '   ', '본문'],
    ['내용이 공백뿐이면', '제목', '   '],
  ])('%s 저장 요청이 나가지 않는다', async (_label, title, content) => {
    renderAt('/admin/notices/new')
    await screen.findByRole('heading', { name: '새 공지' })

    fireEvent.change(screen.getByLabelText('제목'), {
      target: { value: title },
    })
    fireEvent.change(screen.getByLabelText('내용'), {
      target: { value: content },
    })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '제목과 내용을 입력해주세요.',
    )
    expect(api.created).toEqual([])
    expect(pathname()).toBe('/admin/notices/new')
  })

  // 제목은 varchar(200)이다 (spec §3-2-2). 상한이 화면에 드러나야 한다.
  it('제목 상한이 화면에 보이고 입력이 상한을 넘지 않는다', async () => {
    renderAt('/admin/notices/new')
    await screen.findByRole('heading', { name: '새 공지' })

    const title = screen.getByLabelText('제목')
    expect(title).toHaveAttribute('maxLength', '200')
    expect(screen.getByText('0/200')).toBeInTheDocument()

    fireEvent.change(title, { target: { value: '가'.repeat(200) } })
    expect(screen.getByText('200/200')).toBeInTheDocument()
  })

  /*
   * **실패했는데 성공한 것처럼 보이면 안 된다.** 서버 메시지를 그대로 띄우고,
   * 이동하지 않고, 입력값을 잃지 않는다.
   */
  it('서버가 거부하면 메시지를 띄우고 이동하지 않는다', async () => {
    api.createFails = new ApiError(
      'VALIDATION_ERROR',
      400,
      '제목은 200자를 넘을 수 없습니다.',
    )

    renderAt('/admin/notices/new')
    await screen.findByRole('heading', { name: '새 공지' })

    fireEvent.change(screen.getByLabelText('제목'), {
      target: { value: '제목' },
    })
    fireEvent.change(screen.getByLabelText('내용'), {
      target: { value: '본문' },
    })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '제목은 200자를 넘을 수 없습니다.',
    )
    expect(pathname()).toBe('/admin/notices/new')
    expect(screen.getByLabelText('제목')).toHaveValue('제목')
  })
})

describe('공지 수정', () => {
  it('기존 값이 폼에 채워지고 저장하면 그 공지로 돌아간다', async () => {
    renderAt('/admin/notices/7/edit')
    await screen.findByRole('heading', { name: '공지 수정' })

    // 빈 폼이면 저장하는 순간 기존 내용이 날아간다.
    expect(screen.getByLabelText('제목')).toHaveValue('기존 제목')
    expect(screen.getByLabelText('내용')).toHaveValue('기존 본문')

    fireEvent.change(screen.getByLabelText('제목'), {
      target: { value: '고친 제목' },
    })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(pathname()).toBe('/notices/7')
    })
    expect(api.updated).toEqual([
      { id: 7, title: '고친 제목', content: '기존 본문' },
    ])
  })

  it('없는 공지를 수정하려 하면 안내가 나오고 폼이 뜨지 않는다', async () => {
    api.getFails = true

    renderAt('/admin/notices/7/edit')

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '공지를 찾을 수 없습니다',
    )
    expect(screen.queryByLabelText('제목')).toBeNull()
  })
})
