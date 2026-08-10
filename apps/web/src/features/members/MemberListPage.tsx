import { type FormEvent, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { approve, list, updateStatus } from '@/api/adminUsers'
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

const PAGE_SIZE = 20

const STATUS_LABEL: Record<UserStatus, string> = {
  PENDING: '승인 대기',
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
  const [confirming, setConfirming] = useState(false)
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
        /*
         * 목록이 바뀌면 선택을 버린다. 다른 페이지·다른 조건에서 고른 사람이 남아 있으면
         * 화면에 보이지 않는 사람을 승인하게 된다 — 되돌릴 수 없는 조작이다.
         */
        setSelected([])
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

  function toggleAll(next: boolean) {
    setSelected(next ? approvableHere.map((user) => user.id) : [])
  }

  function toggleOne(id: number, next: boolean) {
    setSelected((current) =>
      next ? [...current, id] : current.filter((value) => value !== id),
    )
  }

  async function runApprove() {
    setWorking(true)
    setNotice(null)
    try {
      const result = await approve(selected)
      /*
       * **성공·실패 건수를 안내한다** (2-2 §2-2-2 MUST). 실패가 있으면 누구인지도 말한다 —
       * 건수만으로는 무엇을 조치해야 할지 알 수 없다.
       */
      const failedNames = result.failed
        .map(({ userId }) => rows.find((user) => user.id === userId)?.name)
        .filter((name): name is string => name !== undefined)
      setNotice(
        result.failed.length === 0
          ? `${result.approved.length}명을 승인했습니다.`
          : `${result.approved.length}명을 승인하고 ${result.failed.length}명은 실패했습니다.` +
              ` 신청서를 내지 않은 계정입니다: ${failedNames.join(', ')}`,
      )
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
      setConfirming(false)
    }
  }

  /**
   * 정지·해제.
   *
   * **마지막 활성 관리자인지 화면은 판단하지 않는다** (2-2 §2-2-7 — 검사는 서버에서 한다).
   * 화면은 활성 관리자가 몇 명인지 모른다. 서버가 403으로 거부하면 그 메시지를 그대로
   * 보여준다 — 막힌 것을 성공처럼 보이게 하면 관리자가 정지된 줄 알고 자리를 뜬다.
   */
  async function toggleStatus(user: User) {
    setWorking(true)
    setNotice(null)
    try {
      const next = user.status === 'SUSPENDED' ? 'ACTIVE' : 'SUSPENDED'
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
            {Object.entries(STATUS_LABEL).map(([value, label]) => (
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
              onClick={() => setConfirming(true)}
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
                    <TableCell>{ROLE_LABEL[user.role]}</TableCell>
                    <TableCell>
                      {STATUS_LABEL[user.status]}
                      {/*
                       * 왜 선택할 수 없는지 화면에 드러난다. 체크박스만 잠가두면
                       * 관리자는 고장난 줄 안다.
                       */}
                      {user.status === 'PENDING' && !approvable && (
                        <span className="ml-2 text-xs text-muted-foreground">
                          신청서 미제출
                        </span>
                      )}
                    </TableCell>
                    <TableCell>{formatDate(user.appliedAt)}</TableCell>
                    <TableCell>{formatDate(user.approvedAt)}</TableCell>
                    <TableCell className="text-right">
                      {user.status !== 'PENDING' && (
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          disabled={working}
                          onClick={() => toggleStatus(user)}
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

      <AlertDialog open={confirming} onOpenChange={setConfirming}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>선택한 회원을 승인할까요?</AlertDialogTitle>
            {/* 누구를 승인하는지 이름으로 보여준다. 건수만으로는 확인이 안 된다. */}
            <AlertDialogDescription>
              {selected.length}명을 승인합니다:{' '}
              {selected
                .map((id) => rows.find((user) => user.id === id)?.name)
                .filter(Boolean)
                .join(', ')}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction onClick={runApprove}>승인</AlertDialogAction>
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
