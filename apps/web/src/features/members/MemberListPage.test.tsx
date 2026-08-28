import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import { MemoryRouter, useLocation, useNavigate } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/App'
import type {
  AdminUserQuery,
  ApproveResult,
  DeactivateResult,
  ReactivateResult,
} from '@/api/adminUsers'
import { ApiError } from '@/api/client'
import type { Page, User } from '@/api/types'
import { SessionProvider } from '@/auth/session'

/**
 * 회원 관리 화면 (#42).
 *
 * **컴포넌트를 직접 렌더하지 않는다.** `/admin/members`로 앱을 띄워 라우트 가드를 실제로
 * 태운다 — 컴포넌트를 직접 그리면 가드를 건너뛰어 라우트가 가드 밖으로 나가도 통과한다.
 *
 * **비동기 로딩이 끝났음을 증명하는 것을 기다린 뒤 단언한다.** 제목("회원 관리")은 목록이
 * 오기 전에도 이미 렌더되므로 그걸 기다리면 아무것도 기다리지 않은 것과 같다.
 */
const api = vi.hoisted(() => ({
  queries: [] as AdminUserQuery[],
  approved: [] as number[][],
  statusCalls: [] as { id: number; status: string }[],
  roleAttempts: [] as { id: number; role: string }[],
  roleError: null as ApiError | null,
  rejected: [] as number[][],
  removed: [] as number[],
  summaryCalls: [] as number[],
  removeError: null as ApiError | null,
  summaryError: null as ApiError | null,
  rejectError: null as ApiError | null,
  /** 실패하든 말든 **시도한 것**. 화면이 미리 막지 않았는지 보려면 이게 필요하다. */
  statusAttempts: [] as { id: number; status: string }[],
  approveResult: null as ApproveResult | null,
  approveError: null as ApiError | null,
  statusError: null as ApiError | null,
  listError: null as ApiError | null,
  /** 일괄 비활성화 호출 횟수. 본문이 없으므로 셀 것이 이것뿐이다 (계약 §3-2-6). */
  deactivateCalls: 0,
  deactivateResult: null as DeactivateResult | null,
  deactivateError: null as ApiError | null,
  reactivated: [] as number[][],
  reactivateResult: null as ReactivateResult | null,
  reactivateError: null as ApiError | null,
  /**
   * 학기 전환 확인 창이 세는 대상 인원수 (`status=ACTIVE&role=USER&size=1`).
   *
   * **목록의 `totalElements`(42)와도, 이 페이지의 행 수와도 다른 값이다.** 같게 두면
   * 화면이 엉뚱한 숫자를 보여줘도 문구가 그대로라 T-356이 아무것도 재지 않는다.
   */
  activeUserCount: 37,
  /** 그 건수 조회만 실패시킨다. `listError`는 목록까지 함께 죽여 화면이 아예 안 뜬다. */
  countError: null as ApiError | null,
}))

function member(overrides: Partial<User> & { id: number; name: string }): User {
  return {
    email: `user${overrides.id}@khu.ac.kr`,
    studentNo: '2021123456',
    department: '컴퓨터공학과',
    role: 'USER',
    status: 'ACTIVE',
    createdAt: '2026-03-01T09:00:00Z',
    appliedAt: '2026-03-02T09:00:00Z',
    approvedAt: '2026-03-03T09:00:00Z',
    ...overrides,
  }
}

/**
 * 신청서를 낸 PENDING 둘, **내지 않은 PENDING 하나**, ACTIVE 하나, SUSPENDED 하나.
 * 신청 안 한 계정이 없으면 "선택되지 않는다"는 규칙을 확인할 수가 없다.
 */
const MEMBERS: User[] = [
  member({ id: 1, name: '신청한하나', status: 'PENDING', approvedAt: null }),
  member({ id: 2, name: '신청한둘', status: 'PENDING', approvedAt: null }),
  member({
    id: 3,
    name: '미신청',
    status: 'PENDING',
    studentNo: null,
    // 학과도 신청서에서 온다 — 안 냈으면 학번처럼 비어 있다 (§3-2-2).
    department: null,
    appliedAt: null,
    approvedAt: null,
  }),
  member({ id: 4, name: '활동회원', status: 'ACTIVE' }),
  // 학과 필드가 생기기 **전에** 승인된 회원 — 신청은 했지만 department가 없다 (§3-2-2).
  member({ id: 7, name: '이전승인', status: 'ACTIVE', department: null }),
  member({ id: 5, name: '정지회원', status: 'SUSPENDED' }),
  /*
   * 지난 학기 부원 (#228). **없으면 "비활동"이 "정지"와 갈리는지, 복구로 고를 수 있는지를
   * 확인할 수 없다** — 화면에 그 상태가 한 번도 나오지 않는다.
   */
  member({ id: 6, name: '비활동회원', status: 'INACTIVE' }),
  /*
   * **로그인한 관리자 본인.** 자기 정지(2-2 §2-2-7)를 확인하려면 명단에 본인이 있어야
   * 하고 테스트가 **그 행**을 눌러야 한다. 다른 사람 행을 누르고 403을 주입하면
   * "서버가 거부하면 보여준다"만 확인될 뿐 자기 정지 경로는 밟지 않는다.
   */
  member({ id: 99, name: '김관리', role: 'ADMIN', status: 'ACTIVE' }),
]

vi.mock('@/api/adminUsers', () => ({
  /*
   * **한 틱 미룬다.** 동기적으로 응답하면 재조회 중 `data`가 `null`인 창이 사실상 사라져,
   * "재조회가 끝나기 전에 단언하는" 경합이 실행마다 났다 안 났다 한다 — 1/12쯤으로 터지는
   * 플레이키가 그렇게 생겼다. 실제 네트워크는 즉시 끝나지 않는다.
   */
  list: async (query: AdminUserQuery): Promise<Page<User>> => {
    api.queries.push(query)
    if (api.listError) return Promise.reject(api.listError)
    /*
     * **학기 전환 확인 창의 건수 조회다** (2-2 §2-2-3 — 미리보기 전용 API를 두지 않는다).
     * 목록과 다른 `totalElements`를 준다 — 같으면 화면이 어느 값을 쓰는지 구분되지 않는다.
     */
    if (
      query.status === 'ACTIVE' &&
      query.role === 'USER' &&
      query.size === 1
    ) {
      if (api.countError) return Promise.reject(api.countError)
      await new Promise((resolve) => setTimeout(resolve, 0))
      return {
        content: [],
        page: {
          size: 1,
          number: 0,
          totalElements: api.activeUserCount,
          totalPages: api.activeUserCount,
        },
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 0))
    return Promise.resolve({
      /*
       * **복사본을 돌려준다.** 같은 객체를 주면 `approve` mock이 명단을 고칠 때 화면이
       * 이미 들고 있는 데이터가 함께 바뀐다 — 재조회 없이도 "승인 가능 1명"이 되어,
       * 재조회가 끝났다는 증거가 증거 노릇을 못 한다. 실제 서버는 매번 새 응답을 준다.
       */
      content: MEMBERS.map((user) => ({ ...user })),
      page: { size: 20, number: 0, totalElements: 42, totalPages: 3 },
    })
  },
  /*
   * **서버처럼 상태를 바꾼다.** 승인된 사람은 다음 조회에서 `ACTIVE`로 온다.
   * 바꾸지 않으면 재조회 전후의 화면이 똑같아, "재조회가 끝났다"를 증명하는 것이
   * 화면에 하나도 없다 — 그래서 기다릴 대상이 없어진다.
   */
  approve: (userIds: number[]): Promise<ApproveResult> => {
    if (api.approveError) return Promise.reject(api.approveError)
    api.approved.push(userIds)
    const result = api.approveResult ?? { approved: userIds, failed: [] }
    for (const id of result.approved) {
      const found = MEMBERS.find((user) => user.id === id)
      if (found) {
        found.status = 'ACTIVE'
        found.approvedAt = '2026-03-03T09:00:00Z'
      }
    }
    return Promise.resolve(result)
  },
  deactivateAll: async (): Promise<DeactivateResult> => {
    api.deactivateCalls += 1
    if (api.deactivateError) throw api.deactivateError
    const result = api.deactivateResult ?? { deactivated: [4, 7] }
    // 서버처럼 명단을 고친다 — 재조회 뒤 화면이 실제로 달라져야 기다릴 대상이 생긴다.
    for (const id of result.deactivated) {
      const found = MEMBERS.find((user) => user.id === id)
      if (found) found.status = 'INACTIVE'
    }
    return result
  },
  reactivate: async (userIds: number[]): Promise<ReactivateResult> => {
    api.reactivated.push(userIds)
    if (api.reactivateError) throw api.reactivateError
    const result = api.reactivateResult ?? { reactivated: userIds, failed: [] }
    for (const id of result.reactivated) {
      const found = MEMBERS.find((user) => user.id === id)
      if (found) found.status = 'ACTIVE'
    }
    return result
  },
  reject: async (userIds: number[]) => {
    api.rejected.push(userIds)
    if (api.rejectError) throw api.rejectError
    return { rejected: userIds, failed: [] }
  },
  contentSummary: async (id: number) => {
    api.summaryCalls.push(id)
    if (api.summaryError) throw api.summaryError
    // 네 값을 서로 다르게 준다 — 같으면 매핑이 어긋나도 문구가 그대로라 통과한다.
    return { notes: 3, notices: 1, photos: 5, posts: 7 }
  },
  remove: (id: number): Promise<void> => {
    api.removed.push(id)
    if (api.removeError) return Promise.reject(api.removeError)
    return Promise.resolve()
  },
  updateRole: (id: number, role: string): Promise<User> => {
    api.roleAttempts.push({ id, role })
    if (api.roleError) return Promise.reject(api.roleError)
    return Promise.resolve({ ...MEMBERS[0], role: role as 'USER' | 'ADMIN' })
  },
  updateStatus: (id: number, status: string): Promise<User> => {
    api.statusAttempts.push({ id, status })
    if (api.statusError) return Promise.reject(api.statusError)
    api.statusCalls.push({ id, status })
    return Promise.resolve(MEMBERS[0])
  },
}))

