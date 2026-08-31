import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearCookies, setCookie } from '@/test/cookies'
import { contentTypeOf, extensionOf, uploadAll, uploadUrls } from './photos'

/**
 * 사진 업로드의 S3 직결 구간.
 *
 * **화면 테스트는 `uploadAll`을 통째로 mock하므로 이 계층은 그 뒤에 가려져 있다.** 여기서
 * 지키는 것은 자료(`notes`)와 **결정적으로 다른 한 가지** — presigned PUT에 실을
 * `Content-Type`이다.
 *
 * 사진 쪽 서버는 `PutObjectRequest`에 `contentType`을 실어 서명한다
 * (`S3FileStorage.presignPut`). SigV4의 `SignedHeaders`에 `content-type`이 들어가므로
 * **브라우저가 정확히 같은 값을 보내지 않으면 S3가 `SignatureDoesNotMatch`로 거절한다.**
 * 자료 쪽(`S3FileStorage.presignPut`)은 서명에 넣지 않아 이 제약이 없다.
 *
 * 이 어긋남은 **로컬 픽스처로는 절대 드러나지 않는다** — 진짜 S3에 붙는 날 처음 보게 된다.
 */

const fetchMock = vi.fn()

beforeEach(() => {
  vi.stubEnv('VITE_USE_FIXTURES', 'false')
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
  /*
   * 발급(`POST /photos/upload-url`)은 쓰기라 `request()`가 CSRF 토큰을 싣는다. 쿠키가
   * 없으면 발급 요청 전에 `GET /auth/csrf`부터 나가 이 파일이 보려는 것과 무관한 실패가
   * 난다 — 그 경로는 `client.test.ts`가 본다. 여기서는 이미 있는 상태로 둔다.
   */
  setCookie('XSRF-TOKEN', 'test-token')
})

