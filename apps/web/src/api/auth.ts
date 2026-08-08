import { request } from './client'
import type { User } from './types'

export interface SignupRequest {
  email: string
  studentNo: string
  name: string
  password: string
}

export interface LoginRequest {
  email: string
  password: string
}

export function signup(body: SignupRequest): Promise<void> {
  return request('/auth/signup', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function login(body: LoginRequest): Promise<User> {
  return request<User>('/auth/login', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function logout(): Promise<void> {
  return request('/auth/logout', { method: 'POST' })
}

export function getMe(): Promise<User> {
  return request<User>('/auth/me')
}
