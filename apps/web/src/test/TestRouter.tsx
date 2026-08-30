import type { ComponentProps } from 'react'
import { MemoryRouter as ReactRouterMemoryRouter } from 'react-router-dom'
import { LiveAlertProvider } from '@/components/live-alert/LiveAlertProvider'

/** 실제 엔트리와 같은 Router → live alert 순서를 테스트에서 공유한다. */
export function MemoryRouter(
  props: ComponentProps<typeof ReactRouterMemoryRouter>,
) {
  const { children, ...routerProps } = props
  return (
    <ReactRouterMemoryRouter {...routerProps}>
      <LiveAlertProvider>{children}</LiveAlertProvider>
    </ReactRouterMemoryRouter>
  )
}

export * from 'react-router-dom'
