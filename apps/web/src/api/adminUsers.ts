import { request, toQuery } from './client'
import {
  fixtureAdminUsers,
  fixtureApproveUsers,
  fixtureUpdateUserStatus,
} from './fixtures'
import type { Page, Role, User, UserStatus } from './types'

export type AdminUserQuery = {
  status?: UserStatus
  role?: Role
  q?: string
  sort?: string
  page?: number
  size?: number
}

export function list(query: AdminUserQuery = {}): Promise<Page<User>> {
  // 플래그는 함수 안에서 리터럴로 평가한다 — 이유는 `auth.ts` 상단 주석에 있다.
  if (import.meta.env.VITE_USE_FIXTURES === 'true')
    return fixtureAdminUsers(query)
  return request<Page<User>>(`/admin/users${toQuery(query)}`)
}

/**
 * 일괄 승인 결과. 형태는 계약 §3-2-6이 원본이다 (2026-08-13 확정).
 *
 * **건수를 따로 받지 않는다.** 배열 길이가 곧 건수라 두 값이 어긋날 자리가 없다.
 */
export interface ApproveResult {
  /** 승인된 id. */
  approved: number[]
  /** 승인하지 못한 id와 사유. 누가 실패했는지 알아야 운영자가 조치할 수 있다. */
  failed: { userId: number; reason: ApproveFailureReason }[]
}

/**
 * 승인 실패 사유 (§3-2-6).
 *
 * **셋을 하나로 뭉개면 안 된다.** 전부 `NOT_APPLIED`로 안내하면 신청서를 내고 이미
 * 승인까지 받은 사람에게 "신청서를 내지 않았다"고 말하게 된다 — 두 관리자가 같은
 * 신청을 연달아 처리하면 실제로 밟는 경로다.
 */
export type ApproveFailureReason = 'NOT_FOUND' | 'NOT_PENDING' | 'NOT_APPLIED'

/** 일괄 승인. `PENDING` → `ACTIVE`. */
export function approve(userIds: number[]): Promise<ApproveResult> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true') {
    return fixtureApproveUsers(userIds)
  }
  return request<ApproveResult>('/admin/users/approve', {
    method: 'POST',
    body: JSON.stringify({ userIds }),
  })
}

export function updateStatus(
  id: number,
  status: 'ACTIVE' | 'SUSPENDED',
): Promise<User> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true') {
    return fixtureUpdateUserStatus(id, status)
  }
  return request<User>(`/admin/users/${id}/status`, {
    method: 'PATCH',
    body: JSON.stringify({ status }),
  })
}
