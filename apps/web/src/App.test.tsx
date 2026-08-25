import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { ApiError } from './api/client'
import type { User } from './api/types'
import { SessionProvider, useSession } from './auth/session'

const auth = vi.hoisted(() => ({
  me: (): Promise<User> =>
    Promise.reject(new Error('테스트가 지정하지 않았다')),
  logout: (): Promise<void> => Promise.resolve(),
  listRejects: null as unknown,
}))

vi.mock('./api/auth', () => ({
  getMe: () => auth.me(),
  logout: () => auth.logout(),
}))

// 이 파일은 라우트 가드와 헤더만 본다. 공지 화면이 실제 요청을 내보내면
// 로딩 실패 alert가 생겨 로그아웃 alert와 섞인다.
vi.mock('./api/notices', () => ({
  list: () =>
    auth.listRejects
      ? Promise.reject(auth.listRejects)
      : Promise.resolve({
          content: [],
          page: { size: 10, number: 0, totalElements: 0, totalPages: 0 },
        }),
  get: () => Promise.reject(new Error('이 파일에서는 쓰지 않는다')),
  togglePin: () => Promise.reject(new Error('이 파일에서는 쓰지 않는다')),
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

/** 지금 주소를 화면에 드러내 이동 결과를 단언할 수 있게 한다. */
function Address() {
  const { pathname } = useLocation()
  return <div data-testid="pathname">{pathname}</div>
}

/** 화면이 아직 없어서, 보호 API가 403을 주는 상황을 이 버튼으로 대신 일으킨다. */
function ReportError({ error }: { error: unknown }) {
  const { reportApiError } = useSession()
  return (
    <button type="button" onClick={() => reportApiError(error)}>
      오류 발생
    </button>
  )
}

function renderAt(path: string, extra?: React.ReactNode) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <Address />
        {extra}
        <App />
      </SessionProvider>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  auth.me = () =>
    Promise.reject(new ApiError('UNAUTHENTICATED', 401, '로그인이 필요합니다.'))
  auth.logout = () => Promise.resolve()
  auth.listRejects = null
})

/** 메뉴 링크 이름 목록. 플레이스홀더 화면 제목과 겹치므로 범위를 nav 안으로 좁힌다. */
function menuLabels() {
  const nav = screen.getByRole('navigation', { name: '주요 메뉴' })
  return within(nav)
    .queryAllByRole('link')
    .map((link) => link.textContent)
}

describe('라우트 가드', () => {
  it('PENDING 사용자가 보호 라우트에 가면 대기중 안내로 되돌린다', async () => {
    auth.me = () =>
      Promise.resolve({ ...BASE, status: 'PENDING', approvedAt: null })

    renderAt('/notices')

    expect(
      await screen.findByRole('heading', { name: '승인 대기 중' }),
    ).toBeInTheDocument()
  })

  it('ACTIVE USER가 관리자 라우트에 가면 차단하고 부원 홈으로 되돌린다', async () => {
    auth.me = () => Promise.resolve(BASE)

    renderAt('/admin/members')

    expect(
      await screen.findByRole('heading', { name: '공지사항' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '회원 관리' })).toBeNull()
  })

  // 회귀 — 정지 계정을 세션에 넣으면 homePath → RequireActive → GuestOnly가
  // 서로를 밀며 무한히 돈다(Maximum update depth exceeded).
  it('getMe가 SUSPENDED 사용자를 주면 세션을 만들지 않고 순환 없이 로그인 화면에 닿는다', async () => {
    auth.me = () => Promise.resolve({ ...BASE, status: 'SUSPENDED' })

    renderAt('/notices')

    expect(
      await screen.findByRole('heading', { name: '로그인' }),
    ).toBeInTheDocument()
  })

  /*
   * 회귀 — 가입도 구글 버튼 하나로 하므로 `/signup`은 없다(2-1 §2-1-9, 3-3 결정 13).
   * **저장된 링크로 들어오면 로그인으로 보낸다.** wildcard에 맡기면 랜딩으로 가는데,
   * 가입하러 온 사람이 길을 다시 찾아야 한다.
   */
  it('없어진 /signup으로 들어오면 로그인 화면으로 보낸다', async () => {
    auth.me = () =>
      Promise.reject(
        new ApiError('UNAUTHENTICATED', 401, '로그인이 필요합니다.'),
      )

    renderAt('/signup')

    expect(
      await screen.findByRole('heading', { name: '로그인' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '가입 신청' })).toBeNull()
  })

  // 회귀 — 세션 도중 관리자가 정지시키면(#31) 이후 보호 API가 403 SUSPENDED로 실패한다.
  // 이 코드를 무시하면 ACTIVE 세션이 남아 화면은 열려 있고 요청만 전부 실패한다.
  it('보호 API가 403 SUSPENDED를 주면 ACTIVE 세션을 정리하고 로그인 화면으로 보낸다', async () => {
    auth.me = () => Promise.resolve(BASE)

    renderAt(
      '/notices',
      <ReportError
        error={new ApiError('SUSPENDED', 403, '이용이 정지된 계정입니다.')}
      />,
    )

    expect(
      await screen.findByRole('heading', { name: '공지사항' }),
    ).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '오류 발생' }))

    expect(
      await screen.findByRole('heading', { name: '로그인' }),
    ).toBeInTheDocument()
  })
})

