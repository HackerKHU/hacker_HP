import { type FormEvent, useEffect, useRef, useState } from 'react'
import { submitApplication } from '@/api/auth'
import { ApiError } from '@/api/client'
import { hasApplied, useSession } from '@/auth/session'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

/**
 * 입력 상한. **스키마에서 온 값이다** — `student_no varchar(20)`, `name varchar(50)`
 * (spec §3-2-2).
 *
 * **그 밖의 형식 규칙은 만들지 않는다.** 계약이 요구하는 것은 "공백이 아닐 것"뿐이다
 * (§3-2-3 MUST). 자릿수나 숫자만 같은 규칙을 지어내면 계약에 없는 이유로 유효한 학번을
 * 막는다 — 편입·교환학생·대학원처럼 형태가 다른 학번이 실제로 있다.
 */
const STUDENT_NO_MAX = 20
const NAME_MAX = 50

/**
 * 입력 예시. **규칙이 아니라 예시다.**
 *
 * 위 주석대로 계약이 요구하는 것은 "공백이 아닐 것"뿐이고 학번 형식 제약은 없다
 * (#38 결정 4). `xxxxxx`를 자릿수 요구로 읽지 않도록 `maxLength` 말고는 아무것도
 * 걸지 않았다 — `pattern`도 `inputMode`도 주지 않는다. 편입·교환학생·대학원처럼
 * 형태가 다른 학번이 실제로 있고 그것들도 그대로 받아야 한다.
 *
 * **연도를 문자열에 박지 않는다.** `2026xxxxxx`라고 적어두면 내년 신입생이 작년 예시를
 * 보게 되고, 고치는 사람이 나올 때까지 아무도 모른다. 화면에 보이는 예시는 "올해 들어온
 * 사람의 학번"이어야 뜻이 통하므로 실행 시점의 연도로 만든다.
 *
 * 모듈이 처음 불릴 때 한 번만 계산한다. 렌더마다 `new Date()`를 부를 이유가 없다.
 *
 * ⚠️ **뒤 여섯 자리는 확인된 값이 아니다.** 이대로면 총 10자리인데, 경희대 학번이
 * 실제로 10자리인지 **우리는 모른다.** 계약에는 `student_no varchar(20)`뿐이고
 * (spec §3-2-2) 형식 규칙이 없다. 픽스처가 10자리를 쓰지만 그건 우리가 지어낸 값이라
 * 근거가 못 된다. 자릿수가 실제와 다르면 사용자가 "이 형식이어야 한다"고 오해할 수
 * 있으니, **실제 자릿수를 확인하면 이 줄을 그 값으로 고친다.** 확인 전까지도 입력은
 * 막지 않는다 — 위 주석대로 `pattern`을 걸지 않았다.
 */
const STUDENT_NO_PLACEHOLDER = `${new Date().getFullYear()}000000`

