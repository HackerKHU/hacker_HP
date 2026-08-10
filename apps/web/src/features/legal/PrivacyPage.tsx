import { Link } from 'react-router-dom'
import { CLUB } from '@/features/landing/content'
import { PRIVACY_SECTIONS, PRIVACY_UPDATED } from './privacyContent'

/**
 * 개인정보처리방침. **가드를 붙이지 않는다** — 랜딩과 같은 공개 페이지다.
 *
 * 랜딩은 한 페이지 규칙(spec §2-1-8)이 있지만 이건 랜딩 섹션이 아니라 **별도 법적 문서**라
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

        {/*
         * 방문자와 개발자 모두가 미완성임을 알아야 한다. 법적 문서라 빠진 항목이 있는 채로
         * 공개되면 없느니만 못하다.
         */}
        <p
          role="note"
          className="mt-6 max-w-2xl rounded-lg border border-border bg-card p-4 text-sm leading-7 text-muted-foreground"
        >
          <strong className="text-foreground">
            이 문서는 초안이며 검토가 필요합니다.
          </strong>{' '}
          아직 정해지지 않아 비어 있는 항목이 있습니다. 아래에 “확인 후 채워야
          하는 항목”으로 표시해 두었으며, 정식 시행 전에 모두 채워야 합니다.
        </p>

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

                {section.todo && (
                  <p className="rounded-md border border-border border-dashed p-3 text-sm leading-7 text-muted-foreground">
                    확인 후 채워야 하는 항목 — {section.todo}
                  </p>
                )}
              </dd>
            </div>
          ))}

          <div>
            <dt className="text-lg font-semibold tracking-tight">8. 시행일</dt>
            <dd className="mt-4">
              {PRIVACY_UPDATED.effectiveDate ? (
                <p className="leading-8 text-muted-foreground">
                  이 방침은 {PRIVACY_UPDATED.effectiveDate}부터 시행합니다.
                </p>
              ) : (
                <p className="rounded-md border border-border border-dashed p-3 text-sm leading-7 text-muted-foreground">
                  확인 후 채워야 하는 항목 — 시행일을 정해서 넣어야 합니다.
                </p>
              )}
            </dd>
          </div>
        </dl>
      </div>
    </div>
  )
}
