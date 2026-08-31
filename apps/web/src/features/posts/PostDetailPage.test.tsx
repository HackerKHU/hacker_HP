import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import type { PostDetail } from '@/api/posts'
import type { User } from '@/api/types'
import { SessionProvider, useSession } from '@/auth/session'
import {
  MemoryRouter,
  Route,
  Routes,
  useLocation,
  useNavigationType,
} from '@/test/TestRouter'
import { PostDetailPage } from './PostDetailPage'

const api = vi.hoisted(() => ({
  post: null as PostDetail | null,
  remove: vi.fn<(id: number) => Promise<void>>(),
}))

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

const auth = vi.hoisted(() => ({ me: null as User | null }))

const POST: PostDetail = {
  id: 701,
  title: '이번 학기 스터디 모집합니다',
  content: '매주 수요일 저녁 7시입니다.\n관심 있으신 분 연락 주세요.',
  // 관리자는 작성자가 아니어도 삭제할 수 있어야 한다.
  author: { id: 99, name: '권승원66' },
  createdAt: '2026-08-01T09:00:00Z',
  updatedAt: '2026-08-01T09:00:00Z',
}

vi.mock('@/api/posts', () => ({
  get: () =>
    api.post
      ? Promise.resolve(api.post)
      : Promise.reject(
          new ApiError('NOT_FOUND', 404, '게시글을 찾을 수 없습니다.'),
        ),
  remove: api.remove,
}))

vi.mock('@/api/auth', () => ({
  getMe: () => Promise.resolve(auth.me),
  logout: () => Promise.resolve(),
}))

function NavigationProbe() {
  const { pathname } = useLocation()
  const navigationType = useNavigationType()
  return (
    <div data-testid="navigation">
      {pathname}:{navigationType}
    </div>
  )
}

function SessionProbe() {
  const { state } = useSession()
  const value =
    state.kind === 'active' ? `${state.kind}:${state.user.role}` : state.kind
  return <div data-testid="session-kind">{value}</div>
}

