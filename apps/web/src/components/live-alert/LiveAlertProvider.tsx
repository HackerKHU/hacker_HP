import { CircleCheck, Info, OctagonAlert, X } from 'lucide-react'
import {
  createContext,
  type ReactNode,
  use,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import { useLocation } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

export type LiveAlertKind = 'success' | 'info' | 'error'

export type LiveAlertOptions = {
  persistOnNavigation?: boolean
  durationMs?: number
}

export interface LiveAlerts {
  success: (message: string, options?: LiveAlertOptions) => void
  info: (message: string, options?: LiveAlertOptions) => void
  error: (message: string, options?: LiveAlertOptions) => void
  dismiss: () => void
}

type AlertState = {
  id: number
  kind: LiveAlertKind
  message: string
  durationMs: number
  persistOnNavigation: boolean
  routeKey: string
}

const DEFAULT_DURATION: Record<LiveAlertKind, number> = {
  success: 6_000,
  info: 6_000,
  error: 10_000,
}

const LiveAlertContext = createContext<LiveAlerts | null>(null)

function durationFor(kind: LiveAlertKind, message: string): number {
  const base = DEFAULT_DURATION[kind]
  if (message.length < 80) return base
  return Math.min(15_000, base + (message.length - 80) * 50)
}

function iconFor(kind: LiveAlertKind) {
  const className = 'mt-0.5 size-5 shrink-0'
  if (kind === 'success')
    return <CircleCheck aria-hidden="true" className={className} />
  if (kind === 'error')
    return <OctagonAlert aria-hidden="true" className={className} />
  return <Info aria-hidden="true" className={className} />
}

export function LiveAlertProvider({ children }: { children: ReactNode }) {
  const location = useLocation()
  const routeKey = `${location.pathname}${location.search}`
  const routeKeyRef = useRef(routeKey)
  routeKeyRef.current = routeKey
  const nextId = useRef(0)
  const [current, setCurrent] = useState<AlertState | null>(null)

  const show = useCallback(
    (kind: LiveAlertKind, message: string, options?: LiveAlertOptions) => {
      nextId.current += 1
      setCurrent({
        id: nextId.current,
        kind,
        message,
        durationMs:
          options?.durationMs === undefined
            ? durationFor(kind, message)
            : Math.max(1, options.durationMs),
        persistOnNavigation: options?.persistOnNavigation === true,
        routeKey: routeKeyRef.current,
      })
    },
    [],
  )

  const dismiss = useCallback(() => setCurrent(null), [])

  const value = useMemo<LiveAlerts>(
    () => ({
      success: (message, options) => show('success', message, options),
      info: (message, options) => show('info', message, options),
      error: (message, options) => show('error', message, options),
      dismiss,
    }),
    [dismiss, show],
  )

  useEffect(() => {
    setCurrent((alert) => {
      if (!alert || alert.routeKey === routeKey) return alert
      if (alert.persistOnNavigation) {
        return { ...alert, persistOnNavigation: false, routeKey }
      }
      return null
    })
  }, [routeKey])

  useEffect(() => {
    const alertId = current?.id
    const durationMs = current?.durationMs
    if (alertId === undefined || durationMs === undefined) return
    const timer = window.setTimeout(() => {
      setCurrent((alert) => (alert?.id === alertId ? null : alert))
    }, durationMs)
    return () => window.clearTimeout(timer)
  }, [current?.durationMs, current?.id])

  return (
    <LiveAlertContext value={value}>
      {children}
      {current ? (
        <div className="live-alert-viewport" data-live-alert-viewport="true">
          <div
            key={current.id}
            role={current.kind === 'error' ? 'alert' : 'status'}
            aria-live={current.kind === 'error' ? undefined : 'polite'}
            aria-atomic="true"
            data-live-alert-kind={current.kind}
            className={cn(
              'live-alert-card flex items-start gap-3 border bg-background px-4 py-3 text-sm text-foreground shadow-lg',
              current.kind === 'error'
                ? 'border-foreground/40'
                : 'border-border',
            )}
          >
            {iconFor(current.kind)}
            <p className="min-w-0 flex-1 leading-6 [overflow-wrap:anywhere]">
              {current.message}
            </p>
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="-m-2 size-10 shrink-0"
              aria-label="알림 닫기"
              onClick={dismiss}
            >
              <X aria-hidden="true" />
            </Button>
          </div>
        </div>
      ) : null}
    </LiveAlertContext>
  )
}

export function useLiveAlert(): LiveAlerts {
  const alerts = use(LiveAlertContext)
  if (!alerts) {
    throw new Error('useLiveAlert은 LiveAlertProvider 안에서만 쓸 수 있다.')
  }
  return alerts
}