const auth = vi.hoisted(() => ({ role: 'ADMIN' as 'USER' | 'ADMIN' }))

const ME: User = member({ id: 99, name: '김관리', role: 'ADMIN' })

vi.mock('@/api/auth', () => ({
  getMe: () => Promise.resolve({ ...ME, role: auth.role }),
  logout: () => Promise.resolve(),
}))

vi.mock('@/api/notices', () => ({
  list: () =>
    Promise.resolve({
      content: [],
      page: { size: 10, number: 0, totalElements: 0, totalPages: 0 },
    }),
  get: () => Promise.reject(new Error('이 파일에서는 쓰지 않는다')),
  togglePin: () => Promise.reject(new Error('이 파일에서는 쓰지 않는다')),
  create: () => Promise.reject(new Error('이 파일에서는 쓰지 않는다')),
  update: () => Promise.reject(new Error('이 파일에서는 쓰지 않는다')),
  remove: () => Promise.reject(new Error('이 파일에서는 쓰지 않는다')),
}))

function Address() {
  const { pathname } = useLocation()
  const navigate = useNavigate()
  return (
    <>
      <div data-testid="pathname">{pathname}</div>
      {/* MemoryRouter에는 브라우저 히스토리가 없다. 라우터의 뒤로가기를 그대로 쓴다. */}
      <button type="button" onClick={() => navigate(-1)}>
        뒤로가기
      </button>
    </>
  )
}

function renderAt(path = '/admin/members') {
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

/** 목록이 도착했다는 증거. 회원 행이 그려진 뒤에만 존재한다. */
function loaded() {
  return screen.findByRole('checkbox', { name: '신청한하나 선택' })
}

/**
 * 행 액션 메뉴를 열고 항목을 누른다 (#99).
 *
 * 정지·권한·제거는 이제 버튼이 아니라 `⋯` 메뉴 안이다 — 표에 버튼이 행마다 셋씩
 * 늘어서면 되돌릴 수 없는 `제거`가 `정지`와 같은 무게로 보인다.
 */
async function rowAction(name: string, label: string | RegExp) {
  openRowMenu(
    within(row(name)).getByRole('button', { name: `${name} 관리 메뉴` }),
  )
  const item = await screen.findByRole('menuitem', { name: label })
  fireEvent.click(item)
}

/**
 * Radix 메뉴는 `pointerdown`으로 열린다 — `click`만 쏘면 안 열린다.
 *
 * `user-event`를 들이면 한 줄이지만, 이 검사 하나 때문에 의존성을 늘리지 않는다.
 * jsdom에 `PointerEvent`가 없어 `MouseEvent`로 대신 만든다.
 */
function openRowMenu(trigger: HTMLElement) {
  fireEvent.pointerDown(
    trigger,
    new MouseEvent('pointerdown', { bubbles: true, button: 0 }),
  )
}

/**
 * 행 메뉴에 그 항목이 있는지. 메뉴를 열어 확인하고 다시 닫는다.
 *
 * **요소가 아니라 있고 없음을 돌려준다** — 닫고 나면 요소가 문서에서 사라져
 * `toBeInTheDocument()`가 항상 실패한다.
 */
async function hasRowMenuItem(name: string, label: string | RegExp) {
  const trigger = within(row(name)).queryByRole('button', {
    name: `${name} 관리 메뉴`,
  })
  if (!trigger) return false
  openRowMenu(trigger)
  // 메뉴는 포털로 늦게 붙는다. 열릴 때까지 기다린 뒤에 항목을 찾는다.
  await screen.findByRole('menu')
  const found = screen.queryAllByRole('menuitem', { name: label }).length > 0
  fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' })
  return found
}

/** 이름으로 행을 집는다. */
function row(name: string): HTMLElement {
  const cell = screen.getByRole('cell', { name })
  const found = cell.closest('tr')
  if (!found) throw new Error(`${name} 행을 찾지 못했다`)
  return found
}

beforeEach(() => {
  api.queries = []
  api.approved = []
  api.statusCalls = []
  api.statusAttempts = []
  api.roleAttempts = []
  api.roleError = null
  api.rejected = []
  api.removed = []
  api.summaryCalls = []
  api.removeError = null
  api.summaryError = null
  api.rejectError = null
  api.approveResult = null
  api.approveError = null
  api.statusError = null
  api.listError = null
  api.deactivateCalls = 0
  api.deactivateResult = null
  api.deactivateError = null
  api.reactivated = []
  api.reactivateResult = null
  api.reactivateError = null
  api.activeUserCount = 37
  api.countError = null
  auth.role = 'ADMIN'
  // approve·학기 전환 mock이 명단을 실제로 고치므로 매 테스트마다 처음 상태로 되돌린다.
  for (const user of MEMBERS) {
    if (user.id <= 3) {
      user.status = 'PENDING'
      user.approvedAt = null
    }
    if (user.id === 4 || user.id === 7) user.status = 'ACTIVE'
    if (user.id === 6) user.status = 'INACTIVE'
  }
})

describe('접근 권한', () => {
  // 이슈 완료 조건 — ADMIN 라우트에서만 열린다.
  it('USER가 /admin/members로 직접 들어오면 차단된다', async () => {
    auth.role = 'USER'

    renderAt()

    await waitFor(() => {
      expect(pathname()).toBe('/notices')
    })
    expect(screen.queryByRole('heading', { name: '회원 관리' })).toBeNull()
  })

  it('ADMIN은 회원 관리 화면을 연다', async () => {
    renderAt()

    expect(await loaded()).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { name: '회원 관리' }),
    ).toBeInTheDocument()
  })
})

