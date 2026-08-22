import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from '@/components/ui/accordion'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { ACTIVITIES, CLUB, FAQS, SECTIONS, STATS, SUPPORT } from './content'
import { PublicHeader } from './PublicHeader'

/** 본문 컨테이너. 다른 섹션과 좌우 정렬을 맞추는 기준이다. */
const CONTAINER = 'mx-auto w-full max-w-[1152px] px-6'

/** 섹션 제목이 고정 헤더에 가리지 않도록 여백을 준다 (`scroll-mt-*`). */
const SECTION = `${CONTAINER} scroll-mt-24 py-28`

function Heading({ children }: { children: string }) {
  return <h2 className="text-4xl font-semibold tracking-tight">{children}</h2>
}

function Hero() {
  return (
    <section id="top" className={cn(SECTION, 'py-32')}>
      <p className="text-sm tracking-[0.2em] text-muted-foreground">
        {CLUB.eyebrow}
      </p>
      {/*
        두 줄은 의도된 줄바꿈이다. `index.css`가 제목에 `text-wrap: balance`를 걸어두는데,
        줄을 우리가 정했으므로 `text-wrap`(=wrap)으로 되돌려 간섭을 없앤다.
        각 줄을 블록으로 두면 개행 문자 없이 두 줄이 고정된다.
      */}
      <h1 className="mt-6 text-5xl leading-tight font-semibold tracking-tight text-wrap text-foreground">
        {CLUB.headline.map((line) => (
          <span key={line} className="block">
            {line}
          </span>
        ))}
      </h1>
      <p className="mt-6 text-sm text-muted-foreground">{CLUB.fullName}</p>
    </section>
  )
}

function About() {
  return (
    <section id="about" className={cn(SECTION, 'border-t border-border')}>
      <Heading>소개</Heading>
      <div className="mt-8 max-w-2xl space-y-4">
        {CLUB.about.map((paragraph) => (
          <p key={paragraph} className="leading-8 text-muted-foreground">
            {paragraph}
          </p>
        ))}
      </div>
    </section>
  )
}

/**
 * 트랙 한 벌. 항목 수가 적어 그대로 흘리면 화면 폭보다 짧아 빈 구간이 생긴다.
 * 두 번 반복해 한 벌을 채우고, 그 한 벌을 다시 두 벌로 이어붙여 `-50%`로 순환시킨다.
 * 항목이 늘거나 줄어도 이 구조는 그대로 둔다 — 한 벌이 화면 폭보다 길기만 하면 된다.
 * 각 벌에 고유 이름을 줘서 배열 인덱스를 key로 쓰지 않는다.
 */
const MARQUEE_PASSES = ['pass-1', 'pass-2', 'pass-3', 'pass-4'] as const

function Activities() {
  return (
    <section id="activities" className="scroll-mt-24 pb-28">
      {/*
       * 구분선과 제목은 다른 섹션과 **똑같은 컨테이너**에 둔다. `border-t`를 `section`에
       * 걸면 `max-w`가 없어 화면 끝까지 그어져 다른 섹션과 좌우가 어긋난다.
       * 아래 클래스 묶음은 `SECTION`의 컨테이너 부분과 같아야 한다.
       */}
      <div className={cn(CONTAINER, 'border-t border-border pt-28')}>
        <Heading>활동</Heading>
      </div>

      {/*
       * 카드 흐름만 화면 전체 폭을 쓴다. `100vw`를 쓰지 않고 **컨테이너 밖에 두는** 방식이라
       * 세로 스크롤바 폭만큼 넘쳐 가로 스크롤바가 생기는 일이 없다.
       *
       * 사진과 글이 한 카드다. 움직임 제어와 모션 민감도 대응은 `index.css`의
       * `.marquee-*` 규칙에 있다 — 마우스를 올리거나 포커스가 들어오면 멈추고,
       * 모션을 줄이도록 설정한 사용자에게는 가로 스크롤로 바뀐다.
       */}
      <div className="marquee-viewport mt-10">
        <ul className="marquee-track flex w-max items-start gap-6">
          {MARQUEE_PASSES.map((pass, passIndex) =>
            ACTIVITIES.map((activity) => (
              <li
                key={`${pass}-${activity.title}`}
                className="w-90 shrink-0"
                // 같은 카드가 네 번 나온다. 첫 벌만 읽히게 하고 나머지는 감춘다.
                aria-hidden={passIndex > 0}
              >
                <div className="aspect-[4/3] overflow-hidden rounded-lg border border-border bg-card">
                  {activity.src ? (
                    // 비율이 섞여 있다(대부분 4:3 가로, 사막만 3:4 세로). 카드 비율에
                    // 맞춰 채우되 기준점은 항목별로 잡는다.
                    <img
                      src={activity.src}
                      alt={activity.alt}
                      className={cn(
                        'size-full object-cover',
                        activity.crop ?? 'object-center',
                      )}
                    />
                  ) : (
                    /*
                     * 여덟 칸 중 하나만 사진이 없다. 평평한 단색 상자를 두면 사진들 사이에서
                     * 구멍처럼 읽히므로, 두 무채색 토큰 사이의 옅은 그러데이션으로 채워
                     * "빈 자리"가 아니라 "그림 없는 타일"로 보이게 한다.
                     * 안내 문구는 톤을 낮춰 카드 밖에서는 눈에 걸리지 않게 둔다.
                     */
                    <div className="flex size-full items-center justify-center bg-gradient-to-br from-card to-secondary text-xs text-muted-foreground/70">
                      사진 준비 중
                    </div>
                  )}
                </div>
                <p className="mt-4 text-lg font-medium text-foreground">
                  {activity.title}
                </p>
                {/*
                  설명 길이가 항목마다 달라 그대로 두면 카드 높이가 들쭉날쭉해진다.
                  가장 긴 설명(4줄) 높이를 최소값으로 잡아 카드 바닥을 맞춘다.
                */}
                <p className="mt-1 min-h-21 text-base leading-7 text-muted-foreground">
                  {activity.note}
                </p>
              </li>
            )),
          )}
        </ul>
      </div>
    </section>
  )
}

