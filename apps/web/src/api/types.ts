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

/** 서버가 내려주는 에러 코드. 원본은 spec/3-2-DESIGN-CONTRACT.md §3-2-7 표다. */
export type ErrorCode =
  | 'VALIDATION_ERROR'
  | 'UNAUTHENTICATED'
  | 'PENDING_APPROVAL'
  | 'SUSPENDED'
  | 'FORBIDDEN'
  | 'NOT_FOUND'
  | 'DUPLICATE_EMAIL'
  | 'FILE_TOO_LARGE'
  | 'UNSUPPORTED_FILE_TYPE'

/** 서버까지 못 갔거나 계약을 벗어난 응답이 온 경우. 서버 코드 목록과 섞지 않는다. */
export type ClientErrorCode = 'NETWORK_ERROR' | 'INVALID_RESPONSE'

/** 서버 에러 응답 본문 (spec/5-TESTING.md §5-4). */
export interface ErrorBody {
  code: ErrorCode
  message: string
}
