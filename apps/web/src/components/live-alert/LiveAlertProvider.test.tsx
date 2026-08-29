import { act, fireEvent, render, screen } from '@testing-library/react'
import { StrictMode } from 'react'
import { MemoryRouter, useNavigate } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LiveAlertProvider, useLiveAlert } from './LiveAlertProvider'

function Probe() {
  const alert = useLiveAlert()
  const navigate = useNavigate()
  return (
    <>
      <button type="button" onClick={() => alert.success('저장했습니다.')}>
        성공
      </button>
      <button type="button" onClick={() => alert.info('새 소식입니다.')}>
        정보
      </button>
      <button type="button" onClick={() => alert.error('실패했습니다.')}>
        오류
      </button>
      <button type="button" onClick={() => alert.info('가'.repeat(180))}>
        긴 알림
      </button>
      <button
        type="button"
        onClick={() =>
          alert.success('이동해도 보입니다.', { persistOnNavigation: true })
        }
      >
        이동 성공
      </button>
      <button type="button" onClick={() => navigate('/next?q=1')}>
        이동
      </button>
      <button type="button" onClick={() => navigate('/last')}>
        다시 이동
      </button>
    </>
  )
}

function OutsideProbe() {
  useLiveAlert()
  return null
}

function renderProvider({ strict = false }: { strict?: boolean } = {}) {
  const tree = (
    <MemoryRouter initialEntries={['/start']}>
      <LiveAlertProvider>
        <Probe />
      </LiveAlertProvider>
    </MemoryRouter>
  )
  return render(strict ? <StrictMode>{tree}</StrictMode> : tree)
}

afterEach(() => {
  vi.useRealTimers()
})

describe('LiveAlertProvider', () => {
  it('provider 밖의 hook 사용을 조용히 삼키지 않는다', () => {
    expect(() => render(<OutsideProbe />)).toThrow(
      'useLiveAlert은 LiveAlertProvider 안에서만 쓸 수 있다.',
    )
  })

  it('성공·정보는 polite status, 오류는 assertive alert 하나로 알린다', () => {
    renderProvider()

    fireEvent.click(screen.getByRole('button', { name: '성공' }))
    const success = screen.getByRole('status')
    expect(success).toHaveAttribute('aria-live', 'polite')
    expect(success).toHaveAttribute('aria-atomic', 'true')
    expect(success).toHaveAttribute('data-live-alert-kind', 'success')

    fireEvent.click(screen.getByRole('button', { name: '정보' }))
    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(screen.getByRole('status')).toHaveTextContent('새 소식입니다.')
    expect(screen.getByRole('status')).toHaveAttribute(
      'data-live-alert-kind',
      'info',
    )

    fireEvent.click(screen.getByRole('button', { name: '오류' }))
    const error = screen.getByRole('alert')
    expect(error).toHaveAttribute('aria-atomic', 'true')
    expect(error).not.toHaveAttribute('aria-live')
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('성공·정보는 6초, 오류는 10초 뒤 사라진다', () => {
    vi.useFakeTimers()
    renderProvider()

    fireEvent.click(screen.getByRole('button', { name: '성공' }))
    act(() => vi.advanceTimersByTime(5_999))
    expect(screen.getByRole('status')).toBeInTheDocument()
    act(() => vi.advanceTimersByTime(1))
    expect(screen.queryByRole('status')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '오류' }))
    act(() => vi.advanceTimersByTime(9_999))
    expect(screen.getByRole('alert')).toBeInTheDocument()
    act(() => vi.advanceTimersByTime(1))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('80자 이상 메시지는 읽기 시간을 늘리되 15초를 넘지 않는다', () => {
    vi.useFakeTimers()
    renderProvider()

    fireEvent.click(screen.getByRole('button', { name: '긴 알림' }))
    act(() => vi.advanceTimersByTime(10_999))
    expect(screen.getByRole('status')).toBeInTheDocument()
    act(() => vi.advanceTimersByTime(1))
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('새 알림이 이전 알림과 타이머를 교체한다', () => {
    vi.useFakeTimers()
    renderProvider()

    fireEvent.click(screen.getByRole('button', { name: '오류' }))
    act(() => vi.advanceTimersByTime(5_000))
    fireEvent.click(screen.getByRole('button', { name: '성공' }))
    act(() => vi.advanceTimersByTime(5_000))
    expect(screen.getByRole('status')).toHaveTextContent('저장했습니다.')
    act(() => vi.advanceTimersByTime(1_000))
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('같은 문구를 연속 호출해도 새 live node로 다시 알린다', () => {
    renderProvider()

    fireEvent.click(screen.getByRole('button', { name: '성공' }))
    const first = screen.getByRole('status')
    fireEvent.click(screen.getByRole('button', { name: '성공' }))
    expect(screen.getByRole('status')).not.toBe(first)
  })

  it('닫기 버튼은 포커스를 옮기지 않고 현재 알림만 닫는다', () => {
    renderProvider()
    const trigger = screen.getByRole('button', { name: '성공' })
    trigger.focus()
    fireEvent.click(trigger)

    fireEvent.click(screen.getByRole('button', { name: '알림 닫기' }))
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
  })

  it('일반 알림은 pathname·search 이동 시 지운다', () => {
    renderProvider()
    fireEvent.click(screen.getByRole('button', { name: '성공' }))
    fireEvent.click(screen.getByRole('button', { name: '이동' }))
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('저장 성공 알림은 다음 화면까지 한 번만 유지한다', () => {
    renderProvider()
    fireEvent.click(screen.getByRole('button', { name: '이동 성공' }))
    fireEvent.click(screen.getByRole('button', { name: '이동' }))
    expect(screen.getByRole('status')).toHaveTextContent('이동해도 보입니다.')

    fireEvent.click(screen.getByRole('button', { name: '다시 이동' }))
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('StrictMode 재실행과 unmount에서 예약 타이머를 정리한다', () => {
    vi.useFakeTimers()
    const clear = vi.spyOn(window, 'clearTimeout')
    const view = renderProvider({ strict: true })
    fireEvent.click(screen.getByRole('button', { name: '성공' }))

    view.unmount()
    expect(clear).toHaveBeenCalled()
    expect(vi.getTimerCount()).toBe(0)
  })
})
