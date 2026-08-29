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
  submitted: [] as { studentNo: string; department: string }[],
  submitError: null as ApiError | null,
  /**
   * 학과 목록. **서버가 내려주는 값이다** (`GET /departments`, #166) — 화면이 갖고 있던
   * 사본은 지웠으므로, 여기 적은 값이 곧 화면에 뜨는 목록이다.
   */
  departments: ['컴퓨터공학과', '인공지능학과', '전자공학과'] as string[],
  departmentsError: null as Error | null,
  /** 호출 횟수. "다시 불러오기"가 실제로 다시 물었는지 본다. */
  departmentsCalls: 0,
}))

const BASE: User = {
  id: 3,
  email: 'applicant@khu.ac.kr',
  studentNo: null,
  name: '구글이름',
  department: null,
  role: 'USER',
  status: 'PENDING',
  createdAt: '2026-03-02T09:00:00Z',
  appliedAt: null,
  approvedAt: null,
}

/** 신청서를 낸 상태. **이름은 그대로다** — 신청서가 받는 값이 아니다 (#224). */
const APPLIED: User = {
  ...BASE,
  studentNo: '2021123456',
  department: '인공지능학과',
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
    submitApplication: async (body: {
      studentNo: string
      department: string
    }) => {
      await later(null)
      if (api.submitError) throw api.submitError
      api.submitted.push(body)
    },
  }
})

