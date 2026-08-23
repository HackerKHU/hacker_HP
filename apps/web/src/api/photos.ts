import { ApiError, request, toQuery } from './client'
import {
  fixtureIssuePhotoUploadUrls,
  fixturePhotos,
  fixtureRegisterPhotos,
  fixtureRemovePhoto,
} from './fixtures'
import type { Page } from './types'

/**
 * 활동사진 한 장 (spec §3-2-5).
 *
 * **앨범 그룹은 없다** (spec §2-1-7) — 각 이미지가 개별 레코드다.
 *
 * `url`·`thumbnailUrl`은 서버가 준 주소를 그대로 쓴다. **화면이 조립하지 않는다** — S3 키
 * 구조는 밖으로 드러나지 않고, 그 조립 규칙이 화면에 생기면 서버가 경로를 바꿀 때 같이
 * 깨진다.
 */
export interface Photo {
  id: number
  /** 설명. 없을 수 있다. */
  caption: string | null
  url: string
  thumbnailUrl: string
  /** 업로더. 회원이 제거되면 비고, 그때 `uploaderName`이 "탈퇴한 회원"이 된다 (§3-2-2). */
  uploaderId: number | null
  /** **절대 비지 않는다.** 서버가 채운다 — 화면마다 문구가 갈리지 않게 (spec §3-2-2). */
  uploaderName: string
  createdAt: string
}

/** 목록. **정렬은 서버가 최신순으로 고정한다** (spec §2-1-7) — 화면이 정렬을 보내지 않는다. */
export function list(
  query: { page?: number; size?: number } = {},
): Promise<Page<Photo>> {
  // 플래그는 함수 안에서 리터럴로 평가한다 — 이유는 `auth.ts` 상단 주석에 있다.
  if (import.meta.env.VITE_USE_FIXTURES === 'true') return fixturePhotos(query)
  return request<Page<Photo>>(`/photos${toQuery({ ...query })}`)
}

export function remove(id: number): Promise<void> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true')
    return fixtureRemovePhoto(id)
  return request(`/photos/${id}`, { method: 'DELETE' })
}

// ── 업로드 ──────────────────────────────────────────────────────────────────

/**
 * 서버가 받는 확장자 (spec §3-2-5, `PhotoService.ALLOWED_EXTENSIONS`).
 *
 * 자료(`notes`)와 목록이 다르다 — 사진은 **서버가 디코딩해 리사이즈해야 하므로**
 * 이미지 형식만 받는다 (spec §2-1-7 MUST).
 */
export const PHOTO_EXTENSIONS = ['jpg', 'jpeg', 'png'] as const

/** 한 번에 올릴 수 있는 장수 (`PhotoUploadUrlRequest`·`PhotoRegisterRequest`의 `@Size(max = 20)`). */
export const PHOTO_MAX_COUNT = 20

/** 원본 크기 상한 (`PhotoService.MAX_ORIGINAL_BYTES`). 넘으면 등록 단계에서 걸러진다. */
export const PHOTO_MAX_BYTES = 20 * 1024 * 1024

/**
 * 발급 응답 한 자리 (spec §3-2-5).
 *
 * **`originalName`이 되돌아오지 않는다.** 자료 쪽(`notes`)과 다른 점이고, 그래서
 * **순서가 유일한 짝짓기 수단이다** — `uploadAll` 참고.
 */
export interface PhotoUpload {
  key: string
  uploadUrl: string
}

/**
 * **확장자만 보낸다** (spec §3-2-5). 파일명도 크기도 보내지 않는다 — 서버는 확장자로
 * 임시 키와 서명할 `Content-Type`을 정한다.
 */
export function uploadUrls(extensions: string[]): Promise<PhotoUpload[]> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true')
    return fixtureIssuePhotoUploadUrls(extensions)
  return request<PhotoUpload[]>('/photos/upload-url', {
    method: 'POST',
    body: JSON.stringify({ extensions }),
  })
}

/** 파일명에서 소문자 확장자를 뽑는다. 없으면 빈 문자열이다. */
export function extensionOf(fileName: string): string {
  const dot = fileName.lastIndexOf('.')
  return dot === -1 ? '' : fileName.slice(dot + 1).toLowerCase()
}

/**
 * 서명에 들어간 `Content-Type`. **서버의 규칙을 그대로 옮긴 것이다**
 * (`PhotoService.contentTypeOf` — `png`면 `image/png`, 나머지는 `image/jpeg`).
 *
 * **자료 업로드와 결정적으로 다른 점이다.** 자료의 presigned PUT은 `Content-Type`을
 * 서명에 넣지 않아 브라우저가 무엇을 보내든 통과하지만(`S3FileStorage.presignPut`),
 * 사진은 서버가 `PutObjectRequest`에 `contentType`을 실어 서명한다
 * (`S3StorageService.presignPut`) — SigV4의 `SignedHeaders`에 `content-type`이 들어가므로
 * **브라우저가 정확히 같은 값을 보내지 않으면 S3가 `SignatureDoesNotMatch`로 거절한다.**
 *
 * 그래서 `file.type`을 쓰지 않고 **확장자에서 다시 계산한다.** 둘은 어긋날 수 있다 —
 * `.png`를 `.jpg`로 이름만 바꾼 파일은 브라우저가 `image/png`라고 하지만 서버는 확장자를
 * 보고 `image/jpeg`로 서명해 두었다. 서명 쪽에 맞추는 것이 유일하게 통과하는 값이다.
 */
export function contentTypeOf(extension: string): string {
  return extension === 'png' ? 'image/png' : 'image/jpeg'
}

