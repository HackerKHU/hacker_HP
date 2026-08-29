import { Download, Star } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '@/api/client'
import {
  downloadUrl,
  get,
  type NoteDetail,
  remove,
  setBookmark,
} from '@/api/notes'
import { isInactive, useSession } from '@/auth/session'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import {
  CATEGORY_LABEL,
  canEdit,
  categoryPath,
  EXAM_TYPE_LABEL,
  formatDate,
  formatSize,
  NOTES_PATH,
  noteErrorText,
  SEMESTER_LABEL,
} from './labels'

type Status = 'loading' | 'loaded' | 'notFound' | 'failed'

/**
 * 자료 상세.
 *
 * **파일 URL을 미리 받지 않는다** (spec §3-2-4). 상세에 담으면 열어보기만 해도 모든
 * 파일의 presigned URL이 발급되어 받지도 않을 주소가 응답·로그·히스토리에 남는다.
 * 내려받기 버튼을 눌렀을 때만 발급한다.
 */
export function NoteDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const session = useSession()
  const { state, reportApiError } = session

  const [note, setNote] = useState<NoteDetail | null>(null)
  const [status, setStatus] = useState<Status>('loading')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    setStatus('loading')
    get(Number(id))
      .then((result) => {
        if (!alive) return
        setNote(result)
        setStatus('loaded')
      })
      .catch((caught: unknown) => {
        if (!alive) return
        // #36 계약 — 403 PENDING_APPROVAL이면 가드가 대기 화면으로 되돌린다.
        reportApiError(caught)
        setStatus(
          caught instanceof ApiError && caught.code === 'NOT_FOUND'
            ? 'notFound'
            : 'failed',
        )
      })
    return () => {
      alive = false
    }
  }, [id, reportApiError])

  /**
   * 내려받기. **발급받은 주소로 브라우저를 보낸다** — 파일 바이트는 서버를 거치지 않는다
   * (spec §2-1-4 MUST).
   *
   * `<a download>`을 쓰지 않는다. 그 힌트는 **다른 오리진 링크에서 브라우저가 무시하고**,
   * 저장될 이름은 서버가 `Content-Disposition`으로 서명에 담아 두었다 (§3-2-4). 여기서
   * 이름을 다시 정하려 들면 서명과 어긋난 기대만 남는다.
   */
  async function handleDownload(fileId: number) {
    setBusy(true)
    setError(null)
    try {
      const issued = await downloadUrl(Number(id), fileId)
      /*
       * 같은 탭을 옮기지 않는다. `attachment`라 브라우저가 내려받기로 처리하고 페이지는
       * 그대로 남지만, 팝업 차단에 걸리지 않게 `noopener`로 연다.
       */
      window.open(issued.url, '_blank', 'noopener,noreferrer')
    } catch (caught: unknown) {
      reportApiError(caught)
      setError(
        caught instanceof ApiError
          ? caught.message
          : '내려받기 주소를 받지 못했습니다. 다시 시도해 주세요.',
      )
    } finally {
      setBusy(false)
    }
  }

  /** 담기·빼기. 서버가 준 `bookmarked`를 보고 방향을 정한다 (계약 §3-2-4 — 토글이 아니다). */
  async function toggleBookmark() {
    if (!note) return
    setBusy(true)
    setError(null)
    try {
      await setBookmark(note.id, !note.bookmarked)
      // 낙관적으로 바꾸지 않는다. 서버가 받아들인 뒤 그 상태로 다시 읽는다.
      setNote(await get(note.id))
    } catch (caught: unknown) {
      reportApiError(caught)
      setError('즐겨찾기를 바꾸지 못했습니다. 다시 시도해 주세요.')
    } finally {
      setBusy(false)
    }
  }

  /** 삭제는 되돌릴 수 없다. 확인 단계를 거친 뒤에만 여기 도달한다. */
  async function handleDelete() {
    if (!note) return
    setBusy(true)
    setError(null)
    try {
      await remove(note.id)
      // 지운 자료의 상세에 남아 있으면 다음 조회가 404다. 목록으로 보내고 기록도 대체한다.
      navigate(categoryPath(note.category), { replace: true })
    } catch (caught: unknown) {
      reportApiError(caught)
      setError(
        caught instanceof ApiError
          ? caught.message
          : '자료를 삭제하지 못했습니다. 다시 시도해 주세요.',
      )
    } finally {
      setBusy(false)
    }
  }

  // 자료를 아직 못 읽었으면 갈래를 모른다. 기본 탭으로 보낸다.
  const backTo = note ? categoryPath(note.category) : NOTES_PATH
  const mine =
    note !== null && state.kind === 'active' && canEdit(note, state.user)

  return (
    <article>
      {/* 목록으로 돌아가는 진입점. 뒤로가기만 믿지 않는다. */}
      <Link
        to={backTo}
        className="text-sm text-muted-foreground transition-colors hover:text-foreground"
      >
        ← {note ? CATEGORY_LABEL[note.category] : '자료게시판'}
      </Link>

      {status === 'loading' && (
        <p className="mt-8 text-sm text-muted-foreground">불러오는 중</p>
      )}

      {status === 'notFound' && (
        <p role="alert" className="mt-8 text-sm text-muted-foreground">
          자료를 찾을 수 없습니다. 삭제되었거나 주소가 잘못되었습니다.
        </p>
      )}

      {status === 'failed' && (
        <p role="alert" className="mt-8 text-sm text-muted-foreground">
          {noteErrorText(isInactive(session))}
        </p>
      )}

      {status === 'loaded' && note && (
        <>
          <div className="mt-6 flex items-start justify-between gap-4">
            <div className="min-w-0">
              <Badge variant="outline">{CATEGORY_LABEL[note.category]}</Badge>
              <h1 className="mt-2 text-2xl font-semibold tracking-tight">
                {note.title}
              </h1>
            </div>

            <div className="flex shrink-0 gap-2">
              <Button
                variant="outline"
                size="sm"
                disabled={busy}
                aria-pressed={note.bookmarked}
                onClick={toggleBookmark}
              >
                <Star
                  className={cn('size-4', note.bookmarked && 'fill-current')}
                  aria-hidden="true"
                />
                {note.bookmarked ? '즐겨찾기 해제' : '즐겨찾기'}
              </Button>

              {/*
               * **본인 것에만 보인다** (spec §2-1-3 MUST, `ADMIN`은 전체). 노출 제어일
               * 뿐 권한 통제가 아니다 (§3-1-7) — 서버가 같은 조건으로 다시 막는다.
               */}
              {mine && (
                <>
                  <Button variant="outline" size="sm" asChild>
                    <Link to={`/notes/${note.id}/edit`}>수정</Link>
                  </Button>

                  <AlertDialog>
                    <AlertDialogTrigger asChild>
                      {/*
                       * **되돌릴 수 없는 조작은 무게가 달라야 한다** — 공지 삭제와 같은
                       * 이유다 (#211). 이 팔레트에서 `destructive`는 붉은색이 아니라
                       * 회색이라 무채색 규칙을 깨지 않는다.
                       */}
                      <Button variant="destructive" size="sm" disabled={busy}>
                        삭제
                      </Button>
                    </AlertDialogTrigger>
                    <AlertDialogContent>
                      <AlertDialogHeader>
                        <AlertDialogTitle>자료를 삭제할까요?</AlertDialogTitle>
                        <AlertDialogDescription>
                          「{note.title}」을(를) 삭제합니다. 첨부파일{' '}
                          {note.files.length}개와 다른 사람의 즐겨찾기도 함께
                          사라지며 되돌릴 수 없습니다.
                        </AlertDialogDescription>
                      </AlertDialogHeader>
                      <AlertDialogFooter>
                        <AlertDialogCancel>취소</AlertDialogCancel>
                        <AlertDialogAction onClick={handleDelete}>
                          삭제
                        </AlertDialogAction>
                      </AlertDialogFooter>
                    </AlertDialogContent>
                  </AlertDialog>
                </>
              )}
            </div>
          </div>

          {/* 메타데이터. 목록의 열과 같은 낱말을 쓴다 — 두 화면이 갈리면 같은 값이 달라 보인다. */}
          <dl className="mt-8 grid grid-cols-[6rem_1fr] gap-y-3 border-t border-border pt-6 text-sm sm:grid-cols-[6rem_1fr_6rem_1fr]">
            <dt className="text-muted-foreground">과목</dt>
            <dd>{note.subjectName}</dd>
            <dt className="text-muted-foreground">교수</dt>
            <dd>{note.professor ?? '—'}</dd>
            <dt className="text-muted-foreground">학기</dt>
            <dd>
              {note.year}년 {SEMESTER_LABEL[note.semester]}
              {note.examType && ` · ${EXAM_TYPE_LABEL[note.examType]}고사`}
            </dd>
            {/* 업로더 이름은 절대 비지 않는다 — 회원이 제거되면 "탈퇴한 회원"이다 (§3-2-2). */}
            <dt className="text-muted-foreground">업로더</dt>
            <dd>{note.uploader.name}</dd>
            <dt className="text-muted-foreground">등록일</dt>
            <dd>{formatDate(note.createdAt)}</dd>
            <dt className="text-muted-foreground">수정일</dt>
            <dd>{formatDate(note.updatedAt)}</dd>
          </dl>

          {error && (
            <p role="alert" className="mt-6 text-sm text-muted-foreground">
              {error}
            </p>
          )}

          <h2 className="mt-10 text-sm font-medium">
            첨부파일 {note.files.length}개
          </h2>
          <ul className="mt-3 divide-y divide-border border-y border-border">
            {note.files.map((file) => (
              <li
                key={file.id}
                className="flex items-center justify-between gap-4 py-3"
              >
                <span className="min-w-0 truncate text-sm">
                  {file.originalName}
                </span>
                <span className="flex shrink-0 items-center gap-3">
                  <span className="text-sm tabular-nums text-muted-foreground">
                    {formatSize(file.sizeBytes)}
                  </span>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={busy}
                    onClick={() => handleDownload(file.id)}
                  >
                    <Download className="size-4" aria-hidden="true" />
                    받기
                  </Button>
                </span>
              </li>
            ))}
          </ul>
        </>
      )}
    </article>
  )
}
