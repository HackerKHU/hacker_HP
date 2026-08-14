import { type FormEvent, useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  type ApproveFailureReason,
  approve,
  list,
  updateStatus,
} from '@/api/adminUsers'
import { ApiError } from '@/api/client'
import type { Page, Role, User, UserStatus } from '@/api/types'
import { useSession } from '@/auth/session'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { lookup } from '@/lib/lookup'

const PAGE_SIZE = 20

/**
 * 실패 사유별 문구 (§3-2-6).
 *
 * **하나로 뭉치면 거짓 안내가 된다.** 두 관리자가 같은 신청을 연달아 처리하거나 오래된
 * 선택에 이미 승인된 계정이 섞이면 서버는 `NOT_PENDING`을 주는데, 그것을 "신청서를 내지
 * 않았다"고 옮기면 운영자가 그 사람에게 신청서를 내라고 연락하게 된다.
 */
const FAILURE_TEXT: Record<ApproveFailureReason, string> = {
  NOT_APPLIED: '신청서를 내지 않은 계정',
  NOT_PENDING: '이미 승인되었거나 정지된 계정',
  NOT_FOUND: '찾을 수 없는 계정',
}

const FAILURE_ORDER: ApproveFailureReason[] = [
  'NOT_APPLIED',
  'NOT_PENDING',
  'NOT_FOUND',
]

/**
 * 실패를 사유별로 묶어 "무엇이 왜 실패했는지"를 한 줄로 만든다.
 *
 * 이름을 찾지 못하면 id로 적는다. 지워진 계정(`NOT_FOUND`)은 목록에 없어 이름을 알 수
 * 없는데, 그렇다고 빼 버리면 **건수와 나열된 사람 수가 어긋난다.**
 */
function describeFailures(
  failed: { userId: number; reason: ApproveFailureReason }[],
  rows: User[],
): string {
  return FAILURE_ORDER.filter((reason) =>
    failed.some((failure) => failure.reason === reason),
  )
    .map((reason) => {
      const who = failed
        .filter((failure) => failure.reason === reason)
        .map(
          ({ userId }) =>
            rows.find((user) => user.id === userId)?.name ?? `#${userId}`,
        )
      return `${FAILURE_TEXT[reason]}: ${who.join(', ')}`
    })
    .join(' / ')
}

/** 확인 창을 거쳐야 하는 조작. 승인은 한 명이든 여럿이든 같은 모양이다. */
type PendingAction =
  | { kind: 'approve'; ids: number[] }
  | { kind: 'status'; user: User; next: 'ACTIVE' | 'SUSPENDED' }

/**
 * 상태 표시. **`PENDING`을 신청 여부로 가른다** (spec §3-1-4).
 *
 * 신청서를 내지 않은 사람은 승인을 기다리는 게 아니라 아직 신청을 안 한 것이다. 둘 다
 * "승인 대기"로 쓰면 그 구분이 사라지고, 사라진 구분을 액션 칸이 문구로 대신 떠맡게 된다 —
 * 실제로 그랬다. 상태 칸에서 갈라야 액션 칸이 버튼 하나로 통일된다.
 */
function statusLabel(user: User): string {
  if (user.status === 'PENDING') {
    return user.appliedAt === null ? '미승인' : '승인 대기'
  }
  return user.status === 'ACTIVE' ? '활동중' : '정지'
}

/**
 * 상태 필터의 라벨. **API 값(`PENDING` 등)과 표시 이름은 다른 층위다.**
 *
 * `PENDING`을 "미승인"이라 부르는 것은 이 필터가 `PENDING` 전부를 데려오기 때문이다 —
 * 행에 "미승인"으로 뜨는 계정과 "승인 대기"로 뜨는 계정이 함께 온다. "승인 대기"라고
 * 적으면 필터가 거짓말을 하므로 둘을 아우르는 이름을 쓴다.
 *
 * **서버에는 둘을 가르는 방법이 이미 있다** — `applied` 파라미터다 (spec §3-2-6).
 * 화면이 아직 쓰지 않을 뿐이고, 붙이면 이 라벨도 "승인 대기"로 좁힐 수 있다.
 *
 * **같은 낱말이 두 범위로 쓰인다.** 여기(필터)의 "미승인"은 `PENDING` 전부이고,
 * `statusLabel()`이 행에 붙이는 "미승인"은 그중 신청서를 내지 않은 계정만이다.
 */