vi.mock('@/api/departments', () => ({
  getDepartments: async () => {
    api.departmentsCalls += 1
    await later(null)
    if (api.departmentsError) throw api.departmentsError
    return api.departments
  },
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

/**
 * 신청 폼을 채운다.
 *
 * **학과도 채워야 한다.** 최초 신청에서는 아무것도 고르지 않은 상태다(#165). 빠뜨리면
 * 화면이 클라이언트에서 막아, 서버 응답을 보려는 케이스가 요청도 못 보내고 통과해 버린다.
 *
 * 이름·이메일은 채우지 않는다 — 읽기 전용이라 사용자가 넣을 수 있는 값이 아니다 (#224).
 */
async function fillApplication(studentNo: string, department = '컴퓨터공학과') {
  fireEvent.change(await screen.findByLabelText('학번'), {
    target: { value: studentNo },
  })
  // 목록이 오기 전에는 `<select>`가 잠겨 있어 값이 바뀌지 않는다 (#166).
  await screen.findByRole('option', { name: department })
  fireEvent.change(screen.getByLabelText('학과'), {
    target: { value: department },
  })
}

beforeEach(() => {
  api.me = BASE
  api.meError = null
  api.meCalls = 0
  api.submitted = []
  api.departments = ['컴퓨터공학과', '인공지능학과', '전자공학과']
  api.departmentsError = null
  api.departmentsCalls = 0
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

  /*
   * **#132를 다시 뒤집는다** (#224). #132가 이름 칸을 비운 근거는 경희대 Workspace가 표시
   * 이름에 소속을 넣는다는 것이었다.
   *
   *     강경현[학생](소프트웨어융합대학 컴퓨터공학부)
   *
   * #215가 그 접미사를 계정 생성 시점에 걷어내면서 전제가 사라졌다. 저장된 값이 곧 실명이니
   * 본인이 다시 칠 이유가 없고, 칠 수 있게 두면 관리자가 심사하는 이름이 구글 계정과 다른
   * 값이 될 수 있다.
   *
   * **`disabled`가 아니라 `readOnly`다.** `disabled`는 값을 흐리게 만들고 포커스도 못 받아
   * 빈 칸처럼 읽힌다 — 여기 담긴 것은 "이 값으로 신청된다"는 사실이라 또렷해야 한다.
   */
  it('이름은 구글 계정의 값으로 채워지고 고칠 수 없다', async () => {
    renderAt()

    const name = await screen.findByLabelText('이름')
    expect(name).toHaveValue('구글이름')
    expect(name).toHaveAttribute('readOnly')
    expect(name).not.toBeDisabled()
  })

  /*
   * 이메일은 원래 신청 항목이 아니었다. **표시만** 더한다 (#224) — 구글 계정이 여럿인 사람이
   * 어느 계정으로 신청하는지 폼 안에서 확인할 수 있어야 한다. 제출 본문에는 담기지 않는다.
   */
  it('이메일도 계정 값으로 채워지고 고칠 수 없다', async () => {
    renderAt()

    const email = await screen.findByLabelText('이메일')
    expect(email).toHaveValue('applicant@khu.ac.kr')
    expect(email).toHaveAttribute('readOnly')
    expect(email).not.toBeDisabled()
  })

  // T-105
  it('신청 후에는 대기 안내와 낸 내용이 보인다', async () => {
    api.me = APPLIED

    renderAt()

    expect(
      await screen.findByRole('heading', { name: '승인 대기 중' }),
    ).toBeInTheDocument()
    expect(screen.getByText('2021123456')).toBeInTheDocument()
    expect(screen.getByText('구글이름')).toBeInTheDocument()
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

describe('필드 순서', () => {
  /*
   * **"확인하는 것 → 적는 것" 순서를 고정한다** (#293).
   *
   * 이름·이메일은 구글 계정에서 온 값이라 고칠 수 없고(#224), 학번·학과는 여기서 채운다.
   * 섞이면 칸을 채우다 막히고 다시 채우기를 반복하게 된다 — 실제로 학번이 맨 앞이라
   * 그랬다.
   *
   * **마이페이지와 같은 순서다** (#178 — 이름·이메일·학번·학과). 어느 한쪽만 손대면
   * 승인 전후로 같은 정보가 다른 자리에 놓이므로, 두 화면을 각자의 파일에서 함께 고정한다.
   *
   * 라벨 텍스트가 아니라 **DOM 순서**를 본다. 이름만 보면 자리를 바꿔도 통과한다.
   */
  it('신청 폼은 이름·이메일·학번·학과 순이다', async () => {
    renderAt()

    await screen.findByLabelText('학번')
    const form = document.querySelector('form') as HTMLFormElement
    expect(
      Array.from(form.querySelectorAll('label')).map(
        (label) => label.textContent,
      ),
    ).toEqual(['이름', '이메일', '학번', '학과'])
  })

  /* 제출 전후로 값이 자리를 옮기면 무엇이 저장됐는지 다시 훑게 된다. */
  it('대기 안내도 같은 순서다', async () => {
    api.me = APPLIED
    renderAt()

    await screen.findByRole('heading', { name: '승인 대기 중' })
    const list = document.querySelector('dl') as HTMLElement
    expect(
      Array.from(list.querySelectorAll('dt')).map((term) => term.textContent),
    ).toEqual(['이름', '학번', '학과'])
  })
})

describe('신청서 제출', () => {
  // T-107
  it('제출하면 서버에서 다시 읽어 대기 안내로 바뀐다', async () => {
    renderAt()

    await fillApplication('2021123456', '인공지능학과')
    // 제출 성공 뒤 화면은 서버가 준 값으로 그려진다.
    api.me = APPLIED
    fireEvent.click(screen.getByRole('button', { name: '제출' }))

    expect(
      await screen.findByRole('heading', { name: '승인 대기 중' }),
    ).toBeInTheDocument()
    /*
     * **두 필드를 다 담아야 한다** (spec §3-2-3 MUST). `department`가 빠진 채로 나가면
     * 서버가 `400 VALIDATION_ERROR`로 막는데, 화면에 고를 자리가 없으면 사용자가 그
     * 오류를 풀 방법이 없다 — 실제로 그 상태로 배포됐다 (#165).
     *
     * **`name`이 없는 것도 계약이다** (#224). 담아 보내면 서버가 무시하지만, 화면이 보내는
     * 순간 "이름을 바꿀 수 있다"는 잘못된 전제가 코드에 남는다.
     */
    expect(api.submitted).toEqual([
      { studentNo: '2021123456', department: '인공지능학과' },
    ])
  })

  /*
   * 학과는 목록에서만 고른다 (§3-2-2 MUST). 자유 입력 칸이면 표기가 제각각이 되어 회원
   * 목록에서 학과로 거르는 것이 무의미해진다.
   */
  it('학과는 서버가 내려준 목록에서 고른다', async () => {
    renderAt()

    const select = await screen.findByLabelText('학과')
    expect(select.tagName).toBe('SELECT')

    /*
     * **목록을 화면이 갖고 있지 않다** (#166). 사본을 두면 서버에만 학과를 더했을 때
     * 그 학과 지원자가 고를 자리가 없고, 웹에만 더하면 `400`으로 막힌다.
     */
    await waitFor(() =>
      expect(
        within(select)
          .getAllByRole('option')
          .slice(1)
          .map((option) => option.textContent),
      ).toEqual(api.departments),
    )
    // 첫 항목은 "안 고름"이다.
    expect(within(select).getAllByRole('option')[0]).toHaveValue('')
  })

  /*
   * 목록을 못 받으면 학과를 고를 수 없고, 학과 없이는 서버가 신청을 거부한다 (§3-2-3 MUST).
   * 조용히 빈 `<select>`를 두면 사용자는 제출이 안 되는 이유를 알 방법이 없다 (#166).
   */
  it('학과 목록을 못 받으면 알리고 다시 불러올 수 있다', async () => {
    api.departmentsError = new Error('network')
    renderAt()

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '학과 목록을 불러오지 못했습니다.',
    )
    expect(api.departmentsCalls).toBe(1)

    api.departmentsError = null
    fireEvent.click(screen.getByRole('button', { name: '다시 불러오기' }))

    expect(
      await screen.findByRole('option', { name: '인공지능학과' }),
    ).toBeInTheDocument()
    expect(api.departmentsCalls).toBe(2)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  /*
   * 기본값을 첫 항목으로 두면 손대지 않은 사람이 컴퓨터공학과로 저장된다 — 부원 다수의
   * 소속이라 틀려도 그럴듯해 보여서 아무도 못 잡는다. 안 고르면 막는 편이 낫다.
   */
  it('학과를 안 고르면 요청이 나가지 않는다', async () => {
    renderAt()

    fireEvent.change(await screen.findByLabelText('학번'), {
      target: { value: '2021123456' },
    })
    fireEvent.click(screen.getByRole('button', { name: '제출' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '학과를 선택해주세요.',
    )
    expect(api.submitted).toEqual([])
  })

  // 결정 5 — 낙관적으로 바꾸지 않는다. 서버가 저장했는지 확인된 뒤에 바뀐다.
  it('제출은 성공했지만 서버가 아직 신청 전이라고 하면 폼에 머문다', async () => {
    renderAt()

    await fillApplication('2021123456')
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

    // 학과는 고른다 — 둘 다 비면 무엇 때문에 막혔는지 이 케이스가 가리지 못한다.
    await fillApplication('   ')
    fireEvent.click(screen.getByRole('button', { name: '제출' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '학번을 입력해주세요.',
    )
    expect(api.submitted).toEqual([])
  })

  // 결정 4 — 계약에 없는 형식 규칙을 만들지 않는다. 상한만 스키마에서 온다.
  it('학번 상한은 스키마 값이고 그 밖의 형식은 막지 않는다', async () => {
    renderAt()

    const studentNo = await screen.findByLabelText('학번')
    expect(studentNo).toHaveAttribute('maxLength', '20')

    // 숫자가 아닌 학번도 그대로 받는다 — 편입·교환학생 학번이 그렇다.
    await fillApplication('EX-2021-7')
    fireEvent.click(screen.getByRole('button', { name: '제출' }))

    await waitFor(() => {
      expect(api.submitted).toEqual([
        { studentNo: 'EX-2021-7', department: '컴퓨터공학과' },
      ])
    })
  })

  /*
   * 예시(placeholder)는 **규칙이 아니다.** 위 케이스가 `EX-2021-7`로 그것을 지키고,
   * 여기서는 예시가 형식 제약으로 새어나가지 않았는지를 본다 — `pattern`·`inputMode`가
   * 붙으면 브라우저가 예시와 같은 모양을 강제하게 된다.
   */
  it('학번 예시는 올해 연도로 만들고, 예시일 뿐 형식을 강제하지 않는다', async () => {
    renderAt()

    const studentNo = await screen.findByLabelText('학번')
    /*
     * **화면 값을 코드에서 가져오지 않는다.** 여기서 상수를 import하면 코드가 `2026`을
     * 박아두어도 같이 틀린 값을 비교하게 된다. 연도는 테스트가 따로 구한다 — 코드가
     * 리터럴로 굳는 순간 해가 바뀌면 이 단언이 깨진다. 그것이 잡으려는 결함이다.
     */
    expect(studentNo).toHaveAttribute(
      'placeholder',
      `${new Date().getFullYear()}000000`,
    )
    expect(studentNo).not.toHaveAttribute('pattern')
    expect(studentNo).not.toHaveAttribute('inputMode')
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
      new ApiError('VALIDATION_ERROR', 400, '학번을 입력해 주세요.'),
      /학번을 입력/,
    ],
  ])('%s를 화면에 띄우고 입력을 남긴다', async (_label, error, expected) => {
    api.submitError = error

    renderAt()
    await fillApplication('2021123456')
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
    await fillApplication('2021123456')
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
    await fillApplication('2021123456')
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

  /*
   * T-115의 화면 절반 — **`refresh()` 경로로 정지가 오는 경우.**
   *
   * 제출 실패는 `reportApiError()`를 타지만 "다시 확인"은 `refresh()`를 탄다. 두 경로가
   * 별개라, 한쪽만 확인하면 다른 쪽 배선이 끊겨도 안 잡힌다.
   */
  it('다시 확인 중 정지된 것이 드러나면 정지 안내로 간다', async () => {
    api.me = APPLIED

    renderAt()
    await screen.findByRole('heading', { name: '승인 대기 중' })

    // 그 사이 관리자가 정지시켰다.
    api.meError = new ApiError('SUSPENDED', 403, '정지된 계정입니다.')
    fireEvent.click(screen.getByRole('button', { name: '다시 확인' }))

    await waitFor(() => {
      expect(pathname()).toBe('/login')
    })
    expect(await screen.findByRole('alert')).toHaveTextContent(/정지된 계정/)
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
    expect(screen.getByLabelText('학과')).toHaveValue('인공지능학과')
    // 이름은 수정 화면에서도 고칠 수 없다 (#224).
    expect(screen.getByLabelText('이름')).toHaveAttribute('readOnly')
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

  /*
   * 로그아웃은 `AppLayout` 헤더의 계정 메뉴가 제공한다 (결정 2, #178). 이 화면이 새로
   * 만들지 않는다 — 만들면 같은 조작이 두 곳에 생기고, 한쪽만 고쳐진다.
   */
  it('로그아웃은 이 화면이 아니라 헤더의 계정 메뉴에 있다', async () => {
    api.me = APPLIED

    renderAt()
    await screen.findByRole('heading', { name: '승인 대기 중' })

    // 메뉴를 열기 전에는 화면 어디에도 없다.
    expect(screen.queryByRole('button', { name: '로그아웃' })).toBeNull()

    const trigger = screen.getByRole('button', { name: '계정 메뉴' })
    fireEvent.pointerDown(
      trigger,
      new MouseEvent('pointerdown', { bubbles: true, button: 0 }),
    )
    await screen.findByRole('menu')

    expect(screen.getAllByRole('menuitem', { name: '로그아웃' })).toHaveLength(
      1,
    )
  })
})
