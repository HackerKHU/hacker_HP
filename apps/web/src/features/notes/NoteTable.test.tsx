import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { NoteSummary } from '@/api/notes'
import { MemoryRouter } from '@/test/TestRouter'
import { NoteTable } from './NoteTable'

const LONG_TITLE =
  '기존 자료의 아주 긴 제목은 원문을 보존하면서 목록과 즐겨찾기에서 메타데이터 열을 밀지 않습니다'.repeat(
    2,
  )

const NOTE: NoteSummary = {
  id: 301,
  category: 'EXAM',
  title: LONG_TITLE,
  subjectName: '운영체제',
  professor: '김교수',
  year: 2026,
  semester: 'SPRING',
  examType: 'MIDTERM',
  uploader: { id: 1, name: '홍길동' },
  fileCount: 2,
  viewCount: 123,
  bookmarked: true,
  createdAt: '2026-08-01T09:00:00Z',
}

describe('자료 공통 표', () => {
  it.each([
    ['전체 목록', false],
    ['즐겨찾기 목록', true],
  ] as const)(
    '%s에서 장문 제목만 한 줄로 줄이고 원문은 보존한다',
    (_, showCategory) => {
      const { container } = render(
        <MemoryRouter>
          <NoteTable
            notes={[NOTE]}
            showCategory={showCategory}
            onToggleBookmark={vi.fn()}
            busy={false}
          />
        </MemoryRouter>,
      )

      const table = container.querySelector('table')
      expect(table?.className).toContain('table-fixed')
      const title = screen.getByRole('link', { name: LONG_TITLE })
      const titleHead = screen.getByRole('columnheader', { name: '제목' })
      expect(titleHead.className).toContain('w-56')
      expect(titleHead.className).toContain('sm:w-72')
      expect(title).toHaveAttribute('title', LONG_TITLE)
      expect(title.className).toContain('truncate')
      expect(title.parentElement?.className).toContain('max-w-0')
      expect(screen.getByRole('cell', { name: '운영체제' })).toBeVisible()
    },
  )
})
