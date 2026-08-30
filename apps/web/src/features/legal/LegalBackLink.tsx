import type { MouseEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'

function hasAppHistory(): boolean {
  const state: unknown = window.history.state

  return (
    typeof state === 'object' &&
    state !== null &&
    'idx' in state &&
    typeof state.idx === 'number' &&
    state.idx > 0
  )
}

/** 앱 안에서 온 경우 직전 화면으로, 직접 진입한 경우 랜딩으로 돌아간다. */
export function LegalBackLink() {
  const navigate = useNavigate()

  function handleClick(event: MouseEvent<HTMLAnchorElement>) {
    const isPlainLeftClick =
      event.button === 0 &&
      !event.metaKey &&
      !event.ctrlKey &&
      !event.shiftKey &&
      !event.altKey

    if (isPlainLeftClick && hasAppHistory()) {
      event.preventDefault()
      navigate(-1)
    }
  }

  return (
    <Link
      to="/"
      onClick={handleClick}
      className="text-sm text-muted-foreground transition-colors hover:text-foreground"
    >
      ← 돌아가기
    </Link>
  )
}
