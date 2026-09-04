import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ListSurface } from './ListSurface'

describe('ListSurface', () => {
  it('내용 높이만 감싸 행이 적을 때 빈 영역 끝에 아래 선을 만들지 않는다', () => {
    render(
      <ListSurface data-testid="surface">
        <div>한 행</div>
      </ListSurface>,
    )

    const surface = screen.getByTestId('surface')
    expect(surface.className).toContain('border-y')
    expect(surface.className).not.toContain('min-h-72')
  })
})
