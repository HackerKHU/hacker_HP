import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, request, toQuery } from './client'
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

  it('message가 없는 에러 본문은 INVALID_RESPONSE로 떨어뜨린다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => Response.json({ code: 'FORBIDDEN' }, { status: 403 })),
    )

    const error = await request('/notices').catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe('INVALID_RESPONSE')
    expect(typeof (error as ApiError).message).toBe('string')
  })

  it('계약에 없는 code는 INVALID_RESPONSE로 격리한다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        Response.json(
          { code: 'FORBIDEN', message: '오타 난 코드' },
          { status: 403 },
        ),
      ),
    )

    const error = await request('/notices').catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).code).toBe('INVALID_RESPONSE')
  })

  it('200 + 빈 본문을 undefined로 통과시킨다', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('', { status: 200 })),
    )

    await expect(request('/auth/logout', { method: 'POST' })).resolves.toBe(
      undefined,
    )
  })
})

describe('request base URL', () => {
  it('상대 경로 앞에 /api/v1을 붙여 호출한다', async () => {
    const fetchMock = vi.fn(async () => new Response('', { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await request('/auth/me')

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/auth/me',
      expect.objectContaining({ credentials: 'include' }),
    )
  })

  it('절대 URL을 만들지 않는다 — Vercel rewrites 프록시를 타야 한다', async () => {
    const fetchMock = vi.fn(async () => new Response('', { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await request('/notices')

    const [url] = fetchMock.mock.calls[0] as unknown as [string]
    expect(url.startsWith('/api/v1/')).toBe(true)
    expect(url).not.toMatch(/^https?:\/\//)
  })
})

describe('toQuery', () => {
  it('빈 문자열은 빼고 0은 남긴다', () => {
    expect(toQuery({ q: '', page: 0, size: undefined })).toBe('?page=0')
  })
})