/** 카운트업 지속시간. 조정은 여기 한 곳. */
const COUNT_UP_MS = 1200

/** 빠르게 올라가다 천천히 멈춘다 (ease-out). */
function easeOut(progress: number): number {
  return 1 - (1 - progress) ** 3
}

/**
 * 숫자를 0에서 목표값까지 올린다. **화면에 들어올 때 한 번만** 실행한다 —
 * 페이지 로드 시점에 시작하면 스크롤해서 내려왔을 땐 이미 끝나 있고,
 * 볼 때마다 다시 움직이면 성가시다.
 *
 * 애니메이션 라이브러리를 쓰지 않는다. `requestAnimationFrame` 한 줄이면 된다.
 */
function CountUp({
  value,
  unit,
  minDigits = 1,
  approx = false,
}: {
  value: number
  unit: string
  minDigits?: number
  approx?: boolean
}) {
  const ref = useRef<HTMLSpanElement>(null)
  const [shown, setShown] = useState(0)

  useEffect(() => {
    const node = ref.current
    const reduceMotion =
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches

    // 모션을 줄이는 설정이면 움직이지 않고 최종값을 바로 보여준다.
    // IntersectionObserver가 없는 환경(jsdom 등)에서도 값이 0에 멈추지 않게 한다.
    if (!node || reduceMotion || typeof IntersectionObserver === 'undefined') {
      setShown(value)
      return
    }

    let frame = 0
    let startedAt = 0

    const observer = new IntersectionObserver((entries) => {
      if (!entries.some((entry) => entry.isIntersecting)) return
      observer.disconnect() // 한 번만 실행한다

      const step = (now: number) => {
        if (startedAt === 0) startedAt = now
        const progress = Math.min(1, (now - startedAt) / COUNT_UP_MS)
        setShown(Math.round(value * easeOut(progress)))
        if (progress < 1) frame = requestAnimationFrame(step)
      }
      frame = requestAnimationFrame(step)
    })
    observer.observe(node)

    return () => {
      observer.disconnect()
      cancelAnimationFrame(frame)
    }
  }, [value])

  /*
   * `+`는 카운트업이 끝난 뒤가 아니라 처음부터 붙인다.
   *
   * 중간에 붙이면 완료되는 순간 글자 하나만큼 폭이 튄다 — 바로 아래 `tabular-nums`
   * 주석이 막으려는 그 덜컹거림이다. 도는 동안 `340+`가 보여도 거짓이 아니다.
   * 최종값이 근사 하한이므로 어느 프레임에서도 "이만큼 이상"은 참이다.
   */
  return (
    <span ref={ref}>
      {String(shown).padStart(minDigits, '0')}
      {approx && '+'}
      {unit}
    </span>
  )
}

