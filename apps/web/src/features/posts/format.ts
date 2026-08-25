/** 서버는 UTC로 내려준다. 목록에서는 날짜까지만 보여준다. */
export function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
}

/** 상세에서는 분까지 보여준다. 공지 상세와 같은 기준이다. */
export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}