afterEach(() => {
  clearCookies()
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

/** 발급 응답 하나를 만든다. 계약대로 `key`와 `uploadUrl`뿐이다 — 파일명이 없다. */
function issued(count: number) {
  return Array.from({ length: count }, (_, index) => ({
    key: `photos/uploads/${index}.jpg`,
    uploadUrl: `https://s3.example/${index}`,
  }))
}

/** `request()`가 쓰는 fetch 응답. */
function jsonResponse(body: unknown): Response {
  return {
    ok: true,
    status: 200,
    text: () => Promise.resolve(JSON.stringify(body)),
  } as unknown as Response
}

describe('확장자와 Content-Type', () => {
  it.each([
    ['mt.jpg', 'jpg'],
    ['MT.JPEG', 'jpeg'],
    ['a.b.png', 'png'],
    ['이름 없는 파일', ''],
  ])('%s의 확장자는 %s다', (name, expected) => {
    expect(extensionOf(name)).toBe(expected)
  })

  /*
   * **서버 규칙을 그대로 옮긴 것이다** (`PhotoService.contentTypeOf`).
   * `png`면 `image/png`, 그 밖은 전부 `image/jpeg`.
   */
  it.each([
    ['png', 'image/png'],
    ['jpg', 'image/jpeg'],
    ['jpeg', 'image/jpeg'],
  ])('%s는 %s로 서명된다', (extension, expected) => {
    expect(contentTypeOf(extension)).toBe(expected)
  })
})

describe('S3 직접 업로드', () => {
  /*
   * **서명과 같은 `Content-Type`을 보낸다.** 빠뜨리거나 다른 값을 보내면 S3가 서명
   * 불일치로 거절한다 — 이것이 자료 업로드와 다른 점이다.
   */
  it('PUT에 서명과 같은 Content-Type을 싣는다', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(issued(1)))
      .mockResolvedValueOnce({ ok: true, status: 200 } as Response)

    await uploadAll([new File(['x'], 'mt.png', { type: 'image/png' })])

    const [url, init] = fetchMock.mock.calls[1]
    expect(url).toBe('https://s3.example/0')
    expect(init.method).toBe('PUT')
    expect(init.headers).toEqual({ 'Content-Type': 'image/png' })
  })

  /*
   * **`file.type`이 아니라 확장자에서 계산한다.** 서버는 확장자만 받아 서명하므로
   * (계약 §3-2-5 — 발급 요청에 파일명도 크기도 없다), `.png`를 `.jpg`로 이름만 바꾼 파일은
   * 브라우저가 `image/png`라 해도 **서명은 `image/jpeg`다.** 서명 쪽에 맞춰야 통과한다.
   */
  it('브라우저가 말하는 타입과 확장자가 어긋나면 확장자를 따른다', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(issued(1)))
      .mockResolvedValueOnce({ ok: true, status: 200 } as Response)

    // 이름은 .jpg인데 실제 바이트는 png인 파일.
    await uploadAll([new File(['x'], 'renamed.jpg', { type: 'image/png' })])

    expect(fetchMock.mock.calls[1][1].headers).toEqual({
      'Content-Type': 'image/jpeg',
    })
  })

  /* 발급 요청에는 **확장자만** 실린다 (계약 §3-2-5) — 파일명도 크기도 보내지 않는다. */
  it('발급 요청에 확장자만 보낸다', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(issued(2)))

    await uploadUrls(['jpg', 'png'])

    const body = JSON.parse(fetchMock.mock.calls[0][1].body)
    expect(body).toEqual({ extensions: ['jpg', 'png'] })
  })

  /*
   * **순서로 짝을 맞춘다.** 응답에 파일명이 없으므로 순서 말고는 짝지을 것이 없다 —
   * 어긋나면 A의 바이트가 B의 키에 올라가고, 그 사고는 갤러리에 엉뚱한 사진이 뜨고 나서야
   * 드러난다.
   */
  it('발급 순서대로 올리고 그 키를 그 순서로 돌려준다', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(issued(3)))
      .mockResolvedValue({ ok: true, status: 200 } as Response)

    const keys = await uploadAll([
      new File(['a'], 'a.jpg'),
      new File(['b'], 'b.png'),
      new File(['c'], 'c.jpeg'),
    ])

    expect(keys).toEqual([
      'photos/uploads/0.jpg',
      'photos/uploads/1.jpg',
      'photos/uploads/2.jpg',
    ])
    // 두 번째 파일만 png다 — 순서가 어긋났다면 이 헤더가 다른 자리에 붙는다.
    expect(fetchMock.mock.calls[2][1].headers).toEqual({
      'Content-Type': 'image/png',
    })
  })

  /* 개수가 어긋나면 계약이 깨진 것이다. 엉뚱한 키에 올리느니 멈춘다. */
  it('발급 개수가 파일 수와 다르면 올리지 않고 멈춘다', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(issued(1)))

    await expect(
      uploadAll([new File(['a'], 'a.jpg'), new File(['b'], 'b.jpg')]),
    ).rejects.toThrow('업로드 주소를 받지 못했습니다.')

    // 발급 요청 한 번뿐 — PUT은 나가지 않았다.
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  /* S3의 실패 본문은 XML이라 그대로 보여줄 수 없다. 우리 문구로 바꾼다. */
  it('S3가 거절하면 우리 오류로 바꾼다', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(issued(1)))
      .mockResolvedValueOnce({ ok: false, status: 403 } as Response)

    await expect(uploadAll([new File(['x'], 'mt.jpg')])).rejects.toThrow(
      '사진을 올리지 못했습니다.',
    )
  })

  /*
   * **픽스처 모드에서는 S3로 나가지 않는다.** 발급 주소가 `blob:`이라 실제 `PUT`은
   * 실패하고, 그 실패가 업로드 오류로 보여 **업로드 화면을 끝까지 볼 수 없다** —
   * 백엔드 없이 화면을 확인하라는 픽스처의 목적과 어긋난다.
   */
  it('픽스처 모드에서는 PUT을 보내지 않고 키를 돌려준다', async () => {
    vi.stubEnv('VITE_USE_FIXTURES', 'true')
    // 업로드는 ADMIN 전용이라 픽스처가 다른 시나리오를 `403`으로 막는다 (계약 §3-2-5).
    vi.stubEnv('VITE_FIXTURE_SCENARIO', 'admin')
    /*
     * 플래그와 시나리오는 **모듈이 처음 불릴 때** 읽힌다. 위에서 정적으로 가져온 것은
     * 이미 꺼진 상태로 굳어 있으므로 새로 불러온다.
     */
    vi.resetModules()
    const photos = await import('./photos')

    const keys = await photos.uploadAll([
      new File(['a'], 'a.jpg'),
      new File(['b'], 'b.png'),
    ])

    // 발급도 픽스처가 받으므로 네트워크 요청이 하나도 나가지 않는다.
    expect(fetchMock).not.toHaveBeenCalled()
    expect(keys).toHaveLength(2)
  })

  it('올린 장수를 진행 콜백으로 알린다', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse(issued(2)))
      .mockResolvedValue({ ok: true, status: 200 } as Response)

    const seen: [number, number][] = []
    await uploadAll(
      [new File(['a'], 'a.jpg'), new File(['b'], 'b.jpg')],
      (done, total) => seen.push([done, total]),
    )

    expect(seen).toEqual([
      [1, 2],
      [2, 2],
    ])
  })
})
