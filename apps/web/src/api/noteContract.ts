/** 새로 등록하거나 바꾸는 자료 제목의 코드포인트 상한. */
export const NOTE_TITLE_MAX = 50

/** DB가 기존 자료 제목을 보존하는 물리 상한. 새 입력 상한과 혼동하지 않는다. */
export const STORED_NOTE_TITLE_MAX = 200

/** Java `String.codePointCount`와 같은 단위로 센다. */
export function countCodePoints(text: string): number {
  return [...text].length
}

/**
 * 서버가 자료 제목을 저장할 때 쓰는 Java `String.trim()`과 같은 정규화.
 *
 * ECMAScript `String.trim()`은 NBSP(U+00A0)까지 없애 서버와 다른 제목을 만들 수 있다.
 * 여기서는 양끝의 U+0000~U+0020 코드 유닛만 제거한다. 이 범위 밖 문자는 공백처럼
 * 보이더라도 제목의 의미 문자로 보존한다.
 */
export function normalizeNoteTitle(title: string): string {
  let start = 0
  let end = title.length
  while (start < end && title.charCodeAt(start) <= 0x20) start += 1
  while (end > start && title.charCodeAt(end - 1) <= 0x20) end -= 1
  return title.slice(start, end)
}
