import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  FileRef,
  NoteDetail,
  NoteMetadata,
  UploadedFile,
} from '@/api/notes'
import type { User } from '@/api/types'
import { SessionProvider } from '@/auth/session'
import { NoteFormPage } from './NoteFormPage'

/**
 * 자료 등록·수정.
 *
 * 여기서 지키는 것은 **#59 완료 조건 두 가지**다.
 *
 * - "브라우저가 S3로 직접 업로드하고 서버로 파일이 흐르지 않는다" — 등록 요청에는 키만
 *   실리고 `File` 객체는 실리지 않는다.
 * - "본인 자료에만 수정·삭제 진입점이 보인다" — 남의 자료로 수정 화면에 직접 들어와도 막힌다.
 */

const api = vi.hoisted(() => ({
  note: null as NoteDetail | null,
  uploaded: [] as string[],
  created: [] as (NoteMetadata & { files: UploadedFile[] })[],
  updated: [] as (NoteMetadata & { files: FileRef[] })[],
}))

const MINE: NoteDetail = {
  id: 301,
  category: 'EXAM',
  title: '운영체제 중간고사 정리본',
  subjectName: '운영체제',
  professor: '김교수',
  year: 2026,
  semester: 'SPRING',
  examType: 'MIDTERM',
  uploader: { id: 1, name: '홍길동' },
  files: [{ id: 1000, originalName: '기존.pdf', sizeBytes: 1_048_576 }],
  bookmarked: false,
  createdAt: '2026-08-01T09:00:00Z',
  updatedAt: '2026-08-01T09:00:00Z',
}

vi.mock('@/api/notes', () => ({
  get: () => Promise.resolve(api.note),
  /*
   * `uploadAll`은 발급 → S3 PUT을 감싼 함수다. 여기서는 **파일이 이 경로로만 지나가는지**를
   * 보므로, 실제 네트워크 대신 이름을 기록하고 키를 돌려준다.
   */
  uploadAll: (files: File[]) => {
    api.uploaded.push(...files.map((file) => file.name))
    return Promise.resolve(
      files.map((file) => ({
        key: `notes/uploads/1/${file.name}`,
        originalName: file.name,
      })),
    )
  },
  create: (body: NoteMetadata & { files: UploadedFile[] }) => {
    api.created.push(body)
    return Promise.resolve({ ...MINE, id: 401 })
  },
  update: (_id: number, body: NoteMetadata & { files: FileRef[] }) => {
    api.updated.push(body)
    return Promise.resolve(MINE)
  },
}))

const BASE: User = {
  id: 1,
  email: 'member@khu.ac.kr',
  studentNo: '2021123456',
  name: '홍길동',
  department: '컴퓨터공학과',
  role: 'USER',
  status: 'ACTIVE',
  createdAt: '2026-03-02T09:00:00Z',
  appliedAt: '2026-03-02T09:10:00Z',
  approvedAt: '2026-03-03T09:00:00Z',
}

const auth = vi.hoisted(() => ({ me: null as unknown }))

vi.mock('@/api/auth', () => ({
  getMe: () => Promise.resolve(auth.me),
  logout: () => Promise.resolve(),
}))

function renderForm(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <SessionProvider>
        <Routes>
          <Route path="/notes/new" element={<NoteFormPage />} />
          <Route path="/notes/:id/edit" element={<NoteFormPage />} />
          <Route path="/notes/:id" element={<h1>자료 상세</h1>} />
        </Routes>
      </SessionProvider>
    </MemoryRouter>,
  )
}

/** 파일 하나를 고른다. `<input type="file">`의 `files`는 직접 대입할 수 없다. */
function pick(name: string, bytes = 10) {
  const input = screen.getByLabelText('첨부파일')
  const file = new File(['x'.repeat(bytes)], name, { type: 'application/pdf' })
  fireEvent.change(input, { target: { files: [file] } })
  return file
}

beforeEach(() => {
  api.note = MINE
  api.uploaded = []
  api.created = []
  api.updated = []
  auth.me = BASE
})