const STATUS_FILTERS: Record<UserStatus, string> = {
  PENDING: '미승인',
  ACTIVE: '활동중',
  SUSPENDED: '정지',
}

const ROLE_LABEL: Record<Role, string> = { USER: '부원', ADMIN: '관리자' }

/** 정렬 옵션. 값은 API의 `sort` 파라미터 그대로다 (계약 §3-2-6). */
const SORTS = [
  { value: '', label: '신청일 최신순' },
  { value: 'name', label: '이름순' },
  { value: 'studentNo', label: '학번순' },
] as const

/**
 * **승인 대상인가.** `status = PENDING AND applied_at IS NOT NULL` (계약 §3-2-6 MUST).
 *
 * 구글 로그인만 하고 신청서를 내지 않은 계정은 승인 대상이 아니다. 승인해버리면 학번이
 * 빈 `ACTIVE`가 만들어진다. 서버가 이 조건으로 거르지만 화면도 같은 조건으로 잠근다 —
 * 선택할 수 없는 이유가 보여야 관리자가 헤매지 않는다.
 */
function isApprovable(user: User): boolean {
  return user.status === 'PENDING' && user.appliedAt !== null
}

/**
 * 날짜 표시. **"가입 신청일"은 `appliedAt`이다** (2-2 §2-2-1 MUST) — `createdAt`(첫 구글
 * 로그인)이 아니다. 둘은 며칠 차이가 나므로 여기서 바꿔 쓰면 운영자가 다른 날짜를 보고
 * 판단한다.
 */
function formatDate(iso: string | null): string {
  if (iso === null) return '—'
  return new Date(iso).toLocaleDateString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
}

function parsePage(raw: string | null): number {
  const value = Number(raw ?? '0')
  if (!Number.isFinite(value)) return 0
  return Math.max(0, Math.floor(value))
}

