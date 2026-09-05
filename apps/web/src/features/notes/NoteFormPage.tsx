import { X } from 'lucide-react'
import { type FormEvent, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { ApiError } from '@/api/client'
import {
  countCodePoints,
  NOTE_TITLE_MAX,
  normalizeNoteTitle,
  STORED_NOTE_TITLE_MAX,
} from '@/api/noteContract'
import {
  type Category,
  create,
  type ExamType,
  type FileRef,
  get,
  type NoteFile,
  type Semester,
  update,
  uploadAll,
} from '@/api/notes'
import { isInactive, useSession } from '@/auth/session'
import { useLiveAlert } from '@/components/live-alert/LiveAlertProvider'
import { SELECT_CLASS, SelectArrow } from '@/components/native-select'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  CATEGORY_LABEL,
  canEdit,
  categoryFromParam,
  categoryPath,
  EXAM_TYPE_LABEL,
  formatSize,
  noteErrorText,
  SEMESTER_LABEL,
} from './labels'

/**
 * 상한. 제목의 신규·변경 계약은 50자이고, DB의 `varchar(200)`은 기존 장문을 보존하는
 * 물리 상한이다. 과목명·교수명과 파일 정책은 스키마·`app.notes.upload`에서 온다.
 *
 * **서버가 원본이다.** 여기 값은 왕복을 덜기 위한 사본이라, 설정이 바뀌면 서버가 `413`·
 * `415`로 거절하고 그 메시지가 그대로 화면에 뜬다 — 화면이 통과시켜도 저장되지 않는다.
 */
const SUBJECT_MAX = 100
const PROFESSOR_MAX = 50
const MAX_FILES = 10
const MAX_FILE_BYTES = 20 * 1024 * 1024
const ALLOWED_EXTENSIONS = [
  'pdf',
  'docx',
  'pptx',
  'hwp',
  'zip',
  'png',
  'jpg',
] as const

/** `<input type="file">`의 `accept`. 고르는 단계에서 걸러 주면 왕복이 줄어든다. */
const ACCEPT = ALLOWED_EXTENSIONS.map((extension) => `.${extension}`).join(',')

type Loading = 'loading' | 'ready' | 'notFound' | 'failed' | 'denied'

/**
 * 연도 선택지. **올해부터 2016년까지** 내려간다.
 *
 * 한때 5년 전까지만 뒀는데 **그보다 오래된 정리본을 올릴 방법이 아예 없었다.** 선배들이
 * 남긴 자료가 그 범위 밖이라 고를 값이 없었던 것이다.
 *
 * 아래로 끝을 고정하고 위는 실행 시점의 연도를 쓴다 — 해가 바뀌면 저절로 늘어난다.
 * 서버는 2000~2100을 받으므로(`NoteCreateRequest`) 이 목록은 그 안이다.
 */
const THIS_YEAR = new Date().getFullYear()
const OLDEST_YEAR = 2016
const YEARS = Array.from(
  { length: THIS_YEAR - OLDEST_YEAR + 1 },
  (_, index) => THIS_YEAR - index,
)

/**
 * 자료 등록·수정 화면. **한 컴포넌트가 두 라우트를 맡는다** — 공지 폼과 같은 이유다.
 * 폼도 제약도 저장 후 이동 규칙도 같고, 다른 것은 "기존 값을 먼저 불러오는가"뿐이다.
 *
 * **업로드 흐름은 세 단계다** (spec §2-1-2 MUST).
 *
 * ```text
 * ① 브라우저 → 서버   presigned PUT URL 요청 (파일명·크기)
 * ② 브라우저 → S3     파일을 직접 업로드 (서버를 거치지 않음)
 * ③ 브라우저 → 서버   메타데이터 + 업로드된 키 목록 등록
 * ```
 *
 * ①②는 `uploadAll`이 맡고 여기서는 ③만 부른다. **파일 바이트는 우리 서버로도 Vercel
 * 프록시로도 흐르지 않는다** — 그래서 본문 제한(4.5MB)에 걸리지 않는다.
 */
