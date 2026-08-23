import type { Category, ExamType, NoteSummary, Semester } from '@/api/notes'
import type { ActiveUser } from '@/auth/session'

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
  FALL: '2학기',
}

export const EXAM_TYPE_LABEL: Record<ExamType, string> = {
  MIDTERM: '중간',
  FINAL: '기말',
}

/** 카테고리별 목록 경로. 라우트와 메뉴가 같은 값을 쓰게 한다. */
export const CATEGORY_PATH: Record<Category, string> = {
  EXAM: '/notes/exam',
  SUBJECT: '/notes/subject',
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
  user: ActiveUser,
): boolean {
  if (user.role === 'ADMIN') return true
  return note.uploader.id !== null && note.uploader.id === user.id
}
