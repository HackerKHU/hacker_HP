import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/App'
import { ApiError } from '@/api/client'
import type { User } from '@/api/types'
import { SessionProvider } from '@/auth/session'

/**
 * 신청·대기 화면 (#38).
 *
 * **컴포넌트를 직접 렌더하지 않는다.** `/pending`으로 앱을 띄워 `PendingOnly` 가드를
 * 실제로 태운다.
 *
 * **모든 응답을 한 박자 늦춘다.** 즉시 응답하면 로딩 구간이 사실상 사라져, "아직 안 온
 * 것을 기다리는" 경합이 실행마다 났다 안 났다 한다. 늦춰두면 그 구간이 항상 열려
 * 잘못된 대기 지점이 바로 드러난다.
 */
const DELAY = 100

const api = vi.hoisted(() => ({
  me: null as User | null,
  meError: null as ApiError | null,
  /** `getMe` 호출 횟수. "다시 확인"이 실제로 서버에 물었는지 본다. */
  meCalls: 0,
  submitted: [] as { studentNo: string; name: string }[],
  submitError: null as ApiError | null,
}))

const BASE: User = {
  id: 3,
  email: 'applicant@khu.ac.kr',
  studentNo: null,
  name: '구글이름',
  role: 'USER',
  status: 'PENDING',
  createdAt: '2026-03-02T09:00:00Z',
  appliedAt: null,
  approvedAt: null,
}

/** 신청서를 낸 상태. */
const APPLIED: User = {
  ...BASE,
  studentNo: '2021123456',
  name: '홍길동',
  appliedAt: '2026-03-02T10:00:00Z',
}

function later<T>(value: T): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(value), DELAY))
}

vi.mock('@/api/auth', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/auth')>()
  return {
    ...actual,
    getMe: async () => {
      api.meCalls += 1
      await later(null)
      if (api.meError) throw api.meError
      if (!api.me)
        throw new ApiError('UNAUTHENTICATED', 401, '로그인이 필요합니다.')
      return api.me
    },
    logout: () => Promise.resolve(),
    submitApplication: async (body: { studentNo: string; name: string }) => {
      await later(null)
      if (api.submitError) throw api.submitError
      api.submitted.push(body)
    },
  }
})

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