describe('자료 등록', () => {
  /*
   * **#59 완료 조건 — "브라우저가 S3로 직접 업로드하고 서버로 파일이 흐르지 않는다."**
   *
   * 등록 본문에 `File`이 실리면 Vercel 프록시의 4.5MB 제한에 걸리고, 계약이 정한 세 단계
   * 흐름(§2-1-2 MUST)도 깨진다. 본문에는 **키와 이름만** 있어야 한다.
   */
  it('파일은 업로드 경로로만 지나가고 등록 본문에는 키만 실린다', async () => {
    renderForm('/notes/new')

    fireEvent.change(await screen.findByLabelText('제목'), {
      target: { value: '자료구조 정리' },
    })
    fireEvent.change(screen.getByLabelText('과목명'), {
      target: { value: '자료구조' },
    })
    pick('정리본.pdf')
    fireEvent.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(api.created).toHaveLength(1)
    })
    expect(api.uploaded).toEqual(['정리본.pdf'])
    expect(api.created[0].files).toEqual([
      { key: 'notes/uploads/1/정리본.pdf', originalName: '정리본.pdf' },
    ])
    // 본문 어디에도 File 객체가 없다.
    for (const file of api.created[0].files) {
      expect(file).not.toBeInstanceOf(File)
    }
  })

  /* 파일이 없으면 저장하지 않는다 (spec §2-1-2 MUST — 1개 이상). */
  it('파일 없이 저장하면 요청이 나가지 않는다', async () => {
    renderForm('/notes/new')

    fireEvent.change(await screen.findByLabelText('제목'), {
      target: { value: '제목' },
    })
    fireEvent.change(screen.getByLabelText('과목명'), {
      target: { value: '과목' },
    })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '파일을 하나 이상 올려주세요',
    )
    expect(api.created).toEqual([])
  })

  /*
   * 허용 확장자는 설정값이다 (spec §2-1-2 MUST). 서버가 `415`로 막지만 **고르는 단계에서
   * 먼저 알려준다** — 20MB를 다 올린 뒤에 거절당하면 그 시간이 통째로 버려진다.
   */
  it('허용되지 않는 확장자는 고르는 단계에서 막는다', async () => {
    renderForm('/notes/new')
    await screen.findByLabelText('첨부파일')

    pick('malware.exe')

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '올릴 수 없는 형식입니다',
    )
  })

  /*
   * **`SUBJECT`에는 시험 구분이 없다** (계약 §3-2-2 CHECK 제약). 값을 보내면 서버가
   * `400`이므로 화면이 `null`을 보내야 한다.
   */
  it('과목 정리본으로 저장하면 examType이 null이다', async () => {
    renderForm('/notes/new?category=SUBJECT')

    fireEvent.change(await screen.findByLabelText('제목'), {
      target: { value: '과목 정리' },
    })
    fireEvent.change(screen.getByLabelText('과목명'), {
      target: { value: '자료구조' },
    })
    pick('정리본.pdf')
    fireEvent.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(api.created).toHaveLength(1)
    })
    expect(api.created[0].category).toBe('SUBJECT')
    expect(api.created[0].examType).toBeNull()
  })

  /*
   * **주소는 신뢰 경계다.** 목록에서 넘어올 때 붙는 `?category=`는 사람이 손으로 고칠 수
   * 있고 옛 링크·오타도 들어온다. 계약에 없는 값이 그대로 `<select>`에 들어가면 **어느
   * 옵션과도 맞지 않아 갈래 칸이 빈 채로 열리고**, 그 상태로 저장하면 서버가 `400`이다.
   *
   * 목록 화면과 **같은 함수로 읽는다** — 규칙이 두 벌이면 같은 주소를 두 화면이 다르게
   * 해석한다.
   */
  it.each(['INVALID', 'subject', ''])(
    '갈래가 `%s`처럼 계약에 없는 값이면 시험 정리본으로 떨어진다',
    async (raw) => {
      renderForm(`/notes/new?category=${raw}`)

      expect(await screen.findByLabelText('카테고리')).toHaveValue('EXAM')
      // `EXAM`이 실제로 골라졌으므로 그 갈래에만 있는 칸도 함께 나온다.
      expect(screen.getByLabelText('시험 구분')).toBeVisible()
    },
  )

  /* 제대로 된 값은 그대로 쓴다 — 위 fallback이 모든 값을 삼키면 안 된다. */
  it('갈래가 SUBJECT면 그대로 과목 정리본으로 연다', async () => {
    renderForm('/notes/new?category=SUBJECT')

    expect(await screen.findByLabelText('카테고리')).toHaveValue('SUBJECT')
    expect(screen.queryByLabelText('시험 구분')).toBeNull()
  })

  /* 교수명은 선택이다. 빈 문자열을 보내면 서버가 그것을 값으로 저장한다. */
  it('교수명을 비우면 null을 보낸다', async () => {
    renderForm('/notes/new')

    fireEvent.change(await screen.findByLabelText('제목'), {
      target: { value: '제목' },
    })
    fireEvent.change(screen.getByLabelText('과목명'), {
      target: { value: '과목' },
    })
    pick('정리본.pdf')
    fireEvent.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(api.created).toHaveLength(1)
    })
    expect(api.created[0].professor).toBeNull()
  })

  /*
   * **연도 하한은 2016년이다** (#266). 5년 전까지만 두던 것을 내렸는데, 목록을
   * `THIS_YEAR - OLDEST_YEAR + 1`개로 만들기 때문에 **길이가 한 칸만 어긋나도
   * 경계에서 조용히 잘린다** — 화면은 멀쩡해 보이고 가장 오래된 해만 사라진다.
   *
   * 그래서 있어야 할 끝(2016)과 없어야 할 그 앞(2015)을 함께 고정한다. 하나만
   * 보면 목록 전체가 밀려도 통과한다.
   */
  it('연도는 2016년까지 있고 그 앞은 없다', async () => {
    renderForm('/notes/new')

    const year = await screen.findByLabelText('연도')
    expect(within(year).getByRole('option', { name: '2016년' })).toBeTruthy()
    expect(within(year).queryByRole('option', { name: '2015년' })).toBeNull()
    // 위 끝은 실행 시점의 연도다 — 해가 바뀌면 손대지 않아도 늘어나야 한다.
    expect(
      within(year).getByRole('option', {
        name: `${new Date().getFullYear()}년`,
      }),
    ).toBeTruthy()
  })
})

