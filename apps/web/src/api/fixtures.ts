/**
 * 백엔드가 없는 동안 화면을 만들기 위한 더미 응답 계층.
 *
 * **서버가 붙으면 이 파일을 통째로 지운다.** 같이 지울 곳은 파일 이름을 여기 적지 않고
 * 찾는다 — 픽스처를 쓰는 파일이 늘 때마다 목록이 낡기 때문이다. 실제로 그랬다:
 * `auth.ts`만 적혀 있는 동안 `notices.ts`에 분기 여섯 개가 생겼고, 적힌 대로 따르면
 * 빌드가 깨지는 상태였다.
 *
 * ```sh
 * # apps/web에서 실행한다 (저장소 루트가 아니다)
 * rg -il "fixture" . --hidden
 * ```
 *
 * `fixture`라는 낱말 하나만 대소문자 없이 찾는다. `VITE_USE_FIXTURES`·
 * `VITE_FIXTURE_SCENARIO`·이 파일·정적 import·동적 `import('./fixtures')`·문서 안내가
 * 전부 그 낱말을 지나가므로, 패턴을 늘리지 않아도 새 참조가 걸린다. `--hidden`이 없으면
 * `.env.example`이 빠진다. gitignore된 각자의 `.env.local`은 검색에 안 잡히니 따로 지운다.
 *
 * 임시 파일이므로 여기에 화면 로직을 두지 않는다 — 응답만 만든다.
 *
 * 이 파일의 값은 프로덕션 번들에 실리면 안 된다. 플래그를 상수로 export하지 않는 이유는
 * `auth.ts` 상단 주석에 있다. 기준은 코드 모양이 아니라 측정이다 —
 * `npm run build` 후 `grep -c "member@khu.ac.kr" dist/assets/*.js`가 0이어야 한다.
 *
 * 반환 타입은 반드시 `src/api/types.ts`의 실제 계약 타입으로 선언한다.
 * 타입이 계약을 강제하는 것이 이 파일의 존재 이유다.
 */

import { DEPARTMENTS } from '@/features/auth/departments'
import type {
  AdminUserQuery,
  ApproveResult,
  ContentSummary,
  RejectResult,
} from './adminUsers'
import { ApiError } from './client'
import type {
  DownloadUrl,
  FileRef,
  NoteDetail,
  NoteFile,
  NoteFilterOptions,
  NoteMetadata,
  NoteQuery,
  NoteSummary,
  Upload,
  UploadCandidate,
  UploadedFile,
  Uploader,
} from './notes'
import type { Notice } from './notices'
import type { Photo, PhotoRegisterResult, PhotoUpload } from './photos'
import type { PostDetail, PostSummary } from './posts'
import type { Page, Role, User } from './types'

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
 * - `guest`     세션 없음. getMe가 `null` (서버는 204, #190)
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
  department: '컴퓨터공학과',
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
  // 구글 로그인만 마친 상태. 구글이 학번도 학과도 주지 않으므로 비어 있다 (3-3 결정 13).
  applying: {
    ...BASE,
    id: 3,
    studentNo: null,
    department: null,
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
let application: {
  studentNo: string
  department: string
} | null = null

export function fixtureMe(): Promise<User | null> {
  // 서버가 세션이 없으면 204로 답한다 (#190). 오류가 아니라 "비로그인"이라는 답이다.
  if (SCENARIO === 'guest') return Promise.resolve(null)
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
      department: application.department,
      // 이름은 덮지 않는다 — 신청서가 받는 값이 아니라 구글 계정의 값이다 (#224).
      appliedAt: '2026-03-02T10:00:00Z',
    })
  }
  /*
   * **관리자는 명부의 본인 레코드를 본다.**
   *
   * 회원 관리 화면에서 본인을 정지하면 그 다음 `getMe()`가 `SUSPENDED`를 돌려줘야 한다 —
   * 계약이 "이미 로그인된 세션도 다음 요청에서 차단"(2-2 §2-2-3 MUST)이기 때문이다.
   * 여기서 `USERS.admin`(불변 복사본)을 돌려주면 본인을 정지해도 세션이 계속 살아 있어,
   * 픽스처가 계약보다 무른 상태가 된다.
   */
  if (SCENARIO === 'admin') return Promise.resolve(self())
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
  department: string
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
  if (studentNo === '') {
    return Promise.reject(
      new ApiError('VALIDATION_ERROR', 400, '학번을 입력해주세요.'),
    )
  }
  /*
   * 학과가 목록 안의 값인지도 서버 계약이다 (§3-2-3 MUST, T-181). 픽스처가 통과시키면
   * 화면이 목록 밖 값을 보내는 회귀를 못 잡는다 — 지금 이 이슈(#165)가 정확히 그 종류였다.
   */
  if (!(DEPARTMENTS as readonly string[]).includes(body.department)) {
    return Promise.reject(
      new ApiError('VALIDATION_ERROR', 400, '학과를 선택해 주세요.'),
    )
  }
  /*
   * 학번 중복 거부도 서버 계약이다 (§3-1-4, T-07). 픽스처가 통과시키면 한 학번으로 여러
   * 계정이 만들어지는 경로를 화면에서 못 잡고, 409를 보여주는 UI도 검증할 수 없다.
   * 명부(`MEMBERS`)에 이미 쓰이고 있는 학번인지 본다 — 관리자 화면이 보는 그 명단이다.
   */
  if (MEMBERS.some((user) => user.studentNo === studentNo)) {
    return Promise.reject(
      new ApiError(
        'DUPLICATE_STUDENT_NO',
        409,
        '이미 등록된 학번입니다. 본인 학번이 맞다면 운영진에게 문의해 주세요.',
      ),
    )
  }

  application = { studentNo, department: body.department }
  return Promise.resolve()
}

// ── 공지 ────────────────────────────────────────────────────────────────────

/**
 * 기본 페이지 크기. **계약의 기본값과 같아야 한다** (spec §3-2-8 — `size`(기본 20)).
 *
 * 여기가 서버와 다르면 `size`를 명시하지 않는 호출에서 픽스처와 서버가 다른 건수를
 * 준다. 공지 목록은 지금 10을 명시하지만 그건 화면의 선택이지 이 기본값과 무관하다.
 */
const FIXTURE_PAGE_SIZE = 20
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
    authorId: 2,
    authorName: '관리자',
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
    authorId: 2,
    authorName: '관리자',
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
    authorId: 2,
    authorName: '관리자',
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

const TITLE_MAX = 200

/**
 * 다음에 발급할 id. **배열에서 계산하지 않는다.**
 *
 * `Math.max(...NOTICES.map(...))`은 공지를 전부 지우면 `Math.max(...[])`가 `-Infinity`라
 * `/notices/-Infinity` 같은 주소와 중복 key를 만든다. 지운 id를 재사용하지 않는 점도
 * 실제 auto increment와 같다 — 배열을 고치는 구조를 골랐으면 빈 상태도 정상 상태다.
 */
let nextId = Math.max(...NOTICES.map((notice) => notice.id)) + 1

function requireAdmin(): ApiError | null {
  if (SCENARIO === 'guest') {
    return new ApiError('UNAUTHENTICATED', 401, '로그인이 필요합니다.')
  }
  // 계약 §3-2-5 — 공지 쓰기는 ADMIN 전용이다 (T-04).
  if (SCENARIO !== 'admin') {
    return new ApiError('FORBIDDEN', 403, '권한이 없습니다.')
  }
  /*
   * **정지된 관리자는 더 이상 관리자가 아니다** (2-2 §2-2-3 MUST — 이미 로그인된 세션도
   * 다음 요청에서 차단된다). 본인이 자기를 정지한 뒤에도 관리 API가 계속 통하면
   * 픽스처가 계약보다 무른 것이고, 그 상태를 화면에서 확인할 수 없다.
   */
  if (self().status !== 'ACTIVE') {
    return new ApiError('SUSPENDED', 403, '정지된 계정입니다.')
  }
  return null
}

