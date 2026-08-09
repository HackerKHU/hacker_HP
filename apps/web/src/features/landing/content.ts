/**
 * 랜딩 콘텐츠.
 *
 * ⚠️ **사실 확인이 필요하다.** 아래 내용은 사용자가 직접 준 문장과, 공식 인스타그램
 * [@khu_hacker](https://www.instagram.com/khu_hacker/)에서 읽은 내용을 근거로 쓴 초안이다.
 * 인스타그램에서 읽은 부분(활동 종류·동아리방 호수·모집 대상)은 **동아리 운영진이 한 번
 * 검토해야 한다.** 확인되지 않은 내용은 지어 넣지 않고 `TODO:`로 남겼다.
 *
 * 남은 자리는 `grep -rn "TODO:" src/features/landing index.html`로 한 번에 찾는다.
 *
 * 랜딩은 정적이다 (spec 3-3 결정 8). 이 파일이 유일한 콘텐츠 출처이며 API를 호출하지
 * 않는다. 나중에 관리자 편집 기능이 붙으면 **이 파일이 API 응답으로 바뀐다.**
 */

export const CLUB = {
  name: 'HACKER',
  fullName: '경희대학교 소프트웨어융합대학 동아리 해커',
  tagline:
    '모니터 앞에서의 뜨거운 몰입과 필드 위의 활기찬 에너지가 공존하는 공동체입니다.',
  about: [
    '모니터 앞에서의 뜨거운 몰입과 필드 위의 활기찬 에너지가 공존하는 공동체입니다.',
    '전문 지식을 탐구하는 스터디부터 함께 땀 흘리는 여러 소모임까지, 컴퓨터공학도의 건강한 라이프스타일을 설계합니다.',
    '1987년부터 이어져 온 경희대학교 소프트웨어융합대학 동아리입니다.',
  ],
  room: '전자정보대학 241-6호',
  instagram: 'https://www.instagram.com/khu_hacker/',
  /**
   * 경희대 국제캠퍼스 공식 출처(정보처·국제교육원 등) 여러 곳에서 일치 확인된 주소다.
   * **소프트웨어융합대학·전자정보대학은 서울캠퍼스가 아니라 국제캠퍼스다** —
   * 서울 동대문구 주소를 쓰면 틀린다. 241-6호는 공식 인스타그램에 적힌 동아리방이다.
   */
  address: {
    postalCode: '17104',
    road: '경기도 용인시 기흥구 덕영대로 1732',
    detail: '경희대학교 국제캠퍼스 전자정보대학 241-6호',
  },
} as const

export interface Stat {
  /** 단위를 숫자에 포함한다 — "39"보다 "39년"이 읽힌다 */
  value: string
  label: string
}

export const STATS: Stat[] = [
  // 1987년 창립부터 2026년까지. 창립 연도가 확인되었으므로 이 값만 확정이다.
  { value: '39년', label: '함께한 시간' },
  // TODO: 아래 세 칸은 실제 수치를 모른다. 채울 만한 것 — 현재 활동 인원,
  //       누적 부원 수, 진행한 스터디 수, 운영한 소모임 수 중 아는 것으로 고른다.
  //       숫자를 모르면 칸을 줄이는 편이 낫다. 빈 값을 그럴듯하게 채우지 말 것.
  { value: 'TODO명', label: '활동 중인 부원' },
  { value: 'TODO명', label: '거쳐 간 부원' },
  { value: 'TODO회', label: '진행한 스터디' },
]

export interface Activity {
  /**
   * `public/landing/` 아래 경로. 실물이 없으면 빈 문자열로 두고 화면이 자리표시자를
   * 그리게 한다. 파일을 넣는 방법은 `public/landing/README.md` 참고.
   */
  src: string
  /** 스크린리더가 읽는 설명. 사진을 넣을 때 함께 채운다 */
  alt: string
  title: string
  note: string
}

/**
 * 인스타그램에서 확인된 활동만 적었다. 세부 일정이나 성과는 확인되지 않아 넣지 않았다.
 * TODO: `public/landing/`에 실제 사진을 넣고 `src`와 `alt`를 채운다.
 */
export const ACTIVITIES: Activity[] = [
  { src: '', alt: '', title: '스터디', note: '방학마다 함께 공부합니다.' },
  { src: '', alt: '', title: '헬스클럽', note: '함께 운동하는 소모임입니다.' },
  {
    src: '',
    alt: '',
    title: '동아리 행사',
    note: '로고 공모전 같은 행사를 엽니다.',
  },
  {
    src: '',
    alt: '',
    title: '활동보고',
    note: '학기마다 활동을 정리해 공유합니다.',
  },
]

export interface Faq {
  question: string
  answer: string
}

export const FAQS: Faq[] = [
  {
    question: '누가 지원할 수 있나요?',
    answer: '경희대학교 학생이라면 누구나 지원할 수 있습니다.',
  },
  {
    question: '어떤 활동을 하나요?',
    answer:
      '방학마다 여는 스터디와 함께 운동하는 헬스클럽 같은 소모임을 운영하고, 학기마다 활동을 정리해 공유합니다.',
  },
  {
    question: '동아리방은 어디인가요?',
    answer: '전자정보대학 241-6호입니다.',
  },
  {
    question: '개발 경험이 없어도 되나요?',
    answer: 'TODO: 실제 답변 — 운영진이 확인해 채운다.',
  },
  {
    question: '모집은 언제 하나요?',
    answer: 'TODO: 실제 답변 — 모집 시기와 방법을 적는다.',
  },
]

export const SUPPORT = {
  description:
    '동아리 활동을 후원해 주실 분을 기다립니다. 후원 방법과 사용처는 메일로 안내드립니다.',
  /** TODO: 실제 문의 메일 주소로 교체 */
  email: 'TODO@example.com',
  /** 메일 제목. 받는 쪽에서 분류하기 쉽게 고정 문구를 둔다 */
  subject: '후원 문의',
} as const

/** 헤더와 푸터가 같이 쓰는 목록. 순서가 곧 화면 순서다. */
export const SECTIONS = [
  { id: 'about', label: '소개' },
  { id: 'activities', label: '활동' },
  { id: 'stats', label: '기록' },
  { id: 'faq', label: 'FAQ' },
  { id: 'support', label: '후원' },
] as const
