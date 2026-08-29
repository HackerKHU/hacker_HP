import type { ActiveUser } from '@/auth/session'
import { useSession } from '@/auth/session'

/**
 * 상태 표시. **한 칸짜리 표인 것이 의도다.**
 *
 * 이 화면은 `ACTIVE` 전용이라(spec [§3-1-3](../../../../spec/3-1-DESIGN-ARCHITECTURE.md)
 * 권한 매트릭스) 세션 타입이 다른 상태를 애초에 담지 못한다. 나중에 `INACTIVE`가
 * 세션 유니온에 들어오면(#229) **이 표가 타입 오류로 먼저 걸린다** — 새 상태가 "활동중"으로
 * 조용히 표시되는 것보다 낫다.
 */
const STATUS_LABEL: Record<ActiveUser['status'], string> = {
  ACTIVE: '활동중',
}

/**
 * 날짜 표시. **비어 있으면 `—`다.** 승인일은 있고 신청일이 없는 계정이 실제로 있다 —
 * 이 필드들이 생기기 전에 승인된 회원이다 (spec §3-2-2). 줄을 통째로 숨기지 않는 이유는
 * 회원 관리 목록과 같다: 무엇이 비었는지 보여야 문의할 수 있다.
 */
function formatDate(iso: string | null): string {
  if (iso === null) return '—'
  return new Date(iso).toLocaleDateString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
}

/**
 * 마이페이지 — 내 정보 (spec [2-1 §2-1-9](../../../../spec/2-1-USER-STORIES.md), #178).
 *
 * **보기 전용이다** (MUST). 고칠 수 있는 입력란을 두지 않는다 — 덜 만든 상태가 아니라
 * 결정이다. 이름은 구글 프로필에서 오고, 학번·학과는 관리자가 승인 심사에 쓴 값이라
 * 승인 뒤에 본인이 갈아치우면 **심사한 내용과 저장된 내용이 달라진다.**
 *
 * **새 API를 부르지 않는다** (MUST, spec §3-2-3 "마이페이지 조회에 새 경로를 두지 않는다").
 * `GET /auth/me`가 이미 전부 내려주고 세션이 그 값을 들고 있다 — 여기서 다시 부르면
 * 같은 값을 만드는 자리가 둘이 된다.
 *
 * **회원 탈퇴는 여기 없다.** 이 화면 위에 #226이 얹는다.
 */
export function MyPage() {
  const { state } = useSession()

  /*
   * 가드(`RequireActive`)가 이미 걸러 여기 오는 것은 `active`뿐이다. 그래도 렌더 순서상
   * 타입이 유니온이므로 좁혀 준다 — 가드를 믿고 단언(`as`)하면 라우트가 바뀌는 날
   * 화면이 조용히 깨진다.
   */
  if (state.kind !== 'active') return null
  const { user } = state

  return (
    /*
     * 신청·대기 화면과 **같은 폭이다** (`apps/web/README.md` "화면 폭과 여백"). 라벨 하나에
     * 값 하나가 붙은 짧은 카드라 넓힐 이유가 없고, 대기 안내의 `<dl>`과 같은 모양이라
     * 폭이 다르면 승인 전후로 같은 정보가 다른 화면처럼 보인다.
     */
    <section className="mx-auto max-w-sm">
      <h1 className="text-2xl font-semibold tracking-tight">내 정보</h1>

      {/*
        **고칠 수 있는 입력란이 없다** (spec 5-TESTING T-387 MUST). `<dl>`인 것이 그
        결정을 형태로 말한다 — 읽기 전용 `<input>`을 늘어놓으면 "지금은 못 고친다"로
        읽혀, 고치는 기능을 안 만든 것이 아니라 덜 만든 것처럼 보인다.
      */}
      <dl className="mt-8 space-y-3 border-t border-border pt-6 text-sm">
        {[
          ['이름', user.name],
          ['이메일', user.email],
          /*
           * 학번·학과는 신청서에서 채워진다. 이 필드가 생기기 전에 승인된 회원은
           * 비어 있고 일괄로 채우지 않는다 (spec §3-2-2).
           */
          ['학번', user.studentNo ?? '—'],
          ['학과', user.department ?? '—'],
          ['상태', STATUS_LABEL[user.status]],
          // "가입 신청일"은 `appliedAt`이다 — `createdAt`(첫 구글 로그인)이 아니다.
          ['가입 신청일', formatDate(user.appliedAt)],
          ['승인일', formatDate(user.approvedAt)],
        ].map(([label, value]) => (
          <div key={label} className="flex gap-4">
            <dt className="w-20 shrink-0 text-muted-foreground">{label}</dt>
            <dd className="min-w-0 break-all">{value}</dd>
          </div>
        ))}
      </dl>

      {/*
        **고치는 길이 없다는 것을 알린다.** 적지 않으면 "어디서 고치지"를 찾다가 못 찾고,
        운영진에게 묻는 대신 화면이 고장난 줄 안다.
      */}
      <p className="mt-8 text-sm leading-7 text-muted-foreground">
        여기 있는 정보는 직접 고칠 수 없습니다. 잘못된 값이 있으면 운영진에게
        문의해 주세요.
      </p>
    </section>
  )
}
