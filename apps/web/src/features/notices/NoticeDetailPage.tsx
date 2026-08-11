import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '@/api/client'
import { get, type Notice, remove } from '@/api/notices'
import { useSession } from '@/auth/session'
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
import { Button } from '@/components/ui/button'

/** 상세에서는 분까지 보여준다. 목록은 날짜까지만 쓴다. */
function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/**
 * 읽는 글의 폭. **랜딩 소개·후원이 이미 쓰는 값이다** (`max-w-2xl` = 672px) — 새 숫자를
 * 만들지 않는다.
 *
 * 한글은 전각이라 글자 폭이 폰트 크기와 비슷하다. 672px이면 16px 본문에서 42자, 14px
 * 본문에서 48자다. 읽기 좋은 줄 길이(40~50자 안팎) 안에 든다. 1152px을 그대로 쓰면 82자가
 * 되어 **눈이 다음 줄 시작을 못 찾는다.**
 */
const READING = 'max-w-2xl'

type Status = 'loading' | 'loaded' | 'notFound' | 'failed'

export function NoticeDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { state, reportApiError } = useSession()
  const isAdmin = state.kind === 'active' && state.user.role === 'ADMIN'
  const [notice, setNotice] = useState<Notice | null>(null)
  const [status, setStatus] = useState<Status>('loading')
  const [deleting, setDeleting] = useState(false)
  const [deleteFailed, setDeleteFailed] = useState(false)

  useEffect(() => {
    let alive = true
    setStatus('loading')
    get(Number(id))
      .then((result) => {
        if (!alive) return
        setNotice(result)
        setStatus('loaded')
      })
      .catch((error: unknown) => {
        if (!alive) return
        // #36 계약 — 403 PENDING_APPROVAL이면 가드가 대기 화면으로 되돌린다.
        reportApiError(error)
        // 없는 id로 들어와도 화면이 깨지지 않고 안내가 나와야 한다.
        setStatus(
          error instanceof ApiError && error.code === 'NOT_FOUND'
            ? 'notFound'
            : 'failed',
        )
      })
    return () => {
      alive = false
    }
  }, [id, reportApiError])

  /** 삭제는 되돌릴 수 없다. 확인 단계를 거친 뒤에만 여기 도달한다. */
  async function handleDelete() {
    setDeleting(true)
    setDeleteFailed(false)
    try {
      await remove(Number(id))
      // 지운 글의 상세에 남아 있으면 다음 조회가 404다. 목록으로 보내고 기록도 대체한다.
      navigate('/notices', { replace: true })
    } catch (error: unknown) {
      reportApiError(error)
      setDeleteFailed(true)
    } finally {
      setDeleting(false)
    }
  }

  return (
    // 제목·메타·본문이 같은 폭을 쓴다. 본문만 좁히면 제목이 붕 뜬다.
    <article className={READING}>
      {/* 목록으로 돌아가는 진입점. 뒤로가기만 믿지 않는다. */}
      <Link
        to="/notices"
        className="text-sm text-muted-foreground transition-colors hover:text-foreground"
      >
        ← 공지 목록
      </Link>

      {status === 'loading' && (
        <p className="mt-8 text-sm text-muted-foreground">불러오는 중</p>
      )}

      {status === 'notFound' && (
        <p role="alert" className="mt-8 text-sm text-muted-foreground">
          공지를 찾을 수 없습니다. 삭제되었거나 주소가 잘못되었습니다.
        </p>
      )}

      {status === 'failed' && (
        <p role="alert" className="mt-8 text-sm text-muted-foreground">
          공지를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
        </p>
      )}

      {status === 'loaded' && notice && (
        <>
          <h1 className="mt-6 text-2xl font-semibold tracking-tight">
            {notice.title}
          </h1>
          <div className="mt-2 flex items-center justify-between gap-4">
            <time
              dateTime={notice.createdAt}
              className="block text-sm text-muted-foreground"
            >
              {formatDateTime(notice.createdAt)}
            </time>

            {/*
             * 수정·삭제 진입점은 ADMIN에게만 보인다. **노출 제어일 뿐 권한 통제가 아니다**
             * (spec §3-1-7). 실제 통제는 `/admin/**` 라우트 가드와 서버다 — 여기를 뚫어도
             * 폼에 도달하지 못하고, 도달해도 서버가 거부한다.
             */}
            {isAdmin && (
              <div className="flex shrink-0 gap-2">
                <Button variant="outline" size="sm" asChild>
                  <Link to={`/admin/notices/${notice.id}/edit`}>수정</Link>
                </Button>

                <AlertDialog>
                  <AlertDialogTrigger asChild>
                    <Button variant="outline" size="sm" disabled={deleting}>
                      삭제
                    </Button>
                  </AlertDialogTrigger>
                  <AlertDialogContent>
                    <AlertDialogHeader>
                      <AlertDialogTitle>공지를 삭제할까요?</AlertDialogTitle>
                      {/* 무엇을 지우는지 제목으로 보여준다. "이 항목"만으로는 확인이 안 된다. */}
                      <AlertDialogDescription>
                        「{notice.title}」을(를) 삭제합니다. 되돌릴 수 없습니다.
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
              </div>
            )}
          </div>

          {deleteFailed && (
            <p role="alert" className="mt-4 text-sm text-muted-foreground">
              공지를 삭제하지 못했습니다. 다시 시도해 주세요.
            </p>
          )}
          {/* 본문은 평문이다. 리치 텍스트는 범위 밖이고, 줄바꿈만 보존한다. */}
          <div className="mt-8 whitespace-pre-wrap border-t border-border pt-8 text-sm leading-7">
            {notice.content}
          </div>
        </>
      )}
    </article>
  )
}
