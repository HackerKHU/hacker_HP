import { ThumbsUp } from 'lucide-react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { cn } from '@/lib/utils'

/** 네 목록의 같은 조회 조건이다. 다른 조건은 보존하고 페이지와 충돌하는 즐겨찾기만 비운다. */
export function LikeFilterLink({
  exclusiveBookmark = false,
}: {
  exclusiveBookmark?: boolean
}) {
  const [params] = useSearchParams()
  const { pathname } = useLocation()
  const active =
    params.get('liked') === 'true' &&
    (!exclusiveBookmark || params.get('bookmarked') !== 'true')
  const next = new URLSearchParams(params)
  next.delete('page')
  if (exclusiveBookmark) next.delete('bookmarked')
  if (active) next.delete('liked')
  else next.set('liked', 'true')
  const query = next.toString()
  return (
    <Link
      to={query ? `${pathname}?${query}` : pathname}
      aria-current={active ? 'page' : undefined}
      className={cn(
        'flex min-h-11 shrink-0 items-center gap-1 whitespace-nowrap rounded-md px-1 py-1.5 text-sm transition-colors sm:gap-1.5 sm:px-3',
        active
          ? 'bg-accent font-medium text-foreground'
          : 'text-muted-foreground hover:text-foreground',
      )}
    >
      <ThumbsUp
        className={cn('size-4', active && 'fill-current')}
        aria-hidden="true"
      />
      좋아요
    </Link>
  )
}
