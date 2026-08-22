# 브랜드 자산

2026 로고 공모전 1등 작품 하나(`source/hacker-logo-master.png`, 1254×1254 흰 배경 PNG)에서
`_generate.py`로 파생한 배포용 이미지 모음이다. **원본만이 정본이다** — 파생본을 직접 손보지 말고
원본을 바꾼 뒤 `python3 brand/_generate.py`로 전부 다시 만든다.

## 무엇을 쓰면 되나

| 상황 | 파일 |
|---|---|
| 웹 헤더·배너 (가로로 긴 자리) | `horizontal/lockup-horizontal-black-*.png` |
| 다크 배경 위 | `*-white-*.png` (잉크가 흰색, 배경 투명) |
| 포스터·굿즈·세로 자리 | `vertical/lockup-vertical-black-*.png` |
| 심볼만 (프로필, 워터마크) | `mark/mark-*.png` |
| 글자만 | `wordmark/wordmark-*.png` |
| 투명을 못 쓰는 곳 (인쇄, 카톡 프로필, 한글·워드 문서) | `*-on-white-*.png`, `*-on-black-*.png` |
| 파비콘 | `icon/favicon.ico` (16·32·48 포함) |
| iOS 홈 화면 | `icon/apple-touch-icon-180.png` |
| PWA · 안드로이드 | `icon/icon-192.png`, `icon-512.png`, `icon-maskable-512.png` |
| 오픈그래프·SNS 카드 | `social/og-light-1200x630.png`, `og-dark-1200x630.png` |

파일명 끝 숫자는 **긴 변 픽셀**이다. `-black`/`-white`는 잉크 색이고 배경은 투명,
`-on-white`/`-on-black`은 배경이 채워진 버전이다.

## 웹에 쓰는 사본

웹이 실제로 서빙하는 파일은 `apps/web/public/` 아래에 **복사되어 있다.** 빌드가 이 폴더를
읽지 않으므로, 원본을 다시 생성하면 **그 사본도 손으로 갱신해야 한다.**

| 사본 | 원본 |
|---|---|
| `apps/web/public/favicon.ico` | `icon/favicon.ico` |
| `apps/web/public/apple-touch-icon-180.png` | `icon/apple-touch-icon-180.png` |
| `apps/web/public/brand/og-image.png` | `social/og-light-1200x630.png` |
| `apps/web/public/brand/lockup-vertical-white-1024.png` | `vertical/…-white-1024.png` (로그인 좌측 패널) |
| `apps/web/public/brand/lockup-horizontal-white-512.png` | `horizontal/…-white-512.png` (랜딩 헤더, 다크) |
| `apps/web/public/brand/lockup-horizontal-black-512.png` | `horizontal/…-black-512.png` (내부 헤더, 라이트) |
| `apps/web/public/brand/mark-white-512.png` | `mark/mark-white-512.png` |
| `apps/web/public/brand/mark-black-256.png` | `mark/mark-black-256.png` (좁은 화면 로그인) |
| `apps/web/public/brand/icon-192.png` 외 | `icon/icon-192.png` 외 (PWA 매니페스트용, 아직 미사용) |

## 규칙

- 잉크색은 `#1A1A1A`, 배경은 `#FFFFFF`. 다른 색을 입히지 않는다.
- 비율을 바꾸거나 세로/가로로 늘리지 않는다.
- 심볼과 워드마크의 간격·상대 크기를 임의로 바꾸지 않는다. 조합이 필요하면 `_generate.py`에서 만든다.
- 세로 락업은 **원작 그대로**다. 가로 락업은 원작에 없어서 만든 조합이고, 두 값이 그 모양을
  정한다 — 둘 다 `_generate.py`에 있고 근거를 주석으로 남겼다.
  - `WORD_SCALE = 4.0` — 워드마크를 원작 비율보다 키운다. 원작의 `HACKER`는 심볼의 0.10배라
    옆에 놓으면 먹힌다. 4.0이면 0.42배로, **심볼이 살짝 우세하되 글자도 읽히는** 지점이다.
  - `gap = 심볼 폭 × 0.34` — 둘 사이 간격. 좁으면 한 글자처럼 뭉치고 넓으면 따로 논다.

  값을 바꿀 때는 **헤더 크기(높이 32px)로 줄여서** 판단한다. 큰 크기에서 좋아 보이는 비율이
  작아지면 무너진다 — 실제로 2.4배는 큰 화면에서만 멀쩡했다.
  세로 정렬 기준은 `HACKER` 줄의 중심이고, 태그라인은 그 아래 매달린다.

## 한계

- **SVG가 없다.** 원본이 래스터라 벡터가 아니다. 큰 인쇄물이나 자수·각인이 필요하면
  원작자에게 벡터 원본(AI/SVG)을 받거나 트레이싱을 따로 의뢰해야 한다. 지금 자산은 2048px가 상한이다.
- **16px 파비콘은 뭉갠다.** 심볼 획이 얇고 밀도가 높아서 16px에서는 형태가 안 남는다.
  브라우저 탭에서 또렷하게 보이려면 단순화한 별도 글리프가 필요한데, 그건 새로운 디자인 결정이라
  임의로 만들지 않았다. 32px 이상은 읽힌다.
