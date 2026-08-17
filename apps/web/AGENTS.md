# Web 작업 지침

## 상위 문서 확인

이 파일은 루트 지침을 대체하지 않고 Web 작업에 필요한 규칙을 추가한다. 작업이나 리뷰를 시작하기 전에 다음 문서를 확인한다.

- 항상 [`../../AGENTS.md`](../../AGENTS.md), [`../../CLAUDE.md`](../../CLAUDE.md), [`../../CONTRIBUTING.md`](../../CONTRIBUTING.md)를 먼저 읽는다.
- 기술 스택과 출시 범위는 [`../../spec/1-BACKGROUND.md`](../../spec/1-BACKGROUND.md)를 따른다.
- 화면 요구사항은 [`../../spec/2-1-USER-STORIES.md`](../../spec/2-1-USER-STORIES.md), 관리자 요구사항은 [`../../spec/2-2-OPERATOR-REQUIREMENTS.md`](../../spec/2-2-OPERATOR-REQUIREMENTS.md)를 읽는다.
- 인증·권한 UI를 변경할 때는 [`../../spec/3-1-DESIGN-ARCHITECTURE.md`](../../spec/3-1-DESIGN-ARCHITECTURE.md)를 확인한다.
- API 연동은 [`../../spec/3-2-DESIGN-CONTRACT.md`](../../spec/3-2-DESIGN-CONTRACT.md), 테스트는 [`../../spec/5-TESTING.md`](../../spec/5-TESTING.md)를 따른다.
- Vercel과 프록시 설정은 [`../../spec/7-DEPLOYMENT.md`](../../spec/7-DEPLOYMENT.md), [`../../docs/ops/deployment.md`](../../docs/ops/deployment.md)를 참고한다.

## 현재 보일러플레이트 범위

- React 19, TypeScript, Vite, npm을 사용한다.
- 현재는 기능, 라우팅, API 호출, 인증 또는 상태관리 라이브러리를 추가하지 않는다.
- 별도 이슈 없이 새로운 의존성이나 기능 화면을 추가하지 않는다.
- 새 작업은 이슈를 먼저 만들고 최신 `origin/develop`에서 이슈 번호가 포함된 브랜치를 생성한 뒤 진행한다.

## 구현 규칙

- `any`를 사용하지 않는다.
- API 호출은 `src/api/` 아래 함수로 감싸고 컴포넌트에서 `fetch`를 직접 호출하지 않는다.
- API base URL은 `/api/v1`로 고정하며 절대 URL을 코드에 넣지 않는다. 상수는 `src/api/client.ts`의 `BASE_URL` 한 곳에만 둔다.
- 관리자 화면과 부원 화면의 라우트를 분리한다.
- 상태관리는 React 기본 기능으로 시작하고 외부 라이브러리는 ADR을 작성한 뒤 도입한다.

## 검증

- 변경 후 `npm run lint`, `npm test`, `npm run build`를 실행한다.
- 사용자 동작을 바꾸면 관련 테스트를 추가한다.
