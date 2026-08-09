/**
 * 백엔드가 없는 동안 화면을 만들기 위한 더미 응답 계층.
 *
 * **서버가 붙으면 이 파일을 통째로 지우고 `auth.ts`의 `VITE_USE_FIXTURES` 분기도 함께 제거한다.**
 * 임시 파일이므로 여기에 화면 로직을 두지 않는다 — 응답만 만든다.
 *
 * 이 파일의 값은 프로덕션 번들에 실리면 안 된다. 플래그를 상수로 export하지 않는 이유는
 * `auth.ts` 상단 주석에 있다. 기준은 코드 모양이 아니라 측정이다 —
 * `npm run build` 후 `grep -c "member@khu.ac.kr" dist/assets/*.js`가 0이어야 한다.
 *
 * 반환 타입은 반드시 `src/api/types.ts`의 실제 계약 타입으로 선언한다.
 * 타입이 계약을 강제하는 것이 이 파일의 존재 이유다.
 */
import { ApiError } from './client'
import type { Notice } from './notices'
import type { Page, User } from './types'

/**
 * 어떤 사용자로 볼지 / 어떤 실패를 볼지 고르는 스위치. `.env.local`에서 바꾼다.
 *
 * 성공 응답만 나오면 오류 화면(#37)과 PENDING 리다이렉트(#38)를 만들 수 없으므로
 * 실패 시나리오를 같은 스위치에 둔다. 새 실패 경로가 필요하면 여기에 값을 추가한다.
 *
 * - `user`      ACTIVE / USER
 * - `admin`     ACTIVE / ADMIN
 * - `applying`  PENDING, 신청서 미제출 — 신청 폼을 봐야 하는 상태 (spec 3-1-6)
 * - `pending`   PENDING, 신청서 제출 완료 — 승인 대기 안내를 봐야 하는 상태
 * - `guest`     세션 없음. getMe가 401 UNAUTHENTICATED
 * - `blocked`   세션은 있으나 서버가 403 PENDING_APPROVAL로 막는 상태 (spec 3-1-6)
 *
 * 정지·도메인 위반 같은 OAuth 실패는 여기에 없다. 서버가 세션을 만들지 않고
 * `/login?error=...`로 되돌리므로(계약 §3-2-3), 그 화면은 주소로 직접 열어 만든다.
 * 시나리오로 두면 `guest`와 결과가 같아 아무것도 구분하지 못한다.
 */
type Scenario = 'user' | 'admin' | 'applying' | 'pending' | 'guest' | 'blocked'

const SCENARIO = (import.meta.env.VITE_FIXTURE_SCENARIO ?? 'user') as Scenario

const BASE = {
  id: 1,
  email: 'member@khu.ac.kr',
  studentNo: '2021123456',
  name: '홍길동',
  createdAt: '2026-03-02T09:00:00Z',
  appliedAt: '2026-03-02T09:10:00Z',
} as const

const USERS: Record<
  'user' | 'admin' | 'applying' | 'pending' | 'blocked',
  User
> = {
  user: {
    ...BASE,
    role: 'USER',
    status: 'ACTIVE',
    approvedAt: '2026-03-03T09:00:00Z',
  },
  admin: {
    ...BASE,
    id: 2,
    email: 'admin@khu.ac.kr',
    name: '김관리',
    role: 'ADMIN',
    status: 'ACTIVE',
    approvedAt: '2026-03-03T09:00:00Z',
  },
  // 구글 로그인만 마친 상태. 구글이 학번을 주지 않으므로 둘 다 비어 있다 (3-3 결정 13).
  applying: {
    ...BASE,
    id: 3,
    studentNo: null,
    role: 'USER',
    status: 'PENDING',
    appliedAt: null,
    approvedAt: null,
  },
  pending: {
    ...BASE,
    id: 3,
    role: 'USER',
    status: 'PENDING',
    approvedAt: null,
  },
  blocked: {
    ...BASE,
    id: 4,
    role: 'USER',
    status: 'PENDING',
    approvedAt: null,
  },
}

/**
 * `applying` 시나리오에서 제출한 신청서. `fixtureApplication()`이 채운다.
 *
 * 이게 없으면 신청 폼을 제출해도 `fixtureMe()`가 계속 `appliedAt: null`을 돌려줘
 * 화면이 폼에 머문다 — 픽스처만으로는 제출 후 화면을 만들 수 없다.
 *
 * 입력값을 그대로 들고 있어야 재제출(승인 전 수정) 화면도 만들 수 있다.
 * 하드코딩된 값을 돌려주면 무엇을 고쳤는지 화면에서 확인할 수 없다.
 */
