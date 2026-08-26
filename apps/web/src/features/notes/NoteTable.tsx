import { Star } from 'lucide-react'
import { Link } from 'react-router-dom'
import type { NoteSummary } from '@/api/notes'
import { ListSurface } from '@/components/ListSurface'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { cn } from '@/lib/utils'
import { EXAM_TYPE_LABEL, formatDate, SEMESTER_LABEL } from './labels'

/**
 * 자료 목록의 표. **목록 화면과 즐겨찾기 화면이 이걸 같이 쓴다.**
 *
 * 계약이 `GET /bookmarks`의 응답을 `GET /notes`와 같은 형태로 맞춰 둔 이유가 이것이다
 * (spec §3-2-4) — "같은 카드를 그리는 화면이라 응답이 다르면 화면이 두 벌이 된다."
 *
 * @param showCategory 갈래 열을 보일지. 카테고리별 목록에서는 전부 같은 값이라 감춘다.
 *   즐겨찾기는 시험·과목이 섞여 있어 보여야 한다.
 */
export function NoteTable({
  notes,
  showCategory,
  onToggleBookmark,
  busy,
}: {
  notes: NoteSummary[]
  showCategory: boolean
  onToggleBookmark: (note: NoteSummary) => void
  busy: boolean
}) {
  return (
    /*
     * 표 래퍼는 각지게 둔다 (#218) — 표는 행이 직선으로 쌓이는 요소인데 래퍼만 둥글면
     * 네 모서리에서 행이 잘려 보인다. 공지 목록·회원 관리와 같은 기조다.
     */
    <ListSurface className="mt-4">
      <Table>
        <TableHeader>
          <TableRow>
            {/* 별표 열은 제목이 없다 — 아이콘만 있는 칸이라 이름을 붙이면 폭만 먹는다. */}
            <TableHead className="w-10" />
            {showCategory && <TableHead className="w-28">카테고리</TableHead>}
            <TableHead>제목</TableHead>
            <TableHead className="w-36">과목</TableHead>
            <TableHead className="w-24">교수</TableHead>
            <TableHead className="w-32">학기</TableHead>
            <TableHead className="w-16 text-right">첨부</TableHead>
            <TableHead className="w-24">업로더</TableHead>
            <TableHead className="w-28">등록일</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {notes.map((note) => (
            <TableRow key={note.id}>
              <TableCell>
                {/*
                 * **목록에서도 담고 뺀다** (spec §2-1-5). 응답의 `bookmarked`를 보고
                 * 방향을 정한다 — 토글이 아니라 담기/빼기 두 요청이라(§3-2-4 MUST)
                 * 재시도가 방금 담은 것을 조용히 빼지 않는다.
                 */}
                <Button
                  variant="ghost"
                  size="icon"
                  className="size-8"
                  disabled={busy}
                  aria-pressed={note.bookmarked}
                  aria-label={
                    note.bookmarked
                      ? `${note.title} 즐겨찾기 해제`
                      : `${note.title} 즐겨찾기`
                  }
                  onClick={() => onToggleBookmark(note)}
                >
                  {/*
                   * 무채색 팔레트라 색으로 구분할 수 없다. **채움과 비움으로 가른다** —
                   * 담긴 것은 칠해진 별, 아닌 것은 테두리만 있는 별이다.
                   */}
                  <Star
                    className={cn(
                      'size-4',
                      note.bookmarked
                        ? 'fill-current text-foreground'
                        : 'text-muted-foreground',
                    )}
                    aria-hidden="true"
                  />
                </Button>
              </TableCell>

              {showCategory && (
                <TableCell>
                  <Badge variant="outline" className="whitespace-nowrap">
                    {note.category === 'EXAM' ? '시험' : '과목'}
                  </Badge>
                </TableCell>
              )}

              <TableCell className="font-medium">
                <Link
                  to={`/notes/${note.id}`}
                  className="underline-offset-4 hover:underline"
                >
                  {note.title}
                </Link>
              </TableCell>
              <TableCell>{note.subjectName}</TableCell>
              {/* 교수명은 없을 수 있다. 줄을 비우면 무엇이 빈 것인지 안 보여 `—`를 그린다. */}
              <TableCell className="text-muted-foreground">
                {note.professor ?? '—'}
              </TableCell>
              <TableCell className="whitespace-nowrap">
                {note.year}년 {SEMESTER_LABEL[note.semester]}
                {note.examType && ` · ${EXAM_TYPE_LABEL[note.examType]}`}
              </TableCell>
              <TableCell className="text-right tabular-nums">
                {note.fileCount}
              </TableCell>
              <TableCell className="text-muted-foreground">
                {note.uploader.name}
              </TableCell>
              <TableCell className="whitespace-nowrap text-muted-foreground">
                {formatDate(note.createdAt)}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </ListSurface>
  )
}
