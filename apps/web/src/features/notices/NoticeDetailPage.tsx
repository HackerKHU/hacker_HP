import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError } from '@/api/client'
import { get, type Notice } from '@/api/notices'
import { useSession } from '@/auth/session'

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

type Status = 'loading' | 'loaded' | 'notFound' | 'failed'

export function NoticeDetailPage() {
  const { id } = useParams<{ id: string }>()
  const { reportApiError } = useSession()
  const [notice, setNotice] = useState<Notice | null>(null)
  const [status, setStatus] = useState<Status>('loading')

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

  return (
    <article>
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
          <time
            dateTime={notice.createdAt}
            className="mt-2 block text-sm text-muted-foreground"
          >
            {formatDateTime(notice.createdAt)}
          </time>
          {/* 본문은 평문이다. 리치 텍스트는 범위 밖이고, 줄바꿈만 보존한다. */}
          <div className="mt-8 whitespace-pre-wrap border-t border-border pt-8 text-sm leading-7">
            {notice.content}
          </div>
        </>
      )}
    </article>
  )
}
