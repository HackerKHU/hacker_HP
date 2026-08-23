import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Photo } from '@/api/photos'
import type { User } from '@/api/types'
import { SessionProvider } from '@/auth/session'
import { PhotoGalleryPage } from './PhotoGalleryPage'

/**
 * 활동사진 갤러리.
 *
 * 여기서 지키는 것은 **#60 완료 조건** — 최신순 그리드와 페이지네이션, 그리고 **삭제가
 * `ADMIN`에게만 보이는가**다. 정렬은 서버가 고정하므로 화면이 다시 정렬하지 않는 것도 본다.
 */

const api = vi.hoisted(() => ({
  rows: [] as Photo[],
  calls: [] as { page?: number; size?: number }[],
  removed: [] as number[],
  total: 0,
  totalPages: 1,
}))

function photo(id: number, caption: string | null): Photo {
  return {
    id,
    caption,
    url: `/landing/mt.jpg?full=${id}`,
    thumbnailUrl: `/landing/mt.jpg?thumb=${id}`,
    uploaderId: 2,
    uploaderName: '김관리',
    createdAt: '2026-08-01T09:00:00Z',
  }
}

vi.mock('@/api/photos', () => ({
  list: (query: { page?: number; size?: number }) => {
    api.calls.push(query)
    return Promise.resolve({
      content: api.rows,
      page: {
        size: 20,
        number: query.page ?? 0,
        totalElements: api.total,
        totalPages: api.totalPages,
      },
    })
  },
  remove: (id: number) => {
    api.removed.push(id)
    api.rows = api.rows.filter((row) => row.id !== id)
    api.total -= 1
    return Promise.resolve()
  },
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

const auth = vi.hoisted(() => ({ me: null as unknown }))

vi.mock('@/api/auth', () => ({
  getMe: () => Promise.resolve(auth.me),
  logout: () => Promise.resolve(),
}))

function renderGallery(path = '/photos') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <PhotoGalleryPage />
      </SessionProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  api.rows = [photo(501, '2026 신입생 환영회'), photo(502, null)]
  api.calls = []
  api.removed = []
  api.total = 2
  api.totalPages = 1
  auth.me = BASE
})

describe('활동사진 갤러리', () => {
  it('사진을 그리드로 보여준다', async () => {
    renderGallery()

    expect(await screen.findByText('2026 신입생 환영회')).toBeVisible()
    expect(screen.getAllByRole('listitem')).toHaveLength(2)
  })

  /*
   * **목록에는 썸네일을 쓴다** (계약 §3-2-5). 원본을 그리면 스무 장이 원본 크기로 내려와
   * 갤러리를 여는 것만으로 트래픽이 터진다. 원본은 눌렀을 때 새 탭으로 연다.
   */
  it('썸네일을 그리고 원본은 링크로 둔다', async () => {
    renderGallery()

    const image = await screen.findByAltText('2026 신입생 환영회')
    expect(image).toHaveAttribute('src', '/landing/mt.jpg?thumb=501')
    expect(image.closest('a')).toHaveAttribute(
      'href',
      '/landing/mt.jpg?full=501',
    )
  })

  /*
   * **설명이 없으면 `alt`를 비운다.** 없는 설명을 지어내 채우면 스크린리더가 사진마다
   * 같은 말을 반복해 읽는다 — 장식으로 두는 편이 낫다.
   */
  it('설명이 없는 사진은 alt가 비어 있다', async () => {
    const { container } = renderGallery()
    await screen.findByText('2026 신입생 환영회')

    /*
     * `alt=""`인 이미지는 접근성 트리에서 장식으로 빠져 `getByRole('img')`로 찾히지
     * 않는다 — **그것이 바로 여기서 원하는 결과다.** 그래서 DOM에서 직접 센다.
     */
    const alts = [...container.querySelectorAll('img')].map((image) =>
      image.getAttribute('alt'),
    )
    expect(alts).toEqual(['2026 신입생 환영회', ''])
  })

  /*
   * **정렬 선택지가 없다** (spec §2-1-7 — 서버가 최신순으로 고정한다). 화면이 정렬을
   * 보내기 시작하면 서버 순서를 덮어쓰는 코드가 하나 더 생긴다.
   */
  it('정렬을 고르지 않고 페이지 조건만 보낸다', async () => {
    renderGallery()
    await screen.findByText('2026 신입생 환영회')

    expect(screen.queryByLabelText('정렬')).toBeNull()
    expect(Object.keys(api.calls[0])).toEqual(['page', 'size'])
  })

  /* 주소의 페이지를 그대로 조회에 싣는다 — 새로고침·링크 공유에 살아남아야 한다. */
  it('주소의 page를 조회에 싣는다', async () => {
    api.totalPages = 3

    renderGallery('/photos?page=2')

    await screen.findByText('2026 신입생 환영회')
    expect(api.calls[0].page).toBe(2)
  })

  /*
   * **#60 완료 조건 — 삭제는 `ADMIN`에게만 보인다.** 노출 제어일 뿐 권한 통제가 아니지만
   * (§3-1-7), 부원에게 누를 수 없는 버튼을 보여줄 이유도 없다.
   */
  it('일반 부원에게는 삭제·업로드 진입점이 없다', async () => {
    renderGallery()
    await screen.findByText('2026 신입생 환영회')

    expect(screen.queryByRole('link', { name: '사진 올리기' })).toBeNull()
    expect(screen.queryByRole('button', { name: /삭제/ })).toBeNull()
  })

  it('ADMIN에게는 삭제·업로드 진입점이 보인다', async () => {
    auth.me = { ...BASE, role: 'ADMIN' }

    renderGallery()
    await screen.findByText('2026 신입생 환영회')

    expect(screen.getByRole('link', { name: '사진 올리기' })).toHaveAttribute(
      'href',
      '/admin/photos/new',
    )
    expect(
      screen.getByRole('button', { name: '2026 신입생 환영회 삭제' }),
    ).toBeVisible()
  })

  /* 삭제는 확인 창을 거친다. 되돌릴 수 없는 조작이다. */
  it('확인 창에서 삭제를 누르면 그 사진만 지운다', async () => {
    auth.me = { ...BASE, role: 'ADMIN' }

    renderGallery()
    await screen.findByText('2026 신입생 환영회')

    fireEvent.click(
      screen.getByRole('button', { name: '2026 신입생 환영회 삭제' }),
    )
    fireEvent.click(await screen.findByRole('button', { name: '삭제' }))

    await waitFor(() => {
      expect(api.removed).toEqual([501])
    })
  })

  it('사진이 없으면 안내가 뜬다', async () => {
    api.rows = []
    api.total = 0
    api.totalPages = 0

    renderGallery()

    expect(await screen.findByText('등록된 사진이 없습니다.')).toBeVisible()
  })
})
