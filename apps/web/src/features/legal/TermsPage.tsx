import { Link } from 'react-router-dom'
import { CLUB } from '@/features/landing/content'
import { TERMS_SECTIONS, TERMS_UPDATED } from './termsContent'

/**
 * 이용약관. 개인정보처리방침과 같은 취급이다 — **가드를 붙이지 않는다.** 정지된 사람도
 * 자기가 무엇을 어겼다는 것인지 읽을 수 있어야 한다.
 *
 * 방침 페이지와 골격이 같지만 공용 레이아웃으로 묶지 않았다. 지금 둘뿐이라 묶어서 줄어드는
 * 줄 수보다 props로 갈라지는 자리가 더 늘어난다. 법적 문서가 셋째로 생기면 그때 묶는다.
 */
export function TermsPage() {
  return (
    <div className="dark min-h-screen bg-background text-foreground">
      {/*
       * **한 칸으로 읽는다.** 방침과 같은 이유다. 바깥이 1152px이고 본문만 `max-w-2xl`이면
       * 제목은 화면을 가로지르는데 문단은 왼쪽 절반에서 끝나, 줄이 화면 한참 앞에서
       * 꺾이는 것처럼 보인다.
       *
       * 폭을 문단 쪽에 맞춰 통째로 줄이고 가운데 세운다. 문단은 자기 칸을 끝까지 채우고
       * 줄을 어디서 바꿀지는 브라우저가 정한다. 문자열에는 줄바꿈을 넣지 않는다.
       */}
      <div className="mx-auto w-full max-w-3xl px-6 py-20">
        <Link
          to="/"
          className="text-sm text-muted-foreground transition-colors hover:text-foreground"
        >
          ← {CLUB.name}
        </Link>

        <h1 className="mt-8 text-3xl font-semibold tracking-tight">이용약관</h1>

        <dl className="mt-12 space-y-12">
          {TERMS_SECTIONS.map((section) => (
            <div key={section.title}>
              <dt className="text-lg font-semibold tracking-tight">
                {section.title}
              </dt>
              <dd className="mt-4 space-y-4">
                {section.paragraphs.map((paragraph) => (
                  <p
                    key={paragraph}
                    className="leading-8 text-muted-foreground"
                  >
                    {paragraph}
                  </p>
                ))}
              </dd>
            </div>
          ))}

          <div>
            <dt className="text-lg font-semibold tracking-tight">8. 시행일</dt>
            <dd className="mt-4">
              <p className="leading-8 text-muted-foreground">
                이 약관은 {TERMS_UPDATED.effectiveDate}부터 시행합니다.
              </p>
            </dd>
          </div>
        </dl>

        {/* 두 문서는 서로를 참조한다. 4항이 방침의 보관 항목을 가리키므로 길이 있어야 한다. */}
        <p className="mt-16 text-sm text-muted-foreground">
          <Link
            to="/privacy"
            className="transition-colors hover:text-foreground"
          >
            개인정보처리방침
          </Link>
        </p>
      </div>
    </div>
  )
}
