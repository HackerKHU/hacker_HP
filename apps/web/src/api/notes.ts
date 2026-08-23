import { ApiError, request, toQuery } from './client'
import {
  fixtureBookmarks,
  fixtureCreateNote,
  fixtureDownloadUrl,
  fixtureNote,
  fixtureNoteFilters,
  fixtureNotes,
  fixtureRemoveNote,
  fixtureSetBookmark,
  fixtureUpdateNote,
  fixtureUploadUrls,
} from './fixtures'
import type { Page } from './types'

/** 자료의 갈래 (spec §2-1-1). `EXAM`만 `examType`을 갖는다. */
export type Category = 'EXAM' | 'SUBJECT'
export type Semester = 'SPRING' | 'FALL'
export type ExamType = 'MIDTERM' | 'FINAL'

/**
 * 업로더. **`name`은 절대 비지 않는다** (spec §3-2-2) — 회원이 제거되면 서버가
 * "탈퇴한 회원"을 채운다. `id`는 그때 `null`이 된다.
 *
 * **"본인 것인가" 판단은 `id`로 한다** (§3-2-2 MUST). 이름으로 견주면 탈퇴한 회원끼리
 * 서로의 자료를 지울 수 있다.
 */
export interface Uploader {
  id: number | null
  name: string
}

/** 목록의 한 행 (spec §3-2-4). **파일은 개수만 담긴다** — 내용은 상세가 준다. */
export interface NoteSummary {
  id: number
  category: Category
  title: string
  subjectName: string
  /** 없을 수 있다. */
  professor: string | null
  year: number
  semester: Semester
  /** `category=EXAM`에만 있다. */
  examType: ExamType | null
  uploader: Uploader
  fileCount: number
  bookmarked: boolean
  createdAt: string
}

/**
 * 첨부 파일. **S3 키는 담기지 않는다** (spec §3-2-4 MUST) — 받는 길은 `downloadUrl`뿐이고
 * 그래서 `id`가 필요하다.
 */
export interface NoteFile {
  id: number
  originalName: string
  sizeBytes: number
}

/** 상세 (spec §3-2-4). 목록의 항목에 `files`와 `updatedAt`이 더해진다. */
export interface NoteDetail extends Omit<NoteSummary, 'fileCount'> {
  files: NoteFile[]
  updatedAt: string
}

export type NoteQuery = {
  category?: Category
  /** 제목·과목명·교수명 통합 검색어. 필드를 나눠 보내지 않는다 (spec §2-1-1 MUST). */
  q?: string
  subject?: string
  professor?: string
  year?: number
  semester?: Semester
  examType?: ExamType
  /** `latest`(기본) | `title`. 그 밖의 값은 서버가 기본값으로 본다 (spec §3-2-4). */
  sort?: NoteSortValue
  page?: number
  size?: number
}

/**
 * 정렬 값. **Spring Data의 속성 정렬(`?sort=title,asc`)이 아니다** (spec §3-2-4) —
 * 서버가 두 낱말만 받고 나머지는 기본값으로 본다.
 */
export const NOTE_SORTS = ['latest', 'title'] as const
export type NoteSortValue = (typeof NOTE_SORTS)[number]

export function list(query: NoteQuery = {}): Promise<Page<NoteSummary>> {
  // 플래그는 함수 안에서 리터럴로 평가한다 — 이유는 `auth.ts` 상단 주석에 있다.
  if (import.meta.env.VITE_USE_FIXTURES === 'true') return fixtureNotes(query)
  return request<Page<NoteSummary>>(`/notes${toQuery({ ...query })}`)
}

export function get(id: number): Promise<NoteDetail> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true') return fixtureNote(id)
  return request<NoteDetail>(`/notes/${id}`)
}

/**
 * 필터 옵션. **실제 등록된 값에서 만든다** (spec §3-2-4 MUST) — 학기·시험 구분은
 * enum으로 고정이라 담기지 않고 화면이 이미 안다.
 */
export interface NoteFilterOptions {
  subjects: string[]
  professors: string[]
  years: number[]
}

export function filters(): Promise<NoteFilterOptions> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true') return fixtureNoteFilters()
  return request<NoteFilterOptions>('/notes/filters')
}