function validate(title: string, content: string): ApiError | null {
  // 스키마가 NOT NULL이므로 빈 값은 서버가 거부한다 (계약 §3-2-2 `notices`).
  if (title.trim() === '' || content.trim() === '') {
    return new ApiError('VALIDATION_ERROR', 400, '제목과 내용을 입력해주세요.')
  }
  // varchar(200). 넘치면 서버가 자르는 게 아니라 거부한다.
  if (title.length > TITLE_MAX) {
    return new ApiError(
      'VALIDATION_ERROR',
      400,
      `제목은 ${TITLE_MAX}자를 넘을 수 없습니다.`,
    )
  }
  return null
}

/** 서버 정렬(`is_pinned DESC, created_at DESC`)을 픽스처에서도 유일하게 구현하는 자리. */
function sortNotices() {
  NOTICES.sort((a, b) => {
    if (a.isPinned !== b.isPinned) return a.isPinned ? -1 : 1
    return b.createdAt.localeCompare(a.createdAt)
  })
}

/**
 * 고정 토글. 실제 서버처럼 상태를 바꾸고 목록을 다시 정렬한다 —
 * 고정하면 위로 올라가는 것이 화면에서 보여야 한다.
 */
export function fixtureTogglePin(id: number): Promise<Notice> {
  // 고정도 ADMIN 전용이다 (spec §3-1-3). 쓰기 넷 중 여기만 빠져 있으면 픽스처가
  // 서버 계약과 어긋나고, "픽스처가 권한을 거부한다"는 말이 반만 참이 된다.
  const denied = requireAdmin()
  if (denied) return Promise.reject(denied)

  const found = NOTICES.find((notice) => notice.id === id)
  if (!found) {
    return Promise.reject(
      new ApiError('NOT_FOUND', 404, '공지를 찾을 수 없습니다.'),
    )
  }
  found.isPinned = !found.isPinned
  sortNotices()
  return Promise.resolve(found)
}

/**
 * 쓰기 계열(등록·수정·삭제·고정)은 **위 `NOTICES` 배열을 그대로 고친다.** 넷 모두
 * `requireAdmin()`을 탄다 — 하나라도 빠지면 계약이 비대칭이 된다.
 *
 * 이 배열은 모듈 수준이라 새로고침 전까지 값이 유지된다. 그래야 목록 → 작성 → 상세 →
 * 수정 왕복이 말이 된다 — 저장했는데 목록에 없으면 화면을 확인할 수가 없다. 새로고침하면
 * 초기값으로 돌아가는데, 그게 오히려 낫다. 브라우저 저장소에 넣으면 지우는 절차가 따로
 * 생기고, "서버가 붙으면 이 파일을 통째로 지운다"는 원칙이 파일 밖으로 새어나간다.
 *
 * 서버 계약을 여기서도 지킨다 — 권한(ADMIN)과 필수값·길이 검사를 픽스처가 통과시키면
 * 오류 UI 없이도 폼이 멀쩡해 보이고, 그 회귀가 서버 붙는 날까지 안 드러난다.
 */
export function fixtureCreateNotice(body: {
  title: string
  content: string
}): Promise<Notice> {
  const denied = requireAdmin() ?? validate(body.title, body.content)
  if (denied) return Promise.reject(denied)

  const now = new Date().toISOString()
  const created: Notice = {
    id: nextId++,
    title: body.title.trim(),
    content: body.content.trim(),
    // 등록 직후에는 고정되지 않는다. 고정은 별도 토글이다.
    isPinned: false,
    authorId: 2,
    authorName: '관리자',
    createdAt: now,
    updatedAt: now,
  }
  NOTICES.push(created)
  sortNotices()
  return Promise.resolve(created)
}

export function fixtureUpdateNotice(
  id: number,
  body: { title?: string; content?: string },
): Promise<Notice> {
  const denied = requireAdmin()
  if (denied) return Promise.reject(denied)

  const found = NOTICES.find((notice) => notice.id === id)
  if (!found) {
    return Promise.reject(
      new ApiError('NOT_FOUND', 404, '공지를 찾을 수 없습니다.'),
    )
  }
  // PATCH라 준 필드만 바뀐다. 안 준 필드는 기존 값이 그대로 검사 대상이다.
  const title = body.title ?? found.title
  const content = body.content ?? found.content
  const invalid = validate(title, content)
  if (invalid) return Promise.reject(invalid)

  found.title = title.trim()
  found.content = content.trim()
  found.updatedAt = new Date().toISOString()
  return Promise.resolve(found)
}

export function fixtureRemoveNotice(id: number): Promise<void> {
  const denied = requireAdmin()
  if (denied) return Promise.reject(denied)

  const index = NOTICES.findIndex((notice) => notice.id === id)
  if (index === -1) {
    return Promise.reject(
      new ApiError('NOT_FOUND', 404, '공지를 찾을 수 없습니다.'),
    )
  }
  NOTICES.splice(index, 1)
  return Promise.resolve()
}

// ── 회원 관리 ────────────────────────────────────────────────────────────────

/**
 * 회원 명부. 위 `USERS`(`/auth/me`용 단일 사용자)와 다른 목적이라 따로 둔다 —
 * 그쪽은 "지금 로그인한 사람"이고 이쪽은 "관리자가 보는 명단"이다.
 *
 * 신청 전(`appliedAt: null`) 계정을 일부러 섞어 뒀다. 승인 대상은 신청서를 낸 계정으로
 * 한정되므로(계약 §3-2-6 MUST) **그 계정이 화면에서 선택되지 않는 것**이 이 화면의 핵심
 * 규칙인데, 명단에 그런 계정이 없으면 규칙이 지켜지는지 화면에서 확인할 수가 없다.
 *
 * 활성 관리자도 둘 이상 둔다. 한 명뿐이면 마지막 관리자 자기 정지 차단(§2-2-7)만 보이고
 * 정상적으로 정지되는 경로를 볼 수 없다.
 */
const MEMBERS: User[] = []

/** 가입부터 신청까지 걸린 일수. 순서를 섞으려고 일부러 들쭉날쭉하게 둔다. */
const APPLY_DELAY = [1, 11, 3, 14, 6, 9]

const MEMBER_NAMES = [
  '강도현',
  '김서연',
  '남우진',
  '문지호',
  '박하늘',
  '배준영',
  '서민재',
  '송예린',
  '신동하',
  '오세림',
  '유가온',
  '윤태경',
  '이도윤',
  '임채원',
  '장서우',
  '전민석',
  '정예나',
  '조현우',
  '차수빈',
  '최은서',
  '표지훈',
  '한소율',
  '홍시우',
  '황재민',
]

