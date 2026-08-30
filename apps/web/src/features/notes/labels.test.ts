import { describe, expect, it } from 'vitest'
import { SEMESTER_LABEL, semesterFromParam } from './labels'

describe('학기 라벨', () => {
  it('네 학기를 학사 순서와 같은 문구로 제공한다', () => {
    expect(Object.entries(SEMESTER_LABEL)).toEqual([
      ['SPRING', '1학기'],
      ['SUMMER', '여름학기'],
      ['FALL', '2학기'],
      ['WINTER', '겨울학기'],
    ])
  })

  it.each(['SPRING', 'SUMMER', 'FALL', 'WINTER'] as const)(
    '주소의 유효한 학기 %s를 그대로 읽는다',
    (semester) => {
      expect(semesterFromParam(semester)).toBe(semester)
    },
  )

  it.each([null, '', 'AUTUMN', 'summer'])(
    '주소의 잘못된 학기 %s는 필터에서 제거한다',
    (semester) => {
      expect(semesterFromParam(semester)).toBeUndefined()
    },
  )
})
