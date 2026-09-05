import { LegalBackLink } from './LegalBackLink'
import { PRIVACY_SECTIONS, PRIVACY_UPDATED } from './privacyContent'

/**
 * 개인정보처리방침. **가드를 붙이지 않는다** — 랜딩과 같은 공개 페이지다.
 *
 * 랜딩은 한 페이지 규칙(spec §2-1-10)이 있지만 이건 랜딩 섹션이 아니라 **별도 법적 문서**라
 * 자기 라우트를 가진다. 앵커로 넣지 않는다.
 */
export function PrivacyPage() {
  return (
    <div className="dark min-h-screen bg-background text-foreground">
      {/*
       * **한 칸으로 읽는다.** 예전에는 바깥이 1152px이고 본문만 `max-w-2xl`이라, 제목과
       * 돌아가기는 화면을 가로지르는데 문단은 왼쪽 절반에서 끝났다. 문단마다 줄이 화면
       * 한참 앞에서 꺾이는 것처럼 보이고 오른쪽은 계속 비어 있었다.
       *
       * 폭을 문단 쪽에 맞춰 통째로 줄이고 가운데 세운다. 이제 문단은 자기 칸을 끝까지
       * 채우고, 어디서 줄을 바꿀지는 브라우저가 정한다. 문자열에는 줄바꿈을 넣지 않고
       * 문단 사이는 여백(`space-y-*`)으로만 벌린다.
       */}
      <div className="mx-auto w-full max-w-3xl px-6 py-20">
        <LegalBackLink />

        <h1 className="mt-8 text-3xl font-semibold tracking-tight">
          개인정보처리방침
        </h1>

        <dl className="mt-12 space-y-12">
          {PRIVACY_SECTIONS.map((section) => (
            <div key={section.title}>
              <dt className="text-xl font-semibold tracking-tight">
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
            <dt className="text-xl font-semibold tracking-tight">8. 시행일</dt>
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