for (const [index, name] of MEMBER_NAMES.entries()) {
  /*
   * 앞의 여섯은 PENDING이다. 그중 둘(index 4·5)은 **신청서를 내지 않았다** —
   * 구글 로그인만 마친 상태라 학번도 없다 (3-3 결정 13).
   */
  const pending = index < 6
  const applied = pending ? index < 4 : true
  const admin = index === 6 || index === 7
  const suspended = index === 8

  MEMBERS.push({
    id: 1000 + index,
    email: `member${index + 1}@khu.ac.kr`,
    studentNo: applied
      ? `20${21 + (index % 5)}${String(100000 + index)}`
      : null,
    name,
    /*
     * 학번과 같이 신청서에서 채워진다 — 안 낸 계정은 비어 있다.
     *
     * **여러 학과가 섞이게 둔다.** 전부 같은 학과면 회원 목록이 학과를 아예 안 그려도
     * 화면이 그럴듯해 보인다. 목록 앞쪽(소프트웨어융합대학 셋)에서 돌려 쓴다 — 실제
     * 부원 분포도 그쪽에 몰려 있다.
     */
    department: applied ? DEPARTMENTS[index % 3] : null,
    role: admin ? 'ADMIN' : 'USER',
    status: pending ? 'PENDING' : suspended ? 'SUSPENDED' : 'ACTIVE',
    // 계정 생성(첫 구글 로그인)은 신청보다 앞선다. 둘을 며칠 벌려 둬야 화면이 어느
    // 날짜를 쓰는지 눈으로 구분된다 (2-2 §2-2-1 MUST — 신청일은 appliedAt이다).
    /*
     * **두 날짜의 순서가 서로 다르도록 만든다.**
     *
     * 가입(첫 구글 로그인)부터 신청서 제출까지 걸린 기간은 사람마다 다르다. 이 간격을
     * 일정하게 두면 `createdAt` 순서와 `appliedAt` 순서가 같아져, 정렬이 잘못된 필드를
     * 봐도 결과가 같아 회귀가 드러나지 않는다 — 실제로 그런 상태였다.
     */
    createdAt: daysAgo(60 - index),
    appliedAt: applied ? daysAgo(60 - index - APPLY_DELAY[index % 6]) : null,
    approvedAt: pending ? null : daysAgo(40 - index),
  })
}

/**
 * 로그인한 관리자 본인. `admin` 시나리오의 `USERS.admin`과 **같은 사람이고 같은 객체다.**
 * 명부에서 상태가 바뀌면 `fixtureMe()`도 그 값을 본다.
 */
const SELF_ID = USERS.admin.id
MEMBERS.unshift({ ...USERS.admin })

/** 명부에 있는 본인. `MEMBERS`를 갈아끼우지 않으므로 항상 찾을 수 있다. */
function self(): User {
  const found = MEMBERS.find((user) => user.id === SELF_ID)
  if (!found) throw new Error('명부에 본인이 없다')
  return found
}

function matchesQuery(user: User, query: AdminUserQuery): boolean {
  if (query.status && user.status !== query.status) return false
  if (query.role && user.role !== query.role) return false
  // 신청 여부. 서버와 같은 규칙이어야 픽스처로 만든 화면이 실제와 같게 움직인다 (§3-2-6).
  if (
    query.applied !== undefined &&
    (user.appliedAt !== null) !== query.applied
  ) {
    return false
  }
  const keyword = query.q?.trim().toLowerCase()
  if (!keyword) return true
  // 검색은 이름·학번·이메일 통합이다 (2-2 §2-2-1).
  return [user.name, user.studentNo ?? '', user.email].some((field) =>
    field.toLowerCase().includes(keyword),
  )
}

/**
 * 정렬. **기본은 신청일 최신순**이고, "가입 신청일"은 `appliedAt`이다 (2-2 §2-2-1 MUST) —
 * `createdAt`이 아니다. 신청하지 않은 계정은 `appliedAt`이 없어 항상 뒤로 보낸다.
 */
function compare(a: User, b: User, sort: string | undefined): number {
  switch (sort) {
    case 'name':
      return a.name.localeCompare(b.name, 'ko')
    case 'studentNo':
      return (a.studentNo ?? '').localeCompare(b.studentNo ?? '')
    default:
      if (a.appliedAt === b.appliedAt) return 0
      if (a.appliedAt === null) return 1
      if (b.appliedAt === null) return -1
      return b.appliedAt.localeCompare(a.appliedAt)
  }
}

export function fixtureAdminUsers(
  query: AdminUserQuery = {},
): Promise<Page<User>> {
  const denied = requireAdmin()
  if (denied) return Promise.reject(denied)

  const matched = MEMBERS.filter((user) => matchesQuery(user, query)).sort(
    (a, b) => compare(a, b, query.sort),
  )
  const size = query.size ?? FIXTURE_PAGE_SIZE
  const page = query.page ?? 0
  const start = page * size
  return Promise.resolve({
    content: matched.slice(start, start + size),
    page: {
      size,
      number: page,
      totalElements: matched.length,
      totalPages: Math.ceil(matched.length / size),
    },
  })
}

/**
 * 일괄 승인.
 *
 * **신청서를 내지 않은 계정은 실패로 집계하고 상태를 바꾸지 않는다** (계약 §3-2-6 MUST).
 * 픽스처가 이걸 통과시키면 `student_no`가 빈 `ACTIVE`가 만들어지는 경로를 화면에서 못 잡고,
 * 실패 건수를 안내하는 UI도 검증할 수 없다 — 늘 전부 성공이니까.
 */
export function fixtureApproveUsers(userIds: number[]): Promise<ApproveResult> {
  const denied = requireAdmin()
  if (denied) return Promise.reject(denied)

  const result: ApproveResult = { approved: [], failed: [] }
  // 서버와 같이 중복을 먼저 지운다. 그대로 두면 [id, id]가 성공 1건 + NOT_PENDING 1건이
  // 되어, 실제로는 나올 수 없는 부분 실패를 픽스처 화면에서만 보게 된다 (계약 §3-2-6).
  for (const id of [...new Set(userIds)]) {
    const found = MEMBERS.find((user) => user.id === id)
    // 사유를 서버와 같이 가른다. 하나로 뭉개면 화면이 거짓 원인을 안내한다.
    if (!found) {
      result.failed.push({ userId: id, reason: 'NOT_FOUND' })
      continue
    }
    if (found.status !== 'PENDING') {
      result.failed.push({ userId: id, reason: 'NOT_PENDING' })
      continue
    }
    if (found.appliedAt === null) {
      result.failed.push({ userId: id, reason: 'NOT_APPLIED' })
      continue
    }
    found.status = 'ACTIVE'
    found.approvedAt = new Date().toISOString()
    result.approved.push(id)
  }
  return Promise.resolve(result)
}

/**
 * 일괄 거부 (2-2 §2-2-2). `PENDING` 계정을 지운다.
 *
 * **`PENDING`이 아니면 거부하지 않는다** (§3-2-6). 이 경로로 이용 중인 회원을 지울 수
 * 없다 — 그것은 "제거"이고 세션 폐기·정지 선행 같은 규칙이 따로 붙는다 (§2-2-4).
 * 픽스처가 통과시키면 화면이 그 구분을 잃는다.
 */
export function fixtureRejectUsers(userIds: number[]): Promise<RejectResult> {
  const denied = requireAdmin()
  if (denied) return Promise.reject(denied)

  const result: RejectResult = { rejected: [], failed: [] }
  // 승인과 같이 중복을 먼저 지운다 — 그대로 두면 실제로는 나올 수 없는 부분 실패가 생긴다.
  for (const id of [...new Set(userIds)]) {
    const index = MEMBERS.findIndex((user) => user.id === id)
    if (index < 0) {
      result.failed.push({ userId: id, reason: 'NOT_FOUND' })
      continue
    }
    if (MEMBERS[index].status !== 'PENDING') {
      result.failed.push({ userId: id, reason: 'NOT_PENDING' })
      continue
    }
    MEMBERS.splice(index, 1)
    result.rejected.push(id)
  }
  return Promise.resolve(result)
}

/**
 * 제거하면 남을 콘텐츠 건수 (2-2 §2-2-4 MUST).
 *
 * **세 값을 항상 담는다.** `0`을 빼면 화면이 "없음"과 "모름"을 가르지 못한다.
 * 값은 id로 갈라 둔다 — 전부 같은 수를 주면 화면이 엉뚱한 칸을 그려도 티가 안 난다.
 */