describe('승인 대상', () => {
  /*
   * 계약 §3-2-6 MUST — 승인 대상은 `status = PENDING AND applied_at IS NOT NULL`이다.
   * 신청서를 내지 않은 계정을 승인하면 학번이 빈 ACTIVE가 만들어진다.
   */
  /*
   * 신청서를 내지 않은 사람은 승인을 기다리는 게 아니라 아직 신청을 안 한 것이다.
   * **그 구분을 상태 칸이 한다** — 액션 칸에 문구를 넣어 대신하지 않는다.
   */
  it('미승인 회원은 상태로 구분되고 선택도 액션도 없다', async () => {
    renderAt()
    await loaded()

    const target = row('미신청')
    expect(within(target).getByRole('checkbox')).toBeDisabled()
    expect(target).toHaveTextContent('미승인')
    expect(target).not.toHaveTextContent('승인 대기')
    // 액션 칸이 비어 있다. 왜 비었는지는 상태만 봐도 자명하다.
    expect(within(target).queryByRole('button')).toBeNull()
    expect(target).not.toHaveTextContent('신청서 미제출')
  })

  /*
   * 필터와 행이 **같은 낱말로 같은 것을 가리킨다.** 전에는 필터의 "미승인"이 `PENDING`
   * 전부였고 행의 "미승인"은 그중 신청 안 한 계정만이라, 같은 낱말이 두 범위로 쓰였다.
   * 서버가 `applied`로 둘을 갈라 주므로(spec §3-2-6) 이제 그럴 이유가 없다.
   */
  it('상태 필터가 승인 대기와 미승인을 가른다', async () => {
    renderAt()
    await loaded()

    const filter = screen.getByLabelText('상태')
    expect(
      within(filter).getByRole('option', { name: '승인 대기' }),
    ).toHaveValue('PENDING:applied')
    expect(within(filter).getByRole('option', { name: '미승인' })).toHaveValue(
      'PENDING:none',
    )
    // PENDING 전부를 한 번에 보는 선택지는 두지 않는다 — 위 둘의 합집합이다.
    expect(
      within(filter).queryByRole('option', { name: '승인 대기 · 미승인' }),
    ).not.toBeInTheDocument()
  })

  it('신청서를 낸 PENDING은 승인 대기로 보인다', async () => {
    renderAt()
    await loaded()

    expect(row('신청한하나')).toHaveTextContent('승인 대기')
  })

  it('PENDING이 아닌 회원도 선택할 수 없다', async () => {
    renderAt()
    await loaded()

    for (const name of ['활동회원', '정지회원']) {
      expect(within(row(name)).getByRole('checkbox')).toBeDisabled()
    }
  })

  /*
   * **"가입 신청일"은 `appliedAt`이다** (2-2 §2-2-1 MUST) — `createdAt`(첫 구글 로그인)이
   * 아니다. 둘은 며칠 차이가 나므로 바꿔 쓰면 운영자가 다른 날짜를 보고 판단한다.
   * 픽스처 데이터의 두 날짜를 일부러 벌려 놓아, 어느 쪽을 그리는지 눈으로 갈린다.
   */
  /*
   * #200 — 임원진을 여럿 두려면 화면에서 관리자를 지정할 수 있어야 한다. 되돌릴 수 없는
   * 조작이라 확인 창을 거친다 (T-77과 같은 규칙).
   */
  it('관리자 지정은 확인 창을 거쳐 role만 바꾼다', async () => {
    renderAt()
    await loaded()

    await rowAction('활동회원', '관리자 지정')
    // 누른 것만으로는 요청이 나가지 않는다.
    expect(api.roleAttempts).toEqual([])

    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('활동회원')
    fireEvent.click(within(dialog).getByRole('button', { name: '관리자 지정' }))

    await waitFor(() => {
      expect(api.roleAttempts).toEqual([{ id: 4, role: 'ADMIN' }])
    })
  })

  /*
   * #201 — 거부는 승인과 같은 선택 흐름이다. 되돌릴 수 없으니 확인 창을 거친다.
   */
  it('선택한 신청을 거부한다', async () => {
    renderAt()
    // `loaded()`가 돌려주는 것이 곧 그 회원의 체크박스다.
    fireEvent.click(await loaded())
    fireEvent.click(screen.getByRole('button', { name: /1명 거부/ }))
    expect(api.rejected).toEqual([])

    const dialog = await screen.findByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: '거부' }))

    await waitFor(() => expect(api.rejected).toEqual([[1]]))
  })

  /*
   * **제거 확인 창은 남을 콘텐츠 건수를 보여준다** (2-2 §2-2-4 MUST). 제거는 되돌아갈
   * 수단을 없애는 유일한 조작이고, 관계가 끊기면 운영자도 그 회원의 콘텐츠를 못 찾는다.
   * 건수를 안 보여주면 무엇이 남는지 모르는 채로 되돌릴 수 없는 조작을 하게 된다.
   */
  it('제거 확인 창이 남을 콘텐츠 건수를 보여준다', async () => {
    renderAt()
    await loaded()

    await rowAction('활동회원', '제거')

    const dialog = await screen.findByRole('alertdialog')
    expect(api.summaryCalls).toEqual([4])
    await waitFor(() => {
      expect(dialog).toHaveTextContent('자료 3건')
      expect(dialog).toHaveTextContent('공지 1건')
      expect(dialog).toHaveTextContent('활동사진 5건')
      expect(dialog).toHaveTextContent('게시글 7건')
    })
    // 아직 요청은 안 나갔다 — 확인을 눌러야 한다.
    expect(api.removed).toEqual([])

    fireEvent.click(within(dialog).getByRole('button', { name: '제거' }))
    await waitFor(() => expect(api.removed).toEqual([4]))
  })

  /*
   * **건수를 보여주기 전에는 제거를 누를 수 없다** (2-2 §2-2-4 MUST). 확인 창의 존재
   * 이유가 "무엇이 남는지 보고 정한다"인데, 못 본 채로 누를 수 있으면 그 MUST가 무의미해진다.
   */
  it('건수를 못 받으면 제거 버튼이 잠긴다', async () => {
    api.summaryError = new ApiError(
      'NOT_FOUND',
      404,
      '회원을 찾을 수 없습니다.',
    )

    renderAt()
    await loaded()
    await rowAction('활동회원', '제거')

    const dialog = await screen.findByRole('alertdialog')
    await waitFor(() => {
      expect(dialog).toHaveTextContent('불러오지 못했습니다')
    })
    expect(within(dialog).getByRole('button', { name: '제거' })).toBeDisabled()
    expect(api.removed).toEqual([])
  })

  /*
   * T-161 — **보낸 사람은 성공이든 실패든 선택에서 빠진다.** 실패 때 남겨 두면 해제할 수
   * 없는 선택이 된다. 원래 이 경로에 정리가 없어 요청이 실패하면 그대로 남았다.
   */
  it('거부가 실패해도 보낸 사람은 선택에서 빠진다', async () => {
    // 서버 500은 화면이 코드로 분기할 대상이 아니라 웹 계약 목록에 없다 (§5-4).
    api.rejectError = new ApiError(
      'NETWORK_ERROR',
      0,
      '잠시 후 다시 시도해 주세요.',
    )

    renderAt()
    fireEvent.click(await loaded())
    fireEvent.click(screen.getByRole('button', { name: /1명 거부/ }))

    const dialog = await screen.findByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: '거부' }))

    expect(await screen.findByRole('status')).toHaveTextContent(
      '거부하지 못했습니다',
    )
    await waitFor(() => {
      expect(within(row('신청한하나')).getByRole('checkbox')).not.toBeChecked()
    })
  })

  /*
   * `PENDING`은 제거가 아니라 거부다 (§2-2-2) — 남긴 것이 없어 세션 폐기·콘텐츠 처리
   * 규칙이 붙지 않고, 일괄 거부가 담당한다.
   */
  it('제거 버튼은 PENDING에 없다', async () => {
    renderAt()
    await loaded()

    for (const name of ['신청한하나', '미신청']) {
      expect(await hasRowMenuItem(name, '제거')).toBe(false)
    }
    expect(await hasRowMenuItem('정지회원', '제거')).toBe(true)
  })

  /*
   * 노출 조건은 **`PENDING`만 제외**다 (2-2 §2-2-5). 승인 전에 관리자로 만들면 승인일시
   * 없는 ADMIN이 생기지만, 정지된 회원은 대상이다 — 권한과 상태는 독립이고 정지된
   * 관리자의 권한을 회수하는 것은 오히려 필요한 조작이다.
   */
  it('권한 버튼은 PENDING에만 없고 정지된 회원에는 있다', async () => {
    renderAt()
    await loaded()

    expect(await hasRowMenuItem('정지회원', '관리자 지정')).toBe(true)

    /*
     * **메뉴를 열어서 확인한다.** 행 안의 버튼만 찾으면 항목이 잘못 노출돼도 닫힌 메뉴
     * 안에 있어 잡히지 않는다 — 부재 검사가 아무것도 지키지 못하게 된다.
     */
    for (const name of ['신청한하나', '미신청']) {
      expect(await hasRowMenuItem(name, /관리자 지정|권한 회수/)).toBe(false)
    }
  })

  /*
   * **화면이 미리 막지 않는다** (2-2 §2-2-7 MUST — 검사는 서버에서). 활성 관리자가 몇
   * 명인지 화면은 모른다. 미리 비활성화하면 서버만 아는 조건을 화면이 흉내 내게 되고,
   * 틀리는 순간 되돌릴 방법이 없다. T-80과 같은 뿌리다.
   */
  it('권한 회수가 서버에서 막히면 그 사유를 그대로 보여준다', async () => {
    api.roleError = new ApiError(
      'FORBIDDEN',
      403,
      '활성 관리자가 없어집니다. 다른 관리자를 먼저 지정해 주세요.',
    )

    renderAt()
    await loaded()

    // 메뉴에 항목이 살아 있다는 것 자체가 "화면이 미리 막지 않았다"는 증거다.
    await rowAction('김관리', '권한 회수')

    const dialog = await screen.findByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: '권한 회수' }))

    expect(await screen.findByRole('status')).toHaveTextContent(
      '활성 관리자가 없어집니다. 다른 관리자를 먼저 지정해 주세요.',
    )
  })

  it('가입 신청일 칸에 createdAt이 아니라 appliedAt이 나온다', async () => {
    renderAt()
    await loaded()

    const cells = within(row('신청한하나')).getAllByRole('cell')
    // 0 체크박스, 1 이름, 2 학번, 3 학과, 4 이메일, 5 권한, 6 상태, 7 가입 신청일, 8 승인일
    expect(cells[7]).toHaveTextContent('2026. 03. 02.')
    expect(cells[7]).not.toHaveTextContent('2026. 03. 01.')
  })

  /*
   * T-186 — 학과 칸은 `department`를 그린다 (2-2 §2-2-1). 이 필드가 생기기 전에
   * 승인된 회원과 신청 전 계정은 값이 없으므로 `—`다 — 빈 것이지 오류가 아니다.
   * 관리자가 승인 심사에서 실제로 참고하는 값이라(#100) 목록에서 바로 보여야 한다.
   */
  it('학과 칸에 department가 나오고 없으면 —다', async () => {
    renderAt()
    await loaded()

    expect(
      within(row('신청한하나')).getByRole('cell', { name: '컴퓨터공학과' }),
    ).toBeInTheDocument()

    // T-186의 양쪽 — 신청 전 계정과, 필드가 생기기 전에 승인된 기존 회원.
    for (const name of ['미신청', '이전승인']) {
      const cells = within(row(name)).getAllByRole('cell')
      expect(cells[3]).toHaveTextContent('—')
    }
  })

  it('신청서를 낸 PENDING은 선택할 수 있다', async () => {
    renderAt()

    expect(await loaded()).toBeEnabled()
    expect(within(row('신청한둘')).getByRole('checkbox')).toBeEnabled()
  })
})

