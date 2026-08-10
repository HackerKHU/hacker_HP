import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/App'
import type { AdminUserQuery, ApproveResult } from '@/api/adminUsers'
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
  approveResult: null as ApproveResult | null,
  approveError: null as ApiError | null,
  statusError: null as ApiError | null,
  listError: null as ApiError | null,
}))

function member(overrides: Partial<User> & { id: number; name: string }): User {
  return {
    email: `user${overrides.id}@khu.ac.kr`,
    studentNo: '2021123456',
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
    appliedAt: null,
    approvedAt: null,
  }),
  member({ id: 4, name: '활동회원', status: 'ACTIVE' }),
  member({ id: 5, name: '정지회원', status: 'SUSPENDED' }),
]

vi.mock('@/api/adminUsers', () => ({
  list: (query: AdminUserQuery): Promise<Page<User>> => {
    api.queries.push(query)
    if (api.listError) return Promise.reject(api.listError)
    return Promise.resolve({
      content: MEMBERS,
      page: { size: 20, number: 0, totalElements: 42, totalPages: 3 },
    })
  },
  approve: (userIds: number[]): Promise<ApproveResult> => {
    if (api.approveError) return Promise.reject(api.approveError)
    api.approved.push(userIds)
    return Promise.resolve(
      api.approveResult ?? { approved: userIds, failed: [] },
    )
  },
  updateStatus: (id: number, status: string): Promise<User> => {
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
  return <div data-testid="pathname">{pathname}</div>
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
  api.approveResult = null
  api.approveError = null
  api.statusError = null
  api.listError = null
  auth.role = 'ADMIN'
  for (const user of MEMBERS) {
    if (user.id <= 3) user.status = 'PENDING'
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
  it('신청서를 내지 않은 PENDING은 선택할 수 없고 이유가 보인다', async () => {
    renderAt()
    await loaded()

    const target = within(row('미신청')).getByRole('checkbox')
    expect(target).toBeDisabled()
    expect(row('미신청')).toHaveTextContent('신청서 미제출')
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
  it('가입 신청일 칸에 createdAt이 아니라 appliedAt이 나온다', async () => {
    renderAt()
    await loaded()

    const cells = within(row('신청한하나')).getAllByRole('cell')
    // 0 체크박스, 1 이름, 2 학번, 3 이메일, 4 권한, 5 상태, 6 가입 신청일, 7 승인일
    expect(cells[6]).toHaveTextContent('2026. 03. 02.')
    expect(cells[6]).not.toHaveTextContent('2026. 03. 01.')
  })

  it('신청서를 낸 PENDING은 선택할 수 있다', async () => {
    renderAt()

    expect(await loaded()).toBeEnabled()
    expect(within(row('신청한둘')).getByRole('checkbox')).toBeEnabled()
  })
})

describe('전체 선택', () => {
  /*
   * **범위는 이 페이지의 승인 대상뿐이다.** 검색 결과 전부를 뜻하면 관리자가 보지 못한
   * 사람까지 승인하게 된다 — 되돌릴 수 없는 조작이다.
   */
  it('전체 선택은 이 페이지의 승인 대상만 고른다', async () => {
    renderAt()
    await loaded()

    fireEvent.click(
      screen.getByRole('checkbox', { name: '이 페이지의 승인 대상 전체 선택' }),
    )

    expect(within(row('신청한하나')).getByRole('checkbox')).toBeChecked()
    expect(within(row('신청한둘')).getByRole('checkbox')).toBeChecked()
    expect(within(row('미신청')).getByRole('checkbox')).not.toBeChecked()
    expect(within(row('활동회원')).getByRole('checkbox')).not.toBeChecked()
  })

  // 선택 범위를 화면이 말하지 않으면 그것대로 관리자를 속이는 것이다.
  it('선택 범위가 이 페이지임을 화면이 말한다', async () => {
    renderAt()
    await loaded()

    fireEvent.click(
      screen.getByRole('checkbox', { name: '이 페이지의 승인 대상 전체 선택' }),
    )

    expect(screen.getByText(/이 페이지에서 2명 선택됨/)).toBeInTheDocument()
    // 전체 인원도 함께 보여 페이지가 전부가 아님이 드러난다.
    expect(screen.getByText(/전체 42명/)).toBeInTheDocument()
  })

  it('다시 누르면 전부 해제된다', async () => {
    renderAt()
    await loaded()

    const all = screen.getByRole('checkbox', {
      name: '이 페이지의 승인 대상 전체 선택',
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

  it('아무도 선택하지 않으면 승인 버튼이 잠겨 있다', async () => {
    renderAt()
    await loaded()

    expect(
      screen.getByRole('button', { name: /선택한 0명 승인/ }),
    ).toBeDisabled()
  })
})

describe('상태 변경', () => {
  it('활동중 회원을 정지하고 결과를 안내한다', async () => {
    renderAt()
    await loaded()

    fireEvent.click(
      within(row('활동회원')).getByRole('button', { name: '정지' }),
    )

    expect(await screen.findByRole('status')).toHaveTextContent(
      '활동회원 회원을 정지했습니다.',
    )
    expect(api.statusCalls).toEqual([{ id: 4, status: 'SUSPENDED' }])
  })

  it('정지된 회원은 해제로 되돌린다', async () => {
    renderAt()
    await loaded()

    fireEvent.click(
      within(row('정지회원')).getByRole('button', { name: '정지 해제' }),
    )

    await waitFor(() => {
      expect(api.statusCalls).toEqual([{ id: 5, status: 'ACTIVE' }])
    })
  })

  /*
   * 2-2 §2-2-7 MUST — 마지막 활성 관리자의 자기 정지는 **서버가** 막는다. 화면은 활성
   * 관리자가 몇 명인지 모르므로 미리 판단하지 않고, 서버가 준 거부 사유를 그대로 보여준다.
   * 로그아웃에서 "실패인데 성공처럼 보이는" 결함이 있었다 — 같은 실수를 반복하지 않는다.
   */
  it('서버가 막으면 그 사유를 그대로 보여준다', async () => {
    api.statusError = new ApiError(
      'FORBIDDEN',
      403,
      '마지막 활성 관리자는 자기 자신을 정지할 수 없습니다.',
    )

    renderAt()
    await loaded()

    fireEvent.click(
      within(row('활동회원')).getByRole('button', { name: '정지' }),
    )

    const status = await screen.findByRole('status')
    expect(status).toHaveTextContent('상태를 바꾸지 못했습니다')
    expect(status).toHaveTextContent('마지막 활성 관리자')
    expect(status).not.toHaveTextContent('정지했습니다.')
  })

  // PENDING에게는 정지가 의미 없다. 승인 전에는 로그인 자체가 막혀 있다.
  it('PENDING 행에는 정지 버튼이 없다', async () => {
    renderAt()
    await loaded()

    expect(within(row('신청한하나')).queryByRole('button')).toBeNull()
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

  it.each([
    ['상태', 'PENDING', 'status'],
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
      target: { value: 'PENDING' },
    })

    await waitFor(() => {
      expect(api.queries.at(-1)?.page).toBe(0)
    })
  })

  it('목록을 불러오지 못하면 안내한다', async () => {
    api.listError = new ApiError('FORBIDDEN', 403, '권한이 없습니다.')

    renderAt()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '회원 목록을 불러오지 못했습니다',
    )
  })
})
