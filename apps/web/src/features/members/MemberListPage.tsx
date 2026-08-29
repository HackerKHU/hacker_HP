import { MoreHorizontalIcon } from 'lucide-react'
import { type FormEvent, useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  type ApproveFailureReason,
  approve,
  type BulkStatusFailureReason,
  type BulkStatusTarget,
  bulkUpdateStatus,
  type ContentSummary,
  contentSummary,
  type DeactivateFailureReason,
  deactivate,
  list,
  type RejectFailureReason,
  reject,
  remove,
  updateRole,
  updateStatus,
} from '@/api/adminUsers'
import { ApiError } from '@/api/client'
import type { Page, Role, User, UserStatus } from '@/api/types'
import { useSession } from '@/auth/session'
import { clampedOutOfRange } from '@/components/clampPage'
import { ListSurface } from '@/components/ListSurface'
import { useLiveAlert } from '@/components/live-alert/LiveAlertProvider'
import { parsePage, writePage } from '@/components/Pager'
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
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
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

/**
 * 거부 실패 문구 (§3-2-6). 승인과 사유 집합이 달라 따로 둔다 — `NOT_APPLIED`가 없다.
 *
 * `NOT_PENDING`을 "이미 처리됨"으로 뭉개지 않는다. 이 경로로는 이용 중인 회원을 지울 수
 * 없고, 그것은 제거(§2-2-4)라는 별개 조작이다.
 */
const REJECT_FAILURE_TEXT: Record<RejectFailureReason, string> = {
  NOT_PENDING: '승인 대기 상태가 아닌 계정',
  NOT_FOUND: '찾을 수 없는 계정',
}

/**
 * 복구 실패 문구 (§3-2-6). 승인·거부와 사유 집합이 또 다르다.
 *
 * **`NOT_INACTIVE`에 정지된 계정이 섞여 있다.** "이미 활동 중"으로만 옮기면 관리자는
 * 정지된 사람이 올라온 줄 알고 자리를 뜬다 — 그 사람은 복구되지도, 정지가 풀리지도 않았다.
 */
const DEACTIVATE_FAILURE_TEXT: Record<DeactivateFailureReason, string> = {
  NOT_ACTIVE_USER: '활동 중인 일반 부원이 아님',
  NOT_FOUND: '찾을 수 없는 계정',
}

const BULK_STATUS_FAILURE_TEXT: Record<BulkStatusFailureReason, string> = {
  NOT_FOUND: '찾을 수 없는 계정',
  NOT_APPLIED: '신청서를 내지 않은 계정',
  PENDING_NOT_ALLOWED: '승인 대기 상태라 정지할 수 없는 계정',
  ADMIN_SUSPEND_REQUIRES_ROLE_REVOCATION:
    '관리자 권한을 먼저 회수해야 하는 계정',
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
  | {
      kind: 'bulk-status'
      ids: number[]
      target: BulkStatusTarget
    }
  | { kind: 'bulk-deactivate'; ids: number[] }
  | { kind: 'status'; user: User; next: 'ACTIVE' | 'SUSPENDED' }
  | { kind: 'role'; user: User; next: Role }
  | { kind: 'reject'; ids: number[] }
  /*
   * 제거만 확인 창에 **무엇이 남는지**까지 담는다 (2-2 §2-2-4 MUST). 되돌아갈 수단을
   * 없애는 유일한 조작이라, 관계가 끊기고 나면 운영자도 그 회원의 콘텐츠를 찾을 수 없다.
   * `summary`가 `null`이면 아직 불러오는 중이다.
   */
  | {
      kind: 'remove'
      user: User
      /*
       * `null`이면 아직 불러오는 중, `'failed'`면 못 받았다. 셋을 가르는 이유는 확인 창이
       * "0건"과 "모름"을 다르게 말해야 하기 때문이다 — 모르는데 0건으로 보이면 남는 것이
       * 없다고 읽혀 되돌릴 수 없는 조작을 그 전제로 하게 된다.
       */
      summary: ContentSummary | 'failed' | null
      /**
       * 이 확인 창을 연 요청의 세대. 같은 회원을 닫았다 다시 열면 값이 달라진다 —
       * 회원 id만 비교하면 A→닫기→A나 A→B→A에서 취소된 응답이 새 창을 덮는다.
       */
      token: number
    }

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
  if (user.status === 'ACTIVE') return '활동중'
  /*
   * **"비활동"은 "정지"와 같은 낱말이 아니다** (2-2 §2-2-3 MUST, T-362). 뭉치면 관리자가
   * **정지된 사람을 복구하려다 실패하고**, 비활동인 사람은 정지된 줄 안다 — 복구는 id로
   * 고르는 조작이라 이 칸이 고르는 근거 전부다.
   */
  return user.status === 'INACTIVE' ? '비활동' : '정지'
}