let application: { studentNo: string; name: string } | null = null

export function fixtureMe(): Promise<User> {
  if (SCENARIO === 'guest') {
    return Promise.reject(
      new ApiError('UNAUTHENTICATED', 401, '로그인이 필요합니다.'),
    )
  }
  if (SCENARIO === 'blocked') {
    return Promise.reject(
      new ApiError('PENDING_APPROVAL', 403, '가입 승인 대기 중입니다.'),
    )
  }
  // 신청서를 냈으면 그 값으로 덮는다. `pending`도 대상이다 — 승인 전 재제출이
  // 계약에 있으므로(T-51) 그 화면도 픽스처로 만들 수 있어야 한다.
  if (application && (SCENARIO === 'applying' || SCENARIO === 'pending')) {
    return Promise.resolve({
      ...USERS[SCENARIO],
      studentNo: application.studentNo,
      name: application.name,
      appliedAt: '2026-03-02T10:00:00Z',
    })
  }
  return Promise.resolve(USERS[SCENARIO])
}

/**
 * 신청서 제출은 본문을 반환하지 않는다. 제출 후 화면은 `fixtureMe()`로 다시 그린다.
 *
 * `applying` 시나리오에서는 제출 여부를 기억해, 이어지는 `fixtureMe()`가 신청 완료
 * 상태를 돌려준다. 그래야 폼 → 대기 안내 전환을 픽스처만으로 확인할 수 있다.
 *
 * 로그인은 구글 OAuth 리다이렉트라 픽스처로 흉내 낼 수 없다 (3-3 결정 13).
 * 어떤 사용자로 볼지는 `VITE_FIXTURE_SCENARIO`가 정한다.
 */
export function fixtureApplication(body: {
  studentNo: string
  name: string
}): Promise<void> {
  if (SCENARIO === 'guest') {
    return Promise.reject(
      new ApiError('UNAUTHENTICATED', 401, '로그인이 필요합니다.'),
    )
  }
  // 신청 API는 PENDING 전용이다 (계약 §3-2-3, T-50). 픽스처가 이걸 허용하면
  // 승인 후 학번을 바꾸는 회귀가 화면 개발 중에 드러나지 않는다.
  if (SCENARIO === 'user' || SCENARIO === 'admin') {
    return Promise.reject(
      new ApiError('FORBIDDEN', 403, '승인된 계정은 신청서를 낼 수 없습니다.'),
    )
  }
  // 공백 검증은 서버 계약이다 (§3-2-3, T-52). 픽스처가 통과시키면 오류 UI 없이도
  // 폼이 정상처럼 보이고, 빈 신청서가 승인 대상이 되는 경로를 화면에서 못 잡는다.
  const studentNo = body.studentNo.trim()
  const name = body.name.trim()
  if (studentNo === '' || name === '') {
    return Promise.reject(
      new ApiError('VALIDATION_ERROR', 400, '학번과 이름을 입력해주세요.'),
    )
  }
  application = { studentNo, name }
  return Promise.resolve()
}

// ── 공지 ────────────────────────────────────────────────────────────────────

const FIXTURE_PAGE_SIZE = 10
const DAY_MS = 24 * 60 * 60 * 1000

/**
 * 픽스처 날짜는 **모듈이 로드된 시각 기준 상대 오프셋**이다.
 *
 * 절대 날짜를 박으면 시간이 지나면서 "새글" 표시가 영영 사라져 화면 확인이 안 된다.
 * 기준 시각을 한 번만 찍어 모든 공지가 같은 순간을 기준으로 삼으므로, 몇 번째 공지가
 * 며칠 전인지는 항상 같다 — 정렬 순서와 새글 대상이 실행마다 바뀌지 않는다.
 */
const LOADED_AT = Date.now()

function daysAgo(days: number): string {
  return new Date(LOADED_AT - days * DAY_MS).toISOString()
}

/**
 * 서버가 `is_pinned DESC, created_at DESC`로 정렬해 내려준다 (spec §2-1-6).
 * **픽스처도 그 순서로 이미 정렬된 상태로 둔다.** 화면에서 다시 정렬하면 서버가 붙었을 때
 * 클라이언트 정렬이 남아 서버 순서를 덮어쓴다.
 */