export function fixtureContentSummary(id: number): Promise<ContentSummary> {
  const denied = requireAdmin()
  if (denied) return Promise.reject(denied)

  const found = MEMBERS.find((user) => user.id === id)
  if (!found) {
    return Promise.reject(
      new ApiError('NOT_FOUND', 404, '회원을 찾을 수 없습니다.'),
    )
  }
  return Promise.resolve({
    notes: id % 5,
    notices: id % 3,
    photos: id % 7,
    posts: id % 4,
  })
}

/**
 * 회원 제거 (2-2 §2-2-4). **되돌릴 수 없다.**
 *
 * 서버는 정지를 먼저 확정하고 세션까지 폐기하지만, 픽스처가 흉내 낼 수 있는 것은
 * 명부에서 사라지는 것까지다. 마지막 활성 관리자 보호는 서버 규칙이므로 함께 흉내 낸다 —
 * 통과시키면 그 실패 화면을 만들 수 없다 (§2-2-7).
 */
export function fixtureRemoveUser(id: number): Promise<void> {
  const denied = requireAdmin()
  if (denied) return Promise.reject(denied)

  const index = MEMBERS.findIndex((user) => user.id === id)
  if (index < 0) {
    return Promise.reject(
      new ApiError('NOT_FOUND', 404, '회원을 찾을 수 없습니다.'),
    )
  }

  const remaining = MEMBERS.filter(
    (user) =>
      user.id !== id && user.role === 'ADMIN' && user.status === 'ACTIVE',
  )
  if (MEMBERS[index].role === 'ADMIN' && remaining.length === 0) {
    return Promise.reject(
      new ApiError(
        'FORBIDDEN',
        403,
        '마지막 활성 관리자는 제거할 수 없습니다.',
      ),
    )
  }

  MEMBERS.splice(index, 1)
  return Promise.resolve()
}

/**
 * 권한 부여·회수 (2-2 §2-2-5). **이 조작 뒤에 활성 관리자가 0명이 되면 막는다** (§2-2-7 MUST).
 *
 * 자기 자신인지만 보지 않는다 — 관리자가 둘일 때 서로의 권한을 동시에 회수하면 각자
 * "남을 회수하는 것"이라 자기 검사에 걸리지 않는다. 조작 뒤에 남는지를 센다.
 *
 * 픽스처가 서버처럼 거부해야 그 실패 화면을 만들 수 있다.
 */
export function fixtureUpdateUserRole(id: number, role: Role): Promise<User> {
  const denied = requireAdmin()
  if (denied) return Promise.reject(denied)

  const found = MEMBERS.find((user) => user.id === id)
  if (!found) {
    return Promise.reject(
      new ApiError('NOT_FOUND', 404, '회원을 찾을 수 없습니다.'),
    )
  }

  // 승인 대기 계정은 이 경로의 대상이 아니다 (§2-2-5) — 승인일시 없는 ADMIN이 생긴다.
  if (found.status === 'PENDING') {
    return Promise.reject(
      new ApiError(
        'FORBIDDEN',
        403,
        '승인 대기 중인 계정은 권한을 바꿀 수 없습니다.',
      ),
    )
  }

  const remaining = MEMBERS.filter(
    (user) =>
      user.status === 'ACTIVE' &&
      (user.id === id ? role === 'ADMIN' : user.role === 'ADMIN'),
  )
  if (remaining.length === 0) {
    return Promise.reject(
      // 자기 대상과 남 대상을 가른다 (§2-2-7 — 화면이 서버 문구를 그대로 보여준다).
      new ApiError(
        'FORBIDDEN',
        403,
        id === SELF_ID
          ? '마지막 활성 관리자는 자기 권한을 회수할 수 없습니다.'
          : '활성 관리자가 없어집니다. 다른 관리자를 먼저 지정해 주세요.',
      ),
    )
  }

  found.role = role
  return Promise.resolve(found)
}

/**
 * 상태 전환. **마지막 활성 관리자가 자기를 정지시키는 것을 막는다** (2-2 §2-2-7 MUST).
 *
 * 화면은 활성 관리자가 몇 명인지 모르므로 이 판단을 하지 않는다. 픽스처가 서버처럼
 * 거부해야 그 실패 화면을 만들 수 있다.
 */
export function fixtureUpdateUserStatus(
  id: number,
  status: 'ACTIVE' | 'SUSPENDED',
): Promise<User> {
  const denied = requireAdmin()
  if (denied) return Promise.reject(denied)

  const found = MEMBERS.find((user) => user.id === id)
  if (!found) {
    return Promise.reject(
      new ApiError('NOT_FOUND', 404, '회원을 찾을 수 없습니다.'),
    )
  }

  const activeAdmins = MEMBERS.filter(
    (user) => user.role === 'ADMIN' && user.status === 'ACTIVE',
  )
  const isSelf = id === SELF_ID
  if (
    status === 'SUSPENDED' &&
    isSelf &&
    activeAdmins.length === 1 &&
    activeAdmins[0].id === id
  ) {
    return Promise.reject(
      new ApiError(
        'FORBIDDEN',
        403,
        '마지막 활성 관리자는 자기 자신을 정지할 수 없습니다.',
      ),
    )
  }

  found.status = status
  return Promise.resolve(found)
}

// ── 자료·즐겨찾기 ────────────────────────────────────────────────────────────

/**
 * 자료 픽스처.
 *
 * **업로더를 섞어 둔다** — 본인 것(`SELF`), 남의 것, 탈퇴한 회원의 것. 그래야 "본인
 * 자료에만 수정·삭제 진입점이 보인다"(#59 완료 조건)를 화면에서 확인할 수 있다.
 * 하나로 통일하면 그 분기가 늘 참이거나 늘 거짓이라 아무것도 못 잡는다.
 *
 * **과목·교수·연도도 흩어 둔다.** 필터가 실제로 걸러내는지 보려면 걸러질 것이 있어야 한다.
 */
const NOTE_SUBJECTS = [
  { subject: '운영체제', professor: '김교수' },
  { subject: '컴퓨터네트워크', professor: '이교수' },
  { subject: '자료구조', professor: '박교수' },
  { subject: '알고리즘', professor: null },
  { subject: '데이터베이스', professor: '최교수' },
]

/** 로그인한 나. `GET /auth/me`가 주는 계정과 같아야 소유 판단이 화면과 맞는다. */
function viewer(): User {
  return SCENARIO === 'admin' ? USERS.admin : USERS.user
}

/**
 * 목록·상세가 함께 쓰는 자료 하나. `files`까지 들고 있고 목록은 개수만 꺼내 쓴다 —
 * 두 벌로 두면 수정 화면에서 고친 값이 목록에 반영되지 않는다.
 */
type FixtureNote = Omit<NoteDetail, 'bookmarked'>

/** 담아둔 자료 id. **응답의 `bookmarked`는 여기서 만든다** (계약 §3-2-4). */
const bookmarked = new Set<number>()

