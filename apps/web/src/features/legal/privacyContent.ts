/**
 * 개인정보처리방침 초안.
 *
 * ⚠️ **법적 문서다. 검토 없이 공개하지 않는다.** 모르는 항목은 그럴듯하게 채우지 않고
 * `todo`로 남겼다 — 틀린 내용이 올라가면 없느니만 못하다.
 *
 * ⚠️ **구글 OAuth 전제는 아직 `develop`에 반영되지 않았다.** 수집 항목을 "구글 계정에서
 * 받는 이메일·이름"으로, 비밀번호를 저장하지 않는 것으로 쓴 것은 [3-3 결정 13]을 따른
 * 것인데, 그 결정과 스키마 변경은 `docs/76-google-oauth-signup`에 있고 아직 머지 전이다.
 * 현재 `develop`의 `V1__init.sql`에는 `password_hash`가 남아 있다.
 * **#76이 머지된 뒤에 이 문서가 사실이 된다.** 그 전에 공개하면 안 된다.
 *
 * 근거 문서 — 수집 항목은 `spec/3-2-DESIGN-CONTRACT.md`의 `users` 테이블,
 * 가입 거부 시 삭제는 `spec/3-1-DESIGN-ARCHITECTURE.md` §3-1-2, 위탁 업체는
 * `docs/ops/infra.md`와 `spec/3-3-DESIGN-DECISIONS.md`.
 */

export interface PrivacySection {
  title: string
  paragraphs?: string[]
  items?: string[]
  /** 채워야 할 내용. 화면에 자리표시자로 드러난다. */
  todo?: string
}

export const PRIVACY_UPDATED = {
  /** TODO: 시행일을 정해서 넣는다. */
  effectiveDate: null as string | null,
}

export const PRIVACY_SECTIONS: PrivacySection[] = [
  {
    title: '1. 수집하는 개인정보',
    paragraphs: [
      '회원 가입과 서비스 이용 과정에서 아래 정보를 수집합니다. 비밀번호는 받지도 저장하지도 않습니다.',
    ],
    items: [
      '구글 계정에서 받는 이메일 주소와 이름',
      '학번',
      '가입 신청일시와 승인일시',
      '로그인 상태를 유지하기 위한 세션 정보',
    ],
  },
  {
    title: '2. 수집·이용 목적',
    items: [
      '동아리 부원 여부 확인과 가입 승인',
      '공지와 자료 등 회원 서비스 제공',
      '관리자의 회원 관리',
    ],
  },
  {
    title: '3. 처리 위탁',
    paragraphs: ['서비스 운영에 필요한 범위에서 아래에 처리를 위탁합니다.'],
    items: [
      'Amazon Web Services — 서버, 데이터베이스, 파일 저장',
      'Vercel — 프론트엔드 배포',
      'Google — 로그인 인증',
    ],
  },
  {
    title: '4. 보관과 파기',
    paragraphs: [
      '가입 신청이 거부되면 계정 기록을 삭제합니다. 별도의 거부 상태를 남기지 않으므로 거부된 분은 다시 신청할 수 있습니다.',
    ],
    todo: '그 밖의 보관 기간을 정해서 넣어야 합니다.',
  },
  {
    title: '5. 제3자 제공',
    todo: '제3자 제공 여부와 범위를 확인해서 넣어야 합니다.',
  },
  {
    title: '6. 이용자의 권리',
    paragraphs: [
      '본인의 개인정보를 열람하거나 정정·삭제해 달라고 요구할 수 있습니다.',
    ],
    todo: '요구하는 방법(연락 경로와 처리 절차)을 넣어야 합니다.',
  },
  {
    title: '7. 개인정보 보호책임자와 문의처',
    todo: '보호책임자와 연락처를 넣어야 합니다.',
  },
]
