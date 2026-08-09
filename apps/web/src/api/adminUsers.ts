import { request, toQuery } from './client'
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
  return request<Page<User>>(`/admin/users${toQuery(query)}`)
}

/** 일괄 승인. `PENDING` → `ACTIVE`. */
export function approve(userIds: number[]): Promise<void> {
  return request('/admin/users/approve', {
    method: 'POST',
    body: JSON.stringify({ userIds }),
  })
}

export function updateStatus(
  id: number,
  status: 'ACTIVE' | 'SUSPENDED',
): Promise<User> {
  return request<User>(`/admin/users/${id}/status`, {
    method: 'PATCH',
    body: JSON.stringify({ status }),
  })
}
