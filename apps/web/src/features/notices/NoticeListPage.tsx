import { Pin } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { list, type Notice, togglePin } from '@/api/notices'
import type { Page } from '@/api/types'
import { useSession } from '@/auth/session'
import { ListSurface } from '@/components/ListSurface'
import { useLiveAlert } from '@/components/live-alert/LiveAlertProvider'
import {
  KOREAN_PAGER_LABELS,
  Pager,
  parsePage,
  writePage,
} from '@/components/Pager'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { cn } from '@/lib/utils'

const PAGE_SIZE = 10

/** 며칠 이내를 새글로 볼지. 7일로 바꾸자는 말이 나오면 여기만 고친다. */
const NEW_WITHIN_DAYS = 3
const DAY_MS = 24 * 60 * 60 * 1000

/**
 * 새글 판정은 **사용자 PC 시계 기준**이다. 시계가 틀어져 있으면 경계에 걸린 글의 표시가
 * 어긋날 수 있다. 서버가 계산해 내려주면 정확하지만 응답 필드가 하나 늘고 계약이 커진다 —
 * 동아리 사이트에서 새글 표시가 하루 어긋나는 것은 감당 가능한 수준이라고 봤다.
 */
function isNew(createdAt: string): boolean {
  return Date.now() - new Date(createdAt).getTime() < NEW_WITHIN_DAYS * DAY_MS
}

/** 서버는 UTC로 내려준다. 목록에서는 날짜까지만 보여준다. */
function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
}

/**
 * 이 화면의 주소에는 `page` 말고 다른 조회 조건이 없다. 그래서 매번 새로 만든다.
 *
 * <b>컴포넌트 밖에 둔다</b> — 안에 두면 렌더마다 새 함수가 되어, 이것을 쓰는 effect가 매 렌더
 * 다시 돌거나 의존성에서 빠진 채 남는다.
 */
function pageParams(next: number): URLSearchParams {
  const params = new URLSearchParams()
  writePage(params, next)
  return params
}

