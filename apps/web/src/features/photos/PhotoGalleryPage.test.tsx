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

/**
 * `<dialog>` 대역.
 *
 * **jsdom은 `showModal()`을 구현하지 않는다.** 그냥 `vi.fn()`으로 두면 `open`이 서지 않아
 * 다이얼로그 안이 숨은 상태가 되고, 접근성 질의(`getByRole`)가 그 안을 못 본다 —
 * 실제 브라우저와 다른 결과가 나온다. **진짜가 하는 일 중 `open`을 세우는 것까지** 흉내낸다.
 *
 * 포커스 트랩·ESC·`::backdrop`은 브라우저가 주는 것이라 여기서 검증할 대상이 아니다.
 */
function stubDialog() {
  HTMLDialogElement.prototype.showModal = vi.fn(function (
    this: HTMLDialogElement,
  ) {
    this.open = true
  })
  HTMLDialogElement.prototype.close = vi.fn(function (this: HTMLDialogElement) {
    this.open = false
    this.dispatchEvent(new Event('close'))
  })
}

beforeEach(() => {
  stubDialog()
  api.rows = [photo(501, '2026 신입생 환영회'), photo(502, null)]
  api.calls = []
  api.removed = []
  api.total = 2
  api.totalPages = 1
  auth.me = BASE
})

describe('활동사진 갤러리', () => {
  /*
   * 화면 이름은 **갤러리**다 (2026-08-23). 담고 있는 것은 여전히 활동사진이고(spec §2-1-7)
   * 라우트도 `/photos`지만, 사용자에게 보이는 이름은 헤더 메뉴·제목·돌아가기 링크에서
   * 이 하나로 통일한다 — 화면마다 다른 이름으로 부르면 같은 곳인지 알 수 없다.
   */
  it('제목이 갤러리다', async () => {
    renderGallery()

    expect(await screen.findByRole('heading', { name: '갤러리' })).toBeVisible()
  })

  it('사진을 그리드로 보여준다', async () => {
    renderGallery()

    expect(await screen.findByText('2026 신입생 환영회')).toBeVisible()
    expect(screen.getAllByRole('listitem')).toHaveLength(2)
  })

  /*
   * **목록에는 썸네일을 쓴다** (계약 §3-2-5). 원본을 그리면 스무 장이 원본 크기로 내려와
   * 갤러리를 여는 것만으로 트래픽이 터진다. 원본은 눌렀을 때 오버레이가 보여준다 (#270).
   */
  it('목록에는 썸네일을 그린다', async () => {
    renderGallery()

    expect(await screen.findByAltText('2026 신입생 환영회')).toHaveAttribute(
      'src',
      '/landing/mt.jpg?thumb=501',
    )
  })

  /*
   * **갤러리를 떠나지 않는다** (#270). 한때 원본을 새 탭으로 열었는데, 사진 한 장을 보려고
   * 화면을 떠났다가 돌아와야 해 훑는 흐름이 매번 끊겼다. 링크가 아니라 버튼이어야 한다.
   */
  it('썸네일이 다른 주소로 가는 링크가 아니다', async () => {
    renderGallery()
    const image = await screen.findByAltText('2026 신입생 환영회')

    expect(image.closest('a')).toBeNull()
    expect(image.closest('button')).not.toBeNull()
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

  /*
   * **크게 보기** (#270).
   *
   * jsdom에는 `showModal()`이 없다 — `<dialog>`를 구현하지 않는다. 그래서 여는 동작만
   * 대역으로 세우고, **화면이 무엇을 넘기는가**를 본다: 원본 주소와 설명이다.
   * 포커스 트랩·ESC·`::backdrop`은 브라우저가 주는 것이라 여기서 검증할 대상이 아니다.
   */
  it('썸네일을 누르면 원본을 크게 보여준다', async () => {
    renderGallery()
    fireEvent.click(
      await screen.findByRole('button', {
        name: '2026 신입생 환영회 크게 보기',
      }),
    )

    expect(HTMLDialogElement.prototype.showModal).toHaveBeenCalled()
    // 목록은 썸네일이지만 크게 보는 자리는 원본이다.
    const dialog = document.querySelector('dialog') as HTMLDialogElement
    expect(dialog.querySelector('img')).toHaveAttribute(
      'src',
      '/landing/mt.jpg?full=501',
    )
  })

  /* 닫는 버튼이 있다. ESC와 바깥 클릭은 `<dialog>`가 맡아 jsdom에서 재현되지 않는다. */
  it('크게 보기에 닫기 버튼이 있다', async () => {
    renderGallery()
    fireEvent.click(
      await screen.findByRole('button', {
        name: '2026 신입생 환영회 크게 보기',
      }),
    )

    expect(screen.getByRole('button', { name: '닫기' })).toBeVisible()
  })

  /* 설명이 없는 사진도 열린다 — `aria-label`이 그때는 일반 문구가 된다. */
  it('설명이 없는 사진도 크게 볼 수 있다', async () => {
    renderGallery()

    expect(
      await screen.findByRole('button', { name: '사진 크게 보기' }),
    ).toBeVisible()
  })

  it('사진이 없으면 안내가 뜬다', async () => {
    api.rows = []
    api.total = 0
    api.totalPages = 0

    renderGallery()

    expect(await screen.findByText('등록된 사진이 없습니다.')).toBeVisible()
  })
})