describe('전체 선택', () => {
  /*
   * **범위는 이 페이지의 일괄 처리 대상뿐이다.** 검색 결과 전부를 뜻하면 관리자가 보지 못한
   * 사람까지 승인하게 된다 — 되돌릴 수 없는 조작이다.
   *
   * **승인 대상과 복구 대상을 함께 고른다** (#232). 한쪽만 고르게 하면 "전체" 필터에서
   * 비활동 회원은 **필터를 먼저 걸 줄 아는 사람에게만** 보인다.
   */
  it('전체 선택은 이 페이지의 승인·복구 대상만 고른다', async () => {
    renderAt()
    await loaded()

    fireEvent.click(
      screen.getByRole('checkbox', {
        name: '이 페이지의 일괄 처리 대상 전체 선택',
      }),
    )

    expect(within(row('신청한하나')).getByRole('checkbox')).toBeChecked()
    expect(within(row('신청한둘')).getByRole('checkbox')).toBeChecked()
    expect(within(row('비활동회원')).getByRole('checkbox')).toBeChecked()
    expect(within(row('미신청')).getByRole('checkbox')).not.toBeChecked()
    expect(within(row('활동회원')).getByRole('checkbox')).not.toBeChecked()
    expect(within(row('정지회원')).getByRole('checkbox')).not.toBeChecked()
  })

  // 선택 범위를 화면이 말하지 않으면 그것대로 관리자를 속이는 것이다.
  it('선택 범위가 이 페이지임을 화면이 말한다', async () => {
    renderAt()
    await loaded()

    fireEvent.click(
      screen.getByRole('checkbox', {
        name: '이 페이지의 일괄 처리 대상 전체 선택',
      }),
    )

    expect(screen.getByText(/이 페이지에서 3명 선택됨/)).toBeInTheDocument()
    // 전체 인원도 함께 보여 페이지가 전부가 아님이 드러난다.
    expect(screen.getByText(/전체 42명/)).toBeInTheDocument()
  })

  it('다시 누르면 전부 해제된다', async () => {
    renderAt()
    await loaded()

    const all = screen.getByRole('checkbox', {
      name: '이 페이지의 일괄 처리 대상 전체 선택',
    })
    fireEvent.click(all)
    fireEvent.click(all)

    expect(within(row('신청한하나')).getByRole('checkbox')).not.toBeChecked()
    expect(screen.getByText(/이 페이지에서 0명 선택됨/)).toBeInTheDocument()
  })
})

