import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/App'
import type {
  AdminUserQuery,
  ApproveResult,
  BulkStatusResult,
  BulkStatusTarget,
  ContentSummary,
  DeactivateResult,
  RejectResult,
} from '@/api/adminUsers'
import { ApiError } from '@/api/client'
import type { Page, Role, User } from '@/api/types'
import { SessionProvider } from '@/auth/session'
import { MemoryRouter, useLocation, useNavigate } from '@/test/TestRouter'

const api = vi.hoisted(() => ({
  queries: [] as AdminUserQuery[],
  approved: [] as number[][],
  rejected: [] as number[][],
  bulkCalls: [] as { userIds: number[]; status: BulkStatusTarget }[],
  deactivateCalls: [] as number[][],
  statusCalls: [] as { id: number; status: string }[],
  roleCalls: [] as { id: number; role: Role }[],
  summaryCalls: [] as number[],
  removed: [] as number[],
  approveResult: null as ApproveResult | null,
  rejectResult: null as RejectResult | null,
  bulkResult: null as BulkStatusResult | null,
  deactivateResult: null as DeactivateResult | null,
  bulkError: null as ApiError | null,
  deactivateError: null as ApiError | null,
  listError: null as ApiError | null,
  holdBulk: false,
  releaseBulk: null as (() => void) | null,
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

const ORIGINAL_MEMBERS: User[] = [
  member({ id: 1, name: '신청완료', status: 'PENDING', approvedAt: null }),
  member({
    id: 2,
    name: '미신청',
    status: 'PENDING',
    studentNo: null,
    department: null,
    appliedAt: null,
    approvedAt: null,
  }),
  member({ id: 3, name: '활동회원', status: 'ACTIVE' }),
  member({ id: 4, name: '비활동회원', status: 'INACTIVE' }),
  member({ id: 5, name: '정지회원', status: 'SUSPENDED' }),
  member({ id: 99, name: '김관리', role: 'ADMIN', status: 'ACTIVE' }),
]

let members: User[] = []

vi.mock('@/api/adminUsers', () => ({
  list: async (query: AdminUserQuery): Promise<Page<User>> => {
    api.queries.push(query)
    if (api.listError) throw api.listError
    await new Promise((resolve) => setTimeout(resolve, 0))
    return {
      content: members.map((user) => ({ ...user })),
      page: {
        size: 20,
        number: query.page ?? 0,
        totalElements: 42,
        totalPages: 3,
      },
    }
  },
  approve: async (userIds: number[]): Promise<ApproveResult> => {
    api.approved.push(userIds)
    return api.approveResult ?? { approved: userIds, failed: [] }
  },
  reject: async (userIds: number[]): Promise<RejectResult> => {
    api.rejected.push(userIds)
    return api.rejectResult ?? { rejected: userIds, failed: [] }
  },
  bulkUpdateStatus: async (
    userIds: number[],
    status: BulkStatusTarget,
  ): Promise<BulkStatusResult> => {
    api.bulkCalls.push({ userIds, status })
    if (api.bulkError) throw api.bulkError
    if (api.holdBulk) {
      await new Promise<void>((resolve) => {
        api.releaseBulk = resolve
      })
    }
    return (
      api.bulkResult ?? {
        targetStatus: status,
        processed: userIds,
        failed: [],
      }
    )
  },
  deactivate: async (userIds: number[]): Promise<DeactivateResult> => {
    api.deactivateCalls.push(userIds)
    if (api.deactivateError) throw api.deactivateError
    return (
      api.deactivateResult ?? {
        deactivated: userIds,
        failed: [],
      }
    )
  },
  contentSummary: async (id: number): Promise<ContentSummary> => {
    api.summaryCalls.push(id)
    return { notes: 3, notices: 1, photos: 5, posts: 7 }
  },
  remove: async (id: number) => {
    api.removed.push(id)
  },
  updateRole: async (id: number, role: Role): Promise<User> => {
    api.roleCalls.push({ id, role })
    return { ...members[0], role }
  },
  updateStatus: async (id: number, status: string): Promise<User> => {
    api.statusCalls.push({ id, status })
    return members[0]
  },
}))

const auth = vi.hoisted(() => ({ role: 'ADMIN' as Role }))
const ME = member({ id: 99, name: '김관리', role: 'ADMIN' })

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
  get: () => Promise.reject(new Error('사용하지 않음')),
  togglePin: () => Promise.reject(new Error('사용하지 않음')),
  create: () => Promise.reject(new Error('사용하지 않음')),
  update: () => Promise.reject(new Error('사용하지 않음')),
  remove: () => Promise.reject(new Error('사용하지 않음')),
}))

