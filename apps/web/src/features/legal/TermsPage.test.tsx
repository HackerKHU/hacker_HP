import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import App from '@/App'
import { ApiError } from '@/api/client'
import { SessionProvider } from '@/auth/session'
import { MemoryRouter } from '@/test/TestRouter'

vi.mock('@/api/auth', () => ({
  getMe: () =>
    Promise.reject(
      new ApiError('UNAUTHENTICATED', 401, '로그인이 필요합니다.'),
    ),
}))

vi.mock('@/api/notices', () => ({
  list: () => Promise.reject(new Error('이 화면에서는 쓰지 않는다')),
  get: () => Promise.reject(new Error('이 화면에서는 쓰지 않는다')),
  togglePin: () => Promise.reject(new Error('이 화면에서는 쓰지 않는다')),
}))

function open(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  )
}

async function openTerms() {
  open('/terms')
  return screen.findByRole('heading', { name: '이용약관', level: 1 })
}

describe('이용약관', () => {
  /*
   * **가드를 붙이지 않는다.** 방침과 같은 공개 문서이고, 특히 정지된 사람이 자기가
   * 무엇을 어겼다는 것인지 읽으려면 로그인 없이 열려야 한다.
   */
  it('비로그인 상태에서 열린다', async () => {
    expect(await openTerms()).toBeInTheDocument()
  })

  /*
   * 조항마다 대응하는 구현이 있다. 문구가 지워지면 운영진이 실제로 하는 조치의 근거가
   * 사라지므로 화면에서 고정한다.
   */
  it('승인제·정지·삭제 근거를 밝힌다', async () => {
    await openTerms()

    // 계정 — 구글 로그인과 승인제 (3-1 §3-1-2)
    expect(screen.getByText(/구글 계정으로 로그인/)).toBeInTheDocument()
    expect(screen.getByText(/가입은 승인제입니다/)).toBeInTheDocument()
    // 이용 제한 — SUSPENDED와 회원 제거의 근거
    expect(
      screen.getByText(/계정을 정지하거나 회원에서 제거할 수 있습니다/),
    ).toBeInTheDocument()
    // 서비스 중단 고지
    expect(screen.getByText(/최소 30일 전에 공지로/)).toBeInTheDocument()
    expect(screen.getByText(/부터 시행합니다/)).toBeInTheDocument()
  })

  /*
   * **약관이 권한 매트릭스보다 넓게 적히면 안 된다** (3-1 §3-1-3). 자유 게시판에는
   * 삭제 기능 자체가 없으므로(3-3 결정 16) "운영진이 지운다"로 뭉뚱그리면 거짓이다.
   */
  it('게시판 글은 아무도 화면에서 지울 수 없다는 것을 따로 밝힌다', async () => {
    await openTerms()

    expect(
      screen.getByText(/올리신 분도 운영진도 화면에서 지우실 수 없습니다/),
    ).toBeInTheDocument()
  })

  /*
   * 방침과 같다. 문단은 통짜 문자열이고 줄을 바꾸는 것은 브라우저 몫이다. 문자열의
   * `\n`과 `whitespace-pre` 계열 클래스를 함께 본다. 한쪽만 보면 다른 쪽으로 같은
   * 결과가 나는 것을 놓친다.
   */
  it('문단에 개행이 남지 않는다', async () => {
    await openTerms()
    const blocks = document.querySelectorAll('dd p, dd li')

    expect(blocks.length).toBeGreaterThan(0)
    for (const block of blocks) {
      expect(block.textContent).not.toMatch(/[\n\r]/)
      expect(block.className).not.toMatch(/whitespace-(pre|break-spaces)/)
    }
  })

  /* 방침과 같은 규칙이다. 줄표 뒤에 놓이던 것이 조건 쪽이라 곁가지로 읽히면 안 된다. */
  it('본문에 줄표가 없다', async () => {
    await openTerms()

    expect(document.body.textContent).not.toContain('—')
  })

  /*
   * 링크가 없으면 문서가 있어도 아무도 못 읽는다. 랜딩 푸터와 로그인 카드 두 곳을
   * 본다 — 앱 셸 푸터는 로그인 이후 화면이라 여기서 열리지 않는다.
   */
  it.each([
    ['/', '랜딩 푸터'],
    ['/login', '로그인 카드'],
  ])('%s에 약관으로 가는 길이 있다 (%s)', async (path) => {
    open(path)

    expect(
      await screen.findByRole('link', { name: '이용약관' }),
    ).toHaveAttribute('href', '/terms')
  })
})
