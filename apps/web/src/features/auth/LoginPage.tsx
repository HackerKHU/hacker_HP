import { Link, useSearchParams } from 'react-router-dom'
import { GOOGLE_LOGIN_PATH } from '@/api/auth'
import { useSession } from '@/auth/session'
import { Button } from '@/components/ui/button'
import { CLUB } from '@/features/landing/content'
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

/**
 * 허용 도메인. 원본은 서버 설정(`app.auth.allowed-email-domain`, spec §3-1-4)이고 화면은
 * 안내만 한다 — 검증은 서버가 한다. 값이 바뀌면 이 한 줄을 고친다.
 */
const ALLOWED_EMAIL_DOMAIN = 'khu.ac.kr'

/** 표기용 연도. 렌더에서 `new Date()`를 부르면 테스트가 실행 시점에 따라 달라진다. */
const COPYRIGHT_YEAR = 2026

/**
 * 왼쪽 패널 — **로고 자리다. 글자를 두지 않는다.**
 *
 * 랜딩과 같은 방식으로 `.dark`를 씌워 토큰을 뒤집는다 — 새 색을 만들지 않는다.
 *
 * 자리는 **정사각형**이다. 이 사이트가 마크를 필요로 하는 자리가 둘인데 요구 비율이
 * 서로 다르다 — 헤더(`PublicHeader`)는 가로로 긴 워드마크, 파비콘·앱 아이콘은
 * 정사각형이다. 정사각형 자리는 둘 다 받는다(가로형 마크는 폭을 채우고 가운데 정렬하면
 * 된다). 반대로 가로로 긴 자리는 정사각형 마크를 넣으면 좌우가 남는다.
 *
 * 2026 로고 공모전 1등 작품이 확정되어 자리표시자를 걷었다. 자산은 `brand/`에서 파생한
 * 것을 쓴다 (`brand/README.md`) — **원본만이 정본이고 파생본을 직접 손보지 않는다.**
 *
 * 이 패널은 배경이 검정(`.dark`)이라 **잉크가 흰색이고 배경이 투명한** 마크를 쓴다.
 * `-on-white`/`-on-black`은 배경이 채워진 버전이라 여기서는 네모가 비쳐 보인다.
 */
