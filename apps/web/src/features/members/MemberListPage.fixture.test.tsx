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
    updateStatus: fixtures.fixtureUpdateUserStatus,
  }
})

function renderFixturePage() {
  render(
    <MemoryRouter initialEntries={['/admin/members']}>
      <SessionProvider>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  )
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

it('실제 admin fixture 전체 선택에서 정지 혼합 실패와 후속 활성화 성공의 live role을 가른다', async () => {
  renderFixturePage()

  // 신선한 첫 페이지에는 신청 완료 PENDING과 ADMIN이 있어 정지가 부분 실패한다.
  await selectCurrentPage()
  await confirm('선택 항목 정지', '정지')

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
  await confirm('선택 항목 활성화', '활성화')

  const success = await screen.findByRole('status')
  expect(screen.getAllByRole('status')).toHaveLength(1)
  expect(screen.queryByRole('alert')).toBeNull()
  expect(success).toHaveTextContent('활성화 처리했습니다')
  expect(success).toHaveTextContent('이미 목표 상태인 멱등 결과 포함')
}, 15_000)
