import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
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

const ACTIVE: User = {
  ...PENDING,
  id: 1,
  studentNo: '2021123456',
  status: 'ACTIVE',
  appliedAt: '2026-03-02T10:00:00Z',
  approvedAt: '2026-03-03T09:00:00Z',
}

/**
 * 세션이 계산한 값을 화면에 그대로 드러내는 프로브.
 *
 * `kind`도 함께 드러낸다 — 신청 여부만으로는 "세션이 통째로 비로그인이 되었는가"를
 * 구분할 수 없다. `refresh()`가 거부하는지도 여기서 받는다.
 */
function Probe() {
  const session = useSession()
  const applied = hasApplied(session)
  const [refreshError, setRefreshError] = useState('')
  return (
    <>
      <span data-testid="applied">{String(applied)}</span>
      <span data-testid="kind">{session.state.kind}</span>
      <span data-testid="refresh-error">{refreshError}</span>
      <button
        type="button"
        onClick={() => {
          setRefreshError('')
          session.refresh().catch((error: unknown) => {
            setRefreshError(
              error instanceof ApiError ? error.code : '알 수 없음',
            )
          })
        }}
      >
        새로고침
      </button>
    </>
  )
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

  // 회귀 — `null`을 풀 경로가 없으면 신청 화면이 폼과 안내 중 무엇도 못 고르고 영영 멈춘다.
  /*
   * T-112·T-113 — **"서버에 못 닿았다"는 "로그아웃됐다"가 아니다** (spec §3-1-5).
   *
   * 네트워크가 잠깐 끊긴 것만으로 세션을 버리면, 승인을 기다리며 "다시 확인"을 누르던
   * 지원자가 로그인 화면으로 튕기고 자기가 무엇을 잘못했는지 알 수 없다.
   */
  it.each([
    [
      '네트워크 오류',
      new ApiError('NETWORK_ERROR', 0, '서버에 연결하지 못했습니다.'),
    ],
    ['5xx', new ApiError('INVALID_RESPONSE', 500, '서버 오류입니다.')],
  ])('refresh()가 %s로 실패해도 세션을 그대로 둔다', async (_label, error) => {
    auth.me.mockResolvedValueOnce(ACTIVE)
    await renderProbe()
    const kind = screen.getByTestId('kind')
    await waitFor(() => expect(kind).toHaveTextContent('active'))

    auth.me.mockRejectedValueOnce(error)
    fireEvent.click(screen.getByRole('button', { name: '새로고침' }))

    // 실패가 호출부에 도달한 뒤에도 세션은 그대로다 — guest로 떨어지지 않는다.
    await waitFor(() =>
      expect(screen.getByTestId('refresh-error')).not.toHaveTextContent(''),
    )
    expect(kind).toHaveTextContent('active')
  })

  it('refresh()는 상태를 알아내지 못하면 호출부에 알린다', async () => {
    auth.me.mockResolvedValueOnce(ACTIVE)
    await renderProbe()
    await waitFor(() =>
      expect(screen.getByTestId('kind')).toHaveTextContent('active'),
    )

    auth.me.mockRejectedValueOnce(
      new ApiError('NETWORK_ERROR', 0, '서버에 연결하지 못했습니다.'),
    )
    fireEvent.click(screen.getByRole('button', { name: '새로고침' }))

    // 화면이 "다시 시도해 달라"를 보여주려면 실패를 알 수 있어야 한다.
    await waitFor(() =>
      expect(screen.getByTestId('refresh-error')).toHaveTextContent(
        'NETWORK_ERROR',
      ),
    )
  })

  it('refresh()가 401을 받으면 비로그인으로 정리한다', async () => {
    auth.me.mockResolvedValueOnce(ACTIVE)
    await renderProbe()
    const kind = screen.getByTestId('kind')
    await waitFor(() => expect(kind).toHaveTextContent('active'))

    auth.me.mockRejectedValueOnce(
      new ApiError('UNAUTHENTICATED', 401, '로그인이 필요합니다.'),
    )
    fireEvent.click(screen.getByRole('button', { name: '새로고침' }))

    await waitFor(() => expect(kind).toHaveTextContent('guest'))
  })

  it('refresh()가 알 수 없는 PENDING을 실제 상태로 푼다', async () => {
    const { ApiError } = await import('../api/client')
    auth.me.mockRejectedValueOnce(
      new ApiError('PENDING_APPROVAL', 403, '승인 대기 중입니다.'),
    )

    const el = await renderProbe()
    await waitFor(() => expect(el).toHaveTextContent('null'))

    auth.me.mockResolvedValueOnce({
      ...PENDING,
      studentNo: '2024001122',
      appliedAt: '2026-03-02T10:00:00Z',
    })
    fireEvent.click(screen.getByRole('button', { name: '새로고침' }))

    await waitFor(() => expect(el).toHaveTextContent('true'))
  })
})
