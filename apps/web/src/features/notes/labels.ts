import type { Category, ExamType, NoteSummary, Semester } from '@/api/notes'
import type { SessionUser } from '@/auth/session'

/**
 * 화면 낱말. **enum 값과 표시 문구를 한 곳에서 잇는다** — 화면마다 따로 적으면 목록의
 * "1학기"와 상세의 "봄학기"가 갈린다.
 */
export const CATEGORY_LABEL: Record<Category, string> = {
  EXAM: '시험 정리본',
  SUBJECT: '과목 정리본',
}

export const SEMESTER_LABEL: Record<Semester, string> = {
  SPRING: '1학기',
  SUMMER: '여름학기',
  FALL: '2학기',
  WINTER: '겨울학기',
}

/** 주소의 `semester`를 계약의 enum으로 좁힌다. 모르는 값은 필터에서 제거한다. */
export function semesterFromParam(raw: string | null): Semester | undefined {
  if (raw === null || !Object.hasOwn(SEMESTER_LABEL, raw)) return undefined
  return raw as Semester
}

export const EXAM_TYPE_LABEL: Record<ExamType, string> = {
  MIDTERM: '중간',
  FINAL: '기말',
}

/** 자료게시판 경로. 헤더 메뉴·돌아가기 링크가 같은 값을 쓰게 한다. */
export const NOTES_PATH = '/notes'

/**
 * 갈래 탭의 주소. **갈래를 URL에 둔다** — 새로고침·뒤로가기·링크 공유에 살아남아야
 * 한다(`apps/web/AGENTS.md`). 파라미터 이름은 서버의 `category` 그대로다.
 *
 * 빠져 있으면 시험 정리본으로 본다 (`categoryFromParam`). 그래도 탭 링크는 값을 늘
 * 적는다 — 주소만 보고 어느 탭인지 알 수 있어야 공유가 뜻을 갖는다.
 */
export function categoryPath(category: Category): string {
  return `${NOTES_PATH}?category=${category}`
}

/** 주소의 `category`를 계약의 enum으로 되돌린다. 모르는 값이면 기본값(시험)이다. */
export function categoryFromParam(raw: string | null): Category {
  return raw === 'SUBJECT' ? 'SUBJECT' : 'EXAM'
}

/** 서버는 UTC로 내려준다. 목록·상세 모두 날짜까지만 쓴다. */
export function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
}

/**
 * 파일 크기. 사람이 읽는 단위로 줄인다 — `1048576`은 크기를 가늠하게 해주지 않는다.
 * 1024 기준이라 브라우저의 표시와 어긋나지 않는다.
 */
export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB']
  let value = bytes / 1024
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[unit]}`
}

/**
 * **수정·삭제 진입점을 보일지** (spec §2-1-3, §3-2-4).
 *
 * **노출 제어일 뿐 권한 통제가 아니다** (spec §3-1-7). 서버가 같은 조건으로 다시 막는다 —
 * 여기를 뚫어도 `403`이다.
 *
 * **판단은 `id`로 한다** (§3-2-2 MUST). 이름으로 견주면 "탈퇴한 회원"끼리 서로의 자료를
 * 지울 수 있다. 그래서 **업로더가 빈 자료는 `ADMIN`만** 손댈 수 있다 — 주인이 없으므로
 * "본인"이 성립하지 않는다.
 */
export function canEdit(
  note: Pick<NoteSummary, 'uploader'>,
  user: SessionUser,
): boolean {
  if (user.role === 'ADMIN') return true
  return note.uploader.id !== null && note.uploader.id === user.id
}

/**
 * 자료를 불러오지 못했을 때의 문구. **세 자료 화면이 같이 쓴다** (목록·상세·등록/수정).
 *
 * **`403 INACTIVE`는 사유를 말해준다** (#231, spec 3-1 §3-1-2). 비활동 부원에게 "잠시 후
 * 다시 시도해 주세요"라고 하면 **이번 학기 내내 다시 시도한다** — 그 사람은 같은 답만 받는다.
 * 로그인 화면으로 튕기지 않는 대신 왜 막혔는지가 화면에 있어야 한다 (spec 3-1 §3-1-5).
 *
 * 자료 화면 전체를 비활동 부원에게 어떻게 보여줄지는 #59가 맡는다. 여기는 그 화면이
 * 없는 동안 사용자가 영문을 모르지 않게 하는 최소한이다.
 */
export function noteErrorText(inactive: boolean): string {
  return inactive
    ? '이번 학기 비활동 부원은 자료를 이용할 수 없습니다. 운영진에게 문의해 주세요.'
    : '자료를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
}
