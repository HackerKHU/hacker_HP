import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import App from '@/App'
import { SessionProvider } from '@/auth/session'
import { MemoryRouter } from '@/test/TestRouter'

vi.hoisted(() => {
  vi.stubEnv('VITE_FIXTURE_SCENARIO', 'admin')
})

vi.mock('@/api/auth', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/auth')>()
  const fixtures = await import('@/api/fixtures')
  return { ...actual, getMe: fixtures.fixtureMe }
})

vi.mock('@/api/adminUsers', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/adminUsers')>()
  const fixtures = await import('@/api/fixtures')
  return {
    ...actual,
    list: fixtures.fixtureAdminUsers,
    approve: fixtures.fixtureApproveUsers,
    reject: fixtures.fixtureRejectUsers,
    deactivate: fixtures.fixtureDeactivateUsers,
    bulkUpdateStatus: fixtures.fixtureBulkUpdateUserStatus,
    reactivate: fixtures.fixtureReactivateUsers,
    contentSummary: fixtures.fixtureContentSummary,
    remove: fixtures.fixtureRemoveUser,
    updateRole: fixtures.fixtureUpdateUserRole,
  }
})

function renderFixturePage(path = '/admin/members') {
  render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  )
}

function fixtureRow(name: string): HTMLElement {
  const found = screen.getByRole('cell', { name }).closest('tr')
  if (!found) throw new Error(`${name} 행을 찾지 못했다`)
  return found
}

async function promoteFixtureMember(name: string) {
  const trigger = within(fixtureRow(name)).getByRole('button', {
    name: `${name} 관리 메뉴`,
  })
  fireEvent.pointerDown(
    trigger,
    new MouseEvent('pointerdown', { bubbles: true, button: 0 }),
  )
  fireEvent.click(await screen.findByRole('menuitem', { name: '관리자 지정' }))
  const dialog = await screen.findByRole('alertdialog')
  expect(dialog).toHaveTextContent('활동 관리자로 전환')
  fireEvent.click(within(dialog).getByRole('button', { name: '관리자 지정' }))
  await waitFor(() =>
    expect(screen.getByRole('status')).toHaveTextContent(
      `${name} 회원을 활동 관리자로 지정했습니다.`,
    ),
  )
  await waitFor(() => {
    expect(within(fixtureRow(name)).getByText('활동중')).toBeInTheDocument()
    expect(within(fixtureRow(name)).getByText('관리자')).toBeInTheDocument()
  })

  fireEvent.pointerDown(
    within(fixtureRow(name)).getByRole('button', {
      name: `${name} 관리 메뉴`,
    }),
    new MouseEvent('pointerdown', { bubbles: true, button: 0 }),
  )
  const menu = await screen.findByRole('menu')
  expect(
    within(menu)
      .getAllByRole('menuitem')
      .map((item) => item.textContent),
  ).toEqual(['권한 회수'])
  fireEvent.keyDown(menu, { key: 'Escape' })
  await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
}

async function selectCurrentPage() {
  const header = await screen.findByRole('checkbox', {
    name: '이 페이지의 모든 회원 선택',
  })
  fireEvent.click(header)
  await waitFor(() =>
    expect(screen.getByText(/이 페이지에서 20명 선택됨/)).toBeInTheDocument(),
  )
}

async function confirm(button: string, action: string) {
  fireEvent.click(screen.getByRole('button', { name: button }))
  const dialog = await screen.findByRole('alertdialog')
  fireEvent.click(within(dialog).getByRole('button', { name: action }))
}

it('실제 admin fixture의 신청 완료 PENDING도 inline 버튼 없이 승인·거부·제거 메뉴를 쓴다', async () => {
  renderFixturePage('/admin/members?status=PENDING&applied=true&sort=name')
  const pending = await screen.findByRole('row', { name: /강도현/ })

  expect(within(pending).queryByRole('button', { name: '승인' })).toBeNull()
  expect(within(pending).queryByRole('button', { name: '거부' })).toBeNull()
  fireEvent.pointerDown(
    within(pending).getByRole('button', { name: '강도현 관리 메뉴' }),
    new MouseEvent('pointerdown', { bubbles: true, button: 0 }),
  )
  const menu = await screen.findByRole('menu')
  expect(
    within(menu)
      .getAllByRole('menuitem')
      .map((item) => item.textContent),
  ).toEqual(['승인', '거부', '제거'])
  expect(
    menu.querySelectorAll('[data-slot="dropdown-menu-separator"]'),
  ).toHaveLength(1)
  expect(within(menu).getByRole('menuitem', { name: '제거' })).toHaveAttribute(
    'data-variant',
    'destructive',
  )
})

