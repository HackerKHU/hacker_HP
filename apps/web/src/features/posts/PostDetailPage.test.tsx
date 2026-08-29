import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import type { PostDetail } from '@/api/posts'
import type { User } from '@/api/types'
import { SessionProvider } from '@/auth/session'
import { MemoryRouter, Route, Routes } from '@/test/TestRouter'
import { PostDetailPage } from './PostDetailPage'

/**
 * 게시글 상세.
 *
 * **이 파일의 핵심은 본문이 평문으로 그려지는가다** (#237 완료 조건, spec §2-1-8 MUST).
 * 전 부원이 자유 서술을 남기는 자리라 공지와 위험도가 다르다 — 서식을 허용하는 순간
 * 그 입력이 다른 부원의 브라우저에서 실행될 수 있는 표면이 생긴다.
 */

const api = vi.hoisted(() => ({ post: null as PostDetail | null }))

const POST: PostDetail = {
  id: 701,
  title: '이번 학기 스터디 모집합니다',
  content: '매주 수요일 저녁 7시입니다.\n관심 있으신 분 연락 주세요.',
  author: { id: 1, name: '홍길동' },
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

vi.mock('@/api/auth', () => ({
  getMe: () => Promise.resolve(BASE),
  logout: () => Promise.resolve(),
}))

function renderDetail() {
  return render(
    <MemoryRouter initialEntries={['/posts/701']}>
      <SessionProvider>
        <Routes>
          <Route path="/posts/:id" element={<PostDetailPage />} />
          <Route path="/posts" element={<h1>자유게시판</h1>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  api.post = POST
})

describe('게시글 상세', () => {
  it('제목·본문·작성자·작성일을 보여준다', async () => {
    renderDetail()

    expect(
      document.querySelector('[data-detail-surface="post"]'),
    ).toBeInTheDocument()
    expect(
      await screen.findByRole('heading', { name: POST.title }),
    ).toBeVisible()
    expect(screen.getByText(/매주 수요일 저녁 7시입니다/)).toBeVisible()
    expect(screen.getByText('홍길동')).toBeVisible()
  })

  /*
   * **#237 완료 조건 — 본문에 `<script>`나 HTML을 넣어도 글자 그대로 보인다.**
   *
   * React가 중괄호 안의 문자열을 텍스트 노드로 넣어 자동 이스케이프한다.
   * `dangerouslySetInnerHTML`이나 마크다운 렌더러가 들어오면 여기서 잡힌다.
   */
  it('본문의 HTML을 실행하지 않고 글자 그대로 보여준다', async () => {
    api.post = {
      ...POST,
      content: '<script>alert(1)</script><b>굵게</b>',
    }

    const { container } = renderDetail()
    await screen.findByRole('heading', { name: POST.title })

    // 글자 그대로 보인다.
    expect(
      screen.getByText('<script>alert(1)</script><b>굵게</b>'),
    ).toBeVisible()
    // 태그로 해석되지 않았다 — 요소가 만들어지지 않는다.
    expect(container.querySelector('script')).toBeNull()
    expect(container.querySelector('b')).toBeNull()
  })

  /* 줄바꿈은 살린다 — 평문이라 그것 말고는 서식이 없다. */
  it('줄바꿈을 살려 그린다', async () => {
    const { container } = renderDetail()
    await screen.findByRole('heading', { name: POST.title })

    const body = container.querySelector('.whitespace-pre-wrap')
    expect(body).not.toBeNull()
    expect(body?.textContent).toContain('\n')
  })

  /*
   * **작성자가 탈퇴한 글도 깨지지 않는다** (#237 완료 조건). 서버가 이름을 채우므로
   * 화면은 그 문구를 만들지 않고 그대로 그린다 (§2-1-8).
   */
  it('탈퇴한 회원의 글도 작성자 이름이 보인다', async () => {
    api.post = { ...POST, author: { id: null, name: '탈퇴한 회원' } }

    renderDetail()
    await screen.findByRole('heading', { name: POST.title })

    expect(screen.getByText('탈퇴한 회원')).toBeVisible()
  })

  /*
   * **수정·삭제 진입점이 없다.** API에 그 경로가 아예 없으므로(§3-2-5) 버튼을 만들면
   * 누를 수 없는 것을 보여주게 된다. 관리자 삭제는 후속이다 (#238) — 미리 만들지 않는다.
   */
  it('수정·삭제 버튼이 없다', async () => {
    renderDetail()
    await screen.findByRole('heading', { name: POST.title })

    expect(screen.queryByRole('button', { name: /삭제/ })).toBeNull()
    expect(screen.queryByRole('link', { name: /수정/ })).toBeNull()
  })

  it('없는 글이면 안내가 뜬다', async () => {
    api.post = null

    renderDetail()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '게시글을 찾을 수 없습니다',
    )
  })
})
