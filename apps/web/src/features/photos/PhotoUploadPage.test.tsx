import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { PhotoRegisterItem, PhotoRegisterResult } from '@/api/photos'
import type { User } from '@/api/types'
import { SessionProvider } from '@/auth/session'
import { PhotoUploadPage } from './PhotoUploadPage'

/**
 * 활동사진 업로드.
 *
 * 핵심은 둘이다.
 *
 * - **원본이 서버로 흐르지 않는다** — 등록 본문에는 키와 설명만 실린다 (계약 §3-2-5).
 * - **일부 실패를 성공처럼 다루지 않는다** — 한 장이 실패해도 응답은 `200`이므로,
 *   화면이 `failed`를 읽지 않으면 **올린 줄 아는 사진이 없어진다.**
 */

const api = vi.hoisted(() => ({
  uploaded: [] as string[],
  registered: [] as PhotoRegisterItem[],
  result: null as PhotoRegisterResult | null,
  /**
   * 등록을 붙잡아 두는 손잡이. 값이 있으면 `register`가 그 약속이 풀릴 때까지 기다린다 —
   * **업로드가 진행 중인 순간**의 화면을 검사할 수 있다.
   */
  hold: null as { promise: Promise<void>; release: () => void } | null,
}))

vi.mock('@/api/photos', async (importOriginal) => {
  // 상수(확장자·상한)와 `extensionOf`는 실제 것을 그대로 쓴다 — 화면이 그 값으로 거른다.
  const actual = await importOriginal<typeof import('@/api/photos')>()
  return {
    ...actual,
    uploadAll: (files: File[]) => {
      api.uploaded.push(...files.map((file) => file.name))
      return Promise.resolve(
        files.map((_, index) => `photos/uploads/fixture-${index}.jpg`),
      )
    },
    register: async (photos: PhotoRegisterItem[]) => {
      api.registered.push(...photos)
      if (api.hold) await api.hold.promise
      return api.result ?? { registered: photos.map(() => PHOTO), failed: [] }
    },
  }
})

const PHOTO = {
  id: 601,
  caption: null,
  url: '/landing/mt.jpg',
  thumbnailUrl: '/landing/mt.jpg',
  uploaderId: 2,
  uploaderName: '김관리',
  createdAt: '2026-08-01T09:00:00Z',
}

const ADMIN: User = {
  id: 2,
  email: 'admin@khu.ac.kr',
  studentNo: '2021123456',
  name: '김관리',
  department: '컴퓨터공학과',
  role: 'ADMIN',
  status: 'ACTIVE',
  createdAt: '2026-03-02T09:00:00Z',
  appliedAt: '2026-03-02T09:10:00Z',
  approvedAt: '2026-03-03T09:00:00Z',
}

vi.mock('@/api/auth', () => ({
  getMe: () => Promise.resolve(ADMIN),
  logout: () => Promise.resolve(),
}))

