import { type FormEvent, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '@/api/client'
import {
  CONTENT_MAX,
  countCodePoints,
  create,
  get,
  TITLE_MAX,
  update,
} from '@/api/posts'
import { useSession } from '@/auth/session'
import { useLiveAlert } from '@/components/live-alert/LiveAlertProvider'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'

type Loading = 'loading' | 'ready' | 'notFound' | 'forbidden' | 'failed'

/**
 * 게시글 등록·수정 (spec §2-1-8).
 *
 * **승인된 `ACTIVE`·`INACTIVE` 부원은 누구나 쓴다** (spec §3-1-3 매트릭스와
 * INACTIVE 예외 규칙). 진입은 라우트 가드가 막고 여기서 권한을 다시 판단하지 않는다
 * (§3-1-7).
 *
 * 제목·본문 검증과 평문 원문 보존 계약이 같아 한 폼을 쓴다. 수정은 기존 글을 먼저 읽고
 * 작성자 id를 세션 사용자 id와 비교한 뒤에만 폼을 그린다. 관리자 역할도 남의 글을 고칠
 * 권한을 만들지 않는다 (§3-2-5, 결정 21).
 */
export function PostFormPage() {
  const { id } = useParams<{ id: string }>()
  const editing = id !== undefined
  const navigate = useNavigate()
  const { state, reportApiError } = useSession()
  const alert = useLiveAlert()

  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [loading, setLoading] = useState<Loading>(editing ? 'loading' : 'ready')
  const [saving, setSaving] = useState(false)
  const savingRef = useRef(false)
  const [fieldErrors, setFieldErrors] = useState<{
    title?: string
    content?: string
  }>({})

  const viewerId = state.kind === 'active' ? state.user.id : null

  useEffect(() => {
    if (!editing || viewerId === null) return
    let alive = true
    setLoading('loading')
    get(Number(id))
      .then((post) => {
        if (!alive) return
        if (post.author.id !== viewerId) {
          /* 남의 제목·본문을 입력 가능한 폼에 잠깐이라도 넣지 않는다. */
          setLoading('forbidden')
          return
        }
        setTitle(post.title)
        setContent(post.content)
        setLoading('ready')
      })
      .catch((caught: unknown) => {
        if (!alive) return
        reportApiError(caught)
        setLoading(
          caught instanceof ApiError && caught.code === 'NOT_FOUND'
            ? 'notFound'
            : 'failed',
        )
      })
    return () => {
      alive = false
    }
  }, [editing, id, reportApiError, viewerId])

  /*
   * **코드 포인트로 센다.** 서버가 `codePointCount`로 재므로(`CodePointSizeValidator`)
   * `String.length`(UTF-16 단위)로 세면 이모지 하나가 2로 잡혀 **서버는 받아주는 글을
   * 화면이 먼저 막는다.** 남은 양 표시와 제출 검사가 같은 함수를 쓴다.
   *
   * **재는 값이 곧 보내는 값이다.** 검증(`@CodePointSize`)은 서버가 다듬기 전의 원문에
   * 걸리므로, 화면이 다듬은 뒤 세면 상한 언저리에서 판정이 갈린다.
   */
  const titleCount = countCodePoints(title)
  const contentCount = countCodePoints(content)
  const tooLong = titleCount > TITLE_MAX || contentCount > CONTENT_MAX

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (savingRef.current) return

    /*
     * 계약이 요구하는 것은 공백이 아닐 것과 길이뿐이다 (§2-1-8). 서버가 거부할 요청을
     * 굳이 보내지 않는다 — 같은 규칙을 앞당겨 적용하는 것이지 서버 검증을 대신하지 않는다.
     */
    const emptyErrors = {
      ...(title.trim() === '' ? { title: '제목을 입력해주세요.' } : {}),
      ...(content.trim() === '' ? { content: '내용을 입력해주세요.' } : {}),
    }
    if (Object.keys(emptyErrors).length > 0) {
      setFieldErrors(emptyErrors)
      return
    }
    if (tooLong) {
      setFieldErrors({
        ...(titleCount > TITLE_MAX
          ? { title: '제목 글자 수 상한을 넘었습니다.' }
          : {}),
        ...(contentCount > CONTENT_MAX
          ? { content: '내용 글자 수 상한을 넘었습니다.' }
          : {}),
      })
      return
    }

    savingRef.current = true
    setSaving(true)
    setFieldErrors({})
    try {
      /*
       * **원문 그대로 보낸다.** 서버는 본문을 다듬지 않고 저장한다 (계약 §3-2-5 MUST,
       * `PostService` — "본문은 trim하지 않는다"). 화면이 앞뒤를 털면 **들여쓴 코드나
       * 일부러 띄운 줄이 조용히 사라진다.**
       *
       * 제목은 서버가 다듬지만 여기서 미리 털지 않는다 — 다듬는 자리가 둘이면 어느 쪽이
       * 진짜인지 갈리고, 길이 검증도 원문에 걸린다.
       *
       * 공백뿐인지는 아래 제출 검사가 `trim`으로 이미 걸렀다. **거르는 것과 보내는 것은
       * 다른 일이다.**
       */
      const saved = editing
        ? await update(Number(id), { title, content })
        : await create({ title, content })
      alert.success(
        editing ? '게시글을 수정했습니다.' : '게시글을 등록했습니다.',
        { persistOnNavigation: true },
      )
      // 쓴 글을 볼 수 있는 곳으로 보낸다. `replace`로 뒤로가기가 폼에 돌아오지 않게 한다.
      navigate(`/posts/${saved.id}`, { replace: true })
    } catch (caught: unknown) {
      if (reportApiError(caught)) return
      /*
       * **실패했는데 성공한 것처럼 보이면 안 된다.** 이동하지 않고 입력을 그대로 둔 채
       * 서버가 준 메시지를 보여준다 — 무엇을 고쳐야 하는지는 서버가 안다.
       */
      if (editing) {
        if (
          caught instanceof ApiError &&
          caught.status >= 400 &&
          caught.status < 500
        ) {
          alert.error(`게시글을 수정하지 못했습니다. ${caught.message}`)
        } else {
          const detail = caught instanceof ApiError ? ` ${caught.message}` : ''
          alert.error(
            `게시글 수정 결과를 확인할 수 없습니다.${detail} 수정이 반영되었을 수 있으니 게시글 상세에서 확인해 주세요.`,
          )
        }
      } else {
        alert.error(
          caught instanceof ApiError
            ? caught.message
            : '글을 올리지 못했습니다. 잠시 후 다시 시도해 주세요.',
        )
      }
    } finally {
      savingRef.current = false
      setSaving(false)
    }
  }

  const backTo = editing ? `/posts/${id}` : '/posts'

  return (
    <section className="min-h-[32rem]" data-detail-surface="post-form">
      <Link
        to={backTo}
        className="text-sm text-muted-foreground transition-colors hover:text-foreground"
      >
        ← {editing ? '게시글로' : '자유게시판'}
      </Link>

      <h1 className="mt-6 text-3xl font-semibold tracking-tight">
        {editing ? '게시글 수정' : '글쓰기'}
      </h1>

      {loading === 'loading' && (
        <p className="mt-8 text-sm text-muted-foreground">불러오는 중</p>
      )}

      {loading === 'notFound' && (
        <p role="alert" className="mt-8 text-sm text-muted-foreground">
          게시글을 찾을 수 없습니다. 삭제되었거나 주소가 잘못되었습니다.
        </p>
      )}

      {loading === 'forbidden' && (
        <p role="alert" className="mt-8 text-sm text-muted-foreground">
          본인이 쓴 게시글만 수정할 수 있습니다.
        </p>
      )}

      {loading === 'failed' && (
        <p role="alert" className="mt-8 text-sm text-muted-foreground">
          게시글을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
        </p>
      )}

      {loading === 'ready' && (
        <form onSubmit={handleSubmit} className="mt-8 space-y-6">
          <div className="space-y-2">
            <div className="flex items-baseline justify-between">
              <Label htmlFor="post-title">제목</Label>
              {/* 상한을 눌러 막기만 하면 왜 안 써지는지 알 수 없다. 남은 양을 보여준다. */}
              <span className="text-xs tabular-nums text-muted-foreground">
                {titleCount}/{TITLE_MAX}
              </span>
            </div>
            {/*
             * `maxLength`를 걸지 않는다. 그 속성은 **UTF-16 단위로 자르므로** 서버의 코드
             * 포인트 상한과 어긋난다 — 이모지가 섞이면 서버가 받아줄 글을 브라우저가 먼저
             * 막는다. 세는 것과 막는 것 모두 위 `countCodePoints`가 맡는다.
             */}
            <Input
              id="post-title"
              value={title}
              onChange={(event) => {
                setTitle(event.target.value)
                setFieldErrors((current) => ({ ...current, title: undefined }))
              }}
              placeholder="제목을 입력하세요"
              aria-invalid={
                fieldErrors.title !== undefined || titleCount > TITLE_MAX
              }
              aria-describedby={
                fieldErrors.title ? 'post-title-error' : undefined
              }
            />
          </div>

          <div className="space-y-2">
            <div className="flex items-baseline justify-between">
              <Label htmlFor="post-content">내용</Label>
              <span className="text-xs tabular-nums text-muted-foreground">
                {contentCount.toLocaleString('ko-KR')}/
                {CONTENT_MAX.toLocaleString('ko-KR')}
              </span>
            </div>
            {/*
             * 평문 textarea 하나다. **마크다운·리치텍스트를 붙이지 않는다** (§2-1-8 MUST) —
             * 상세 화면도 `whitespace-pre-wrap`으로 줄바꿈만 보존해 그린다.
             */}
            <Textarea
              id="post-content"
              value={content}
              onChange={(event) => {
                setContent(event.target.value)
                setFieldErrors((current) => ({
                  ...current,
                  content: undefined,
                }))
              }}
              placeholder="내용을 입력하세요"
              className="min-h-64"
              aria-invalid={
                fieldErrors.content !== undefined || contentCount > CONTENT_MAX
              }
              aria-describedby={
                fieldErrors.content ? 'post-content-error' : undefined
              }
            />
          </div>

          <div
            className="min-h-12 space-y-1"
            data-form-feedback-slot="true"
            role={
              Object.values(fieldErrors).some(Boolean) ? 'alert' : undefined
            }
          >
            {fieldErrors.title && (
              <p
                id="post-title-error"
                className="text-sm text-muted-foreground"
              >
                {fieldErrors.title}
              </p>
            )}
            {fieldErrors.content && (
              <p
                id="post-content-error"
                className="text-sm text-muted-foreground"
              >
                {fieldErrors.content}
              </p>
            )}
          </div>

          {/* 오른쪽 정렬 + 주 동작이 맨 끝 (`apps/web/README.md` "폼 버튼"). */}
          <div className="flex justify-end gap-2">
            <Button type="button" variant="outline" asChild>
              <Link to={backTo}>취소</Link>
            </Button>
            <Button type="submit" disabled={saving || tooLong}>
              {saving ? '저장 중' : editing ? '수정' : '저장'}
            </Button>
          </div>
        </form>
      )}
    </section>
  )
}