/**
 * 브라우저에서 S3로 **직접** 올린다 (spec §3-2-5 MUST).
 *
 * **`request()`를 쓰지 않는다.** 그 함수는 `/api/v1`을 붙이고 인증 쿠키와 CSRF 헤더를
 * 싣는데, 여기 목적지는 우리 서버가 아니라 S3다. `fetch`를 직접 부르는 자리는 이 한 곳뿐이고
 * **그래서 이 함수가 `src/api/` 안에 있다** — 컴포넌트는 여전히 `fetch`를 모른다.
 *
 * 원본 바이트가 서버(와 Vercel 프록시의 4.5MB 제한)를 지나가지 않는 것이 이 구조의 요점이다.
 * 요즘 휴대폰 사진은 그 제한보다 흔히 크다.
 */
async function putToStorage(
  url: string,
  file: File,
  contentType: string,
): Promise<void> {
  let response: Response
  try {
    response = await fetch(url, {
      method: 'PUT',
      // 위 `contentTypeOf` 주석 참고 — 이 헤더가 서명과 다르면 S3가 거절한다.
      headers: { 'Content-Type': contentType },
      body: file,
    })
  } catch (cause) {
    throw new ApiError('NETWORK_ERROR', 0, '사진을 올리지 못했습니다.', {
      cause,
    })
  }
  if (!response.ok) {
    /*
     * S3의 실패 본문은 XML이라 우리 오류 계약과 형태가 다르다. 그대로 보여줄 수 없어
     * 여기서 우리 문구로 바꾼다 — 만료된 URL과 서명 불일치(`403`)가 흔한 원인이다.
     */
    throw new ApiError(
      'INVALID_RESPONSE',
      response.status,
      '사진을 올리지 못했습니다. 잠시 후 다시 시도해 주세요.',
    )
  }
}

/**
 * 발급 → 업로드를 한 번에. 업로드 화면이 이것만 부르면 된다.
 *
 * **순서로 짝을 맞춘다.** 응답에 파일명이 없으므로(§3-2-5) 순서 말고는 짝지을 것이 없다 —
 * 계약이 요청 순서를 유지하므로 인덱스가 맞고, **길이가 다르면 계약이 깨진 것이라 멈춘다.**
 *
 * **하나씩 순서대로 올린다.** 20MB짜리 스무 장을 동시에 밀면 좁은 회선에서 전부 느려지고
 * 어느 것도 끝나지 않는다. `onProgress`로 몇 장째인지 화면에 알린다.
 */
export async function uploadAll(
  files: File[],
  onProgress?: (done: number, total: number) => void,
): Promise<string[]> {
  const extensions = files.map((file) => extensionOf(file.name))
  const issued = await uploadUrls(extensions)
  if (issued.length !== files.length) {
    throw new ApiError(
      'INVALID_RESPONSE',
      0,
      '업로드 주소를 받지 못했습니다. 잠시 후 다시 시도해 주세요.',
    )
  }

  /*
   * **픽스처 모드에서는 S3로 나가지 않는다.** 발급된 주소가 `blob:`이라 실제로 `PUT`하면
   * 실패하고, 그 실패가 업로드 오류로 보여 **업로드 화면을 끝까지 볼 수 없다** — 백엔드
   * 없이 화면을 확인하라는 픽스처의 목적과 어긋난다 (`apps/web/README.md`).
   *
   * **프로덕션 경로는 그대로다.** 플래그가 꺼진 빌드에서는 이 분기가 늘 거짓이라
   * `putToStorage`가 예전과 똑같이 불린다.
   */
  const skipStorage = import.meta.env.VITE_USE_FIXTURES === 'true'

  const keys: string[] = []
  for (const [index, file] of files.entries()) {
    const slot = issued[index]
    if (!skipStorage) {
      await putToStorage(slot.uploadUrl, file, contentTypeOf(extensions[index]))
    }
    keys.push(slot.key)
    onProgress?.(index + 1, files.length)
  }
  return keys
}

// ── 등록 ────────────────────────────────────────────────────────────────────

/** 등록할 한 장. `key`는 발급 응답의 값 그대로다. */
export interface PhotoRegisterItem {
  key: string
  /** 설명. 비우면 `null`로 보낸다 — 빈 문자열은 서버가 값으로 저장한다. */
  caption: string | null
}

/** 등록 실패 사유 (spec §3-2-5). */
export type PhotoFailureReason =
  | 'NOT_FOUND'
  | 'FILE_TOO_LARGE'
  | 'UNSUPPORTED_FILE_TYPE'
  | 'VALIDATION_ERROR'

/**
 * 등록 결과.
 *
 * **일부가 실패해도 `200`이다** (spec §3-2-5) — 회원 일괄 승인과 같은 원칙이다. 한 장의
 * 실패가 스무 장을 통째로 되돌리면 안 된다. 화면은 `registered`와 `failed`를 **함께** 읽어
 * 무엇이 남았는지 알린다.
 */
export interface PhotoRegisterResult {
  registered: Photo[]
  failed: { key: string; reason: PhotoFailureReason }[]
}

/**
 * 메타데이터 등록. **서버가 이 요청 안에서 원본을 읽어 리사이즈한 뒤 저장한다**
 * (spec §3-2-5) — 장수가 많으면 응답이 그만큼 느리다.
 *
 * **업로더는 인증 주체로만 정한다** (MUST) — 본문으로 받으면 다른 사람 이름으로 올릴 수 있다.
 */
export function register(
  photos: PhotoRegisterItem[],
): Promise<PhotoRegisterResult> {
  if (import.meta.env.VITE_USE_FIXTURES === 'true')
    return fixtureRegisterPhotos(photos)
  return request<PhotoRegisterResult>('/photos', {
    method: 'POST',
    body: JSON.stringify({ photos }),
  })
}