describe('일괄 승인', () => {
  it('확인 전에는 승인되지 않고 누구를 승인하는지 보여준다', async () => {
    renderAt()
    fireEvent.click(await loaded())

    fireEvent.click(screen.getByRole('button', { name: /선택한 1명 승인/ }))

    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('신청한하나')
    expect(api.approved).toEqual([])

    fireEvent.click(within(dialog).getByRole('button', { name: '취소' }))
    await waitFor(() => {
      expect(screen.queryByRole('alertdialog')).toBeNull()
    })
    expect(api.approved).toEqual([])
  })

  it('확인하면 선택한 id만 보내고 성공 건수를 안내한다', async () => {
    renderAt()
    fireEvent.click(await loaded())
    fireEvent.click(within(row('신청한둘')).getByRole('checkbox'))

    fireEvent.click(screen.getByRole('button', { name: /선택한 2명 승인/ }))
    const dialog = await screen.findByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: '승인' }))

    expect(await screen.findByRole('status')).toHaveTextContent(
      '2명을 승인했습니다.',
    )
    expect(api.approved).toEqual([[1, 2]])
  })

  /*
   * 2-2 §2-2-2 MUST — 처리 결과(성공/실패 건수)를 안내한다. 실패가 있으면 **누구인지**도
   * 말한다. 건수만으로는 운영자가 무엇을 조치해야 할지 알 수 없다.
   */
  it('일부가 실패하면 성공·실패 건수와 실패한 사람을 안내한다', async () => {
    api.approveResult = {
      approved: [1],
      failed: [{ userId: 2, reason: 'NOT_APPLIED' }],
    }

    renderAt()
    fireEvent.click(await loaded())
    fireEvent.click(within(row('신청한둘')).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: /선택한 2명 승인/ }))
    const dialog = await screen.findByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: '승인' }))

    const status = await screen.findByRole('status')
    expect(status).toHaveTextContent('1명을 승인하고 1명은 실패했습니다')
    expect(status).toHaveTextContent('신청한둘')
    expect(status).toHaveTextContent('신청서를 내지 않은 계정')
  })

  /*
   * 사유를 하나로 뭉치면 **거짓 원인을 안내한다.** 두 관리자가 같은 신청을 연달아 처리하면
   * 서버는 `NOT_PENDING`을 주는데, 그것을 "신청서를 내지 않았다"고 옮기면 운영자가 이미
   * 승인된 사람에게 신청서를 내라고 연락하게 된다 (계약 §3-2-6).
   */
  it('실패 사유를 뭉치지 않고 각각의 문구로 안내한다', async () => {
    api.approveResult = {
      approved: [],
      failed: [
        { userId: 1, reason: 'NOT_PENDING' },
        { userId: 2, reason: 'NOT_APPLIED' },
      ],
    }

    renderAt()
    fireEvent.click(await loaded())
    fireEvent.click(within(row('신청한둘')).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: /선택한 2명 승인/ }))
    const dialog = await screen.findByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: '승인' }))

    const status = await screen.findByRole('status')
    expect(status).toHaveTextContent(
      '이미 승인되었거나 정지된 계정: 신청한하나',
    )
    expect(status).toHaveTextContent('신청서를 내지 않은 계정: 신청한둘')
  })

  /** 지워진 계정은 목록에 없어 이름을 알 수 없다. 그렇다고 빼면 건수와 나열이 어긋난다. */
  it('이름을 찾지 못한 실패도 id로 안내한다', async () => {
    api.approveResult = {
      approved: [],
      failed: [{ userId: 999, reason: 'NOT_FOUND' }],
    }

    renderAt()
    fireEvent.click(await loaded())
    fireEvent.click(screen.getByRole('button', { name: /선택한 1명 승인/ }))
    const dialog = await screen.findByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: '승인' }))

    const status = await screen.findByRole('status')
    expect(status).toHaveTextContent('찾을 수 없는 계정: #999')
  })

  it('승인이 실패하면 성공한 것처럼 보이지 않는다', async () => {
    api.approveError = new ApiError('FORBIDDEN', 403, '권한이 없습니다.')

    renderAt()
    fireEvent.click(await loaded())
    fireEvent.click(screen.getByRole('button', { name: /선택한 1명 승인/ }))
    const dialog = await screen.findByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: '승인' }))

    const status = await screen.findByRole('status')
    expect(status).toHaveTextContent('승인하지 못했습니다')
    expect(status).toHaveTextContent('권한이 없습니다')
    expect(status).not.toHaveTextContent('승인했습니다.')
  })

  /*
   * 한 명만 승인하려고 체크박스를 거치게 하지 않는다. **여럿은 체크박스, 한 명은 행에서.**
   * 다만 되돌릴 수 없는 건 같으므로 확인 창은 똑같이 거친다.
   */
  it('행의 승인 버튼은 체크박스 없이 그 한 명만 승인한다', async () => {
    renderAt()
    await loaded()

    // 아무것도 선택하지 않은 상태다.
    expect(screen.getByText(/이 페이지에서 0명 선택됨/)).toBeInTheDocument()

    fireEvent.click(
      within(row('신청한둘')).getByRole('button', { name: '승인' }),
    )
    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('신청한둘')
    expect(dialog).not.toHaveTextContent('신청한하나')
    expect(api.approved).toEqual([])

    fireEvent.click(within(dialog).getByRole('button', { name: '승인' }))

    expect(await screen.findByRole('status')).toHaveTextContent(
      '1명을 승인했습니다.',
    )
    expect(api.approved).toEqual([[2]])
  })

  /*
   * 회귀 — **행 승인이 무관한 선택까지 지우면 안 된다.**
   *
   * 재현: A를 체크한 상태에서 다른 행 B의 승인 버튼을 누르면, 조회 조건은 그대로이고
   * A는 여전히 승인 대상인데 A의 체크가 안내 없이 풀렸다. 조건 변경 때 지적받은 것과
   * 같은 일이 다른 경로에서 다시 일어난 것이다.
   */
  it('행 승인은 다른 사람의 선택을 건드리지 않는다', async () => {
    renderAt()
    // A(신청한하나)를 체크한다.
    fireEvent.click(await loaded())
    expect(screen.getByText(/이 페이지에서 1명 선택됨/)).toBeInTheDocument()

    // B(신청한둘)를 행에서 승인한다.
    fireEvent.click(
      within(row('신청한둘')).getByRole('button', { name: '승인' }),
    )
    const dialog = await screen.findByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: '승인' }))

    /*
     * **재조회가 끝날 때까지 기다린다.** 승인 성공은 목록을 다시 부르고, 그동안 `data`가
     * `null`이라 선택 건수 표시 자체가 화면에서 사라진다. 안내(`status`)는 재조회보다
     * 먼저 뜨므로 그것만 기다리면 사라진 순간에 단언하게 된다.
     *
     * 기다릴 대상은 **재조회 뒤에만 존재하는 것**이다 — 승인된 사람이 빠져 승인 가능
     * 인원이 2명에서 1명으로 줄어든 표시.
     */
    await screen.findByRole('status')
    await screen.findByText(/승인 가능 1명/)

    // A의 선택은 그대로다.
    expect(screen.getByText(/이 페이지에서 1명 선택됨/)).toBeInTheDocument()
    expect(within(row('신청한하나')).getByRole('checkbox')).toBeChecked()
  })

  it('처리된 사람은 성공이든 실패든 선택에서 빠진다', async () => {
    renderAt()
    fireEvent.click(await loaded())
    fireEvent.click(within(row('신청한둘')).getByRole('checkbox'))
    expect(screen.getByText(/이 페이지에서 2명 선택됨/)).toBeInTheDocument()

    // 하나만 성공하고 하나는 실패한 응답.
    api.approveResult = {
      approved: [1],
      failed: [{ userId: 2, reason: 'NOT_APPLIED' }],
    }
    fireEvent.click(screen.getByRole('button', { name: /선택한 2명 승인/ }))
    const dialog = await screen.findByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: '승인' }))

    // 위와 같은 이유로 재조회가 끝난 증거를 기다린다.
    await screen.findByRole('status')
    await screen.findByText(/승인 가능 1명/)

    /*
     * 둘 다 빠진다. 실패한 사람을 남겨 두면 **관리자가 해제할 수도 없는 선택**이 된다 —
     * 재조회 뒤 그 행은 승인 대상이 아니라 체크박스가 잠기거나 아예 사라지는데, 버튼은
     * 계속 살아 있어 언제나 실패하는 요청을 다시 보내게 된다. 누가 왜 실패했는지는
     * 안내가 이미 말했다.
     */
    expect(screen.getByText(/이 페이지에서 0명 선택됨/)).toBeInTheDocument()
    expect(within(row('신청한둘')).getByRole('checkbox')).not.toBeChecked()
  })

  /*
   * #99 — 선택이 없으면 일괄 액션을 **아예 그리지 않는다.** 전에는 "선택한 0명 승인"이
   * 잠긴 채 떠 있었는데, 뜻 없는 버튼이 화면에 남아 진짜 눌러야 할 때와 구분이 안 됐다.
   * 요약 문구는 선택과 무관하게 계속 보인다.
   */
  it('아무도 선택하지 않으면 일괄 액션이 보이지 않는다', async () => {
    renderAt()
    await loaded()

    expect(screen.queryByRole('button', { name: /명 승인/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /명 거부/ })).toBeNull()
    expect(screen.getByText(/이 페이지에서 0명 선택됨/)).toBeInTheDocument()
  })
})