/** 등록·수정이 함께 쓰는 메타데이터. 두 계약의 파일 항목만 다르다 (spec §3-2-4). */
export interface NoteMetadata {
  category: Category
  title: string
  subjectName: string
  /** 비우면 `null`로 보낸다 — 빈 문자열을 보내면 서버가 그것을 값으로 저장한다. */
  professor: string | null
  year: number
  semester: Semester
  /** `category=EXAM`이면 필수, `SUBJECT`면 `null`이다. */
  examType: ExamType | null
}

/** 발급받아 업로드를 마친 파일. `key`는 발급 응답의 값 그대로다. */
export interface UploadedFile {
  key: string
  originalName: string
}

export function create(
  body: NoteMetadata & { files: UploadedFile[] },
): Promise<NoteDetail> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true')
    return fixtureCreateNote(body)
  return request<NoteDetail>('/notes', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

/**
 * 수정 뒤에 남을 첨부 하나. **둘 중 하나만 채운다** (spec §3-2-4) — 그대로 둘 기존
 * 파일은 `fileId`, 새로 올린 파일은 `key`+`originalName`이다. 둘 다이거나 둘 다 비면
 * 서버가 `400`으로 거절한다.
 */
export type FileRef = { fileId: number } | { key: string; originalName: string }

/**
 * 수정. **경로는 `PATCH`지만 동작은 전체 교체다** (spec §3-2-4) — `files`는 "수정 뒤에
 * 남을 첨부 전부"이고 목록에 없는 기존 파일은 삭제된다.
 */
export function update(
  id: number,
  body: NoteMetadata & { files: FileRef[] },
): Promise<NoteDetail> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true')
    return fixtureUpdateNote(id, body)
  return request<NoteDetail>(`/notes/${id}`, {
    method: 'PATCH',
    body: JSON.stringify(body),
  })
}

export function remove(id: number): Promise<void> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true') return fixtureRemoveNote(id)
  return request(`/notes/${id}`, { method: 'DELETE' })
}

// ── 업로드 ──────────────────────────────────────────────────────────────────

/** 발급 요청의 파일 하나. `sizeBytes`는 브라우저가 잰 값이다. */
export interface UploadCandidate {
  originalName: string
  sizeBytes: number
}

/**
 * 발급 응답의 한 자리 (spec §3-2-4).
 *
 * `originalName`이 되돌아오는 것은 **여러 개를 발급받았을 때 화면이 짝을 잃지 않게**
 * 하기 위해서다. 그래도 이름은 중복될 수 있으므로 이 화면은 **순서로 짝을 맞춘다**
 * (`uploadAll` 참고).
 */
export interface Upload {
  originalName: string
  key: string
  url: string
  expiresAt: string
}

/** **파일을 한 번에 받는다** — 하나씩 발급하면 왕복이 10번이다 (spec §3-2-4). */
export function uploadUrls(files: UploadCandidate[]): Promise<Upload[]> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true')
    return fixtureUploadUrls(files)
  return request<{ uploads: Upload[] }>('/notes/upload-url', {
    method: 'POST',
    body: JSON.stringify({ files }),
  }).then((response) => response.uploads)
}

/**
 * 브라우저에서 S3로 **직접** 올린다 (spec §2-1-2 MUST).
 *
 * **`request()`를 쓰지 않는다.** 그 함수는 `/api/v1`을 붙이고 인증 쿠키와 CSRF 헤더를
 * 싣는데, 여기 목적지는 우리 서버가 아니라 S3다 — 쿠키를 다른 오리진에 보낼 이유가 없고
 * 서명에 없는 헤더를 얹으면 S3가 거절할 수 있다. `fetch`를 직접 부르는 자리는 이 한 곳뿐이고,
 * **그래서 이 함수가 `src/api/` 안에 있다.** 컴포넌트는 여전히 `fetch`를 모른다.
 *
 * 파일 바이트가 서버(와 Vercel 프록시의 4.5MB 제한)를 지나가지 않는 것이 이 구조의 요점이다.
 */
