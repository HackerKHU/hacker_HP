import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import App from './App'

describe('App', () => {
  it('서비스 준비 상태를 보여준다', () => {
    render(<App />)

    expect(
      screen.getByRole('heading', {
        name: '동아리 홈페이지를 준비하고 있어요.',
      }),
    ).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('서비스 준비 중')
  })
})