describe('상태 변경', () => {
  /*
   * 정지는 **즉시 로그인을 막는** 조작이다 (2-2 §2-2-3 MUST). 일괄 승인만 확인받고
   * 정지는 그냥 나가면 앞뒤가 안 맞는다.
   */
  it('정지도 확인을 거치고, 확인 전에는 요청이 나가지 않는다', async () => {
    renderAt()
    await loaded()

    await rowAction('활동회원', '정지')

    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('활동회원')
    // 무엇이 일어나는지 다이얼로그가 말한다.
    expect(dialog).toHaveTextContent('즉시 로그인할 수 없습니다')
    expect(api.statusAttempts).toEqual([])

    fireEvent.click(within(dialog).getByRole('button', { name: '취소' }))
    await waitFor(() => {
      expect(screen.queryByRole('alertdialog')).toBeNull()
    })
    expect(api.statusAttempts).toEqual([])
  })

  it('확인하면 정지하고 결과를 안내한다', async () => {
    renderAt()
    await loaded()

    await rowAction('활동회원', '정지')
    const dialog = await screen.findByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: '정지' }))

    expect(await screen.findByRole('status')).toHaveTextContent(
      '활동회원 회원을 정지했습니다.',
    )
    expect(api.statusCalls).toEqual([{ id: 4, status: 'SUSPENDED' }])
  })

  it('정지된 회원은 해제로 되돌린다', async () => {
    renderAt()
    await loaded()

    await rowAction('정지회원', '정지 해제')
    const dialog = await screen.findByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: '정지 해제' }))

    await waitFor(() => {
      expect(api.statusCalls).toEqual([{ id: 5, status: 'ACTIVE' }])
    })
  })

  /*
   * 2-2 §2-2-7 MUST — 마지막 활성 관리자의 자기 정지는 **서버가** 막는다. 화면은 활성
   * 관리자가 몇 명인지 모르므로 미리 판단하지 않고, 서버가 준 거부 사유를 그대로 보여준다.
   * 로그아웃에서 "실패인데 성공처럼 보이는" 결함이 있었다 — 같은 실수를 반복하지 않는다.
   */
  it('로그인한 본인을 정지할 때 서버가 막으면 그 사유를 그대로 보여준다', async () => {
    api.statusError = new ApiError(
      'FORBIDDEN',
      403,
      '마지막 활성 관리자는 자기 자신을 정지할 수 없습니다.',
    )

    renderAt()
    await loaded()

    // **로그인한 관리자 본인(id 99)의 행**을 누른다. 남의 행을 누르면 자기 정지가 아니다.
    await rowAction('김관리', '정지')
    const dialog = await screen.findByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: '정지' }))

    const status = await screen.findByRole('status')
    expect(status).toHaveTextContent('상태를 바꾸지 못했습니다')
    expect(status).toHaveTextContent('마지막 활성 관리자')
    expect(status).not.toHaveTextContent('정지했습니다.')
    // 화면이 미리 막지 않았다는 것 — 실제로 요청을 보냈어야 이 화면이 나온다.
    expect(api.statusAttempts).toEqual([{ id: 99, status: 'SUSPENDED' }])
  })

  // PENDING에게는 정지가 의미 없다. 승인 전에는 로그인 자체가 막혀 있다.
  it('PENDING 행에는 정지 버튼이 없다', async () => {
    renderAt()
    await loaded()

    // 승인 버튼은 있고 정지 버튼은 없다. 승인 전에는 로그인 자체가 막혀 있다.
    expect(
      within(row('신청한하나')).queryByRole('button', { name: '승인' }),
    ).toBeInTheDocument()
    expect(await hasRowMenuItem('신청한하나', '정지')).toBe(false)
  })
})

describe('검색·필터·정렬', () => {
  it('검색어를 넣으면 q로 다시 조회한다', async () => {
    renderAt()
    await loaded()

    fireEvent.change(screen.getByLabelText('검색'), {
      target: { value: '신청한' },
    })
    fireEvent.click(screen.getByRole('button', { name: '검색' }))

    await waitFor(() => {
      expect(api.queries.at(-1)?.q).toBe('신청한')
    })
  })

  /**
   * **거르는 것은 서버가 한다** (spec §3-2-6). 화면이 받아서 버리면 총 건수와 총 페이지 수가
   * 실제와 어긋나 관리자가 "12명 남았다"고 읽는 숫자가 틀리게 된다.
   */
  it('승인 대기를 고르면 status와 applied를 함께 보낸다', async () => {
    renderAt()
    await loaded()

    fireEvent.change(screen.getByLabelText('상태'), {
      target: { value: 'PENDING:applied' },
    })

    await waitFor(() => {
      expect(api.queries.at(-1)?.status).toBe('PENDING')
      expect(api.queries.at(-1)?.applied).toBe(true)
    })
  })

  it('미승인을 고르면 applied=false로 보낸다', async () => {
    renderAt()
    await loaded()

    fireEvent.change(screen.getByLabelText('상태'), {
      target: { value: 'PENDING:none' },
    })

    await waitFor(() => {
      expect(api.queries.at(-1)?.status).toBe('PENDING')
      expect(api.queries.at(-1)?.applied).toBe(false)
    })
  })

  /** 신청 여부와 무관한 상태를 고르면 그 파라미터를 아예 보내지 않는다. */
  it('활동중을 고르면 applied를 보내지 않는다', async () => {
    renderAt()
    await loaded()

    fireEvent.change(screen.getByLabelText('상태'), {
      target: { value: 'ACTIVE' },
    })

    await waitFor(() => {
      expect(api.queries.at(-1)?.status).toBe('ACTIVE')
      expect(api.queries.at(-1)?.applied).toBeUndefined()
    })
  })

  /**
   * 뒤로가기·새로고침·링크 공유에 살아남아야 한다 (T-84와 같은 규칙).
   *
   * URL에는 <b>서버 파라미터를 그대로</b> 적는다 — 합성 값을 쓰면 주소만 보고는 무엇을
   * 조회하는지 알 수 없다.
   */
  it('주소로 들어와도 승인 대기 필터가 선택돼 있다', async () => {
    renderAt('/admin/members?status=PENDING&applied=true')
    await loaded()

    expect(screen.getByLabelText('상태')).toHaveValue('PENDING:applied')
  })

  /**
   * 필터를 쪼개기 전의 주소 — `?status=PENDING` 하나가 `PENDING` 전부를 뜻했다.
   *
   * 그대로 두면 목록은 `PENDING`만 가져오는데 필터는 짝이 없어 <b>"전체"로 보인다.</b>
   * 관리자는 전원을 보고 있다고 믿지만 실제로는 일부만 본다 — 이 화면이 없애려던 거짓말이다.
   */
  it('옛 PENDING 주소는 승인 대기로 맞추고 그 사실을 알린다', async () => {
    renderAt('/admin/members?status=PENDING')
    await loaded()

    expect(await screen.findByRole('status')).toHaveTextContent(
      '이전 주소의 조건을 "승인 대기"로 맞췄습니다',
    )
    await waitFor(() => {
      expect(screen.getByLabelText('상태')).toHaveValue('PENDING:applied')
      expect(api.queries.at(-1)?.applied).toBe(true)
    })
  })

  it.each([
    ['권한', 'ADMIN', 'role'],
    ['정렬', 'name', 'sort'],
  ])('%s를 바꾸면 그 값으로 다시 조회한다', async (label, value, key) => {
    renderAt()
    await loaded()

    fireEvent.change(screen.getByLabelText(label), { target: { value } })

    await waitFor(() => {
      expect(api.queries.at(-1)?.[key as 'status']).toBe(value)
    })
  })

  /*
   * 조건을 바꾸면 첫 페이지로 돌아간다. 3페이지에서 조건을 좁히면 결과가 없어 빈 화면이
   * 뜨는데, 관리자는 검색이 안 된 줄 안다.
   */
  it('조건을 바꾸면 첫 페이지로 돌아간다', async () => {
    renderAt('/admin/members?page=2')
    await loaded()
    expect(api.queries.at(-1)?.page).toBe(2)

    fireEvent.change(screen.getByLabelText('상태'), {
      target: { value: 'PENDING:applied' },
    })

    await waitFor(() => {
      expect(api.queries.at(-1)?.page).toBe(0)
    })
  })

  /*
   * 선택을 지우는 것 자체는 맞다 — 안 지우면 화면에 안 보이는 사람이 승인 대상에 남는다.
   * 문제는 **말없이 지우는 것**이다. 관리자는 자기가 고른 게 아직 살아 있다고 믿는다.
   */
  it('조건을 바꿔 선택이 풀리면 그 사실을 알린다', async () => {
    renderAt()
    fireEvent.click(await loaded())
    expect(screen.getByText(/이 페이지에서 1명 선택됨/)).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('상태'), {
      target: { value: 'PENDING:applied' },
    })

    expect(await screen.findByRole('status')).toHaveTextContent(
      '조회 조건이 바뀌어 선택한 1명이 해제되었습니다.',
    )
    // 조건 변경도 재조회를 부른다. 0명 표시는 재조회가 끝나야 다시 그려진다.
    expect(
      await screen.findByText(/이 페이지에서 0명 선택됨/),
    ).toBeInTheDocument()
  })

  // 선택이 없었으면 조용해야 한다. 빈 안내는 소음이다.
  it('선택이 없었으면 조건을 바꿔도 안내하지 않는다', async () => {
    renderAt()
    await loaded()

    fireEvent.change(screen.getByLabelText('상태'), {
      target: { value: 'PENDING:applied' },
    })

    await waitFor(() => {
      expect(api.queries.at(-1)?.status).toBe('PENDING')
    })
    expect(screen.queryByRole('status')).toBeNull()
  })

  /*
   * 뒤로가기로 돌아왔을 때 **목록은 A인데 입력창은 B**로 남으면, 관리자가 화면에 적힌
   * 조건과 다른 명단을 보고 승인한다. 승인은 되돌릴 수 없다.
   */
  it('URL의 검색어가 바뀌면 입력창도 따라간다', async () => {
    renderAt('/admin/members?q=A')
    await loaded()
    expect(screen.getByLabelText('검색')).toHaveValue('A')

    fireEvent.change(screen.getByLabelText('검색'), { target: { value: 'B' } })
    fireEvent.click(screen.getByRole('button', { name: '검색' }))
    await waitFor(() => {
      expect(api.queries.at(-1)?.q).toBe('B')
    })

    // 뒤로가기 — URL이 q=A로 돌아가면 입력창도 A여야 한다.
    fireEvent.click(screen.getByRole('button', { name: '뒤로가기' }))
    await waitFor(() => {
      expect(screen.getByLabelText('검색')).toHaveValue('A')
    })
  })

  /*
   * `?page=999`로 들어오면 "1000 / 3"이 굳어 이전 버튼을 999번 눌러야 빠져나온다.
   * 총 페이지 수를 알게 된 뒤 마지막 유효 페이지로 되돌린다.
   */
  /*
   * 회귀 — **확인창이 사라진 조건의 대상을 들고 남으면 안 된다.**
   *
   * 재현: q=A → q=B 검색 → 회원 선택 후 확인창 열기 → 뒤로가기. 목록과 입력창은 A로
   * 돌아오고 선택도 풀리는데 확인창만 B 조건의 대상을 들고 남아 있었다. 확인을 누르면
   * **관리자가 화면에서 볼 수 없는 사람이 승인된다.**
   */
  it('조회 조건이 바뀌면 열려 있던 확인창도 닫히고 이유를 알린다', async () => {
    renderAt('/admin/members?q=A')
    await loaded()

    fireEvent.change(screen.getByLabelText('검색'), { target: { value: 'B' } })
    fireEvent.click(screen.getByRole('button', { name: '검색' }))
    await waitFor(() => {
      expect(api.queries.at(-1)?.q).toBe('B')
    })

    fireEvent.click(await loaded())
    fireEvent.click(screen.getByRole('button', { name: /선택한 1명 승인/ }))
    expect(await screen.findByRole('alertdialog')).toBeInTheDocument()

    // 뒤로가기 — q=A로 돌아간다. 확인창이 모달이라 바깥은 aria-hidden이다.
    fireEvent.click(
      screen.getByRole('button', { name: '뒤로가기', hidden: true }),
    )

    await waitFor(() => {
      expect(screen.queryByRole('alertdialog')).toBeNull()
    })
    expect(await screen.findByRole('status')).toHaveTextContent(
      '진행 중이던 확인을 닫',
    )
    // 승인 요청은 나가지 않았다.
    expect(api.approved).toEqual([])
  })

  it('범위를 넘은 page는 마지막 페이지로 보정된다', async () => {
    renderAt('/admin/members?page=999')

    await waitFor(() => {
      // totalPages가 3이므로 마지막은 0-기반 2다.
      expect(api.queries.at(-1)?.page).toBe(2)
    })
    // 보정 후 다시 조회한 결과가 그려져야 페이지 표시가 나온다.
    expect(await screen.findByText('3 / 3')).toBeInTheDocument()
  })

  it('목록을 불러오지 못하면 안내한다', async () => {
    api.listError = new ApiError('FORBIDDEN', 403, '권한이 없습니다.')

    renderAt()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '회원 목록을 불러오지 못했습니다',
    )
  })
})

