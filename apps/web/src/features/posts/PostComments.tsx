import { type FormEvent, useEffect, useState } from 'react'
import { ApiError } from '@/api/client'
import {
  COMMENT_CONTENT_MAX,
  countCodePoints,
  createComment,
  comments as listComments,
  type PostComment,
  removeComment,
  updateComment,
} from '@/api/posts'
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
import { Textarea } from '@/components/ui/textarea'
import { formatDateTime } from './format'

type Status = 'loading' | 'loaded' | 'failed'

/**
 * 게시글 상세의 댓글 (spec §3-2-5, 3-3 결정 23).
 *
 * **정렬도 페이지네이션도 화면이 정하지 않는다.** 서버가 오래된순(`created_at ASC, id ASC`)
 * 배열로 내려주고 페이지가 없다 — 대화를 읽는 순서라 게시글 목록(최신순)과 반대다.
 * 받은 순서를 그대로 그린다.
 *
 * **본문을 HTML로 그리지 않는다** (MUST) — `whitespace-pre-wrap`으로 줄바꿈만 살린다.
 * 게시글 상세와 같은 판단이고, 전 부원이 쓰는 자리라 더 그렇다.
 *
 * 수정은 작성자 본인만, 삭제는 활성 관리자 또는 작성자 본인이다 — **관리자도 남의 댓글은
 * 고칠 수 없다** (결정 23 D2). 노출 제어일 뿐 권한 통제가 아니며 서버가 다시 막는다 (§3-1-7).
 */
