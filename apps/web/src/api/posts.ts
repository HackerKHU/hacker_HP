import { request, toQuery } from './client'
import {
  fixtureComments,
  fixtureCreateComment,
  fixtureCreatePost,
  fixtureEditComment,
  fixtureEditPost,
  fixturePost,
  fixturePosts,
  fixtureRemoveComment,
  fixtureRemovePost,
} from './fixtures'
import type { Page } from './types'

/**
 * 작성자 (spec §3-2-5).
 *
 * **`name`은 절대 비지 않는다** — 회원이 제거되면 서버가 "탈퇴한 회원"을 채우고 `id`가
 * `null`이 된다 (§2-1-8). 화면은 그 문구를 직접 만들지 않는다.
 */
export interface PostAuthor {
  id: number | null
  name: string
}

/**
 * 목록의 한 행 (spec §3-2-5).
 *
 * **본문(`content`)이 없다** (MUST). 상한이 10,000자라 20건이면 그것만으로 응답이
 * 200KB가 된다 — 자료 목록이 파일을 개수만 담는 것과 같은 판단이다. 미리보기도 없다:
 * 자를 위치를 서버가 정하게 되고, 길이를 바꾸면 계약이 바뀐다.
 */
export interface PostSummary {
  id: number
  title: string
  author: PostAuthor
  createdAt: string
}

/** 상세 (spec §3-2-5). 목록의 항목에 `content`와 `updatedAt`이 더해진다. */
export interface PostDetail extends PostSummary {
  content: string
  updatedAt: string
}

/**
 * 길이 상한 (spec §2-1-8, `PostCreateRequest`의 `@CodePointSize`).
 *
 * **서버는 코드 포인트로 센다** (`CodePointSizeValidator` — `codePointCount`). 화면도
 * 같은 방식으로 세야 한다 — 자바스크립트의 `String.length`는 UTF-16 단위라 이모지 하나가
 * 2로 잡혀, **서버는 받아주는 글을 화면이 먼저 막는다.** `countCodePoints` 참고.
 */
export const TITLE_MAX = 200
export const CONTENT_MAX = 10_000

/**
 * 코드 포인트 개수. 서버의 `codePointCount`와 같은 값을 준다.
 *
 * `[...text]`가 문자열을 코드 포인트 단위로 순회한다 — 서로게이트 쌍(이모지 등)이 하나로
 * 세어진다. 결합 문자(가족 이모지 같은 ZWJ 시퀀스)는 서버도 여러 개로 세므로 그대로 맞는다.
 */
export function countCodePoints(text: string): number {
  return [...text].length
}

/**
 * 목록. **최신순 고정이라 정렬을 보내지 않는다** (spec §2-1-8 MUST).
 *
 * 서버가 `created_at DESC, id DESC`로 고정한다. 받지 않으면 **이상한 값으로 서버가 터질
 * 자리가 없다** — 자료 목록이 `sort=bogus` 하나로 `500`이 났던 적이 있다 (#52).
 */
export function list(
  query: { page?: number; size?: number } = {},
): Promise<Page<PostSummary>> {
  // 플래그는 함수 안에서 리터럴로 평가한다 — 이유는 `auth.ts` 상단 주석에 있다.
  if (import.meta.env.VITE_USE_FIXTURES === 'true') return fixturePosts(query)
  return request<Page<PostSummary>>(`/posts${toQuery({ ...query })}`)
}

export function get(id: number): Promise<PostDetail> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true') return fixturePost(id)
  return request<PostDetail>(`/posts/${id}`)
}

/**
 * 등록. **작성자는 인증 주체로만 정한다** (spec §2-1-8 MUST) — 본문으로 받지 않는다.
 *
 * 수정도 같은 본문 모양을 쓰지만 작성자 본인만 가능하다. 작성자 id는 어느 요청에도
 * 담지 않고 서버가 인증 주체와 저장된 작성자를 비교한다 (spec §3-2-5).
 */
export function create(body: {
  title: string
  content: string
}): Promise<PostDetail> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true')
    return fixtureCreatePost(body)
  return request<PostDetail>('/posts', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

/** 작성자가 제목·본문을 보낸 값으로 통째로 바꾼다 (spec §3-2-5, #256). */
export function update(
  id: number,
  body: { title: string; content: string },
): Promise<PostDetail> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true')
    return fixtureEditPost(id, body)
  return request<PostDetail>(`/posts/${id}`, {
    method: 'PATCH',
    body: JSON.stringify(body),
  })
}

/** 활성 관리자 또는 작성자 본인이 게시글을 완전히 삭제한다 (spec §3-2-5). */
export function remove(id: number): Promise<void> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true') return fixtureRemovePost(id)
  return request(`/posts/${id}`, { method: 'DELETE' })
}

// ── 댓글 ────────────────────────────────────────────────────────────────────

/**
 * 댓글 하나 (spec §3-2-5). **목록·상세를 나누지 않는다** — 상한이 2,000자라 목록에
 * 본문을 그대로 담아도 게시글 목록이 겪는 응답 크기 문제가 생기지 않는다.
 */
export interface PostComment {
  id: number
  content: string
  author: PostAuthor
  createdAt: string
  updatedAt: string
}

/** 댓글 본문 상한 (spec §3-2-5). 게시글보다 낮다 — 더 가벼운 콘텐츠로 본다 (결정 23). */
export const COMMENT_CONTENT_MAX = 2_000

/**
 * 목록. **배열이고 페이지네이션이 없다** (spec §3-2-5).
 *
 * **오래된순 고정이라 화면이 다시 정렬하지 않는다** — 서버가 `created_at ASC, id ASC`로
 * 내려준다. 게시글 목록(최신순)과 반대인데, 댓글은 대화를 읽는 순서이기 때문이다 (결정 23).
 */
export function comments(postId: number): Promise<PostComment[]> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true')
    return fixtureComments(postId)
  return request<PostComment[]>(`/posts/${postId}/comments`)
}

/** 등록. 작성자는 인증 주체로만 정한다 (spec §3-2-5 MUST) — 게시글과 같다. */
export function createComment(
  postId: number,
  body: { content: string },
): Promise<PostComment> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true')
    return fixtureCreateComment(postId, body)
  return request<PostComment>(`/posts/${postId}/comments`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

/** 수정. **통째로 교체다** (spec §3-2-5) — 작성자 본인만 가능하고 관리자 예외가 없다. */
export function updateComment(
  postId: number,
  commentId: number,
  body: { content: string },
): Promise<PostComment> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true')
    return fixtureEditComment(postId, commentId, body)
  return request<PostComment>(`/posts/${postId}/comments/${commentId}`, {
    method: 'PATCH',
    body: JSON.stringify(body),
  })
}

/** 삭제. 활성 관리자 또는 작성자 본인이 완전히 삭제한다 (spec §3-2-5). */
export function removeComment(
  postId: number,
  commentId: number,
): Promise<void> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true')
    return fixtureRemoveComment(postId, commentId)
  return request(`/posts/${postId}/comments/${commentId}`, { method: 'DELETE' })
}
