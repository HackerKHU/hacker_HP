# hacker_HP Web

React 19, TypeScript, Vite로 만든 프론트엔드 애플리케이션이다. MVP 화면은 전부 구현되어 있다 — 공개 랜딩, 개인정보처리방침, 로그인, 신청·대기, 공지 목록·상세·작성·수정, 회원 관리.

## UI 기반

shadcn/ui + Tailwind CSS를 쓴다 ([3-3 결정 10](../../spec/3-3-DESIGN-DECISIONS.md#3-3-11-결정-10--ui-기반으로-shadcnui--tailwind를-쓴다)). 컴포넌트는 `npx shadcn@latest add <이름>`으로 `src/components/ui/`에 복사해 쓰며, **그 화면을 만드는 이슈에서 필요한 것만** 추가한다.

색 토큰은 `src/index.css`에 있고 이름은 shadcn 규약(`--background`, `--foreground`, `--card`, `--border`, `--muted-foreground` …)을 그대로 쓴다. 컴포넌트들이 이 이름을 참조하므로 별도 이름 체계를 만들지 않는다. 값은 무채색뿐이며 유채색을 넣지 않는다.

**`:root`가 라이트(로그인 이후 화면), `.dark`가 다크(랜딩)다.** 랜딩은 최상위를 `.dark`로 감싸 팔레트를 뒤집어 쓴다.

폰트 Pretendard는 `index.html`에서 jsDelivr CDN으로 받으며 버전을 고정해 두었다. CDN이 실패하면 `--font-sans`의 시스템 폰트로 저하될 뿐 화면은 깨지지 않는다 — 외부 의존이므로 CSP를 걸 때 이 호스트를 빠뜨리지 않는다.

## 바깥에서 받은 것

**출처와 손대면 안 되는 이유를 파일 옆에 남긴다.** 활동사진은 `public/landing/README.md`에,
구글 G 로고는 `src/features/auth/GoogleLogo.tsx` 주석에 있다.

로고는 구글 배포본에서 **로고 부분만 그대로** 옮긴 것이라 색·비율을 고치지 않는다
(spec [3-1 §3-1-5](../../spec/3-1-DESIGN-ARCHITECTURE.md)). 왜 JSX가 아니라 마크업
문자열로 두었는지도 그 주석에 적혀 있다 — React가 `foreignObject` 안을 SVG 네임스페이스로
만들어 그라디언트가 칠해지지 않기 때문이다.

## 화면 폭과 여백

화면마다 폭을 새로 정하지 않는다. **세 갈래뿐이고 각각 이유가 있다.**

| 종류 | 폭 | 화면 | 왜 |
|---|---|---|---|
| 읽는 글 | `max-w-2xl` (672px) | 공지 상세, 공지 작성·수정, 개인정보처리방침, 랜딩 소개·후원 | 한글은 전각이라 글자 폭이 폰트 크기와 비슷하다. 672px이면 16px 본문에서 42자, 14px에서 48자다. 읽기 좋은 줄 길이는 40~50자 안팎이고 둘 다 그 안이다. 전체폭이면 82자가 되어 **눈이 다음 줄 시작을 못 찾는다** |
| 짧은 폼 | `mx-auto max-w-sm` (384px) | 로그인, 신청·대기 | 입력이 두어 개뿐이라 넓힐 이유가 없다. 가운데 정렬해 두 화면 사이를 오갈 때 좌우로 튀지 않게 한다 |
| 표 | 제약 없음 (`<main>`의 1152px) | 공지 목록, 회원 관리 | 열이 많아 좁히면 잘리거나 줄바꿈이 생긴다 |

**공지 작성 폼이 읽기 폭인 것은 의도다.** 거기서 쓴 본문이 상세에서 같은 폭으로 읽히므로, 폭이 다르면 작성자가 보는 줄바꿈과 독자가 보는 줄바꿈이 달라진다.

### 상하 여백

**`AppLayout` 안이면 화면이 자기 상하 여백을 갖지 않는다.** `<main>`이 이미 `py-8`을 준다 — 화면에서 또 주면 겹친다.

**밖이면 갖는다.** 랜딩·개인정보처리방침·로그인은 헤더도 `<main>`도 없어 스스로 여백을 만든다. 로그인의 `py-16`이 그것이다.

## 요구 환경

- Node.js 24.15 이상
- npm 11 이상

## 실행

```bash
npm install
npm run dev
```

API base URL은 `/api/v1`로 고정되어 있다. 로컬에서는 `vite.config.ts`의 프록시가 `http://localhost:8080`으로 넘기므로 [`apps/api`](../api)를 함께 띄운다. 프록시 키는 `/api`라 `/api/v1`이 그대로 넘어간다.

## 백엔드 없이 실행하기

API 서버 없이 화면만 확인하려면 픽스처를 켠다.

```bash
cp .env.example .env.local
```

`.env.local`에서 `VITE_USE_FIXTURES=true`로 바꾸면 `src/api/` 함수들이 실제 요청 대신 더미 응답을 돌려준다. 기본값은 꺼짐이고, 꺼진 빌드에는 더미 값이 포함되지 않는다.

`VITE_FIXTURE_SCENARIO`로 어떤 사용자·실패 상황을 볼지 고른다.

| 값 | 상황 |
|---|---|
| `user` (기본) | ACTIVE / USER — 관리자 라우트가 막힌다 |
| `admin` | ACTIVE / ADMIN — 관리자 라우트가 열린다 |
| `applying` | PENDING, **신청서 제출 전** — 구글 로그인만 마친 상태. 학번이 비어 있다 |
| `pending` | PENDING, **신청서 제출 후** — 승인 대기 |
| `guest` | 세션 없음 — 보호 라우트가 로그인 화면으로 간다 |
| `blocked` | 세션은 있으나 서버가 403 `PENDING_APPROVAL`로 차단 |

`applying`과 `pending`을 나눈 것은 화면이 신청 폼과 대기 안내를 갈라야 하기 때문이다
(spec §3-1-4 — 승인 대상은 신청서를 낸 계정으로 한정된다).

회원 관리도 픽스처가 받는다. 명부에 **신청서를 내지 않은 PENDING**과 활성 관리자 여럿을
섞어 두었다 — 그런 계정이 없으면 "승인 대상이 아니다"와 "마지막 관리자는 자기를 정지할 수
없다"를 화면에서 확인할 수가 없다. 검색·필터·정렬·페이지네이션도 실제로 동작한다.

공지 쓰기(등록·수정·삭제·고정)도 픽스처가 받는다. **저장한 값이 메모리에 남아** 목록 →
작성 → 상세 → 수정 왕복을 그대로 확인할 수 있고, 새로고침하면 초기값으로 돌아간다.
권한(ADMIN)과 필수값·제목 200자 검사도 픽스처가 서버처럼 거부한다 — 통과시키면 오류
화면을 만들 수 없다. 쓰기를 보려면 `VITE_FIXTURE_SCENARIO=admin`으로 둔다.

**픽스처는 임시다.** [`src/api/fixtures.ts`](src/api/fixtures.ts)는 백엔드가 붙으면 통째로 지운다.
같이 지울 곳은 아래로 찾는다 — 파일 목록을 문서에 적으면 픽스처를 쓰는 파일이 늘 때마다 낡고,
낡은 목록대로 지우면 남은 import 때문에 빌드가 깨진다.

```sh
# apps/web에서 실행한다 (저장소 루트가 아니다)
rg -il "fixture" . --hidden
```

`fixture`라는 낱말 하나만 대소문자 없이 찾는다. `VITE_USE_FIXTURES`·`VITE_FIXTURE_SCENARIO`·
픽스처 파일 자체·정적 import·동적 `import('./fixtures')`·이 문서의 안내가 전부 그 낱말을
지나가므로, 패턴을 늘리지 않아도 새 참조가 걸린다. `--hidden`이 없으면 `.env.example`이 빠진다.
gitignore된 각자의 `.env.local`은 검색에 안 잡히니 따로 지운다.

## 배포

[`vercel.json`](vercel.json)에 SPA fallback rewrite가 있다. `BrowserRouter`를 쓰므로 이것이 없으면 `/notices` 같은 경로를 새로고침할 때 404가 난다.

**rewrite 순서에 제약이 있다.** `/api/*`를 ALB로 넘기는 규칙([docs/ops/deployment.md](../../docs/ops/deployment.md))을 추가할 때는 반드시 SPA fallback **위에** 넣는다. Vercel은 위에서부터 첫 번째로 맞는 규칙을 적용하므로, 아래에 두면 fallback이 API 요청까지 `/index.html`로 삼켜 로그인이 되지 않는다.

## 검증

```bash
npm run lint
npm test
npm run build
```

작업 전에 [`AGENTS.md`](AGENTS.md)와 연결된 상위 스펙·컨벤션 문서를 확인한다.
