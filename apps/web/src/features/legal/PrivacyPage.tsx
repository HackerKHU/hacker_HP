import { Link } from 'react-router-dom'
import { CLUB } from '@/features/landing/content'
import { PRIVACY_SECTIONS, PRIVACY_UPDATED } from './privacyContent'

/**
 * 개인정보처리방침. **가드를 붙이지 않는다** — 랜딩과 같은 공개 페이지다.
 *
 * 랜딩은 한 페이지 규칙(spec §2-1-9)이 있지만 이건 랜딩 섹션이 아니라 **별도 법적 문서**라
 * 자기 라우트를 가진다. 앵커로 넣지 않는다.
 */
export function PrivacyPage() {
  return (
    <div className="dark min-h-screen bg-background text-foreground">
      <div className="mx-auto w-full max-w-[1152px] px-6 py-20">
        <Link
          to="/"
          className="text-sm text-muted-foreground transition-colors hover:text-foreground"
        >
          ← {CLUB.name}
        </Link>

        <h1 className="mt-8 text-3xl font-semibold tracking-tight">
          개인정보처리방침
        </h1>

        <dl className="mt-12 max-w-2xl space-y-12">
          {PRIVACY_SECTIONS.map((section) => (
            <div key={section.title}>
              <dt className="text-lg font-semibold tracking-tight">
                {section.title}
              </dt>
              <dd className="mt-4 space-y-4">
                {section.paragraphs?.map((paragraph) => (
                  <p
                    key={paragraph}
                    className="leading-8 text-muted-foreground"
                  >
                    {paragraph}
                  </p>
                ))}

                {section.items && (
                  <ul className="list-disc space-y-2 pl-5 leading-8 text-muted-foreground">
                    {section.items.map((item) => (
                      <li key={item}>{item}</li>
                    ))}
                  </ul>
                )}
              </dd>
            </div>
          ))}

          <div>
            <dt className="text-lg font-semibold tracking-tight">8. 시행일</dt>
            <dd className="mt-4">
              <p className="leading-8 text-muted-foreground">
                이 방침은 {PRIVACY_UPDATED.effectiveDate}부터 시행합니다.
              </p>
            </dd>
          </div>
        </dl>
      </div>
    </div>
  )
}