function Address() {
  const { pathname } = useLocation()
  const navigate = useNavigate()
  return (
    <>
      <div data-testid="pathname">{pathname}</div>
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

function loaded() {
  return screen.findByRole('checkbox', { name: '신청완료 선택' })
}

function row(name: string): HTMLElement {
  const found = screen.getByRole('cell', { name }).closest('tr')
  if (!found) throw new Error(`${name} 행을 찾지 못했다`)
  return found
}

function openRowMenu(name: string) {
  const trigger = within(row(name)).getByRole('button', {
    name: `${name} 관리 메뉴`,
  })
  fireEvent.pointerDown(
    trigger,
    new MouseEvent('pointerdown', { bubbles: true, button: 0 }),
  )
}

async function confirmBulk(button: string, confirm: string) {
  fireEvent.click(screen.getByRole('button', { name: button }))
  const dialog = await screen.findByRole('alertdialog')
  fireEvent.click(within(dialog).getByRole('button', { name: confirm }))
}

const BULK_LABELS = [
  '선택 항목 활성화',
  '선택 항목 비활성화',
  '선택 항목 정지',
] as const

beforeEach(() => {
  members = ORIGINAL_MEMBERS.map((user) => ({ ...user }))
  api.queries = []
  api.approved = []
  api.rejected = []
  api.bulkCalls = []
  api.deactivateCalls = []
  api.statusCalls = []
  api.roleCalls = []
  api.summaryCalls = []
  api.removed = []
  api.approveResult = null
  api.rejectResult = null
  api.bulkResult = null
  api.deactivateResult = null
  api.bulkError = null
  api.deactivateError = null
  api.listError = null
  api.holdBulk = false
  api.releaseBulk = null
  auth.role = 'ADMIN'
})

describe('회원 목록과 선택', () => {
  it('USER의 관리자 라우트 접근을 막는다', async () => {
    auth.role = 'USER'
    renderAt()

    await waitFor(() =>
      expect(screen.getByTestId('pathname')).toHaveTextContent('/'),
    )
    expect(screen.queryByRole('heading', { name: '회원 관리' })).toBeNull()
  })

  it('회원 목록은 안정된 list surface 안에 그려진다', async () => {
    renderAt()
    await loaded()

    expect(
      document.querySelector('[data-list-surface="members"]'),
    ).not.toBeNull()
    expect(document.querySelector('[data-pager-slot="true"]')).not.toBeNull()
    expect(within(row('미신청')).getByText('미승인')).toBeInTheDocument()
    expect(within(row('비활동회원')).getByText('비활동')).toBeInTheDocument()
    expect(within(row('정지회원')).getByText('정지')).toBeInTheDocument()
  })

  it('세 버튼만 정확한 순서·라벨·동일 class/variant/size로 항상 둔다', async () => {
    renderAt()
    await loaded()

    const buttons = BULK_LABELS.map((name) =>
      screen.getByRole('button', { name }),
    )
    const group = buttons[0].parentElement
    if (!group) throw new Error('일괄 버튼 그룹이 없다')
    expect(
      within(group)
        .getAllByRole('button')
        .map((button) => button.textContent),
    ).toEqual(BULK_LABELS)
    expect(new Set(buttons.map((button) => button.className))).toHaveLength(1)
    for (const button of buttons) {
      expect(button).toHaveAttribute('data-variant', 'outline')
      expect(button).toHaveAttribute('data-size', 'sm')
      expect(button).toBeDisabled()
    }
    expect(
      screen.queryByRole('button', { name: '일괄 비활동 전환' }),
    ).toBeNull()
    expect(
      screen.queryByRole('button', { name: /선택한 .*명 승인/ }),
    ).toBeNull()
    expect(
      screen.queryByRole('button', { name: /선택한 .*명 거부/ }),
    ).toBeNull()
    expect(
      screen.queryByRole('button', { name: /선택한 .*명 복구/ }),
    ).toBeNull()
  })

  it('모든 status와 role의 현재 페이지 행을 개별 선택할 수 있다', async () => {
    renderAt()
    await loaded()

    for (const user of members) {
      const checkbox = within(row(user.name)).getByRole('checkbox')
      expect(checkbox).not.toBeDisabled()
      fireEvent.click(checkbox)
      expect(checkbox).toBeChecked()
    }
    expect(screen.getByText(/이 페이지에서 6명 선택됨/)).toBeInTheDocument()
  })

  it('헤더 checkbox는 accessible name과 indeterminate를 갖고 현재 페이지만 토글한다', async () => {
    renderAt()
    await loaded()

    const header = screen.getByRole('checkbox', {
      name: '이 페이지의 모든 회원 선택',
    })
    fireEvent.click(within(row('활동회원')).getByRole('checkbox'))
    expect(header).toHaveAttribute('aria-checked', 'mixed')

    fireEvent.click(header)
    for (const user of members) {
      expect(within(row(user.name)).getByRole('checkbox')).toBeChecked()
    }
    expect(screen.getByText(/검색 결과 전체 42명/)).toBeInTheDocument()

    fireEvent.click(header)
    for (const user of members) {
      expect(within(row(user.name)).getByRole('checkbox')).not.toBeChecked()
    }
  })
})

describe('선택 상태 일괄 조작', () => {
  it.each([
    [
      '선택 항목 활성화',
      '활성화',
      '선택한 회원을 활성화할까요?',
      '신청서를 내지 않은 승인 대기 계정',
    ],
    [
      '선택 항목 비활성화',
      '비활성화',
      '선택한 회원을 비활성화할까요?',
      '관리자·승인 대기·정지·이미 비활동인 계정',
    ],
    [
      '선택 항목 정지',
      '정지',
      '선택한 회원을 정지할까요?',
      '관리자 계정은 실패',
    ],
  ] as const)(
    '%s는 대상 수·이름·상태별 실패 가능성을 확인한 뒤에만 실행된다',
    async (button, confirm, title, warning) => {
      renderAt()
      await loaded()
      fireEvent.click(within(row('미신청')).getByRole('checkbox'))
      fireEvent.click(within(row('김관리')).getByRole('checkbox'))

      fireEvent.click(screen.getByRole('button', { name: button }))
      const dialog = await screen.findByRole('alertdialog')
      expect(within(dialog).getByRole('heading')).toHaveTextContent(title)
      expect(dialog).toHaveTextContent('2명')
      expect(dialog).toHaveTextContent('미신청')
      expect(dialog).toHaveTextContent('김관리')
      expect(dialog).toHaveTextContent(warning)
      expect(api.bulkCalls).toEqual([])
      expect(api.deactivateCalls).toEqual([])

      fireEvent.click(within(dialog).getByRole('button', { name: confirm }))
    },
  )

  it('활성화·정지는 각각 선택 id와 목표를 PATCH 한 번에 보낸다', async () => {
    renderAt()
    await loaded()
    fireEvent.click(within(row('신청완료')).getByRole('checkbox'))
    fireEvent.click(within(row('정지회원')).getByRole('checkbox'))

    await confirmBulk('선택 항목 활성화', '활성화')
    await waitFor(() =>
      expect(api.bulkCalls).toEqual([{ userIds: [1, 5], status: 'ACTIVE' }]),
    )
    await loaded()

    fireEvent.click(within(row('활동회원')).getByRole('checkbox'))
    await confirmBulk('선택 항목 정지', '정지')
    await waitFor(() =>
      expect(api.bulkCalls).toEqual([
        { userIds: [1, 5], status: 'ACTIVE' },
        { userIds: [3], status: 'SUSPENDED' },
      ]),
    )
  })

  it('비활성화는 선택 id를 POST 한 번에 보낸다', async () => {
    renderAt()
    await loaded()
    fireEvent.click(within(row('활동회원')).getByRole('checkbox'))
    fireEvent.click(within(row('김관리')).getByRole('checkbox'))

    await confirmBulk('선택 항목 비활성화', '비활성화')

    await waitFor(() => expect(api.deactivateCalls).toEqual([[3, 99]]))
  })

  it('요청 중에는 세 버튼을 모두 잠근다', async () => {
    api.holdBulk = true
    renderAt()
    await loaded()
    fireEvent.click(within(row('활동회원')).getByRole('checkbox'))

    await confirmBulk('선택 항목 정지', '정지')
    await waitFor(() => expect(api.bulkCalls).toHaveLength(1))
    for (const label of BULK_LABELS) {
      expect(screen.getByRole('button', { name: label })).toBeDisabled()
    }

    api.releaseBulk?.()
    await waitFor(() =>
      expect(screen.getByText(/1명을 정지 처리했습니다/)).toBeInTheDocument(),
    )
  })

  it('processed의 멱등 결과와 부분 실패 사유를 성공으로 뭉개지 않는다', async () => {
    api.bulkResult = {
      targetStatus: 'ACTIVE',
      processed: [3, 5],
      failed: [
        { userId: 2, reason: 'NOT_APPLIED' },
        { userId: 777, reason: 'NOT_FOUND' },
      ],
    }
    renderAt()
    await loaded()
    for (const name of ['활동회원', '미신청', '정지회원']) {
      fireEvent.click(within(row(name)).getByRole('checkbox'))
    }

    await confirmBulk('선택 항목 활성화', '활성화')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('2명을 활성화 처리했습니다')
    expect(alert).toHaveTextContent('이미 목표 상태인 멱등 결과 포함')
    const text = alert.textContent ?? ''
    expect(text).toContain('활동회원, 정지회원')
    expect(text.indexOf('활동회원')).toBeLessThan(text.indexOf('정지회원'))
    expect(alert).toHaveTextContent('2명은 처리하지 못했습니다')
    expect(alert).toHaveTextContent('미신청: 신청서를 내지 않은 계정')
    expect(alert).toHaveTextContent('#777: 찾을 수 없는 계정')
  })

  it('실패 배열의 입력 순서를 보존해 이유를 안내한다', async () => {
    api.bulkResult = {
      targetStatus: 'SUSPENDED',
      processed: [3],
      failed: [
        { userId: 99, reason: 'ADMIN_SUSPEND_REQUIRES_ROLE_REVOCATION' },
        { userId: 1, reason: 'PENDING_NOT_ALLOWED' },
      ],
    }
    renderAt()
    await loaded()
    fireEvent.click(
      screen.getByRole('checkbox', { name: '이 페이지의 모든 회원 선택' }),
    )

    await confirmBulk('선택 항목 정지', '정지')

    const text = (await screen.findByRole('alert')).textContent ?? ''
    expect(text.indexOf('김관리')).toBeLessThan(text.indexOf('신청완료'))
    expect(text).toContain('관리자 권한을 먼저 회수')
    expect(text).toContain('승인 대기 상태라 정지할 수 없는')
  })

  it('비활성화 혼합 결과의 변경·실패와 사유를 그대로 안내한다', async () => {
    api.deactivateResult = {
      deactivated: [3],
      failed: [
        { userId: 99, reason: 'NOT_ACTIVE_USER' },
        { userId: 777, reason: 'NOT_FOUND' },
      ],
    }
    renderAt()
    await loaded()
    fireEvent.click(within(row('활동회원')).getByRole('checkbox'))
    fireEvent.click(within(row('김관리')).getByRole('checkbox'))

    await confirmBulk('선택 항목 비활성화', '비활성화')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('1명을 비활성화했습니다')
    expect(alert).toHaveTextContent('활동회원')
    expect(alert).toHaveTextContent('2명은 변경하지 못했습니다')
    expect(alert).toHaveTextContent('김관리: 활동 중인 일반 부원이 아님')
    expect(alert).toHaveTextContent('#777: 찾을 수 없는 계정')
  })

  it.each([
    [400, 'VALIDATION_ERROR', '선택값이 올바르지 않습니다.'],
    [403, 'FORBIDDEN', '권한이 없습니다.'],
  ] as const)(
    '%i 오류는 서버 사유를 보여주고 성공으로 말하지 않는다',
    async (status, code, message) => {
      api.bulkError = new ApiError(code, status, message)
      renderAt()
      await loaded()
      fireEvent.click(within(row('활동회원')).getByRole('checkbox'))

      await confirmBulk('선택 항목 정지', '정지')

      const alert = await screen.findByRole('alert')
      expect(alert).toHaveTextContent(`정지하지 못했습니다. ${message}`)
      expect(alert).not.toHaveTextContent('정지 처리했습니다')
    },
  )

  it('500이면 변경 없음으로 단정하지 않고 재조회·재선택·재시도를 안내한다', async () => {
    api.bulkError = new ApiError('INVALID_RESPONSE', 500, '서버 오류입니다.')
    renderAt()
    await loaded()
    fireEvent.click(within(row('활동회원')).getByRole('checkbox'))
    const before = api.queries.length

    await confirmBulk('선택 항목 정지', '정지')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('일부 상태 변경이 반영되었을 수 있습니다')
    expect(alert).toHaveTextContent('다시 불러온 목록에서 상태를 확인')
    expect(alert).toHaveTextContent('다시 선택해 재시도')
    await waitFor(() => expect(api.queries.length).toBeGreaterThan(before))
  })
})

describe('조회 조건 변경', () => {
  it.each([
    [
      '검색어',
      () => {
        fireEvent.change(screen.getByLabelText('검색'), {
          target: { value: '강' },
        })
        fireEvent.click(screen.getByRole('button', { name: '검색' }))
      },
    ],
    [
      '상태',
      () =>
        fireEvent.change(screen.getByLabelText('상태'), {
          target: { value: 'ACTIVE' },
        }),
    ],
    [
      '권한',
      () =>
        fireEvent.change(screen.getByLabelText('권한'), {
          target: { value: 'ADMIN' },
        }),
    ],
    [
      '정렬',
      () =>
        fireEvent.change(screen.getByLabelText('정렬'), {
          target: { value: 'name' },
        }),
    ],
    [
      '페이지',
      () => fireEvent.click(screen.getByRole('button', { name: '다음' })),
    ],
  ] as const)(
    '%s 변경은 selection을 해제하고 info alert로 이유를 알린다',
    async (_label, change) => {
      renderAt()
      await loaded()
      fireEvent.click(within(row('활동회원')).getByRole('checkbox'))

      change()

      expect(await screen.findByRole('status')).toHaveTextContent(
        '조회 조건이 바뀌어 선택한 1명이 해제되었습니다.',
      )
      await waitFor(() =>
        expect(
          screen.getByText(/이 페이지에서 0명 선택됨/),
        ).toBeInTheDocument(),
      )
    },
  )

  it('조회 조건 변경은 열린 confirm dialog도 닫고 info alert로 알린다', async () => {
    renderAt()
    await loaded()
    fireEvent.click(within(row('활동회원')).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '선택 항목 정지' }))
    expect(await screen.findByRole('alertdialog')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('정렬'), {
      target: { value: 'name' },
    })

    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    expect(await screen.findByRole('status')).toHaveTextContent(
      '진행 중이던 확인을 닫고 선택한 1명이 해제되었습니다.',
    )
  })
})

