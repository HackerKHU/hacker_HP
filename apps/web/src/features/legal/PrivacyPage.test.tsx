import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import App from '@/App'
import { ApiError } from '@/api/client'
import { SessionProvider } from '@/auth/session'

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

async function openPrivacy() {
  render(
    <MemoryRouter initialEntries={['/privacy']}>
      <SessionProvider>
        <App />
      </SessionProvider>
    </MemoryRouter>,
  )

  return screen.findByRole('heading', {
    name: '개인정보처리방침',
    level: 1,
  })
}

/** 항목은 `dt`/`dd` 짝이라 heading이 아니다. 제목을 감싼 블록을 집는다. */
function retentionSection() {
  return screen.getByText('5. 보관과 파기').parentElement as HTMLElement
}

describe('개인정보처리방침', () => {
  // 랜딩과 같은 공개 페이지다. 가드 아래로 들어가면 비로그인이 못 본다.
  it('비로그인 상태에서 열린다', async () => {
    expect(await openPrivacy()).toBeInTheDocument()
  })

  /*
   * **초안 안내가 남아 있으면 미완성이다** (#87 완료 조건). 문안을 확정하고도 이 문구를
   * 지우지 않으면 방문자는 여전히 믿을 수 없는 문서로 읽는다.
   */
  it('초안 안내와 미확정 자리표시자가 남아 있지 않다', async () => {
    await openPrivacy()

    expect(screen.queryByRole('note')).not.toBeInTheDocument()
    expect(
      screen.queryByText(/확인 후 채워야 하는 항목/),
    ).not.toBeInTheDocument()
  })

  /*
   * 공개 전에 채우기로 한 네 항목이다. 하나라도 빠지면 법적 고지로서 성립하지 않으므로
   * 문구가 지워지는 것을 화면에서 막는다.
   */
  it('제3자 제공·권리 행사·보호책임자·시행일을 밝힌다', async () => {
    await openPrivacy()

    expect(
      screen.getByText(/개인정보를 제3자에게 제공하지 않습니다/),
    ).toBeInTheDocument()
    // 권리 행사와 문의는 같은 주소로 받는다. 주소가 빠지면 행사할 길이 없다.
    expect(
      screen.getAllByText(/hacker19870101@gmail\.com/).length,
    ).toBeGreaterThan(1)
    expect(screen.getByText(/보호책임자는 동아리 회장/)).toBeInTheDocument()
    expect(screen.getByText(/부터 시행합니다/)).toBeInTheDocument()
  })

  /*
   * 탈퇴 뒤에 무엇이 남는지는 고지 대상이다 (spec 2-2 §2-2-4, #49).
   *
   * 이 셋이 없으면 화면은 "계정을 지웁니다"까지만 말하게 되는데, 그것은
   * 실제 보관 방식과 다르다. 문구가 지워지거나 다시 익명화로 읽히게
   * 바뀌어도 통과하면 안 되므로 화면에서 고정한다.
   */
  it('탈퇴 뒤에 콘텐츠가 남는다는 것을 알린다', async () => {
    await openPrivacy()
    const section = retentionSection()

    // ① 콘텐츠는 남는다
    expect(section).toHaveTextContent(
      '자료와 첨부 파일, 공지, 활동사진, 게시글은 남습니다',
    )
    // ② 남되 올린 사람 표시만 바뀐다
    expect(section).toHaveTextContent('탈퇴한 회원')
    // ③ 본인만 보던 기록은 함께 사라진다
    expect(section).toHaveTextContent('즐겨찾기')
  })

  /*
   * 익명화라고 읽히면 안 된다. 자료 본문이나 파일 이름, 사진에 담긴
   * 정보는 그대로 남으므로 "표시까지"라는 범위를 화면이 말해야 한다.
   */
  it('콘텐츠 안의 개인정보는 남는다는 범위를 밝힌다', async () => {
    await openPrivacy()
    const section = retentionSection()

    expect(section).toHaveTextContent('올린 사람 이름 자리까지')
    expect(section).toHaveTextContent('그것만으로 누구인지 알아볼 수 있습니다')
    // 자료·공지뿐 아니라 게시글 본문도 같은 대상이다 (#235).
    expect(section).toHaveTextContent('게시글 내용')
    // 계정이 제거된 뒤에는 본인이 지울 수 없다는 것도 알려야 한다.
    expect(section).toHaveTextContent('로그인하실 수 없어')
  })

  /*
   * 게시판에는 삭제 기능이 자체가 없다 (spec 3-3 결정 16, #235).
   *
   * 바로 위 문단이 "남기고 싶지 않은 것은 직접 삭제하시라"고 안내하는데,
   * 게시글에는 그 길이 없다. 구분해 적지 않으면 이용자는 계정을 유지하는
   * 동안에는 스스로 지울 수 있다고 읽는다 — 사실과 다른 안내다.
   */
  it('게시글은 직접 지울 수 없다는 것을 따로 알린다', async () => {
    await openPrivacy()
    const section = retentionSection()

    expect(section).toHaveTextContent(
      '자유 게시판에 올리신 글은 직접 지우실 수 없습니다',
    )
    expect(section).toHaveTextContent('문의처로 알려 주시기')
  })

  /*
   * **저장하는 컬럼을 빠짐없이 적는다** (#80). `users.department`는 실제로 저장되는데
   * 수집 항목에서 빠져 있었다 — 공개 문서가 "이건 안 받습니다"라고 말한 셈이다.
   *
   * 학번만 보면 신청서에서 받는 두 값 중 하나가 빠져도 통과하므로 둘을 함께 본다.
   */
  it('신청서에서 받는 학번과 학과를 모두 수집 항목에 적는다', async () => {
    await openPrivacy()
    const section = screen.getByText('1. 수집하는 개인정보')
      .parentElement as HTMLElement

    expect(section).toHaveTextContent('학번')
    expect(section).toHaveTextContent('학과')
  })

  /*
   * **없는 화면을 정정 경로로 안내하지 않는다.** 마이페이지는 존재한 적이 없다.
   *
   * 실제 경로는 셋으로 갈린다 — 이름은 구글 계정 값 고정(#224), 학번·학과는 승인
   * 대기 중 신청서 재제출, 그 밖에는 문의처. 이걸 뭉뚱그리면 이용자가 고칠 수 있는
   * 것을 못 고치거나, 없는 화면을 찾아 헤맨다. 셋을 각각 고정한다.
   */
  /*
   * **본문에 줄표를 쓰지 않는다.** 줄표 뒤에 있던 것은 곁가지가 아니라 조건이었고,
   * 곁가지처럼 읽히면 안 되는 말이었다. 문안을 고칠 때 한 군데씩 되돌아오기 쉬워
   * 화면 전체에서 한 번에 막는다.
   */
  it('본문에 줄표가 없다', async () => {
    await openPrivacy()

    expect(document.body.textContent).not.toContain('—')
  })

  it('정정 경로를 실제 화면대로 안내한다', async () => {
    await openPrivacy()
    const section = screen.getByText('6. 이용자의 권리')
      .parentElement as HTMLElement

    expect(section).not.toHaveTextContent('마이페이지')
    // ① 이름 — 구글 계정에서 바꾼다
    expect(section).toHaveTextContent('구글 계정에 저장된 값')
    // ② 학번·학과 — 승인 대기 중에만 신청서 재제출로
    expect(section).toHaveTextContent('신청서를 다시 제출해')
    // ③ 승인된 뒤에는 화면에 길이 없다
    expect(section).toHaveTextContent('화면에서 고치는 길이 없으니')
  })
})