function renderUpload() {
  render(
    <MemoryRouter initialEntries={['/admin/photos/new']}>
      <SessionProvider>
        <Routes>
          <Route path="/admin/photos/new" element={<PhotoUploadPage />} />
          <Route path="/photos" element={<h1>갤러리</h1>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

/** 사진을 고른다. `<input type="file">`의 `files`는 직접 대입할 수 없다. */
function pick(...names: string[]) {
  const input = screen.getByLabelText('사진')
  const files = names.map(
    (name) => new File(['x'], name, { type: 'image/jpeg' }),
  )
  fireEvent.change(input, { target: { files } })
}

/** 등록을 멈춰 세운다. 돌려받은 함수를 부르면 그때 진행된다. */
function holdRegister(): () => void {
  let release = () => {}
  const promise = new Promise<void>((resolve) => {
    release = resolve
  })
  api.hold = { promise, release }
  return release
}

beforeEach(() => {
  api.uploaded = []
  api.registered = []
  api.result = null
  api.hold = null
  // jsdom에는 없다. 미리보기 주소를 만드는 데 쓴다.
  vi.stubGlobal('URL', {
    ...URL,
    createObjectURL: vi.fn((_blob: Blob) => `blob:${Math.random()}`),
    revokeObjectURL: vi.fn(),
  })
})

describe('활동사진 업로드', () => {
  /*
   * **원본은 업로드 경로로만 지나간다** (계약 §3-2-5 MUST). 등록 본문에 `File`이 실리면
   * Vercel 프록시의 4.5MB 제한에 걸린다 — 휴대폰 사진은 그보다 흔히 크다.
   */
  it('원본은 업로드 경로로만 지나가고 등록 본문에는 키와 설명만 실린다', async () => {
    renderUpload()
    pick('mt.jpg')

    fireEvent.change(await screen.findByLabelText('설명 (선택)'), {
      target: { value: '엠티 사진' },
    })
    fireEvent.click(screen.getByRole('button', { name: '올리기' }))

    await waitFor(() => {
      expect(api.registered).toHaveLength(1)
    })
    expect(api.uploaded).toEqual(['mt.jpg'])
    expect(api.registered[0]).toEqual({
      key: 'photos/uploads/fixture-0.jpg',
      caption: '엠티 사진',
    })
  })

  /* 설명은 선택이다. 빈 문자열을 보내면 서버가 그것을 값으로 저장한다. */
  it('설명을 비우면 null을 보낸다', async () => {
    renderUpload()
    pick('mt.jpg')

    fireEvent.click(await screen.findByRole('button', { name: '올리기' }))

    await waitFor(() => {
      expect(api.registered).toHaveLength(1)
    })
    expect(api.registered[0].caption).toBeNull()
  })

  /*
   * 설명은 **장마다 따로** 받는다 (계약 §3-2-5 — `photos[]`의 각 항목이 `caption`을 갖는다).
   * 하나로 묶으면 스무 장에 같은 설명이 붙는다.
   */
  it('사진마다 설명을 따로 받는다', async () => {
    renderUpload()
    pick('mt.jpg', 'festival.jpg')

    const captions = await screen.findAllByLabelText('설명 (선택)')
    expect(captions).toHaveLength(2)
    fireEvent.change(captions[0], { target: { value: '엠티' } })
    fireEvent.change(captions[1], { target: { value: '축제' } })
    fireEvent.click(screen.getByRole('button', { name: '올리기' }))

    await waitFor(() => {
      expect(api.registered).toHaveLength(2)
    })
    expect(api.registered.map((item) => item.caption)).toEqual(['엠티', '축제'])
  })

  /*
   * **일부 실패를 성공처럼 다루지 않는다** (계약 §3-2-5). 응답이 `200`이라고 갤러리로
   * 보내면 **올린 줄 아는 사진이 조용히 없어진다.** 실패가 있으면 화면에 남아 사유를 알린다.
   */
  it('일부가 실패하면 이동하지 않고 실패한 것만 남긴다', async () => {
    api.result = {
      registered: [PHOTO],
      failed: [
        { key: 'photos/uploads/fixture-1.jpg', reason: 'FILE_TOO_LARGE' },
      ],
    }

    renderUpload()
    pick('mt.jpg', 'huge.jpg')
    fireEvent.click(await screen.findByRole('button', { name: '올리기' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '1장을 올렸고 1장이 실패했습니다',
    )
    // 사유를 문구로 옮겨 무엇을 고쳐야 하는지 알린다.
    expect(screen.getByRole('alert')).toHaveTextContent('20MB를 넘습니다')
    // 갤러리로 가지 않았다.
    expect(screen.queryByRole('heading', { name: '갤러리' })).toBeNull()
    // 실패한 사진만 남는다.
    expect(await screen.findAllByLabelText('설명 (선택)')).toHaveLength(1)
  })

  /* 전부 성공하면 갤러리로 보낸다 — 결과를 볼 수 있는 곳이다. */
  it('전부 성공하면 갤러리로 간다', async () => {
    renderUpload()
    pick('mt.jpg')

    fireEvent.click(await screen.findByRole('button', { name: '올리기' }))

    expect(await screen.findByRole('heading', { name: '갤러리' })).toBeVisible()
  })

  /*
   * 허용 형식은 서버가 `415`로 막지만 **고르는 단계에서 먼저 알려준다** — 20MB짜리를
   * 다 올린 뒤에 거절당하면 그 시간이 통째로 버려진다.
   */
  it('이미지가 아닌 파일은 고르는 단계에서 막는다', async () => {
    renderUpload()
    await screen.findByLabelText('사진')

    pick('report.pdf')

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '올릴 수 없는 형식입니다',
    )
    expect(api.uploaded).toEqual([])
  })

  it('사진 없이 올리면 요청이 나가지 않는다', async () => {
    renderUpload()

    fireEvent.click(await screen.findByRole('button', { name: '올리기' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '사진을 한 장 이상 골라주세요',
    )
    expect(api.uploaded).toEqual([])
  })

  /*
   * **올리는 중에는 목록을 건드릴 수 없다.**
   *
   * 제출 시점의 목록으로 발급·업로드·등록이 진행되므로 그 사이 파일을 더 고르거나 빼면
   * **화면과 처리 중인 것이 어긋난다** — 새로 고른 사진은 올라가지도 않으면서 "올린 것"처럼
   * 보이고, 뺀 사진은 이미 올라가고 있는데 목록에서만 사라진다.
   */
  it('올리는 중에는 파일 입력·빼기·설명이 잠긴다', async () => {
    const release = holdRegister()

    renderUpload()
    pick('mt.jpg')
    fireEvent.click(await screen.findByRole('button', { name: '올리기' }))

    // 등록이 붙잡혀 있어 아직 진행 중이다.
    await waitFor(() => {
      expect(api.registered).toHaveLength(1)
    })
    expect(screen.getByLabelText('사진')).toBeDisabled()
    expect(screen.getByLabelText('설명 (선택)')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'mt.jpg 빼기' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '올리는 중' })).toBeDisabled()

    release()
    expect(await screen.findByRole('heading', { name: '갤러리' })).toBeVisible()
  })

  /*
   * **잠금이 뚫려도 엉뚱한 사진을 지우지 않는다.**
   *
   * 부분 실패 대조를 인덱스로 하면, 그 사이 목록이 한 칸이라도 밀렸을 때 **실패한 사진이
   * 사라지고 성공한 사진이 남는다** — 정확히 반대다. 붙잡아 둔 목록에서 남길 것을 먼저
   * 정하고 안정된 식별자로 맞추므로 그 어긋남이 생기지 않는다.
   *
   * 여기서는 잠금을 우회해 상태를 직접 흔든다(`addFiles`가 부르는 `change`를 강제로
   * 일으킨다) — 잠금과 대조 로직 **둘 다** 지키는지 보려면 하나를 빼고 확인해야 한다.
   */
  it('올리는 중 목록이 바뀌어도 실패한 사진이 남고 새로 고른 사진이 유실되지 않는다', async () => {
    api.result = {
      registered: [PHOTO],
      failed: [
        {
          key: 'photos/uploads/fixture-1.jpg',
          reason: 'UNSUPPORTED_FILE_TYPE',
        },
      ],
    }
    const release = holdRegister()

    renderUpload()
    pick('good.jpg', 'broken.jpg')

    const captions = await screen.findAllByLabelText('설명 (선택)')
    fireEvent.change(captions[0], { target: { value: '성공할 사진' } })
    fireEvent.change(captions[1], { target: { value: '실패할 사진' } })
    fireEvent.click(screen.getByRole('button', { name: '올리기' }))

    await waitFor(() => {
      expect(api.registered).toHaveLength(2)
    })

    /*
     * 잠금을 우회해 목록 맨 앞에 한 장을 끼워 넣는다. 인덱스로 대조했다면 이 한 칸 밀림에
     * `good`이 남고 `broken`이 지워진다.
     */
    const input = screen.getByLabelText('사진') as HTMLInputElement
    input.disabled = false
    fireEvent.change(input, {
      target: { files: [new File(['x'], 'late.jpg', { type: 'image/jpeg' })] },
    })

    release()

    await screen.findByRole('alert')
    const left = await screen.findAllByLabelText('설명 (선택)')
    const values = left.map((field) => (field as HTMLInputElement).value)
    // 실패한 사진이 남았고, 성공한 사진만 빠졌다. 늦게 고른 사진도 그대로다.
    expect(values).toContain('실패할 사진')
    expect(values).not.toContain('성공할 사진')
    expect(screen.getByText('late.jpg')).toBeVisible()
  })

  /*
   * 등록 본문의 설명은 **제출 시점 값**이다. 올리는 중에 고쳐도 이미 보낸 것은 바뀌지
   * 않으므로, 화면이 그 사이 입력을 받으면 **보이는 글과 저장된 글이 갈린다.**
   */
  it('등록 본문의 설명은 제출 시점 값이다', async () => {
    const release = holdRegister()

    renderUpload()
    pick('mt.jpg')
    fireEvent.change(await screen.findByLabelText('설명 (선택)'), {
      target: { value: '처음 설명' },
    })
    fireEvent.click(screen.getByRole('button', { name: '올리기' }))

    await waitFor(() => {
      expect(api.registered).toHaveLength(1)
    })
    expect(api.registered[0].caption).toBe('처음 설명')

    release()
    await screen.findByRole('heading', { name: '갤러리' })
  })

  /* 중간의 한 장을 빼도 나머지 설명이 자리를 유지해야 한다 (key가 인덱스가 아닌 이유). */
  it('중간의 사진을 빼도 나머지 설명이 따라 밀리지 않는다', async () => {
    renderUpload()
    pick('a.jpg', 'b.jpg', 'c.jpg')

    const captions = await screen.findAllByLabelText('설명 (선택)')
    fireEvent.change(captions[0], { target: { value: '가' } })
    fireEvent.change(captions[2], { target: { value: '다' } })

    fireEvent.click(screen.getByRole('button', { name: 'b.jpg 빼기' }))

    const left = await screen.findAllByLabelText('설명 (선택)')
    expect(left).toHaveLength(2)
    expect((left[0] as HTMLInputElement).value).toBe('가')
    expect((left[1] as HTMLInputElement).value).toBe('다')
  })
})
