import { Link, useSearchParams } from 'react-router-dom'
import { GOOGLE_LOGIN_PATH } from '@/api/auth'
import { useSession } from '@/auth/session'
import { Button } from '@/components/ui/button'
import { lookup } from '@/lib/lookup'
import { GoogleLogo } from './GoogleLogo'

/**
 * 콜백이 되돌려 보내는 실패 코드와 화면 문구 (계약 §3-2-3 표).
 *
 * **매핑에 없는 값은 아무것도 띄우지 않는다.** 두 가지 이유다.
 *
 * 하나, 받은 값을 그대로 그리면 계약이 깨졌을 때 그대로 새어나간다. 계약은 쿼리에
 * 이메일·토큰·예외 메시지를 담지 않기로 했지만(MUST), 화면이 그 약속에 기대어 출력하면
 * 약속이 깨진 날 주소창 내용이 그대로 사용자에게 보인다.
 *
 * 둘, `?error=bogus`처럼 주소를 잘못 친 사람에게 일반 오류를 띄우면 **없는 문제를
 * 알리는 셈**이다. 아무 일도 없었으니 아무것도 말하지 않는다.
 */
/**
 * 버튼 문구. **승인된 CTA 셋 중 `Continue with Google`의 한글판이다** (spec §3-1-5).
 * 이 버튼이 로그인과 가입을 겸하므로(3-3 결정 13) 뜻도 정확하다. 임의 표현이나
 * `Google` 단독은 승인 범위 밖이다.
 */
const GOOGLE_BUTTON_LABEL = 'Google로 계속하기'

const ERROR_MESSAGE: Record<string, string> = {
  domain: '경희대 구글 계정(@khu.ac.kr)으로 로그인해 주세요.',
  unverified:
    '구글 계정의 이메일 인증이 끝나지 않았습니다. 구글에서 인증을 마친 뒤 다시 시도해 주세요.',
  /*
   * 정지 안내. **`?error=suspended`와 정지된 세션이 같은 문구를 쓴다** (spec §3-1-5 MUST).
   * 사용자에게는 같은 사실이고, 어느 경로로 왔는지는 구별할 수도 없는 내부 사정이다.
   */
  suspended: '이용이 정지된 계정입니다. 동아리 운영진에게 문의해 주세요.',
  // 원인을 자세히 알리지 않는다 (계약 §3-2-3).
  failed: '로그인하지 못했습니다. 잠시 후 다시 시도해 주세요.',
}

/**
 * 로그인 화면. **가입도 같은 버튼이다** (3-3 결정 13) — 별도 가입 화면은 없다.
 *
 * 이 화면에는 `AppLayout`(헤더)을 붙이지 않는다. 로그인 전에는 메뉴가 갈 곳이 없다.
 */
export function LoginPage() {
  const [searchParams] = useSearchParams()
  const { state } = useSession()

  /*
   * 정지 안내에 도달하는 길이 둘이다 (spec §3-1-5).
   *
   * ① 로그인 시도가 막혀 서버가 `?error=suspended`로 되돌린 경우
   * ② 이용 중 관리자가 정지시켜(2-2 §2-2-3 MUST) 다음 요청이 403 SUSPENDED로 막히고
   *    세션이 정지로 정리되어 가드가 여기로 보낸 경우 — **주소에 쿼리가 없다**
   *
   * ②가 없으면 그 사람은 **설명 없는 로그인 화면으로 한 번 튕긴다.** 다시 로그인하면
   * ①이 안내를 주므로 영영 모르는 것은 아니지만, 그 한 번을 없애는 것이 ②의 몫이다.
   */
  const code =
    state.kind === 'suspended' ? 'suspended' : searchParams.get('error')
  /*
   * **`lookup()`으로 꺼낸다. 직접 인덱싱하지 않는다.**
   *
   * `?error=__proto__`는 선언한 적 없는 키인데도 `Object.prototype`을 돌려주고, 그것이
   * truthy라 아래에서 객체를 렌더하려다 화면이 죽는다 — **URL 하나로 공개 로그인
   * 진입점이 통째로 멈춘다.** `constructor`·`toString`도 같다.
   */
  const message = lookup(ERROR_MESSAGE, code)

  return (
    <section className="mx-auto max-w-sm py-16">
      <h1 className="text-2xl font-semibold tracking-tight">로그인</h1>
      <p className="mt-3 text-sm text-muted-foreground">
        구글 계정으로 로그인합니다. 처음이라면 이 버튼으로 가입이 함께
        진행됩니다.
      </p>

      {message && (
        <p
          role="alert"
          className="mt-6 rounded-md border border-border px-4 py-3 text-sm text-foreground"
        >
          {message}
        </p>
      )}

      {/*
       * **브라우저 전체를 이동시킨다. `fetch`가 아니다.**
       *
       * OAuth는 리다이렉트 흐름이라 `fetch`로는 성립하지 않는다 — 구글 로그인 화면이
       * 떠야 하는데 응답만 받아오게 된다. 그래서 `api/auth.ts`에도 함수가 없고 경로
       * 상수만 있다.
       */}
      {/*
       * **로고는 공식 것, 버튼과 문구는 우리 것이다** (spec §3-1-5).
       *
       * 색·테두리·글씨색을 가이드라인 Light 값으로 이 버튼에서만 맞춘다. 전역 토큰을
       * 건드리지 않는다 — 사이트의 다른 버튼까지 구글 규격이 될 이유가 없다.
       * `--border`(#e5e5e5)와 `--foreground`(#262626)가 규격(#747775, #1f1f1f)과 달라
       * 여기서만 덮어쓴다.
       *
       * 여백은 공식 배포본(180×40)에서 잰 값이다 — 좌우 12px, 로고와 글씨 사이 14px.
       */}
      <Button
        type="button"
        variant="outline"
        className="mt-8 h-10 gap-[14px] border-[#747775] px-3 text-sm text-[#1f1f1f]"
        onClick={() => {
          window.location.assign(GOOGLE_LOGIN_PATH)
        }}
      >
        <GoogleLogo className="size-5" />
        {GOOGLE_BUTTON_LABEL}
      </Button>

      <p className="mt-6 text-sm text-muted-foreground">
        <Link to="/" className="transition-colors hover:text-foreground">
          ← 동아리 소개로 돌아가기
        </Link>
      </p>
    </section>
  )
}