describe('행 조작과 목록 회귀', () => {
  it('행별 승인은 확인 뒤 그 id만 보낸다', async () => {
    renderAt()
    await loaded()

    fireEvent.click(
      within(row('신청완료')).getByRole('button', { name: '승인' }),
    )
    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('신청완료')
    fireEvent.click(within(dialog).getByRole('button', { name: '승인' }))

    await waitFor(() => expect(api.approved).toEqual([[1]]))
  })

  it('일괄 거부 없이 행별 거부를 확인 뒤 실행한다', async () => {
    renderAt()
    await loaded()

    fireEvent.click(
      within(row('신청완료')).getByRole('button', { name: '거부' }),
    )
    const dialog = await screen.findByRole('alertdialog')
    expect(dialog).toHaveTextContent('신청완료')
    fireEvent.click(within(dialog).getByRole('button', { name: '거부' }))

    await waitFor(() => expect(api.rejected).toEqual([[1]]))
  })

  it.each([
    ['활동회원', '정지', 3, 'SUSPENDED'],
    ['정지회원', '정지 해제', 5, 'ACTIVE'],
  ] as const)(
    '기존 행 메뉴에서 %s의 %s를 유지한다',
    async (name, label, id, status) => {
      renderAt()
      await loaded()

      openRowMenu(name)
      fireEvent.click(await screen.findByRole('menuitem', { name: label }))
      const dialog = await screen.findByRole('alertdialog')
      fireEvent.click(within(dialog).getByRole('button', { name: label }))

      await waitFor(() => expect(api.statusCalls).toEqual([{ id, status }]))
    },
  )

  it('기존 행 메뉴의 role 변경을 확인 뒤 실행한다', async () => {
    renderAt()
    await loaded()

    openRowMenu('활동회원')
    fireEvent.click(
      await screen.findByRole('menuitem', { name: '관리자 지정' }),
    )
    const dialog = await screen.findByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: '관리자 지정' }))

    await waitFor(() =>
      expect(api.roleCalls).toEqual([{ id: 3, role: 'ADMIN' }]),
    )
  })

  it('목록 조회 실패는 list surface 안에서 한 번 알리고 재시도한다', async () => {
    api.listError = new ApiError('NETWORK_ERROR', 0, '연결 실패')
    renderAt()

    const error = await screen.findByRole('alert')
    expect(error).toHaveTextContent('회원 목록을 불러오지 못했습니다')
    expect(error.closest('[data-list-surface="members"]')).not.toBeNull()
    const before = api.queries.length
    api.listError = null
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    await loaded()
    expect(api.queries.length).toBeGreaterThan(before)
  })
})