function renderAt(path = '/pending') {
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

beforeEach(() => {
  api.me = BASE
  api.meError = null
  api.meCalls = 0
  api.submitted = []
  api.submitError = null
})

describe('한 화면 두 모습', () => {
  // T-104 — 첫 구글 로그인 직후에는 학번이 없다 (spec §3-1-4).
  it('신청 전에는 신청 폼이 뜬다', async () => {
    renderAt()

    expect(await screen.findByLabelText('학번')).toHaveValue('')
    expect(
      screen.getByRole('heading', { name: '가입 신청' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '승인 대기 중' })).toBeNull()
  })

  // 결정 3 — 이름은 구글 프로필에서 받아둔 값이 있다. 빈 칸으로 두면 다시 쓰게 한다.
  it('이름은 계정에 있는 값으로 미리 채운다', async () => {
    renderAt()

    expect(await screen.findByLabelText('이름')).toHaveValue('구글이름')
  })

  // T-105
  it('신청 후에는 대기 안내와 낸 내용이 보인다', async () => {
    api.me = APPLIED

    renderAt()

    expect(
      await screen.findByRole('heading', { name: '승인 대기 중' }),
    ).toBeInTheDocument()
    expect(screen.getByText('2021123456')).toBeInTheDocument()
    expect(screen.getByText('홍길동')).toBeInTheDocument()
    expect(screen.queryByLabelText('학번')).toBeNull()
  })

  /*
   * T-106 — **이 파일의 핵심.**
   *
   * 403 PENDING_APPROVAL로만 PENDING을 알아내면 세션에 사용자가 없어 신청 여부를 모른다.
   * 기본값으로 폼을 띄우면 **이미 신청한 사람이 다시 쓰게 된다** — 자기가 낸 내용을 볼 수
   * 없으니 학번을 새로 기억해내야 하고, 재제출은 성공하므로 잘못된 값이 저장된다.
   */
  it('신청 여부를 모르는 동안에는 폼도 안내도 띄우지 않는다', async () => {
    api.meError = new ApiError(
      'PENDING_APPROVAL',
      403,
      '가입 승인 대기 중입니다.',
    )

    renderAt()

    // 로딩만 보인다.
    expect(
      await screen.findByText('신청 정보를 확인하는 중'),
    ).toBeInTheDocument()
    expect(screen.queryByLabelText('학번')).toBeNull()
    expect(screen.queryByRole('heading', { name: '승인 대기 중' })).toBeNull()
  })

  it('신청 여부를 확인한 뒤에 알맞은 모습을 고른다', async () => {
    api.meError = new ApiError(
      'PENDING_APPROVAL',
      403,
      '가입 승인 대기 중입니다.',
    )

    renderAt()
    await screen.findByText('신청 정보를 확인하는 중')

    // 화면이 다시 물어본다. 이번엔 서버가 신청 완료 상태를 준다.
    api.meError = null
    api.me = APPLIED

    expect(
      await screen.findByRole('heading', { name: '승인 대기 중' }),
    ).toBeInTheDocument()
    expect(api.meCalls).toBeGreaterThan(1)
  })
})

describe('신청서 제출', () => {
  // T-107
  it('제출하면 서버에서 다시 읽어 대기 안내로 바뀐다', async () => {
    renderAt()

    fireEvent.change(await screen.findByLabelText('학번'), {
      target: { value: '2021123456' },
    })
    fireEvent.change(screen.getByLabelText('이름'), {
      target: { value: '홍길동' },
    })
    // 제출 성공 뒤 화면은 서버가 준 값으로 그려진다.
    api.me = APPLIED
    fireEvent.click(screen.getByRole('button', { name: '제출' }))

    expect(
      await screen.findByRole('heading', { name: '승인 대기 중' }),
    ).toBeInTheDocument()
    expect(api.submitted).toEqual([{ studentNo: '2021123456', name: '홍길동' }])
  })

  // 결정 5 — 낙관적으로 바꾸지 않는다. 서버가 저장했는지 확인된 뒤에 바뀐다.
  it('제출은 성공했지만 서버가 아직 신청 전이라고 하면 폼에 머문다', async () => {
    renderAt()

    fireEvent.change(await screen.findByLabelText('학번'), {
      target: { value: '2021123456' },
    })
    fireEvent.click(screen.getByRole('button', { name: '제출' }))

    await waitFor(() => {
      expect(api.submitted).toHaveLength(1)
    })
    // getMe가 계속 신청 전 상태를 주므로 화면도 폼이다.
    expect(await screen.findByLabelText('학번')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '승인 대기 중' })).toBeNull()
  })

  it('공백만 넣으면 요청이 나가지 않는다', async () => {
    renderAt()

    fireEvent.change(await screen.findByLabelText('학번'), {
      target: { value: '   ' },
    })
    fireEvent.click(screen.getByRole('button', { name: '제출' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '학번과 이름을 입력해주세요.',
    )
    expect(api.submitted).toEqual([])
  })

  // 결정 4 — 계약에 없는 형식 규칙을 만들지 않는다. 상한만 스키마에서 온다.
  it('학번·이름 상한은 스키마 값이고 그 밖의 형식은 막지 않는다', async () => {
    renderAt()

    const studentNo = await screen.findByLabelText('학번')
    expect(studentNo).toHaveAttribute('maxLength', '20')
    expect(screen.getByLabelText('이름')).toHaveAttribute('maxLength', '50')

    // 숫자가 아닌 학번도 그대로 받는다 — 편입·교환학생 학번이 그렇다.
    fireEvent.change(studentNo, { target: { value: 'EX-2021-7' } })
    fireEvent.click(screen.getByRole('button', { name: '제출' }))

    await waitFor(() => {
      expect(api.submitted).toEqual([
        { studentNo: 'EX-2021-7', name: '구글이름' },
      ])
    })
  })
})

describe('제출 실패', () => {
  /*
   * T-108 — **실패했는데 성공한 것처럼 보이면 안 된다.** 입력을 지우면 무엇이 거부됐는지
   * 확인할 방법이 없다.
   */
  it.each([
    [
      'DUPLICATE_STUDENT_NO',
      new ApiError('DUPLICATE_STUDENT_NO', 409, '이미 등록된 학번입니다.'),
      /이미 등록된 학번/,
    ],
    [
      'VALIDATION_ERROR',
      new ApiError('VALIDATION_ERROR', 400, '학번과 이름을 입력해주세요.'),
      /학번과 이름/,
    ],
  ])('%s를 화면에 띄우고 입력을 남긴다', async (_label, error, expected) => {
    api.submitError = error

    renderAt()
    fireEvent.change(await screen.findByLabelText('학번'), {
      target: { value: '2021123456' },
    })
    fireEvent.click(screen.getByRole('button', { name: '제출' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(expected)
    // 입력이 남아 있고, 대기 안내로 넘어가지 않았다.
    expect(screen.getByLabelText('학번')).toHaveValue('2021123456')
    expect(screen.queryByRole('heading', { name: '승인 대기 중' })).toBeNull()
  })
})

describe('제출 중 상태가 바뀌면', () => {
  /*
   * T-116 — **화면이 403 코드를 세션 계층에 넘겨야 한다.**
   *
   * 재현: 대기 중인 사람을 관리자가 정지 → 그 사람이 제출 → 403 SUSPENDED.
   * 넘기지 않으면 오류 문구만 뜨고 세션은 `pending`으로 남아 `/pending`에 갇힌다 —
   * #37에서 손본 정지 안내 경로가 여기서 샌다.
   */
  it('제출이 403 SUSPENDED로 막히면 정지 안내로 간다', async () => {
    api.submitError = new ApiError('SUSPENDED', 403, '정지된 계정입니다.')

    renderAt()
    fireEvent.change(await screen.findByLabelText('학번'), {
      target: { value: '2021123456' },
    })
    fireEvent.click(screen.getByRole('button', { name: '제출' }))

    await waitFor(() => {
      expect(pathname()).toBe('/login')
    })
    expect(await screen.findByRole('alert')).toHaveTextContent(/정지된 계정/)
  })

  // 세션이 바뀌지 않는 실패(409·400)에서는 화면에 그대로 남아 사유가 보인다.
  it('409는 세션을 건드리지 않고 화면에 사유만 남긴다', async () => {
    api.submitError = new ApiError(
      'DUPLICATE_STUDENT_NO',
      409,
      '이미 등록된 학번입니다.',
    )

    renderAt()
    fireEvent.change(await screen.findByLabelText('학번'), {
      target: { value: '2021123456' },
    })
    fireEvent.click(screen.getByRole('button', { name: '제출' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      /이미 등록된 학번/,
    )
    expect(pathname()).toBe('/pending')
  })
})

describe('상태를 확인하지 못하면', () => {
  /*
   * T-112 — 네트워크 오류로는 세션을 버리지 않는다 (spec §3-1-5). 대신 화면이 알린다 —
   * 아무 말도 안 하면 사용자는 버튼이 고장난 줄 안다.
   */
  it('다시 확인이 실패해도 튕기지 않고 다시 시도할 수 있게 알린다', async () => {
    api.me = APPLIED

    renderAt()
    await screen.findByRole('heading', { name: '승인 대기 중' })

    api.meError = new ApiError(
      'NETWORK_ERROR',
      0,
      '서버에 연결하지 못했습니다.',
    )
    fireEvent.click(screen.getByRole('button', { name: '다시 확인' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '상태를 확인하지 못했습니다',
    )
    // 세션은 그대로다 — 로그인 화면으로 튕기지 않는다.
    expect(pathname()).toBe('/pending')
    expect(
      screen.getByRole('heading', { name: '승인 대기 중' }),
    ).toBeInTheDocument()
  })

  it('신청 여부 확인이 실패하면 다시 시도할 길을 준다', async () => {
    api.meError = new ApiError(
      'PENDING_APPROVAL',
      403,
      '가입 승인 대기 중입니다.',
    )

    renderAt()
    await screen.findByText('신청 정보를 확인하는 중')

    // 화면이 다시 물어보는데 이번엔 네트워크가 끊겨 있다.
    api.meError = new ApiError(
      'NETWORK_ERROR',
      0,
      '서버에 연결하지 못했습니다.',
    )

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '상태를 확인하지 못했습니다',
    )
    expect(
      screen.getByRole('button', { name: '다시 확인' }),
    ).toBeInTheDocument()
  })
})

describe('승인 반영', () => {
  /*
   * T-109·T-110 — 승인은 이 화면에 저절로 반영되지 않는다 (spec §3-1-6).
   * 버튼이 필요하고, 버튼만 두고 설명이 없으면 누를 이유를 모른다.
   */
  it('저절로 바뀌지 않는다는 것을 화면이 알린다', async () => {
    api.me = APPLIED

    renderAt()
    await screen.findByRole('heading', { name: '승인 대기 중' })

    expect(screen.getByText(/저절로 바뀌지 않습니다/)).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: '다시 확인' }),
    ).toBeInTheDocument()
  })

  it('다시 확인을 누르면 서버에 물어보고, 승인되었으면 화면이 풀린다', async () => {
    api.me = APPLIED

    renderAt()
    await screen.findByRole('heading', { name: '승인 대기 중' })
    const before = api.meCalls

    // 그 사이 관리자가 승인했다.
    api.me = {
      ...APPLIED,
      status: 'ACTIVE',
      approvedAt: '2026-03-03T09:00:00Z',
    }
    fireEvent.click(screen.getByRole('button', { name: '다시 확인' }))

    await waitFor(() => {
      expect(pathname()).toBe('/notices')
    })
    expect(api.meCalls).toBeGreaterThan(before)
  })

  it('아직 승인 전이면 대기 화면에 그대로 머문다', async () => {
    api.me = APPLIED

    renderAt()
    await screen.findByRole('heading', { name: '승인 대기 중' })

    fireEvent.click(screen.getByRole('button', { name: '다시 확인' }))

    await waitFor(() => {
      expect(api.meCalls).toBeGreaterThan(1)
    })
    expect(
      screen.getByRole('heading', { name: '승인 대기 중' }),
    ).toBeInTheDocument()
    expect(pathname()).toBe('/pending')
  })
})

describe('신청 내용 수정', () => {
  // 승인 전까지 고칠 수 있다 (spec §3-1-6).
  it('수정을 누르면 낸 값이 채워진 폼이 열린다', async () => {
    api.me = APPLIED

    renderAt()
    await screen.findByRole('heading', { name: '승인 대기 중' })

    fireEvent.click(screen.getByRole('button', { name: '신청 내용 수정' }))

    expect(screen.getByLabelText('학번')).toHaveValue('2021123456')
    expect(screen.getByLabelText('이름')).toHaveValue('홍길동')
  })

  it('취소하면 대기 안내로 돌아간다', async () => {
    api.me = APPLIED

    renderAt()
    await screen.findByRole('heading', { name: '승인 대기 중' })
    fireEvent.click(screen.getByRole('button', { name: '신청 내용 수정' }))

    fireEvent.click(screen.getByRole('button', { name: '취소' }))

    expect(
      screen.getByRole('heading', { name: '승인 대기 중' }),
    ).toBeInTheDocument()
  })
})

describe('접근 범위', () => {
  // T-111 — PENDING에게 열린 유일한 인증 화면이다 (spec §3-1-6).
  it.each(['/notices', '/admin/members'])(
    '%s에 가면 대기 화면으로 되돌린다',
    async (path) => {
      api.me = APPLIED

      renderAt(path)

      await waitFor(() => {
        expect(pathname()).toBe('/pending')
      })
    },
  )

  // T-58 — 공개 랜딩은 인증 영역 밖이라 이 제한의 대상이 아니다.
  it('랜딩은 그대로 본다', async () => {
    api.me = APPLIED

    renderAt('/')

    expect(await screen.findByRole('heading', { level: 1 })).toBeInTheDocument()
    expect(pathname()).toBe('/')
  })

  // 로그아웃은 AppLayout 헤더가 이미 제공한다 (결정 2). 여기서 새로 만들지 않는다.
  it('로그아웃 버튼은 헤더에 하나만 있다', async () => {
    api.me = APPLIED

    renderAt()
    await screen.findByRole('heading', { name: '승인 대기 중' })

    expect(screen.getAllByRole('button', { name: '로그아웃' })).toHaveLength(1)
  })
})
