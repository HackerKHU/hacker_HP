import { MoreHorizontalIcon } from 'lucide-react'
import { type FormEvent, useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  type ApproveFailureReason,
  approve,
  type ContentSummary,
  contentSummary,
  deactivateAll,
  list,
  type ReactivateFailureReason,
  type RejectFailureReason,
  reactivate,
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
const REACTIVATE_FAILURE_TEXT: Record<ReactivateFailureReason, string> = {
  NOT_INACTIVE: '비활동이 아닌 계정 (이미 활동 중이거나 정지됨)',
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
  /** 학기 전환 — 일괄 복구. 승인과 같은 모양이다 (2-2 §2-2-3). */
  | { kind: 'reactivate'; ids: number[] }
  /*
   * 학기 전환 — 일괄 비활성화. **대상을 고르지 않으므로 ids가 없다** (계약 §3-2-6 MUST).
   * 대신 몇 명이 바뀌는지를 들고 있는다 — 관리자는 목록에서 누가 바뀌는지 볼 수 없으므로
   * **최소한 몇 명인지는 보고 눌러야 한다** (2-2 §2-2-3 MUST).
   *
   * `null`이면 아직 세는 중, `'failed'`면 못 셌다. **세는 중에는 누를 수 없고**(위 MUST),
   * **못 셌으면 누를 수 있다** — 그때는 셀 수 없다는 것까지 보여준 상태이고, 건수는 확인
   * 창을 여는 시점의 참고치이지 실행의 조건이 아니다 (§2-2-3). 셋을 가르지 않으면 둘 중
   * 하나가 반드시 어긋난다.
   */
  | { kind: 'deactivate'; count: number | 'failed' | null; token: number }
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

/** **복구 대상인가.** `INACTIVE`만이다 (계약 §3-2-6 — 정지된 계정을 이 경로로 풀 수 없다). */
function isReactivatable(user: User): boolean {
  return user.status === 'INACTIVE'
}

/**
 * 체크박스로 고를 수 있는가.
 *
 * **두 일괄 조작이 체크박스 한 벌을 나눠 쓴다.** "전체" 필터에서는 한 페이지에 승인 대상과
 * 복구 대상이 섞여 있는데, 한쪽만 고를 수 있게 하면 나머지 한쪽은 **필터를 먼저 걸 줄
 * 아는 사람에게만** 보인다. 골라 놓고 나서 무엇을 할지는 아래 버튼이 각자의 몫만
 * 가져가 정한다.
 */
function isSelectable(user: User): boolean {
  return isApprovable(user) || isReactivatable(user)
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
  /**
   * 확인을 기다리는 조작. **되돌릴 수 없는 것은 전부 여기를 지난다** — 일괄 승인, 행 단위
   * 승인, 정지·해제. 정지는 즉시 로그인을 막으므로(2-2 §2-2-3 MUST) 승인만 확인받고
   * 정지는 그냥 나가면 앞뒤가 안 맞는다.
   */
  const [pending, setPending] = useState<PendingAction | null>(null)
  /** 조회 조건이 바뀔 때 "확인창이 열려 있었는지"를 읽으려고 둔다. `selectedRef`와 같은 이유다. */
  const pendingRef = useRef<PendingAction | null>(null)
  /** 제거 확인 창의 건수 요청 세대. 늦게 도착한 응답을 가려낸다. */
  const removeToken = useRef(0)
  /** 학기 전환 확인 창의 대상 건수 요청 세대. 같은 이유다. */
  const deactivateToken = useRef(0)

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
  /** **이 페이지에서** 승인할 수 있는 사람. */
  const approvableHere = rows.filter(isApprovable)
  /** **이 페이지에서** 복구할 수 있는 사람. */
  const reactivatableHere = rows.filter(isReactivatable)
  /** 전체 선택의 범위. 승인 대상과 복구 대상을 합친 것이다. */
  const selectableHere = rows.filter(isSelectable)
  const allSelected =
    selectableHere.length > 0 &&
    selectableHere.every((user) => selected.includes(user.id))

  /*
   * **고른 사람을 조작별로 가른다.** 버튼은 각자 자기 몫만 보내고 자기 몫의 수만 말한다 —
   * 합계를 적으면 "선택한 5명 승인"을 눌렀는데 3명만 승인되고, 관리자는 나머지 둘이
   * 어디로 갔는지 알 수 없다.
   */
  const selectedApprovable = selected.filter((id) =>
    approvableHere.some((user) => user.id === id),
  )
  const selectedReactivatable = selected.filter((id) =>
    reactivatableHere.some((user) => user.id === id),
  )

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
    if (action.kind === 'reactivate') {
      const names = action.ids
        .map((id) => rows.find((user) => user.id === id)?.name)
        .filter(Boolean)
        .join(', ')
      return {
        title: '선택한 회원을 복구할까요?',
        body: `${action.ids.length}명을 이번 학기 활동 부원으로 되돌립니다: ${names}`,
      }
    }
    if (action.kind === 'deactivate') {
      /*
       * **대상 건수를 보여준다** (2-2 §2-2-3 MUST). 조건으로 고르므로 관리자는 목록에서
       * 누가 바뀌는지 볼 수 없다 — 최소한 몇 명인지는 보고 눌러야 한다.
       *
       * 못 세도 진행할 수 있다는 것을 문구가 말한다. 건수는 **참고치이지 실행의 조건이
       * 아니다** — 확인 창을 여는 사이 누가 승인을 받아 늘어나도 전환은 그대로 진행한다.
       */
      const target =
        action.count === null
          ? '대상 인원수를 확인하는 중입니다.'
          : action.count === 'failed'
            ? '대상 인원수를 불러오지 못했습니다. 그래도 전환은 진행할 수 있습니다.'
            : `지금 활동 중인 일반 부원 ${action.count}명이 비활동이 됩니다.`
      return {
        title: '활동 부원 전원을 비활동으로 바꿀까요?',
        /*
         * **무엇이 대상이 아닌지도 말한다** (#228 D4). 셋을 적지 않으면 관리자는 "전원"을
         * 글자 그대로 믿고, 관리자인 자기 계정까지 내려가는 줄 안다.
         */
        body:
          `${target} 관리자·정지된 회원·승인 대기 계정은 바뀌지 않습니다.` +
          ' 되돌리려면 상태 필터에서 "비활동"을 골라 복구합니다.',
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
    if (action.kind === 'reactivate') return runReactivate(action.ids)
    if (action.kind === 'deactivate') return runDeactivate()
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

  /**
   * 정지·해제.
   *
   * **마지막 활성 관리자인지 화면은 판단하지 않는다** (2-2 §2-2-7 — 검사는 서버에서 한다).
   * 화면은 활성 관리자가 몇 명인지 모른다. 서버가 403으로 거부하면 그 메시지를 그대로
   * 보여준다 — 막힌 것을 성공처럼 보이게 하면 관리자가 정지된 줄 알고 자리를 뜬다.
   */
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

  /**
   * 일괄 복구 (2-2 §2-2-3). **승인과 같은 규칙으로 움직인다** — 부분 실패도 `200`이고,
   * 성공·실패 건수를 안내하며, 보낸 사람만 선택에서 뺀다.
   */
  async function runReactivate(ids: number[]) {
    setWorking(true)
    try {
      const result = await reactivate(ids)
      /*
       * **누가 왜 실패했는지 말한다.** 건수만으로는 조치할 수 없고, 특히 `NOT_INACTIVE`에는
       * 정지된 계정이 섞여 있다 — 그 사람은 복구되지도 정지가 풀리지도 않았다.
       */
      const failures = result.failed
        .map(({ userId, reason }) => {
          const who =
            rows.find((user) => user.id === userId)?.name ?? `#${userId}`
          return `${REACTIVATE_FAILURE_TEXT[reason]}: ${who}`
        })
        .join(' / ')
      const message = failures
        ? `${result.reactivated.length}명을 복구했습니다. ${result.failed.length}명은 복구하지 못했습니다 — ${failures}`
        : `${result.reactivated.length}명을 복구했습니다.`
      if (failures) alert.error(message)
      else alert.success(message)
      /*
       * **보낸 사람만 뺀다** (T-161, 승인과 같은 규칙) — 그리고 **응답을 받았을 때만** 뺀다.
       *
       * 요청 자체가 실패하면(네트워크·5xx) 그 사람들은 여전히 `INACTIVE`이고 여전히 복구
       * 대상이다. 거기서 선택까지 풀어 버리면 관리자는 **방금 고른 명단을 처음부터 다시
       * 골라야 한다** — 실패했으니 다시 시도하라고 해 놓고 다시 시도할 수단을 치우는 셈이다.
       * 승인이 성공 경로에서만 선택을 정리하는 것과 같은 이유다.
       */
      setSelection(selectedRef.current.filter((id) => !ids.includes(id)))
      setReloadKey((key) => key + 1)
    } catch (error: unknown) {
      if (reportApiError(error)) return
      // 선택은 그대로 둔다 — 위 주석 참고. 다시 누르면 같은 명단이 그대로 나간다.
      alert.error(
        error instanceof ApiError
          ? `복구하지 못했습니다. ${error.message}`
          : '복구하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      )
    } finally {
      setWorking(false)
      setConfirm(null)
    }
  }

  /**
   * 학기 전환 확인 창을 연다. **대상 건수는 목록을 같은 조건으로 조회해 얻는다**
   * (2-2 §2-2-3 — 미리보기 전용 API를 두지 않는다).
   *
   * **`size=1`로 부른다.** 필요한 것은 `page.totalElements` 하나이고, 20명을 받아 와도
   * 화면에 쓰지 않는다. **현재 페이지의 행 수를 세지 않는다** (T-356 MUST) — 그러면
   * 관리자가 100명을 내리면서 20명으로 안다.
   *
   * 제거 확인 창과 같이 **먼저 열고 뒤이어 채운다.** 다 받고 나서 열면 누른 뒤 아무 반응이
   * 없는 구간이 생겨 두 번 누르게 된다. 그동안 창은 "확인하는 중"이라고 말하고 **실행
   * 버튼은 잠겨 있다** — 건수를 보여주기 전에 눌리면 이 창이 있는 이유가 사라진다.
   */
  async function openDeactivate() {
    const token = ++deactivateToken.current
    setConfirm({ kind: 'deactivate', count: null, token })

    /** 이 응답이 아직 화면에 붙어 있는 창의 것인가. */
    const current = () => {
      const open = pendingRef.current
      return open?.kind === 'deactivate' && open.token === token ? open : null
    }

    try {
      const result = await list({ status: 'ACTIVE', role: 'USER', size: 1 })
      const open = current()
      if (open) setConfirm({ ...open, count: result.page.totalElements })
    } catch (error: unknown) {
      const handled = reportApiError(error)
      const open = current()
      // 취소된 요청의 실패는 삼킨다 — 창을 닫은 뒤 뜨는 오류가 다른 안내를 덮지 않게.
      if (open) {
        setConfirm({ ...open, count: 'failed' })
        if (!handled) alert.error('전환 대상 인원수를 불러오지 못했습니다.')
      }
    }
  }

  /**
   * 일괄 비활성화 (2-2 §2-2-3).
   *
   * **바뀐 id를 응답에서 받아 건수로 안내한다** (계약 §3-2-6 MUST). 조건으로 실행했으므로
   * 응답이 아니면 방금 무엇이 바뀌었는지 알 방법이 없다. **되돌리는 길은 복구 하나뿐이라**
   * (§2-2-3 MUST) 여기에 취소를 따로 두지 않고 어디서 되돌리는지만 알려준다.
   */
  async function runDeactivate() {
    setWorking(true)
    try {
      const result = await deactivateAll()
      alert.success(
        result.deactivated.length === 0
          ? '비활동으로 바뀐 회원이 없습니다. 이미 전원이 비활동이거나 활동 중인 일반 부원이 없습니다.'
          : `${result.deactivated.length}명을 비활동으로 바꿨습니다. 되돌리려면 상태 필터에서 "비활동"을 골라 복구하세요.`,
      )
      setReloadKey((key) => key + 1)
    } catch (error: unknown) {
      if (reportApiError(error)) return
      /*
       * **세션 반영에 실패하면 `500`이지만 상태는 이미 바뀌었을 수 있다** (2-2 §2-2-5 MUST).
       * "실패했으니 아무 일도 없었다"고 말하면 관리자가 다시 누르는데, 그 재요청이 실제로는
       * 복구 수단이다 — 목록을 확인하라고 말하는 편이 정확하다.
       */
      alert.error(
        error instanceof ApiError
          ? `학기 전환에 실패했습니다. ${error.message} 목록에서 상태를 확인해 주세요.`
          : '학기 전환에 실패했습니다. 목록에서 상태를 확인해 주세요.',
      )
      setReloadKey((key) => key + 1)
    } finally {
      setWorking(false)
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
      /*
       * 선택은 건드리지 않는다. 제거 대상은 비-`PENDING`이고 선택 가능한 것은 신청서를
       * 낸 `PENDING`뿐이라, 제거 대상이 선택에 들어갈 수 없다 — 여기서 비우면 무관한
       * 신청자 선택만 조용히 풀린다.
       */
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
        {/*
         * **학기 전환은 목록의 조작이 아니라 화면의 조작이다** (2-2 §2-2-3). 대상을 서버가
         * 고르므로 선택과 무관하고, 그래서 선택했을 때만 나오는 일괄 버튼 줄이 아니라
         * 제목 옆에 늘 있다.
         *
         * **`outline`이다.** 이 화면에서 가장 자주 하는 일은 승인이라 채워진 버튼은 그쪽
         * 몫이고, 학기마다 한 번 누르는 것이 그 옆에서 더 눈에 띄면 안 된다.
         */}
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={working}
          onClick={openDeactivate}
        >
          일괄 비활동 전환
        </Button>
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
            {/*
             * **선택했을 때만 일괄 액션이 나온다** (#99). 늘 띄워 두면 "선택한 0명 승인"
             * 이라는 뜻 없는 버튼이 화면에 남고, 진짜 눌러야 할 때와 구분이 안 된다.
             *
             * 요약은 항상 보인다 — 몇 명이 있고 그중 몇이 승인 대상인지는 선택과 무관하게
             * 알아야 하는 정보다.
             */}
            <div className="flex flex-wrap items-center justify-between gap-3">
              {/*
               * **선택 범위를 화면이 분명히 말한다.** 전체 선택은 이 페이지만이다 — 검색
               * 결과 전부를 뜻하면 관리자가 3페이지째 있는 사람까지 모르고 승인한다.
               * 범위를 말하지 않고 페이지만 선택하는 것도 관리자를 속이는 것이다.
               */}
              <p className="text-sm text-muted-foreground">
                이 페이지에서 {selected.length}명 선택됨 (승인 가능{' '}
                {approvableHere.length}명 · 복구 가능 {reactivatableHere.length}
                명 · 전체 {data.page.totalElements}명)
              </p>
              {/*
               * **버튼은 자기 몫이 있을 때만 나온다.** "전체" 필터에서는 승인 대상과 복구
               * 대상이 한 선택에 섞이는데, 셋을 늘 띄워 두면 그중 둘은 늘 0명짜리다.
               */}
              <div className="flex items-center gap-2">
                {selectedApprovable.length > 0 && (
                  <>
                    <Button
                      type="button"
                      size="sm"
                      disabled={working}
                      onClick={() =>
                        setConfirm({ kind: 'approve', ids: selectedApprovable })
                      }
                    >
                      선택한 {selectedApprovable.length}명 승인
                    </Button>
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      disabled={working}
                      onClick={() =>
                        setConfirm({ kind: 'reject', ids: selectedApprovable })
                      }
                    >
                      선택한 {selectedApprovable.length}명 거부
                    </Button>
                  </>
                )}
                {selectedReactivatable.length > 0 && (
                  <Button
                    type="button"
                    size="sm"
                    disabled={working}
                    onClick={() =>
                      setConfirm({
                        kind: 'reactivate',
                        ids: selectedReactivatable,
                      })
                    }
                  >
                    선택한 {selectedReactivatable.length}명 복구
                  </Button>
                )}
              </div>
            </div>

            <ListSurface className="mt-4">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-10">
                      <Checkbox
                        checked={allSelected}
                        disabled={selectableHere.length === 0}
                        onCheckedChange={(next) => toggleAll(next === true)}
                        aria-label="이 페이지의 일괄 처리 대상 전체 선택"
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
                            disabled={!isSelectable(user)}
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
                (pending?.kind === 'remove' &&
                  (pending.summary === null || pending.summary === 'failed')) ||
                /*
                 * **세는 중에는 누를 수 없다** (2-2 §2-2-3 MUST — 누르기 전에 대상 건수를
                 * 보여준다). 열자마자 누를 수 있게 두면 건수가 도착하기 전에 수십 명이
                 * 바뀌는데, 그 창이 있는 이유가 정확히 그것을 막는 것이다.
                 *
                 * **`'failed'`는 다르다.** 그때는 셀 수 없다는 것까지 보여준 상태이고,
                 * 건수는 참고치이지 실행의 조건이 아니다 (§2-2-3) — 여기서 막으면 건수
                 * 조회가 실패할 때 학기 전환이 통째로 멈춘다.
                 */
                (pending?.kind === 'deactivate' && pending.count === null)
              }
              onClick={() => {
                if (pending) run(pending)
              }}
            >
              {pending?.kind === 'approve'
                ? '승인'
                : pending?.kind === 'reactivate'
                  ? '복구'
                  : pending?.kind === 'deactivate'
                    ? '비활동 전환'
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