/**
 * 상태 배지의 무게 (#99).
 *
 * **무채색 안에서 가른다** — 새 색을 들이지 않는다. `destructive`도 이 팔레트에서는
 * 회색(`--destructive: #525252`)이라 붉은색이 아니다.
 *
 * 주의가 필요한 순서로 무게를 준다: 정지(가장 진함) → 승인 대기 → 활동중 → 미승인.
 * **승인 대기가 활동중보다 무겁다** — 관리자가 이 화면에서 찾는 것이 그 사람들이다.
 */
function statusVariant(
  user: User,
): 'default' | 'secondary' | 'outline' | 'destructive' {
  if (user.status === 'SUSPENDED') return 'destructive'
  /*
   * 비활동은 정지보다 가볍고 활동중보다 무겁다 (#228). 자료가 막혀 있으니 그냥 흘려보낼
   * 상태는 아니지만 **제재가 아니다** — 정지와 같은 무게로 그리면 낱말만 갈라 놓고 화면이
   * 다시 뭉치는 것이다.
   */
  if (user.status === 'INACTIVE') return 'secondary'
  if (user.status === 'ACTIVE') return 'outline'
  // PENDING 둘 — 신청서를 낸 쪽만 승인 대상이라 눈에 걸려야 한다.
  return user.appliedAt === null ? 'outline' : 'default'
}

/**
 * 상태 필터. **API 값과 표시 이름은 다른 층위다.**
 *
 * `PENDING`을 <b>둘로 쪼갠다.</b> 그 상태에는 신청서를 낸 계정과 구글 로그인만 해본 계정이
 * 함께 있고, 관리자가 이 화면에서 가장 자주 하는 일이 **"승인 대기만 보여줘"**이기 때문이다.
 * 서버가 `applied`로 그 둘을 갈라 준다 (spec §3-2-6).
 *
 * **낱말은 행의 상태 칸과 같다** (`statusLabel`). 전에는 필터의 "미승인"이 `PENDING` 전부를
 * 뜻하고 행의 "미승인"은 그중 신청 안 한 계정만을 뜻해 **같은 낱말이 두 범위로 쓰였다.**
 * 이제 두 곳이 같은 것을 가리킨다.
 *
 * `PENDING` 전부를 한 번에 보는 선택지는 두지 않는다 — 위 둘의 합집합이고, "전체"에서
 * 상태 칸이 이미 둘을 갈라 보여준다.
 */
type StatusFilter = {
  /** `<select>`의 값. URL이 아니라 화면 안에서만 쓰는 합성 키다. */
  value: string
  label: string
  status?: UserStatus
  applied?: boolean
}

const STATUS_FILTERS: StatusFilter[] = [
  { value: '', label: '전체' },
  {
    value: 'PENDING:applied',
    label: '승인 대기',
    status: 'PENDING',
    applied: true,
  },
  { value: 'PENDING:none', label: '미승인', status: 'PENDING', applied: false },
  { value: 'ACTIVE', label: '활동중', status: 'ACTIVE' },
  /*
   * **복구가 이 필터에 매달려 있다** (2-2 §2-2-3 MUST). 복구는 id 목록을 받으므로 비활동
   * 회원을 추릴 수 없으면 전원을 내린 뒤 **아무도 다시 올릴 수 없다** — API만 있고 누를
   * 곳이 없는 상태가 된다.
   */
  { value: 'INACTIVE', label: '비활동', status: 'INACTIVE' },
  { value: 'SUSPENDED', label: '정지', status: 'SUSPENDED' },
]

