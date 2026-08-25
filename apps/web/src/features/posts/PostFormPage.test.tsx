import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import type { User } from '@/api/types'
import { SessionProvider } from '@/auth/session'
import { PostFormPage } from './PostFormPage'

/**
 * 글쓰기.
 *
 * 여기서 지키는 것은 **길이를 서버와 같은 방식으로 세는가**다. 서버가 코드 포인트로
 * 재므로(`CodePointSizeValidator`) `String.length`(UTF-16 단위)로 세면 이모지 하나가 2로
 * 잡혀 **서버는 받아주는 글을 화면이 먼저 막는다.**
 */

const api = vi.hoisted(() => ({
  created: [] as { title: string; content: string }[],
  /** 다음 등록을 실패시킨다. 서버가 거부했을 때의 화면을 보려면 필요하다. */
  failWith: null as unknown,
}))

vi.mock('@/api/posts', async (importOriginal) => {
  // 상수와 `countCodePoints`는 실제 것을 쓴다 — 화면이 그 값으로 센다.
  const actual = await importOriginal<typeof import('@/api/posts')>()
  return {
    ...actual,
    create: (body: { title: string; content: string }) => {
      if (api.failWith) return Promise.reject(api.failWith)
      api.created.push(body)
      return Promise.resolve({
        id: 801,
        title: body.title,
        content: body.content,
        author: { id: 1, name: '홍길동' },
        createdAt: '2026-08-01T09:00:00Z',
        updatedAt: '2026-08-01T09:00:00Z',
      })
    },
  }
})

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

function renderForm() {
  render(
    <MemoryRouter initialEntries={['/posts/new']}>
      <SessionProvider>
        <Routes>
          <Route path="/posts/new" element={<PostFormPage />} />
          <Route path="/posts/:id" element={<h1>게시글 상세</h1>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

async function fill(title: string, content: string) {
  fireEvent.change(await screen.findByLabelText('제목'), {
    target: { value: title },
  })
  fireEvent.change(screen.getByLabelText('내용'), {
    target: { value: content },
  })
}

beforeEach(() => {
  api.created = []
  api.failWith = null
})

describe('글쓰기', () => {
  it('제목과 내용을 보내고 쓴 글로 이동한다', async () => {
    renderForm()
    await fill('스터디 모집', '수요일 저녁입니다.')

    fireEvent.click(screen.getByRole('button', { name: '올리기' }))

    expect(
      await screen.findByRole('heading', { name: '게시글 상세' }),
    ).toBeVisible()
    expect(api.created).toEqual([
      { title: '스터디 모집', content: '수요일 저녁입니다.' },
    ])
  })

  it('비어 있으면 요청이 나가지 않는다', async () => {
    renderForm()
    await fill('  ', '   ')

    fireEvent.click(screen.getByRole('button', { name: '올리기' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '제목과 내용을 입력해주세요',
    )
    expect(api.created).toEqual([])
  })

  /*
   * **서버와 같은 방식으로 센다.** 이모지는 UTF-16으로 2단위지만 코드 포인트로는 1이다 —
   * `String.length`로 세면 200자 상한에 100자만 써도 걸린다.
   */
  it('이모지를 한 글자로 센다', async () => {
    renderForm()
    await fill('🎉🎉🎉', '내용')

    // UTF-16이면 6, 코드 포인트면 3이다.
    expect(screen.getByText('3/200')).toBeVisible()
  })

  /* 상한을 넘으면 제출 자체를 막는다 — 서버가 거부할 요청을 굳이 보내지 않는다. */
  it('제목이 상한을 넘으면 올리기가 잠긴다', async () => {
    renderForm()
    await fill('가'.repeat(201), '내용')

    expect(screen.getByRole('button', { name: '올리기' })).toBeDisabled()
    expect(screen.getByText('201/200')).toBeVisible()
  })

  it('상한 안이면 올리기가 열린다', async () => {
    renderForm()
    await fill('가'.repeat(200), '내용')

    expect(screen.getByRole('button', { name: '올리기' })).not.toBeDisabled()
  })

  /*
   * `maxLength`를 걸지 않는다. 그 속성은 **UTF-16 단위로 자르므로** 서버의 코드 포인트
   * 상한과 어긋난다 — 이모지가 섞이면 서버가 받아줄 글을 브라우저가 먼저 막는다.
   */
  it('입력 칸에 maxLength를 걸지 않는다', async () => {
    renderForm()

    expect(await screen.findByLabelText('제목')).not.toHaveAttribute(
      'maxLength',
    )
    expect(screen.getByLabelText('내용')).not.toHaveAttribute('maxLength')
  })

  /* 서버가 거부하면 이동하지 않고 입력을 그대로 둔다 — 무엇을 고쳐야 하는지 서버가 안다. */
  it('서버가 거부하면 사유를 띄우고 입력이 남는다', async () => {
    api.failWith = new ApiError(
      'VALIDATION_ERROR',
      400,
      '제목은 200자까지 쓸 수 있습니다.',
    )

    renderForm()
    await fill('제목', '내용')
    fireEvent.click(screen.getByRole('button', { name: '올리기' }))

    // 서버가 준 문구를 그대로 보여준다 — 무엇을 고쳐야 하는지는 서버가 안다.
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '제목은 200자까지 쓸 수 있습니다',
    )
    expect(screen.queryByRole('heading', { name: '게시글 상세' })).toBeNull()
    expect(screen.getByLabelText('제목')).toHaveValue('제목')
  })
})
