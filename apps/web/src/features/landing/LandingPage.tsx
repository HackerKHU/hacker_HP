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

/** 섹션 제목이 고정 헤더에 가리지 않도록 여백을 준다 (`scroll-mt-*`). */
const SECTION = 'mx-auto w-full max-w-[1152px] scroll-mt-20 px-6 py-20'

function Heading({ children }: { children: string }) {
  return <h2 className="text-3xl font-semibold tracking-tight">{children}</h2>
}

function Hero() {
  return (
    <section id="top" className={cn(SECTION, 'py-28')}>
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

function Activities() {
  return (
    <section id="activities" className={cn(SECTION, 'border-t border-border')}>
      <Heading>활동</Heading>
      {/*
       * 균일 격자로 두지 않고 세로 위치를 엇갈리게 배치한다. 항목이 적어도
       * 리듬이 생겨 빈 느낌이 덜하다.
       */}
      <ul className="mt-10 grid grid-cols-4 gap-6">
        {ACTIVITIES.map((activity, index) => (
          <li
            key={activity.title}
            className={index % 2 === 1 ? 'mt-12' : undefined}
          >
            <div className="aspect-[3/4] overflow-hidden rounded-lg border border-border bg-card">
              {activity.src ? (
                <img
                  src={activity.src}
                  alt={activity.alt}
                  className="size-full object-cover"
                />
              ) : (
                // 실물이 없다. 비어 있다는 것이 드러나야 채워 넣을 생각을 한다.
                <div className="flex size-full items-center justify-center text-xs text-muted-foreground">
                  사진 없음
                </div>
              )}
            </div>
            <p className="mt-4 font-medium text-foreground">{activity.title}</p>
            <p className="mt-1 text-sm text-muted-foreground">
              {activity.note}
            </p>
          </li>
        ))}
      </ul>
    </section>
  )
}

function Stats() {
  return (
    <section id="stats" className={cn(SECTION, 'border-t border-border')}>
      <Heading>함께한 기록</Heading>
      <dl className="mt-10 grid grid-cols-4 gap-6">
        {STATS.map((stat) => (
          <div key={stat.label}>
            {/* 단위를 숫자에 포함해 값 하나로 읽히게 한다. */}
            <dd className="text-6xl font-semibold tracking-tight text-foreground">
              {stat.value}
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
              shadcn 기본값이 text-sm(14px)이라 같은 페이지의 소개 본문·푸터(16px)보다
              작다. 우리가 정한 값이 아니라 복사본의 기본값이므로 **사용처에서 덮는다** —
              `accordion.tsx`를 고치면 이 컴포넌트를 쓰는 다른 화면까지 따라 바뀐다.
            */}
            <AccordionTrigger className="text-left text-base leading-7 font-semibold">
              {faq.question}
            </AccordionTrigger>
            <AccordionContent>
              {/* 답변은 소개 본문과 같은 16px / 줄높이 2.0으로 맞춘다. */}
              <p className="max-w-3xl text-base leading-8 text-muted-foreground">
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
