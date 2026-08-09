# hacker_HP Web

React 19, TypeScript, Vite로 만든 프론트엔드 애플리케이션이다. 현재 라우터와 권한 라우트 가드, 세션 조회, 공통 레이아웃까지 구현되어 있고 각 화면은 이름만 렌더하는 플레이스홀더다.

## UI 기반

shadcn/ui + Tailwind CSS를 쓴다 ([3-3 결정 10](../../spec/3-3-DESIGN-DECISIONS.md#3-3-11-결정-10--ui-기반으로-shadcnui--tailwind를-쓴다)). 컴포넌트는 `npx shadcn@latest add <이름>`으로 `src/components/ui/`에 복사해 쓰며, **그 화면을 만드는 이슈에서 필요한 것만** 추가한다.

색 토큰은 `src/index.css`에 있고 이름은 shadcn 규약(`--background`, `--foreground`, `--card`, `--border`, `--muted-foreground` …)을 그대로 쓴다. 컴포넌트들이 이 이름을 참조하므로 별도 이름 체계를 만들지 않는다. 값은 무채색뿐이며 유채색을 넣지 않는다.

**`:root`가 라이트(로그인 이후 화면), `.dark`가 다크(랜딩)다.** 랜딩은 최상위를 `.dark`로 감싸 팔레트를 뒤집어 쓴다.

폰트 Pretendard는 `index.html`에서 jsDelivr CDN으로 받으며 버전을 고정해 두었다. CDN이 실패하면 `--font-sans`의 시스템 폰트로 저하될 뿐 화면은 깨지지 않는다 — 외부 의존이므로 CSP를 걸 때 이 호스트를 빠뜨리지 않는다.

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
| `pending` | PENDING — 대기중 안내 화면만 접근 가능 |
| `guest` | 세션 없음 — 보호 라우트가 로그인 화면으로 간다 |
| `suspended` | 로그인이 403 `SUSPENDED`로 차단 |
| `blocked` | 세션은 있으나 서버가 403 `PENDING_APPROVAL`로 차단 |

**픽스처는 임시다.** [`src/api/fixtures.ts`](src/api/fixtures.ts)는 백엔드가 붙으면 통째로 지우고 `src/api/auth.ts`의 `VITE_USE_FIXTURES` 분기도 함께 제거한다.

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
