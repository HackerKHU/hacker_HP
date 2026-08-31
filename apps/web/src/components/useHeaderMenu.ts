import { useEffect, useRef, useState } from 'react'
import { useLocation } from 'react-router-dom'

/**
 * 두 헤더의 모바일 메뉴가 같은 닫힘 규칙을 쓰게 한다.
 *
 * 링크 선택과 재토글은 각 컴포넌트가 `close`/`toggle`로 처리하고, 키보드 사용자는 Escape로
 * 닫은 뒤 열었던 버튼으로 돌아간다. 라우터 위치가 바뀌면 초점을 옮기지 않고 닫는다.
 * 문서 전체 listener는 메뉴가 열린 동안에만 존재한다.
 */
export function useHeaderMenu() {
  const { key: locationKey } = useLocation()
  const [openLocationKey, setOpenLocationKey] = useState<string | null>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const open = openLocationKey === locationKey

  /*
   * 새 위치에서는 위의 파생값이 즉시 `false`라 이전 메뉴가 한 프레임 남지 않는다. 이전
   * history entry로 돌아왔을 때 다시 열리지 않도록 저장한 키도 비운다. 이 경로에서는
   * 사용자가 이동시킨 초점을 존중하고, Escape와 달리 trigger에 focus하지 않는다.
   */
  useEffect(() => {
    setOpenLocationKey((current) => (current === locationKey ? current : null))
  }, [locationKey])

  useEffect(() => {
    if (!open) return undefined

    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      setOpenLocationKey(null)
      triggerRef.current?.focus()
    }

    document.addEventListener('keydown', closeOnEscape)
    return () => document.removeEventListener('keydown', closeOnEscape)
  }, [open])

  return {
    open,
    triggerRef,
    toggle: () =>
      setOpenLocationKey((current) =>
        current === locationKey ? null : locationKey,
      ),
    close: () => setOpenLocationKey(null),
  }
}
