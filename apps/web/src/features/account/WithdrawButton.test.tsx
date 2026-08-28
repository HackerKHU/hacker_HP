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
import type { ContentSummary } from '@/api/adminUsers'
import { ApiError } from '@/api/client'
import type { User } from '@/api/types'
import { SessionProvider } from '@/auth/session'

/**
 * 회원 탈퇴 (#226, spec 5-TESTING **T-388·T-389·T-390·T-391·T-395·T-400·T-402**).
 *
 * **앱을 통째로 띄운다.** 이 흐름의 절반은 화면 밖에 있다 — 탈퇴가 끝나면 세션이 비고
 * 주소가 랜딩으로 바뀌어야 하는데, 컴포넌트만 렌더하면 그 둘 다 볼 수 없다.
 */

const api = vi.hoisted(() => ({
  me: (): Promise<User> =>
    Promise.reject(new Error('테스트가 지정하지 않았다')),
  summary: {
    notes: 3,
    notices: 0,
    photos: 5,
    posts: 2,
  } as ContentSummary,
  summaryError: null as unknown,
  /** 조회·탈퇴 호출 횟수. "부르지 않는다"를 재는 데 쓴다. */
  summaryCalls: 0,
  withdrawCalls: 0,
  withdrawError: null as unknown,
}))

vi.mock('@/api/auth', () => ({
  getMe: () => api.me(),
  logout: () => Promise.resolve(),
  myContentSummary: () => {
    api.summaryCalls += 1
    return api.summaryError
      ? Promise.reject(api.summaryError)
      : Promise.resolve(api.summary)
  },
  withdraw: () => {
    api.withdrawCalls += 1
    return api.withdrawError
      ? Promise.reject(api.withdrawError)
      : Promise.resolve()
  },
}))

const MEMBER: User = {
  id: 7,
  email: 'member@khu.ac.kr',
  studentNo: '2021123456',
  name: '홍길동',
  department: '컴퓨터공학과',
  role: 'USER',
  status: 'ACTIVE',
  createdAt: '2026-03-02T09:00:00Z',
  appliedAt: '2026-03-05T09:10:00Z',
  approvedAt: '2026-03-07T09:00:00Z',
}

function Address() {
  const { pathname } = useLocation()
  return <div data-testid="pathname">{pathname}</div>
}

