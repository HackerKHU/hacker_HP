import { request, toQuery } from './client'
import {
  fixtureAdminUsers,
  fixtureApproveUsers,
  fixtureUpdateUserRole,
  fixtureUpdateUserStatus,
} from './fixtures'
import type { Page, Role, User, UserStatus } from './types'

export type AdminUserQuery = {
  status?: UserStatus
  role?: Role
  q?: string
  /**
   * 신청서를 냈는지 (spec §3-2-6).
   *
   * **`status=PENDING`만으로는 승인 대기를 고를 수 없다.** 구글 로그인만 해보고 신청서를
   * 내지 않은 계정도 `PENDING`이기 때문이다. 승인 대상은 `status=PENDING&applied=true`다.
   *
   * 거르는 것은 **서버가 한다.** 화면이 받아서 버리면 총 건수와 총 페이지 수가 실제와
   * 어긋나 관리자가 "12명 남았다"고 읽는 숫자가 틀리게 된다.
   */
  applied?: boolean
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

/**
 * 권한 부여·회수 (spec 2-2 §2-2-5).
 *
 * **마지막 활성 관리자인지는 화면이 판단하지 않는다** — 서버가 잠금을 걸고 원자적으로 센다
 * (§2-2-7 MUST). 화면은 거부 사유를 그대로 보여준다.
 */
export function updateRole(id: number, role: Role): Promise<User> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true') {
    return fixtureUpdateUserRole(id, role)
  }
  return request<User>(`/admin/users/${id}/role`, {
    method: 'PATCH',
    body: JSON.stringify({ role }),
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
