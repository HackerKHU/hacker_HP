import { X } from 'lucide-react'
import { type FormEvent, useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ApiError } from '@/api/client'
import {
  extensionOf,
  PHOTO_EXTENSIONS,
  PHOTO_MAX_BYTES,
  PHOTO_MAX_COUNT,
  type PhotoFailureReason,
  register,
  uploadAll,
} from '@/api/photos'
import { useSession } from '@/auth/session'
import { useLiveAlert } from '@/components/live-alert/LiveAlertProvider'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

/** `<input type="file">`의 `accept`. 고르는 단계에서 걸러 주면 왕복이 줄어든다. */
const ACCEPT = PHOTO_EXTENSIONS.map((extension) => `.${extension}`).join(',')

/** 설명 상한 (`PhotoRegisterRequest.Item.caption`의 `@Size(max = 200)`). */
const CAPTION_MAX = 200

/** 실패 사유별 문구 (계약 §3-2-5). **하나로 뭉치면 무엇을 고쳐야 할지 알 수 없다.** */
const FAILURE_TEXT: Record<PhotoFailureReason, string> = {
  NOT_FOUND: '올라온 원본을 찾지 못했습니다',
  FILE_TOO_LARGE: '20MB를 넘습니다',
  UNSUPPORTED_FILE_TYPE: '이미지로 읽을 수 없습니다',
  VALIDATION_ERROR: '업로드 정보가 올바르지 않습니다',
}

/** 고른 사진 한 장. 미리보기 주소는 브라우저가 만든 것이라 화면을 떠날 때 되돌려준다. */
type Picked = {
  file: File
  previewUrl: string
  caption: string
}

/**
 * 활동사진 업로드 (spec §2-1-7, §3-2-5).
 *
 * **`ADMIN` 전용이다** (spec §3-1-3 매트릭스). 진입은 라우트 가드가 막고, **여기서 권한을
 * 다시 판단하지 않는다** — 화면이 권한을 판단하기 시작하면 근거가 가드·서버·화면 셋으로
 * 흩어진다 (§3-1-7).
 *
 * **업로드 흐름은 세 단계다** (spec §3-2-5 MUST).
 *
 * ```text
 * ① 브라우저 → 서버   확장자 목록으로 presigned PUT URL 발급
 * ② 브라우저 → S3     원본을 직접 업로드 (서버를 거치지 않음)
 * ③ 브라우저 → 서버   원본 키 + 설명 등록 → 서버가 읽어 리사이즈 후 저장
 * ```
 *
 * ①②는 `uploadAll`이 맡고 여기서는 ③만 부른다. **원본 바이트가 우리 서버로도 Vercel
 * 프록시로도 흐르지 않는다** — 휴대폰 사진은 본문 제한(4.5MB)보다 흔히 크다.
 */