function renderDetail() {
  return render(
    <MemoryRouter initialEntries={['/posts/701']}>
      <SessionProvider>
        <NavigationProbe />
        <SessionProbe />
        <Routes>
          <Route path="/posts/:id" element={<PostDetailPage />} />
          <Route path="/posts/:id/edit" element={<h1>게시글 수정</h1>} />
          <Route path="/posts" element={<h1>자유게시판</h1>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

async function openDeleteDialog() {
  const trigger = await screen.findByRole('button', { name: '삭제' })
  fireEvent.click(trigger)
  return screen.findByRole('alertdialog')
}

beforeEach(() => {
  api.post = POST
  api.remove.mockReset()
  api.remove.mockResolvedValue()
  auth.me = { ...BASE, role: 'ADMIN' }
})

describe('게시글 상세', () => {
  it('장문 제목과 삭제 확인문을 줄이되 원문과 조작을 보존한다', async () => {
    const longTitle = '삭제할 게시글의 아주 긴 제목'.repeat(20)
    api.post = { ...POST, title: longTitle }
    renderDetail()

    const heading = await screen.findByRole('heading', { name: longTitle })
    expect(heading.className).toContain('line-clamp-2')
    expect(heading.className).toContain('break-all')
    expect(heading).toHaveAttribute('title', longTitle)
    expect(screen.getByRole('button', { name: '삭제' })).toBeVisible()

    const dialog = await openDeleteDialog()
    const dialogTitle = within(dialog).getByTitle(longTitle)
    expect(dialogTitle.className).toContain('truncate')
    expect(dialog).toHaveTextContent(longTitle)
  })

  it('제목·본문·작성자·작성일을 보여준다', async () => {
    renderDetail()

    expect(
      document.querySelector('[data-detail-surface="post"]'),
    ).toBeInTheDocument()
    expect(
      await screen.findByRole('heading', { name: POST.title }),
    ).toBeVisible()
    expect(screen.getByText(/매주 수요일 저녁 7시입니다/)).toBeVisible()
    expect(screen.getByText('권승원66')).toBeVisible()
    expect(screen.queryByText(/수정됨/)).toBeNull()
  })

  it('수정 시각이 등록 시각과 다르면 수정됨과 시각을 표시한다', async () => {
    api.post = { ...POST, updatedAt: '2026-08-02T10:30:00Z' }

    renderDetail()

    const marker = await screen.findByText(/수정됨/)
    expect(marker).toBeVisible()
    expect(marker.tagName).toBe('TIME')
    expect(marker).toHaveAttribute('datetime', api.post.updatedAt)
  })

  it('본문의 HTML을 실행하지 않고 글자 그대로 보여준다', async () => {
    api.post = {
      ...POST,
      content: '<script>alert(1)</script><b>굵게</b>',
    }

    const { container } = renderDetail()
    await screen.findByRole('heading', { name: POST.title })

    expect(
      screen.getByText('<script>alert(1)</script><b>굵게</b>'),
    ).toBeVisible()
    expect(container.querySelector('script')).toBeNull()
    expect(container.querySelector('b')).toBeNull()
  })

  it('줄바꿈을 살려 그린다', async () => {
    const { container } = renderDetail()
    await screen.findByRole('heading', { name: POST.title })

    const body = container.querySelector('.whitespace-pre-wrap')
    expect(body).not.toBeNull()
    expect(body?.textContent).toContain('\n')
  })

  it('탈퇴한 회원의 글도 작성자 이름이 보인다', async () => {
    api.post = { ...POST, author: { id: null, name: '탈퇴한 회원' } }

    renderDetail()
    await screen.findByRole('heading', { name: POST.title })

    expect(screen.getByText('탈퇴한 회원')).toBeVisible()
  })

  it('없는 글이면 안내가 뜬다', async () => {
    api.post = null

    renderDetail()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '게시글을 찾을 수 없습니다',
    )
  })
})

describe('작성자 게시글 수정 진입점', () => {
  it.each([
    ['ACTIVE USER', { ...BASE }],
    ['INACTIVE USER', { ...BASE, status: 'INACTIVE' as const }],
    ['ACTIVE ADMIN', { ...BASE, role: 'ADMIN' as const }],
  ])('%s 작성자에게 수정이 보인다', async (_label, me) => {
    auth.me = me
    api.post = { ...POST, author: { id: me.id, name: me.name } }

    renderDetail()

    const edit = await screen.findByRole('link', { name: '수정' })
    expect(edit).toHaveAttribute('href', `/posts/${POST.id}/edit`)
  })

  it.each([
    ['다른 USER', { ...BASE }],
    ['다른 ADMIN', { ...BASE, role: 'ADMIN' as const }],
  ])('%s에게 남의 글 수정은 보이지 않는다', async (_label, me) => {
    auth.me = me
    api.post = { ...POST, author: { id: 99, name: '권승원66' } }

    renderDetail()
    await screen.findByRole('heading', { name: POST.title })

    expect(screen.queryByRole('link', { name: '수정' })).toBeNull()
  })

  it('탈퇴한 회원의 글에는 누구에게도 수정이 보이지 않는다', async () => {
    auth.me = { ...BASE }
    api.post = { ...POST, author: { id: null, name: '탈퇴한 회원' } }

    renderDetail()
    await screen.findByRole('heading', { name: POST.title })

    expect(screen.queryByRole('link', { name: '수정' })).toBeNull()
  })

  it('관리자가 작성자인 글에는 수정과 삭제가 함께 보인다', async () => {
    auth.me = { ...BASE, role: 'ADMIN' }
    api.post = { ...POST, author: { id: BASE.id, name: BASE.name } }

    renderDetail()

    expect(await screen.findByRole('link', { name: '수정' })).toBeVisible()
    expect(screen.getByRole('button', { name: '삭제' })).toBeVisible()
  })
})

describe('관리자·작성자 게시글 삭제', () => {
  it.each([
    [
      '남의 글을 보는 ACTIVE ADMIN',
      { ...BASE, role: 'ADMIN' as const },
      POST.author,
    ],
    [
      '자기 글을 보는 ACTIVE ADMIN',
      { ...BASE, role: 'ADMIN' as const },
      { id: BASE.id, name: BASE.name },
    ],
    [
      '자기 글을 보는 ACTIVE USER',
      { ...BASE },
      { id: BASE.id, name: BASE.name },
    ],
    [
      '자기 글을 보는 INACTIVE USER',
      { ...BASE, status: 'INACTIVE' as const },
      { id: BASE.id, name: BASE.name },
    ],
  ])('%s에게 destructive 삭제가 보인다', async (_label, me, author) => {
    auth.me = me
    api.post = { ...POST, author }
    renderDetail()

    await waitFor(() =>
      expect(screen.getByTestId('session-kind')).toHaveTextContent(
        `active:${me.role}`,
      ),
    )
    const trigger = await screen.findByRole('button', { name: '삭제' })
    expect(trigger).toHaveAttribute('data-variant', 'destructive')
  })

  it.each([
    ['남의 글을 보는 ACTIVE USER', { ...BASE }, POST.author, 'active:USER'],
    [
      '남의 글을 보는 INACTIVE USER',
      { ...BASE, status: 'INACTIVE' as const },
      POST.author,
      'active:USER',
    ],
    [
      '작성자인 PENDING',
      { ...BASE, status: 'PENDING' as const },
      { id: BASE.id, name: BASE.name },
      'pending',
    ],
    [
      '작성자인 SUSPENDED',
      { ...BASE, status: 'SUSPENDED' as const },
      { id: BASE.id, name: BASE.name },
      'suspended',
    ],
    ['비로그인', null, { id: BASE.id, name: BASE.name }, 'guest'],
  ])(
    '%s에게는 삭제가 보이지 않는다',
    async (_label, me, author, expectedState) => {
      auth.me = me
      api.post = { ...POST, author }

      renderDetail()
      await screen.findByRole('heading', { name: POST.title })
      await waitFor(() =>
        expect(screen.getByTestId('session-kind')).toHaveTextContent(
          expectedState,
        ),
      )

      expect(screen.queryByRole('button', { name: '삭제' })).toBeNull()
    },
  )

  it('탈퇴·제거되어 작성자 id가 끊긴 글은 일반 부원에게 삭제가 보이지 않는다', async () => {
    auth.me = { ...BASE }
    api.post = { ...POST, author: { id: null, name: '탈퇴한 회원' } }

    renderDetail()
    await screen.findByRole('heading', { name: POST.title })

    expect(screen.queryByRole('button', { name: '삭제' })).toBeNull()
  })

  it('확인 전에는 삭제하지 않고 취소하면 트리거로 초점이 돌아온다', async () => {
    renderDetail()
    const trigger = await screen.findByRole('button', { name: '삭제' })
    trigger.focus()

    fireEvent.click(trigger)
    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent(POST.title)
    expect(dialog).toHaveTextContent('되돌릴 수 없습니다')
    expect(document.body).toHaveAttribute('data-scroll-locked')
    expect(api.remove).not.toHaveBeenCalled()

    fireEvent.click(within(dialog).getByRole('button', { name: '취소' }))

    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    expect(trigger).toHaveFocus()
    expect(document.body).not.toHaveAttribute('data-scroll-locked')
    expect(api.remove).not.toHaveBeenCalled()
  })

  it('확인하면 한 번 삭제하고 성공 알림을 보존해 목록으로 replace 이동한다', async () => {
    renderDetail()
    const dialog = await openDeleteDialog()

    fireEvent.click(within(dialog).getByRole('button', { name: '삭제' }))

    expect(
      await screen.findByRole('heading', { name: '자유게시판' }),
    ).toBeVisible()
    expect(screen.getByRole('status')).toHaveTextContent(
      '게시글을 삭제했습니다.',
    )
    expect(screen.getByTestId('navigation')).toHaveTextContent('/posts:REPLACE')
    expect(api.remove).toHaveBeenCalledTimes(1)
    expect(api.remove).toHaveBeenCalledWith(POST.id)
  })

  it('확인을 빠르게 두 번 실행해도 DELETE는 한 번만 보낸다', async () => {
    let finish!: () => void
    api.remove.mockReturnValue(
      new Promise<void>((resolve) => {
        finish = resolve
      }),
    )
    renderDetail()
    const dialog = await openDeleteDialog()
    const action = within(dialog).getByRole('button', { name: '삭제' })

    fireEvent.click(action)
    fireEvent.click(action)

    expect(api.remove).toHaveBeenCalledTimes(1)
    finish()
    await screen.findByRole('heading', { name: '자유게시판' })
  })

  it.each([
    [new ApiError('FORBIDDEN', 403, '본인이 쓴 게시글만 삭제할 수 있습니다.')],
    [new ApiError('NOT_FOUND', 404, '게시글을 찾을 수 없습니다.')],
  ])('확정된 4xx 실패는 서버 사유를 알리고 상세에 남는다', async (error) => {
    api.remove.mockRejectedValue(error)
    renderDetail()
    const dialog = await openDeleteDialog()

    fireEvent.click(within(dialog).getByRole('button', { name: '삭제' }))

    const failure = await screen.findByRole('alert')
    expect(failure).toHaveTextContent('게시글을 삭제하지 못했습니다')
    expect(failure).toHaveTextContent(error.message)
    expect(screen.getByTestId('navigation')).toHaveTextContent('/posts/701')
    expect(screen.queryByRole('status')).toBeNull()
  })

  it.each([
    [new ApiError('NETWORK_ERROR', 0, '서버에 연결하지 못했습니다.')],
    [new ApiError('INVALID_RESPONSE', 500, '서버 응답을 해석하지 못했습니다.')],
  ])(
    '네트워크·5xx는 삭제 여부가 불확정임을 알리고 이동하지 않는다',
    async (error) => {
      api.remove.mockRejectedValue(error)
      renderDetail()
      const dialog = await openDeleteDialog()

      fireEvent.click(within(dialog).getByRole('button', { name: '삭제' }))

      const failure = await screen.findByRole('alert')
      expect(failure).toHaveTextContent('게시글 삭제 여부를 확인할 수 없습니다')
      expect(failure).toHaveTextContent('삭제가 반영되었을 수 있으니')
      expect(failure).toHaveTextContent('자유게시판 목록에서 확인')
      expect(screen.getByTestId('navigation')).toHaveTextContent('/posts/701')
      expect(screen.queryByRole('status')).toBeNull()
    },
  )

  it('세션 전이 오류는 공통 처리에 맡기고 중복 알림이나 이동을 만들지 않는다', async () => {
    api.remove.mockRejectedValue(
      new ApiError('SUSPENDED', 403, '정지된 계정입니다.'),
    )
    renderDetail()
    const dialog = await openDeleteDialog()

    fireEvent.click(within(dialog).getByRole('button', { name: '삭제' }))

    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.queryByRole('status')).toBeNull()
    expect(screen.getByTestId('navigation')).toHaveTextContent('/posts/701')
  })
})
