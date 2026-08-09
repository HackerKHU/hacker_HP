import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { User } from '../api/types'
import { hasApplied, SessionProvider, useSession } from './session'

const auth = vi.hoisted(() => ({ me: vi.fn() }))

vi.mock('../api/auth', () => ({
  getMe: () => auth.me(),
  logout: () => Promise.resolve(),
}))

const PENDING: User = {
  id: 3,
  email: 'member@khu.ac.kr',
  studentNo: null,
  name: '홍길동',
  role: 'USER',
  status: 'PENDING',
  createdAt: '2026-03-02T09:00:00Z',
  appliedAt: null,
  approvedAt: null,
}

/** 세션이 계산한 신청 여부를 화면에 그대로 드러내는 프로브. */
function Probe() {
  const session = useSession()
  const applied = hasApplied(session)
  return <span data-testid="applied">{String(applied)}</span>
}

function renderProbe() {
  render(
    <SessionProvider>
      <Probe />
    </SessionProvider>,
  )
  return screen.findByTestId('applied')
}

beforeEach(() => {
  auth.me.mockReset()
})

describe('PENDING 세션의 신청 여부', () => {
  it('신청 전에는 false다 — 화면이 신청 폼을 띄울 근거', async () => {
    auth.me.mockResolvedValue(PENDING)

    const el = await renderProbe()

    await waitFor(() => expect(el).toHaveTextContent('false'))
  })

  it('신청 후에는 true다 — 같은 PENDING이라도 대기 안내로 갈라진다', async () => {
    auth.me.mockResolvedValue({
      ...PENDING,
      studentNo: '2024001122',
      appliedAt: '2026-03-02T10:00:00Z',
    })

    const el = await renderProbe()

    await waitFor(() => expect(el).toHaveTextContent('true'))
  })

  it('403으로만 알아낸 PENDING은 신청 여부를 모른다(null)', async () => {
    const { ApiError } = await import('../api/client')
    auth.me.mockRejectedValue(
      new ApiError('PENDING_APPROVAL', 403, '승인 대기 중입니다.'),
    )

    const el = await renderProbe()

    // 모르는 것을 false로 단정하면 이미 신청한 사람에게 폼이 다시 뜬다.
    await waitFor(() => expect(el).toHaveTextContent('null'))
  })
})
