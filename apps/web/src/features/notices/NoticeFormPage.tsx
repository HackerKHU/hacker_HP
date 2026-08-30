import { type FormEvent, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '@/api/client'
import { create, get, update } from '@/api/notices'
import { useSession } from '@/auth/session'
import { useLiveAlert } from '@/components/live-alert/LiveAlertProvider'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'

/**
 * 제목 상한. **스키마에서 온 값이다** — `notices.title`은 `varchar(200)`이다
 * (spec §3-2-2). 서버는 넘치면 자르지 않고 거부하므로 화면에서 미리 막고 드러낸다.
 */
const TITLE_MAX = 200

/** 불러오기 상태. 등록 화면은 불러올 것이 없어 곧바로 `ready`다. */
type Loading = 'loading' | 'ready' | 'notFound' | 'failed'

/**
 * 공지 작성·수정 화면. **한 컴포넌트가 두 라우트를 맡는다.**
 *
 * 폼도 제약도 저장 후 이동 규칙도 같고 다른 것은 "기존 값을 먼저 불러오는가"뿐이다.
 * 갈라두면 제목 상한 같은 규칙이 두 벌이 되고, 한쪽만 고치는 날이 온다.
 *
 * 진입 자체는 라우트 가드가 ADMIN으로 막는다. **여기서 권한을 다시 판단하지 않는다** —
 * 화면이 권한을 판단하기 시작하면 근거가 가드·서버·화면 셋으로 흩어진다 (spec §3-1-7).
 */
export function NoticeFormPage() {
  const { id } = useParams<{ id: string }>()
  const editing = id !== undefined
  const navigate = useNavigate()
  const { reportApiError } = useSession()
  const alert = useLiveAlert()

  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [loading, setLoading] = useState<Loading>(editing ? 'loading' : 'ready')
  const [saving, setSaving] = useState(false)
  const [fieldErrors, setFieldErrors] = useState<{
    title?: string
    content?: string
  }>({})

  useEffect(() => {
    if (!editing) return
    let alive = true
    setLoading('loading')
    get(Number(id))
      .then((notice) => {
        if (!alive) return
        setTitle(notice.title)
        setContent(notice.content)
        setLoading('ready')
      })
      .catch((caught: unknown) => {
        if (!alive) return
        // #36 계약 — 403 PENDING_APPROVAL이면 가드가 대기 화면으로 되돌린다.
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
  }, [editing, id, reportApiError])

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (saving) return

    /*
     * 두 값 모두 스키마가 `NOT NULL`이다. 공백만 넣은 것도 빈 값으로 본다 —
     * 서버가 거부할 요청을 굳이 보내지 않는다. 서버 검증을 대신하는 것이 아니라,
     * 같은 규칙을 먼저 적용해 사용자가 왕복을 덜 하게 하는 것이다.
     */
    const nextErrors = {
      ...(title.trim() === '' ? { title: '제목을 입력해주세요.' } : {}),
      ...(content.trim() === '' ? { content: '내용을 입력해주세요.' } : {}),
    }
    if (Object.keys(nextErrors).length > 0) {
      setFieldErrors(nextErrors)
      return
    }

    setSaving(true)
    setFieldErrors({})
    try {
      const body = { title: title.trim(), content: content.trim() }
      // 저장 성공 후에는 결과를 볼 수 있는 곳으로 보낸다. 등록은 새로 생긴 공지,
      // 수정은 고친 그 공지다. `replace`로 남겨 뒤로가기가 폼으로 돌아오지 않게 한다.
      const saved = editing
        ? await update(Number(id), body)
        : await create(body)
      alert.success(editing ? '공지를 수정했습니다.' : '공지를 등록했습니다.', {
        persistOnNavigation: true,
      })
      navigate(`/notices/${saved.id}`, { replace: true })
    } catch (caught: unknown) {
      if (reportApiError(caught)) return
      /*
       * **실패했는데 성공한 것처럼 보이면 안 된다.** 이동하지 않고 입력값을 그대로 둔 채
       * 서버가 준 메시지를 보여준다 — 무엇을 고쳐야 하는지는 서버가 안다 (계약 §3-2-7).
       */
      alert.error(
        caught instanceof ApiError
          ? caught.message
          : '저장하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      )
    } finally {
      setSaving(false)
    }
  }

  const backTo = editing ? `/notices/${id}` : '/notices'

  return (
    /*
     * **작성 폭을 공지 상세의 표시 폭에 맞춘다 — 그 근거는 그대로고 값만 바뀌었다.**
     *
     * 여기서 쓴 본문은 상세 화면에서 같은 폭으로 읽힌다. 작성 폭과 표시 폭이 다르면
     * **작성자가 보는 줄바꿈과 독자가 보는 줄바꿈이 달라진다** — 문단을 다듬어 놓고
     * 저장하면 다른 모양이 된다.
     *
     * 둘 다 `max-w-2xl`(672px)이었는데 상세를 전체폭으로 되돌리면서(2026-08-11) 여기도
     * 함께 풀었다. **이제 둘 다 `AppLayout`의 1152px이다** — 한쪽만 고치면 어긋난다.
     */
    <section className="min-h-[32rem]" data-detail-surface="notice-form">
      <Link
        to={backTo}
        className="text-sm text-muted-foreground transition-colors hover:text-foreground"
      >
        ← {editing ? '공지로' : '공지 목록'}
      </Link>

      <h1 className="mt-6 text-2xl font-semibold tracking-tight">
        {editing ? '공지 수정' : '새 공지'}
      </h1>

      {loading === 'loading' && (
        <p className="mt-8 text-sm text-muted-foreground">불러오는 중</p>
      )}

      {loading === 'notFound' && (
        <p role="alert" className="mt-8 text-sm text-muted-foreground">
          공지를 찾을 수 없습니다. 삭제되었거나 주소가 잘못되었습니다.
        </p>
      )}

      {loading === 'failed' && (
        <p role="alert" className="mt-8 text-sm text-muted-foreground">
          공지를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
        </p>
      )}

      {loading === 'ready' && (
        <form onSubmit={handleSubmit} className="mt-8 space-y-6">
          <div className="space-y-2">
            <div className="flex items-baseline justify-between">
              <Label htmlFor="notice-title">제목</Label>
              {/* 상한을 눌러 막기만 하면 왜 안 써지는지 알 수 없다. 남은 양을 보여준다. */}
              <span className="text-xs tabular-nums text-muted-foreground">
                {title.length}/{TITLE_MAX}
              </span>
            </div>
            <Input
              id="notice-title"
              value={title}
              maxLength={TITLE_MAX}
              onChange={(event) => {
                setTitle(event.target.value)
                setFieldErrors((current) => ({ ...current, title: undefined }))
              }}
              placeholder="공지 제목"
              aria-invalid={fieldErrors.title !== undefined}
              aria-describedby={
                fieldErrors.title ? 'notice-title-error' : undefined
              }
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor="notice-content">내용</Label>
            {/*
             * 평문 textarea 하나다. 리치 텍스트 에디터는 이 이슈의 제외 범위이고,
             * 상세 화면도 `whitespace-pre-wrap`으로 줄바꿈만 보존해 그린다.
             */}
            <Textarea
              id="notice-content"
              value={content}
              onChange={(event) => {
                setContent(event.target.value)
                setFieldErrors((current) => ({
                  ...current,
                  content: undefined,
                }))
              }}
              placeholder="공지 내용"
              className="min-h-64"
              aria-invalid={fieldErrors.content !== undefined}
              aria-describedby={
                fieldErrors.content ? 'notice-content-error' : undefined
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
                id="notice-title-error"
                className="text-sm text-muted-foreground"
              >
                {fieldErrors.title}
              </p>
            )}
            {fieldErrors.content && (
              <p
                id="notice-content-error"
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
            <Button type="submit" disabled={saving}>
              {saving ? '저장 중' : '저장'}
            </Button>
          </div>
        </form>
      )}
    </section>
  )
}