function renderAt(path = '/me') {
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

/** 탈퇴 확인 창을 연다. 건수가 도착할 때까지 기다린다 — 그 전에는 확인 버튼이 잠겨 있다. */
async function openDialog() {
  fireEvent.click(await screen.findByRole('button', { name: '회원 탈퇴' }))
  return await screen.findByRole('alertdialog')
}

beforeEach(() => {
  api.me = () => Promise.resolve(MEMBER)
  api.summary = { notes: 3, notices: 0, photos: 5, posts: 2 }
  api.summaryError = null
  api.summaryCalls = 0
  api.withdrawCalls = 0
  api.withdrawError = null
})

describe('회원 탈퇴', () => {
  /*
   * T-388 — 남을 콘텐츠 **네 갈래**의 건수를 보여준다 (MUST). 관리자 제거보다 여기서 더
   * 쓸모 있다: 본인은 창을 닫고 직접 지운 뒤 다시 올 수 있다.
   */
  it('확인 창이 남을 콘텐츠 네 갈래의 건수를 보여준다', async () => {
    renderAt()
    const dialog = await openDialog()

    await waitFor(() =>
      expect(dialog).toHaveTextContent(
        '자료 3건, 공지 0건, 활동사진 5건, 게시글 2건이 "탈퇴한 회원"으로 남습니다.',
      ),
    )
    expect(dialog).toHaveTextContent('즐겨찾기는 함께 사라집니다.')
    expect(dialog).toHaveTextContent('되돌릴 수 없습니다.')
    expect(dialog).toHaveTextContent(
      '같은 구글 계정으로 다시 가입할 수 있습니다.',
    )
  })

  /* T-389 — 확인 창에서 취소하면 `DELETE /auth/me`를 부르지 않는다. */
  it('취소하면 탈퇴를 부르지 않는다', async () => {
    renderAt()
    const dialog = await openDialog()

    fireEvent.click(within(dialog).getByRole('button', { name: '취소' }))

    await waitFor(() =>
      expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument(),
    )
    expect(api.withdrawCalls).toBe(0)
    expect(pathname()).toBe('/me')
  })

  /*
   * T-395 — **건수를 못 읽었으면 확인 버튼이 잠긴다** (MUST). 열어 두면 사용자가 "남을
   * 것을 확인했다"는 잘못된 전제로 되돌릴 수 없는 조작을 한다.
   */
  it('건수를 못 받으면 실패를 알리고 탈퇴를 부를 수 없다', async () => {
    api.summaryError = new Error('network')
    renderAt()
    const dialog = await openDialog()

    await waitFor(() =>
      expect(dialog).toHaveTextContent(
        '남을 콘텐츠 건수를 불러오지 못했습니다.',
      ),
    )
    const confirm = within(dialog).getByRole('button', { name: '탈퇴' })
    expect(confirm).toBeDisabled()

    fireEvent.click(confirm)
    expect(api.withdrawCalls).toBe(0)
  })

  /*
   * T-391 — 성공하면 세션이 비로그인으로 정리된다. **랜딩으로 간다** (#226) — 보호 화면으로
   * 보내면 가드가 로그인으로 다시 튕겨, 방금 계정을 지운 사람이 로그인 화면을 마주한다.
   */
  it('탈퇴하면 세션을 비우고 랜딩으로 간다', async () => {
    renderAt()
    const dialog = await openDialog()

    await waitFor(() =>
      expect(
        within(dialog).getByRole('button', { name: '탈퇴' }),
      ).toBeEnabled(),
    )
    fireEvent.click(within(dialog).getByRole('button', { name: '탈퇴' }))

    await waitFor(() => expect(pathname()).toBe('/'))
    expect(api.withdrawCalls).toBe(1)
    /*
     * 세션이 비었다는 것은 **다시 들어가지지 않는 것**으로 확인한다. 뒤로 가기로 돌아와도
     * 가드가 로그인으로 보낸다 (#226 완료 조건).
     */
    expect(
      screen.queryByRole('heading', { name: '내 정보' }),
    ).not.toBeInTheDocument()
  })

  /*
   * T-402 — `403`(마지막 활성 관리자)·`409`(직전 재활성화)에서는 **계정이 그대로 남아
   * 있다.** 세션을 비우고 로그인으로 보내면 사용자는 탈퇴가 끝난 줄 안다.
   */
  it.each([
    [
      '마지막 활성 관리자',
      new ApiError(
        'FORBIDDEN',
        403,
        '마지막 활성 관리자는 탈퇴할 수 없습니다.',
      ),
    ],
    [
      '지우기 직전 재활성화',
      new ApiError('CONCURRENT_CHANGE', 409, '계정 상태가 방금 바뀌었습니다.'),
    ],
  ])(
    '%s이면 서버 문구를 그대로 보여주고 화면을 그대로 둔다',
    async (_label, error) => {
      api.withdrawError = error
      renderAt()
      const dialog = await openDialog()

      await waitFor(() =>
        expect(
          within(dialog).getByRole('button', { name: '탈퇴' }),
        ).toBeEnabled(),
      )
      fireEvent.click(within(dialog).getByRole('button', { name: '탈퇴' }))

      /*
       * 확인 창은 닫히므로 사유는 **창 밖에** 남는다 — 안에 두면 창과 함께 사라져,
       * 왜 안 되는지 못 본 채 같은 버튼을 다시 누르게 된다.
       */
      expect(await screen.findByRole('alert')).toHaveTextContent(
        (error as ApiError).message,
      )
      expect(pathname()).toBe('/me')
      expect(
        await screen.findByRole('heading', { name: '내 정보' }),
      ).toBeInTheDocument()
    },
  )

  /*
   * T-390·T-400 — `PENDING`은 마이페이지를 볼 수 없으므로 **신청·대기 화면이 그쪽의 유일한
   * 탈퇴 경로다.** 진입점이 보이는 것만으로는 부족하고, 건수 조회부터 비로그인 전환까지
   * 실제로 이어져야 한다 (T-400 MUST).
   */
  it('PENDING은 신청·대기 화면에서 탈퇴를 끝까지 실행한다', async () => {
    api.me = () =>
      Promise.resolve({ ...MEMBER, status: 'PENDING', approvedAt: null })
    api.summary = { notes: 0, notices: 0, photos: 0, posts: 0 }
    renderAt('/pending')

    await screen.findByRole('heading', { name: '승인 대기 중' })
    const dialog = await openDialog()

    await waitFor(() =>
      expect(dialog).toHaveTextContent(
        '자료 0건, 공지 0건, 활동사진 0건, 게시글 0건',
      ),
    )
    expect(api.summaryCalls).toBe(1)

    fireEvent.click(within(dialog).getByRole('button', { name: '탈퇴' }))

    await waitFor(() => expect(pathname()).toBe('/'))
    expect(api.withdrawCalls).toBe(1)
  })

  /* 신청 전 `PENDING`(신청 폼)에서도 나갈 수 있어야 한다 — 마음이 바뀌는 시점은 정해져 있지 않다. */
  it('신청서를 내기 전에도 탈퇴 진입점이 있다', async () => {
    api.me = () =>
      Promise.resolve({
        ...MEMBER,
        status: 'PENDING',
        studentNo: null,
        department: null,
        appliedAt: null,
        approvedAt: null,
      })
    renderAt('/pending')

    await screen.findByRole('heading', { name: '가입 신청' })
    expect(
      await screen.findByRole('button', { name: '회원 탈퇴' }),
    ).toBeInTheDocument()
  })

  /* 지울 것이 있는 사람에게만 "먼저 지우고 오라"고 말한다. 0건인데 안내하면 헛걸음이다. */
  it('남을 것이 없으면 미리 지우라는 안내를 하지 않는다', async () => {
    api.summary = { notes: 0, notices: 0, photos: 0, posts: 0 }
    renderAt()
    const dialog = await openDialog()

    await waitFor(() => expect(dialog).toHaveTextContent('자료 0건'))
    expect(
      within(dialog).queryByRole('link', { name: '자료게시판' }),
    ).not.toBeInTheDocument()
  })
})