const NOTES: FixtureNote[] = Array.from({ length: 23 }, (_, index) => {
  const { subject, professor } = NOTE_SUBJECTS[index % NOTE_SUBJECTS.length]
  const isExam = index % 3 !== 2
  /*
   * 셋 중 하나는 내 것, 하나는 남의 것, 하나는 업로더가 빈 자료(탈퇴한 회원)다.
   * 마지막 것은 `ADMIN`만 손댈 수 있다 (계약 §3-2-4).
   */
  const owner = index % 3
  const me = viewer()
  return {
    id: 301 + index,
    category: isExam ? ('EXAM' as const) : ('SUBJECT' as const),
    title: isExam
      ? `${subject} ${2026 - (index % 3)}년 ${index % 2 === 0 ? '중간' : '기말'}고사 정리본`
      : `${subject} 전체 정리 노트`,
    subjectName: subject,
    professor,
    year: 2026 - (index % 3),
    semester: index % 2 === 0 ? ('SPRING' as const) : ('FALL' as const),
    examType: isExam
      ? index % 2 === 0
        ? ('MIDTERM' as const)
        : ('FINAL' as const)
      : null,
    uploader:
      owner === 0
        ? { id: me.id, name: me.name }
        : owner === 1
          ? { id: 99, name: '권승원' }
          : { id: null, name: '탈퇴한 회원' },
    files: [
      {
        id: 1000 + index * 2,
        originalName: `${subject} 정리본.pdf`,
        sizeBytes: 1_048_576 * ((index % 4) + 1),
      },
      ...(index % 4 === 0
        ? [
            {
              id: 1001 + index * 2,
              originalName: `${subject} 요약.png`,
              sizeBytes: 204_800,
            },
          ]
        : []),
    ],
    createdAt: daysAgo(index),
    updatedAt: daysAgo(index),
  }
})

// 몇 개는 담긴 채로 시작한다 — 즐겨찾기 화면이 처음부터 비어 있으면 확인할 것이 없다.
for (const note of [NOTES[0], NOTES[2], NOTES[5]]) bookmarked.add(note.id)

/** 담긴 순서. **정렬 기준이 "언제 내가 담았나"다** (계약 §3-2-4) — 자료 등록 시각이 아니다. */
const bookmarkOrder: number[] = [NOTES[5].id, NOTES[2].id, NOTES[0].id]

function withBookmark(note: FixtureNote): NoteDetail {
  return { ...note, bookmarked: bookmarked.has(note.id) }
}

function toSummary(note: FixtureNote): NoteSummary {
  const { files, updatedAt: _updatedAt, ...rest } = note
  return {
    ...rest,
    fileCount: files.length,
    bookmarked: bookmarked.has(note.id),
  }
}

function matchesNote(note: FixtureNote, query: NoteQuery): boolean {
  const keyword = query.q?.trim().toLowerCase()
  /*
   * **통합 검색이다** (spec §2-1-1 MUST) — 제목·과목명·교수명을 한 낱말로 훑는다.
   * 필드를 나눠 받는 화면을 만들지 않기 위해 픽스처도 같은 규칙을 쓴다.
   */
  if (
    keyword &&
    ![note.title, note.subjectName, note.professor ?? '']
      .join(' ')
      .toLowerCase()
      .includes(keyword)
  ) {
    return false
  }
  if (query.category && note.category !== query.category) return false
  if (query.subject && note.subjectName !== query.subject) return false
  if (query.professor && note.professor !== query.professor) return false
  if (query.year !== undefined && note.year !== query.year) return false
  if (query.semester && note.semester !== query.semester) return false
  /*
   * **있을 수 없는 조합은 오류가 아니라 0건이다** (계약 §3-2-4). `SUBJECT`에
   * `examType`을 걸면 아무것도 안 맞을 뿐 `400`이 아니다.
   */
  if (query.examType && note.examType !== query.examType) return false
  return true
}

function pageOf(
  rows: NoteSummary[],
  page = 0,
  size = FIXTURE_PAGE_SIZE,
): Page<NoteSummary> {
  const start = page * size
  return {
    content: rows.slice(start, start + size),
    page: {
      size,
      number: page,
      totalElements: rows.length,
      totalPages: Math.ceil(rows.length / size),
    },
  }
}

export function fixtureNotes(
  query: NoteQuery = {},
): Promise<Page<NoteSummary>> {
  const matched = NOTES.filter((note) => matchesNote(note, query))
    .map(toSummary)
    /*
     * **마지막 기준은 언제나 `id`다** (계약 §3-2-4 MUST). 같은 시각·같은 제목이 여럿이면
     * 순서가 정해지지 않아 페이지를 넘길 때마다 배치가 달라진다.
     */
    .sort((a, b) =>
      query.sort === 'title'
        ? a.title.localeCompare(b.title) || a.id - b.id
        : b.createdAt.localeCompare(a.createdAt) || b.id - a.id,
    )
  return Promise.resolve(pageOf(matched, query.page, query.size))
}

export function fixtureNote(id: number): Promise<NoteDetail> {
  const found = NOTES.find((note) => note.id === id)
  if (!found) {
    return Promise.reject(
      new ApiError('NOT_FOUND', 404, '자료를 찾을 수 없습니다.'),
    )
  }
  return Promise.resolve(withBookmark(found))
}

/**
 * 필터 옵션. **실제 등록된 값에서 만든다** (계약 §3-2-4 MUST). 목록을 손으로 적어두면
 * 자료를 추가했을 때 옵션이 따라오지 않아, 고를 수 없는 과목이 생긴다.
 */
export function fixtureNoteFilters(): Promise<NoteFilterOptions> {
  return Promise.resolve({
    subjects: [...new Set(NOTES.map((note) => note.subjectName))].sort((a, b) =>
      a.localeCompare(b),
    ),
    // 교수명이 없는 자료는 옵션을 만들지 않는다 — 화면이 빈 항목을 그린다.
    professors: [
      ...new Set(
        NOTES.map((note) => note.professor).filter(
          (name): name is string => name !== null,
        ),
      ),
    ].sort((a, b) => a.localeCompare(b)),
    years: [...new Set(NOTES.map((note) => note.year))].sort((a, b) => b - a),
  })
}

export function fixtureBookmarks(
  query: { page?: number; size?: number } = {},
): Promise<Page<NoteSummary>> {
  /*
   * **담긴 순서다** (계약 §3-2-4) — 자료 등록 시각이 아니다. 그리고 이 목록의
   * `bookmarked`는 언제나 참이다: 목록에 있다는 것이 곧 담겨 있다는 뜻이다.
   */
  const rows = bookmarkOrder
    .map((id) => NOTES.find((note) => note.id === id))
    .filter((note): note is FixtureNote => note !== undefined)
    .map(toSummary)
  return Promise.resolve(pageOf(rows, query.page, query.size))
}

export function fixtureSetBookmark(id: number, next: boolean): Promise<void> {
  const found = NOTES.find((note) => note.id === id)
  /*
   * **담기는 없는 자료에 `404`, 빼기는 그래도 성공이다** (계약 §3-2-4). 자료가 지워지면
   * 즐겨찾기도 함께 사라져 뺄 것이 이미 없다.
   */
  if (!found) {
    return next
      ? Promise.reject(
          new ApiError('NOT_FOUND', 404, '자료를 찾을 수 없습니다.'),
        )
      : Promise.resolve()
  }
  // **멱등이다** — 이미 담긴 것에 담기, 담기지 않은 것에 빼기 모두 아무 일도 없이 성공한다.
  if (next) {
    if (!bookmarked.has(id)) {
      bookmarked.add(id)
      bookmarkOrder.unshift(id)
    }
  } else {
    bookmarked.delete(id)
    const at = bookmarkOrder.indexOf(id)
    if (at !== -1) bookmarkOrder.splice(at, 1)
  }
  return Promise.resolve()
}

/**
 * 업로드 URL 발급.
 *
 * **확장자와 용량을 픽스처도 거부한다** (계약 §3-2-4). 통과시키면 `413`·`415` 화면을
 * 만들 수 없고, 그 오류는 서버가 붙는 날 처음 보게 된다. **확장자를 크기보다 먼저 본다** —
 * "이 종류는 아예 안 받는다"가 "조금 줄여서 다시"보다 먼저 알아야 할 사실이다.
 */
