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
  return screen.getByText('4. 보관과 파기').parentElement as HTMLElement
}

describe('개인정보처리방침', () => {
  // 랜딩과 같은 공개 페이지다. 가드 아래로 들어가면 비로그인이 못 본다.
  it('비로그인 상태에서 열린다', async () => {
    expect(await openPrivacy()).toBeInTheDocument()
    // 미완성 항목이 있다는 안내가 보여야 한다.
    expect(screen.getByRole('note')).toHaveTextContent(
      '초안이며 검토가 필요합니다',
    )
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
    // 계정이 제거된 뒤에는 본인이 지울 수 없다는 것도 알려야 한다.
    expect(section).toHaveTextContent('로그인하실 수 없어')
  })
})