export function PhotoUploadPage() {
  const navigate = useNavigate()
  const { reportApiError } = useSession()
  const alert = useLiveAlert()

  const [picked, setPicked] = useState<Picked[]>([])
  const fileInput = useRef<HTMLInputElement>(null)
  const [saving, setSaving] = useState(false)
  const [progress, setProgress] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [failedNotice, setFailedNotice] = useState<string | null>(null)

  /**
   * 미리보기 주소를 되돌려준다. `URL.createObjectURL`이 만든 것은 **명시적으로 풀지
   * 않으면 탭이 닫힐 때까지 메모리에 남는다** — 스무 장이면 무시할 양이 아니다.
   *
   * **`picked`를 의존성에 두면 안 된다.** 사진을 한 장 더 고를 때마다 앞 렌더의 cleanup이
   * 돌아 **이미 고른 사진들의 주소까지 풀어버린다** — 그 객체들은 새 배열에도 그대로
   * 들어 있어서 미리보기가 통째로 깨진다. 화면을 떠날 때 한 번만 푼다.
   *
   * 그래서 최신 목록을 `ref`로 따로 들고 있는다. cleanup이 볼 수 있는 `picked`는 이 효과가
   * 처음 돌 때의 값(빈 배열)이라 그것만으로는 아무것도 풀지 못한다.
   */
  const pickedRef = useRef(picked)
  pickedRef.current = picked
  useEffect(() => {
    return () => {
      for (const item of pickedRef.current) URL.revokeObjectURL(item.previewUrl)
    }
  }, [])

  /**
   * 고른 사진을 목록에 더한다. **입력을 비운다** — 비우지 않으면 같은 파일을 다시 고를 때
   * `change`가 나지 않아 아무 일도 일어나지 않는다.
   *
   * 서버가 거절할 것을 여기서 먼저 거른다 (계약 §3-2-5). 20MB짜리를 다 올린 뒤에
   * 거절당하면 그 시간이 통째로 버려진다.
   */
  function addFiles(files: FileList | null) {
    if (!files) return
    const chosen = [...files]
    if (fileInput.current) fileInput.current.value = ''

    for (const file of chosen) {
      const extension = extensionOf(file.name)
      if (!(PHOTO_EXTENSIONS as readonly string[]).includes(extension)) {
        setError(
          `${file.name}은(는) 올릴 수 없는 형식입니다. ${PHOTO_EXTENSIONS.join(', ')}만 됩니다.`,
        )
        return
      }
      if (file.size > PHOTO_MAX_BYTES) {
        setError(`${file.name}이(가) 20MB를 넘습니다.`)
        return
      }
    }
    if (picked.length + chosen.length > PHOTO_MAX_COUNT) {
      setError(`사진은 한 번에 ${PHOTO_MAX_COUNT}장까지 올릴 수 있습니다.`)
      return
    }

    setError(null)
    setPicked((current) => [
      ...current,
      ...chosen.map((file) => ({
        file,
        previewUrl: URL.createObjectURL(file),
        caption: '',
      })),
    ])
  }

  function removeAt(index: number) {
    setPicked((current) => {
      URL.revokeObjectURL(current[index].previewUrl)
      return current.filter((_, at) => at !== index)
    })
  }

  function setCaption(index: number, caption: string) {
    setPicked((current) =>
      current.map((item, at) => (at === index ? { ...item, caption } : item)),
    )
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (saving) return

    if (picked.length === 0) {
      setError('사진을 한 장 이상 골라주세요.')
      return
    }

    setSaving(true)
    setError(null)
    setFailedNotice(null)
    /*
     * **지금 목록을 붙잡아 둔다.** 아래는 `await`가 둘 있는 긴 구간이고, 그 사이 화면 상태가
     * 바뀌면 나중에 인덱스로 대조할 때 어긋난다. 입력은 `saving` 동안 잠그지만(아래 폼 참고)
     * 그것만 믿지 않는다 — 잠금이 한 군데라도 빠지면 조용히 엉뚱한 사진이 지워진다.
     */
    const submitted = picked
    try {
      // ①② 발급 → S3 직접 업로드. **여기서만 원본 바이트가 움직이고, 목적지는 S3다.**
      const keys = await uploadAll(
        submitted.map((item) => item.file),
        (done, total) => setProgress(`사진 올리는 중 (${done}/${total})`),
      )

      /*
       * ③ 등록. **서버가 이 요청 안에서 원본을 읽어 리사이즈한다** (계약 §3-2-5) —
       * 장수가 많으면 그만큼 오래 걸린다. 그래서 진행 문구를 따로 남긴다.
       */
      setProgress('사진 변환하는 중')
      const result = await register(
        keys.map((key, index) => ({
          key,
          // 빈 문자열이 아니라 `null`을 보낸다 — 빈 문자열은 서버가 값으로 저장한다.
          caption: submitted[index].caption.trim() || null,
        })),
      )

      /*
       * **일부만 실패할 수 있다** (계약 §3-2-5 — 한 장의 실패가 나머지를 되돌리지 않는다).
       * 전부 성공한 것처럼 갤러리로 보내면 **올린 줄 아는 사진이 없다.** 실패가 있으면
       * 남아서 무엇이 왜 빠졌는지 보여주고, 성공한 것만 목록에서 지운다.
       */
      if (result.failed.length > 0) {
        const detail = result.failed
          .map((failure) => FAILURE_TEXT[failure.reason])
          .join(', ')
        setFailedNotice(
          `${result.registered.length}장을 올렸고 ${result.failed.length}장이 실패했습니다 (${detail}). 실패한 사진은 아래에 남아 있습니다 — 다시 시도해 주세요.`,
        )

        /*
         * **인덱스가 아니라 항목 자체로 가른다.**
         *
         * 실패한 키를 인덱스로 되짚어 그 자리를 남기는 방식은, 그 사이 목록이 한 칸이라도
         * 밀리면 **엉뚱한 사진을 남기고 실패한 사진을 지운다.** 붙잡아 둔 목록에서 미리
         * "남길 것"을 정해 두고, 그것을 안정된 식별자(`previewUrl` — `createObjectURL`이
         * 장마다 다른 값을 준다)로 현재 목록과 맞춘다.
         *
         * 그래서 **업로드 중 새로 고른 사진이 있어도 지워지지 않는다** — 남길 목록에 없을
         * 뿐 아니라, 아래에서 성공분만 골라 덜어내므로 그대로 남는다.
         */
        const failedKeys = new Set(result.failed.map((failure) => failure.key))
        const succeeded = submitted.filter(
          (_, index) => !failedKeys.has(keys[index]),
        )
        const doneUrls = new Set(succeeded.map((item) => item.previewUrl))
        // 올라간 사진의 미리보기 주소는 여기서 푼다 — 목록에서 빠지면 되돌릴 곳이 없다.
        for (const item of succeeded) URL.revokeObjectURL(item.previewUrl)
        setPicked((current) =>
          current.filter((item) => !doneUrls.has(item.previewUrl)),
        )
        return
      }

      alert.success(`${result.registered.length}장의 사진을 올렸습니다.`, {
        persistOnNavigation: true,
      })
      navigate('/photos', { replace: true })
    } catch (caught: unknown) {
      if (reportApiError(caught)) return
      /*
       * **실패했는데 성공한 것처럼 보이면 안 된다.** 이동하지 않고 고른 사진을 그대로 둔 채
       * 서버가 준 메시지를 보여준다 — `415`·`403`은 무엇을 고쳐야 하는지 서버가 안다.
       */
      alert.error(
        caught instanceof ApiError
          ? caught.message
          : '사진을 올리지 못했습니다. 잠시 후 다시 시도해 주세요.',
      )
    } finally {
      setSaving(false)
      setProgress(null)
    }
  }

  return (
    <section className="min-h-[32rem]" data-detail-surface="photo-upload">
      <Link
        to="/photos"
        className="text-sm text-muted-foreground transition-colors hover:text-foreground"
      >
        ← 갤러리
      </Link>

      <h1 className="mt-6 text-2xl font-semibold tracking-tight">
        사진 올리기
      </h1>

      <form onSubmit={handleSubmit} className="mt-8 space-y-6">
        <div className="space-y-2">
          <div className="flex items-baseline justify-between">
            <Label htmlFor="photo-files">사진</Label>
            <span className="text-xs tabular-nums text-muted-foreground">
              {picked.length}/{PHOTO_MAX_COUNT}
            </span>
          </div>
          <input
            id="photo-files"
            ref={fileInput}
            type="file"
            multiple
            accept={ACCEPT}
            /*
             * **올리는 중에는 잠근다.** 제출 시점의 목록으로 발급·업로드·등록이 진행되므로,
             * 그 사이 목록이 바뀌면 화면과 처리 중인 것이 어긋난다 — 새로 고른 사진은
             * 올라가지도 않으면서 "올린 것"처럼 보이게 된다.
             */
            disabled={saving}
            onChange={(event) => addFiles(event.target.files)}
            aria-invalid={error !== null}
            aria-describedby={error ? 'photo-files-error' : undefined}
            className="block w-full text-sm text-muted-foreground file:mr-3 file:rounded-md file:border file:border-input file:bg-transparent file:px-3 file:py-1.5 file:text-sm file:font-medium file:text-foreground hover:file:bg-accent"
          />
          <p className="text-xs text-muted-foreground">
            {PHOTO_EXTENSIONS.join(', ')} · 장당 20MB · 최대 {PHOTO_MAX_COUNT}장
            · 서버가 가로 1920px로 줄여 저장합니다
          </p>
        </div>

        {picked.length > 0 && (
          <ul className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {picked.map((item, index) => (
              <li
                /*
                 * **미리보기 주소를 key로 쓴다.** `createObjectURL`이 장마다 다른 값을 주므로
                 * 같은 사진을 두 번 골라도 겹치지 않고, 중간의 한 장을 빼도 나머지 항목이
                 * 자리를 유지한다 — 인덱스를 쓰면 뺀 자리 뒤의 설명 입력이 한 칸씩 밀린다.
                 */
                key={item.previewUrl}
                className="space-y-2 border border-border p-3"
              >
                <div className="relative">
                  {/* 아직 올라가지 않은 파일이라 미리보기는 브라우저가 만든 주소다. */}
                  <img
                    src={item.previewUrl}
                    alt=""
                    className="aspect-square w-full bg-muted object-cover"
                  />
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    className="absolute top-1 right-1 size-7 bg-background/80"
                    // 올리는 중에는 뺄 수 없다 — 이미 올라가고 있는 것을 목록에서만 지우게 된다.
                    disabled={saving}
                    aria-label={`${item.file.name} 빼기`}
                    onClick={() => removeAt(index)}
                  >
                    <X className="size-4" aria-hidden="true" />
                  </Button>
                </div>

                <p className="truncate text-xs text-muted-foreground">
                  {item.file.name}
                </p>

                {/*
                 * 설명은 **장마다 따로** 받는다 (계약 §3-2-5 — `photos[]`의 각 항목이
                 * `caption`을 갖는다). 하나로 묶으면 스무 장에 같은 설명이 붙는다.
                 */}
                <div className="space-y-1">
                  <Label
                    htmlFor={`photo-caption-${index}`}
                    className="text-xs font-normal text-muted-foreground"
                  >
                    설명 (선택)
                  </Label>
                  <Input
                    id={`photo-caption-${index}`}
                    value={item.caption}
                    maxLength={CAPTION_MAX}
                    /*
                     * 설명도 제출 시점 값으로 등록된다. 올리는 중에 고칠 수 있게 두면
                     * **화면에는 고친 글이 보이는데 저장된 것은 옛 글이다.**
                     */
                    disabled={saving}
                    onChange={(event) => setCaption(index, event.target.value)}
                    placeholder="사진에 대한 설명을 작성해주세요"
                  />
                </div>
              </li>
            ))}
          </ul>
        )}

        <div className="min-h-12" data-upload-feedback-slot="true">
          {progress && (
            <p role="status" className="text-sm text-muted-foreground">
              {progress}
            </p>
          )}

          {failedNotice && (
            <p role="alert" className="text-sm text-muted-foreground">
              {failedNotice}
            </p>
          )}

          {error && (
            <p
              id="photo-files-error"
              role="alert"
              className="text-sm text-muted-foreground"
            >
              {error}
            </p>
          )}
        </div>

        {/* 오른쪽 정렬 + 주 동작이 맨 끝 (`apps/web/README.md` "폼 버튼"). */}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" asChild>
            <Link to="/photos">취소</Link>
          </Button>
          <Button type="submit" disabled={saving}>
            {saving ? '저장 중' : '저장'}
          </Button>
        </div>
      </form>
    </section>
  )
}
