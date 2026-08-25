import { X } from 'lucide-react'
import { useEffect, useRef } from 'react'
import type { Photo } from '@/api/photos'
import { Button } from '@/components/ui/button'

/**
 * 사진을 화면 위에서 크게 보는 오버레이 (#270).
 *
 * **`<dialog>`의 `showModal()`을 쓴다.** 그것이 **포커스 트랩·ESC 닫기·바깥 영역
 * (`::backdrop`)·닫을 때 포커스 복귀를 브라우저가 준다.** 직접 짜면 포커스 순환과
 * `inert` 처리를 손으로 맞춰야 하고, 그 코드가 이 기능의 값을 넘는다.
 *
 * **뒤 스크롤을 잠그지 않는다.** 흔히 `html { overflow: hidden }`으로 막지만 이
 * 저장소에서는 그것이 회귀다 — `html`이 스크롤바 자리를 늘 예약하도록 맞춰 둔 상태라
 * (#258·#264) 잠그는 순간 그 자리가 사라져 **화면이 가로로 흔들린다.** 오버레이가 화면을
 * 덮으므로 뒤가 스크롤되는 것은 큰 문제가 아니다.
 */
export function PhotoLightbox({
  photo,
  onClose,
}: {
  photo: Photo | null
  onClose: () => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)

  /*
   * 열고 닫는 것을 **명령형 API로 맞춘다.** `<dialog>`는 `open` 속성만 붙이면 모달이
   * 아니라 그냥 보이는 상자가 된다 — 포커스 트랩도 `::backdrop`도 `showModal()`에만 붙는다.
   */
  useEffect(() => {
    const element = dialog.current
    if (!element) return
    if (photo && !element.open) element.showModal()
    if (!photo && element.open) element.close()
  }, [photo])

  /*
   * ESC는 브라우저가 처리하고 `close` 이벤트로 알려준다. **그 신호를 부모 상태에
   * 돌려주지 않으면** 다이얼로그는 닫혔는데 `photo`가 남아 다음 클릭에 열리지 않는다.
   */
  useEffect(() => {
    const element = dialog.current
    if (!element) return
    element.addEventListener('close', onClose)
    return () => element.removeEventListener('close', onClose)
  }, [onClose])

  return (
    // biome-ignore lint/a11y/useKeyWithClickEvents: 바깥 클릭 닫기다. 키보드로 닫는 길은 ESC와 X 버튼이 이미 맡는다.
    <dialog
      ref={dialog}
      aria-label={photo?.caption ?? '사진 크게 보기'}
      /*
       * **바깥을 누르면 닫는다.** `<dialog>`는 `::backdrop`을 눌러도 이벤트 대상이 자기
       * 자신이라, 대상이 다이얼로그일 때만 닫으면 안쪽(사진·버튼) 클릭과 갈린다.
       */
      onClick={(event) => {
        if (event.target === dialog.current) dialog.current?.close()
      }}
      className="max-h-none max-w-none bg-transparent p-0 backdrop:bg-black/80"
    >
      {photo && (
        <div className="flex h-dvh w-screen flex-col items-center justify-center gap-4 p-4 sm:p-10">
          {/*
           * 닫기는 오른쪽 위다. `fixed`로 화면 모서리에 붙여 사진 크기와 무관하게 늘 같은
           * 자리에 있게 한다 — 사진 옆에 두면 세로 사진과 가로 사진에서 자리가 달라진다.
           */}
          <Button
            type="button"
            variant="ghost"
            size="icon"
            aria-label="닫기"
            className="fixed top-4 right-4 z-10 bg-background/80 hover:bg-background"
            onClick={() => dialog.current?.close()}
          >
            <X className="size-5" aria-hidden="true" />
          </Button>

          {/*
           * **원본을 보여준다.** 목록은 썸네일을 쓰지만(§3-2-5) 크게 보는 자리에서는
           * 원본이어야 한다 — 그것이 이 화면의 목적이다.
           *
           * `object-contain`으로 비율을 지킨다. 화면보다 큰 사진은 줄어들 뿐 잘리지 않는다.
           */}
          <img
            src={photo.url}
            alt={photo.caption ?? ''}
            className="max-h-full min-h-0 w-auto max-w-full object-contain"
          />

          {photo.caption && (
            <p className="shrink-0 text-center text-sm text-background dark:text-foreground">
              {photo.caption}
            </p>
          )}
        </div>
      )}
    </dialog>
  )
}
