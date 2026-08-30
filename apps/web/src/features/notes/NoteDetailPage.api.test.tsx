import { render, screen } from '@testing-library/react'
import { StrictMode } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { NoteDetail } from '@/api/notes'
import type { User } from '@/api/types'
import { SessionProvider } from '@/auth/session'
import { NoteDetailPage } from './NoteDetailPage'

const MEMBER: User = {
  id: 1,
  email: 'member@khu.ac.kr',
  studentNo: '2021123456',
  name: '홍길동',
  department: '컴퓨터공학과',
  role: 'USER',
  status: 'ACTIVE',
  createdAt: '2026-03-02T09:00:00Z',
  appliedAt: '2026-03-02T09:10:00Z',
  approvedAt: '2026-03-03T09:00:00Z',
}

const NOTE: NoteDetail = {
  id: 301,
  category: 'EXAM',
  title: '운영체제 중간고사 정리본',
  subjectName: '운영체제',
  professor: '김교수',
  year: 2026,
  semester: 'SPRING',
  examType: 'MIDTERM',
  uploader: { id: 1, name: '홍길동' },
  viewCount: 12_346,
  files: [],
  bookmarked: false,
  createdAt: '2026-08-01T09:00:00Z',
  updatedAt: '2026-08-01T09:00:00Z',
}

const fetchMock = vi.fn<typeof fetch>()

vi.mock('@/api/auth', () => ({
  getMe: () => Promise.resolve(MEMBER),
  logout: () => Promise.resolve(),
}))

beforeEach(() => {
  vi.stubEnv('VITE_USE_FIXTURES', 'false')
  fetchMock.mockReset()
  fetchMock.mockResolvedValue(Response.json(NOTE))
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

it('실제 API 모드의 StrictMode 상세 진입은 GET /notes/{id}를 한 번만 보낸다', async () => {
  render(
    <StrictMode>
      <MemoryRouter initialEntries={['/notes/301']}>
        <SessionProvider>
          <Routes>
            <Route path="/notes/:id" element={<NoteDetailPage />} />
          </Routes>
        </SessionProvider>
      </MemoryRouter>
    </StrictMode>,
  )

  await screen.findByRole('heading', { name: NOTE.title })
  const detailGets = fetchMock.mock.calls.filter(
    ([url, init]) =>
      url === '/api/v1/notes/301' && (init?.method ?? 'GET') === 'GET',
  )
  expect(detailGets).toHaveLength(1)
})
