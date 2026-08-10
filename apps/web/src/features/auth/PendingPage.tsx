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
 * 신청·대기 화면. **한 화면이 두 모습을 가진다** (spec §3-1-6).
 *
 * `PENDING`이 접근할 수 있는 유일한 인증 화면이다. 로그아웃 버튼은 `AppLayout`의 헤더가
 * 이미 제공하므로 여기서 따로 만들지 않는다.
 */
export function PendingPage() {
  const session = useSession()
  const { state, refresh } = session
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
    void refresh()
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
       * **실패했는데 성공한 것처럼 보이면 안 된다.** 입력을 그대로 두고 서버가 준 사유를
       * 보여준다 — 409 DUPLICATE_STUDENT_NO처럼 무엇을 고쳐야 하는지는 서버가 안다.
       * 입력을 지우면 무엇이 거부됐는지 확인할 방법이 없다.
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
    try {
      await refresh()
    } finally {
      setChecking(false)
    }
  }

  // 신청 여부를 모르는 동안에는 폼도 안내도 띄우지 않는다.
  if (applied === null) {
    return (
      <section>
        <h1 className="text-2xl font-semibold tracking-tight">가입 신청</h1>
        {/* 가드의 "불러오는 중"과 구분되는 문구를 쓴다 — 지금 무엇을 하는지도 드러난다. */}
        <p className="mt-6 text-sm text-muted-foreground">
          신청 정보를 확인하는 중
        </p>
      </section>
    )
  }

  const showForm = applied === false || editing

  return (
    <section className="max-w-md">
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

            <div className="flex gap-2">
              <Button type="submit" disabled={saving}>
                {saving ? '제출 중' : '제출'}
              </Button>
              {editing && (
                <Button
                  type="button"
                  variant="outline"
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

          <div className="mt-4 flex gap-2">
            <Button type="button" disabled={checking} onClick={handleRecheck}>
              {checking ? '확인 중' : '다시 확인'}
            </Button>
            <Button
              type="button"
              variant="outline"
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