const NOTICES: Notice[] = [
  {
    id: 101,
    title: '2026학년도 1학기 정기 세미나 일정 안내 및 참가 신청 방법 공지',
    content:
      '1학기 정기 세미나 일정을 안내합니다.\n\n매주 수요일 저녁 7시, 전자정보대학관 305호에서 진행합니다.\n주제는 격주로 바뀌며 첫 주는 리버싱 기초입니다.\n\n참가 신청은 동아리방 화이트보드에 이름을 적어주세요.',
    isPinned: true,
    // 1일 전 — 고정이면서 새글이다. 핀(강)과 NEW(약)의 위계가 한 행에서 보인다.
    createdAt: daysAgo(1),
    updatedAt: daysAgo(1),
  },
  {
    id: 102,
    title: '동아리방 출입 규칙 변경',
    content:
      '이번 학기부터 동아리방 출입 카드 등록이 필요합니다.\n\n미등록 상태로는 야간 출입이 제한됩니다.\n등록은 운영진에게 문의해주세요.',
    isPinned: true,
    createdAt: daysAgo(12),
    updatedAt: daysAgo(10),
  },
]

const TOPICS = [
  '스터디 모집 안내',
  '해커톤 참가팀 모집',
  '정기 총회 결과 공유',
  'CTF 대회 후기 공유 및 다음 대회 참가 안내드립니다',
  '서버 점검 안내',
  '신입 부원 환영회',
  '외부 강연 신청',
  '동아리 티셔츠 수요 조사',
  '기자재 대여 규정 안내 — 노트북과 라즈베리파이 반납 기한을 지켜주세요',
  '방학 중 운영 일정',
  '스터디 결과 발표회',
  '회비 납부 안내',
  '졸업생 멘토링 신청',
  '보안 취약점 제보 채널 안내',
  '학술제 부스 운영 인원 모집',
  '동아리방 청소 당번',
  '리눅스 기초 스터디 자료 공유',
  '워크숍 장소 투표',
  '프로젝트 팀 빌딩 안내',
  '연말 정산 보고',
  '신규 서버 도입 안내',
  '홈페이지 개편 진행 상황',
  '외부 협력 동아리 교류전',
]

for (const [index, topic] of TOPICS.entries()) {
  // 0·2·5·8… 일 전. 앞의 둘이 3일 이내라 새글 표시가 실제로 보인다.
  // 간격이 있어야 날짜가 서로 다르고 정렬이 눈에 보인다.
  const days = index === 0 ? 0 : index * 3 - 1
  NOTICES.push({
    id: 100 - index,
    title: topic,
    content: `${topic}에 대한 상세 내용입니다.\n\n자세한 사항은 운영진에게 문의해주세요.`,
    isPinned: false,
    createdAt: daysAgo(days),
    updatedAt: daysAgo(days),
  })
}

export function fixtureNotices(
  page = 0,
  size = FIXTURE_PAGE_SIZE,
): Promise<Page<Notice>> {
  const start = page * size
  return Promise.resolve({
    content: NOTICES.slice(start, start + size),
    page: {
      size,
      number: page,
      totalElements: NOTICES.length,
      totalPages: Math.ceil(NOTICES.length / size),
    },
  })
}

export function fixtureNotice(id: number): Promise<Notice> {
  const found = NOTICES.find((notice) => notice.id === id)
  if (!found) {
    return Promise.reject(
      new ApiError('NOT_FOUND', 404, '공지를 찾을 수 없습니다.'),
    )
  }
  return Promise.resolve(found)
}

/**
 * 고정 토글. 실제 서버처럼 상태를 바꾸고 목록을 다시 정렬한다 —
 * 고정하면 위로 올라가는 것이 화면에서 보여야 한다.
 */
export function fixtureTogglePin(id: number): Promise<Notice> {
  const found = NOTICES.find((notice) => notice.id === id)
  if (!found) {
    return Promise.reject(
      new ApiError('NOT_FOUND', 404, '공지를 찾을 수 없습니다.'),
    )
  }
  found.isPinned = !found.isPinned
  NOTICES.sort((a, b) => {
    if (a.isPinned !== b.isPinned) return a.isPinned ? -1 : 1
    return b.createdAt.localeCompare(a.createdAt)
  })
  return Promise.resolve(found)
}