/**
 * 학기 전환 — 일괄 비활성화 (#232, 2-2 §2-2-3).
 *
 * **수십 명의 상태를 한 번에 바꾸는 조작이다.** 되돌릴 수 없는 것은 아니지만 되돌리는
 * 길이 복구 하나뿐이라, 관리자가 **무엇이 바뀌는지 모른 채 누르지 않게** 하는 것이
 * 이 묶음이 지키는 전부다.
 */
describe('일괄 비활성화', () => {
  /** 확인 창을 열고 대상 건수가 도착하기를 기다린다. */
  async function openDeactivate() {
    fireEvent.click(screen.getByRole('button', { name: '일괄 비활동 전환' }))
    return screen.findByText(/일반 부원 37명이 비활동이 됩니다/)
  }

  // 완료 조건 — 확인 창을 거치지 않고는 일괄 전환되지 않는다.
  it('버튼만 눌러서는 전환되지 않고, 취소하면 호출도 없다', async () => {
    renderAt()
    await loaded()

    await openDeactivate()
    expect(api.deactivateCalls).toBe(0)

    fireEvent.click(screen.getByRole('button', { name: '취소' }))

    expect(api.deactivateCalls).toBe(0)
  })

  /*
   * **건수는 목록을 같은 조건으로 조회해 얻는다** (2-2 §2-2-3 — 미리보기 API를 두지 않는다).
   * **현재 페이지의 행 수가 아니다** (T-356 MUST) — 그러면 관리자가 100명을 내리면서
   * 20명으로 안다. 여기 화면에는 7행이 있고 목록의 전체는 42명인데, 대상은 37명이다.
   */
  it('확인 창이 대상 인원수를 status=ACTIVE&role=USER로 세어 보여준다', async () => {
    renderAt()
    await loaded()

    await openDeactivate()

    const count = api.queries.at(-1)
    expect(count?.status).toBe('ACTIVE')
    expect(count?.role).toBe('USER')
    expect(count?.size).toBe(1)
  })

  /*
   * 완료 조건 — **무엇이 대상이 아닌지도 보여준다** (#228 D4). 셋을 적지 않으면 관리자는
   * "전원"을 글자 그대로 믿고, 자기 관리자 계정까지 내려가는 줄 안다.
   */
  it('확인 창이 제외되는 대상 셋을 말한다', async () => {
    renderAt()
    await loaded()

    const body = await openDeactivate()

    expect(body).toHaveTextContent('관리자')
    expect(body).toHaveTextContent('정지된 회원')
    expect(body).toHaveTextContent('승인 대기')
  })

  /*
   * **건수는 참고치이지 실행의 조건이 아니다** (2-2 §2-2-3). 제거 확인 창과 다른 점이다 —
   * 거기서는 남을 콘텐츠를 못 보면 누를 수 없지만, 여기서 막으면 건수 조회가 실패할 때
   * 학기 전환이 통째로 멈춘다. 대상을 정하는 것은 어차피 서버다.
   */
  it('대상 인원수를 못 세도 전환은 진행할 수 있다', async () => {
    api.countError = new ApiError('NETWORK_ERROR', 0, '연결하지 못했습니다.')

    renderAt()
    await loaded()

    fireEvent.click(screen.getByRole('button', { name: '일괄 비활동 전환' }))
    await screen.findByText(/대상 인원수를 불러오지 못했습니다/)

    const confirm = screen.getByRole('button', { name: '비활동 전환' })
    expect(confirm).toBeEnabled()
    fireEvent.click(confirm)

    await waitFor(() => expect(api.deactivateCalls).toBe(1))
  })

  it('확인하면 전환하고 바뀐 건수와 되돌리는 길을 안내한다', async () => {
    renderAt()
    await loaded()

    await openDeactivate()
    fireEvent.click(screen.getByRole('button', { name: '비활동 전환' }))

    expect(await screen.findByRole('status')).toHaveTextContent(
      '2명을 비활동으로 바꿨습니다.',
    )
    // 되돌리는 길은 복구 하나뿐이라(§2-2-3 MUST) 어디서 되돌리는지를 말해야 한다.
    expect(screen.getByRole('status')).toHaveTextContent('비활동')
    expect(api.deactivateCalls).toBe(1)
  })

  /*
   * **멱등하다** (계약 §3-2-6). 두 번째 호출은 빈 배열인데, 그것을 "0명 전환됨"으로만
   * 말하면 실패한 것처럼 읽힌다 — 아무도 대상이 아니었다는 뜻이다.
   */
  it('바뀐 사람이 없으면 그렇게 말한다', async () => {
    api.deactivateResult = { deactivated: [] }

    renderAt()
    await loaded()

    await openDeactivate()
    fireEvent.click(screen.getByRole('button', { name: '비활동 전환' }))

    expect(await screen.findByRole('status')).toHaveTextContent(
      '비활동으로 바뀐 회원이 없습니다',
    )
  })

  /*
   * **세션 반영에 실패하면 `500`이지만 상태는 이미 바뀌었을 수 있다** (2-2 §2-2-5 MUST).
   * "아무 일도 없었다"고 안내하면 관리자가 그렇게 믿는다.
   */
  it('실패하면 목록에서 상태를 확인하라고 안내한다', async () => {
    /*
     * 서버는 `500 INTERNAL_ERROR`를 주지만 그 코드는 계약 §3-2-7의 열한 개에 없어
     * `client.ts`가 `INVALID_RESPONSE`로 격리한다 — 화면이 실제로 받는 모양이 이것이다.
     */
    api.deactivateError = new ApiError(
      'INVALID_RESPONSE',
      500,
      '서버 오류입니다.',
    )

    renderAt()
    await loaded()

    await openDeactivate()
    fireEvent.click(screen.getByRole('button', { name: '비활동 전환' }))

    expect(await screen.findByRole('status')).toHaveTextContent(
      '목록에서 상태를 확인해 주세요',
    )
  })
})