const FIXTURE_EXTENSIONS = ['pdf', 'docx', 'pptx', 'hwp', 'zip', 'png', 'jpg']
const FIXTURE_MAX_BYTES = 20 * 1024 * 1024
const FIXTURE_MAX_FILES = 10

export function fixtureUploadUrls(files: UploadCandidate[]): Promise<Upload[]> {
  if (files.length === 0 || files.length > FIXTURE_MAX_FILES) {
    return Promise.reject(
      new ApiError(
        'VALIDATION_ERROR',
        400,
        `파일은 1개 이상 ${FIXTURE_MAX_FILES}개 이하로 올려 주세요.`,
      ),
    )
  }
  for (const file of files) {
    const extension = file.originalName.split('.').pop()?.toLowerCase() ?? ''
    if (!FIXTURE_EXTENSIONS.includes(extension)) {
      return Promise.reject(
        new ApiError(
          'UNSUPPORTED_FILE_TYPE',
          415,
          `${file.originalName}은(는) 올릴 수 없는 형식입니다.`,
        ),
      )
    }
    if (file.sizeBytes > FIXTURE_MAX_BYTES) {
      return Promise.reject(
        new ApiError(
          'FILE_TOO_LARGE',
          413,
          `${file.originalName}이(가) 20MB를 넘습니다.`,
        ),
      )
    }
  }
  return Promise.resolve(
    files.map((file, index) => ({
      originalName: file.originalName,
      key: `notes/uploads/1/fixture-${Date.now()}-${index}`,
      /*
       * **픽스처 URL은 `blob:`이다.** 실제 S3 주소를 흉내내면 브라우저가 그 호스트로
       * 진짜 요청을 보내고, 실패가 업로드 오류로 보인다. `uploadAll`의 PUT은 픽스처
       * 모드에서도 실제로 나가므로 **닿아도 안전한 주소**여야 한다.
       */
      url: URL.createObjectURL(new Blob([])),
      expiresAt: new Date(Date.now() + 5 * 60 * 1000).toISOString(),
    })),
  )
}

/** 다음 자료 id. 등록한 자료가 목록·상세에 그대로 남아야 왕복을 확인할 수 있다. */
let nextNoteId = 401

function toDetail(
  id: number,
  body: NoteMetadata,
  files: NoteFile[],
  uploader: Uploader,
  createdAt: string,
): FixtureNote {
  return {
    id,
    category: body.category,
    title: body.title,
    subjectName: body.subjectName,
    professor: body.professor,
    year: body.year,
    semester: body.semester,
    // `SUBJECT`에는 시험 구분이 없다 (계약 §3-2-2 CHECK 제약).
    examType: body.category === 'EXAM' ? body.examType : null,
    uploader,
    files,
    createdAt,
    updatedAt: new Date().toISOString(),
  }
}

/** 등록 요청의 파일을 첨부 레코드로 바꾼다. 크기는 픽스처가 모르므로 적당히 채운다. */
let nextFileId = 2000
function toFiles(files: UploadedFile[]): NoteFile[] {
  return files.map((file) => ({
    id: nextFileId++,
    originalName: file.originalName,
    sizeBytes: 1_048_576,
  }))
}

/**
 * 등록. **업로더는 인증 주체로만 정한다** (계약 §3-2-4 MUST) — 본문으로 받지 않는다.
 * 픽스처도 같은 규칙을 써야 "남의 이름으로 올리는" 경로가 화면에 생기지 않는다.
 */
export function fixtureCreateNote(
  body: NoteMetadata & { files: UploadedFile[] },
): Promise<NoteDetail> {
  const invalid = validateNote(body)
  if (invalid) return Promise.reject(invalid)

  const me = viewer()
  const created = toDetail(
    nextNoteId++,
    body,
    toFiles(body.files),
    { id: me.id, name: me.name },
    new Date().toISOString(),
  )
  NOTES.unshift(created)
  return Promise.resolve(withBookmark(created))
}

/**
 * 수정. **보낸 것으로 통째로 바꾼다** (계약 §3-2-4) — `files`에 없는 기존 파일은
 * 삭제되고, **업로더는 바뀌지 않는다.**
 */
export function fixtureUpdateNote(
  id: number,
  body: NoteMetadata & { files: FileRef[] },
): Promise<NoteDetail> {
  const found = NOTES.find((note) => note.id === id)
  if (!found) {
    return Promise.reject(
      new ApiError('NOT_FOUND', 404, '자료를 찾을 수 없습니다.'),
    )
  }
  const denied = requireOwner(found)
  if (denied) return Promise.reject(denied)

  const invalid = validateNote(body)
  if (invalid) return Promise.reject(invalid)

  const files: NoteFile[] = []
  for (const ref of body.files) {
    if ('fileId' in ref) {
      const existing = found.files.find((file) => file.id === ref.fileId)
      if (!existing) {
        return Promise.reject(
          new ApiError('VALIDATION_ERROR', 400, '없는 첨부를 남기려 했습니다.'),
        )
      }
      files.push(existing)
    } else {
      files.push(...toFiles([ref]))
    }
  }

  const updated = toDetail(
    id,
    body,
    files,
    // 업로더는 그대로다. ADMIN이 남의 자료를 고쳐도 그렇다.
    found.uploader,
    found.createdAt,
  )
  NOTES[NOTES.indexOf(found)] = updated
  return Promise.resolve(withBookmark(updated))
}

export function fixtureRemoveNote(id: number): Promise<void> {
  const found = NOTES.find((note) => note.id === id)
  if (!found) {
    return Promise.reject(
      new ApiError('NOT_FOUND', 404, '자료를 찾을 수 없습니다.'),
    )
  }
  const denied = requireOwner(found)
  if (denied) return Promise.reject(denied)

  NOTES.splice(NOTES.indexOf(found), 1)
  // 첨부와 즐겨찾기는 DB가 함께 지운다 (`ON DELETE CASCADE`). 픽스처도 같이 지운다.
  bookmarked.delete(id)
  const at = bookmarkOrder.indexOf(id)
  if (at !== -1) bookmarkOrder.splice(at, 1)
  return Promise.resolve()
}

/**
 * **본인 것만, `ADMIN`은 전체다** (계약 §3-2-4). 업로더가 빈 자료는 `ADMIN`만 손댈 수
 * 있다 — 주인이 없으므로 "본인"이 성립하지 않는다.
 *
 * 픽스처가 이걸 통과시키면 화면이 버튼을 잘못 보여줘도 드러나지 않는다.
 */
function requireOwner(note: FixtureNote): ApiError | null {
  const me = viewer()
  if (me.role === 'ADMIN') return null
  if (note.uploader.id !== null && note.uploader.id === me.id) return null
  return new ApiError(
    'FORBIDDEN',
    403,
    '본인이 올린 자료만 수정·삭제할 수 있습니다.',
  )
}

/** 서버가 거부할 것을 픽스처도 거부한다 — 통과시키면 오류 화면을 만들 수 없다. */
function validateNote(
  body: NoteMetadata & { files: unknown[] },
): ApiError | null {
  if (body.title.trim() === '' || body.subjectName.trim() === '') {
    return new ApiError(
      'VALIDATION_ERROR',
      400,
      '제목과 과목명을 입력해 주세요.',
    )
  }
  /*
   * **`EXAM`이면 시험 구분이 필수다** (계약 §3-2-2 CHECK 제약). 짝이 어긋나면 서버가
   * `400`이므로 화면도 같은 규칙으로 미리 막는다.
   */
  if (body.category === 'EXAM' && body.examType === null) {
    return new ApiError('VALIDATION_ERROR', 400, '시험 구분을 선택해 주세요.')
  }
  if (body.files.length === 0) {
    return new ApiError(
      'VALIDATION_ERROR',
      400,
      '파일을 하나 이상 남겨 주세요.',
    )
  }
  return null
}