function Stats() {
  return (
    <section id="stats" className={cn(SECTION, 'border-t border-border')}>
      <Heading>함께한 기록</Heading>
      {/* 모바일은 2×2다. 4열 고정이면 390px에서 숫자가 잘린다 (#174). */}
      <dl className="mt-10 grid grid-cols-2 gap-x-6 gap-y-10 md:grid-cols-4">
        {STATS.map((stat) => (
          <div key={stat.label}>
            {/*
              `tabular-nums`가 없으면 숫자가 바뀔 때마다 글자 폭이 달라져 카운트업 내내
              레이아웃이 덜컹거린다. 이 애니메이션에서 제일 티나는 결함이다.
            */}
            {/*
              모바일은 4xl까지 내린다. 5xl이면 가장 긴 값("2000+명" ≈ 186px)이 390px
              화면의 열 폭(159px)을 넘어 두 줄로 갈라지고, 카운트업 중 행 높이가 튄다.
            */}
            <dd className="text-4xl font-semibold tracking-tight tabular-nums text-foreground sm:text-5xl md:text-6xl">
              <CountUp
                value={stat.value}
                unit={stat.unit}
                minDigits={stat.minDigits}
                approx={stat.approx}
              />
            </dd>
            <dt className="mt-3 text-sm text-muted-foreground">{stat.label}</dt>
          </div>
        ))}
      </dl>
    </section>
  )
}

function Faq() {
  return (
    <section id="faq" className={cn(SECTION, 'border-t border-border')}>
      <Heading>자주 묻는 질문</Heading>
      {/* 아코디언의 키보드 조작과 포커스는 Radix가 처리한다 (3-3 결정 10). */}
      {/*
       * 아코디언 자체에는 폭 상한을 두지 않는다. 질문 행이 다른 섹션과 같은 폭을 써야
       * 좌우 정렬이 맞는다. 대신 답변 문단만 읽기 좋은 폭으로 묶는다 —
       * 1104px짜리 한 줄 문단은 눈이 줄을 놓친다.
       */}
      <Accordion type="single" collapsible className="mt-8">
        {FAQS.map((faq) => (
          <AccordionItem key={faq.question} value={faq.question}>
            {/*
              **질문은 본문이 아니라 소제목이다.** 레퍼런스 실측에서도 20px였다(dnd·nexters).
              답변만 본문 16px로 두면 질문과 답변 사이에 위계가 생긴다.

              shadcn 기본값(`text-sm`)은 우리가 정한 값이 아니라 복사본의 기본값이므로
              **사용처에서 덮는다** — `accordion.tsx`를 고치면 이 컴포넌트를 쓰는 다른
              화면까지 따라 바뀐다.
            */}
            <AccordionTrigger className="py-5 text-left text-xl leading-8 font-semibold">
              {faq.question}
            </AccordionTrigger>
            <AccordionContent>
              {/*
                답변에 폭 상한을 두지 않는다. 768px로 묶어 두면 질문 행(1104px)보다 훨씬
                일찍 줄이 바뀌어, 오른쪽이 비어 있는데도 문장이 끊긴 것처럼 보인다.
                1104px는 16px 한글 기준 한 줄 65~70자로 읽기 좋은 범위 안이다.
              */}
              <p className="text-base leading-8 text-muted-foreground">
                {faq.answer}
              </p>
            </AccordionContent>
          </AccordionItem>
        ))}
      </Accordion>
    </section>
  )
}

function Support() {
  // 문의는 mailto로 받는다. 별도 페이지나 폼을 만들면 백엔드가 딸려온다.
  const mailto = `mailto:${SUPPORT.email}?subject=${encodeURIComponent(SUPPORT.subject)}`

  return (
    <section id="support" className={cn(SECTION, 'border-t border-border')}>
      <Heading>후원</Heading>
      <p className="mt-8 max-w-2xl leading-8 text-muted-foreground">
        {SUPPORT.description}
      </p>
      <Button asChild size="lg" className="mt-8">
        <a href={mailto}>후원 문의하기</a>
      </Button>
    </section>
  )
}

function Footer() {
  return (
    <footer className="border-t border-border">
      {/* 모바일은 세로로 쌓는다. 가로 고정이면 주소와 링크 일곱 개가 찌그러진다 (#174). */}
      <div className="mx-auto flex w-full max-w-[1152px] flex-col gap-8 px-6 py-10 text-sm text-muted-foreground sm:flex-row sm:items-start sm:justify-between">
        <address className="not-italic">
          <span className="block text-foreground">{CLUB.fullName}</span>
          <span className="mt-2 block">
            ({CLUB.address.postalCode}) {CLUB.address.road}
          </span>
          <span className="block">{CLUB.address.detail}</span>
        </address>
        <nav
          className="flex flex-wrap gap-x-4 gap-y-2"
          aria-label="섹션 바로가기"
        >
          {SECTIONS.map((section) => (
            <a
              key={section.id}
              href={`#${section.id}`}
              className="transition-colors hover:text-foreground"
            >
              {section.label}
            </a>
          ))}
          <a
            href={CLUB.instagram}
            target="_blank"
            rel="noreferrer"
            className="transition-colors hover:text-foreground"
          >
            인스타그램
          </a>
          <Link
            to="/privacy"
            className="transition-colors hover:text-foreground"
          >
            개인정보처리방침
          </Link>
        </nav>
      </div>
    </footer>
  )
}

