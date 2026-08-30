import { Link } from 'react-router-dom'
import { CLUB } from '@/features/landing/content'

/**
 * 사이트 푸터. **랜딩과 로그인 이후 화면이 이 하나를 같이 그린다** (#304).
 *
 * 예전에는 둘이 각자 markup을 들고 있었고 담긴 것도 달랐다 — 랜딩은 주소와 링크 일곱 개,
 * 내부는 링크 세 개짜리 한 줄이었다. **한쪽만 고쳐지는 자리라, 실제로 어긋난 채로 오래
 * 갔다.** 모양을 맞추는 것과 목록을 맞추는 것이 같은 일이므로 컴포넌트도 하나다.
 *
 * 기준은 랜딩 쪽이다. 동아리 이름·주소는 **어느 화면에서든 있어야 하는 정보**이고,
 * 그것이 내부 화면에만 없을 이유가 없다.
 *
 * 남는 링크는 셋뿐이다 — 인스타그램과 법적 문서 둘. 섹션 앵커 다섯 개는 헤더가 이미
 * 그리고 있어 뺐고(#304), 내부 푸터의 `동아리 소개`는 헤더 로고와 목적지가 같아서 뺐다.
 */
export function SiteFooter() {
  return (
    <footer className="border-t border-border">
      {/* 모바일은 세로로 쌓는다. 가로 고정이면 주소와 링크가 찌그러진다 (#174). */}
      <div className="mx-auto flex w-full max-w-[1152px] flex-col gap-8 px-6 py-10 text-sm text-muted-foreground sm:flex-row sm:items-start sm:justify-between">
        <address className="not-italic">
          <span className="block text-foreground">{CLUB.fullName}</span>
          <span className="mt-2 block">
            ({CLUB.address.postalCode}) {CLUB.address.road}
          </span>
          <span className="block">{CLUB.address.detail}</span>
        </address>

        {/*
         * **이름이 `섹션 바로가기`가 아니다.** 그 이름으로 섹션 앵커 다섯을 담고 있었는데,
         * 항목이 사라진 뒤에도 이름만 남으면 스크린리더에게 거짓말이 된다.
         */}
        <nav className="flex flex-wrap gap-x-4 gap-y-2" aria-label="푸터 링크">
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
          <Link to="/terms" className="transition-colors hover:text-foreground">
            이용약관
          </Link>
        </nav>
      </div>
    </footer>
  )
}
