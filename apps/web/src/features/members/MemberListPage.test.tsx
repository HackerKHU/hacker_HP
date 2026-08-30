import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  act,
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
  roleCalls: [] as { id: number; role: Role }[],
  roleError: null as ApiError | null,
  summaryCalls: [] as number[],
  summaryError: null as ApiError | null,
  removed: [] as number[],
  approveResult: null as ApproveResult | null,
  rejectResult: null as RejectResult | null,
  approveError: null as Error | null,
  rejectError: null as Error | null,
  bulkResult: null as BulkStatusResult | null,
  deactivateResult: null as DeactivateResult | null,
  bulkError: null as ApiError | null,
  deactivateError: null as ApiError | null,
  listError: null as ApiError | null,
  holdBulk: false,
  releaseBulk: null as (() => void) | null,
  holdApprove: false,
  releaseApprove: null as (() => void) | null,
  holdReject: false,
  releaseReject: null as (() => void) | null,
  totalElements: 42,
  totalPages: 3,
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
        totalElements: api.totalElements,
        totalPages: api.totalPages,
      },
    }
  },
  approve: async (userIds: number[]): Promise<ApproveResult> => {
    api.approved.push(userIds)
    if (api.approveError) throw api.approveError
    if (api.holdApprove) {
      await new Promise<void>((resolve) => {
        api.releaseApprove = resolve
      })
    }
    return api.approveResult ?? { approved: userIds, failed: [] }
  },
  reject: async (userIds: number[]): Promise<RejectResult> => {
    api.rejected.push(userIds)
    if (api.rejectError) throw api.rejectError
    if (api.holdReject) {
      await new Promise<void>((resolve) => {
        api.releaseReject = resolve
      })
    }
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
    if (api.summaryError) throw api.summaryError
    return { notes: 3, notices: 1, photos: 5, posts: 7 }
  },
  remove: async (id: number) => {
    api.removed.push(id)
  },
  updateRole: async (id: number, role: Role): Promise<User> => {
    api.roleCalls.push({ id, role })
    if (api.roleError) throw api.roleError
    const found = members.find((user) => user.id === id) ?? members[0]
    if (role === 'ADMIN') {
      found.status = 'ACTIVE'
      found.deactivatedAt = null
    }
    found.role = role
    return { ...found }
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
  const { pathname, search } = useLocation()
  const navigate = useNavigate()
  return (
    <>
      <div data-testid="pathname">{pathname}</div>
      <div data-testid="search">{search}</div>
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

async function rowAction(name: string, label: string | RegExp) {
  openRowMenu(name)
  fireEvent.click(await screen.findByRole('menuitem', { name: label }))
}

async function confirmBulk(button: string, confirm: string) {
  fireEvent.click(screen.getByRole('button', { name: button }))
  const dialog = await screen.findByRole('alertdialog')
  fireEvent.click(within(dialog).getByRole('button', { name: confirm }))
}

const SELECTION_ACTION_LABELS = [
  '선택한 회원 승인',
  '선택한 회원 거부',
  '선택한 회원 활성화',
  '선택한 회원 비활성화',
  '선택한 회원 정지',
] as const

beforeEach(() => {
  members = ORIGINAL_MEMBERS.map((user) => ({ ...user }))
  api.queries = []
  api.approved = []
  api.rejected = []
  api.bulkCalls = []
  api.deactivateCalls = []
  api.roleCalls = []
  api.roleError = null
  api.summaryCalls = []
  api.summaryError = null
  api.removed = []
  api.approveResult = null
  api.rejectResult = null
  api.approveError = null
  api.rejectError = null
  api.bulkResult = null
  api.deactivateResult = null
  api.bulkError = null
  api.deactivateError = null
  api.listError = null
  api.holdBulk = false
  api.releaseBulk = null
  api.holdApprove = false
  api.releaseApprove = null
  api.holdReject = false
  api.releaseReject = null
  api.totalElements = 42
  api.totalPages = 3
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

  it('다섯 버튼을 정확한 순서·라벨·동일 class/variant/size로 항상 둔다', async () => {
    renderAt()
    await loaded()

    const buttons = SELECTION_ACTION_LABELS.map((name) =>
      screen.getByRole('button', { name }),
    )
    const group = buttons[0].parentElement
    if (!group) throw new Error('일괄 버튼 그룹이 없다')
    expect(
      within(group)
        .getAllByRole('button')
        .map((button) => button.textContent),
    ).toEqual(SELECTION_ACTION_LABELS)
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
  it('AlertDialog modal lock은 유지하되 이미 예약한 scrollbar 폭을 body margin에 더하지 않는다', async () => {
    renderAt()
    await loaded()
    expect(document.body).not.toHaveAttribute('data-scroll-locked')
    const initialInlineMargin = document.body.style.marginRight

    fireEvent.click(within(row('활동회원')).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '선택한 회원 정지' }))
    await screen.findByRole('alertdialog')

    // modal·focus trap·scroll lock을 없애는 식으로 위치 흔들림을 숨기지 않는다.
    await waitFor(() =>
      expect(document.body).toHaveAttribute('data-scroll-locked'),
    )
    expect(document.body.style.marginRight).toBe(initialInlineMargin)

    /*
     * jsdom은 실제 scrollbar 폭과 동적 style singleton의 cascade를 브라우저처럼 계산하지
     * 않는다. 그래서 런타임에서는 Radix lock의 생명주기를, 여기서는 실제 stylesheet의
     * 고우선순위 override를 함께 확인한다.
     */
    const raw = readFileSync(resolve(process.cwd(), 'src/index.css'), 'utf-8')
    const css = raw.replace(/\/\*[\s\S]*?\*\//g, '')
    const override = css.match(
      /html\s+body\[data-scroll-locked\]\s*\{([^}]*)\}/,
    )
    expect(override?.[1]).toMatch(/margin-right:\s*0\s*!important;/)
    expect(override?.[1]).not.toMatch(/overflow|overscroll/)

    fireEvent.click(
      within(screen.getByRole('alertdialog')).getByRole('button', {
        name: '취소',
      }),
    )
    await waitFor(() =>
      expect(document.body).not.toHaveAttribute('data-scroll-locked'),
    )
    expect(document.body.style.marginRight).toBe(initialInlineMargin)
  })

  it.each([
    [
      '선택한 회원 승인',
      '승인',
      '선택한 회원을 승인할까요?',
      '신청서를 내지 않았거나 이미 처리된 회원',
    ],
    [
      '선택한 회원 거부',
      '거부',
      '선택한 신청을 거부할까요?',
      '승인 대기 상태가 아닌 회원',
    ],
    [
      '선택한 회원 활성화',
      '활성화',
      '선택한 회원을 활성화할까요?',
      '신청서를 내지 않은 승인 대기 계정',
    ],
    [
      '선택한 회원 비활성화',
      '비활성화',
      '선택한 회원을 비활성화할까요?',
      '관리자·승인 대기·이미 비활동인 계정',
    ],
    [
      '선택한 회원 정지',
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
      if (button === '선택한 회원 거부') {
        expect(dialog).toHaveTextContent('미승인 상태로 되돌립니다')
        expect(dialog).toHaveTextContent(
          '계정은 유지되어 다시 신청할 수 있습니다',
        )
        expect(dialog).not.toHaveTextContent('계정을 지웁니다')
      }
      expect(api.approved).toEqual([])
      expect(api.rejected).toEqual([])
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

    await confirmBulk('선택한 회원 활성화', '활성화')
    await waitFor(() =>
      expect(api.bulkCalls).toEqual([{ userIds: [1, 5], status: 'ACTIVE' }]),
    )
    await loaded()

    fireEvent.click(within(row('활동회원')).getByRole('checkbox'))
    await confirmBulk('선택한 회원 정지', '정지')
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

    await confirmBulk('선택한 회원 비활성화', '비활성화')

    await waitFor(() => expect(api.deactivateCalls).toEqual([[3, 99]]))
  })

  it('선택 승인·거부는 상태를 미리 거르지 않고 고른 id를 각각 한 번만 보낸다', async () => {
    renderAt()
    await loaded()
    fireEvent.click(within(row('신청완료')).getByRole('checkbox'))
    fireEvent.click(within(row('활동회원')).getByRole('checkbox'))

    await confirmBulk('선택한 회원 승인', '승인')
    await waitFor(() => expect(api.approved).toEqual([[1, 3]]))
    await loaded()

    fireEvent.click(within(row('미신청')).getByRole('checkbox'))
    fireEvent.click(within(row('김관리')).getByRole('checkbox'))
    await confirmBulk('선택한 회원 거부', '거부')

    await waitFor(() => expect(api.rejected).toEqual([[2, 99]]))
  })

  it('선택 승인의 부분 실패는 서버 사유를 error live alert로 보여준다', async () => {
    api.approveResult = {
      approved: [1],
      failed: [{ userId: 3, reason: 'NOT_PENDING' }],
    }
    renderAt()
    await loaded()
    fireEvent.click(within(row('신청완료')).getByRole('checkbox'))
    fireEvent.click(within(row('활동회원')).getByRole('checkbox'))

    await confirmBulk('선택한 회원 승인', '승인')

    const approvalAlert = await screen.findByRole('alert')
    expect(approvalAlert).toHaveTextContent('1명을 승인하고 1명은 실패')
    expect(approvalAlert).toHaveTextContent('활동회원')
    expect(approvalAlert).toHaveTextContent('이미 승인되었거나 정지된 계정')
  })

  it('선택 거부의 부분 실패도 서버 사유를 error live alert로 보여준다', async () => {
    api.rejectResult = {
      rejected: [1],
      failed: [{ userId: 3, reason: 'NOT_PENDING' }],
    }
    renderAt()
    await loaded()
    fireEvent.click(within(row('신청완료')).getByRole('checkbox'))
    fireEvent.click(within(row('활동회원')).getByRole('checkbox'))

    await confirmBulk('선택한 회원 거부', '거부')

    const rejectionAlert = await screen.findByRole('alert')
    expect(rejectionAlert).toHaveTextContent(
      '1명의 신청을 거부해 미승인 상태로 되돌렸습니다',
    )
    expect(rejectionAlert).toHaveTextContent('1명은 거부하지 못했습니다')
    expect(rejectionAlert).toHaveTextContent(
      '승인 대기 상태가 아닌 계정: 활동회원',
    )
  })

  it.each([
    {
      action: 'approve' as const,
      button: '선택한 회원 승인',
      confirm: '승인',
      error: new ApiError('NETWORK_ERROR', 0, '연결이 끊어졌습니다.'),
      uncertainEffect: '일부 승인이 반영되었을 수 있습니다',
      selectedAfter: 2,
    },
    {
      action: 'reject' as const,
      button: '선택한 회원 거부',
      confirm: '거부',
      error: new ApiError('NETWORK_ERROR', 0, '연결이 끊어졌습니다.'),
      uncertainEffect: '일부 미승인 변경이 반영되었을 수 있습니다',
      selectedAfter: 0,
    },
  ])(
    '선택 $confirm 5xx/network는 결과를 단정하지 않고 재조회한다',
    async ({
      action,
      button,
      confirm,
      error,
      uncertainEffect,
      selectedAfter,
    }) => {
      if (action === 'approve') api.approveError = error
      else api.rejectError = error
      renderAt()
      await loaded()
      fireEvent.click(within(row('신청완료')).getByRole('checkbox'))
      fireEvent.click(within(row('활동회원')).getByRole('checkbox'))
      const queriesBefore = api.queries.length

      await confirmBulk(button, confirm)

      const alert = await screen.findByRole('alert')
      expect(alert).toHaveTextContent(
        `${confirm} 요청 결과를 확정할 수 없습니다`,
      )
      expect(alert).toHaveTextContent(uncertainEffect)
      expect(alert).toHaveTextContent('다시 불러온 목록')
      expect(screen.queryByRole('status')).toBeNull()
      await waitFor(() =>
        expect(api.queries.length).toBeGreaterThan(queriesBefore),
      )
      await waitFor(() =>
        expect(
          screen.getByText(
            new RegExp(`이 페이지에서 ${selectedAfter}명 선택됨`),
          ),
        ).toBeInTheDocument(),
      )
    },
  )

  it.each([
    {
      action: 'approve' as const,
      button: '선택한 회원 승인',
      confirm: '승인',
      error: new ApiError(
        'VALIDATION_ERROR',
        400,
        '승인 대상이 올바르지 않습니다.',
      ),
    },
    {
      action: 'reject' as const,
      button: '선택한 회원 거부',
      confirm: '거부',
      error: new ApiError('FORBIDDEN', 403, '거부 권한이 없습니다.'),
    },
  ])(
    '선택 $confirm 4xx는 서버 사유를 확정 실패로 보이고 재조회한다',
    async ({ action, button, confirm, error }) => {
      if (action === 'approve') api.approveError = error
      else api.rejectError = error
      renderAt()
      await loaded()
      fireEvent.click(within(row('신청완료')).getByRole('checkbox'))
      const queriesBefore = api.queries.length

      await confirmBulk(button, confirm)

      const alert = await screen.findByRole('alert')
      expect(alert).toHaveTextContent(
        `${confirm}하지 못했습니다. ${error.message}`,
      )
      expect(alert).not.toHaveTextContent('결과를 확정할 수 없습니다')
      expect(screen.queryByRole('status')).toBeNull()
      await waitFor(() =>
        expect(api.queries.length).toBeGreaterThan(queriesBefore),
      )
    },
  )

  it('요청 중에는 다섯 버튼과 모든 선택 checkbox를 잠근다', async () => {
    api.holdBulk = true
    renderAt()
    await loaded()
    fireEvent.click(within(row('활동회원')).getByRole('checkbox'))

    await confirmBulk('선택한 회원 정지', '정지')
    await waitFor(() => expect(api.bulkCalls).toHaveLength(1))
    for (const label of SELECTION_ACTION_LABELS) {
      expect(screen.getByRole('button', { name: label })).toBeDisabled()
    }
    expect(
      screen.getByRole('checkbox', { name: '이 페이지의 모든 회원 선택' }),
    ).toBeDisabled()
    for (const user of members) {
      expect(within(row(user.name)).getByRole('checkbox')).toBeDisabled()
    }

    api.releaseBulk?.()
    await waitFor(() =>
      expect(screen.getByText(/1명을 정지 처리했습니다/)).toBeInTheDocument(),
    )
  })

  it('확인 실행을 빠르게 연속 클릭해도 같은 bulk API를 한 번만 호출한다', async () => {
    api.holdBulk = true
    renderAt()
    await loaded()
    fireEvent.click(within(row('활동회원')).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '선택한 회원 정지' }))
    const dialog = await screen.findByRole('alertdialog')
    const action = within(dialog).getByRole('button', { name: '정지' })

    // 한 act 안에서 두 native event를 보내 React의 disabled 재렌더보다 빠른 입력을 재현한다.
    act(() => {
      action.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      action.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    })

    await waitFor(() => expect(api.bulkCalls).toHaveLength(1))
    expect(action).toBeDisabled()
    for (const label of SELECTION_ACTION_LABELS) {
      expect(
        screen.getByRole('button', { name: label, hidden: true }),
      ).toBeDisabled()
    }

    api.releaseBulk?.()
    await waitFor(() =>
      expect(screen.getByText(/1명을 정지 처리했습니다/)).toBeInTheDocument(),
    )
  })

  it.each([
    {
      button: '선택한 회원 승인',
      confirm: '승인',
      hold: () => {
        api.holdApprove = true
      },
      calls: () => api.approved,
      release: () => api.releaseApprove?.(),
    },
    {
      button: '선택한 회원 거부',
      confirm: '거부',
      hold: () => {
        api.holdReject = true
      },
      calls: () => api.rejected,
      release: () => api.releaseReject?.(),
    },
  ])(
    '$button 확인을 동일 틱에 두 번 누르면 API는 한 번만 나간다',
    async ({ button, confirm, hold, calls, release }) => {
      hold()
      renderAt()
      await loaded()
      fireEvent.click(within(row('신청완료')).getByRole('checkbox'))
      fireEvent.click(within(row('활동회원')).getByRole('checkbox'))
      fireEvent.click(screen.getByRole('button', { name: button }))
      const dialog = await screen.findByRole('alertdialog')
      const action = within(dialog).getByRole('button', { name: confirm })

      act(() => {
        action.dispatchEvent(new MouseEvent('click', { bubbles: true }))
        action.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      })

      await waitFor(() => expect(calls()).toEqual([[1, 3]]))
      expect(action).toBeDisabled()
      expect(
        screen.getByRole('checkbox', {
          name: '이 페이지의 모든 회원 선택',
          hidden: true,
        }),
      ).toBeDisabled()
      expect(within(row('미신청')).getByRole('checkbox')).toBeDisabled()

      release()
      await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    },
  )

  it('행 상태 확인을 빠르게 연속 클릭해도 API를 한 번만 호출한다', async () => {
    api.holdBulk = true
    renderAt()
    await loaded()
    await rowAction('활동회원', '정지')
    const dialog = await screen.findByRole('alertdialog')
    const action = within(dialog).getByRole('button', { name: '정지' })

    act(() => {
      action.dispatchEvent(new MouseEvent('click', { bubbles: true }))
      action.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    })

    await waitFor(() =>
      expect(api.bulkCalls).toEqual([{ userIds: [3], status: 'SUSPENDED' }]),
    )
    expect(action).toBeDisabled()

    api.releaseBulk?.()
    expect(await screen.findByRole('status')).toHaveTextContent(
      '활동회원 회원을 정지했습니다',
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

    await confirmBulk('선택한 회원 활성화', '활성화')

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

    await confirmBulk('선택한 회원 정지', '정지')

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

    await confirmBulk('선택한 회원 비활성화', '비활성화')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('1명을 비활성화했습니다')
    expect(alert).toHaveTextContent('활동회원')
    expect(alert).toHaveTextContent('2명은 변경하지 못했습니다')
    expect(alert).toHaveTextContent(
      '김관리: 비활성화할 수 없는 상태·권한의 계정',
    )
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

      await confirmBulk('선택한 회원 정지', '정지')

      const alert = await screen.findByRole('alert')
      expect(alert).toHaveTextContent(`정지하지 못했습니다. ${message}`)
      expect(alert).not.toHaveTextContent('정지 처리했습니다')
    },
  )

  it.each([
    new ApiError('INVALID_RESPONSE', 500, '서버 오류입니다.'),
    new ApiError('NETWORK_ERROR', 0, '연결이 끊어졌습니다.'),
  ])(
    '500/status=0은 변경 없음으로 단정하지 않고 재조회를 안내한다',
    async (error) => {
      api.bulkError = error
      renderAt()
      await loaded()
      fireEvent.click(within(row('활동회원')).getByRole('checkbox'))
      const before = api.queries.length

      await confirmBulk('선택한 회원 정지', '정지')

      const alert = await screen.findByRole('alert')
      expect(alert).toHaveTextContent('정지 요청 결과를 확정할 수 없습니다')
      expect(alert).toHaveTextContent('일부 상태 변경이 반영되었을 수 있습니다')
      expect(alert).toHaveTextContent(
        '다시 불러온 목록에서 상태와 선택 대상을 확인',
      )
      await waitFor(() => expect(api.queries.length).toBeGreaterThan(before))
    },
  )

  it('선택 비활성화 status=0도 확정 실패가 아닌 불확정 결과로 안내한다', async () => {
    api.deactivateError = new ApiError(
      'NETWORK_ERROR',
      0,
      '연결이 끊어졌습니다.',
    )
    renderAt()
    await loaded()
    fireEvent.click(within(row('활동회원')).getByRole('checkbox'))
    const before = api.queries.length

    await confirmBulk('선택한 회원 비활성화', '비활성화')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('비활성화 요청 결과를 확정할 수 없습니다')
    expect(alert).toHaveTextContent('일부 상태 변경이 반영되었을 수 있습니다')
    expect(alert).not.toHaveTextContent('비활성화하지 못했습니다')
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
      () =>
        fireEvent.click(
          screen.getByRole('link', { name: '다음 페이지로 이동' }),
        ),
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
    fireEvent.click(screen.getByRole('button', { name: '선택한 회원 정지' }))
    expect(await screen.findByRole('alertdialog')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('정렬'), {
      target: { value: 'name' },
    })

    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    expect(await screen.findByRole('status')).toHaveTextContent(
      '진행 중이던 확인을 닫고 선택한 1명이 해제되었습니다.',
    )
  })

  it('검색·status·role·sort를 URL과 목록 API 조회에 함께 보존한다', async () => {
    renderAt()
    await loaded()

    fireEvent.change(screen.getByLabelText('검색'), {
      target: { value: '활동 회원' },
    })
    fireEvent.click(screen.getByRole('button', { name: '검색' }))
    await waitFor(() => expect(api.queries.at(-1)?.q).toBe('활동 회원'))

    fireEvent.change(screen.getByLabelText('상태'), {
      target: { value: 'ACTIVE' },
    })
    await waitFor(() => expect(api.queries.at(-1)?.status).toBe('ACTIVE'))

    fireEvent.change(screen.getByLabelText('권한'), {
      target: { value: 'ADMIN' },
    })
    await waitFor(() => expect(api.queries.at(-1)?.role).toBe('ADMIN'))

    fireEvent.change(screen.getByLabelText('정렬'), {
      target: { value: 'name' },
    })
    await waitFor(() => expect(api.queries.at(-1)?.sort).toBe('name'))

    const params = new URLSearchParams(
      screen.getByTestId('search').textContent ?? '',
    )
    expect(params.get('q')).toBe('활동 회원')
    expect(params.get('status')).toBe('ACTIVE')
    expect(params.get('role')).toBe('ADMIN')
    expect(params.get('sort')).toBe('name')
    expect(api.queries.at(-1)?.applied).toBeUndefined()
  })

  it('옛 status=PENDING 주소를 승인 대기 URL과 applied=true 조회로 보정한다', async () => {
    renderAt('/admin/members?status=PENDING')
    await loaded()

    expect(await screen.findByRole('status')).toHaveTextContent(
      '이전 주소의 조건을 "승인 대기"로 맞췄습니다',
    )
    await waitFor(() => {
      expect(screen.getByLabelText('상태')).toHaveValue('PENDING:applied')
      expect(api.queries.at(-1)).toMatchObject({
        status: 'PENDING',
        applied: true,
      })
    })
    const params = new URLSearchParams(
      screen.getByTestId('search').textContent ?? '',
    )
    expect(params.get('status')).toBe('PENDING')
    expect(params.get('applied')).toBe('true')
  })

  it('범위를 넘은 page를 마지막 유효 페이지로 보정한다', async () => {
    renderAt('/admin/members?page=999')

    await waitFor(() => expect(api.queries.at(-1)?.page).toBe(2))
    expect(
      await screen.findByRole('link', { name: '3페이지로 이동' }),
    ).toHaveAttribute('aria-current', 'page')
    expect(screen.getByTestId('search')).toHaveTextContent('?page=2')
  })

  it('INACTIVE를 정지와 다른 비활동으로 표시하고 필터 URL/API에 보존한다', async () => {
    renderAt()
    await loaded()

    expect(within(row('비활동회원')).getByText('비활동')).toBeInTheDocument()
    expect(within(row('비활동회원')).queryByText('정지')).toBeNull()
    expect(within(row('정지회원')).getByText('정지')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('상태'), {
      target: { value: 'INACTIVE' },
    })
    await waitFor(() => {
      expect(api.queries.at(-1)?.status).toBe('INACTIVE')
      expect(api.queries.at(-1)?.applied).toBeUndefined()
    })
    expect(screen.getByLabelText('상태')).toHaveValue('INACTIVE')
    expect(screen.getByTestId('search')).toHaveTextContent('?status=INACTIVE')
  })
})

describe('회원 목록 페이지네이션', () => {
  function shownPages(viewport: 'mobile' | 'desktop'): number[] {
    return [
      ...document.querySelectorAll(
        `[data-pager-page][data-pager-${viewport}-visible="true"]`,
      ),
    ].map((item) => Number(item.getAttribute('data-pager-page')))
  }

  it('5페이지 이하는 모두 표시하고 현재 번호에 aria-current를 둔다', async () => {
    api.totalElements = 100
    api.totalPages = 5
    renderAt('/admin/members?page=3')
    await loaded()

    expect(shownPages('mobile')).toEqual([1, 2, 3, 4, 5])
    expect(shownPages('desktop')).toEqual([1, 2, 3, 4, 5])
    expect(
      screen.getByRole('link', { name: '4페이지로 이동' }),
    ).toHaveAttribute('aria-current', 'page')
    expect(
      document.querySelector('[data-slot="pagination-content"]'),
    ).toHaveClass('flex-wrap', 'justify-center', 'gap-1')
  })

  it('번호를 직접 눌러도 검색·상태·권한·정렬 query를 보존한다', async () => {
    renderAt(
      '/admin/members?q=%EA%B0%95&status=PENDING&applied=true&role=USER&sort=name&page=1',
    )
    await loaded()

    fireEvent.click(screen.getByRole('link', { name: '3페이지로 이동' }))

    await waitFor(() => expect(api.queries.at(-1)?.page).toBe(2))
    const params = new URLSearchParams(
      screen.getByTestId('search').textContent ?? '',
    )
    expect(params.get('q')).toBe('강')
    expect(params.get('status')).toBe('PENDING')
    expect(params.get('applied')).toBe('true')
    expect(params.get('role')).toBe('USER')
    expect(params.get('sort')).toBe('name')
    expect(params.get('page')).toBe('2')
    expect(api.queries.at(-1)).toMatchObject({
      q: '강',
      status: 'PENDING',
      applied: true,
      role: 'USER',
      sort: 'name',
      page: 2,
    })
    expect(
      await screen.findByRole('link', { name: '3페이지로 이동' }),
    ).toHaveAttribute('aria-current', 'page')
  })

  it('1페이지 번호로 돌아가면 다른 query는 보존하고 page는 지운다', async () => {
    renderAt('/admin/members?q=%EA%B0%95&status=ACTIVE&page=2')
    await loaded()

    fireEvent.click(screen.getByRole('link', { name: '1페이지로 이동' }))

    await waitFor(() => expect(api.queries.at(-1)?.page).toBe(0))
    const params = new URLSearchParams(
      screen.getByTestId('search').textContent ?? '',
    )
    expect(params.get('q')).toBe('강')
    expect(params.get('status')).toBe('ACTIVE')
    expect(params.has('page')).toBe(false)
  })

  it.each([
    [0, [1, 2, 3, 4, 20], [1, 2, 3, 4, 5, 6, 7, 8, 9, 20], 1],
    [2, [1, 2, 3, 4, 20], [1, 2, 3, 4, 5, 6, 7, 8, 9, 20], 1],
    [9, [1, 9, 10, 11, 20], [1, 7, 8, 9, 10, 11, 12, 13, 14, 20], 2],
    [17, [1, 17, 18, 19, 20], [1, 12, 13, 14, 15, 16, 17, 18, 19, 20], 1],
    [19, [1, 17, 18, 19, 20], [1, 12, 13, 14, 15, 16, 17, 18, 19, 20], 1],
  ] as const)(
    '20페이지의 0-based %d에서 모바일 5개·PC 10개 창을 표시한다',
    async (page, mobile, desktop, ellipses) => {
      api.totalElements = 400
      api.totalPages = 20
      renderAt(`/admin/members?page=${page}`)
      await loaded()

      expect(shownPages('mobile')).toEqual(mobile)
      expect(shownPages('desktop')).toEqual(desktop)
      expect(
        document.querySelectorAll(
          '[data-pager-mobile-visible="true"] [data-slot="pagination-ellipsis"]',
        ),
      ).toHaveLength(ellipses)
      expect(
        document.querySelectorAll(
          '[data-pager-desktop-visible="true"] [data-slot="pagination-ellipsis"]',
        ),
      ).toHaveLength(ellipses)
      expect(
        screen.getByRole('link', { name: `${page + 1}페이지로 이동` }),
      ).toHaveAttribute('aria-current', 'page')
    },
  )

  it.each([
    [0, '', '이전 페이지로 이동', '다음 페이지로 이동'],
    [19, '?page=19', '다음 페이지로 이동', '이전 페이지로 이동'],
  ] as const)(
    '0-based %d 경계에서 %s만 비활성화하고 클릭도 무시한다',
    async (page, initialSearch, disabled, enabled) => {
      api.totalElements = 400
      api.totalPages = 20
      renderAt(`/admin/members${initialSearch}`)
      await loaded()

      const disabledLink = screen.getByRole('link', { name: disabled })
      expect(disabledLink).toHaveAttribute('aria-disabled', 'true')
      expect(screen.getByRole('link', { name: enabled })).not.toHaveAttribute(
        'aria-disabled',
        'true',
      )

      const queryCount = api.queries.length
      const searchBeforeClick = screen.getByTestId('search').textContent
      fireEvent.click(disabledLink)

      expect(screen.getByTestId('search').textContent).toBe(searchBeforeClick)
      expect(api.queries).toHaveLength(queryCount)
      expect(api.queries.at(-1)?.page).toBe(page)
    },
  )

  it('번호 이동도 선택과 열린 확인을 해제하고 이유를 알린다', async () => {
    api.totalElements = 200
    api.totalPages = 10
    renderAt()
    await loaded()
    const thirdPage = screen.getByRole('link', { name: '3페이지로 이동' })
    fireEvent.click(within(row('활동회원')).getByRole('checkbox'))
    fireEvent.click(screen.getByRole('button', { name: '선택한 회원 정지' }))
    expect(await screen.findByRole('alertdialog')).toBeInTheDocument()

    // modal 뒤 링크는 실제 포인터로 누를 수 없지만, 뒤로가기·주소 이동과 같은 URL 변경을 재현한다.
    fireEvent.click(thirdPage)

    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    expect(await screen.findByRole('status')).toHaveTextContent(
      '진행 중이던 확인을 닫고 선택한 1명이 해제되었습니다.',
    )
    expect(screen.getByText(/이 페이지에서 0명 선택됨/)).toBeInTheDocument()
    expect(screen.getByTestId('search')).toHaveTextContent('?page=2')
  })
})

describe('행 조작과 목록 회귀', () => {
  it.each([
    ['승인', () => api.approved],
    ['거부', () => api.rejected],
  ] as const)(
    '신청 완료 메뉴의 %s는 확인 뒤 그 id만 보내고 다른 선택을 보존한다',
    async (action, calls) => {
      renderAt()
      await loaded()
      fireEvent.click(within(row('활동회원')).getByRole('checkbox'))

      await rowAction('신청완료', action)
      const dialog = await screen.findByRole('alertdialog')
      expect(dialog).toHaveTextContent('신청완료')
      expect(calls()).toEqual([])
      fireEvent.click(within(dialog).getByRole('button', { name: action }))

      await waitFor(() => expect(calls()).toEqual([[1]]))
      await waitFor(() =>
        expect(within(row('활동회원')).getByRole('checkbox')).toBeChecked(),
      )
    },
  )

  it.each([
    ['승인', () => api.approved],
    ['거부', () => api.rejected],
  ] as const)(
    '신청 완료 메뉴의 %s 확인을 취소하면 트리거로 포커스가 돌아가고 요청하지 않는다',
    async (action, calls) => {
      renderAt()
      await loaded()
      const trigger = within(row('신청완료')).getByRole('button', {
        name: '신청완료 관리 메뉴',
      })
      trigger.focus()

      await rowAction('신청완료', action)
      const dialog = await screen.findByRole('alertdialog')
      expect(calls()).toEqual([])
      fireEvent.click(within(dialog).getByRole('button', { name: '취소' }))

      await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
      expect(trigger).toHaveFocus()
      expect(calls()).toEqual([])
    },
  )

  it.each([
    ['활동회원', ['비활성화', '정지', '관리자 지정', '제거']],
    ['비활동회원', ['활성화', '정지', '관리자 지정', '제거']],
    ['정지회원', ['활성화', '비활성화', '관리자 지정', '제거']],
  ] as const)(
    '%s USER 메뉴는 상태별 행동만 순서대로 보인다',
    async (name, labels) => {
      renderAt()
      await loaded()

      openRowMenu(name)
      const menu = await screen.findByRole('menu')
      expect(
        within(menu)
          .getAllByRole('menuitem')
          .map((item) => item.textContent),
      ).toEqual(labels)
      expect(
        menu.querySelectorAll('[data-slot="dropdown-menu-separator"]'),
      ).toHaveLength(1)
    },
  )

  it('행 메뉴를 반복해 열고 닫아도 body scrollbar 관련 style을 바꾸지 않고 트리거로 포커스가 돌아온다', async () => {
    renderAt()
    await loaded()
    const trigger = within(row('활동회원')).getByRole('button', {
      name: '활동회원 관리 메뉴',
    })
    const bodyStyle = () => ({
      overflow: document.body.style.overflow,
      padding: document.body.style.padding,
      paddingRight: document.body.style.paddingRight,
      right: document.body.style.right,
      width: document.body.style.width,
    })
    const initial = bodyStyle()

    for (let count = 0; count < 2; count += 1) {
      trigger.focus()
      fireEvent.pointerDown(
        trigger,
        new MouseEvent('pointerdown', { bubbles: true, button: 0 }),
      )
      const menu = await screen.findByRole('menu')
      expect(bodyStyle()).toEqual(initial)
      fireEvent.keyDown(menu, { key: 'Escape' })
      await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
      expect(bodyStyle()).toEqual(initial)
      expect(trigger).toHaveFocus()
    }
  })

  it('ADMIN 메뉴는 권한 회수만 보이고 상태 조작·지정·제거를 숨긴다', async () => {
    renderAt()
    await loaded()

    openRowMenu('김관리')
    const menu = await screen.findByRole('menu')
    expect(
      within(menu)
        .getAllByRole('menuitem')
        .map((item) => item.textContent),
    ).toEqual(['권한 회수'])
    expect(menu).not.toHaveTextContent('활성화')
    expect(menu).not.toHaveTextContent('비활성화')
    expect(menu).not.toHaveTextContent('정지')
    expect(menu).not.toHaveTextContent('관리자 지정')
    expect(menu).not.toHaveTextContent('제거')
    expect(
      menu.querySelector('[data-slot="dropdown-menu-separator"]'),
    ).toBeNull()
  })

  it('신청 완료 PENDING 메뉴는 승인·거부·구분선·제거 순서이고 inline 버튼은 없다', async () => {
    renderAt()
    await loaded()

    expect(
      within(row('신청완료')).queryByRole('button', { name: '승인' }),
    ).toBeNull()
    expect(
      within(row('신청완료')).queryByRole('button', { name: '거부' }),
    ).toBeNull()

    openRowMenu('신청완료')
    const menu = await screen.findByRole('menu')
    expect(
      within(menu)
        .getAllByRole('menuitem')
        .map((item) => item.textContent),
    ).toEqual(['승인', '거부', '제거'])
    expect(
      menu.querySelectorAll('[data-slot="dropdown-menu-separator"]'),
    ).toHaveLength(1)
    for (const action of ['승인', '거부']) {
      expect(
        within(menu).getByRole('menuitem', { name: action }),
      ).not.toHaveAttribute('data-variant', 'destructive')
    }
    expect(
      within(menu).getByRole('menuitem', { name: '제거' }),
    ).toHaveAttribute('data-variant', 'destructive')
    expect(menu).not.toHaveTextContent('활성화')
    expect(menu).not.toHaveTextContent('비활성화')
    expect(menu).not.toHaveTextContent('정지')
    expect(menu).not.toHaveTextContent('관리자 지정')
    expect(menu).not.toHaveTextContent('권한 회수')
  })

  it('신청 전 PENDING 메뉴는 제거만 보이고 inline 승인·거부와 구분선이 없다', async () => {
    renderAt()
    await loaded()

    expect(
      within(row('미신청')).queryByRole('button', { name: '승인' }),
    ).toBeNull()
    expect(
      within(row('미신청')).queryByRole('button', { name: '거부' }),
    ).toBeNull()

    openRowMenu('미신청')
    const menu = await screen.findByRole('menu')
    expect(
      within(menu)
        .getAllByRole('menuitem')
        .map((item) => item.textContent),
    ).toEqual(['제거'])
    expect(
      within(menu).getByRole('menuitem', { name: '제거' }),
    ).toHaveAttribute('data-variant', 'destructive')
    expect(menu).not.toHaveTextContent('승인')
    expect(menu).not.toHaveTextContent('거부')
    expect(menu).not.toHaveTextContent('활성화')
    expect(menu).not.toHaveTextContent('비활성화')
    expect(menu).not.toHaveTextContent('정지')
    expect(menu).not.toHaveTextContent('관리자 지정')
    expect(menu).not.toHaveTextContent('권한 회수')
    expect(
      menu.querySelector('[data-slot="dropdown-menu-separator"]'),
    ).toBeNull()
  })

  it('PENDING은 비정상 ADMIN 조합이어도 status-first 승인·거부·제거 메뉴를 쓴다', async () => {
    members[0].role = 'ADMIN'
    renderAt()
    await loaded()

    openRowMenu('신청완료')
    const menu = await screen.findByRole('menu')
    expect(
      within(menu)
        .getAllByRole('menuitem')
        .map((item) => item.textContent),
    ).toEqual(['승인', '거부', '제거'])
    expect(menu).not.toHaveTextContent('권한 회수')
  })

  it('권한 회수 후 USER로 재조회되면 해당 상태 메뉴를 보인다', async () => {
    renderAt()
    await loaded()

    await rowAction('김관리', '권한 회수')
    const dialog = await screen.findByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: '권한 회수' }))
    await waitFor(() =>
      expect(api.roleCalls).toEqual([{ id: 99, role: 'USER' }]),
    )
    await screen.findByRole('status')
    await waitFor(() =>
      expect(within(row('김관리')).getByText('부원')).toBeInTheDocument(),
    )

    openRowMenu('김관리')
    const menu = await screen.findByRole('menu')
    expect(
      within(menu)
        .getAllByRole('menuitem')
        .map((item) => item.textContent),
    ).toEqual(['비활성화', '정지', '관리자 지정', '제거'])
  })

  it.each([
    ['비활동회원', '활성화', 4, 'ACTIVE'],
    ['활동회원', '정지', 3, 'SUSPENDED'],
  ] as const)(
    '%s의 %s는 bulk status API에 해당 id만 보낸다',
    async (name, label, id, status) => {
      renderAt()
      await loaded()

      await rowAction(name, label)
      const dialog = await screen.findByRole('alertdialog')
      fireEvent.click(within(dialog).getByRole('button', { name: label }))

      await waitFor(() =>
        expect(api.bulkCalls).toEqual([{ userIds: [id], status }]),
      )
    },
  )

  it('정지 USER의 비활성화는 deactivate API에 해당 id만 보낸다', async () => {
    renderAt()
    await loaded()

    await rowAction('정지회원', '비활성화')
    const dialog = await screen.findByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: '비활성화' }))

    await waitFor(() => expect(api.deactivateCalls).toEqual([[5]]))
  })

  it.each([
    {
      name: '비활동회원',
      label: '활성화',
      action: 'ACTIVATE',
      reason: '찾을 수 없는 계정',
    },
    {
      name: '정지회원',
      label: '비활성화',
      action: 'DEACTIVATE',
      reason: '비활성화할 수 없는 상태·권한의 계정',
    },
    {
      name: '활동회원',
      label: '정지',
      action: 'SUSPEND',
      reason: '승인 대기 상태라 정지할 수 없는 계정',
    },
  ] as const)(
    '행 $label 부분 실패는 성공으로 보이지 않고 사유를 알린 뒤 재조회한다',
    async ({ name, label, action, reason }) => {
      if (action === 'ACTIVATE') {
        api.bulkResult = {
          targetStatus: 'ACTIVE',
          processed: [],
          failed: [{ userId: 4, reason: 'NOT_FOUND' }],
        }
      } else if (action === 'DEACTIVATE') {
        api.deactivateResult = {
          deactivated: [],
          failed: [{ userId: 5, reason: 'NOT_ACTIVE_USER' }],
        }
      } else {
        api.bulkResult = {
          targetStatus: 'SUSPENDED',
          processed: [],
          failed: [{ userId: 3, reason: 'PENDING_NOT_ALLOWED' }],
        }
      }
      renderAt()
      await loaded()
      const queriesBefore = api.queries.length

      await rowAction(name, label)
      const dialog = await screen.findByRole('alertdialog')
      fireEvent.click(within(dialog).getByRole('button', { name: label }))

      const error = await screen.findByRole('alert')
      expect(error).toHaveTextContent(reason)
      expect(screen.queryByRole('status')).toBeNull()
      await waitFor(() =>
        expect(api.queries.length).toBeGreaterThan(queriesBefore),
      )
    },
  )

  it('행 상태 조작 status=0도 일부 반영 가능성을 안내하고 재조회한다', async () => {
    api.bulkError = new ApiError('NETWORK_ERROR', 0, '연결이 끊어졌습니다.')
    renderAt()
    await loaded()
    const before = api.queries.length

    await rowAction('활동회원', '정지')
    const dialog = await screen.findByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: '정지' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('정지 요청 결과를 확정할 수 없습니다')
    expect(alert).toHaveTextContent('일부 상태 변경이 반영되었을 수 있습니다')
    expect(alert).not.toHaveTextContent('정지하지 못했습니다')
    await waitFor(() => expect(api.queries.length).toBeGreaterThan(before))
  })

  it.each(['활동회원', '비활동회원', '정지회원'])(
    '%s의 관리자 지정은 활동 관리자 전환을 확인한다',
    async (name) => {
      renderAt()
      await loaded()

      await rowAction(name, '관리자 지정')
      const dialog = await screen.findByRole('alertdialog')
      expect(dialog).toHaveTextContent('활동 관리자로 전환')
      fireEvent.click(
        within(dialog).getByRole('button', { name: '관리자 지정' }),
      )

      const id = ORIGINAL_MEMBERS.find((user) => user.name === name)?.id
      await waitFor(() =>
        expect(api.roleCalls).toEqual([{ id, role: 'ADMIN' }]),
      )
    },
  )

  it.each(['비활동회원', '정지회원'])(
    '%s을 관리자로 지정한 뒤 재조회하면 활동중·관리자이고 권한 회수만 보인다',
    async (name) => {
      renderAt()
      await loaded()

      await rowAction(name, '관리자 지정')
      const dialog = await screen.findByRole('alertdialog')
      fireEvent.click(
        within(dialog).getByRole('button', { name: '관리자 지정' }),
      )

      expect(await screen.findByRole('status')).toHaveTextContent(
        `${name} 회원을 활동 관리자로 지정했습니다.`,
      )
      await waitFor(() => {
        expect(within(row(name)).getByText('활동중')).toBeInTheDocument()
        expect(within(row(name)).getByText('관리자')).toBeInTheDocument()
      })

      openRowMenu(name)
      const menu = await screen.findByRole('menu')
      expect(
        within(menu)
          .getAllByRole('menuitem')
          .map((item) => item.textContent),
      ).toEqual(['권한 회수'])
    },
  )

  it('제거 확인 창이 남을 콘텐츠 건수를 항목별로 보여준 뒤에만 실행한다', async () => {
    renderAt()
    await loaded()

    await rowAction('활동회원', '제거')
    const dialog = await screen.findByRole('alertdialog')
    expect(api.summaryCalls).toEqual([3])
    await waitFor(() => {
      expect(dialog).toHaveTextContent('자료 3건')
      expect(dialog).toHaveTextContent('공지 1건')
      expect(dialog).toHaveTextContent('활동사진 5건')
      expect(dialog).toHaveTextContent('게시글 7건')
    })
    expect(api.removed).toEqual([])

    fireEvent.click(within(dialog).getByRole('button', { name: '제거' }))
    await waitFor(() => expect(api.removed).toEqual([3]))
  })

  it.each([
    ['신청완료', 1],
    ['미신청', 2],
  ] as const)(
    '%s PENDING도 기존 제거 확인과 API 경로를 재사용한다',
    async (name, id) => {
      renderAt()
      await loaded()

      await rowAction(name, '제거')
      const dialog = await screen.findByRole('alertdialog')
      expect(api.summaryCalls).toEqual([id])
      await waitFor(() => expect(dialog).toHaveTextContent('자료 3건'))
      expect(api.removed).toEqual([])

      fireEvent.click(within(dialog).getByRole('button', { name: '제거' }))
      await waitFor(() => expect(api.removed).toEqual([id]))
    },
  )

  it.each(['신청완료', '미신청'] as const)(
    '%s PENDING 제거 확인을 취소하면 같은 관리 메뉴 트리거로 포커스가 돌아온다',
    async (name) => {
      renderAt()
      await loaded()
      const trigger = within(row(name)).getByRole('button', {
        name: `${name} 관리 메뉴`,
      })
      trigger.focus()

      await rowAction(name, '제거')
      const dialog = await screen.findByRole('alertdialog')
      await waitFor(() => expect(dialog).toHaveTextContent('자료 3건'))
      fireEvent.click(within(dialog).getByRole('button', { name: '취소' }))

      await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
      expect(trigger).toHaveFocus()
      expect(api.removed).toEqual([])
    },
  )

  it('제거 content summary 조회 실패 시 실행을 차단한다', async () => {
    api.summaryError = new ApiError(
      'NOT_FOUND',
      404,
      '회원을 찾을 수 없습니다.',
    )
    renderAt()
    await loaded()

    await rowAction('활동회원', '제거')
    const dialog = await screen.findByRole('alertdialog')
    await waitFor(() => expect(dialog).toHaveTextContent('불러오지 못했습니다'))
    expect(within(dialog).getByRole('button', { name: '제거' })).toBeDisabled()
    expect(api.removed).toEqual([])
  })

  it('마지막 활성 관리자 권한 회수 403 사유를 그대로 보여준다', async () => {
    api.roleError = new ApiError(
      'FORBIDDEN',
      403,
      '활성 관리자가 없어집니다. 다른 관리자를 먼저 지정해 주세요.',
    )
    renderAt()
    await loaded()

    await rowAction('김관리', '권한 회수')
    const dialog = await screen.findByRole('alertdialog')
    fireEvent.click(within(dialog).getByRole('button', { name: '권한 회수' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '활성 관리자가 없어집니다. 다른 관리자를 먼저 지정해 주세요.',
    )
    expect(api.roleCalls).toEqual([{ id: 99, role: 'USER' }])
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
