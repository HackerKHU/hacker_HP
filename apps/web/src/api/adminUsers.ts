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
 * 일괄 승인 결과.
 *
 * ⚠️ **계약에 없는 형태다** (spec §3-2-9 미합의). 계약은 "신청하지 않은 계정의 id가 섞여
 * 오면 그 건은 실패로 집계한다"(§3-2-6 MUST)까지만 정하고 응답 형태를 말하지 않는데,
 * 화면은 성공·실패 건수를 안내해야 한다(2-2 §2-2-2 MUST). 그래서 프론트가 형태를 제안하고
 * 여기에 맞춰 만들었다 — 서버와 합의되면 §3-2-9를 지우고 계약 본문에 넣는다.
 *
 * **건수를 따로 받지 않는다.** 배열 길이가 곧 건수라 두 값이 어긋날 자리가 없다.
 */
export interface ApproveResult {
  /** 승인된 id. */
  approved: number[]
  /** 승인하지 못한 id와 사유. 누가 실패했는지 알아야 운영자가 조치할 수 있다. */
  failed: { userId: number; reason: ApproveFailureReason }[]
}

/** 지금 계약이 정의하는 실패는 하나뿐이다 (§3-2-6 — 신청서를 내지 않은 계정). */
export type ApproveFailureReason = 'NOT_APPLIED'

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
