import { type FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import { submitApplication } from '@/api/auth'
import { ApiError } from '@/api/client'
import { getDepartments } from '@/api/departments'
import { hasApplied, useSession } from '@/auth/session'
import { useLiveAlert } from '@/components/live-alert/LiveAlertProvider'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { WithdrawButton } from '@/features/account/WithdrawButton'

/**
 * 입력 상한. **스키마에서 온 값이다** — `student_no varchar(20)` (spec §3-2-2).
 *
 * **자릿수는 여전히 고정하지 않는다** (#328). 계약이 요구하는 것은 "숫자일 것"과 이 상한뿐이다
 * (§3-2-3 MUST). 폼 예시가 10자리지만 그것이 실제 자릿수라는 근거가 없어, 길이를 규칙으로
 * 굳히면 그 길이가 아닌 학번을 가진 사람이 가입하지 못한다.
 */
const STUDENT_NO_MAX = 20

/**
 * 입력 예시. **자릿수는 규칙이 아니라 예시다.**
 *
 * **학번은 숫자만 받는다** (#328, §3-2-3 MUST). 학교 학번이 전부 숫자임을 확인하고 정했다 —
 * 한때는 *"편입·교환학생·대학원처럼 형태가 다른 학번이 실제로 있다"* 는 이유로 형식을 걸지
 * 않았는데(#38), 확인 결과 그 전제가 사실이 아니었다.
 *
 * **자릿수는 그대로 열어 둔다.** `inputMode`로 숫자 자판만 띄우고, 거절은 제출할 때 문구로
 * 알린다 — 입력 중에 글자를 지우면 잘못 붙여넣었을 때 무엇이 사라졌는지 알 수 없다.
 *
 * **연도를 문자열에 박지 않는다.** `2026xxxxxx`라고 적어두면 내년 신입생이 작년 예시를
 * 보게 되고, 고치는 사람이 나올 때까지 아무도 모른다. 화면에 보이는 예시는 "올해 들어온
 * 사람의 학번"이어야 뜻이 통하므로 실행 시점의 연도로 만든다.
 *
 * 모듈이 처음 불릴 때 한 번만 계산한다. 렌더마다 `new Date()`를 부를 이유가 없다.
 *
 * ⚠️ **뒤 여섯 자리는 확인된 값이 아니다.** 이대로면 총 10자리인데, 경희대 학번이
 * 실제로 10자리인지 **우리는 모른다.** 계약에는 `student_no varchar(20)`뿐이고
 * (spec §3-2-2) 자릿수 규칙이 없다. 픽스처가 10자리를 쓰지만 그건 우리가 지어낸 값이라
 * 근거가 못 된다. 자릿수가 실제와 다르면 사용자가 "이 형식이어야 한다"고 오해할 수
 * 있으니, **실제 자릿수를 확인하면 이 줄을 그 값으로 고친다.** 확인 전까지 길이는
 * 막지 않는다 — 숫자만 본다.
 */
const STUDENT_NO_PLACEHOLDER = `${new Date().getFullYear()}000000`

/**
 * 고칠 수 없는 칸의 모양. **`disabled`가 아니라 `readOnly`다** (#224).
 *
 * `disabled`는 값을 흐리게 만들고 포커스도 못 받아 **빈 칸처럼 읽힌다** — 여기 담긴 것은
 * "정보가 없다"가 아니라 "이 값으로 신청된다"는 사실이라, 또렷하게 보여야 한다. 복사도
 * 되어야 하고(학번 문의 때 이메일을 긁는다) 스크린리더도 값을 읽어야 한다.
 *
 * 대신 **고칠 수 있는 칸처럼 보이지도 않아야 한다.** 배경을 옅게 깔고 글자를 죽여 옆의
 * 학번·학과와 성격이 다르다는 것을 형태로 말한다. 커서도 텍스트 캐럿이 아닌 기본 화살표다.
 */
const READONLY_CLASS =
  'cursor-default bg-muted text-muted-foreground focus-visible:border-input focus-visible:ring-0'

/**
 * 학과는 **자유 입력이 아니라 목록에서 고른다** (spec §3-2-2 MUST). `<input>`이 아니라
 * `<select>`인 이유이고, 그래서 `maxLength` 같은 상한도 두지 않는다 — 고를 수 있는 값이
 * 전부 유효하다.
 *
 * **shadcn `Select`를 들이지 않고 네이티브 `<select>`를 쓴다.** 항목이 103개라 모바일에서는
 * OS 기본 피커가 커스텀 리스트박스보다 낫고(휠·검색·한 손 조작), radix 의존성도 늘지
 * 않는다. 모양은 `Input`과 맞춰 폼 안에서 결이 어긋나지 않게 한다.
 *
 * `appearance-none`으로 기본 화살표를 지우고 배경 이미지로 직접 그린다. 지우지 않으면
 * 브라우저마다 다른 화살표가 붙어 다른 입력들과 높이·여백이 어긋난다.
 */
const SELECT_CLASS =
  'h-9 w-full min-w-0 appearance-none rounded-md border border-input bg-transparent bg-no-repeat px-3 py-1 pr-9 text-base shadow-xs transition-[color,box-shadow] outline-none focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:pointer-events-none disabled:cursor-not-allowed disabled:opacity-50 md:text-sm dark:bg-input/30'

/**
 * 화살표. `currentColor`를 쓸 수 없어(배경 이미지다) 무채색 팔레트의 중간 값을 직접 넣는다.
 * 라이트/다크 어느 쪽에서도 배경과 충분히 구분된다.
 *
 * **위치·크기도 여기서 준다.** Tailwind 임의값(`bg-[position:...]`)으로 쓰면 밑줄 이스케이프를
 * 한 글자만 틀려도 값이 조용히 버려져 화살표가 왼쪽 끝에 붙는다 — 실제로 그랬다. 이미지와
 * 한 곳에 두면 셋이 같이 움직인다.
 */
const SELECT_ARROW = {
  backgroundImage:
    "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16' fill='none' stroke='%23888' stroke-width='1.5'%3E%3Cpath d='M4 6l4 4 4-4'/%3E%3C/svg%3E\")",
  backgroundPosition: 'right 0.625rem center',
  backgroundSize: '1rem',
} as const

/**
 * 화면 컨테이너. **로그인 화면과 같은 폭·정렬이다** — 로그인에서 여기로 넘어오는 흐름에서
 * 화면이 좌우로 튀지 않아야 한다.
 *
 * 상하 여백은 주지 않는다. 이 화면은 `AppLayout` 안이고 `<main>`이 이미 `py-8`을 준다 —
 * 레이아웃 밖 화면이 자기 여백을 갖는 것은 그쪽에 헤더도 `<main>`도 없기 때문이다.
 *
 * **`my-auto`로 세로 가운데에 둔다. `items-center`가 아니다.** `AppLayout`의 `<main>`이
 * 세로 flex라 여기서 위아래 `margin: auto`를 잡으면 남는 공간을 반씩 나눠 갖는다. 남는
 * 공간이 없으면 0이 되어 위에서 시작하고 아래로 흐르므로 **잘려서 못 보는 부분이 없다.**
 * 정렬로 가운데를 잡으면 그 상황에서 위쪽이 밖으로 밀려 스크롤로 닿을 수 없다.
 *
 * **레이아웃 안에서 가운데로 두는 화면은 여기뿐이다.** 이 화면은 짧은 카드 하나가 전부고
 * 길이가 거의 변하지 않는다. 목록·표·읽는 글은 위에서 시작해야 한다 — 가운데로 두면 행
 * 수나 글 길이에 따라 시작 위치가 위아래로 움직인다.
 *
 * 로딩 화면과 본 화면이 이 값을 같이 쓴다. 따로 적으면 한쪽만 바뀌어 로딩이 끝나는 순간
 * 화면이 움직인다.
 */
const CONTAINER = 'mx-auto my-auto max-w-sm'

/**
 * 신청·대기 화면. **한 화면이 두 모습을 가진다** (spec §3-1-6).
 *
 * `PENDING`이 접근할 수 있는 유일한 인증 화면이다. 로그아웃 버튼은 `AppLayout`의 헤더가
 * 이미 제공하므로 여기서 따로 만들지 않는다.
 */
export function PendingPage() {
  const session = useSession()
  const { state, refresh, reportApiError } = session
  const alert = useLiveAlert()
  const applied = hasApplied(session)

  const user = state.kind === 'pending' ? state.user : null

  /**
   * 신청 내용을 고치는 중인가. 대기 안내에서 "수정"을 누르면 켜진다.
   * 승인 전까지 고칠 수 있다 (§3-1-6).
   */
  const [editing, setEditing] = useState(false)
  /** 폼을 그리는가. 최초 신청이거나, 낸 내용을 고치는 중이면 그렇다. */
  const showForm = applied === false || editing
  /**
   * 사용자가 고친 값. **아직 손대지 않았으면 `null`이고, 그때는 계정에 있는 값을 그대로
   * 보여준다.** 고칠 수 있는 것은 학번·학과뿐이다 — 이름·이메일은 구글 계정의 값이라
   * 여기 담기지 않는다 (#224).
   *
   * 계정 값을 상태로 복사해 두지 않는다. 복사하려면 effect가 필요하고, 그러면 폼이 먼저
   * 빈 칸으로 그려진 뒤 한 박자 늦게 채워진다 — 그 사이를 보는 경합이 생긴다. 파생값으로
   * 두면 첫 렌더부터 채워져 있고, 서버에서 다시 읽어도 사용자가 입력한 값을 덮지 않는다.
   */
  const [draft, setDraft] = useState<{
    studentNo: string
    department: string
  } | null>(null)
  const values = draft ?? {
    studentNo: user?.studentNo ?? '',
    /*
     * **고르지 않은 상태를 빈 문자열로 둔다.** 첫 항목(컴퓨터공학과)을 기본값으로 두면
     * 손대지 않고 제출한 사람이 그 학과로 저장된다 — 부원 다수의 소속이라 틀려도 그럴듯해
     * 보여서 아무도 못 잡는다. 안 고르면 제출이 막히는 편이 낫다.
     */
    department: user?.department ?? '',
  }
  const [saving, setSaving] = useState(false)
  const [fieldErrors, setFieldErrors] = useState<{
    studentNo?: string
    department?: string
  }>({})
  const [unknownFailed, setUnknownFailed] = useState(false)
  const [checking, setChecking] = useState(false)

  /**
   * 고를 수 있는 학과. **서버가 내려준다** (`GET /departments`, #166). `null`은 "아직
   * 안 왔다"이고, 실패는 `departmentsFailed`가 따로 든다 — 빈 배열로 뭉뚱그리면 목록이
   * 정말 비어 온 경우와 못 받은 경우를 화면이 구분하지 못한다.
   */
  const [departments, setDepartments] = useState<string[] | null>(null)
  const [departmentsFailed, setDepartmentsFailed] = useState(false)

  /**
   * **못 받으면 신청 자체가 막힌다** (#166). 학과는 필수인데 목록에서만 고를 수 있어,
   * 조용히 빈 `<select>`를 두면 사용자는 제출이 안 되는 이유를 알 수 없다. 실패를 알리고
   * 다시 부를 자리를 준다.
   */
  const loadDepartments = useCallback(() => {
    setDepartmentsFailed(false)
    getDepartments().then(setDepartments, () => setDepartmentsFailed(true))
  }, [])

  // 대기 안내에는 고를 자리가 없다. 수정을 눌러 폼이 열릴 때 부른다.
  useEffect(() => {
    if (showForm) loadDepartments()
  }, [showForm, loadDepartments])

  /**
   * **신청 여부를 모르는 상태를 푼다** (spec §3-1-6 MUST).
   *
   * `403 PENDING_APPROVAL`로만 `PENDING`을 알아낸 경우 세션에 사용자가 없어 `applied`가
   * `null`이다. 여기서 기본값으로 폼을 띄우면 **이미 신청한 사람이 다시 쓰게 된다** —
   * 자기가 낸 내용을 볼 수 없으니 학번을 새로 기억해내야 하고, 재제출은 성공하므로
   * 잘못된 값이 그대로 저장된다.
   *
   * **"모름"은 로딩과 다르다.** 첫 조회가 끝나기 전에도 `hasApplied()`는 `null`인데,
   * 그때 물어보면 이 한 번을 로딩 구간에서 써버리고 정작 403으로 도착한 뒤에는 다시
   * 묻지 않는다. `pending`이면서 사용자가 없는 경우만 "모름"이다.
   *
   * 한 번만 부른다. `refresh()`가 실패해 여전히 모르면 다시 불러도 같은 결과다.
   */
  const unknown = state.kind === 'pending' && state.user === null
  const asked = useRef(false)
  useEffect(() => {
    if (!unknown || asked.current) return
    asked.current = true
    refresh().catch(() => {
      // 여기서 삼키면 화면이 "확인하는 중"에 영영 머문다. 다시 시도할 길을 준다.
      setUnknownFailed(true)
      alert.error('상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.')
    })
  }, [alert, unknown, refresh])

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (saving) return

    /*
     * 계약이 요구하는 것은 숫자일 것과 20자 이하다 (§3-2-3 MUST, #328). 서버가 거부할
     * 요청을 굳이 보내지 않는다 — 같은 규칙을 앞당겨 적용하는 것이지 서버 검증을
     * 대신하지 않는다.
     *
     * **빈 값과 숫자 아님을 한 문구로 묶는다.** 서버도 그렇게 답한다 — 나누면 화면과
     * 서버가 같은 입력에 다른 말을 하게 된다.
     */
    if (!/^[0-9]+$/.test(values.studentNo.trim())) {
      setFieldErrors({ studentNo: '학번을 숫자로 입력해주세요.' })
      return
    }
    /*
     * 학과는 따로 본다. 위 문구에 묶으면 학번을 채운 사람이 무엇이 빠졌는지 모른다 —
     * `<select>`는 비어 있어도 칸이 채워진 것처럼 보여서 더 그렇다.
     */
    if (values.department === '') {
      setFieldErrors({ department: '학과를 선택해주세요.' })
      return
    }

    setSaving(true)
    setFieldErrors({})
    try {
      await submitApplication({
        studentNo: values.studentNo.trim(),
        // 목록에서 고른 값이라 다듬을 것이 없다.
        department: values.department,
      })
      /*
       * **저장된 것을 확인한 뒤에 화면을 바꾼다.** 낙관적으로 먼저 바꾸면 서버가 거부한
       * 경우에도 대기 안내가 떠, 내지 않은 신청서를 낸 것으로 믿게 된다.
       */
      await refresh()
      setEditing(false)
      // 서버가 저장한 값이 다시 내려오므로 임시 입력은 버린다.
      setDraft(null)
      alert.success('가입 신청서를 제출했습니다.')
    } catch (caught: unknown) {
      /*
       * **코드를 세션 계층에 넘긴다** (spec 5-TESTING T-116). `403`은 `PENDING_APPROVAL`·
       * `SUSPENDED`·`FORBIDDEN`이 함께 쓰므로 화면이 넘기지 않으면 세션이 옛 상태로 남는다 —
       * 대기 중에 정지당한 사람이 제출하면 오류 문구만 뜨고 정지 안내로 가지 못한다.
       * 다른 화면(공지·회원)이 모두 이렇게 한다. 여기만 빠져 있었다.
       */
      if (reportApiError(caught)) return
      /*
       * **실패했는데 성공한 것처럼 보이면 안 된다.** 입력을 그대로 두고 서버가 준 사유를
       * 보여준다 — 409 DUPLICATE_STUDENT_NO처럼 무엇을 고쳐야 하는지는 서버가 안다.
       * 입력을 지우면 무엇이 거부됐는지 확인할 방법이 없다.
       *
       * 세션이 정리되어 화면이 바뀌는 경우에는 이 문구가 보이지 않는다 — 가드가 다른
       * 화면으로 옮기기 때문이다. 바뀌지 않는 경우(409·400)에만 남는다.
       */
      alert.error(
        caught instanceof ApiError
          ? caught.message
          : '신청서를 내지 못했습니다. 잠시 후 다시 시도해 주세요.',
      )
    } finally {
      setSaving(false)
    }
  }

  /** 승인은 이 화면에 저절로 반영되지 않는다 (§3-1-6). 사용자가 직접 확인한다. */
  async function handleRecheck() {
    setChecking(true)
    setUnknownFailed(false)
    try {
      await refresh()
    } catch {
      /*
       * `refresh()`는 **상태를 알아내지 못했을 때만** 거부한다 (spec §3-1-5) — 세션은
       * 그대로다. 아무 말도 안 하면 사용자는 버튼이 고장난 줄 안다.
       */
      setUnknownFailed(true)
      alert.error('상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.')
    } finally {
      setChecking(false)
    }
  }

  // 신청 여부를 모르는 동안에는 폼도 안내도 띄우지 않는다.
  if (applied === null) {
    return (
      // 아래 본 화면과 같은 폭·정렬이다. 다르면 로딩이 끝나는 순간 화면이 좌우로 튄다.
      <section className={CONTAINER}>
        <h1 className="text-2xl font-semibold tracking-tight">가입 신청</h1>
        {unknownFailed ? (
          <>
            <p className="mt-6 text-sm text-muted-foreground">
              상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.
            </p>
            {/*
              아래 대기 화면의 "다시 확인"과 같은 동작·같은 라벨이므로 모양도 같다.
              여기서만 좁으면, 다시 확인이 성공해 대기 화면으로 넘어가는 순간 같은
              버튼이 전체폭으로 늘어난다.
            */}
            <Button
              type="button"
              className="mt-4 w-full"
              disabled={checking}
              onClick={handleRecheck}
            >
              {checking ? '확인 중' : '다시 확인'}
            </Button>
          </>
        ) : (
          /* 가드의 "불러오는 중"과 구분되는 문구를 쓴다 — 지금 무엇을 하는지도 드러난다. */
          <p className="mt-6 text-sm text-muted-foreground">
            신청 정보를 확인하는 중
          </p>
        )}
      </section>
    )
  }

  /**
   * 목록이 아직/영영 없어도 **이미 낸 학과는 칸에 남는다.** 없으면 `<select>`의 값이
   * 어느 `<option>`과도 맞지 않아 빈 칸으로 보이고, 수정하러 들어온 사람은 자기가 낸
   * 학과가 지워진 줄 안다.
   */
  const departmentOptions =
    departments ?? (values.department === '' ? [] : [values.department])

  return (
    <section className={CONTAINER}>
      <h1 className="text-2xl font-semibold tracking-tight">
        {showForm ? '가입 신청' : '승인 대기 중'}
      </h1>

      {showForm ? (
        <>
          <p className="mt-3 text-sm text-muted-foreground">
            승인 심사에 필요한 정보입니다. 승인 전까지 언제든 고칠 수 있습니다.
          </p>

          {/*
           * **순서는 "확인하는 것 → 적는 것"이다** (#293). 앞의 둘(이름·이메일)은 구글
           * 계정에서 온 값이라 고칠 수 없고, 뒤의 둘(학번·학과)은 여기서 채운다. 섞어 두면
           * 칸을 채우다 막히고 다시 채우기를 반복하게 되고, 어디부터 손대야 하는지가
           * 순서만으로는 드러나지 않는다.
           *
           * **마이페이지와 같은 순서다** (#178 — 이름·이메일·학번·학과). 신청 화면만 다르면
           * 승인 전후로 같은 정보가 다른 자리에 있어, 확인하러 온 사람이 매번 다시 훑는다.
           */}
          <form onSubmit={handleSubmit} className="mt-8 space-y-6">
            {/*
             * **이름·이메일은 구글 계정의 값이고 고칠 수 없다** (#224).
             *
             * 이름은 한때 신청서에서 직접 받았다. 학교 Workspace가 표시 이름에
             * `[학생](소프트웨어융합대학 컴퓨터공학부)`를 붙여 내려주기 때문이었는데,
             * #215가 그 접미사를 계정 생성 시점에 걷어내면서 저장된 값이 곧 실명이 됐다.
             *
             * **서버도 `name`을 받지 않는다.** 화면만 잠그면 API를 직접 부르는 쪽이 남는다.
             *
             * 이메일은 원래 신청 항목이 아니었다. 여기 **표시만** 더한다 — 구글 계정이 여럿인
             * 사람이 어느 계정으로 신청하는지 폼 안에서 확인할 수 있어야 한다.
             */}
            <div className="space-y-2">
              <Label htmlFor="application-name">이름</Label>
              <Input
                id="application-name"
                value={user?.name ?? ''}
                readOnly
                className={READONLY_CLASS}
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="application-email">이메일</Label>
              <Input
                id="application-email"
                value={user?.email ?? ''}
                readOnly
                className={READONLY_CLASS}
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="application-student-no">학번</Label>
              <Input
                id="application-student-no"
                value={values.studentNo}
                placeholder={STUDENT_NO_PLACEHOLDER}
                maxLength={STUDENT_NO_MAX}
                inputMode="numeric"
                aria-invalid={fieldErrors.studentNo !== undefined}
                aria-describedby={
                  fieldErrors.studentNo
                    ? 'application-student-no-error'
                    : undefined
                }
                onChange={(event) =>
                  setDraft({ ...values, studentNo: event.target.value })
                }
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="application-department">학과</Label>
              {/*
                `<option value="">`을 첫 항목으로 둬서 "안 고름"이 눈에 보이게 한다.
                `disabled`는 걸지 않는다 — 잘못 골랐을 때 되돌릴 자리가 없어진다.
              */}
              <select
                id="application-department"
                className={SELECT_CLASS}
                style={SELECT_ARROW}
                value={values.department}
                /*
                 * 아직 못 받았으면 고를 수 없다. 열리는데 안이 비어 있으면 "학과가 없다"로
                 * 읽힌다 — 목록이 오는 중이라는 것은 아래 문구가 말한다.
                 */
                disabled={departments === null}
                aria-invalid={fieldErrors.department !== undefined}
                aria-describedby={
                  fieldErrors.department
                    ? 'application-department-error'
                    : undefined
                }
                onChange={(event) =>
                  setDraft({ ...values, department: event.target.value })
                }
              >
                <option value="">
                  {departments === null
                    ? '학과를 불러오는 중'
                    : '학과를 선택해 주세요'}
                </option>
                {departmentOptions.map((department) => (
                  <option key={department} value={department}>
                    {department}
                  </option>
                ))}
              </select>
              {departmentsFailed && (
                <p role="alert" className="text-sm text-muted-foreground">
                  학과 목록을 불러오지 못했습니다. 학과를 골라야 신청할 수
                  있습니다.{' '}
                  <button
                    type="button"
                    className="underline underline-offset-4 hover:text-foreground"
                    onClick={loadDepartments}
                  >
                    다시 불러오기
                  </button>
                </p>
              )}
            </div>

            <div className="min-h-12" data-form-feedback-slot="true">
              {fieldErrors.studentNo && (
                <p
                  id="application-student-no-error"
                  role="alert"
                  className="text-sm text-muted-foreground"
                >
                  {fieldErrors.studentNo}
                </p>
              )}
              {fieldErrors.department && (
                <p
                  id="application-department-error"
                  role="alert"
                  className="text-sm text-muted-foreground"
                >
                  {fieldErrors.department}
                </p>
              )}
            </div>

            {/*
             * **좁은 카드에서는 주 동작이 전체폭 하나다** (`apps/web/README.md` "폼 버튼").
             * 384px짜리 카드 안에서 오른쪽으로 밀면 버튼이 한쪽에 치우쳐 보이고, 바로 옆
             * 로그인 카드는 구글 버튼이 이미 전체폭이라 결도 어긋난다.
             *
             * **취소는 그 아래 글자 버튼으로 내린다.** 전체폭 둘을 세로로 쌓으면 크기가
             * 같아 취소가 제출만큼 중요해 보인다. 로그인 카드가 전체폭 버튼 아래에
             * `← 동아리 소개로 돌아가기`를 두는 것과 같은 구조다 — 새로 만든 모양이 아니다.
             * `ghost`라 테두리·배경이 없고, 폭은 채워 누를 자리를 넓게 준다.
             *
             * 순서가 바뀌어도 Enter 제출은 그대로다 — 취소가 `type="button"`이라 이 폼의
             * submit 버튼은 여전히 하나뿐이다.
             */}
            <div className="flex flex-col gap-1">
              <Button type="submit" className="w-full" disabled={saving}>
                {saving ? '제출 중' : '제출'}
              </Button>
              {editing && (
                <Button
                  type="button"
                  variant="ghost"
                  className="w-full text-muted-foreground hover:text-foreground"
                  onClick={() => {
                    setEditing(false)
                    setFieldErrors({})
                    setDraft(null)
                  }}
                >
                  취소
                </Button>
              )}
            </div>
          </form>
        </>
      ) : (
        <>
          <p className="mt-3 text-sm leading-7 text-muted-foreground">
            신청서를 받았습니다. 운영진이 확인한 뒤 승인합니다.
          </p>

          {/* 폼과 같은 순서다 (#293). 제출 전후로 값이 자리를 옮기면 무엇이 저장됐는지 다시 훑게 된다. */}
          {user && (
            <dl className="mt-8 space-y-3 border-t border-border pt-6 text-sm">
              <div className="flex gap-4">
                <dt className="w-16 shrink-0 text-muted-foreground">이름</dt>
                <dd>{user.name}</dd>
              </div>
              <div className="flex gap-4">
                <dt className="w-16 shrink-0 text-muted-foreground">학번</dt>
                <dd>{user.studentNo ?? '—'}</dd>
              </div>
              {/*
                이 필드가 생기기 전에 신청한 계정은 값이 없다 (§3-2-2). 학번과 같은
                방식으로 `—`를 그린다 — 줄을 통째로 숨기면 무엇이 비었는지 안 보인다.
              */}
              <div className="flex gap-4">
                <dt className="w-16 shrink-0 text-muted-foreground">학과</dt>
                <dd>{user.department ?? '—'}</dd>
              </div>
            </dl>
          )}

          {/*
           * **저절로 바뀌지 않는다는 것을 알린다** (spec §3-1-6 MUST). 버튼만 두고
           * 설명이 없으면 누를 이유를 모르고, 승인된 뒤에도 이 화면에 머문다.
           */}
          <p className="mt-8 text-sm leading-7 text-muted-foreground">
            승인되어도 이 화면은 저절로 바뀌지 않습니다. 아래 버튼으로 다시
            확인해 주세요.
          </p>

          {/*
           * 폼은 아니지만 **같은 좁은 카드라 같은 모양을 쓴다** — 주 동작이 전체폭이고
           * 부수 동작은 그 아래 글자 버튼이다. 한 카드 안에서 위(신청 폼)와 아래가
           * 다른 모양이면 규칙이 둘로 보인다.
           *
           * 바로 위 문장이 "**아래 버튼으로** 다시 확인해 주세요"라고 버튼을 가리키는데,
           * 전체폭이면 그 문장 바로 밑을 가득 채우므로 가리키는 것과 가리켜지는 것이
           * 붙는다. (오른쪽으로 밀면 그 둘이 갈라져서 앞서 왼쪽에 뒀던 자리다.)
           */}
          <div className="mt-4 flex flex-col gap-1">
            <Button
              type="button"
              className="w-full"
              disabled={checking}
              onClick={handleRecheck}
            >
              {checking ? '확인 중' : '다시 확인'}
            </Button>
            <Button
              type="button"
              variant="ghost"
              className="w-full text-muted-foreground hover:text-foreground"
              onClick={() => setEditing(true)}
            >
              신청 내용 수정
            </Button>
          </div>
        </>
      )}

      {/*
        **탈퇴 진입점** (spec §3-1-6 MUST, 5-TESTING T-390). `PENDING`은 마이페이지를 볼 수
        없으므로 **탈퇴가 화면에 드러나는 곳이 여기뿐이다** — 없으면 구글 로그인만 해보고
        신청서를 내지 않은 사람이 자기 계정을 지울 방법이 아예 없다. 그 계정은
        승인 목록(`status=PENDING&applied=true`)에도 뜨지 않아 관리자 눈에도 안 띈다.

        **두 모습 모두에 둔다** (MUST). 삼항 밖에 두어 신청 폼에서도 대기 안내에서도 보인다 —
        마음이 바뀌는 것은 신청서를 내기 전에도 낸 뒤에도 일어난다.

        로그아웃(헤더)과 **나란히 두되 같은 무게로 두지 않는다.** 되돌릴 수 없는 조작이라
        구분선 아래 글자 버튼 하나이고, 확인 창을 거친다.
      */}
      <div className="mt-10 flex flex-col items-end gap-2 border-t border-border pt-4">
        <WithdrawButton />
      </div>
    </section>
  )
}