export function MemberListPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const { reportApiError } = useSession()

  const page = parsePage(searchParams.get('page'))
  const status = (searchParams.get('status') ?? '') as UserStatus | ''
  const role = (searchParams.get('role') ?? '') as Role | ''
  const sort = searchParams.get('sort') ?? ''
  const keyword = searchParams.get('q') ?? ''

  // 입력 중인 검색어는 URL과 분리한다. 글자마다 조회하면 요청이 쏟아진다.
  const [draft, setDraft] = useState(keyword)
  const [data, setData] = useState<Page<User> | null>(null)
  const [failed, setFailed] = useState(false)
  const [reloadKey, setReloadKey] = useState(0)

  /** 선택은 **id로 들고 있는다.** 인덱스로 들면 정렬·페이지가 바뀔 때 엉뚱한 사람이 남는다. */
  const [selected, setSelected] = useState<number[]>([])
  /**
   * 조회 조건이 바뀔 때 "직전에 무엇이 선택돼 있었는지"를 읽으려고 둔다.
   * `selected`를 effect의 의존성에 넣으면 선택할 때마다 목록을 다시 부른다.
   */
  const selectedRef = useRef<number[]>([])

  function setSelection(next: number[]) {
    selectedRef.current = next
    setSelected(next)
  }
  /**
   * 확인을 기다리는 조작. **되돌릴 수 없는 것은 전부 여기를 지난다** — 일괄 승인, 행 단위
   * 승인, 정지·해제. 정지는 즉시 로그인을 막으므로(2-2 §2-2-3 MUST) 승인만 확인받고
   * 정지는 그냥 나가면 앞뒤가 안 맞는다.
   */
  const [pending, setPending] = useState<PendingAction | null>(null)
  /** 조회 조건이 바뀔 때 "확인창이 열려 있었는지"를 읽으려고 둔다. `selectedRef`와 같은 이유다. */
  const pendingRef = useRef<PendingAction | null>(null)

  function setConfirm(next: PendingAction | null) {
    pendingRef.current = next
    setPending(next)
  }
  const [working, setWorking] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)

  // biome-ignore lint/correctness/useExhaustiveDependencies: reloadKey는 본문에서 읽지 않고 재조회 트리거로만 쓴다.
  useEffect(() => {
    let alive = true
    setData(null)
    setFailed(false)
    list({
      page,
      size: PAGE_SIZE,
      q: keyword || undefined,
      status: status || undefined,
      role: role || undefined,
      sort: sort || undefined,
    })
      .then((result) => {
        if (!alive) return
        setData(result)
      })
      .catch((error: unknown) => {
        if (!alive) return
        reportApiError(error)
        setFailed(true)
      })
    return () => {
      alive = false
    }
  }, [page, keyword, status, role, sort, reloadKey, reportApiError])

  /**
   * 조회 조건이 바뀌면 선택을 버린다. 다른 페이지·다른 조건에서 고른 사람이 남아 있으면
   * **화면에 보이지 않는 사람을 승인하게 된다** — 되돌릴 수 없는 조작이다.
   *
   * 다만 **말없이 지우지 않는다.** 관리자는 자기가 고른 것이 아직 살아 있다고 믿는다.
   * 선택이 있었을 때만 알린다 — 없었는데 안내가 뜨면 그건 소음이다.
   */
  // biome-ignore lint/correctness/useExhaustiveDependencies: 조회 조건은 본문에서 읽지 않고 "조건이 바뀌었다"는 신호로만 쓴다.
  useEffect(() => {
    const dropped = selectedRef.current.length
    const hadConfirm = pendingRef.current !== null
    if (dropped === 0 && !hadConfirm) return

    setSelection([])
    /*
     * **열려 있던 확인창도 함께 닫는다.** 확인창은 조건이 바뀌기 전 목록의 대상을 들고
     * 있다. 그대로 두면 뒤로가기로 다른 조건으로 돌아온 뒤 확인을 눌러 **화면에 보이지도
     * 않는 사람을 승인하게 된다.** 승인은 되돌릴 수 없다.
     */
    setConfirm(null)
    setNotice(
      hadConfirm
        ? dropped > 0
          ? `조회 조건이 바뀌어 진행 중이던 확인을 닫고 선택한 ${dropped}명이 해제되었습니다.`
          : '조회 조건이 바뀌어 진행 중이던 확인을 닫았습니다.'
        : `조회 조건이 바뀌어 선택한 ${dropped}명이 해제되었습니다.`,
    )
  }, [page, keyword, status, role, sort])

  /**
   * URL의 검색어가 바뀌면 입력창도 따라간다.
   *
   * 뒤로가기로 돌아왔을 때 **목록은 A인데 입력창은 B**로 남으면, 관리자가 화면에 적힌
   * 조건과 다른 명단을 보고 승인한다. 승인은 되돌릴 수 없다.
   */
  useEffect(() => {
    setDraft(keyword)
  }, [keyword])

  /**
   * 범위를 넘은 `page`로 들어오면 마지막 유효 페이지로 되돌린다 (`NoticeListPage`와 같은 규칙).
   * 그냥 두면 `?page=999`가 "1000 / 3"으로 굳어 이전 버튼을 999번 눌러야 빠져나온다.
   *
   * `totalPages`가 0이면 되돌릴 유효 페이지가 없으므로 움직이지 않는다.
   */
  useEffect(() => {
    if (!data) return
    const { totalPages } = data.page
    if (totalPages >= 1 && page >= totalPages) {
      const next = new URLSearchParams(searchParams)
      next.set('page', String(totalPages - 1))
      setSearchParams(next, { replace: true })
    }
  }, [data, page, searchParams, setSearchParams])

  /** 파라미터를 바꾸면 언제나 첫 페이지로 돌아간다. 3페이지에서 조건을 바꾸면 빈 화면이 뜬다. */
  function setParam(key: string, value: string) {
    const next = new URLSearchParams(searchParams)
    if (value === '') next.delete(key)
    else next.set(key, value)
    next.delete('page')
    setSearchParams(next)
  }

  function submitSearch(event: FormEvent) {
    event.preventDefault()
    setParam('q', draft.trim())
  }

  const rows = data?.content ?? []
  /** **이 페이지에서** 승인할 수 있는 사람. 전체 선택의 범위가 이것이다. */
  const approvableHere = rows.filter(isApprovable)
  const allSelected =
    approvableHere.length > 0 &&
    approvableHere.every((user) => selected.includes(user.id))

  /** 확인 창이 물어볼 문장. 무엇을 누구에게 하는지 드러나야 한다. */
  function describe(action: PendingAction): { title: string; body: string } {
    if (action.kind === 'approve') {
      const names = action.ids
        .map((id) => rows.find((user) => user.id === id)?.name)
        .filter(Boolean)
        .join(', ')
      return {
        title: '선택한 회원을 승인할까요?',
        body: `${action.ids.length}명을 승인합니다: ${names}`,
      }
    }
    const suspending = action.next === 'SUSPENDED'
    return {
      title: suspending ? '회원을 정지할까요?' : '정지를 해제할까요?',
      body: suspending
        ? `${action.user.name} 회원을 정지합니다. 정지되면 즉시 로그인할 수 없습니다.`
        : `${action.user.name} 회원의 정지를 해제합니다.`,
    }
  }

  function toggleAll(next: boolean) {
    setSelection(next ? approvableHere.map((user) => user.id) : [])
  }

  function toggleOne(id: number, next: boolean) {
    setSelection(
      next
        ? [...selectedRef.current, id]
        : selectedRef.current.filter((value) => value !== id),
    )
  }

  function run(action: PendingAction) {
    return action.kind === 'approve'
      ? runApprove(action.ids)
      : runStatus(action.user, action.next)
  }

  async function runApprove(ids: number[]) {
    setWorking(true)
    setNotice(null)
    try {
      const result = await approve(ids)
      /*
       * **성공·실패 건수를 안내한다** (2-2 §2-2-2 MUST). 실패가 있으면 누구인지도 말한다 —
       * 건수만으로는 무엇을 조치해야 할지 알 수 없다.
       */
      setNotice(
        result.failed.length === 0
          ? `${result.approved.length}명을 승인했습니다.`
          : `${result.approved.length}명을 승인하고 ${result.failed.length}명은 실패했습니다.` +
              ` ${describeFailures(result.failed, rows)}`,
      )
      /*
       * **보낸 사람만 선택에서 뺀다. 전체를 비우지 않는다.**
       *
       * 행 승인은 한 명만 처리하는데 선택 전체를 비우면, 조회 조건이 바뀌지도 않았고
       * 여전히 승인 대상인 다른 선택이 안내 없이 사라진다 — 조건 변경 때 지적받은 것과
       * 같은 일이 다른 경로에서 다시 일어난다.
       *
       * **실패한 사람도 뺀다.** 세 사유 모두 재조회 뒤에는 승인 대상이 아니다
       * (`isApprovable`) — 체크박스가 잠기거나 행이 아예 사라진다. 선택에만 남으면
       * 관리자는 그것을 **해제할 수도 없는데** 버튼은 계속 "선택한 N명 승인"으로 살아
       * 있어, 언제나 실패하는 요청을 다시 보내게 된다. 누가 왜 실패했는지는 위 안내가
       * 이미 말했고, 다시 보내려면 다시 고르면 된다.
       */
      const settled = [
        ...result.approved,
        ...result.failed.map(({ userId }) => userId),
      ]
      setSelection(selectedRef.current.filter((id) => !settled.includes(id)))
      setReloadKey((key) => key + 1)
    } catch (error: unknown) {
      reportApiError(error)
      // 실패했는데 성공한 것처럼 보이면 안 된다. 선택도 그대로 두어 다시 시도할 수 있게 한다.
      setNotice(
        error instanceof ApiError
          ? `승인하지 못했습니다. ${error.message}`
          : '승인하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      )
    } finally {
      setWorking(false)
      setConfirm(null)
    }
  }

  /**
   * 정지·해제.
   *
   * **마지막 활성 관리자인지 화면은 판단하지 않는다** (2-2 §2-2-7 — 검사는 서버에서 한다).
   * 화면은 활성 관리자가 몇 명인지 모른다. 서버가 403으로 거부하면 그 메시지를 그대로
   * 보여준다 — 막힌 것을 성공처럼 보이게 하면 관리자가 정지된 줄 알고 자리를 뜬다.
   */
  async function runStatus(user: User, next: 'ACTIVE' | 'SUSPENDED') {
    setWorking(true)
    setNotice(null)
    try {
      await updateStatus(user.id, next)
      setNotice(
        `${user.name} 회원을 ${next === 'SUSPENDED' ? '정지' : '정지 해제'}했습니다.`,
      )
      setReloadKey((key) => key + 1)
    } catch (error: unknown) {
      reportApiError(error)
      setNotice(
        error instanceof ApiError
          ? `상태를 바꾸지 못했습니다. ${error.message}`
          : '상태를 바꾸지 못했습니다. 잠시 후 다시 시도해 주세요.',
      )
    } finally {
      setWorking(false)
      setConfirm(null)
    }
  }

  return (
    <section>
      <h1 className="text-2xl font-semibold tracking-tight">회원 관리</h1>

      <form
        onSubmit={submitSearch}
        className="mt-6 flex flex-wrap items-end gap-3"
      >
        <div className="grow space-y-2 sm:grow-0">
          <Label htmlFor="member-search">검색</Label>
          <Input
            id="member-search"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            placeholder="이름 · 학번 · 이메일"
            className="sm:w-64"
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="member-status">상태</Label>
          <select
            id="member-status"
            value={status}
            onChange={(event) => setParam('status', event.target.value)}
            className="h-9 rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50"
          >
            <option value="">전체</option>
            {Object.entries(STATUS_FILTERS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </div>

        <div className="space-y-2">
          <Label htmlFor="member-role">권한</Label>
          <select
            id="member-role"
            value={role}
            onChange={(event) => setParam('role', event.target.value)}
            className="h-9 rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50"
          >
            <option value="">전체</option>
            {Object.entries(ROLE_LABEL).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </div>

        <div className="space-y-2">
          <Label htmlFor="member-sort">정렬</Label>
          <select
            id="member-sort"
            value={sort}
            onChange={(event) => setParam('sort', event.target.value)}
            className="h-9 rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50"
          >
            {SORTS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>

        <Button type="submit" variant="outline">
          검색
        </Button>
      </form>

      {notice && (
        <p role="status" className="mt-6 text-sm text-foreground">
          {notice}
        </p>
      )}

      {failed && (
        <p role="alert" className="mt-6 text-sm text-muted-foreground">
          회원 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
        </p>
      )}

      {data === null && !failed && (
        <p className="mt-6 text-sm text-muted-foreground">불러오는 중</p>
      )}

      {data !== null && (
        <>
          <div className="mt-6 flex flex-wrap items-center justify-between gap-3">
            {/*
             * **선택 범위를 화면이 분명히 말한다.** 전체 선택은 이 페이지만이다 — 검색
             * 결과 전부를 뜻하면 관리자가 3페이지째 있는 사람까지 모르고 승인한다.
             * 범위를 말하지 않고 페이지만 선택하는 것도 관리자를 속이는 것이다.
             */}
            <p className="text-sm text-muted-foreground">
              이 페이지에서 {selected.length}명 선택됨 (승인 가능{' '}
              {approvableHere.length}명 · 전체 {data.page.totalElements}명)
            </p>
            <Button
              type="button"
              disabled={selected.length === 0 || working}
              onClick={() => setConfirm({ kind: 'approve', ids: selected })}
            >
              선택한 {selected.length}명 승인
            </Button>
          </div>

          <Table className="mt-4">
            <TableHeader>
              <TableRow>
                <TableHead className="w-10">
                  <Checkbox
                    checked={allSelected}
                    disabled={approvableHere.length === 0}
                    onCheckedChange={(next) => toggleAll(next === true)}
                    aria-label="이 페이지의 승인 대상 전체 선택"
                  />
                </TableHead>
                <TableHead>이름</TableHead>
                <TableHead>학번</TableHead>
                <TableHead>이메일</TableHead>
                <TableHead>권한</TableHead>
                <TableHead>상태</TableHead>
                <TableHead>가입 신청일</TableHead>
                <TableHead>승인일</TableHead>
                <TableHead className="text-right">관리</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((user) => {
                const approvable = isApprovable(user)
                return (
                  <TableRow key={user.id}>
                    <TableCell>
                      <Checkbox
                        checked={selected.includes(user.id)}
                        disabled={!approvable}
                        onCheckedChange={(next) =>
                          toggleOne(user.id, next === true)
                        }
                        aria-label={`${user.name} 선택`}
                      />
                    </TableCell>
                    <TableCell className="font-medium">{user.name}</TableCell>
                    <TableCell>{user.studentNo ?? '—'}</TableCell>
                    <TableCell className="text-muted-foreground">
                      {user.email}
                    </TableCell>
                    <TableCell>
                      {lookup(ROLE_LABEL, user.role) ?? '—'}
                    </TableCell>
                    {/*
                     * "미승인"과 "승인 대기"가 여기서 갈린다. 그래서 액션 칸이 왜
                     * 비어 있는지 상태만 봐도 자명하다 — 문구를 덧붙이지 않는다.
                     */}
                    <TableCell>{statusLabel(user)}</TableCell>
                    <TableCell>{formatDate(user.appliedAt)}</TableCell>
                    <TableCell>{formatDate(user.approvedAt)}</TableCell>
                    {/*
                     * **액션은 상태마다 버튼 하나다.** 한 명만 승인하려고 체크박스를
                     * 거치게 하지 않는다 — 여럿은 체크박스, 한 명은 이 자리다.
                     * "미승인"(신청서를 내지 않은 계정)은 할 수 있는 것이 없어 비어 있다.
                     */}
                    <TableCell className="text-right">
                      {approvable && (
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          disabled={working}
                          onClick={() =>
                            setConfirm({ kind: 'approve', ids: [user.id] })
                          }
                        >
                          승인
                        </Button>
                      )}
                      {user.status !== 'PENDING' && (
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          disabled={working}
                          onClick={() =>
                            setConfirm({
                              kind: 'status',
                              user,
                              next:
                                user.status === 'SUSPENDED'
                                  ? 'ACTIVE'
                                  : 'SUSPENDED',
                            })
                          }
                        >
                          {user.status === 'SUSPENDED' ? '정지 해제' : '정지'}
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>

          {rows.length === 0 && (
            <p className="mt-6 text-sm text-muted-foreground">
              조건에 맞는 회원이 없습니다.
            </p>
          )}

          {data.page.totalPages > 1 && (
            <div className="mt-8 flex items-center justify-center gap-4">
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={page === 0}
                onClick={() =>
                  setSearchParams(pageParams(searchParams, page - 1))
                }
              >
                이전
              </Button>
              <span className="text-sm text-muted-foreground tabular-nums">
                {page + 1} / {data.page.totalPages}
              </span>
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={page >= data.page.totalPages - 1}
                onClick={() =>
                  setSearchParams(pageParams(searchParams, page + 1))
                }
              >
                다음
              </Button>
            </div>
          )}
        </>
      )}

      <AlertDialog
        open={pending !== null}
        onOpenChange={(open) => {
          if (!open) setConfirm(null)
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {pending ? describe(pending).title : ''}
            </AlertDialogTitle>
            {/* 누구를 어떻게 하는지 이름으로 보여준다. 건수만으로는 확인이 안 된다. */}
            <AlertDialogDescription>
              {pending ? describe(pending).body : ''}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                if (pending) run(pending)
              }}
            >
              {pending?.kind === 'approve'
                ? '승인'
                : pending?.next === 'SUSPENDED'
                  ? '정지'
                  : '정지 해제'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </section>
  )
}

/** 페이지만 바꾸고 검색·필터는 유지한다. */
function pageParams(current: URLSearchParams, next: number): URLSearchParams {
  const params = new URLSearchParams(current)
  if (next <= 0) params.delete('page')
  else params.set('page', String(next))
  return params
}