/**
 * **`homePath()`가 돌려주는 경로가 실재하는 라우트인지** 확인한다.
 *
 * 이 검사가 없어서 회귀가 났다. `/admin/notices` 라우트를 지우면서 `homePath()`가 계속
 * 그 경로를 돌려줬고, wildcard가 `/`(공개 랜딩)로 되돌려 **관리자가 로그인해도 앱에
 * 들어가지 못했다.** 죽은 경로를 돌려주면 여기서 잡힌다 — 도착지가 랜딩이 되기 때문이다.
 *
 * `homePath()`를 직접 부르지 않고 **`/login`에서 `GuestOnly`가 태우는 실제 경로**를 본다.
 * 함수 반환값만 비교하면 그 문자열이 라우트로 존재하는지는 여전히 아무도 확인하지 않는다.
 */
describe('로그인 후 도착 경로', () => {
  it.each([
    ['ADMIN', { ...BASE, role: 'ADMIN' as const }, '/notices', '공지사항'],
    ['USER', BASE, '/notices', '공지사항'],
    [
      'PENDING',
      { ...BASE, status: 'PENDING' as const, approvedAt: null },
      '/pending',
      '승인 대기 중',
    ],
  ])('%s는 %s로 간다', async (_label, user, expected, heading) => {
    auth.me = () => Promise.resolve(user)

    renderAt('/login')

    expect(
      await screen.findByRole('heading', { name: heading }),
    ).toBeInTheDocument()
    expect(screen.getByTestId('pathname')).toHaveTextContent(expected)
  })
})

/*
 * **옛 즐겨찾기 주소를 살린다** (#261). 그 화면이 자료게시판의 토글로 접혔으므로,
 * 주소를 공유했거나 북마크해 둔 사람이 빈 화면을 보면 안 된다.
 */
describe('옛 주소', () => {
  it('/bookmarks로 들어오면 자료게시판의 즐겨찾기 상태로 보낸다', async () => {
    auth.me = () => Promise.resolve(BASE)

    renderAt('/bookmarks')

    await screen.findByRole('heading', { name: '자료게시판' })
    expect(screen.getByTestId('pathname')).toHaveTextContent('/notes')
  })
})