async function putToStorage(url: string, file: File): Promise<void> {
  let response: Response
  try {
    response = await fetch(url, { method: 'PUT', body: file })
  } catch (cause) {
    throw new ApiError('NETWORK_ERROR', 0, '파일을 올리지 못했습니다.', {
      cause,
    })
  }
  if (!response.ok) {
    /*
     * S3의 실패 본문은 XML이라 우리 오류 계약과 형태가 다르다. 그대로 보여줄 수 없어
     * 여기서 우리 문구로 바꾼다 — 만료된 URL(`403`)이 가장 흔한 원인이다.
     */
    throw new ApiError(
      'INVALID_RESPONSE',
      response.status,
      '파일을 올리지 못했습니다. 잠시 후 다시 시도해 주세요.',
    )
  }
}

/**
 * 발급 → 업로드를 한 번에. 등록·수정 화면이 이것만 부르면 된다.
 *
 * **순서로 짝을 맞춘다.** 서버가 `originalName`을 되돌려주지만 같은 이름을 두 번 고르는
 * 일이 있어(다른 폴더의 `정리본.pdf` 둘) 이름으로 찾으면 엉뚱한 키에 올린다. 계약이
 * 목록 순서를 유지하므로 인덱스가 안전하다 — 길이가 다르면 계약이 깨진 것이라 멈춘다.
 *
 * **하나씩 순서대로 올린다.** 20MB짜리 열 개를 동시에 밀면 좁은 회선에서 전부 느려지고
 * 어느 것도 끝나지 않는다. `onProgress`로 몇 개째인지 화면에 알린다.
 */
export async function uploadAll(
  files: File[],
  onProgress?: (done: number, total: number) => void,
): Promise<UploadedFile[]> {
  const issued = await uploadUrls(
    files.map((file) => ({ originalName: file.name, sizeBytes: file.size })),
  )
  if (issued.length !== files.length) {
    throw new ApiError(
      'INVALID_RESPONSE',
      0,
      '업로드 주소를 받지 못했습니다. 잠시 후 다시 시도해 주세요.',
    )
  }

  const uploaded: UploadedFile[] = []
  for (const [index, file] of files.entries()) {
    const slot = issued[index]
    await putToStorage(slot.url, file)
    uploaded.push({ key: slot.key, originalName: file.name })
    onProgress?.(index + 1, files.length)
  }
  return uploaded
}

// ── 내려받기 ────────────────────────────────────────────────────────────────

/** 짧은 수명의 presigned GET URL (spec §3-2-4). 저장될 이름은 서명에 들어 있다. */
export interface DownloadUrl {
  url: string
  originalName: string
  expiresAt: string
}

export function downloadUrl(
  noteId: number,
  fileId: number,
): Promise<DownloadUrl> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true')
    return fixtureDownloadUrl(noteId, fileId)
  return request<DownloadUrl>(`/notes/${noteId}/files/${fileId}`)
}

// ── 즐겨찾기 ────────────────────────────────────────────────────────────────

/**
 * 담기·빼기. **토글이 아니다** (spec §3-2-4 MUST) — 같은 요청이 상태를 뒤집으면
 * 재시도가 방금 담은 것을 조용히 뺀다. 화면이 현재 `bookmarked`를 보고 방향을 정한다.
 *
 * 둘 다 멱등이라 이미 담긴 것에 담기, 담기지 않은 것에 빼기 모두 성공이다.
 */
export function setBookmark(id: number, bookmarked: boolean): Promise<void> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true')
    return fixtureSetBookmark(id, bookmarked)
  return request(`/notes/${id}/bookmark`, {
    method: bookmarked ? 'POST' : 'DELETE',
  })
}

/**
 * 내 즐겨찾기 목록. **응답 형태가 `GET /notes`와 같다** (spec §3-2-4) — 같은 카드를
 * 그리는 화면이라 목록 컴포넌트를 한 벌만 쓴다.
 *
 * **검색·필터를 받지 않는다.** 이미 본인이 추린 목록이다.
 */
export function bookmarks(
  query: { page?: number; size?: number } = {},
): Promise<Page<NoteSummary>> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true')
    return fixtureBookmarks(query)
  return request<Page<NoteSummary>>(`/bookmarks${toQuery({ ...query })}`)
}