export function NoticeListPage() {
  // 페이지 번호는 URL에 둔다. 컴포넌트 state에만 두면 새로고침·뒤로가기로 돌아왔을 때
  // 보던 페이지가 날아간다.
  //
  // URL의 `page`는 API 파라미터와 같은 **0-기반**이다 (spec §3-2-8). 화면 라벨만 1을
  // 더해 보여준다 — URL과 API 사이에 변환을 두면 그 자리가 off-by-one이 사는 곳이 된다.
  const [searchParams, setSearchParams] = useSearchParams()
  const { pathname } = useLocation()
  const page = parsePage(searchParams.get('page'))

  const { state, reportApiError } = useSession()
  const alert = useLiveAlert()
  const isAdmin = state.kind === 'active' && state.user.role === 'ADMIN'

  const [data, setData] = useState<Page<Notice> | null>(null)
  const [failed, setFailed] = useState(false)
  // 관리 모드는 URL에 넣지 않는다. 페이지 번호와 달리 공유하거나 새로고침으로 유지할
  // 이유가 없는 일시적 UI 상태다.
  const [managing, setManaging] = useState(false)
  // 토글은 목록 정렬을 바꾼다. 하나가 진행 중이면 전부 잠근다 — 항목별로 추적하는 것보다
  // 짧고, 동시에 여러 개를 누르는 것이 애초에 의미가 없다.
  const [pinning, setPinning] = useState(false)
  // 재조회 트리거. 값을 올리면 아래 effect가 다시 돈다.
  const [reloadKey, setReloadKey] = useState(0)

  /**
   * **목록을 조회하는 유일한 경로다.** 토글 후 재조회도 `reloadKey`를 올려 이 effect를
   * 다시 태운다 — 명령형으로 따로 부르면 그 요청은 cleanup이 취소하지 못해서,
   * 페이지를 옮기는 중 토글하면 이전 페이지의 응답이 새 페이지 데이터를 덮어쓴다.
   */
  // biome-ignore lint/correctness/useExhaustiveDependencies: reloadKey는 본문에서 읽지 않고 재조회 트리거로만 쓴다.
  useEffect(() => {
    let alive = true
    setData(null)
    setFailed(false)
    list({ page, size: PAGE_SIZE })
      .then((result) => {
        if (alive) setData(result)
      })
      .catch((error: unknown) => {
        if (!alive) return
        // #36 계약 — 403 PENDING_APPROVAL이면 가드가 대기 화면으로 되돌린다.
        reportApiError(error)
        setFailed(true)
      })
    return () => {
      alive = false
    }
  }, [page, reloadKey, reportApiError])

  /**
   * F-2 — 범위를 넘은 `page`로 들어오면 마지막 유효 페이지로 되돌린다.
   * 그냥 두면 공지가 있는데도 "등록된 공지가 없습니다"가 뜬다.
   *
   * `totalPages`가 0이면(공지가 하나도 없으면) 움직이지 않는다. 되돌릴 유효 페이지가
   * 없어서 무한히 이동하게 된다. 이동 후에는 `page < totalPages`가 되어 조건이 다시
   * 참이 되지 않으므로 루프가 생기지 않는다.
   */
  useEffect(() => {
    if (!data) return
    const { totalPages } = data.page
    if (totalPages >= 1 && page >= totalPages) {
      setSearchParams(pageParams(totalPages - 1), { replace: true })
    }
  }, [data, page, setSearchParams])

  /**
   * 페이지 파라미터를 만드는 **유일한 규칙**. `goTo`(클릭 이동)와 `hrefFor`(링크 주소)가
   * 이걸 같이 쓴다 — 두 곳이 다른 규칙을 쓰면 링크가 가리키는 곳과 클릭 결과가 갈린다.
   * 0페이지는 파라미터를 빼서 주소가 깨끗해진다.
   */
  function goTo(next: number) {
    setSearchParams(pageParams(next))
  }

  /**
   * 링크가 실제 대상 주소를 가리키게 한다. `href="#"`이면 새 탭으로 열거나 주소를 복사할 때
   * 엉뚱한 곳이 열린다 — 페이지 번호를 URL에 둔 이 화면의 설계와 앞뒤가 맞지 않는다.
   * 클릭은 여전히 `preventDefault` 후 `setSearchParams`를 타므로 전체 새로고침이 나지 않는다.
   */
  function hrefFor(next: number): string {
    const search = pageParams(next).toString()
    return search === '' ? pathname : `${pathname}?${search}`
  }

  /**
   * 고정 토글.
   *
   * 낙관적 업데이트를 하지 않는다. 토글은 정렬 순서까지 바꾸므로 화면에서 미리 흉내내려면
   * 클라이언트가 정렬을 다시 구현해야 하고, 그러면 서버 정렬을 덮어쓰는 코드가 하나 더
   * 생긴다. 되돌릴 것도 없어진다.
   */
  async function handleTogglePin(id: number) {
    setPinning(true)
    try {
      await togglePin(id)
      alert.success('공지의 고정 상태를 바꿨습니다.')
      setReloadKey((key) => key + 1)
    } catch (error: unknown) {
      if (!reportApiError(error)) {
        alert.error('고정 상태를 바꾸지 못했습니다. 다시 시도해 주세요.')
      }
    } finally {
      setPinning(false)
    }
  }

  return (
    <section>
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold tracking-tight">공지사항</h1>

        {/*
         * 관리 버튼은 ADMIN에게만 보인다. **노출 제어일 뿐 권한 통제가 아니다**
         * (spec §3-1-7). 서버가 `PATCH /notices/{id}/pin`을 ADMIN으로 막는 것이 전제이고,
         * 여기를 뚫어도 서버가 거부한다.
         *
         * 평소 목록은 읽기 화면으로 깨끗하게 둔다 — 토글을 항상 노출하면 읽으러 온
         * 관리자가 실수로 누른다.
         */}
        {isAdmin && (
          <div className="flex shrink-0 gap-2">
            <Button variant="outline" size="sm" asChild>
              <Link to="/admin/notices/new">글쓰기</Link>
            </Button>
            <Button
              variant={managing ? 'secondary' : 'outline'}
              size="sm"
              aria-pressed={managing}
              onClick={() => setManaging((on) => !on)}
            >
              관리
            </Button>
          </div>
        )}
      </div>

      <div className="mt-6 min-h-72" data-list-surface="notices">
        {data === null && !failed && (
          <p className="mt-8 text-sm text-muted-foreground">불러오는 중</p>
        )}

        {failed && (
          <div className="mt-8 space-y-4">
            <p role="alert" className="text-sm text-muted-foreground">
              공지를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
            </p>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setReloadKey((key) => key + 1)}
            >
              다시 시도
            </Button>
          </div>
        )}

        {data !== null && data.content.length === 0 && (
          <p className="mt-8 text-sm text-muted-foreground">
            등록된 공지가 없습니다.
          </p>
        )}

        {data !== null && data.content.length > 0 && (
          <ListSurface>
            {/*
             * **자료게시판과 같은 표다** (`NoteTable`). 행 전체를 링크로 만들지 않고
             * **제목 셀만 링크로 둔다** — 좋아요·작성자 같은 열이 링크 안에 들어가면
             * 훑는 눈이 어디를 눌러야 상세로 가는지 알 수 없다.
             *
             * `table-fixed` + `min-w`는 좁은 화면에서 열이 뭉개지지 않게 하고, 넘치는
             * 만큼은 `Table`의 컨테이너가 가로로 스크롤한다.
             */}
            {/*
             * **제목만 왼쪽, 나머지는 가운데다.** 제목까지 가운데로 두면 글 길이마다
             * 왼쪽 끝이 달라져 목록을 훑는 눈이 매번 자리를 다시 잡는다 — 게시판 목록이
             * 제목만 왼쪽에 두는 이유다.
             *
             * 표에 `text-center`를 걸어 `<td>`로 상속시키고, `TableHead`가 들고 있는
             * `text-left`는 열마다 덮는다. 제목 열만 그 기본값을 그대로 쓴다.
             */}
            <Table className="table-fixed min-w-[640px] text-center">
              <TableHeader>
                <TableRow>
                  <TableHead>제목</TableHead>
                  <TableHead className="w-28 text-center">작성자</TableHead>
                  {/* 조회수 열은 아직 없다: 공지 응답에 필드가 없다 (#375). */}
                  <TableHead className="w-20 text-center">좋아요</TableHead>
                  <TableHead className="w-28 text-center">등록일</TableHead>
                  {/* 고정 토글 열은 이름이 없다 — 버튼만 있는 칸이라 이름을 붙이면 폭만 먹는다. */}
                  {managing && isAdmin && (
                    <TableHead className="w-24 text-center" />
                  )}
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.content.map((notice) => (
                  <TableRow key={notice.id}>
                    {/*
                     * 무채색 팔레트라 색으로 구분할 수 없다. 고정 공지는 좌측 세로 바와
                     * 핀 아이콘, 순검정 제목으로 가른다. **바는 첫 셀이 든다** — 행이 더는
                     * 링크가 아니라서 예전에 바를 들고 있던 요소가 없어졌다.
                     */}
                    <TableCell
                      className={cn(
                        'max-w-0 border-l-[3px] text-left font-medium',
                        notice.isPinned
                          ? 'border-l-primary'
                          : 'border-l-transparent',
                      )}
                    >
                      <Link
                        to={`/notices/${notice.id}`}
                        className={cn(
                          'flex items-center gap-2 underline-offset-4 hover:underline',
                          notice.isPinned ? 'text-primary' : 'text-foreground',
                        )}
                      >
                        {notice.isPinned && (
                          <>
                            <Pin
                              className="size-3.5 shrink-0"
                              aria-hidden="true"
                            />
                            {/* 아이콘만 두면 스크린리더가 못 읽는다. 의미는 남긴다. */}
                            <span className="sr-only">고정</span>
                          </>
                        )}
                        <span className="min-w-0 truncate" title={notice.title}>
                          {notice.title}
                        </span>
                        {isNew(notice.createdAt) && (
                          // 고정(핀 + 세로 바)이 강한 표시라 새글은 테두리만 있는 약한
                          // 라벨로 둔다. 둘 다 채우면 위계가 사라져 아무것도 안 튄다.
                          <Badge
                            variant="outline"
                            className="shrink-0 rounded-sm px-1.5 py-0 text-[10px] leading-none tracking-wide text-muted-foreground"
                          >
                            NEW
                          </Badge>
                        )}
                      </Link>
                    </TableCell>

                    {/* 작성자 이름은 절대 비지 않는다 — 제거되면 "탈퇴한 회원"이다 (§3-2-2). */}
                    <TableCell className="truncate text-muted-foreground">
                      {notice.authorName}
                    </TableCell>

                    {/*
                     * **숫자만이다.** 목록은 훑는 화면이고 반응은 글을 읽고 남기는 것이라,
                     * 누르는 자리는 상세에만 둔다 (#348 D1). 아이콘도 두지 않는다 — 열
                     * 제목이 이미 "좋아요"라고 말한다.
                     */}
                    <TableCell className="tabular-nums">
                      {notice.likeCount}
                    </TableCell>

                    <TableCell className="whitespace-nowrap text-muted-foreground">
                      <time dateTime={notice.createdAt}>
                        {formatDate(notice.createdAt)}
                      </time>
                    </TableCell>

                    {managing && isAdmin && (
                      <TableCell>
                        <Button
                          variant="ghost"
                          size="sm"
                          disabled={pinning}
                          onClick={() => handleTogglePin(notice.id)}
                        >
                          {notice.isPinned ? '고정 해제' : '고정'}
                        </Button>
                      </TableCell>
                    )}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </ListSurface>
        )}
      </div>

      <Pager
        className="mt-8"
        page={page}
        totalPages={data?.page.totalPages ?? 0}
        hrefFor={hrefFor}
        onGo={goTo}
        labels={KOREAN_PAGER_LABELS}
      />
    </section>
  )
}
