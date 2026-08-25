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
      <div className="mx-auto w-full max-w-[1152px] px-6 py-20">
        <Link
          to="/"
          className="text-sm text-muted-foreground transition-colors hover:text-foreground"
        >
          ← {CLUB.name}
        </Link>

        <h1 className="mt-8 text-3xl font-semibold tracking-tight">이용약관</h1>

        <dl className="mt-12 max-w-2xl space-y-12">
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