export function PostComments({ postId }: { postId: number }) {
  const { state, reportApiError } = useSession()
  const alert = useLiveAlert()

  const [comments, setComments] = useState<PostComment[]>([])
  const [status, setStatus] = useState<Status>('loading')
  // 재조회 트리거. 등록·수정·삭제 뒤에 값을 올리면 아래 effect가 다시 돈다.
  const [reloadKey, setReloadKey] = useState(0)

  const [draft, setDraft] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [draftError, setDraftError] = useState<string | undefined>(undefined)

  // 한 번에 하나만 고친다. 여러 줄을 동시에 열면 어느 것을 저장하는지 흐려진다.
  const [editingId, setEditingId] = useState<number | null>(null)
  const [editDraft, setEditDraft] = useState('')
  const [saving, setSaving] = useState(false)
  const [deletingId, setDeletingId] = useState<number | null>(null)

  /**
   * **댓글을 조회하는 유일한 경로다.** 등록·수정·삭제 뒤에도 `reloadKey`를 올려 이 effect를
   * 다시 태운다 — 명령형으로 따로 부르면 그 요청은 cleanup이 취소하지 못한다.
   */
  // biome-ignore lint/correctness/useExhaustiveDependencies: reloadKey는 본문에서 읽지 않고 재조회 트리거로만 쓴다.
  useEffect(() => {
    let alive = true
    setStatus('loading')
    listComments(postId)
      .then((result) => {
        if (!alive) return
        // **서버 순서 그대로다** — 여기서 다시 정렬하면 서버 정렬을 덮어쓴다.
        setComments(result)
        setStatus('loaded')
      })
      .catch((caught: unknown) => {
        if (!alive) return
        // #36 계약 — 403 PENDING_APPROVAL이면 가드가 대기 화면으로 되돌린다.
        reportApiError(caught)
        setStatus('failed')
      })
    return () => {
      alive = false
    }
  }, [postId, reloadKey, reportApiError])

  const viewer = state.kind === 'active' ? state.user : null

  /** 작성자 본인만. **관리자 예외가 없다** (결정 23 D2 — 게시글 수정과 같다). */
  function canEdit(comment: PostComment): boolean {
    return (
      viewer !== null &&
      comment.author.id !== null &&
      comment.author.id === viewer.id
    )
  }

  /** 활성 관리자 또는 작성자 본인 (계약 §3-2-5). */
  function canDelete(comment: PostComment): boolean {
    if (viewer === null) return false
    if (viewer.role === 'ADMIN' && viewer.status === 'ACTIVE') return true
    return comment.author.id !== null && comment.author.id === viewer.id
  }

  /*
   * **코드 포인트로 센다.** 서버가 `codePointCount`로 재므로 `String.length`(UTF-16)로 세면
   * 이모지 하나가 2로 잡혀 서버가 받아줄 글을 화면이 먼저 막는다 — 게시글 폼과 같다.
   */
  const draftCount = countCodePoints(draft)
  const draftTooLong = draftCount > COMMENT_CONTENT_MAX
  const editCount = countCodePoints(editDraft)
  const editTooLong = editCount > COMMENT_CONTENT_MAX

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (submitting) return
    if (draft.trim() === '') {
      setDraftError('내용을 입력해주세요.')
      return
    }
    if (draftTooLong) {
      setDraftError('내용 글자 수 상한을 넘었습니다.')
      return
    }

    setSubmitting(true)
    setDraftError(undefined)
    try {
      // **원문 그대로 보낸다** — 서버가 본문을 다듬지 않는다 (§3-2-5). 게시글과 같다.
      await createComment(postId, { content: draft })
      // 등록 성공이면 폼을 비우고 목록을 다시 부른다 — 내가 쓴 댓글이 끝에 붙는 것이 보여야 한다.
      setDraft('')
      setReloadKey((key) => key + 1)
      alert.success('댓글을 등록했습니다.')
    } catch (caught: unknown) {
      if (reportApiError(caught)) return
      alert.error(
        caught instanceof ApiError
          ? `댓글을 등록하지 못했습니다. ${caught.message}`
          : '댓글을 등록하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      )
    } finally {
      setSubmitting(false)
    }
  }

  function startEdit(comment: PostComment) {
    setEditingId(comment.id)
    setEditDraft(comment.content)
  }

  function cancelEdit() {
    setEditingId(null)
    setEditDraft('')
  }

  /** **통째로 교체다** (계약 §3-2-5) — 보낸 값이 그대로 새 본문이 된다. */
  async function handleSaveEdit(commentId: number) {
    if (saving) return
    if (editDraft.trim() === '' || editTooLong) return

    setSaving(true)
    try {
      await updateComment(postId, commentId, { content: editDraft })
      cancelEdit()
      setReloadKey((key) => key + 1)
      alert.success('댓글을 수정했습니다.')
    } catch (caught: unknown) {
      if (reportApiError(caught)) return
      alert.error(
        caught instanceof ApiError
          ? `댓글을 수정하지 못했습니다. ${caught.message}`
          : '댓글을 수정하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      )
    } finally {
      setSaving(false)
    }
  }

  async function handleDelete(commentId: number) {
    if (deletingId !== null) return
    setDeletingId(commentId)
    try {
      await removeComment(postId, commentId)
      // 고치던 댓글이 사라지면 열린 편집창도 닫는다.
      if (editingId === commentId) cancelEdit()
      setReloadKey((key) => key + 1)
      alert.success('댓글을 삭제했습니다.')
    } catch (caught: unknown) {
      if (reportApiError(caught)) return
      alert.error(
        caught instanceof ApiError
          ? `댓글을 삭제하지 못했습니다. ${caught.message}`
          : '댓글을 삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      )
    } finally {
      setDeletingId(null)
    }
  }

  return (
    <section
      className="mt-12 border-t border-border pt-8"
      data-comment-surface="post"
    >
      <h2 className="text-lg font-semibold tracking-tight">
        댓글 {status === 'loaded' && comments.length}
      </h2>

      {status === 'loading' && (
        <p className="mt-6 text-sm text-muted-foreground">불러오는 중</p>
      )}

      {status === 'failed' && (
        <div className="mt-6 space-y-4">
          <p role="alert" className="text-sm text-muted-foreground">
            댓글을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
          </p>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => setReloadKey((key) => key + 1)}
          >
            다시 시도
          </Button>
        </div>
      )}

      {status === 'loaded' && comments.length === 0 && (
        <p className="mt-6 text-sm text-muted-foreground">
          아직 댓글이 없습니다. 첫 댓글을 남겨보세요.
        </p>
      )}

      {status === 'loaded' && comments.length > 0 && (
        <ul className="mt-6">
          {comments.map((comment) => (
            <li
              key={comment.id}
              className="border-b border-border py-4 first:pt-0 last:border-b-0"
            >
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="flex flex-wrap items-center gap-3 text-sm text-muted-foreground">
                  {/* 작성자 이름은 절대 비지 않는다 — 제거되면 "탈퇴한 회원"이다 (§2-1-8). */}
                  <span>{comment.author.name}</span>
                  <span aria-hidden="true">·</span>
                  <time dateTime={comment.createdAt}>
                    {formatDateTime(comment.createdAt)}
                  </time>
                  {comment.updatedAt !== comment.createdAt && (
                    <>
                      <span aria-hidden="true">·</span>
                      <time dateTime={comment.updatedAt}>
                        수정됨 {formatDateTime(comment.updatedAt)}
                      </time>
                    </>
                  )}
                </div>

                {editingId !== comment.id &&
                  (canEdit(comment) || canDelete(comment)) && (
                    <div className="flex shrink-0 items-center gap-2">
                      {canEdit(comment) && (
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          onClick={() => startEdit(comment)}
                        >
                          수정
                        </Button>
                      )}
                      {canDelete(comment) && (
                        <AlertDialog>
                          <AlertDialogTrigger asChild>
                            {/*
                             * 되돌릴 수 없는 조작은 확인 단계를 거친다 — 게시글 삭제와
                             * 같은 패턴이다. 이 팔레트의 `destructive`는 회색이다.
                             */}
                            <Button
                              variant="destructive"
                              size="sm"
                              className="shrink-0"
                              disabled={deletingId === comment.id}
                            >
                              삭제
                            </Button>
                          </AlertDialogTrigger>
                          <AlertDialogContent>
                            <AlertDialogHeader>
                              <AlertDialogTitle>
                                댓글을 삭제할까요?
                              </AlertDialogTitle>
                              {/* 무엇을 지우는지 본문으로 보여준다. "이 댓글"만으로는 확인이 안 된다. */}
                              <AlertDialogDescription asChild>
                                <div>
                                  <span className="block">
                                    다음 댓글을 완전히 삭제합니다.
                                  </span>
                                  <span className="mt-1 block truncate font-medium text-foreground">
                                    「{comment.content}」
                                  </span>
                                  <span className="mt-1 block">
                                    되돌릴 수 없습니다.
                                  </span>
                                </div>
                              </AlertDialogDescription>
                            </AlertDialogHeader>
                            <AlertDialogFooter>
                              <AlertDialogCancel
                                disabled={deletingId === comment.id}
                              >
                                취소
                              </AlertDialogCancel>
                              <AlertDialogAction
                                variant="destructive"
                                disabled={deletingId === comment.id}
                                onClick={() => handleDelete(comment.id)}
                              >
                                삭제
                              </AlertDialogAction>
                            </AlertDialogFooter>
                          </AlertDialogContent>
                        </AlertDialog>
                      )}
                    </div>
                  )}
              </div>

              {editingId === comment.id ? (
                <div className="mt-3 space-y-2">
                  <Textarea
                    aria-label="댓글 수정"
                    value={editDraft}
                    onChange={(event) => setEditDraft(event.target.value)}
                    className="min-h-24"
                    aria-invalid={editTooLong}
                  />
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-xs tabular-nums text-muted-foreground">
                      {editCount.toLocaleString('ko-KR')}/
                      {COMMENT_CONTENT_MAX.toLocaleString('ko-KR')}
                    </span>
                    {/* 오른쪽 정렬 + 주 동작이 맨 끝 (`apps/web/README.md` "폼 버튼"). */}
                    <div className="flex gap-2">
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        disabled={saving}
                        onClick={cancelEdit}
                      >
                        취소
                      </Button>
                      <Button
                        type="button"
                        size="sm"
                        disabled={
                          saving || editTooLong || editDraft.trim() === ''
                        }
                        onClick={() => handleSaveEdit(comment.id)}
                      >
                        {saving ? '저장 중' : '저장'}
                      </Button>
                    </div>
                  </div>
                </div>
              ) : (
                /*
                 * **평문이다.** 중괄호 안의 문자열은 React가 텍스트 노드로 넣으므로
                 * `<script>`를 써도 글자 그대로 보인다. 게시글 본문과 같은 규칙이다.
                 */
                <p className="mt-3 whitespace-pre-wrap break-words text-sm leading-7">
                  {comment.content}
                </p>
              )}
            </li>
          ))}
        </ul>
      )}

      {/*
       * **입력 폼은 목록 아래다** (#352 D2) — 오래된순이라 마지막 댓글 바로 다음이 내가 쓸
       * 자리다. 위에 두면 읽던 흐름과 쓰는 자리가 반대로 놓인다.
       */}
      <form onSubmit={handleSubmit} className="mt-8 space-y-2">
        <div className="flex items-baseline justify-between">
          {/* 상한을 눌러 막기만 하면 왜 안 써지는지 알 수 없다. 남은 양을 보여준다. */}
          <span className="text-sm font-medium">댓글 쓰기</span>
          <span className="text-xs tabular-nums text-muted-foreground">
            {draftCount.toLocaleString('ko-KR')}/
            {COMMENT_CONTENT_MAX.toLocaleString('ko-KR')}
          </span>
        </div>
        {/*
         * `maxLength`를 걸지 않는다. 그 속성은 UTF-16 단위로 자르므로 서버의 코드 포인트
         * 상한과 어긋난다 — 게시글 폼과 같은 이유다.
         */}
        <Textarea
          aria-label="댓글 내용"
          value={draft}
          onChange={(event) => {
            setDraft(event.target.value)
            setDraftError(undefined)
          }}
          placeholder="댓글을 입력하세요"
          className="min-h-24"
          aria-invalid={draftError !== undefined || draftTooLong}
          aria-describedby={draftError ? 'comment-draft-error' : undefined}
        />
        <div className="min-h-6" role={draftError ? 'alert' : undefined}>
          {draftError && (
            <p
              id="comment-draft-error"
              className="text-sm text-muted-foreground"
            >
              {draftError}
            </p>
          )}
        </div>
        <div className="flex justify-end">
          <Button type="submit" disabled={submitting || draftTooLong}>
            {submitting ? '등록 중' : '등록'}
          </Button>
        </div>
      </form>
    </section>
  )
}
