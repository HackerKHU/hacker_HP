import { request, toQuery } from './client'
import {
  fixtureCreateNotice,
  fixtureNotice,
  fixtureNotices,
  fixtureRemoveNotice,
  fixtureTogglePin,
  fixtureUpdateNotice,
} from './fixtures'
import type { Page } from './types'

export interface Notice {
  id: number
  title: string
  content: string
  isPinned: boolean
  createdAt: string
  updatedAt: string
}

export type NoticeQuery = {
  page?: number
  size?: number
}

/** 정렬은 서버가 `is_pinned DESC, created_at DESC`로 고정한다 (spec §2-1-6). */
export function list(query: NoticeQuery = {}): Promise<Page<Notice>> {
  // 플래그는 함수 안에서 리터럴로 평가한다 — 이유는 `auth.ts` 상단 주석에 있다.
  if (import.meta.env.VITE_USE_FIXTURES === 'true') {
    return fixtureNotices(query.page, query.size)
  }
  return request<Page<Notice>>(`/notices${toQuery(query)}`)
}

export function get(id: number): Promise<Notice> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true') return fixtureNotice(id)
  return request<Notice>(`/notices/${id}`)
}

export interface NoticeInput {
  title: string
  content: string
}

export function create(body: NoticeInput): Promise<Notice> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true') {
    return fixtureCreateNotice(body)
  }
  return request<Notice>('/notices', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function update(
  id: number,
  body: Partial<NoticeInput>,
): Promise<Notice> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true') {
    return fixtureUpdateNotice(id, body)
  }
  return request<Notice>(`/notices/${id}`, {
    method: 'PATCH',
    body: JSON.stringify(body),
  })
}

export function remove(id: number): Promise<void> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true')
    return fixtureRemoveNotice(id)
  return request(`/notices/${id}`, { method: 'DELETE' })
}

export function togglePin(id: number): Promise<Notice> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true') return fixtureTogglePin(id)
  return request<Notice>(`/notices/${id}/pin`, { method: 'PATCH' })
}