function LogoPanel() {
  return (
    <div className="dark hidden w-[22rem] shrink-0 items-center justify-center rounded-xl bg-background p-10 text-foreground lg:flex">
      {/*
        장식이 아니라 이 화면이 어디인지 말하는 요소라 `alt`를 비우지 않는다. 다만 옆
        카드에 "로그인" 제목이 이미 있으므로 동아리 이름까지만 읽히면 충분하다.
      */}
      <img
        src="/brand/mark-white-512.png"
        alt="해커"
        width={512}
        height={512}
        className="aspect-square w-full object-contain"
      />
    </div>
  )
}

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
    /*
     * 두 장 배치 — 왼쪽은 로고, 오른쪽은 할 일.
     *
     * 좁은 화면에서는 **왼쪽을 숨긴다.** 세로로 쌓으면 정사각형 로고 패널이 화면 폭만큼
     * 높아져(420px 폭이면 340px 넘는 검정 덩어리다) 버튼이 접힌 아래로 내려간다 —
     * 로그인하러 온 사람이 스크롤을 해야 한다.
     *
     * 로고가 들어온 뒤에도 이 판단은 유지된다. 좁은 화면에서 마크가 사라지는 것이
     * 문제인데, 그건 패널을 되살리는 대신 **오른쪽 카드 맨 위 자리로 해결한다** — 아래
     * `lg:hidden` 마크가 그 자리다. 같은 심볼을 폭에 맞는 크기로 보이는 것이지 정보가
     * 없어지는 것이 아니다.
     */
    <div className="flex min-h-screen justify-center bg-muted p-6">
      {/*
       * 세로 가운데는 **`items-center`가 아니라 자식의 `my-auto`로 맞춘다.**
       *
       * `items-center`는 자식이 화면보다 커지는 순간 위쪽을 컨테이너 밖으로 밀어내는데,
       * 그 영역은 `scrollHeight`에 잡히지 않아 **스크롤로 닿을 수 없다.** 창 높이가
       * 낮거나(노트북 가로 모드) 브라우저 글꼴을 키우면 제목이 잘린 채 복구가 안 된다.
       *
       * `margin: auto`는 남는 공간이 있을 때만 나눠 갖고 없으면 0이 된다 — 여유가 있으면
       * 가운데, 모자라면 위에서 시작해 아래로 흐른다. 같은 가운데 정렬인데 잘리지 않는다.
       */}
      <div className="my-auto flex w-full max-w-4xl gap-6">
        <LogoPanel />

        <section className="flex-1 rounded-xl border border-border bg-background p-8 shadow-sm sm:p-10">
          {/*
           * **넓은 화면에서는 제목부터 시작한다.** 왼쪽 패널이 이 화면의 정체를
           * 말하므로 서비스명을 여기 또 적으면 한 화면에서 같은 말을 두 번 한다.
           *
           * 다만 `lg` 미만에서는 그 왼쪽 패널이 통째로 사라지므로 여기가 **유일한
           * 정체 표시**가 된다. 그래서 좁은 화면에서만 나오게 하고, 제목이 아니라
           * eyebrow 꼴로 둔다 — 어느 폭에서든 첫 제목은 `로그인`이다.
           *
           * 로고가 확정되어 마크로 바꿨다. **여기는 라이트 배경**이라 잉크가 검정인
           * 파일을 쓴다 — 왼쪽 패널(다크)과 파일이 다른 이유다.
           */}
          <img
            src="/brand/mark-black-256.png"
            alt={CLUB.fullName}
            width={256}
            height={256}
            className="size-10 object-contain lg:hidden"
          />

          {/*
           * **`tracking-tight`를 쓰지 않는다.** `로그인`은 세 글자의 아래 가로획
           * (`로`의 ㅗ, `그`의 ㅡ, `인`의 ㄴ 밑변)이 같은 높이에 놓인다. 자간을 좁히면
           * 그 사이 틈이 메워져 **밑줄 하나로 읽히고 링크처럼 보인다.** 32px·600으로
           * 키운 뒤 더 뚜렷해졌다. 기본 자간이면 틈이 남아 세 글자로 읽힌다.
           */}
          <h1 className="mt-6 text-[32px] leading-tight font-semibold lg:mt-0">
            로그인
          </h1>
          <p className="mt-3 text-base leading-7 text-muted-foreground">
            구글 계정으로 로그인합니다. 처음이라면 이 버튼으로 가입이 함께
            진행됩니다.
          </p>

          {message && (
            <p
              role="alert"
              className="mt-6 rounded-md border border-border px-4 py-3 text-base leading-7 text-foreground"
            >
              {message}
            </p>
          )}

          {/*
           * 허용 도메인을 **미리** 알린다. 지금은 눌러본 뒤 `?error=domain`을 받아야
           * 아는데, 그때는 이미 구글 화면까지 갔다 온 뒤다 (spec §3-1-4 MUST).
           */}
          {/*
           * `?error=domain`일 때는 숨긴다. 그 안내가 이미 같은 말을 하고 있어서,
           * 둘을 함께 두면 화면이 같은 문장을 두 번 반복한다.
           */}
          {code !== 'domain' && (
            <div className="mt-6 rounded-md bg-muted px-4 py-3">
              <p className="text-base font-medium text-foreground">
                @{ALLOWED_EMAIL_DOMAIN} 계정만 가입할 수 있습니다
              </p>
              {/* 딸린 설명은 한 단 아래(14)로 둔다 — 상자 안에서도 위계가 있어야 한다. */}
              <p className="mt-1 text-sm leading-6 text-muted-foreground">
                개인 구글 계정으로는 로그인되지 않습니다. 학교 계정으로 로그인해
                주세요.
              </p>
            </div>
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
           * 여백은 공식 배포본(180×40)에서 잰 값이다 — 좌우 12px, 로고와 글씨 사이 14px.
           *
           * 폭만 채운다. 로고 비율은 그대로다 (가이드라인 MUST).
           *
           * **크기는 버튼 전체를 한 배율로 키운 것이고, 그 배율은 `em`이 지킨다**
           * (spec §3-1-5). 치수를 px로 적으면 항목마다 반올림이 갈려 "비율 유지"가
           * 조용히 깨진다 — 실제로 그렇게 어긋났었다(로고 ×1.15, 간격 ×1.143).
           *
           * 규격 글씨는 14px이므로 각 치수를 14로 나눈 `em`으로 적는다. 그러면 배율이
           * 코드에 **글씨 크기 한 곳에만** 나오고 나머지는 저절로 따라간다. 지금
           * `text-base`(16px)라 배율은 `16/14`다.
           *
           * | 치수 | 규격 | em (÷14) | 지금 (×16) |
           * |---|---|---|---|
           * | 글씨 | 14 | — | 16 |
           * | 줄간격 | 20 | 1.4286 | 22.86 |
           * | 높이 | 40 | 2.8571 | 45.71 |
           * | 로고 | 20 | 1.4286 | 22.86 |
           * | 좌우 여백 | 12 | 0.8571 | 13.71 |
           * | 로고-글씨 사이 | 14 | 1 | 16 |
           *
           * 좌우 여백을 `has-[>svg]:` 로도 적는다. shadcn `Button`이 아이콘이 든 버튼에
           * `has-[>svg]:px-3`을 걸어두는데, 그냥 `px-*`로만 덮으면 **변이 선택자가
           * 특이도로 이겨 12px이 남는다** — 실제로 그렇게 어긋나 있었다(클래스는 14px인데
           * 렌더는 12px). 브라우저에서 재보지 않으면 드러나지 않는다.
           */}
          <Button
            type="button"
            variant="outline"
            className="mt-6 h-[2.8571em] w-full gap-[1em] border-[#747775] px-[0.8571em] text-base leading-[1.4286] text-[#1f1f1f] has-[>svg]:px-[0.8571em]"
            onClick={() => {
              window.location.assign(GOOGLE_LOGIN_PATH)
            }}
          >
            <GoogleLogo className="size-[1.4286em]" />
            {GOOGLE_BUTTON_LABEL}
          </Button>

          <p className="mt-6 text-center text-sm text-muted-foreground">
            <Link to="/" className="transition-colors hover:text-foreground">
              ← 동아리 소개로 돌아가기
            </Link>
          </p>

          <div className="mt-8 flex items-center justify-between border-t border-border pt-5 text-xs text-muted-foreground">
            <span>
              © {COPYRIGHT_YEAR} {CLUB.name}
            </span>
            <Link
              to="/privacy"
              className="transition-colors hover:text-foreground"
            >
              개인정보처리방침
            </Link>
          </div>
        </section>
      </div>
    </div>
  )
}
