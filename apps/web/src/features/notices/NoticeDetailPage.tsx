import { ThumbsUp } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '@/api/client'
import { get, type Notice, remove, setNoticeLike } from '@/api/notices'
import { useSession } from '@/auth/session'
import { useLiveAlert } from '@/components/live-alert/LiveAlertProvider'
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
import { cn } from '@/lib/utils'

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
  const navigate = useNavigate()
  const { state, reportApiError } = useSession()
  const alert = useLiveAlert()
  const isAdmin = state.kind === 'active' && state.user.role === 'ADMIN'
  const [notice, setNotice] = useState<Notice | null>(null)
  const [status, setStatus] = useState<Status>('loading')
  const [deleting, setDeleting] = useState(false)
  // 진행 중에 또 누르면 POST와 DELETE가 순서를 바꿔 도착해 서버 상태와 화면이 갈린다.
  const [liking, setLiking] = useState(false)

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

  /**
   * 좋아요·취소. 서버가 준 `likedByMe`를 보고 방향을 정한다 (계약 §3-2-5 — 토글이 아니다).
   *
   * **낙관적으로 먼저 반영한다.** 응답이 `204`라 최신 개수가 오지 않아 화면이 직접 센다 —
   * 다시 `GET`하면 왕복이 하나 더 늘고, 그 사이 숫자가 멈춰 있어 누른 것 같지가 않다.
   * 실패하면 누르기 전 값으로 되돌린다.
   */
  async function toggleLike() {
    if (!notice) return
    const next = !notice.likedByMe
    const before = notice
    setLiking(true)
    setNotice({
      ...notice,
      likedByMe: next,
      likeCount: notice.likeCount + (next ? 1 : -1),
    })
    try {
      await setNoticeLike(notice.id, next)
    } catch (error: unknown) {
      setNotice((current) => (current?.id === before.id ? before : current))
      if (!reportApiError(error)) {
        alert.error('좋아요를 바꾸지 못했습니다. 다시 시도해 주세요.')
      }
    } finally {
      setLiking(false)
    }
  }

  /** 삭제는 되돌릴 수 없다. 확인 단계를 거친 뒤에만 여기 도달한다. */
  async function handleDelete() {
    setDeleting(true)
    try {
      await remove(Number(id))
      alert.success('공지를 삭제했습니다.', { persistOnNavigation: true })
      // 지운 글의 상세에 남아 있으면 다음 조회가 404다. 목록으로 보내고 기록도 대체한다.
      navigate('/notices', { replace: true })
    } catch (error: unknown) {
      if (!reportApiError(error)) {
        alert.error('공지를 삭제하지 못했습니다. 다시 시도해 주세요.')
      }
    } finally {
      setDeleting(false)
    }
  }

  return (
    /*
     * **폭을 제한하지 않는다** — `AppLayout`의 `<main>`이 주는 1152px을 그대로 쓴다.
     * 제목·메타·본문이 같은 폭이라는 것은 그대로다. 본문만 좁히면 제목이 붕 뜬다.
     *
     * 한때 `max-w-2xl`(672px)로 좁혔다. 근거는 줄 길이 계산이었다 — 1152px이면 한글이
     * 한 줄에 82자까지 들어가 눈이 다음 줄 시작을 못 찾는다는 것. **계산은 맞지만 그
     * 82자는 아주 긴 공지에서만 나오는 최악값이었다.** 실제 공지는 대부분 몇 줄이라
     * 672px에서는 글이 왼쪽에 쪼그라들고 오른쪽 480px이 빈 채 남는다. 화면을 직접 보고
     * 답답하다는 판단이 나와 되돌렸다 (2026-08-11). 규칙은 `apps/web/README.md`에 있다.
     */
    <article className="min-h-[32rem]" data-detail-surface="notice">
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
          <h1
            className="mt-6 line-clamp-2 break-all text-3xl font-semibold tracking-tight"
            title={notice.title}
          >
            {notice.title}
          </h1>
          <div className="mt-2 flex flex-wrap items-center justify-between gap-4">
            <time
              dateTime={notice.createdAt}
              className="block text-sm text-muted-foreground"
            >
              {formatDateTime(notice.createdAt)}
            </time>

            <div className="flex shrink-0 flex-wrap items-center gap-2">
              {/*
               * **좋아요는 부원도 누른다** (계약 §3-2-5 — `ACTIVE`·`INACTIVE`). 무채색
               * 팔레트라 색으로 구분할 수 없어 **채움과 비움으로 가른다** — 자료 즐겨찾기의
               * 별과 같은 방식이다 (§2-1-5).
               *
               * **따봉이다.** 하트는 빨강을 잃으면 채움/비움이 잘 안 읽히고, 별은 이미
               * 즐겨찾기가 쓰고 있어 "담기"와 "반응하기"가 구분되지 않는다. 결정 24 D4가
               * 적은 이 기능의 의미도 애정이 아니라 동의다 — "봤다·동의한다·중요하게 여긴다".
               */}
              <Button
                variant="outline"
                size="sm"
                disabled={liking}
                aria-pressed={notice.likedByMe}
                onClick={toggleLike}
              >
                <ThumbsUp
                  className={cn('size-4', notice.likedByMe && 'fill-current')}
                  aria-hidden="true"
                />
                좋아요 {notice.likeCount}
              </Button>

              {/*
               * 수정·삭제 진입점은 ADMIN에게만 보인다. **노출 제어일 뿐 권한 통제가 아니다**
               * (spec §3-1-7). 실제 통제는 `/admin/**` 라우트 가드와 서버다 — 여기를 뚫어도
               * 폼에 도달하지 못하고, 도달해도 서버가 거부한다.
               */}
              {isAdmin && (
                <>
                  <Button variant="outline" size="sm" asChild>
                    <Link to={`/admin/notices/${notice.id}/edit`}>수정</Link>
                  </Button>

                  <AlertDialog>
                    <AlertDialogTrigger asChild>
                      {/*
                       * **되돌릴 수 없는 조작은 무게가 달라야 한다.** `수정`과 같은
                       * `outline`이면 손이 미끄러진 곳이 어디였는지 알 수 없다 — 회원 관리의
                       * `제거`를 메뉴 안에서 가른 것과 같은 이유다 (#99).
                       *
                       * 이 팔레트에서 `destructive`는 붉은색이 아니라 회색(`#525252`)이라
                       * 무채색 규칙을 깨지 않는다.
                       */}
                      <Button
                        variant="destructive"
                        size="sm"
                        disabled={deleting}
                      >
                        삭제
                      </Button>
                    </AlertDialogTrigger>
                    <AlertDialogContent>
                      <AlertDialogHeader>
                        <AlertDialogTitle>공지를 삭제할까요?</AlertDialogTitle>
                        {/* 무엇을 지우는지 제목으로 보여준다. "이 항목"만으로는 확인이 안 된다. */}
                        <AlertDialogDescription asChild>
                          <div>
                            <span className="block">
                              다음 공지를 삭제합니다.
                            </span>
                            <span
                              className="mt-1 block truncate font-medium text-foreground"
                              title={notice.title}
                            >
                              「{notice.title}」
                            </span>
                            <span className="mt-1 block">
                              되돌릴 수 없습니다.
                            </span>
                          </div>
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

          {/* 본문은 평문이다. 리치 텍스트는 범위 밖이고, 줄바꿈만 보존한다. */}
          <div className="mt-8 whitespace-pre-wrap border-t border-border pt-8 leading-7">
            {notice.content}
          </div>
        </>
      )}
    </article>
  )
}
