import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, request } from './client'
import type { ErrorCode } from './types'

function mockErrorResponse(status: number, code: ErrorCode) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => Response.json({ code, message: '테스트' }, { status })),
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('request 에러 매핑', () => {
  it('403 PENDING_APPROVAL과 403 FORBIDDEN을 코드로 구분한다', async () => {
    mockErrorResponse(403, 'PENDING_APPROVAL')
    const pending = await request('/auth/me').catch((error: unknown) => error)

    mockErrorResponse(403, 'FORBIDDEN')
    const forbidden = await request('/notices').catch((error: unknown) => error)

    expect(pending).toBeInstanceOf(ApiError)
    expect(forbidden).toBeInstanceOf(ApiError)
    expect((pending as ApiError).code).toBe('PENDING_APPROVAL')
    expect((forbidden as ApiError).code).toBe('FORBIDDEN')
    expect((pending as ApiError).code).not.toBe((forbidden as ApiError).code)
  })
})