/**
 * 내려받기 URL.
 *
 * 픽스처에는 진짜 파일이 없다. **빈 `blob:` 주소를 준다** — 실제 S3 주소를 흉내내면
 * 브라우저가 그 호스트로 요청을 보내고, 그 실패가 우리 오류처럼 보인다.
 */
export function fixtureDownloadUrl(
  noteId: number,
  fileId: number,
): Promise<DownloadUrl> {
  const note = NOTES.find((item) => item.id === noteId)
  const file = note?.files.find((item) => item.id === fileId)
  /*
   * **`fileId`는 그 자료의 것이어야 한다** (계약 §3-2-4 MUST). 아니면 `404`다 —
   * 경로가 거짓말하게 두면 소유자를 따지는 수정·삭제와 기준이 갈린다.
   */
  if (!note || !file) {
    return Promise.reject(
      new ApiError('NOT_FOUND', 404, '파일을 찾을 수 없습니다.'),
    )
  }
  return Promise.resolve({
    url: URL.createObjectURL(new Blob([`fixture: ${file.originalName}`])),
    originalName: file.originalName,
    expiresAt: new Date(Date.now() + 60 * 1000).toISOString(),
  })
}

// ── 활동사진 ─────────────────────────────────────────────────────────────────

/**
 * 활동사진 픽스처.
 *
 * **랜딩이 쓰는 실제 이미지를 재사용한다** (`public/landing/`). 자리표시자 회색 사각형을
 * 쓰면 그리드 간격·비율이 실제와 달라 화면을 확인하는 뜻이 없다. 출처는
 * `public/landing/README.md`에 있다.
 */
const LANDING_PHOTOS = [
  '/landing/mt.jpg',
  '/landing/education.jpg',
  '/landing/festival.jpg',
  '/landing/club.jpg',
  '/landing/opening.jpg',
  '/landing/samak.jpg',
]

const PHOTO_CAPTIONS = [
  '2026 신입생 환영회',
  '정기 세미나 — 리버싱 기초',
  null,
  'CTF 대회 준비 모임',
  '동아리방 정리하는 날',
  null,
]

type FixturePhoto = Photo

/**
 * 25장. **한 페이지(20장)를 넘긴다** — 페이지네이션이 실제로 동작하는지 보려면 2페이지가
 * 있어야 한다 (#60 완료 조건).
 *
 * **업로더를 섞는다** — 본인·남·탈퇴한 회원. 탈퇴한 회원의 사진도 깨지지 않아야 한다.
 */
const PHOTOS: FixturePhoto[] = Array.from({ length: 25 }, (_, index) => {
  const image = LANDING_PHOTOS[index % LANDING_PHOTOS.length]
  const owner = index % 3
  return {
    id: 501 + index,
    caption: PHOTO_CAPTIONS[index % PHOTO_CAPTIONS.length],
    url: image,
    // 픽스처에는 리사이즈본이 없다. 같은 파일을 쓰되 화면은 썸네일 자리에 그린다.
    thumbnailUrl: image,
    uploaderId: owner === 0 ? USERS.admin.id : owner === 1 ? 99 : null,
    uploaderName:
      owner === 0 ? USERS.admin.name : owner === 1 ? '권승원' : '탈퇴한 회원',
    createdAt: daysAgo(index),
  }
})

/** 목록은 **최신순 고정**이다 (spec §2-1-7) — 화면이 정렬을 고르지 않는다. */
export function fixturePhotos(
  query: { page?: number; size?: number } = {},
): Promise<Page<Photo>> {
  const size = query.size ?? FIXTURE_PAGE_SIZE
  const page = query.page ?? 0
  const start = page * size
  const sorted = [...PHOTOS].sort((a, b) =>
    b.createdAt.localeCompare(a.createdAt),
  )
  return Promise.resolve({
    content: sorted.slice(start, start + size),
    page: {
      size,
      number: page,
      totalElements: PHOTOS.length,
      totalPages: Math.ceil(PHOTOS.length / size),
    },
  })
}

/** 업로드·삭제는 `ADMIN` 전용이다 (계약 §3-2-5). 픽스처가 통과시키면 그 가드를 확인할 수 없다. */
function requirePhotoAdmin(): ApiError | null {
  return SCENARIO === 'admin'
    ? null
    : new ApiError('FORBIDDEN', 403, '관리자만 할 수 있습니다.')
}

export function fixtureRemovePhoto(id: number): Promise<void> {
  const denied = requirePhotoAdmin()
  if (denied) return Promise.reject(denied)

  const at = PHOTOS.findIndex((photo) => photo.id === id)
  if (at === -1) {
    return Promise.reject(
      new ApiError('NOT_FOUND', 404, '사진을 찾을 수 없습니다.'),
    )
  }
  PHOTOS.splice(at, 1)
  return Promise.resolve()
}

const PHOTO_EXTENSIONS_FIXTURE = ['jpg', 'jpeg', 'png']
const PHOTO_MAX_COUNT_FIXTURE = 20

/**
 * 발급. **확장자만 받는다** (계약 §3-2-5) — 파일명도 크기도 오지 않는다.
 *
 * 서버가 `jpg`·`jpeg`·`png`가 아니면 `415`다. 픽스처가 통과시키면 그 오류 화면을 만들 수 없다.
 */
export function fixtureIssuePhotoUploadUrls(
  extensions: string[],
): Promise<PhotoUpload[]> {
  const denied = requirePhotoAdmin()
  if (denied) return Promise.reject(denied)

  if (extensions.length === 0 || extensions.length > PHOTO_MAX_COUNT_FIXTURE) {
    return Promise.reject(
      new ApiError(
        'VALIDATION_ERROR',
        400,
        `사진은 1장 이상 ${PHOTO_MAX_COUNT_FIXTURE}장 이하로 올려 주세요.`,
      ),
    )
  }
  for (const extension of extensions) {
    if (!PHOTO_EXTENSIONS_FIXTURE.includes(extension.toLowerCase())) {
      return Promise.reject(
        new ApiError(
          'UNSUPPORTED_FILE_TYPE',
          415,
          'jpg, jpeg, png만 올릴 수 있습니다.',
        ),
      )
    }
  }
  return Promise.resolve(
    extensions.map((extension, index) => ({
      key: `photos/uploads/fixture-${Date.now()}-${index}.${extension.toLowerCase()}`,
      /*
       * **픽스처 URL은 `blob:`이다.** 실제 S3 주소를 흉내내면 브라우저가 그 호스트로 진짜
       * 요청을 보내고, 그 실패가 업로드 오류로 보인다. `uploadAll`의 PUT은 픽스처 모드에서도
       * 실제로 나가므로 **닿아도 안전한 주소**여야 한다.
       */
      uploadUrl: URL.createObjectURL(new Blob([])),
    })),
  )
}

let nextPhotoId = 601

/**
 * 등록.
 *
 * **일부가 실패해도 성공 응답이다** (계약 §3-2-5). 픽스처도 그래야 화면이 `registered`와
 * `failed`를 함께 읽는지 확인할 수 있다 — **마지막 한 장을 일부러 실패시킨다**(2장 이상일 때).
 * 전부 성공시키면 실패 안내를 화면에서 만들 수 없다.
 */
