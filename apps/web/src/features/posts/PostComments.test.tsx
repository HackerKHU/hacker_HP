import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import type { PostComment } from '@/api/posts'
import type { User } from '@/api/types'
import { SessionProvider } from '@/auth/session'
import { MemoryRouter } from '@/test/TestRouter'
import { PostComments } from './PostComments'

/**
 * 댓글 (spec §3-2-5, 3-3 결정 23).
 *
 * 여기서 잠그는 것은 **정렬을 화면이 다시 하지 않는가**, **진입점이 권한과 정확히
 * 일치하는가**, **등록 뒤 폼을 비우고 목록을 다시 부르는가**다. 경로·메서드는
 * `src/api/posts.test.ts`가 mock 뒤쪽에서 따로 본다.
 */

const MINE = 1
const OTHER = 99

const BASE: User = {
  id: MINE,
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

function comment(
  id: number,
  content: string,
  authorId: number | null,
  createdAt: string,
): PostComment {
  return {
    id,
    content,
    author: {
      id: authorId,
      name:
        authorId === MINE
          ? '홍길동'
          : authorId === null
            ? '탈퇴한 회원'
            : '권승원',
    },
    createdAt,
    updatedAt: createdAt,
  }
}

/**
 * 서버가 오래된순으로 내려준 상태. **날짜가 뒤죽박죽인 채로 둔다** — 화면이 다시
 * 정렬하면 이 순서가 무너져 테스트가 깨진다. 서버 순서를 그대로 그리는 것이 계약이다.
 */
const SERVER_ORDER: PostComment[] = [
  comment(901, '첫 댓글', OTHER, '2026-08-31T09:00:00Z'),
  comment(902, '둘째 댓글', MINE, '2026-08-01T09:00:00Z'),
  comment(903, '셋째 댓글', null, '2026-09-01T09:00:00Z'),
]

const api = vi.hoisted(() => ({
  comments: [] as unknown[],
  listCalls: 0,
  listError: null as unknown,
  created: [] as { postId: number; content: string }[],
  createError: null as unknown,
  updated: [] as { postId: number; commentId: number; content: string }[],
  removed: [] as { postId: number; commentId: number }[],
}))

const auth = vi.hoisted(() => ({ me: null as User | null }))

vi.mock('@/api/posts', () => ({
  COMMENT_CONTENT_MAX: 2_000,
  countCodePoints: (text: string) => [...text].length,
  comments: (postId: number) => {
    api.listCalls += 1
    if (api.listError) return Promise.reject(api.listError)
    void postId
    return Promise.resolve(api.comments)
  },
  createComment: (postId: number, body: { content: string }) => {
    if (api.createError) return Promise.reject(api.createError)
    api.created.push({ postId, content: body.content })
    return Promise.resolve({})
  },
  updateComment: (
    postId: number,
    commentId: number,
    body: { content: string },
  ) => {
    api.updated.push({ postId, commentId, content: body.content })
    return Promise.resolve({})
  },
  removeComment: (postId: number, commentId: number) => {
    api.removed.push({ postId, commentId })
    return Promise.resolve()
  },
}))

vi.mock('@/api/auth', () => ({
  getMe: () => Promise.resolve(auth.me),
  logout: () => Promise.resolve(),
}))

function renderComments() {
  return render(
    <MemoryRouter>
      <SessionProvider>
        <PostComments postId={701} />
      </SessionProvider>
    </MemoryRouter>,
  )
}

/** 지금 그려진 댓글 본문 순서. 서버가 준 순서와 같아야 한다. */
function bodies(): string[] {
  return screen
    .getAllByRole('listitem')
    .map((item) => item.querySelector('p')?.textContent ?? '')
}

beforeEach(() => {
  api.comments = SERVER_ORDER.map((row) => ({ ...row }))
  api.listCalls = 0
  api.listError = null
  api.created = []
  api.createError = null
  api.updated = []
  api.removed = []
  auth.me = { ...BASE }
})

describe('게시글 댓글', () => {
  /*
   * **정렬을 화면이 다시 하지 않는다** (계약 §3-2-5 — 오래된순 고정). 픽스처의 날짜가
   * 일부러 뒤죽박죽이라, 화면이 정렬을 넣으면 이 단언이 깨진다.
   */
  it('서버가 준 순서를 그대로 그린다', async () => {
    renderComments()

    await screen.findByText('첫 댓글')
    expect(bodies()).toEqual(['첫 댓글', '둘째 댓글', '셋째 댓글'])
  })

  it('댓글이 없으면 안내를 띄우고 입력 폼은 남긴다', async () => {
    api.comments = []
    renderComments()

    expect(await screen.findByText(/아직 댓글이 없습니다/)).toBeVisible()
    expect(screen.getByRole('textbox', { name: '댓글 내용' })).toBeVisible()
  })

  /* **입력 폼은 목록 아래다** (#352 D2). 위에 두면 읽던 흐름과 쓰는 자리가 반대가 된다. */
  it('입력 폼이 목록보다 뒤에 있다', async () => {
    renderComments()
    await screen.findByText('첫 댓글')

    const last = screen.getAllByRole('listitem').at(-1) as HTMLElement
    const form = screen.getByRole('textbox', { name: '댓글 내용' })
    expect(
      last.compareDocumentPosition(form) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()
  })

  it('등록에 성공하면 폼을 비우고 목록을 다시 부른다', async () => {
    renderComments()
    await screen.findByText('첫 댓글')
    expect(api.listCalls).toBe(1)

    const input = screen.getByRole('textbox', { name: '댓글 내용' })
    // 원문 그대로 보낸다 — 서버가 본문을 다듬지 않는다 (§3-2-5).
    fireEvent.change(input, { target: { value: '  새 댓글\n' } })
    fireEvent.click(screen.getByRole('button', { name: '등록' }))

    await waitFor(() => {
      expect(api.created).toEqual([{ postId: 701, content: '  새 댓글\n' }])
    })
    await waitFor(() => {
      expect(api.listCalls).toBe(2)
    })
    expect(input).toHaveValue('')
  })

  it('빈 내용으로는 보내지 않고 안내만 띄운다', async () => {
    renderComments()
    await screen.findByText('첫 댓글')

    fireEvent.change(screen.getByRole('textbox', { name: '댓글 내용' }), {
      target: { value: '   ' },
    })
    fireEvent.click(screen.getByRole('button', { name: '등록' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '내용을 입력해주세요',
    )
    expect(api.created).toEqual([])
  })

  it('등록 실패는 알리고 쓰던 내용을 지우지 않는다', async () => {
    api.createError = new ApiError('VALIDATION_ERROR', 400, '내용이 깁니다.')
    renderComments()
    await screen.findByText('첫 댓글')

    const input = screen.getByRole('textbox', { name: '댓글 내용' })
    fireEvent.change(input, { target: { value: '보낼 댓글' } })
    fireEvent.click(screen.getByRole('button', { name: '등록' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '댓글을 등록하지 못했습니다',
    )
    expect(input).toHaveValue('보낼 댓글')
    expect(api.listCalls).toBe(1)
  })

  it('조회 실패는 알리고 같은 자리에서 재시도한다', async () => {
    api.listError = new Error('network')
    renderComments()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '댓글을 불러오지 못했습니다',
    )
    api.listError = null
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))

    expect(await screen.findByText('첫 댓글')).toBeVisible()
  })

  /*
   * **진입점이 권한과 정확히 일치해야 한다** (결정 23 D2). 노출 제어일 뿐 권한 통제가
   * 아니지만(§3-1-7), 어긋나면 부원이 눌러서 403을 받는 자리가 생긴다.
   */
  it('내 댓글에만 수정이 보이고 남의 댓글에는 아무 것도 없다', async () => {
    renderComments()
    await screen.findByText('첫 댓글')

    const [other, mine, withdrawn] = screen.getAllByRole('listitem')
    expect(within(other).queryByRole('button')).toBeNull()
    expect(within(withdrawn).queryByRole('button')).toBeNull()
    expect(within(mine).getByRole('button', { name: '수정' })).toBeVisible()
    expect(within(mine).getByRole('button', { name: '삭제' })).toBeVisible()
  })

  /** 관리자도 남의 댓글은 **고칠 수 없다** — 삭제만 열린다 (결정 23 D2). */
  it('활성 관리자에게는 남의 댓글에 삭제만 보인다', async () => {
    auth.me = { ...BASE, role: 'ADMIN' }
    renderComments()
    await screen.findByText('첫 댓글')

    const [other] = screen.getAllByRole('listitem')
    expect(within(other).getByRole('button', { name: '삭제' })).toBeVisible()
    expect(within(other).queryByRole('button', { name: '수정' })).toBeNull()
  })

  it('수정은 통째로 교체해 보내고 목록을 다시 부른다', async () => {
    renderComments()
    await screen.findByText('첫 댓글')

    const [, mine] = screen.getAllByRole('listitem')
    fireEvent.click(within(mine).getByRole('button', { name: '수정' }))

    const editor = screen.getByRole('textbox', { name: '댓글 수정' })
    // 기존 본문이 들어 있어야 "통째로 교체"가 지우는 편집이 되지 않는다.
    expect(editor).toHaveValue('둘째 댓글')
    fireEvent.change(editor, { target: { value: '고친 둘째 댓글' } })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(api.updated).toEqual([
        { postId: 701, commentId: 902, content: '고친 둘째 댓글' },
      ])
    })
    await waitFor(() => {
      expect(api.listCalls).toBe(2)
    })
    expect(
      screen.queryByRole('textbox', { name: '댓글 수정' }),
    ).not.toBeInTheDocument()
  })

  it('수정을 취소하면 아무 것도 보내지 않고 편집창을 닫는다', async () => {
    renderComments()
    await screen.findByText('첫 댓글')

    const [, mine] = screen.getAllByRole('listitem')
    fireEvent.click(within(mine).getByRole('button', { name: '수정' }))
    fireEvent.change(screen.getByRole('textbox', { name: '댓글 수정' }), {
      target: { value: '버릴 내용' },
    })
    fireEvent.click(screen.getByRole('button', { name: '취소' }))

    expect(
      screen.queryByRole('textbox', { name: '댓글 수정' }),
    ).not.toBeInTheDocument()
    expect(api.updated).toEqual([])
    expect(screen.getByText('둘째 댓글')).toBeVisible()
  })

  /* 되돌릴 수 없는 조작이라 확인 단계를 거친다 — 게시글 삭제와 같은 패턴이다. */
  it('삭제는 확인 창을 거친 뒤에만 보내고 목록을 다시 부른다', async () => {
    renderComments()
    await screen.findByText('첫 댓글')

    const [, mine] = screen.getAllByRole('listitem')
    fireEvent.click(within(mine).getByRole('button', { name: '삭제' }))

    const dialog = await screen.findByRole('alertdialog')
    // 무엇을 지우는지 본문으로 보여준다.
    expect(dialog).toHaveTextContent('둘째 댓글')
    expect(api.removed).toEqual([])

    fireEvent.click(within(dialog).getByRole('button', { name: '삭제' }))

    await waitFor(() => {
      expect(api.removed).toEqual([{ postId: 701, commentId: 902 }])
    })
    await waitFor(() => {
      expect(api.listCalls).toBe(2)
    })
  })
})