describe('헤더 메뉴 노출', () => {
  it('ADMIN에게는 회원 관리가 보인다', async () => {
    auth.me = () => Promise.resolve({ ...BASE, role: 'ADMIN' })

    renderAt('/admin/members')
    await screen.findByRole('heading', { name: '회원 관리' })

    /*
     * 자료 메뉴는 부원 것과 같고 **회원 관리만 더 붙는다** (spec §3-1-3 매트릭스 —
     * 자료·즐겨찾기는 USER·ADMIN 모두 `O`). 관리자에게만 보이는 것은 회원 관리뿐이다.
     */
    expect(menuLabels()).toEqual([
      '공지사항',
      '자료게시판',
      '갤러리',
      '자유 게시판',
      '회원 관리',
    ])
    /*
     * 목록형 "공지 관리" 화면은 없다 (spec §2-1-9). 라우트도 메뉴도 두지 않는다 —
     * 작성·수정은 /admin/notices/new·/edit이 맡고 고정 토글은 공지 목록에 있다.
     * 무심코 되살리면 여기서 잡힌다.
     */
    expect(menuLabels()).not.toContain('공지 관리')
  })

  /*
   * 서버 응답도 신뢰 경계다 (`client.ts` 주석). 계약에 없는 `role`이 오면 — 특히
   * `__proto__`처럼 **선언한 적 없는데 값을 돌려주는 프로토타입 키**면 — 메뉴 표를 직접
   * 인덱싱하는 코드는 `Object.prototype`을 받아 `.map`에서 죽는다. 헤더가 죽으면 앱
   * 전체가 죽는다. 메뉴가 비는 쪽으로 떨어져야 한다.
   */
  it('계약에 없는 role이 와도 헤더가 죽지 않는다', async () => {
    // 계약 위반을 흉내내는 자리라 캐스트가 필요하다. 타입은 이 상황을 막지 못한다.
    auth.me = () =>
      Promise.resolve({ ...BASE, role: '__proto__' } as unknown as User)

    renderAt('/notices')

    expect(
      await screen.findByRole('heading', { name: '공지사항' }),
    ).toBeInTheDocument()
    expect(menuLabels()).toEqual([])
  })

  it('ACTIVE USER에게는 부원 메뉴만 보이고 관리 메뉴는 없다', async () => {
    auth.me = () => Promise.resolve(BASE)

    renderAt('/notices')
    await screen.findByRole('heading', { name: '공지사항' })

    // 자료·즐겨찾기는 `ACTIVE`면 누구나 쓴다 (spec §3-1-3). 관리자 전용이 아니다.
    /*
     * 자료는 메뉴 하나다 — 갈래(시험·과목)는 그 화면 안의 탭이다.
     * **갤러리(활동사진)도 부원 메뉴다** — 업로드만 ADMIN이라 그 진입점은 갤러리 안에 있다
     * (spec §3-1-3 매트릭스). 메뉴를 관리자에게만 두면 부원이 사진을 볼 길이 없다.
     */
    /*
     * **즐겨찾기 메뉴가 없다** (#261). 담아둔 자료를 보는 것은 다른 목적지가 아니라
     * 자료게시판을 추리는 조건이라 그 화면의 토글로 접혔다.
     */
    expect(menuLabels()).toEqual([
      '공지사항',
      '자료게시판',
      '갤러리',
      '자유 게시판',
    ])
    expect(menuLabels()).not.toContain('회원 관리')
  })

  it('PENDING에게는 메뉴가 없고 로그아웃만 있다', async () => {
    auth.me = () =>
      Promise.resolve({ ...BASE, status: 'PENDING', approvedAt: null })

    renderAt('/pending')
    await screen.findByRole('heading', { name: '승인 대기 중' })

    expect(menuLabels()).toEqual([])
    expect(screen.getByRole('button', { name: '로그아웃' })).toBeInTheDocument()
  })
})

describe('로그아웃', () => {
  it('성공하면 세션을 비우고 로그인 화면으로 보낸다', async () => {
    auth.me = () => Promise.resolve(BASE)

    renderAt('/notices')
    fireEvent.click(await screen.findByRole('button', { name: '로그아웃' }))

    expect(
      await screen.findByRole('heading', { name: '로그인' }),
    ).toBeInTheDocument()
  })

  // 회귀 — 실패를 성공처럼 처리하면 서버 세션(HttpOnly 쿠키)이 살아 있는데 사용자는
  // 로그아웃됐다고 믿는다. 공용 PC에서 다음 사람이 남의 계정으로 들어가진다.
  it('서버 오류로 실패하면 세션을 유지하고 이동하지 않는다', async () => {
    auth.me = () => Promise.resolve(BASE)
    auth.logout = () =>
      Promise.reject(
        new ApiError('NETWORK_ERROR', 0, '서버에 연결하지 못했습니다.'),
      )

    renderAt('/notices')
    fireEvent.click(await screen.findByRole('button', { name: '로그아웃' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '로그아웃하지 못했습니다',
    )
    expect(
      screen.getByRole('heading', { name: '공지사항' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '로그인' })).toBeNull()
  })
})

describe('화면에서 올린 API 오류', () => {
  // 회귀 — 권한이 바뀐 사용자가 공지 화면에 남아 요청만 계속 실패하면 안 된다.
  // 목록 API의 403 PENDING_APPROVAL이 세션 계약을 타고 가드까지 이어져야 한다.
  it('목록 조회가 403 PENDING_APPROVAL이면 대기 화면으로 보낸다', async () => {
    /*
     * 403으로만 알아낸 `PENDING`은 **신청 여부를 모르는 상태**라, 대기 화면이 서버에
     * 다시 물어 무엇을 보일지 정한다 (spec §3-1-6). 그래서 `getMe`도 같은 사실을
     * 말해야 한다 — 여기서 `ACTIVE`를 주면 재확인이 세션을 풀어 화면이 도로 나간다.
     * 그건 그것대로 옳은 동작이지만 이 테스트가 보려는 것은 **403이 대기 화면으로
     * 보내는가**다.
     */
    auth.me = () =>
      Promise.resolve({ ...BASE, status: 'PENDING', approvedAt: null })
    auth.listRejects = new ApiError(
      'PENDING_APPROVAL',
      403,
      '가입 승인 대기 중입니다.',
    )

    renderAt('/notices')

    expect(
      await screen.findByRole('heading', { name: '승인 대기 중' }),
    ).toBeInTheDocument()
  })
})
