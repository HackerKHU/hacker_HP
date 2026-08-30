import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Photo } from '@/api/photos'
import type { User } from '@/api/types'
import { SessionProvider } from '@/auth/session'
import { MemoryRouter, useLocation } from '@/test/TestRouter'
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
  removeError: null as unknown,
  listError: null as unknown,
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
    if (api.listError) return Promise.reject(api.listError)
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
    if (api.removeError) return Promise.reject(api.removeError)
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

function LocationProbe() {
  const { search } = useLocation()
  return <div data-testid="search">{search}</div>
}

function renderGallery(path = '/photos') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <PhotoGalleryPage />
        <LocationProbe />
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
  api.removeError = null
  api.listError = null
  auth.me = BASE
})

describe('활동사진 갤러리', () => {
  it('조회 상태와 무관하게 그리드 surface와 pager 자리를 유지한다', async () => {
    renderGallery()
    expect(
      document.querySelector('[data-list-surface="photos"]'),
    ).toBeInTheDocument()
    expect(
      document.querySelector('[data-pager-slot="true"]'),
    ).toBeInTheDocument()
    await screen.findAllByRole('button', { name: /크게 보기/ })
    expect(
      document.querySelector('[data-list-surface="photos"]'),
    ).toBeInTheDocument()
  })

  it('조회 실패 surface에서 재시도해 그리드를 복구한다', async () => {
    api.listError = new Error('network')
    renderGallery()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '사진을 불러오지 못했습니다',
    )
    expect(screen.getAllByRole('alert')).toHaveLength(1)
    expect(
      document.querySelector('[data-live-alert-viewport="true"]'),
    ).not.toBeInTheDocument()
    api.listError = null
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(await screen.findByText('2026 신입생 환영회')).toBeVisible()
    expect(
      document.querySelector('[data-pager-slot="true"]'),
    ).toBeInTheDocument()
  })
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

  it('페이지 번호를 직접 누르면 1-based 표시와 0-based URL·조회를 함께 바꾼다', async () => {
    api.totalPages = 20

    renderGallery('/photos?page=9')

    await screen.findByText('2026 신입생 환영회')
    expect(
      screen.getByRole('link', { name: '10페이지로 이동', current: 'page' }),
    ).toBeInTheDocument()
    expect(
      document.querySelectorAll(
        '[data-pager-mobile-visible="true"] [data-slot="pagination-ellipsis"]',
      ),
    ).toHaveLength(2)
    expect(
      document.querySelectorAll(
        '[data-pager-desktop-visible="true"] [data-slot="pagination-ellipsis"]',
      ),
    ).toHaveLength(2)
    fireEvent.click(screen.getByRole('link', { name: '12페이지로 이동' }))

    await waitFor(() => expect(api.calls.at(-1)?.page).toBe(11))
    expect(screen.getByTestId('search')).toHaveTextContent('?page=11')
    expect(
      screen.getByRole('link', { name: '12페이지로 이동', current: 'page' }),
    ).toBeInTheDocument()
  })

  it.each([
    ['/photos', 0, '이전 페이지로 이동'],
    ['/photos?page=19', 19, '다음 페이지로 이동'],
  ] as const)(
    '경계 %s에서 비활성 방향 링크를 눌러도 URL과 조회를 바꾸지 않는다',
    async (path, page, label) => {
      api.totalPages = 20
      renderGallery(path)
      await screen.findByText('2026 신입생 환영회')
      const calls = [...api.calls]
      const search = screen.getByTestId('search').textContent

      const boundary = screen.getByRole('link', { name: label })
      expect(boundary).toHaveAttribute('aria-disabled', 'true')
      fireEvent.click(boundary)

      expect(screen.getByTestId('search').textContent).toBe(search)
      expect(api.calls).toEqual(calls)
      expect(api.calls.at(-1)?.page).toBe(page)
    },
  )

  it('1페이지 번호는 page 파라미터를 지운다', async () => {
    api.totalPages = 3
    renderGallery('/photos?page=2')
    await screen.findByText('2026 신입생 환영회')

    fireEvent.click(screen.getByRole('link', { name: '1페이지로 이동' }))

    await waitFor(() => expect(api.calls.at(-1)?.page).toBe(0))
    expect(screen.getByTestId('search')).toHaveTextContent('')
  })

  /*
   * **#60 완료 조건 — 삭제는 `ADMIN`에게만 보인다.** 노출 제어일 뿐 권한 통제가 아니지만
   * (§3-1-7), 부원에게 누를 수 없는 버튼을 보여줄 이유도 없다.
   */
  it('일반 부원에게는 삭제·업로드 진입점이 없다', async () => {
    renderGallery()
    await screen.findByText('2026 신입생 환영회')

    expect(screen.queryByRole('link', { name: '업로드' })).toBeNull()
    expect(screen.queryByRole('button', { name: /삭제/ })).toBeNull()
  })

  it('ADMIN에게는 삭제·업로드 진입점이 보인다', async () => {
    auth.me = { ...BASE, role: 'ADMIN' }

    renderGallery()
    await screen.findByText('2026 신입생 환영회')

    expect(screen.getByRole('link', { name: '업로드' })).toHaveAttribute(
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
    expect(screen.getByRole('status')).toHaveTextContent('사진을 삭제했습니다.')
  })

  it('삭제 실패는 fixed error alert로 알리고 사진을 유지한다', async () => {
    auth.me = { ...BASE, role: 'ADMIN' }
    api.removeError = new Error('network')
    renderGallery()
    await screen.findByText('2026 신입생 환영회')

    fireEvent.click(
      screen.getByRole('button', { name: '2026 신입생 환영회 삭제' }),
    )
    fireEvent.click(await screen.findByRole('button', { name: '삭제' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('사진을 삭제하지 못했습니다')
    expect(alert.closest('[data-live-alert-viewport="true"]')).not.toBeNull()
    expect(screen.getByText('2026 신입생 환영회')).toBeVisible()
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

  /* 닫는 버튼이 있다. ESC와 포커스 트랩은 `<dialog>`가 맡아 jsdom에서 재현되지 않는다. */
  it('크게 보기에 닫기 버튼이 있다', async () => {
    renderGallery()
    fireEvent.click(
      await screen.findByRole('button', {
        name: '2026 신입생 환영회 크게 보기',
      }),
    )

    expect(screen.getByRole('button', { name: '닫기' })).toBeVisible()
  })

  /*
   * **바깥 클릭 닫기는 우리 코드다** (#270 검수). `<dialog>`가 주지 않는 동작이고,
   * `::backdrop`은 화면을 채운 안쪽 칸에 가려 누를 자리가 없다. 그래서 그 칸 자신이
   * 바깥 역할을 하는데, **이건 브라우저 기능이 아니라 평범한 클릭 핸들러라 여기서 본다.**
   *
   * 사진을 누를 때 닫히면 안 되는 것이 짝이다. 하나만 보면 "무엇을 눌러도 닫힌다"와
   * 구분되지 않아 통과한다.
   */
  it('사진 바깥을 누르면 닫히고 사진을 누르면 닫히지 않는다', async () => {
    renderGallery()
    fireEvent.click(
      await screen.findByRole('button', {
        name: '2026 신입생 환영회 크게 보기',
      }),
    )

    const dialog = document.querySelector('dialog') as HTMLDialogElement
    const outside = dialog.querySelector('div') as HTMLElement
    const image = dialog.querySelector('img') as HTMLElement

    // 사진은 안쪽이다. 누른다고 닫히면 확대해서 보는 일 자체가 안 된다.
    fireEvent.click(image)
    expect(dialog.open).toBe(true)

    fireEvent.click(outside)
    await waitFor(() => {
      expect(dialog.open).toBe(false)
    })
  })

  /*
   * 닫은 뒤 부모 상태도 풀려야 다음 사진이 열린다. `close` 이벤트를 `onClose`로
   * 돌려주지 않으면 다이얼로그만 닫히고 `photo`가 남아 두 번째 클릭이 먹지 않는다.
   */
  it('닫은 뒤에 다른 사진을 다시 열 수 있다', async () => {
    renderGallery()
    fireEvent.click(
      await screen.findByRole('button', {
        name: '2026 신입생 환영회 크게 보기',
      }),
    )

    const dialog = document.querySelector('dialog') as HTMLDialogElement
    fireEvent.click(screen.getByRole('button', { name: '닫기' }))
    await waitFor(() => {
      expect(dialog.open).toBe(false)
    })

    fireEvent.click(screen.getByRole('button', { name: '사진 크게 보기' }))
    await waitFor(() => {
      expect(dialog.open).toBe(true)
    })
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