/** URL에 적힌 두 값(`status`·`applied`)을 `<select>`가 아는 하나로 되돌린다. */
function toFilterValue(status: string, applied: string | null): string {
  const found = STATUS_FILTERS.find(
    (filter) =>
      (filter.status ?? '') === status &&
      (filter.applied === undefined
        ? applied === null
        : String(filter.applied) === applied),
  )
  return found?.value ?? ''
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

export function MemberListPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const { reportApiError } = useSession()
  const alert = useLiveAlert()

  const page = parsePage(searchParams.get('page'))
  const status = (searchParams.get('status') ?? '') as UserStatus | ''
  /*
   * URL에 서버 파라미터를 그대로 적는다 (spec §3-2-6). 합성 값을 쓰면 주소만 보고는
   * 무엇을 조회하는지 알 수 없고, 뒤로가기·새로고침·링크 공유에서 되살리기도 어렵다.
   */
  const applied = searchParams.get('applied')
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
  /** 상태·권한·계정을 바꾸는 조작은 모두 이 확인 단계를 지난다. */
  const [pending, setPending] = useState<PendingAction | null>(null)
  /** 조회 조건이 바뀔 때 "확인창이 열려 있었는지"를 읽으려고 둔다. `selectedRef`와 같은 이유다. */
  const pendingRef = useRef<PendingAction | null>(null)
  /** 제거 확인 창의 건수 요청 세대. 늦게 도착한 응답을 가려낸다. */
  const removeToken = useRef(0)

  function setConfirm(next: PendingAction | null) {
    pendingRef.current = next
    setPending(next)
  }

  /**
   * 확인 창을 닫은 뒤 돌아갈 자리 (#99 검수).
   *
   * 행 메뉴에서 열면 **메뉴가 먼저 닫히고 그 항목이 사라진다.** 확인 창에는
   * `AlertDialogTrigger`도 없어서 Radix가 돌려보낼 곳을 못 찾고 포커스가 `<body>`로
   * 떨어진다 — 실제 브라우저에서 확인했다. 키보드로 쓰는 사람은 25행짜리 표에서 자기
   * 위치를 잃는다.
   *
   * 그래서 **트리거(`⋯`)를 직접 들고 있다가** 되돌린다. 활성 요소를 읽는 방식은 안 된다 —
   * 그 시점의 활성 요소는 곧 사라질 메뉴 항목이다.
   */
  const openerRef = useRef<string | null>(null)

  /**
   * 행 메뉴에서 확인 창을 연다. 돌아갈 자리를 **이름으로** 기억한다.
   *
   * 요소를 들고 있지 않는 이유는, 확인이 끝나면 목록을 다시 불러와 그 버튼이 새로
   * 그려지기 때문이다 — 옛 요소는 이미 문서에서 빠져 있다. 이름으로 찾으면 재조회 뒤에도
   * 같은 행을 가리킨다.
   */
  function confirmFromMenu(user: User, next: PendingAction) {
    openerRef.current = user.name
    setConfirm(next)
  }
  const [working, setWorking] = useState(false)
  /**
   * React state가 다시 그려지기 전 같은 확인 버튼이 연속 실행되는 틈을 막는다.
   * `working`은 화면 disabled용이고, 이 ref가 이벤트 핸들러의 동기 잠금이다.
   */
  const bulkWorkingRef = useRef(false)

  function startBulk(): boolean {
    if (bulkWorkingRef.current) return false
    bulkWorkingRef.current = true
    setWorking(true)
    return true
  }

  function finishBulk() {
    bulkWorkingRef.current = false
    setWorking(false)
  }

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
      applied: applied === null ? undefined : applied === 'true',
      role: role || undefined,
      sort: sort || undefined,
    })
      .then((result) => {
        /*
         * **이 조회가 아직 유효할 때만 손댄다.** 조건이 바뀌면 정리 함수가 `alive`를
         * 내리므로 여기를 건너뛴다 — 그 가드가 아래 되돌리기의 안전장치다.
         */
        if (!alive) return
        if (clampedOutOfRange(result, page, searchParams, setSearchParams))
          return
        setData(result)
        const visibleIds = new Set(result.content.map((user) => user.id))
        const visibleSelection = selectedRef.current.filter((id) =>
          visibleIds.has(id),
        )
        if (visibleSelection.length !== selectedRef.current.length) {
          setSelection(visibleSelection)
        }
      })
      .catch((error: unknown) => {
        if (!alive) return
        reportApiError(error)
        setFailed(true)
      })
    return () => {
      alive = false
    }
  }, [
    page,
    keyword,
    status,
    applied,
    role,
    sort,
    reloadKey,
    reportApiError,
    searchParams,
    setSearchParams,
  ])

  /**
   * <b>옛 주소를 새 조합으로 맞춘다.</b>
   *
   * 상태 필터를 쪼개기 전에는 `?status=PENDING` 하나가 `PENDING` 전부를 뜻했다. 그 주소가
   * 기록·북마크·붙여넣은 링크로 다시 열리면 목록은 여전히 `PENDING`만 가져오는데 필터는
   * 짝이 없어 <b>"전체"로 보인다</b> — 관리자는 전원을 보고 있다고 믿지만 실제로는 일부만 본다.
   * 이 화면이 없애려던 바로 그 거짓말이다.
   *
   * 가장 가까운 새 조합인 <b>"승인 대기"</b>로 맞추고 주소도 함께 고친다. <b>말없이 바꾸지
   * 않는다</b> — 아래 선택 해제와 같은 규칙이다 (T-83). 범위가 좁아지는 쪽이라 관리자가
   * 알아야 한다.
   */
  useEffect(() => {
    if (status !== 'PENDING' || applied !== null) return

    const next = new URLSearchParams(searchParams)
    next.set('applied', 'true')
    setSearchParams(next, { replace: true })
    alert.info(
      '이전 주소의 조건을 "승인 대기"로 맞췄습니다. 신청하지 않은 계정은 "미승인"에서 볼 수 있습니다.',
      { persistOnNavigation: true },
    )
  }, [status, applied, searchParams, setSearchParams, alert])

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
    alert.info(
      hadConfirm
        ? dropped > 0
          ? `조회 조건이 바뀌어 진행 중이던 확인을 닫고 선택한 ${dropped}명이 해제되었습니다.`
          : '조회 조건이 바뀌어 진행 중이던 확인을 닫았습니다.'
        : `조회 조건이 바뀌어 선택한 ${dropped}명이 해제되었습니다.`,
    )
  }, [page, keyword, status, applied, role, sort])

  /**
   * URL의 검색어가 바뀌면 입력창도 따라간다.
   *
   * 뒤로가기로 돌아왔을 때 **목록은 A인데 입력창은 B**로 남으면, 관리자가 화면에 적힌
   * 조건과 다른 명단을 보고 승인한다. 승인은 되돌릴 수 없다.
   */
  useEffect(() => {
    setDraft(keyword)
  }, [keyword])

  /** 파라미터를 바꾸면 언제나 첫 페이지로 돌아간다. 3페이지에서 조건을 바꾸면 빈 화면이 뜬다. */
  function setParam(key: string, value: string) {
    const next = new URLSearchParams(searchParams)
    if (value === '') next.delete(key)
    else next.set(key, value)
    writePage(next, 0)
    setSearchParams(next)
  }

  /**
   * 상태 필터는 URL 파라미터 <b>두 개</b>를 함께 움직인다.
   *
   * 하나씩 세우면 그 사이에 조회가 한 번 더 나가고, `status`만 바뀐 중간 상태로 목록이
   * 잠깐 그려진다 — 관리자가 보는 것과 고른 것이 어긋난다.
   */
  function setStatusFilter(value: string) {
    const filter = STATUS_FILTERS.find((candidate) => candidate.value === value)
    const next = new URLSearchParams(searchParams)
    if (filter?.status === undefined) next.delete('status')
    else next.set('status', filter.status)
    if (filter?.applied === undefined) next.delete('applied')
    else next.set('applied', String(filter.applied))
    writePage(next, 0)
    setSearchParams(next)
  }

  function submitSearch(event: FormEvent) {
    event.preventDefault()
    setParam('q', draft.trim())
  }

  const rows = data?.content ?? []
  /** 전체 선택은 status·role과 무관하게 현재 페이지의 모든 행이다 (#297). */
  const selectableHere = rows
  const allSelected =
    selectableHere.length > 0 &&
    selectableHere.every((user) => selected.includes(user.id))

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
    if (action.kind === 'bulk-status') {
      const names = action.ids
        .map((id) => rows.find((user) => user.id === id)?.name)
        .filter(Boolean)
        .join(', ')
      const activating = action.target === 'ACTIVE'
      return {
        title: activating
          ? '선택한 회원을 활성화할까요?'
          : '선택한 회원을 정지할까요?',
        body: activating
          ? `${action.ids.length}명을 활성화합니다: ${names}. 신청서를 내지 않은 승인 대기 계정은 실패할 수 있고, 이미 활동 중인 계정은 멱등 처리됩니다.`
          : `${action.ids.length}명을 정지합니다: ${names}. 승인 대기 계정과 관리자 계정은 실패할 수 있고, 비활동 회원은 정지 뒤 해제해도 활동 상태로 돌아옵니다.`,
      }
    }
    if (action.kind === 'bulk-deactivate') {
      const names = action.ids
        .map((id) => rows.find((user) => user.id === id)?.name)
        .filter(Boolean)
        .join(', ')
      return {
        title: '선택한 회원을 비활성화할까요?',
        body: `${action.ids.length}명을 비활성화합니다: ${names}. 활동 중인 일반 부원만 바뀌며, 관리자·승인 대기·정지·이미 비활동인 계정은 실패할 수 있습니다.`,
      }
    }
    if (action.kind === 'reject') {
      const names = action.ids
        .map((id) => rows.find((user) => user.id === id)?.name)
        .filter(Boolean)
        .join(', ')
      return {
        title: '선택한 신청을 거부할까요?',
        body: `${action.ids.length}명의 신청을 거부하고 계정을 지웁니다: ${names}`,
      }
    }
    if (action.kind === 'remove') {
      const { user, summary } = action
      /*
       * 건수를 아직 모르면 그렇게 말한다. "0건"으로 보이면 남는 것이 없다고 읽혀,
       * 관리자가 그 전제로 되돌릴 수 없는 조작을 한다.
       */
      const leaves =
        summary === null
          ? '남을 콘텐츠를 확인하는 중입니다.'
          : summary === 'failed'
            ? '남을 콘텐츠 건수를 불러오지 못했습니다. 다시 시도해 주세요.'
            : `자료 ${summary.notes}건, 공지 ${summary.notices}건, 활동사진 ${summary.photos}건, 게시글 ${summary.posts}건이 "탈퇴한 회원"으로 남습니다.`
      return {
        title: '이 회원을 제거할까요?',
        body: `${user.name} 회원의 계정을 지웁니다. 되돌릴 수 없습니다. ${leaves}`,
      }
    }
    if (action.kind === 'role') {
      const granting = action.next === 'ADMIN'
      return {
        title: granting ? '관리자 권한을 줄까요?' : '관리자 권한을 회수할까요?',
        body: granting
          ? `${action.user.name} 회원이 회원 승인과 공지 작성을 할 수 있게 됩니다.`
          : `${action.user.name} 회원의 관리자 권한을 회수합니다.`,
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
    setSelection(next ? selectableHere.map((user) => user.id) : [])
  }

  function toggleOne(id: number, next: boolean) {
    setSelection(
      next
        ? [...selectedRef.current, id]
        : selectedRef.current.filter((value) => value !== id),
    )
  }

  function run(action: PendingAction) {
    if (action.kind === 'approve') return runApprove(action.ids)
    if (action.kind === 'bulk-status')
      return runBulkStatus(action.ids, action.target)
    if (action.kind === 'bulk-deactivate') return runBulkDeactivate(action.ids)
    if (action.kind === 'reject') return runReject(action.ids)
    if (action.kind === 'remove') return runRemove(action.user)
    if (action.kind === 'role') return runRole(action.user, action.next)
    return runStatus(action.user, action.next)
  }

  async function runApprove(ids: number[]) {
    setWorking(true)
    try {
      const result = await approve(ids)
      /*
       * **성공·실패 건수를 안내한다** (2-2 §2-2-2 MUST). 실패가 있으면 누구인지도 말한다 —
       * 건수만으로는 무엇을 조치해야 할지 알 수 없다.
       */
      const message =
        result.failed.length === 0
          ? `${result.approved.length}명을 승인했습니다.`
          : `${result.approved.length}명을 승인하고 ${result.failed.length}명은 실패했습니다.` +
            ` ${describeFailures(result.failed, rows)}`
      if (result.failed.length === 0) alert.success(message)
      else alert.error(message)
      // 행별 승인은 그 한 명의 선택만 정리하고, 무관한 선택은 그대로 둔다.
      const settled = [
        ...result.approved,
        ...result.failed.map(({ userId }) => userId),
      ]
      setSelection(selectedRef.current.filter((id) => !settled.includes(id)))
      setReloadKey((key) => key + 1)
    } catch (error: unknown) {
      if (reportApiError(error)) return
      // 실패했는데 성공한 것처럼 보이면 안 된다. 선택도 그대로 두어 다시 시도할 수 있게 한다.
      alert.error(
        error instanceof ApiError
          ? `승인하지 못했습니다. ${error.message}`
          : '승인하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      )
    } finally {
      setWorking(false)
      setConfirm(null)
    }
  }

  /*
   * 확인 창을 **먼저 열고** 건수를 뒤이어 채운다. 다 받고 나서 열면 누른 뒤 아무 반응이
   * 없는 구간이 생겨 두 번 누르게 된다. 그동안 창은 "확인하는 중"이라고 말한다.
   *
   * 건수를 못 받아도 창을 닫지 않는다 — 제거 자체는 건수와 무관하게 진행할 수 있고
   * (§2-2-4 — 참고치이지 조건이 아니다), 닫아 버리면 왜 닫혔는지 알 수 없다.
   */
  async function openRemove(user: User) {
    const token = ++removeToken.current
    setConfirm({ kind: 'remove', user, summary: null, token })

    /** 이 응답이 아직 화면에 붙어 있는 창의 것인가. */
    const current = () => {
      const pending = pendingRef.current
      return pending?.kind === 'remove' && pending.token === token
        ? pending
        : null
    }

    try {
      const summary = await contentSummary(user.id)
      const open = current()
      if (open) setConfirm({ ...open, summary })
    } catch (error: unknown) {
      const handled = reportApiError(error)
      const open = current()
      /*
       * 취소된 요청의 실패는 삼킨다. 창을 닫은 뒤 뜨는 오류나, 제거가 먼저 성공한 뒤
       * 늦게 도착한 404가 성공 안내를 덮는 것을 막는다.
       */
      if (open) {
        setConfirm({ ...open, summary: 'failed' })
        if (!handled) {
          alert.error('남을 콘텐츠 건수를 불러오지 못했습니다.')
        }
      }
    }
  }

  function memberName(id: number): string {
    return rows.find((user) => user.id === id)?.name ?? `#${id}`
  }

  function uncertainFailure(action: string, error: unknown): string {
    const detail = error instanceof ApiError ? ` ${error.message}` : ''
    return `${action} 요청 결과를 확정할 수 없습니다.${detail} 일부 상태 변경이 반영되었을 수 있습니다. 다시 불러온 목록에서 상태를 확인한 뒤 대상을 다시 선택해 재시도해 주세요.`
  }

  async function runBulkStatus(ids: number[], target: BulkStatusTarget) {
    if (!startBulk()) return
    const action = target === 'ACTIVE' ? '활성화' : '정지'
    try {
      const result = await bulkUpdateStatus(ids, target)
      const failures = result.failed
        .map(
          ({ userId, reason }) =>
            `${memberName(userId)}: ${BULK_STATUS_FAILURE_TEXT[reason]}`,
        )
        .join(' / ')
      const processed = result.processed.map(memberName).join(', ')
      const message =
        `${result.processed.length}명을 ${action} 처리했습니다(이미 목표 상태인 멱등 결과 포함)` +
        (processed ? `: ${processed}.` : '.') +
        (failures
          ? ` ${result.failed.length}명은 처리하지 못했습니다 — ${failures}`
          : '')
      if (failures) alert.error(message)
      else alert.success(message)
      setSelection([])
    } catch (error: unknown) {
      if (reportApiError(error)) return
      alert.error(
        error instanceof ApiError && error.status < 500
          ? `${action}하지 못했습니다. ${error.message}`
          : uncertainFailure(action, error),
      )
    } finally {
      setReloadKey((key) => key + 1)
      finishBulk()
      setConfirm(null)
    }
  }

  async function runBulkDeactivate(ids: number[]) {
    if (!startBulk()) return
    try {
      const result = await deactivate(ids)
      const failures = result.failed
        .map(
          ({ userId, reason }) =>
            `${memberName(userId)}: ${DEACTIVATE_FAILURE_TEXT[reason]}`,
        )
        .join(' / ')
      const deactivated = result.deactivated.map(memberName).join(', ')
      const message =
        `${result.deactivated.length}명을 비활성화했습니다` +
        (deactivated ? `: ${deactivated}.` : '.') +
        (failures
          ? ` ${result.failed.length}명은 변경하지 못했습니다 — ${failures}`
          : '')
      if (failures) alert.error(message)
      else alert.success(message)
      setSelection([])
    } catch (error: unknown) {
      if (reportApiError(error)) return
      alert.error(
        error instanceof ApiError && error.status < 500
          ? `비활성화하지 못했습니다. ${error.message}`
          : uncertainFailure('비활성화', error),
      )
    } finally {
      setReloadKey((key) => key + 1)
      finishBulk()
      setConfirm(null)
    }
  }

  async function runReject(ids: number[]) {
    setWorking(true)
    try {
      const result = await reject(ids)
      const failures = result.failed
        .map(({ userId, reason }) => {
          const who =
            rows.find((user) => user.id === userId)?.name ?? `#${userId}`
          return `${REJECT_FAILURE_TEXT[reason]}: ${who}`
        })
        .join(' / ')
      const message = failures
        ? `${result.rejected.length}명을 거부했습니다. ${result.failed.length}명은 거부하지 못했습니다 — ${failures}`
        : `${result.rejected.length}명의 신청을 거부했습니다.`
      if (failures) alert.error(message)
      else alert.success(message)
      setReloadKey((key) => key + 1)
    } catch (error: unknown) {
      if (reportApiError(error)) return
      alert.error(
        error instanceof ApiError
          ? `거부하지 못했습니다. ${error.message}`
          : '거부하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      )
    } finally {
      /*
       * **보낸 사람만 선택에서 뺀다** (T-161) — 성공·실패·요청 실패 모두 같다. 통째로
       * 비우면 응답을 기다리는 사이 새로 고른 사람까지 풀리고, 실패 때 그냥 두면 해제할
       * 수 없는 선택이 남는다. 승인과 같은 규칙이다.
       */
      setSelection(selectedRef.current.filter((id) => !ids.includes(id)))
      setWorking(false)
      setConfirm(null)
    }
  }

  async function runRemove(user: User) {
    setWorking(true)
    try {
      await remove(user.id)
      alert.success(`${user.name} 회원을 제거했습니다.`)
      // 목록 재조회가 제거된 id만 선택에서 걷어 내고, 무관한 선택은 그대로 둔다.
      setReloadKey((key) => key + 1)
    } catch (error: unknown) {
      if (reportApiError(error)) return
      alert.error(
        error instanceof ApiError
          ? `제거하지 못했습니다. ${error.message}`
          : '제거하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      )
    } finally {
      setWorking(false)
      setConfirm(null)
    }
  }

  /*
   * **화면이 마지막 활성 관리자를 판단하지 않는다** (2-2 §2-2-7 MUST — 검사는 서버에서).
   * 활성 관리자가 몇 명인지 이 화면은 모른다. 서버가 막으면 그 사유를 그대로 보여준다.
   */
  async function runRole(user: User, next: Role) {
    setWorking(true)
    try {
      await updateRole(user.id, next)
      alert.success(
        `${user.name} 회원에게 관리자 권한을 ${next === 'ADMIN' ? '부여' : '회수'}했습니다.`,
      )
      setReloadKey((key) => key + 1)
    } catch (error: unknown) {
      if (reportApiError(error)) return
      alert.error(
        error instanceof ApiError
          ? `권한을 바꾸지 못했습니다. ${error.message}`
          : '권한을 바꾸지 못했습니다. 잠시 후 다시 시도해 주세요.',
      )
    } finally {
      setWorking(false)
      setConfirm(null)
    }
  }

  async function runStatus(user: User, next: 'ACTIVE' | 'SUSPENDED') {
    setWorking(true)
    try {
      await updateStatus(user.id, next)
      alert.success(
        `${user.name} 회원을 ${next === 'SUSPENDED' ? '정지' : '정지 해제'}했습니다.`,
      )
      setReloadKey((key) => key + 1)
    } catch (error: unknown) {
      if (reportApiError(error)) return
      alert.error(
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
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-semibold tracking-tight">회원 관리</h1>
      </div>

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
            value={toFilterValue(status, applied)}
            onChange={(event) => setStatusFilter(event.target.value)}
            className="h-9 rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-xs outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50"
          >
            {STATUS_FILTERS.map((filter) => (
              <option key={filter.value} value={filter.value}>
                {filter.label}
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

      <div className="mt-6 min-h-[40rem]" data-list-surface="members">
        {failed && (
          <div className="space-y-4">
            <p role="alert" className="text-sm text-muted-foreground">
              회원 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
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

        {data === null && !failed && (
          <p className="text-sm text-muted-foreground">불러오는 중</p>
        )}

        {data !== null && (
          <>
            {/* 세 버튼은 항상 보이고, 선택이 없거나 요청 중일 때 함께 잠긴다 (#297). */}
            <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
              <p className="text-sm text-muted-foreground">
                이 페이지에서 {selected.length}명 선택됨 · 검색 결과 전체{' '}
                {data.page.totalElements}명
              </p>
              <div className="flex w-full flex-col gap-2 sm:w-auto sm:flex-row sm:items-center">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="w-full sm:w-auto"
                  disabled={selected.length === 0 || working}
                  onClick={() =>
                    setConfirm({
                      kind: 'bulk-status',
                      ids: [...selected],
                      target: 'ACTIVE',
                    })
                  }
                >
                  선택 항목 활성화
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="w-full sm:w-auto"
                  disabled={selected.length === 0 || working}
                  onClick={() =>
                    setConfirm({
                      kind: 'bulk-deactivate',
                      ids: [...selected],
                    })
                  }
                >
                  선택 항목 비활성화
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="w-full sm:w-auto"
                  disabled={selected.length === 0 || working}
                  onClick={() =>
                    setConfirm({
                      kind: 'bulk-status',
                      ids: [...selected],
                      target: 'SUSPENDED',
                    })
                  }
                >
                  선택 항목 정지
                </Button>
              </div>
            </div>

            <ListSurface className="mt-4 overflow-x-auto">
              <Table className="min-w-[64rem]">
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-10">
                      <Checkbox
                        checked={
                          allSelected
                            ? true
                            : selected.length > 0
                              ? 'indeterminate'
                              : false
                        }
                        disabled={selectableHere.length === 0}
                        onCheckedChange={(next) => toggleAll(next === true)}
                        aria-label="이 페이지의 모든 회원 선택"
                      />
                    </TableHead>
                    <TableHead>이름</TableHead>
                    <TableHead>학번</TableHead>
                    <TableHead>학과</TableHead>
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
                            onCheckedChange={(next) =>
                              toggleOne(user.id, next === true)
                            }
                            aria-label={`${user.name} 선택`}
                          />
                        </TableCell>
                        <TableCell className="font-medium">
                          {user.name}
                        </TableCell>
                        <TableCell>{user.studentNo ?? '—'}</TableCell>
                        {/*
                         * 학과 필드가 생기기 전에 승인된 회원과 신청 전 계정은 값이 없다
                         * (§3-2-2 — 일괄로 채우지 않는다). 학번과 같은 방식으로 —를 그린다.
                         */}
                        <TableCell>{user.department ?? '—'}</TableCell>
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
                        <TableCell>
                          <Badge variant={statusVariant(user)}>
                            {statusLabel(user)}
                          </Badge>
                        </TableCell>
                        <TableCell>{formatDate(user.appliedAt)}</TableCell>
                        <TableCell>{formatDate(user.approvedAt)}</TableCell>
                        {/*
                         * **액션은 상태마다 버튼 하나다.** 한 명만 승인하려고 체크박스를
                         * 거치게 하지 않는다 — 여럿은 체크박스, 한 명은 이 자리다.
                         * "미승인"(신청서를 내지 않은 계정)은 할 수 있는 것이 없어 비어 있다.
                         */}
                        {/*
                         * **행 액션은 버튼이 아니라 메뉴다** (#99).
                         *
                         * 버튼으로 늘어놓으면 한 행에 셋, 25행이면 75개가 화면을 채운다. 더
                         * 나쁜 것은 **위계가 사라지는 것**이다 — 되돌릴 수 없는 `제거`가
                         * `정지`와 똑같이 생긴다. 메뉴로 접으면 표는 데이터만 보여주고,
                         * 위험한 조작은 한 단계 안쪽에서 `destructive`로 구분된다.
                         *
                         * 승인만 예외로 밖에 남긴다 — 이 화면에서 가장 자주 하는 일이라
                         * 메뉴 뒤로 숨기면 한 번 더 누르게 된다.
                         */}
                        <TableCell className="text-right">
                          <div className="flex items-center justify-end gap-2">
                            {approvable && (
                              <>
                                <Button
                                  type="button"
                                  variant="outline"
                                  size="sm"
                                  disabled={working}
                                  onClick={() =>
                                    setConfirm({
                                      kind: 'approve',
                                      ids: [user.id],
                                    })
                                  }
                                >
                                  승인
                                </Button>
                                <Button
                                  type="button"
                                  variant="outline"
                                  size="sm"
                                  disabled={working}
                                  onClick={() =>
                                    setConfirm({
                                      kind: 'reject',
                                      ids: [user.id],
                                    })
                                  }
                                >
                                  거부
                                </Button>
                              </>
                            )}
                            {user.status !== 'PENDING' && (
                              <DropdownMenu>
                                <DropdownMenuTrigger asChild>
                                  <Button
                                    type="button"
                                    variant="ghost"
                                    size="sm"
                                    disabled={working}
                                    aria-label={`${user.name} 관리 메뉴`}
                                  >
                                    <MoreHorizontalIcon aria-hidden="true" />
                                  </Button>
                                </DropdownMenuTrigger>
                                <DropdownMenuContent align="end">
                                  <DropdownMenuItem
                                    onSelect={() =>
                                      confirmFromMenu(user, {
                                        kind: 'status',
                                        user,
                                        next:
                                          user.status === 'SUSPENDED'
                                            ? 'ACTIVE'
                                            : 'SUSPENDED',
                                      })
                                    }
                                  >
                                    {user.status === 'SUSPENDED'
                                      ? '정지 해제'
                                      : '정지'}
                                  </DropdownMenuItem>
                                  <DropdownMenuItem
                                    onSelect={() =>
                                      confirmFromMenu(user, {
                                        kind: 'role',
                                        user,
                                        next:
                                          user.role === 'ADMIN'
                                            ? 'USER'
                                            : 'ADMIN',
                                      })
                                    }
                                  >
                                    {user.role === 'ADMIN'
                                      ? '권한 회수'
                                      : '관리자 지정'}
                                  </DropdownMenuItem>
                                  <DropdownMenuSeparator />
                                  {/*
                                되돌릴 수 없는 조작만 여기 아래다. 구분선과 `destructive`가
                                "다른 종류"임을 말한다 — 확인 창이 마지막 방어선이고, 그
                                앞에서도 손이 미끄러지지 않게 한다.
                              */}
                                  <DropdownMenuItem
                                    variant="destructive"
                                    onSelect={() => {
                                      openerRef.current = user.name
                                      openRemove(user)
                                    }}
                                  >
                                    제거
                                  </DropdownMenuItem>
                                </DropdownMenuContent>
                              </DropdownMenu>
                            )}
                          </div>
                        </TableCell>
                      </TableRow>
                    )
                  })}
                </TableBody>
              </Table>

              {/* 빈 상태도 카드 안이다 — 밖에 두면 표만 사라지고 테두리가 남는다. */}
              {rows.length === 0 && (
                <p className="px-6 py-10 text-center text-sm text-muted-foreground">
                  조건에 맞는 회원이 없습니다.
                </p>
              )}
            </ListSurface>

            <div
              className="mt-8 flex min-h-9 items-start justify-center gap-4"
              data-pager-slot="true"
            >
              {data.page.totalPages > 1 && (
                <>
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
                </>
              )}
            </div>
          </>
        )}
        {data === null && <div className="min-h-9" data-pager-slot="true" />}
      </div>

      <AlertDialog
        open={pending !== null}
        onOpenChange={(open) => {
          if (!open) setConfirm(null)
        }}
      >
        <AlertDialogContent
          onCloseAutoFocus={(event) => {
            const name = openerRef.current
            openerRef.current = null
            if (!name) return
            /*
             * 그 행이 아직 있으면 트리거로 돌아간다. 제거·거부처럼 행이 사라졌으면
             * 아무것도 하지 않는다 — Radix 기본 동작에 맡긴다.
             */
            const trigger = document.querySelector<HTMLElement>(
              `[aria-label="${CSS.escape(name)} 관리 메뉴"]`,
            )
            if (!trigger) return
            event.preventDefault()
            trigger.focus()
          }}
        >
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
            {/*
              **건수를 보여주기 전에는 제거를 누를 수 없다** (2-2 §2-2-4 MUST). 느린 조회나
              실패 때 그냥 열어 두면 무엇이 남는지 한 번도 못 본 채로 되돌릴 수 없는 조작을
              하게 된다 — 그것이 이 확인 창의 존재 이유다.
            */}
            <AlertDialogAction
              disabled={
                working ||
                (pending?.kind === 'remove' &&
                  (pending.summary === null || pending.summary === 'failed'))
              }
              onClick={() => {
                if (pending) run(pending)
              }}
            >
              {pending?.kind === 'approve'
                ? '승인'
                : pending?.kind === 'bulk-status'
                  ? pending.target === 'ACTIVE'
                    ? '활성화'
                    : '정지'
                  : pending?.kind === 'bulk-deactivate'
                    ? '비활성화'
                    : pending?.kind === 'reject'
                      ? '거부'
                      : pending?.kind === 'remove'
                        ? '제거'
                        : pending?.kind === 'role'
                          ? pending.next === 'ADMIN'
                            ? '관리자 지정'
                            : '권한 회수'
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
  writePage(params, next)
  return params
}
