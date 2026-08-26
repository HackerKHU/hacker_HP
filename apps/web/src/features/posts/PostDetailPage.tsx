import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError } from '@/api/client'
import { get, type PostDetail } from '@/api/posts'
import { useSession } from '@/auth/session'
import { formatDateTime } from './format'

type Status = 'loading' | 'loaded' | 'notFound' | 'failed'

/**
 * 게시글 상세 (spec §2-1-8).
 *
 * **본문을 HTML로 그리지 않는다** (MUST). `whitespace-pre-wrap`으로 줄바꿈만 살리고
 * 내용은 텍스트 노드로 넣어 React의 자동 이스케이프를 탄다 — `dangerouslySetInnerHTML`이나
 * 마크다운 렌더러를 붙이는 순간 XSS 표면이 생긴다. **전 부원이 쓰는 자리라 관리자만 쓰는
 * 공지와 위험도가 다르다.**
 *
 * **링크 자동 변환도 넣지 않는다.** 본문의 URL을 `<a>`로 바꾸려면 `javascript:` 같은
 * 스킴을 걸러야 하는데, 그 필터를 직접 쓰는 것이 이 기능의 값을 넘는다.
 *
 * **수정·삭제 버튼이 없다.** 빠뜨린 것이 아니라 계약에 그 경로가 없다 (§3-2-5).
 * 관리자 삭제는 후속이다 (#238).
 */
export function PostDetailPage() {
  const { id } = useParams<{ id: string }>()
  const { reportApiError } = useSession()

  const [post, setPost] = useState<PostDetail | null>(null)
  const [status, setStatus] = useState<Status>('loading')

  useEffect(() => {
    let alive = true
    setStatus('loading')
    get(Number(id))
      .then((result) => {
        if (!alive) return
        setPost(result)
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

  return (
    /*
     * **폭을 제한하지 않는다** — `AppLayout`의 `<main>`이 주는 1152px을 그대로 쓴다.
     * 공지 상세와 같은 판단이다 (`apps/web/README.md` "화면 폭과 여백"): 남이 쓴 글은
     * 길이도 형태도 우리가 모르고, 대부분 몇 줄이라 좁히면 왼쪽에 쪼그라든다.
     */
    <article>
      {/* 목록으로 돌아가는 진입점. 뒤로가기만 믿지 않는다. */}
      <Link
        to="/posts"
        className="text-sm text-muted-foreground transition-colors hover:text-foreground"
      >
        ← 자유게시판
      </Link>

      {status === 'loading' && (
        <p className="mt-8 text-sm text-muted-foreground">불러오는 중</p>
      )}

      {status === 'notFound' && (
        <p role="alert" className="mt-8 text-sm text-muted-foreground">
          게시글을 찾을 수 없습니다. 주소가 잘못되었을 수 있습니다.
        </p>
      )}

      {status === 'failed' && (
        <p role="alert" className="mt-8 text-sm text-muted-foreground">
          글을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
        </p>
      )}

      {status === 'loaded' && post && (
        <>
          <h1 className="mt-6 text-2xl font-semibold tracking-tight">
            {post.title}
          </h1>

          <div className="mt-2 flex items-center gap-3 text-sm text-muted-foreground">
            {/* 작성자 이름은 절대 비지 않는다 — 제거되면 "탈퇴한 회원"이다 (§2-1-8). */}
            <span>{post.author.name}</span>
            <span aria-hidden="true">·</span>
            <time dateTime={post.createdAt}>
              {formatDateTime(post.createdAt)}
            </time>
          </div>

          {/*
           * **평문이다.** 중괄호 안의 문자열은 React가 텍스트 노드로 넣으므로 `<script>`를
           * 써도 글자 그대로 보인다. 여기에 `dangerouslySetInnerHTML`을 넣지 말 것.
           */}
          <div className="mt-8 whitespace-pre-wrap border-t border-border pt-8 text-sm leading-7">
            {post.content}
          </div>
        </>
      )}
    </article>
  )
}