export function fixtureRegisterPhotos(
  photos: { key: string; caption: string | null }[],
): Promise<PhotoRegisterResult> {
  const denied = requirePhotoAdmin()
  if (denied) return Promise.reject(denied)

  if (photos.length === 0 || photos.length > PHOTO_MAX_COUNT_FIXTURE) {
    return Promise.reject(
      new ApiError('VALIDATION_ERROR', 400, '등록할 사진이 없습니다.'),
    )
  }

  const registered: Photo[] = []
  const failed: PhotoRegisterResult['failed'] = []
  photos.forEach((item, index) => {
    // 2장 이상이면 마지막 한 장이 실패한다 — 부분 실패 화면을 볼 수 있어야 한다.
    if (photos.length > 1 && index === photos.length - 1) {
      failed.push({ key: item.key, reason: 'NOT_FOUND' })
      return
    }
    const image = LANDING_PHOTOS[registered.length % LANDING_PHOTOS.length]
    const created: Photo = {
      id: nextPhotoId++,
      caption: item.caption,
      url: image,
      thumbnailUrl: image,
      // 업로더는 인증 주체로만 정한다 (계약 §3-2-5 MUST). 본문으로 받지 않는다.
      uploaderId: USERS.admin.id,
      uploaderName: USERS.admin.name,
      createdAt: new Date().toISOString(),
    }
    PHOTOS.unshift(created)
    registered.push(created)
  })
  return Promise.resolve({ registered, failed })
}

// ── 자유 게시판 ──────────────────────────────────────────────────────────────

/**
 * 게시판 픽스처.
 *
 * **본문에 HTML을 섞어 둔다.** 계약이 평문만 다루기로 못 박았으므로(spec §2-1-8 MUST)
 * 화면이 그것을 글자 그대로 그리는지 눈으로 확인할 수 있어야 한다 — 태그가 사라지거나
 * 굵게 보이면 그 자리에서 드러난다.
 *
 * **작성자도 섞는다** — 나·남·탈퇴한 회원. 탈퇴한 글이 깨지지 않는 것이 #237 완료 조건이다.
 */
const POST_BODIES = [
  '이번 학기 알고리즘 스터디 모집합니다.\n\n매주 수요일 저녁 7시, 동아리방에서 진행해요.\n관심 있으신 분은 댓글 대신 카톡으로 연락 주세요!',
  '어제 세미나 자료 올려두었습니다. 자료게시판에서 받아가세요.',
  '<script>alert(1)</script> 이런 것도 그냥 글자로 보여야 합니다.\n<b>굵게</b> 안 되는 게 맞아요.',
  '동아리방 청소 도와주실 분 구합니다.\n토요일 오후 2시입니다.',
  '학교 앞에 새로 생긴 카페 괜찮네요. 과제하기 좋습니다.',
]

const POST_TITLES = [
  '이번 학기 스터디 모집합니다',
  '세미나 자료 공유',
  'HTML이 그대로 보이는지 확인용',
  '동아리방 청소 도와주세요',
  '학교 앞 카페 추천',
]

/** 게시글 하나. 목록은 여기서 `content`·`updatedAt`을 빼고 꺼낸다. */
type FixturePost = PostDetail

/**
 * 25건. **한 페이지(20건)를 넘긴다** — 페이지네이션이 실제로 도는지 보려면 2페이지가 있어야 한다.
 */
const POSTS: FixturePost[] = Array.from({ length: 25 }, (_, index) => {
  const owner = index % 3
  return {
    id: 701 + index,
    title: `${POST_TITLES[index % POST_TITLES.length]}${index >= POST_TITLES.length ? ` (${index + 1})` : ''}`,
    content: POST_BODIES[index % POST_BODIES.length],
    author:
      owner === 0
        ? { id: USERS.user.id, name: USERS.user.name }
        : owner === 1
          ? { id: 99, name: '권승원' }
          : // 탈퇴한 회원. 서버가 이름을 채운다 (§2-1-8).
            { id: null, name: '탈퇴한 회원' },
    createdAt: daysAgo(index),
    updatedAt: daysAgo(index),
  }
})

/** 최신순 고정 (spec §2-1-8 MUST) — 화면이 정렬을 고르지 않는다. */
export function fixturePosts(
  query: { page?: number; size?: number } = {},
): Promise<Page<PostSummary>> {
  const size = query.size ?? FIXTURE_PAGE_SIZE
  const page = query.page ?? 0
  const start = page * size
  const rows = [...POSTS]
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.id - a.id)
    // **목록은 본문을 담지 않는다** (계약 §3-2-5 MUST). 픽스처가 담으면 화면이 그것에 기댄다.
    .map(({ content: _content, updatedAt: _updatedAt, ...rest }) => rest)
  return Promise.resolve({
    content: rows.slice(start, start + size),
    page: {
      size,
      number: page,
      totalElements: POSTS.length,
      totalPages: Math.ceil(POSTS.length / size),
    },
  })
}

export function fixturePost(id: number): Promise<PostDetail> {
  const found = POSTS.find((post) => post.id === id)
  if (!found) {
    return Promise.reject(
      new ApiError('NOT_FOUND', 404, '게시글을 찾을 수 없습니다.'),
    )
  }
  return Promise.resolve(found)
}

let nextPostId = 801

/**
 * 등록.
 *
 * **작성자는 인증 주체로만 정한다** (계약 §3-2-5 MUST) — 본문으로 받지 않는다. 픽스처도
 * 같은 규칙을 써야 "남의 이름으로 쓰는" 경로가 화면에 생기지 않는다.
 *
 * **길이는 코드 포인트로 센다** — 서버의 `@CodePointSize`와 같다. 통과시키면 화면의
 * 글자 수 세기가 틀려도 드러나지 않는다.
 */
export function fixtureCreatePost(body: {
  title: string
  content: string
}): Promise<PostDetail> {
  /*
   * **검사는 원문에 건다.** 서버의 `@NotBlank`·`@CodePointSize`도 다듬기 전 값에 걸린다 —
   * 화면이 다듬은 뒤 재면 상한 언저리에서 판정이 갈린다.
   */
  if (body.title.trim() === '' || body.content.trim() === '') {
    return Promise.reject(
      new ApiError('VALIDATION_ERROR', 400, '제목과 내용을 입력해 주세요.'),
    )
  }
  if ([...body.title].length > 200) {
    return Promise.reject(
      new ApiError('VALIDATION_ERROR', 400, '제목은 200자까지 쓸 수 있습니다.'),
    )
  }
  if ([...body.content].length > 10000) {
    return Promise.reject(
      new ApiError(
        'VALIDATION_ERROR',
        400,
        '내용은 10,000자까지 쓸 수 있습니다.',
      ),
    )
  }

  const me = SCENARIO === 'admin' ? USERS.admin : USERS.user
  /*
   * **등록 직후 두 시각은 같다** (`PostService`가 한 `now`를 둘에 쓴다). 여기서 각각
   * `new Date()`를 부르면 밀리초가 갈려, 화면이 "수정된 글"을 가리려 할 때 픽스처만
   * 다르게 답한다.
   */
  const now = new Date().toISOString()
  const created: FixturePost = {
    id: nextPostId++,
    /*
     * **제목은 다듬고 본문은 그대로 둔다** — 서버가 그렇게 한다 (§3-2-5 MUST,
     * `PostService` — "본문은 trim하지 않는다"). 픽스처가 본문을 털면 들여쓴 코드가
     * 사라지는 회귀를 화면에서 못 잡는다.
     */
    title: body.title.trim(),
    content: body.content,
    author: { id: me.id, name: me.name },
    createdAt: now,
    updatedAt: now,
  }
  POSTS.unshift(created)
  return Promise.resolve(created)
}
