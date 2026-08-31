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

function purposeSection() {
  return screen.getByText('2. 수집·이용 목적').parentElement as HTMLElement
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
    expect(
      screen.getByText('이 방침은 2026년 8월 31일부터 시행합니다.'),
    ).toBeInTheDocument()
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

  it('가입 거부 때 신청 정보만 지우고 계정 식별정보와 재신청 경로는 유지한다고 알린다', async () => {
    await openPrivacy()
    const section = retentionSection()

    expect(section).toHaveTextContent(
      '학번과 학과, 가입 신청일시는 즉시 삭제합니다',
    )
    expect(section).toHaveTextContent(
      '구글 계정 식별자와 이메일 주소, 이름, 계정 생성일시는 미승인 계정 기록으로 유지',
    )
    expect(section).toHaveTextContent(
      '같은 계정으로 로그인해 다시 신청서를 제출',
    )
    expect(section).not.toHaveTextContent(
      '가입 신청이 거부되면 계정 기록을 삭제',
    )
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
    expect(section).toHaveTextContent('로그인하실 수 없고')
  })

  /** 게시판 작성자 본인과 운영진의 완전 삭제 경계를 그대로 알린다 (결정 20). */
  it('게시글은 직접 완전 삭제할 수 있고 계정 제거 뒤에는 운영진에게 요청한다고 알린다', async () => {
    await openPrivacy()
    const section = retentionSection()

    expect(section).toHaveTextContent(
      '자유 게시판에 올리신 글은 상세 화면에서 직접 완전히 삭제할 수 있습니다',
    )
    expect(section).toHaveTextContent(
      '작성자 관계도 끊겨 직접 지울 수 없으므로',
    )
    expect(section).toHaveTextContent('문의처로 알려 주시기')
    expect(section).toHaveTextContent('운영진이 관리 화면에서 완전히 삭제')
  })

  /*
   * **저장하는 컬럼을 빠짐없이 적는다** (#80). `users.department`는 실제로 저장되는데
   * 수집 항목에서 빠져 있었다 — 공개 문서가 "이건 안 받습니다"라고 말한 셈이다.
   *
   * 학번만 보면 신청서에서 받는 두 값 중 하나가 빠져도 통과하므로 둘을 함께 본다.
   */
  /*
   * T-435 — **학번 뒷자리 노출을 고지한다** (#300 결정 18, #301).
   *
   * 결정 18은 이 고지를 **조건으로** 걸었다. 나머지 사례는 전부 서버가 만드는 표시 이름만
   * 보므로, 이것이 없으면 **표시 이름만 배포해도 전부 통과한다** — 고지 없이 노출된다.
   *
   * 노출되지 **않는** 것까지 함께 본다. "학번을 보여준다"로만 적으면 다음 사람이 범위를
   * 넓혀도 이 사례가 잡지 못한다.
   */
  it('학번 뒷자리가 다른 부원에게 보인다는 것을 알린다', async () => {
    await openPrivacy()
    const section = purposeSection()

    expect(section).toHaveTextContent('학번 끝 두 자리')
    expect(section).toHaveTextContent('다른 부원에게도 보입니다')
    expect(section).toHaveTextContent(
      '학번 전체나 학과, 이메일 주소는 다른 부원에게 보이지 않습니다',
    )
  })

  it('신청서에서 받는 학번과 학과를 모두 수집 항목에 적는다', async () => {
    await openPrivacy()
    const section = screen.getByText('1. 수집하는 개인정보')
      .parentElement as HTMLElement

    expect(section).toHaveTextContent('학번')
    expect(section).toHaveTextContent('학과')
  })

  /*
   * **방침이 안내하는 길이 실제 화면과 같아야 한다.** 없는 화면을 적으면 이용자가 찾아
   * 헤매고, 있는 길을 빼면 스스로 할 수 있는 것을 문의처로 떠넘긴다. 둘 다 권리 행사
   * 절차를 잘못 알린 것이다.
   *
   * 경로는 넷으로 갈린다: 열람은 마이페이지(#178), 탈퇴는 마이페이지와 신청·대기
   * 화면(#226), 이름은 구글 계정 값 고정(#224), 학번·학과는 승인 대기 중 신청서 재제출.
   * 승인된 뒤의 정정만 문의처다. 뭉뚱그리면 그 구분이 사라진다.
   */
  /*
   * **본문에 줄표를 쓰지 않는다.** 줄표 뒤에 있던 것은 곁가지가 아니라 조건이었고,
   * 곁가지처럼 읽히면 안 되는 말이었다. 문안을 고칠 때 한 군데씩 되돌아오기 쉬워
   * 화면 전체에서 한 번에 막는다.
   */
  /*
   * **문단은 통짜 문자열이고 줄을 바꾸는 것은 브라우저 몫이다.**
   *
   * 문자열에 `\n`을 박거나 `whitespace-pre-wrap`을 걸면 화면 폭과 상관없이 정해진
   * 자리에서 꺾인다. 좁은 화면에서는 한 줄이 두 번 접히고 넓은 화면에서는 오른쪽이
   * 비는데, **소스에서는 그냥 보기 좋게 정리한 것처럼 보여서** 알아채기 어렵다.
   *
   * 둘 다 본다. 문자열 쪽만 보면 CSS로 같은 결과가 나는 것을 놓친다.
   */
  it('문단에 개행이 남지 않는다', async () => {
    await openPrivacy()
    const blocks = document.querySelectorAll('dd p, dd li')

    expect(blocks.length).toBeGreaterThan(0)
    for (const block of blocks) {
      expect(block.textContent).not.toMatch(/[\n\r]/)
      expect(block.className).not.toMatch(/whitespace-(pre|break-spaces)/)
    }
  })

  it('본문에 줄표가 없다', async () => {
    await openPrivacy()

    expect(document.body.textContent).not.toContain('—')
  })

  it('정정 경로를 실제 화면대로 안내한다', async () => {
    await openPrivacy()
    const section = screen.getByText('6. 이용자의 권리')
      .parentElement as HTMLElement

    // ① 열람은 마이페이지에서 직접 (#178)
    expect(section).toHaveTextContent('마이페이지에서 바로')
    // ② 이름은 구글 계정에서 바꾼다
    expect(section).toHaveTextContent('구글 계정에 저장된 값')
    // ③ 학번·학과는 승인 대기 중에만 신청서 재제출로
    expect(section).toHaveTextContent('신청서를 다시 제출해')
    // ④ 승인된 뒤의 정정만 문의처다
    expect(section).toHaveTextContent('화면에서 고치는 길이 없으니')
    /*
     * ⑤ 탈퇴는 본인이 직접 한다 (#226). 문의처로만 안내하면 **스스로 할 수 있는 것을
     * 못 하는 줄 알고 기다리게 된다** — 방침 §6이 고지하는 "삭제를 요구할 수 있다"를
     * 본인이 실행하는 경로가 실제로 생겼다.
     */
    expect(section).toHaveTextContent('마이페이지의 회원 탈퇴로 직접')
    expect(section).toHaveTextContent('신청·대기 화면에서 하실 수 있습니다')
  })
})
