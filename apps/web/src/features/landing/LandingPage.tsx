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
import {
  ACTIVITIES,
  CLUB,
  FAQS,
  PHOTOS,
  SECTIONS,
  STATS,
  SUPPORT,
} from './content'
import { PublicHeader } from './PublicHeader'

/** 섹션 제목이 고정 헤더에 가리지 않도록 여백을 준다 (`scroll-mt-*`). */
const SECTION = 'mx-auto w-full max-w-[1152px] scroll-mt-24 px-6 py-28'

function Heading({ children }: { children: string }) {
  return <h2 className="text-4xl font-semibold tracking-tight">{children}</h2>
}

function Hero() {
  return (
    <section id="top" className={cn(SECTION, 'py-32')}>
      <p className="text-sm tracking-[0.2em] text-muted-foreground">
        {CLUB.name}
      </p>
      <p className="mt-3 text-sm text-muted-foreground">{CLUB.fullName}</p>
      <h1 className="mt-6 max-w-3xl text-5xl leading-tight font-semibold tracking-tight text-foreground">
        {CLUB.tagline}
      </h1>
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
 * 사진 트랙 한 벌. 장수가 적어 그대로 흘리면 화면 폭보다 짧아 빈 구간이 생긴다.
 * 두 번 반복해 한 벌을 채우고, 그 한 벌을 다시 두 벌로 이어붙여 `-50%`로 순환시킨다.
 * 장수가 늘거나 줄어도 이 구조는 그대로 둔다 — 한 벌이 화면 폭보다 길기만 하면 된다.
 * 각 벌에 고유 이름을 줘서 배열 인덱스를 key로 쓰지 않는다.
 */
const MARQUEE_PASSES = ['pass-1', 'pass-2', 'pass-3', 'pass-4'] as const

function Activities() {
  return (
    <section id="activities" className={cn(SECTION, 'border-t border-border')}>
      <Heading>활동</Heading>

      {/*
       * 항목 격자가 사진보다 위에 온다. 제목이 "활동"이니 읽는 사람이 먼저 알고 싶은 것은
       * 무엇을 하는가이고, 사진은 분위기를 더하는 쪽이다. 지금은 사진이 전부 자리표시자라
       * 제목 바로 아래에 빈 액자가 흐르면 화면이 깨진 것처럼 보이기도 한다.
       */}
      <ul className="mt-10 grid grid-cols-4 gap-4">
        {ACTIVITIES.map((activity) => (
          <li
            key={activity.title}
            className="rounded-lg border border-border bg-card p-5"
          >
            <p className="font-medium text-foreground">{activity.title}</p>
            <p className="mt-2 text-sm leading-6 text-muted-foreground">
              {activity.note}
            </p>
          </li>
        ))}
      </ul>

      {/*
       * 사진은 가로로 천천히 흐른다. 움직임 제어와 모션 민감도 대응은 `index.css`의
       * `.marquee-*` 규칙에 있다 — 마우스를 올리거나 포커스가 들어오면 멈추고,
       * 모션을 줄이도록 설정한 사용자에게는 가로 스크롤로 바뀐다.
       */}
      <div className="marquee-viewport mt-12">
        <ul className="marquee-track flex w-max items-start gap-6">
          {MARQUEE_PASSES.map((pass, passIndex) =>
            PHOTOS.map((photo, photoIndex) => (
              <li
                key={`${pass}-${photoIndex + 1}`}
                // 같은 사진이 네 번 나온다. 첫 벌만 읽히게 하고 나머지는 감춘다.
                aria-hidden={passIndex > 0}
              >
                <div className="aspect-[3/4] w-64 shrink-0 overflow-hidden rounded-lg border border-border bg-card">
                  {photo.src ? (
                    <img
                      src={photo.src}
                      alt={photo.alt}
                      className="size-full object-cover"
                    />
                  ) : (
                    // 실물이 없다. 비어 있다는 것이 드러나야 채워 넣을 생각을 한다.
                    <div className="flex size-full items-center justify-center text-xs text-muted-foreground">
                      사진 없음
                    </div>
                  )}
                </div>
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
}: {
  value: number
  unit: string
  minDigits?: number
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

  return (
    <span ref={ref}>
      {String(shown).padStart(minDigits, '0')}
      {unit}
    </span>
  )
}

function Stats() {
  return (
    <section id="stats" className={cn(SECTION, 'border-t border-border')}>
      <Heading>함께한 기록</Heading>
      <dl className="mt-10 grid grid-cols-4 gap-6">
        {STATS.map((stat) => (
          <div key={stat.label}>
            {/*
              `tabular-nums`가 없으면 숫자가 바뀔 때마다 글자 폭이 달라져 카운트업 내내
              레이아웃이 덜컹거린다. 이 애니메이션에서 제일 티나는 결함이다.
            */}
            <dd className="text-6xl font-semibold tracking-tight tabular-nums text-foreground">
              <CountUp
                value={stat.value}
                unit={stat.unit}
                minDigits={stat.minDigits}
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
      <div className="mx-auto flex w-full max-w-[1152px] items-start justify-between gap-8 px-6 py-10 text-sm text-muted-foreground">
        <address className="not-italic">
          <span className="block text-foreground">{CLUB.fullName}</span>
          <span className="mt-2 block">
            ({CLUB.address.postalCode}) {CLUB.address.road}
          </span>
          <span className="block">{CLUB.address.detail}</span>
        </address>
        <nav className="flex gap-4" aria-label="섹션 바로가기">
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
 * 공개 랜딩. **가드를 붙이지 않는다** — 비로그인·PENDING·ACTIVE·SUSPENDED 어느
 * 상태에서도 그대로 열려야 한다 (spec 5-TESTING T-21~T-25).
 *
 * **API를 호출하지 않는다** (spec 3-3 결정 8, T-24). 콘텐츠는 전부 `content.ts`에 있다.
 *
 * 랜딩만 다크다. 최상위를 `.dark`로 감싸 #69에서 세운 토큰을 뒤집어 쓴다 —
 * 로그인 이후 화면은 라이트 그대로다.
 */
export function LandingPage() {
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
