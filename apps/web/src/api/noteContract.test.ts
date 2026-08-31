import { describe, expect, it } from 'vitest'
import { normalizeNoteTitle } from './noteContract'

describe('자료 제목 정규화', () => {
  it('Java String.trim과 같이 양끝의 U+0000~U+0020만 제거한다', () => {
    expect(normalizeNoteTitle('\u0000\t 제목 \u001f')).toBe('제목')
    expect(normalizeNoteTitle('\u00a0제목\u00a0')).toBe('\u00a0제목\u00a0')
  })
})
