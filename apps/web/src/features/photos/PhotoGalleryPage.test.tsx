import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
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
  /** 화면이 어떤 방향으로 보냈는지. 토글이 아니므로 방향이 곧 계약이다. */
  likes: [] as { id: number; liked: boolean }[],
  likeFails: false,
  /** 응답을 붙잡아 두는 문. 요청이 도는 동안의 화면을 보려면 끝나지 않는 순간이 필요하다. */
  likeGate: null as Promise<void> | null,
}))

function photo(
  id: number,
  caption: string | null,
  like: { count: number; mine: boolean } = { count: 0, mine: false },
): Photo {
  return {
    id,
    caption,
    url: `/landing/mt.jpg?full=${id}`,
    thumbnailUrl: `/landing/mt.jpg?thumb=${id}`,
    uploaderId: 2,
    uploaderName: '김관리',
    createdAt: '2026-08-01T09:00:00Z',
    likeCount: like.count,
    likedByMe: like.mine,
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
  setPhotoLike: (id: number, liked: boolean) => {
    if (api.likeFails) return Promise.reject(new Error('network'))
    api.likes.push({ id, liked })
    return api.likeGate ?? Promise.resolve()
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
  api.likes = []
  api.likeFails = false
  api.likeGate = null
  auth.me = BASE
})

describe('활동사진 갤러리', () => {
  it('장문 설명을 카드와 삭제 확인문에서 줄이되 원문을 보존한다', async () => {
    const longCaption = '활동사진의 아주 긴 설명'.repeat(20)
    api.rows = [photo(501, longCaption)]
    api.total = 1
    auth.me = { ...BASE, role: 'ADMIN' }
    renderGallery()

    const caption = await screen.findByText(longCaption)
    expect(caption.className).toContain('truncate')
    expect(caption).toHaveAttribute('title', longCaption)
    expect(caption.parentElement?.className).toContain('flex-1')

    fireEvent.click(screen.getByRole('button', { name: `${longCaption} 삭제` }))
    const dialog = await screen.findByRole('alertdialog')
    const dialogCaption = within(dialog).getByTitle(longCaption)
    expect(dialogCaption.className).toContain('truncate')
    expect(dialog).toHaveTextContent(longCaption)
  })

  it('크게 보기의 장문 설명은 화면 안에서 스크롤되고 원문을 보존한다', async () => {
    const longCaption = '크게 보는 활동사진의 아주 긴 설명'.repeat(20)
    api.rows = [photo(501, longCaption)]
    api.total = 1
    renderGallery()

    fireEvent.click(
      await screen.findByRole('button', { name: `${longCaption} 크게 보기` }),
    )
    const dialog = document.querySelector('dialog') as HTMLDialogElement
    const caption = dialog.querySelector(`[title="${longCaption}"]`)
    expect(caption?.className).toContain('max-h-24')
    expect(caption?.className).toContain('overflow-y-auto')
    expect(caption).toHaveTextContent(longCaption)
  })

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

  /*
   * 회귀 (#351) — **크게 보고 있는 사진을 id로 들면서 생긴 자리다.** 목록을 다시 읽는
   * 동안 `data`가 비어 다이얼로그가 닫히는데, 그 닫힘이 부모 상태에 돌아오지 않으면
   * id가 남는다. 그러면 같은 사진이 든 응답이 도착하는 순간 **사용자가 아무것도 하지
   * 않았는데 라이트박스가 다시 열린다.**
   */
  it('재조회로 닫힌 라이트박스는 같은 사진이 돌아와도 다시 열리지 않는다', async () => {
    api.totalPages = 2
    api.total = 25

    renderGallery()
    fireEvent.click(
      await screen.findByRole('button', {
        name: '2026 신입생 환영회 크게 보기',
      }),
    )
    const dialog = document.querySelector('dialog') as HTMLDialogElement
    expect(dialog.open).toBe(true)

    fireEvent.click(screen.getByRole('link', { name: '2페이지로 이동' }))

    await waitFor(() => {
      expect(api.calls.at(-1)?.page).toBe(1)
    })
    // 같은 사진이 이 페이지에도 있다. 그래도 다이얼로그는 닫힌 채여야 한다.
    await waitFor(() => {
      expect(screen.getAllByText('2026 신입생 환영회').length).toBeGreaterThan(
        0,
      )
    })
    expect(dialog.open).toBe(false)
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

/**
 * 좋아요 (#351).
 *
 * D1 — **버튼은 라이트박스에만 있고 그리드에는 개수만 있다.** D2 — **낙관적으로 먼저
 * 반영하고 실패하면 되돌린다.** 응답이 `204`라 최신 개수가 오지 않으므로 화면이 직접 센다
 * (계약 §3-2-5).
 *
 * **상태는 이 화면 하나가 들고 있다.** 라이트박스에 따로 두면 그리드 숫자와 갈린다 —
 * 아래 두 사례가 그 두 자리를 한 번에 본다.
 */
describe('갤러리 좋아요', () => {
  /**
   * 그리드 카드의 개수. 라이트박스 버튼과 같은 문구라 목록 안으로 좁혀 찾는다.
   *
   * **개수는 업로더·날짜 줄의 끝에 붙어 있다** (#384). 그 줄 전체가 한 문단이고 개수는
   * 마지막 조각이라, 앞부분을 모르는 채로 **끝이 "좋아요 N"인 문단**을 찾는다.
   */
  function gridCount(label: string): HTMLElement {
    return within(screen.getByRole('list')).getByText(
      (_, element) =>
        element?.tagName === 'P' &&
        (element.textContent?.replace(/\s+/g, ' ').trim().endsWith(label) ??
          false),
    )
  }

  it('그리드는 개수만 보여준다 — 0도 감추지 않고 버튼도 없다', async () => {
    api.rows = [
      photo(501, '환영회', { count: 3, mine: true }),
      photo(502, null),
    ]

    renderGallery()

    await screen.findByRole('list')
    expect(gridCount('좋아요 3')).toBeVisible()
    // 감추면 카드마다 이 줄의 폭이 달라져 그리드가 흔들린다.
    expect(gridCount('좋아요 0')).toBeVisible()
    /*
     * **캡션이 없는 카드에서도 개수가 마지막 줄이다** (#384). 개수를 따로 띄워 두면
     * 캡션 유무에 따라 세로 위치가 어긋난다 — 업로더 줄로 흡수한 이유가 이것이다.
     */
    const captionless = screen.getAllByRole('listitem')[1]
    const lines = captionless.querySelectorAll('p')
    expect(lines).toHaveLength(1)
    expect(lines[0].textContent?.replace(/\s+/g, ' ').trim()).toMatch(
      /좋아요 0$/,
    )
    // 훑다가 잘못 누르는 일이 없어야 한다 — 카드에 있는 버튼은 크게 보기뿐이다.
    expect(
      within(screen.getByRole('list')).queryByRole('button', {
        name: /좋아요/,
      }),
    ).toBeNull()
  })

  it.each([
    { mine: false, name: '좋아요 3', next: true, after: '좋아요 4' },
    { mine: true, name: '좋아요 3', next: false, after: '좋아요 2' },
  ])(
    'likedByMe=$mine에서 누르면 라이트박스와 그리드가 함께 바뀌고 $next를 보낸다',
    async ({ mine, name, next, after }) => {
      api.rows = [photo(501, '환영회', { count: 3, mine })]
      api.total = 1

      renderGallery()
      fireEvent.click(
        await screen.findByRole('button', { name: '환영회 크게 보기' }),
      )

      const button = screen.getByRole('button', { name })
      expect(button).toHaveAttribute('aria-pressed', String(mine))

      fireEvent.click(button)

      // 응답을 기다리지 않고 먼저 바뀐다 — 그게 낙관적 업데이트다.
      expect(screen.getByRole('button', { name: after })).toHaveAttribute(
        'aria-pressed',
        String(next),
      )
      // 라이트박스에서 누른 결과가 뒤에 있는 카드 숫자에도 그대로 반영된다.
      expect(gridCount(after)).toBeVisible()
      await waitFor(() => {
        expect(api.likes).toEqual([{ id: 501, liked: next }])
      })
    },
  )

  /*
   * 연타하면 `POST`와 `DELETE`가 순서를 바꿔 도착해 서버 상태와 화면이 갈린다. 응답이
   * `204`라 화면이 직접 세는 값도 함께 어긋난다 — 도는 동안 잠근다.
   */
  it('요청이 도는 동안 잠겨 연타해도 한 번만 보낸다', async () => {
    api.rows = [photo(501, '환영회', { count: 3, mine: false })]
    api.total = 1
    let release = () => {}
    api.likeGate = new Promise<void>((resolve) => {
      release = resolve
    })

    renderGallery()
    fireEvent.click(
      await screen.findByRole('button', { name: '환영회 크게 보기' }),
    )
    fireEvent.click(screen.getByRole('button', { name: '좋아요 3' }))

    expect(screen.getByRole('button', { name: /좋아요/ })).toBeDisabled()
    // **잠금은 눈에 보이는 것으로 끝나지 않는다.** 두 번째 클릭이 실제로 나가면 `POST`와
    // `DELETE`가 순서를 바꿔 도착한다 — 요청 수를 센다.
    fireEvent.click(screen.getByRole('button', { name: /좋아요/ }))
    expect(api.likes).toHaveLength(1)

    release()
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /좋아요/ })).toBeEnabled()
    })
    expect(api.likes).toEqual([{ id: 501, liked: true }])
  })

  /*
   * **양방향을 함께 본다.** 누르기만 되돌리고 취소를 빠뜨리면, 취소에 실패한 사진이
   * 화면에서만 떼어진 채 남는다. 잠금이 `finally`에서 풀리는지도 여기서 잰다 — 실패가
   * 버튼을 영영 잠그면 다시 시도할 길이 없다.
   */
  it.each([
    { mine: false, after: '좋아요 4' },
    { mine: true, after: '좋아요 2' },
  ])(
    'likedByMe=$mine에서 실패하면 두 자리 모두 되돌리고 버튼을 다시 연다',
    async ({ mine, after }) => {
      api.rows = [photo(501, '환영회', { count: 3, mine })]
      api.total = 1
      api.likeFails = true

      renderGallery()
      fireEvent.click(
        await screen.findByRole('button', { name: '환영회 크게 보기' }),
      )
      fireEvent.click(screen.getByRole('button', { name: '좋아요 3' }))

      expect(await screen.findByRole('alert')).toHaveTextContent(
        '좋아요를 바꾸지 못했습니다',
      )
      expect(screen.queryByRole('button', { name: after })).toBeNull()
      const button = screen.getByRole('button', { name: '좋아요 3' })
      expect(button).toHaveAttribute('aria-pressed', String(mine))
      expect(button).toBeEnabled()
      expect(gridCount('좋아요 3')).toBeVisible()
    },
  )

  /*
   * **응답은 그 사진에만 닿아야 한다.** 목록을 인덱스로 갈아 끼우거나 라이트박스가 제
   * 상태를 들면, A를 누르고 닫은 뒤 연 B의 숫자가 A의 응답에 흔들린다.
   */
  it.each([
    // 성공하면 A의 낙관적 +1이 그대로 남고, 실패하면 누르기 전 값으로 돌아온다.
    { kind: '성공', fails: false, afterA: '좋아요 4' },
    { kind: '실패', fails: true, afterA: '좋아요 3' },
  ])(
    'A의 좋아요 $kind 응답이 그 사이 연 B를 건드리지 않는다',
    async ({ fails, afterA }) => {
      api.rows = [
        photo(501, 'A 사진', { count: 3, mine: false }),
        photo(502, 'B 사진', { count: 7, mine: true }),
      ]
      api.total = 2
      let settle = () => {}
      api.likeGate = new Promise<void>((resolve, reject) => {
        settle = () => (fails ? reject(new Error('network')) : resolve())
      })

      renderGallery()
      fireEvent.click(
        await screen.findByRole('button', { name: 'A 사진 크게 보기' }),
      )
      fireEvent.click(screen.getByRole('button', { name: '좋아요 3' }))
      // A의 응답을 붙잡은 채 닫고 B를 연다.
      fireEvent.click(screen.getByRole('button', { name: '닫기' }))
      await waitFor(() => {
        expect(
          screen.getByRole('button', { name: 'B 사진 크게 보기' }),
        ).toBeVisible()
      })
      fireEvent.click(screen.getByRole('button', { name: 'B 사진 크게 보기' }))

      settle()
      await waitFor(() => {
        expect(gridCount(afterA)).toBeVisible()
      })
      const button = screen.getByRole('button', { name: '좋아요 7' })
      expect(button).toHaveAttribute('aria-pressed', 'true')
      expect(gridCount('좋아요 7')).toBeVisible()
      expect(api.likes).toEqual([{ id: 501, liked: true }])
    },
  )
})