describe('자료 수정', () => {
  /*
   * **보낸 것으로 통째로 바뀐다** (계약 §3-2-4). 기존 파일은 `fileId`로, 새 파일은 `key`로
   * 가리키며 **둘 중 하나만** 채운다 — 둘 다 채우면 서버가 `400`이다.
   */
  it('남길 기존 파일은 fileId로, 새 파일은 key로 보낸다', async () => {
    renderForm('/notes/301/edit')
    await screen.findByDisplayValue('운영체제 중간고사 정리본')

    pick('추가본.pdf')
    fireEvent.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(api.updated).toHaveLength(1)
    })
    expect(api.updated[0].files).toEqual([
      { fileId: 1000 },
      { key: 'notes/uploads/1/추가본.pdf', originalName: '추가본.pdf' },
    ])
  })

  /* 목록에서 뺀 기존 파일은 보내지 않는다 — 그래서 서버가 지운다. */
  it('뺀 기존 파일은 본문에 실리지 않는다', async () => {
    renderForm('/notes/301/edit')
    await screen.findByDisplayValue('운영체제 중간고사 정리본')

    fireEvent.click(screen.getByRole('button', { name: '기존.pdf 빼기' }))
    pick('대체본.pdf')
    fireEvent.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(api.updated).toHaveLength(1)
    })
    expect(api.updated[0].files).toEqual([
      { key: 'notes/uploads/1/대체본.pdf', originalName: '대체본.pdf' },
    ])
  })

  /* 메타데이터만 고치면 업로드 요청 자체가 나가지 않는다. */
  it('새 파일이 없으면 업로드를 시도하지 않는다', async () => {
    renderForm('/notes/301/edit')
    await screen.findByDisplayValue('운영체제 중간고사 정리본')

    fireEvent.change(screen.getByLabelText('제목'), {
      target: { value: '고친 제목' },
    })
    fireEvent.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => {
      expect(api.updated).toHaveLength(1)
    })
    expect(api.uploaded).toEqual([])
    expect(api.updated[0].title).toBe('고친 제목')
  })

  /*
   * **남의 자료는 폼을 열지 않는다** (spec §2-1-3 MUST). 서버가 저장 단계에서 `403`으로
   * 막지만, 다 채운 뒤에 거절당하면 사용자는 그 입력을 잃는다.
   */
  it('남의 자료로 수정 화면에 직접 들어오면 막는다', async () => {
    api.note = { ...MINE, uploader: { id: 99, name: '권승원' } }

    renderForm('/notes/301/edit')

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '본인이 올린 자료만 수정할 수 있습니다',
    )
    expect(screen.queryByLabelText('제목')).toBeNull()
  })

  it('ADMIN은 남의 자료도 수정 화면을 연다', async () => {
    api.note = { ...MINE, uploader: { id: 99, name: '권승원' } }
    auth.me = { ...BASE, role: 'ADMIN' }

    renderForm('/notes/301/edit')

    expect(
      await screen.findByDisplayValue('운영체제 중간고사 정리본'),
    ).toBeVisible()
  })
})