/**
 * 학기 전환 — 일괄 복구 (#232).
 *
 * **가입 일괄 승인과 같은 조작감이어야 한다** — 체크박스로 고르고, 확인 창을 거치고,
 * 성공·실패 건수를 안내받고, 보낸 사람만 선택에서 빠진다.
 */
describe('일괄 복구', () => {
  /** 비활동 회원을 골라 복구 확인 창까지 연다. */
  async function selectAndConfirm() {
    fireEvent.click(within(row('비활동회원')).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '선택한 1명 복구' }))
    return screen.findByText(/1명을 이번 학기 활동 부원으로 되돌립니다/)
  }

  it('확인 전에는 복구되지 않고 누구를 복구하는지 보여준다', async () => {
    renderAt()
    await loaded()

    const body = await selectAndConfirm()

    expect(body).toHaveTextContent('비활동회원')
    expect(api.reactivated).toEqual([])
  })

  // T-364 — 고른 id만 실린다. 전원을 올리면 비활성화가 무의미해진다.
  it('확인하면 고른 id만 보낸다', async () => {
    renderAt()
    await loaded()

    await selectAndConfirm()
    fireEvent.click(screen.getByRole('button', { name: '복구' }))

    await waitFor(() => expect(api.reactivated).toEqual([[6]]))
    expect(await screen.findByRole('status')).toHaveTextContent(
      '1명을 복구했습니다.',
    )
  })

  /*
   * T-365 — **부분 실패를 안내한다.** 전부 성공한 것처럼 보이면 안 된다. 특히
   * `NOT_INACTIVE`에는 정지된 계정이 섞여 있어, 뭉뚱그리면 관리자는 정지된 사람이
   * 올라온 줄 알고 자리를 뜬다.
   */
  it('일부가 실패하면 누가 왜 실패했는지 말한다', async () => {
    api.reactivateResult = {
      reactivated: [],
      failed: [{ userId: 6, reason: 'NOT_INACTIVE' }],
    }

    renderAt()
    await loaded()

    await selectAndConfirm()
    fireEvent.click(screen.getByRole('button', { name: '복구' }))

    const notice = await screen.findByRole('status')
    expect(notice).toHaveTextContent('1명은 복구하지 못했습니다')
    expect(notice).toHaveTextContent('비활동이 아닌 계정')
    expect(notice).toHaveTextContent('비활동회원')
  })

  /*
   * 승인·거부와 같은 규칙이다 (T-161) — **보낸 사람만 뺀다.** 통째로 비우면 응답을
   * 기다리는 사이 새로 고른 사람까지 풀린다.
   */
  it('복구한 사람만 선택에서 빠진다', async () => {
    renderAt()
    await loaded()

    fireEvent.click(within(row('신청한하나')).getByRole('checkbox'))
    await selectAndConfirm()
    fireEvent.click(screen.getByRole('button', { name: '복구' }))

    await screen.findByText(/1명을 복구했습니다/)
    expect(screen.getByText(/이 페이지에서 1명 선택됨/)).toBeInTheDocument()
    expect(within(row('신청한하나')).getByRole('checkbox')).toBeChecked()
  })

  /*
   * **한 선택 안에서 조작이 갈린다.** 승인 버튼이 복구 대상까지 들고 가면 서버가
   * `NOT_PENDING`으로 되돌려 보내고, 관리자는 자기가 고른 사람이 왜 실패했는지 모른다.
   */
  it('승인 대상과 복구 대상이 섞여 있으면 각 버튼이 자기 몫만 가져간다', async () => {
    renderAt()
    await loaded()

    fireEvent.click(
      screen.getByRole('checkbox', {
        name: '이 페이지의 일괄 처리 대상 전체 선택',
      }),
    )

    expect(
      screen.getByRole('button', { name: '선택한 2명 승인' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: '선택한 1명 복구' }),
    ).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '선택한 1명 복구' }))
    await screen.findByText(/1명을 이번 학기 활동 부원으로 되돌립니다/)
    fireEvent.click(screen.getByRole('button', { name: '복구' }))

    await waitFor(() => expect(api.reactivated).toEqual([[6]]))
  })
})

/**
 * 비활동 회원을 화면에서 찾을 수 있는가 (T-362·T-363).
 *
 * **복구가 여기에 매달려 있다.** 복구는 id 목록을 받으므로, 비활동 회원을 찾을 수도
 * 고를 수도 없으면 전원을 내린 뒤 아무도 다시 올릴 수 없다 — API만 있고 누를 곳이 없다.
 */
describe('비활동 표시와 필터', () => {
  // T-362 MUST — "정지"와 같은 낱말이면 관리자가 정지된 사람을 복구하려다 실패한다.
  it('상태 칸이 정지와 다른 낱말로 비활동을 보여준다', async () => {
    renderAt()
    await loaded()

    expect(within(row('비활동회원')).getByText('비활동')).toBeInTheDocument()
    expect(within(row('정지회원')).getByText('정지')).toBeInTheDocument()
    expect(within(row('비활동회원')).queryByText('정지')).toBeNull()
  })

  // T-363 — 필터로 추리고 주소에 남는다 (뒤로가기·새로고침·링크 공유).
  it('비활동을 고르면 status=INACTIVE로 조회하고 주소에 남는다', async () => {
    renderAt()
    await loaded()

    fireEvent.change(screen.getByLabelText('상태'), {
      target: { value: 'INACTIVE' },
    })

    await waitFor(() => {
      expect(api.queries.at(-1)?.status).toBe('INACTIVE')
      // 승인 대기와 달리 applied는 실리지 않는다 — 그 조건은 PENDING의 것이다.
      expect(api.queries.at(-1)?.applied).toBeUndefined()
    })
    expect(pathname()).toBe('/admin/members')
    expect(screen.getByLabelText('상태')).toHaveValue('INACTIVE')
  })
})
