import { Link, useSearchParams } from 'react-router-dom'
import { GOOGLE_LOGIN_PATH } from '@/api/auth'
import { useSession } from '@/auth/session'
import { lookup } from '@/lib/lookup'

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
 * 구글 공식 로그인 버튼 그림. `public/google/`에 원본 그대로 두고 출처는 그 폴더의
 * README에 있다 (spec §3-1-5 — 수정 없이 쓴다).
 */
const GOOGLE_BUTTON_ASSET = '/google/signin-light-square.svg'

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
      <button
        type="button"
        /*
         * **`Button`으로 감싸지 않는다.** 공식 에셋은 배경·테두리·글씨가 다 들어간
         * **완성된 버튼 그림**이라, shadcn 버튼 안에 넣으면 버튼 안에 버튼이 그려진다.
         * 여기서는 배경 없는 `<button>`이 그림을 감싸기만 한다.
         *
         * 포커스 표시는 남긴다 — 키보드 사용자에게 이것이 유일한 단서다. 호버는
         * 투명도만 살짝 바꾼다. **그림 자체는 변형하지 않는다** (가이드라인 MUST).
         */
        className="mt-8 block rounded-[4px] transition-opacity outline-none hover:opacity-90 focus-visible:ring-[3px] focus-visible:ring-ring/50"
        onClick={() => {
          window.location.assign(GOOGLE_LOGIN_PATH)
        }}
      >
        <img
          src={GOOGLE_BUTTON_ASSET}
          /*
           * **버튼의 접근 가능한 이름이 여기서 나온다** (spec §3-1-5 MUST). 글씨가 그림
           * 안에 있어 이름이 없으면 스크린리더가 "버튼"이라고만 읽는다.
           *
           * 화면에 보이는 영어 문구를 함께 담는다 — 음성 입력 사용자는 **보이는 대로**
           * 말하므로, 한글만 두면 "Sign in with Google"이라고 말했을 때 맞지 않는다.
           */
          alt="구글 계정으로 로그인 (Sign in with Google)"
          /*
           * **폭·높이를 따로 주지 않는다** (가이드라인 MUST — 로고가 늘어나면 안 된다).
           * 원본 비율(180×40)이 그대로 쓰이고, 좁은 화면에서는 `max-w-full`이 가로세로를
           * 함께 줄인다. 높이를 고정하고 폭만 줄이면 찌그러진다.
           */
          className="block h-auto max-w-full"
        />
      </button>

      <p className="mt-6 text-sm text-muted-foreground">
        <Link to="/" className="transition-colors hover:text-foreground">
          ← 동아리 소개로 돌아가기
        </Link>
      </p>
    </section>
  )
}
