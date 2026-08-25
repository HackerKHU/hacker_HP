import { type FormEvent, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ApiError } from '@/api/client'
import { CONTENT_MAX, countCodePoints, create, TITLE_MAX } from '@/api/posts'
import { useSession } from '@/auth/session'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'

/**
 * 글쓰기 (spec §2-1-8).
 *
 * **`ACTIVE`면 누구나 쓴다** (spec §3-1-3 매트릭스). 진입은 라우트 가드가 막고 여기서
 * 권한을 다시 판단하지 않는다 (§3-1-7).
 *
 * **수정 화면이 아니다.** 공지 폼은 한 컴포넌트가 등록·수정을 겸하지만 게시판에는 수정
 * 경로가 아예 없다 (§3-2-5) — 없는 것을 대비해 분기를 만들어 두지 않는다.
 */
export function PostFormPage() {
  const navigate = useNavigate()
  const { reportApiError } = useSession()

  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  /*
   * **코드 포인트로 센다.** 서버가 `codePointCount`로 재므로(`CodePointSizeValidator`)
   * `String.length`(UTF-16 단위)로 세면 이모지 하나가 2로 잡혀 **서버는 받아주는 글을
   * 화면이 먼저 막는다.** 남은 양 표시와 제출 검사가 같은 함수를 쓴다.
   */
  const titleCount = countCodePoints(title)
  const contentCount = countCodePoints(content)
  const tooLong = titleCount > TITLE_MAX || contentCount > CONTENT_MAX

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (saving) return

    /*
     * 계약이 요구하는 것은 공백이 아닐 것과 길이뿐이다 (§2-1-8). 서버가 거부할 요청을
     * 굳이 보내지 않는다 — 같은 규칙을 앞당겨 적용하는 것이지 서버 검증을 대신하지 않는다.
     */
    if (title.trim() === '' || content.trim() === '') {
      setError('제목과 내용을 입력해주세요.')
      return
    }
    if (tooLong) {
      setError('글자 수 상한을 넘었습니다. 줄여서 다시 시도해 주세요.')
      return
    }

    setSaving(true)
    setError(null)
    try {
      const saved = await create({
        title: title.trim(),
        content: content.trim(),
      })
      // 쓴 글을 볼 수 있는 곳으로 보낸다. `replace`로 뒤로가기가 폼에 돌아오지 않게 한다.
      navigate(`/posts/${saved.id}`, { replace: true })
    } catch (caught: unknown) {
      reportApiError(caught)
      /*
       * **실패했는데 성공한 것처럼 보이면 안 된다.** 이동하지 않고 입력을 그대로 둔 채
       * 서버가 준 메시지를 보여준다 — 무엇을 고쳐야 하는지는 서버가 안다.
       */
      setError(
        caught instanceof ApiError
          ? caught.message
          : '글을 올리지 못했습니다. 잠시 후 다시 시도해 주세요.',
      )
    } finally {
      setSaving(false)
    }
  }

  return (
    <section>
      <Link
        to="/posts"
        className="text-sm text-muted-foreground transition-colors hover:text-foreground"
      >
        ← 자유 게시판
      </Link>

      <h1 className="mt-6 text-2xl font-semibold tracking-tight">글쓰기</h1>

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
            onChange={(event) => setTitle(event.target.value)}
            placeholder="이번 학기 스터디 모집합니다"
            aria-invalid={titleCount > TITLE_MAX}
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
            onChange={(event) => setContent(event.target.value)}
            placeholder="내용을 입력하세요"
            className="min-h-64"
            aria-invalid={contentCount > CONTENT_MAX}
          />
        </div>

        {error && (
          <p role="alert" className="text-sm text-muted-foreground">
            {error}
          </p>
        )}

        {/* 오른쪽 정렬 + 주 동작이 맨 끝 (`apps/web/README.md` "폼 버튼"). */}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" asChild>
            <Link to="/posts">취소</Link>
          </Button>
          <Button type="submit" disabled={saving || tooLong}>
            {saving ? '올리는 중' : '올리기'}
          </Button>
        </div>
      </form>
    </section>
  )
}
