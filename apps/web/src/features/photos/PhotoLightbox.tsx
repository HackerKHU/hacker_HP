import { ThumbsUp, X } from 'lucide-react'
import { useEffect, useRef } from 'react'
import type { Photo } from '@/api/photos'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

/**
 * 사진을 화면 위에서 크게 보는 오버레이 (#270).
 *
 * **`<dialog>`의 `showModal()`을 쓴다.** 그것이 **포커스 트랩·ESC 닫기·어두운 뒷면
 * (`::backdrop`)·닫을 때 포커스 복귀를 브라우저가 준다.** 직접 짜면 포커스 순환과
 * `inert` 처리를 손으로 맞춰야 하고, 그 코드가 이 기능의 값을 넘는다.
 *
 * **바깥 클릭으로 닫는 것만은 직접 짠다.** `<dialog>`가 주지 않는 동작이고, 이 화면에서는
 * `::backdrop`을 누르는 방식도 쓸 수 없다. 이유는 아래 안쪽 칸에 적어 뒀다.
 *
 * **뒤 스크롤을 잠그지 않는다.** 흔히 `html { overflow: hidden }`으로 막지만 이
 * 저장소에서는 그것이 회귀다 — `html`이 스크롤바 자리를 늘 예약하도록 맞춰 둔 상태라
 * (#258·#264) 잠그는 순간 그 자리가 사라져 **화면이 가로로 흔들린다.** 오버레이가 화면을
 * 덮으므로 뒤가 스크롤되는 것은 큰 문제가 아니다.
 */
export function PhotoLightbox({
  photo,
  onClose,
  onToggleLike,
  liking,
}: {
  photo: Photo | null
  onClose: () => void
  /**
   * 좋아요·취소. **이 컴포넌트는 상태를 들지 않는다** — 개수를 여기서도 세면 그리드의
   * 숫자와 갈린다. 누른 결과는 부모가 한 곳에서 고치고 `photo`로 다시 내려온다.
   */
  onToggleLike: () => void
  /** 요청이 도는 동안 잠근다. 연타하면 POST와 DELETE가 순서를 바꿔 도착한다. */
  liking: boolean
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
    <dialog
      ref={dialog}
      aria-label={photo?.caption ?? '사진 크게 보기'}
      className="max-h-none max-w-none bg-transparent p-0 backdrop:bg-black/80"
    >
      {photo && (
        /*
         * **어두운 자리를 누르면 닫는다.**
         *
         * 흔히 `::backdrop` 클릭으로 짜지만 **여기서는 그 방법이 통하지 않는다.** 이 칸이
         * `h-dvh w-screen`이라 다이얼로그 상자가 화면을 꽉 채우고, 진짜 `::backdrop`은
         * 그 뒤에 가려 누를 자리가 남지 않는다. 대상이 다이얼로그인지 보는 식으로 짜면
         * 그 조건이 영영 참이 되지 않아 **바깥을 눌러도 닫히지 않는다.**
         *
         * 그래서 화면을 채운 이 칸 자신이 바깥 역할을 한다. `currentTarget`과 같을 때만,
         * 즉 사진·설명·닫기 버튼이 아니라 그 둘레의 빈 자리를 눌렀을 때만 닫는다.
         */
        // biome-ignore lint/a11y/useKeyWithClickEvents: 키보드로 닫는 길은 ESC와 X 버튼이 이미 맡는다. 이 칸에 역할을 주면 읽는 기계가 없는 버튼을 하나 더 읽는다.
        // biome-ignore lint/a11y/noStaticElementInteractions: 같은 이유다. 마우스에게만 주는 지름길이라 대응하는 역할이 없다.
        <div
          onClick={(event) => {
            if (event.target === event.currentTarget) dialog.current?.close()
          }}
          className="flex h-dvh w-screen flex-col items-center justify-center gap-4 p-4 sm:p-10"
        >
          {/*
           * 닫기는 오른쪽 위다. `fixed`로 화면 모서리에 붙여 사진 크기와 무관하게 늘 같은
           * 자리에 있게 한다. 사진 옆에 두면 세로 사진과 가로 사진에서 자리가 달라진다.
           *
           * **버튼 상자를 지우고 X 자체를 보이게 한다.** 배경을 깔면 어두운 화면 위에
           * 흐린 네모가 하나 더 생겨 사진 모서리를 가린다. 뒤가 늘 어두운 자리이므로
           * 흰 X면 충분하고, 밝은 사진이 뒤로 올라오는 경우를 위해 그림자를 준다.
           */}
          <Button
            type="button"
            variant="ghost"
            size="icon"
            aria-label="닫기"
            className="fixed top-4 right-4 z-10 text-white hover:bg-transparent hover:text-white/70 dark:hover:bg-transparent"
            onClick={() => dialog.current?.close()}
          >
            <X className="size-6 drop-shadow-md" aria-hidden="true" />
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
            <p
              className="max-h-24 max-w-full shrink-0 overflow-y-auto break-words px-2 text-center text-sm text-background dark:text-foreground"
              title={photo.caption}
            >
              {photo.caption}
            </p>
          )}

          {/*
           * **버튼은 이 자리에만 둔다** (#351 D1). 그리드 카드에는 개수만 보여준다 —
           * 카드마다 버튼을 두면 훑는 사람이 사진을 고르다 잘못 누른다.
           *
           * 채움/비움과 `aria-pressed`로 내가 눌렀는지 보인다 — 무채색 팔레트라 색으로
           * 가를 수 없다. 따봉인 이유는 공지 좋아요와 같다 (3-3 결정 24 D4).
           */}
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="shrink-0"
            disabled={liking}
            aria-pressed={photo.likedByMe}
            onClick={onToggleLike}
          >
            <ThumbsUp
              className={cn('size-4', photo.likedByMe && 'fill-current')}
              aria-hidden="true"
            />
            좋아요 {photo.likeCount}
          </Button>
        </div>
      )}
    </dialog>
  )
}