/** 구글 프로필에서 미리 채워져 보일 일이 드물다. 비어 있을 때만 나온다. */
const NAME_PLACEHOLDER = '홍길동'

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
  const applied = hasApplied(session)

  const user = state.kind === 'pending' ? state.user : null

  /**
   * 신청 내용을 고치는 중인가. 대기 안내에서 "수정"을 누르면 켜진다.
   * 승인 전까지 고칠 수 있다 (§3-1-6).
   */
  const [editing, setEditing] = useState(false)
  /**
   * 사용자가 고친 값. **아직 손대지 않았으면 `null`이고, 그때는 계정에 있는 값을 그대로
   * 보여준다** (§3-1-4 — 이름은 최초에 구글 프로필에서 받아둔다).
   *
   * 계정 값을 상태로 복사해 두지 않는다. 복사하려면 effect가 필요하고, 그러면 폼이 먼저
   * 빈 칸으로 그려진 뒤 한 박자 늦게 채워진다 — 그 사이를 보는 경합이 생긴다. 파생값으로
   * 두면 첫 렌더부터 채워져 있고, 서버에서 다시 읽어도 사용자가 입력한 값을 덮지 않는다.
   */
  const [draft, setDraft] = useState<{
    studentNo: string
    name: string
  } | null>(null)
  const values = draft ?? {
    studentNo: user?.studentNo ?? '',
    name: user?.name ?? '',
  }
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [checking, setChecking] = useState(false)

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
      setError('상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.')
    })
  }, [unknown, refresh])

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (saving) return

    // 계약이 요구하는 것은 공백이 아닐 것뿐이다 (§3-2-3 MUST). 서버가 거부할 요청을
    // 굳이 보내지 않는다 — 같은 규칙을 앞당겨 적용하는 것이지 서버 검증을 대신하지 않는다.
    if (values.studentNo.trim() === '' || values.name.trim() === '') {
      setError('학번과 이름을 입력해주세요.')
      return
    }

    setSaving(true)
    setError(null)
    try {
      await submitApplication({
        studentNo: values.studentNo.trim(),
        name: values.name.trim(),
      })
      /*
       * **저장된 것을 확인한 뒤에 화면을 바꾼다.** 낙관적으로 먼저 바꾸면 서버가 거부한
       * 경우에도 대기 안내가 떠, 내지 않은 신청서를 낸 것으로 믿게 된다.
       */
      await refresh()
      setEditing(false)
      // 서버가 저장한 값이 다시 내려오므로 임시 입력은 버린다.
      setDraft(null)
    } catch (caught: unknown) {
      /*
       * **코드를 세션 계층에 넘긴다** (spec 5-TESTING T-116). `403`은 `PENDING_APPROVAL`·
       * `SUSPENDED`·`FORBIDDEN`이 함께 쓰므로 화면이 넘기지 않으면 세션이 옛 상태로 남는다 —
       * 대기 중에 정지당한 사람이 제출하면 오류 문구만 뜨고 정지 안내로 가지 못한다.
       * 다른 화면(공지·회원)이 모두 이렇게 한다. 여기만 빠져 있었다.
       */
      reportApiError(caught)
      /*
       * **실패했는데 성공한 것처럼 보이면 안 된다.** 입력을 그대로 두고 서버가 준 사유를
       * 보여준다 — 409 DUPLICATE_STUDENT_NO처럼 무엇을 고쳐야 하는지는 서버가 안다.
       * 입력을 지우면 무엇이 거부됐는지 확인할 방법이 없다.
       *
       * 세션이 정리되어 화면이 바뀌는 경우에는 이 문구가 보이지 않는다 — 가드가 다른
       * 화면으로 옮기기 때문이다. 바뀌지 않는 경우(409·400)에만 남는다.
       */
      setError(
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
    setError(null)
    try {
      await refresh()
    } catch {
      /*
       * `refresh()`는 **상태를 알아내지 못했을 때만** 거부한다 (spec §3-1-5) — 세션은
       * 그대로다. 아무 말도 안 하면 사용자는 버튼이 고장난 줄 안다.
       */
      setError('상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.')
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
        {error ? (
          <>
            <p role="alert" className="mt-6 text-sm text-muted-foreground">
              {error}
            </p>
            <Button
              type="button"
              className="mt-4"
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

  const showForm = applied === false || editing

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

          <form onSubmit={handleSubmit} className="mt-8 space-y-6">
            <div className="space-y-2">
              <Label htmlFor="application-student-no">학번</Label>
              <Input
                id="application-student-no"
                value={values.studentNo}
                placeholder={STUDENT_NO_PLACEHOLDER}
                maxLength={STUDENT_NO_MAX}
                onChange={(event) =>
                  setDraft({ ...values, studentNo: event.target.value })
                }
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="application-name">이름</Label>
              <Input
                id="application-name"
                value={values.name}
                placeholder={NAME_PLACEHOLDER}
                maxLength={NAME_MAX}
                onChange={(event) =>
                  setDraft({ ...values, name: event.target.value })
                }
              />
            </div>

            {error && (
              <p role="alert" className="text-sm text-muted-foreground">
                {error}
              </p>
            )}

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
                    setError(null)
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

          {user && (
            <dl className="mt-8 space-y-3 border-t border-border pt-6 text-sm">
              <div className="flex gap-4">
                <dt className="w-16 shrink-0 text-muted-foreground">학번</dt>
                <dd>{user.studentNo ?? '—'}</dd>
              </div>
              <div className="flex gap-4">
                <dt className="w-16 shrink-0 text-muted-foreground">이름</dt>
                <dd>{user.name}</dd>
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

          {/* 대기 안내에서도 오류를 그린다. 폼 분기에만 두면 "다시 확인"이 실패해도 조용하다. */}
          {error && (
            <p role="alert" className="mt-4 text-sm text-muted-foreground">
              {error}
            </p>
          )}

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
    </section>
  )
}