/**
 * `rgb(r, g, b)` → `#rrggbb`. Safari가 `theme-color`에서 함수 표기를 무시하는 경우가 있어
 * 16진수로 바꿔 넣는다.
 *
 * **정수 세 채널인 `rgb()`/`rgba()`만 바꾼다.** 계산된 색은 소수 채널을 돌려줄 수 있고
 * (`rgb(146.06, 107.46, 131.2)`), `oklch()` 같은 다른 표기도 온다 — 숫자만 긁어 모으면
 * 엉뚱한 색이 된다. 확신이 없으면 원본을 그대로 넘긴다. 브라우저가 못 읽으면 `theme-color`가
 * 무시될 뿐이지만, 틀린 색을 넣으면 크롬이 엉뚱하게 칠해진다.
 */
function toHex(color: string): string {
  const rgb = color.match(/^rgba?\((\d+),\s*(\d+),\s*(\d+)(?:[,)])/)
  if (!rgb) return color
  return `#${rgb
    .slice(1, 4)
    .map((part) => Number(part).toString(16).padStart(2, '0'))
    .join('')}`
}

/**
 * 공개 랜딩. **가드를 붙이지 않는다** — 비로그인·PENDING·ACTIVE·SUSPENDED 어느
 * 상태에서도 그대로 열려야 한다 (spec 5-TESTING T-57~T-61).
 *
 * **콘텐츠를 가져오는 API를 호출하지 않는다** (spec 3-3 결정 8, T-60). 콘텐츠는 전부
 * `content.ts`에 있어 백엔드가 죽어도 렌더된다. 랜딩을 열 때 나가는 `GET /auth/me`는
 * 앱 셸(`SessionProvider`)이 경로와 무관하게 하는 세션 확인이지 이 화면의 요청이 아니다.
 *
 * 랜딩만 다크다. 최상위를 `.dark`로 감싸 #69에서 세운 토큰을 뒤집어 쓴다 —
 * 로그인 이후 화면은 라이트 그대로다.
 */
export function LandingPage() {
  /*
   * 다크를 브라우저 크롬까지 확장한다 (#192).
   *
   * **`.dark`를 `html`에 건다.** 처음에는 `html`의 배경만 인라인으로 칠했는데 흰 띠가
   * 그대로였다 — `body`가 `@apply bg-background`로 `:root`(라이트)를 읽어 **흰색으로
   * 덮고 있었기 때문**이다. iOS Safari가 상태바·툴바 영역에 쓰는 색이 그 body 배경이다.
   * `html`에 클래스를 걸면 토큰이 뒤집혀 body까지 검게 따라오므로, 색을 손으로 칠하는
   * 대신 이미 있는 팔레트가 그대로 적용된다.
   *
   * 안쪽 div의 `.dark`는 남겨 둔다. 이 effect는 첫 페인트 뒤에 도는데, 그때까지 본문이
   * 라이트로 한 프레임 번쩍이면 안 된다.
   *
   * 전역 CSS로 못 박지 않는 이유 — 로그인 이후 화면은 라이트라서, 이 화면에 있는 동안만
   * 유효해야 한다.
   */
  useEffect(() => {
    const html = document.documentElement
    const hadDark = html.classList.contains('dark')
    html.classList.add('dark')

    /*
     * `theme-color`도 함께 준다. 색은 뒤집힌 팔레트에서 읽되 **16진수로 바꿔 넣는다** —
     * Safari가 `rgb()` 표기를 그대로 받아주지 않는 경우가 있다.
     */
    const meta = document.createElement('meta')
    meta.name = 'theme-color'
    meta.content = toHex(getComputedStyle(document.body).backgroundColor)
    const existing = document.querySelector<HTMLMetaElement>(
      'meta[name="theme-color"]',
    )
    const previous = existing?.content
    if (existing) existing.content = meta.content
    else document.head.appendChild(meta)

    return () => {
      if (!hadDark) html.classList.remove('dark')
      if (existing && previous !== undefined) existing.content = previous
      else meta.remove()
    }
  }, [])

  return (
    <div className="dark min-h-screen bg-background text-foreground">
      <PublicHeader />
      <main>
        <Hero />
        <About />
        <Activities />
        <Stats />
        <Faq />
        <Support />
      </main>
      <Footer />
    </div>
  )
}
