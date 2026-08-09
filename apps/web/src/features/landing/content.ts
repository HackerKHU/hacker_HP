/**
 * ⚠️ 랜딩 콘텐츠 — **전부 자리표시자다. 실제 값으로 교체해야 한다.**
 *
 * 아래 값은 동아리의 실제 회원 수·활동 이력·FAQ 답변·후원 정보가 **아니다.**
 * 작성 시점에 실제 정보를 알 수 없어 채워야 할 자리만 표시해 두었다.
 * **그럴듯한 문구를 지어 넣지 않았다** — 공개 페이지에 거짓이 올라가면 안 된다.
 *
 * 교체할 때는 `TODO:` 가 붙은 값을 전부 실제 내용으로 바꾸고 이 주석을 지운다.
 * 남아 있는 `TODO:`는 배포 전 점검 항목이다 — `grep -rn "TODO:" src/features/landing`.
 *
 * 랜딩은 정적이다 (spec 3-3 결정 8). 이 파일이 유일한 콘텐츠 출처이며 API를 호출하지
 * 않는다. 나중에 관리자 편집 기능이 붙으면 **이 파일이 API 응답으로 바뀐다.**
 */

export const CLUB = {
  name: 'HACKER',
  /** TODO: 실제 한 줄 소개로 교체 */
  tagline: 'TODO: 동아리를 한 줄로 소개하는 문장',
  /** TODO: 실제 소개 문단으로 교체. 문단 수는 자유롭게 늘리거나 줄인다 */
  about: [
    'TODO: 이 동아리가 무엇을 하는 곳인지 적는다.',
    'TODO: 어떤 활동을 하는지, 누가 모이는지 적는다.',
    'TODO: 어떤 사람이 지원하면 좋은지 적는다.',
  ],
} as const

export interface Stat {
  /** 단위를 숫자에 포함한다 — "81"보다 "81명"이 읽힌다 */
  value: string
  label: string
}

/** TODO: 실제 수치로 교체. 확인되지 않은 숫자를 쓰지 않는다 */
export const STATS: Stat[] = [
  { value: 'TODO명', label: '활동 중인 부원' },
  { value: 'TODO년', label: '동아리 운영' },
  { value: 'TODO회', label: '누적 세미나' },
  { value: 'TODO개', label: '진행한 프로젝트' },
]

export interface Photo {
  /**
   * `public/landing/` 아래 경로. 실물이 없으면 빈 문자열로 두고 화면이 자리표시자를
   * 그리게 한다. 파일을 넣는 방법은 `public/landing/README.md` 참고.
   */
  src: string
  /** 스크린리더가 읽는 설명. 사진을 넣을 때 함께 채운다 */
  alt: string
  caption: string
}

/** TODO: `public/landing/`에 실제 사진을 넣고 `src`와 `alt`를 채운다 */
export const PHOTOS: Photo[] = [
  { src: '', alt: '', caption: 'TODO: 활동 사진 설명' },
  { src: '', alt: '', caption: 'TODO: 활동 사진 설명' },
  { src: '', alt: '', caption: 'TODO: 활동 사진 설명' },
  { src: '', alt: '', caption: 'TODO: 활동 사진 설명' },
  { src: '', alt: '', caption: 'TODO: 활동 사진 설명' },
  { src: '', alt: '', caption: 'TODO: 활동 사진 설명' },
]

export interface Faq {
  question: string
  answer: string
}

/** TODO: 실제 질문과 답변으로 교체. 답을 모르는 채로 지어내지 않는다 */
export const FAQS: Faq[] = [
  {
    question: 'TODO: 자주 묻는 질문 1 (예: 지원 자격이 어떻게 되나요?)',
    answer: 'TODO: 실제 답변',
  },
  {
    question: 'TODO: 자주 묻는 질문 2 (예: 활동은 얼마나 자주 하나요?)',
    answer: 'TODO: 실제 답변',
  },
  {
    question: 'TODO: 자주 묻는 질문 3 (예: 회비가 있나요?)',
    answer: 'TODO: 실제 답변',
  },
  {
    question: 'TODO: 자주 묻는 질문 4 (예: 비전공자도 지원할 수 있나요?)',
    answer: 'TODO: 실제 답변',
  },
]

export const SUPPORT = {
  /** TODO: 실제 안내 문구로 교체 */
  description:
    'TODO: 어떤 형태의 후원을 받는지, 후원금이 어디에 쓰이는지 적는다.',
  /** TODO: 실제 문의 메일 주소로 교체 */
  email: 'TODO@example.com',
  /** 메일 제목. 받는 쪽에서 분류하기 쉽게 고정 문구를 둔다 */
  subject: '후원 문의',
} as const

/** 헤더와 본문 섹션이 같이 쓰는 목록. 순서가 곧 화면 순서다. */
export const SECTIONS = [
  { id: 'about', label: '소개' },
  { id: 'photos', label: '활동' },
  { id: 'stats', label: '숫자' },
  { id: 'faq', label: 'FAQ' },
  { id: 'support', label: '후원' },
] as const