export function NoteFormPage() {
  const { id } = useParams<{ id: string }>()
  const editing = id !== undefined
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const session = useSession()
  const { state, reportApiError } = session
  const alert = useLiveAlert()

  /*
   * 어느 목록에서 왔는지 `?category=`로 받아 첫 값으로 쓴다. 시험 정리본 목록에서 눌렀는데
   * 폼이 과목 정리본으로 열리면 매번 고쳐야 한다.
   *
   * **목록 화면과 같은 함수로 읽는다.** 규칙을 여기 따로 적으면 두 화면이 같은 주소를
   * 다르게 해석하는 날이 온다 — 모르는 값을 어느 갈래로 볼지는 한 곳에서만 정한다.
   */
  const [category, setCategory] = useState<Category>(
    categoryFromParam(searchParams.get('category')),
  )
  const [title, setTitle] = useState('')
  /** 기존 장문 제목을 그대로 둔 전체 교체 PATCH만 허용하기 위한 원본. */
  const [originalTitle, setOriginalTitle] = useState<string | null>(null)
  const [subjectName, setSubjectName] = useState('')
  const [professor, setProfessor] = useState('')
  const [year, setYear] = useState(THIS_YEAR)
  const [semester, setSemester] = useState<Semester>('SPRING')
  const [examType, setExamType] = useState<ExamType>('MIDTERM')

  /** 그대로 둘 기존 첨부. **여기서 뺀 파일은 저장 시 삭제된다** (계약 §3-2-4). */
  const [keptFiles, setKeptFiles] = useState<NoteFile[]>([])
  /** 새로 고른 파일. 저장을 눌러야 올라간다 — 고르자마자 올리면 취소한 파일이 S3에 남는다. */
  const [added, setAdded] = useState<File[]>([])
  const fileInput = useRef<HTMLInputElement>(null)

  const [loading, setLoading] = useState<Loading>(editing ? 'loading' : 'ready')
  const [saving, setSaving] = useState(false)
  const [progress, setProgress] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<{
    title?: string
    subject?: string
    files?: string
  }>({})

  useEffect(() => {
    if (!editing) return
    let alive = true
    setLoading('loading')
    get(Number(id))
      .then((note) => {
        if (!alive) return
        /*
         * **소유자가 아니면 폼을 열지 않는다** (spec §2-1-3 MUST). 서버가 저장 단계에서
         * `403`으로 막지만, 다 채운 뒤에 거절당하면 사용자는 그 입력을 잃는다.
         */
        if (state.kind !== 'active' || !canEdit(note, state.user)) {
          setLoading('denied')
          return
        }
        setCategory(note.category)
        setTitle(note.title)
        setOriginalTitle(note.title)
        setSubjectName(note.subjectName)
        setProfessor(note.professor ?? '')
        setYear(note.year)
        setSemester(note.semester)
        if (note.examType) setExamType(note.examType)
        setKeptFiles(note.files)
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
  }, [editing, id, state, reportApiError])

  const totalFiles = keptFiles.length + added.length
  const normalizedTitle = normalizeNoteTitle(title)
  const titleCount = countCodePoints(normalizedTitle)
  const keepsLegacyTitle =
    editing &&
    originalTitle !== null &&
    countCodePoints(originalTitle) > NOTE_TITLE_MAX &&
    normalizedTitle === originalTitle
  const exceedsStoredTitleMax = editing && titleCount > STORED_NOTE_TITLE_MAX
  const titleTooLong = titleCount > NOTE_TITLE_MAX && !keepsLegacyTitle

  /**
   * 고른 파일을 목록에 더한다. **입력을 비운다** — 비우지 않으면 같은 파일을 다시 고를 때
   * `change`가 나지 않아 아무 일도 일어나지 않는다.
   *
   * 서버가 거절할 것을 여기서 먼저 거른다 (§3-2-4). 확장자를 크기보다 먼저 본다 —
   * "이 종류는 아예 안 받는다"가 "조금 줄여서 다시"보다 먼저 알아야 할 사실이다.
   */
  function addFiles(picked: FileList | null) {
    if (!picked) return
    const chosen = [...picked]
    if (fileInput.current) fileInput.current.value = ''

    for (const file of chosen) {
      const extension = file.name.split('.').pop()?.toLowerCase() ?? ''
      if (!(ALLOWED_EXTENSIONS as readonly string[]).includes(extension)) {
        setFieldErrors({
          files: `${file.name}은(는) 올릴 수 없는 형식입니다. ${ALLOWED_EXTENSIONS.join(', ')}만 됩니다.`,
        })
        return
      }
      if (file.size > MAX_FILE_BYTES) {
        setFieldErrors({ files: `${file.name}이(가) 20MB를 넘습니다.` })
        return
      }
    }
    if (totalFiles + chosen.length > MAX_FILES) {
      setFieldErrors({
        files: `파일은 최대 ${MAX_FILES}개까지 올릴 수 있습니다.`,
      })
      return
    }
    setFieldErrors({})
    setAdded((current) => [...current, ...chosen])
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (saving) return

    const requiredErrors = {
      ...(normalizedTitle === '' ? { title: '제목을 입력해주세요.' } : {}),
      ...(exceedsStoredTitleMax
        ? { title: '기존 제목의 저장 상한을 넘었습니다.' }
        : titleTooLong
          ? { title: `제목은 ${NOTE_TITLE_MAX}자까지 쓸 수 있습니다.` }
          : {}),
      ...(subjectName.trim() === ''
        ? { subject: '과목명을 입력해주세요.' }
        : {}),
    }
    if (Object.keys(requiredErrors).length > 0) {
      setFieldErrors(requiredErrors)
      return
    }
    /*
     * **파일이 하나도 없으면 저장하지 않는다** (spec §2-1-2 MUST — 1개 이상). 수정에서도
     * 마찬가지다: 계약이 "하나도 남기지 않을 수는 없다"고 못 박았다.
     */
    if (totalFiles === 0) {
      setFieldErrors({ files: '파일을 하나 이상 올려주세요.' })
      return
    }

    setSaving(true)
    setFieldErrors({})
    try {
      /*
       * ①② 발급 → S3 직접 업로드. **여기서만 파일 바이트가 움직이고, 목적지는 S3다.**
       * 새로 고른 파일이 없으면 요청 자체를 보내지 않는다 — 메타데이터만 고치는 수정이 그렇다.
       */
      const uploaded =
        added.length === 0
          ? []
          : await uploadAll(added, (done, total) =>
              setProgress(`파일 올리는 중 (${done}/${total})`),
            )
      setProgress(null)

      const metadata = {
        category,
        title: normalizedTitle,
        subjectName: subjectName.trim(),
        // 빈 문자열이 아니라 `null`을 보낸다 — 빈 문자열은 서버가 값으로 저장한다.
        professor: professor.trim() === '' ? null : professor.trim(),
        year,
        // `SUBJECT`에는 시험 구분이 없다 (계약 §3-2-2 CHECK 제약).
        semester,
        examType: category === 'EXAM' ? examType : null,
      }

      // ③ 메타데이터 + 키 목록 등록.
      const saved = editing
        ? await update(Number(id), {
            ...metadata,
            /*
             * **수정 뒤에 남을 첨부 전부를 보낸다** (계약 §3-2-4). 기존 파일은 `fileId`로,
             * 새 파일은 `key`로 가리키며 **둘 중 하나만** 채운다. 목록에서 뺀 기존 파일은
             * 여기 없으므로 서버가 지운다.
             */
            files: [
              ...keptFiles.map((file): FileRef => ({ fileId: file.id })),
              ...uploaded.map(
                (file): FileRef => ({
                  key: file.key,
                  originalName: file.originalName,
                }),
              ),
            ],
          })
        : await create({ ...metadata, files: uploaded })

      alert.success(editing ? '자료를 수정했습니다.' : '자료를 등록했습니다.', {
        persistOnNavigation: true,
      })
      // 저장 결과를 볼 수 있는 곳으로 보낸다. `replace`로 뒤로가기가 폼에 돌아오지 않게 한다.
      navigate(`/notes/${saved.id}`, { replace: true })
    } catch (caught: unknown) {
      if (reportApiError(caught)) return
      /*
       * **실패했는데 성공한 것처럼 보이면 안 된다.** 이동하지 않고 입력을 그대로 둔 채
       * 서버가 준 메시지를 보여준다 — `413`·`415`·`403`은 무엇을 고쳐야 하는지 서버가 안다.
       */
      alert.error(
        caught instanceof ApiError
          ? caught.message
          : '저장하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      )
    } finally {
      setSaving(false)
      setProgress(null)
    }
  }

  const backTo = editing ? `/notes/${id}` : categoryPath(category)

  return (
    <section className="min-h-[32rem]" data-detail-surface="note-form">
      <Link
        to={backTo}
        className="text-sm text-muted-foreground transition-colors hover:text-foreground"
      >
        ← {editing ? '자료로' : '자료게시판'}
      </Link>

      <h1 className="mt-6 text-3xl font-semibold tracking-tight">
        {editing ? '자료 수정' : '업로드'}
      </h1>

      {loading === 'loading' && (
        <p className="mt-8 text-sm text-muted-foreground">불러오는 중</p>
      )}

      {loading === 'notFound' && (
        <p role="alert" className="mt-8 text-sm text-muted-foreground">
          자료를 찾을 수 없습니다. 삭제되었거나 주소가 잘못되었습니다.
        </p>
      )}

      {loading === 'failed' && (
        <p role="alert" className="mt-8 text-sm text-muted-foreground">
          {noteErrorText(isInactive(session))}
        </p>
      )}

      {loading === 'denied' && (
        <p role="alert" className="mt-8 text-sm text-muted-foreground">
          본인이 올린 자료만 수정할 수 있습니다.
        </p>
      )}

      {loading === 'ready' && (
        <form onSubmit={handleSubmit} className="mt-8 space-y-6">
          <div className="grid gap-6 sm:grid-cols-2">
            <div className="space-y-2">
              <Label htmlFor="note-category">카테고리</Label>
              <select
                id="note-category"
                value={category}
                onChange={(event) =>
                  setCategory(event.target.value as Category)
                }
                className={SELECT_CLASS}
                style={SelectArrow}
              >
                {Object.entries(CATEGORY_LABEL).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
            </div>

            {/*
             * **시험 구분은 `EXAM`에만 나온다** (spec §2-1-1). `SUBJECT`인데 값을 보내면
             * 서버의 CHECK 제약과 어긋나 `400`이다 — 칸을 감추고 `null`을 보낸다.
             */}
            {category === 'EXAM' && (
              <div className="space-y-2">
                <Label htmlFor="note-exam-type">시험 구분</Label>
                <select
                  id="note-exam-type"
                  value={examType}
                  onChange={(event) =>
                    setExamType(event.target.value as ExamType)
                  }
                  className={SELECT_CLASS}
                  style={SelectArrow}
                >
                  {Object.entries(EXAM_TYPE_LABEL).map(([value, label]) => (
                    <option key={value} value={value}>
                      {label}고사
                    </option>
                  ))}
                </select>
              </div>
            )}
          </div>

          <div className="space-y-2">
            <div className="flex items-baseline justify-between">
              <Label htmlFor="note-title">제목</Label>
              {/* 상한을 눌러 막기만 하면 왜 안 써지는지 알 수 없다. 남은 양을 보여준다. */}
              <span className="text-xs tabular-nums text-muted-foreground">
                {titleCount}/{NOTE_TITLE_MAX}
              </span>
            </div>
            <Input
              id="note-title"
              value={title}
              onChange={(event) => {
                setTitle(event.target.value)
                setFieldErrors((current) => ({ ...current, title: undefined }))
              }}
              placeholder="예) 운영체제 중간고사 정리본"
              aria-invalid={fieldErrors.title !== undefined || titleTooLong}
              aria-describedby={
                [
                  fieldErrors.title ? 'note-title-error' : null,
                  keepsLegacyTitle ? 'note-title-legacy-help' : null,
                ]
                  .filter(Boolean)
                  .join(' ') || undefined
              }
            />
            {keepsLegacyTitle && (
              <p
                id="note-title-legacy-help"
                className="text-xs text-muted-foreground"
              >
                기존 제목은 그대로 저장할 수 있습니다. 바꾸려면 50자 이하로
                줄여주세요.
              </p>
            )}
          </div>

          <div className="grid gap-6 sm:grid-cols-2">
            <div className="space-y-2">
              <Label htmlFor="note-subject">과목명</Label>
              <Input
                id="note-subject"
                value={subjectName}
                maxLength={SUBJECT_MAX}
                onChange={(event) => {
                  setSubjectName(event.target.value)
                  setFieldErrors((current) => ({
                    ...current,
                    subject: undefined,
                  }))
                }}
                placeholder="예) 운영체제"
                aria-invalid={fieldErrors.subject !== undefined}
                aria-describedby={
                  fieldErrors.subject ? 'note-subject-error' : undefined
                }
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="note-professor">교수명 (선택)</Label>
              <Input
                id="note-professor"
                value={professor}
                maxLength={PROFESSOR_MAX}
                onChange={(event) => setProfessor(event.target.value)}
                placeholder="비워둘 수 있습니다"
              />
            </div>
          </div>

          <div className="grid gap-6 sm:grid-cols-2">
            <div className="space-y-2">
              <Label htmlFor="note-year">연도</Label>
              <select
                id="note-year"
                value={year}
                onChange={(event) => setYear(Number(event.target.value))}
                className={SELECT_CLASS}
                style={SelectArrow}
              >
                {YEARS.map((value) => (
                  <option key={value} value={value}>
                    {value}년
                  </option>
                ))}
              </select>
            </div>
            <div className="space-y-2">
              <Label htmlFor="note-semester">학기</Label>
              <select
                id="note-semester"
                value={semester}
                onChange={(event) =>
                  setSemester(event.target.value as Semester)
                }
                className={SELECT_CLASS}
                style={SelectArrow}
              >
                {Object.entries(SEMESTER_LABEL).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
            </div>
          </div>

          <div className="space-y-2">
            <div className="flex items-baseline justify-between">
              <Label htmlFor="note-files">첨부파일</Label>
              <span className="text-xs tabular-nums text-muted-foreground">
                {totalFiles}/{MAX_FILES}
              </span>
            </div>
            <input
              id="note-files"
              ref={fileInput}
              type="file"
              multiple
              accept={ACCEPT}
              onChange={(event) => addFiles(event.target.files)}
              aria-invalid={fieldErrors.files !== undefined}
              aria-describedby={
                fieldErrors.files ? 'note-files-error' : undefined
              }
              className="block w-full text-sm text-muted-foreground file:mr-3 file:rounded-md file:border file:border-input file:bg-transparent file:px-3 file:py-1.5 file:text-sm file:font-medium file:text-foreground hover:file:bg-accent"
            />
            <p className="text-xs text-muted-foreground">
              {ALLOWED_EXTENSIONS.join(', ')} · 파일당 20MB · 최대 {MAX_FILES}개
            </p>

            {totalFiles > 0 && (
              <ul className="mt-3 divide-y divide-border border-y border-border">
                {/* 기존 첨부. 빼면 저장할 때 삭제된다 — 지금 지우는 것이 아니다. */}
                {keptFiles.map((file) => (
                  <li
                    key={`kept-${file.id}`}
                    className="flex items-center justify-between gap-4 py-2"
                  >
                    <span className="min-w-0 truncate">
                      {file.originalName}
                    </span>
                    <span className="flex shrink-0 items-center gap-3">
                      <span className="text-xs tabular-nums text-muted-foreground">
                        {formatSize(file.sizeBytes)}
                      </span>
                      <Button
                        type="button"
                        variant="ghost"
                        size="icon"
                        className="size-7"
                        aria-label={`${file.originalName} 빼기`}
                        onClick={() =>
                          setKeptFiles((current) =>
                            current.filter((kept) => kept.id !== file.id),
                          )
                        }
                      >
                        <X className="size-4" aria-hidden="true" />
                      </Button>
                    </span>
                  </li>
                ))}

                {/* 새로 고른 파일. 아직 올라가지 않았다. */}
                {added.map((file, index) => (
                  <li
                    key={`added-${file.name}-${file.lastModified}`}
                    className="flex items-center justify-between gap-4 py-2"
                  >
                    <span className="min-w-0 truncate">
                      {file.name}
                      <span className="ml-2 text-xs text-muted-foreground">
                        새 파일
                      </span>
                    </span>
                    <span className="flex shrink-0 items-center gap-3">
                      <span className="text-xs tabular-nums text-muted-foreground">
                        {formatSize(file.size)}
                      </span>
                      <Button
                        type="button"
                        variant="ghost"
                        size="icon"
                        className="size-7"
                        aria-label={`${file.name} 빼기`}
                        onClick={() =>
                          setAdded((current) =>
                            current.filter((_, at) => at !== index),
                          )
                        }
                      >
                        <X className="size-4" aria-hidden="true" />
                      </Button>
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </div>

          <div
            className="min-h-16 space-y-1"
            data-upload-feedback-slot="true"
            role={
              Object.values(fieldErrors).some(Boolean) ? 'alert' : undefined
            }
          >
            {progress && (
              <p role="status" className="text-sm text-muted-foreground">
                {progress}
              </p>
            )}
            {fieldErrors.title && (
              <p
                id="note-title-error"
                className="text-sm text-muted-foreground"
              >
                {fieldErrors.title}
              </p>
            )}
            {fieldErrors.subject && (
              <p
                id="note-subject-error"
                className="text-sm text-muted-foreground"
              >
                {fieldErrors.subject}
              </p>
            )}
            {fieldErrors.files && (
              <p
                id="note-files-error"
                className="text-sm text-muted-foreground"
              >
                {fieldErrors.files}
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
