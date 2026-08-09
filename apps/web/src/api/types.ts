export type Role = 'USER' | 'ADMIN'

export type UserStatus = 'PENDING' | 'ACTIVE' | 'SUSPENDED'

export interface User {
  id: number
  email: string
  studentNo: string
  name: string
  role: Role
  status: UserStatus
  createdAt: string
  approvedAt: string | null
}

/** 목록 API 공통 응답. 형태는 spec/3-2-DESIGN-CONTRACT.md §3-2-8(Spring Data `PagedModel`)이 원본이다. */
export interface Page<T> {
  content: T[]
  page: {
    size: number
    number: number
    totalElements: number
    totalPages: number
  }
}

/**
 * 서버가 내려주는 에러 코드. 원본은 spec/3-2-DESIGN-CONTRACT.md §3-2-7 표다.
 *
 * 타입과 런타임 검사용 목록이 어긋나지 않도록 배열 하나에서 타입을 파생시킨다.
 * 유니온 타입은 컴파일 타임에만 존재하므로, 서버 응답은 이 목록으로 실제로 검사해야 한다.
 */
export const ERROR_CODES = [
  'VALIDATION_ERROR',
  'UNAUTHENTICATED',
  'PENDING_APPROVAL',
  'SUSPENDED',
  'FORBIDDEN',
  'NOT_FOUND',
  'DUPLICATE_EMAIL',
  'FILE_TOO_LARGE',
  'UNSUPPORTED_FILE_TYPE',
] as const

export type ErrorCode = (typeof ERROR_CODES)[number]

/** 서버까지 못 갔거나 계약을 벗어난 응답이 온 경우. 서버 코드 목록과 섞지 않는다. */
export type ClientErrorCode = 'NETWORK_ERROR' | 'INVALID_RESPONSE'

/** 서버 에러 응답 본문 (spec/5-TESTING.md §5-4). */
export interface ErrorBody {
  code: ErrorCode
  message: string
}