it('실제 admin fixture에서 페이지 번호를 직접 눌러 다음 페이지로 이동한다', async () => {
  renderFixturePage()
  await screen.findByRole('checkbox', { name: '오세림 선택' })
  expect(screen.getByRole('link', { name: '1페이지로 이동' })).toHaveAttribute(
    'aria-current',
    'page',
  )

  fireEvent.click(screen.getByRole('link', { name: '2페이지로 이동' }))

  await waitFor(() =>
    expect(
      screen.getByRole('link', { name: '2페이지로 이동' }),
    ).toHaveAttribute('aria-current', 'page'),
  )
  expect(
    screen.getByRole('link', { name: '다음 페이지로 이동' }),
  ).toHaveAttribute('aria-disabled', 'true')
  expect(
    screen.getByRole('link', { name: '이전 페이지로 이동' }),
  ).not.toHaveAttribute('aria-disabled', 'true')
})

it('실제 admin fixture에서 비활동·정지 USER 승격은 재조회 뒤 활동 관리자이며 권한 회수만 남는다', async () => {
  renderFixturePage()

  await screen.findByRole('checkbox', { name: '오세림 선택' })
  await promoteFixtureMember('오세림')
  await promoteFixtureMember('신동하')
}, 15_000)

it('실제 admin fixture의 선택 승인·거부는 상태를 미리 거르지 않고 부분 실패를 안내한다', async () => {
  renderFixturePage('/admin/members?sort=name')
  await screen.findByRole('checkbox', { name: '강도현 선택' })

  fireEvent.click(within(fixtureRow('강도현')).getByRole('checkbox'))
  fireEvent.click(within(fixtureRow('윤태경')).getByRole('checkbox'))
  await confirm('선택한 회원 승인', '승인')
  await waitFor(() => {
    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('1명을 승인하고 1명은 실패')
    expect(alert).toHaveTextContent('이미 승인되었거나 정지된 계정: 윤태경')
  })

  await waitFor(() =>
    expect(screen.getByText(/이 페이지에서 0명 선택됨/)).toBeInTheDocument(),
  )
  fireEvent.click(within(fixtureRow('김서연')).getByRole('checkbox'))
  fireEvent.click(within(fixtureRow('윤태경')).getByRole('checkbox'))
  await confirm('선택한 회원 거부', '거부')
  await waitFor(() => {
    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent(
      '1명의 신청을 거부해 미승인 상태로 되돌렸습니다',
    )
    expect(alert).toHaveTextContent('승인 대기 상태가 아닌 계정: 윤태경')
  })
  await waitFor(() => {
    const resetRow = fixtureRow('김서연')
    expect(within(resetRow).getByText('미승인')).toBeInTheDocument()
    const cells = within(resetRow).getAllByRole('cell')
    expect(cells[2]).toHaveTextContent('—')
    expect(cells[3]).toHaveTextContent('—')
    expect(cells[7]).toHaveTextContent('—')
    expect(
      within(resetRow).getByRole('checkbox', { name: '김서연 선택' }),
    ).not.toBeChecked()
  })
}, 15_000)

it('실제 admin fixture 전체 선택에서 정지 혼합 실패와 후속 활성화 성공의 live role을 가른다', async () => {
  renderFixturePage()

  // 첫 페이지에 남은 신청 완료 PENDING과 ADMIN이 있어 정지가 부분 실패한다.
  await selectCurrentPage()
  await confirm('선택한 회원 정지', '정지')

  const failure = await screen.findByRole('alert')
  expect(screen.getAllByRole('alert')).toHaveLength(1)
  expect(screen.queryByRole('status')).toBeNull()
  expect(failure).toHaveTextContent('승인 대기 상태라 정지할 수 없는 계정')
  expect(failure).toHaveTextContent('관리자 권한을 먼저 회수해야 하는 계정')

  await waitFor(() =>
    expect(screen.getByText(/이 페이지에서 0명 선택됨/)).toBeInTheDocument(),
  )

  // 실패 대상은 PENDING/ACTIVE로, 성공 대상은 SUSPENDED로 남아 모두 ACTIVE 처리 가능하다.
  await selectCurrentPage()
  await confirm('선택한 회원 활성화', '활성화')

  const success = await screen.findByRole('status')
  expect(screen.getAllByRole('status')).toHaveLength(1)
  expect(screen.queryByRole('alert')).toBeNull()
  expect(success).toHaveTextContent('활성화 처리했습니다')
  expect(success).toHaveTextContent('이미 목표 상태인 멱등 결과 포함')
}, 15_000)
