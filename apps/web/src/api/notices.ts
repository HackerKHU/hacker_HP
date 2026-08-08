import { request, toQuery } from './client'
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
  return request<Page<Notice>>(`/notices${toQuery(query)}`)
}

export function get(id: number): Promise<Notice> {
  return request<Notice>(`/notices/${id}`)
}

export interface NoticeInput {
  title: string
  content: string
}

export function create(body: NoticeInput): Promise<Notice> {
  return request<Notice>('/notices', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function update(
  id: number,
  body: Partial<NoticeInput>,
): Promise<Notice> {
  return request<Notice>(`/notices/${id}`, {
    method: 'PATCH',
    body: JSON.stringify(body),
  })
}

export function remove(id: number): Promise<void> {
  return request(`/notices/${id}`, { method: 'DELETE' })
}

export function togglePin(id: number): Promise<Notice> {
  return request<Notice>(`/notices/${id}/pin`, { method: 'PATCH' })
}
