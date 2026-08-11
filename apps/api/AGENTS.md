# API 작업 지침

## 상위 문서 확인

이 파일은 루트 지침을 대체하지 않고 API 작업에 필요한 규칙을 추가한다. 작업이나 리뷰를 시작하기 전에 다음 문서를 확인한다.

- 항상 [`../../AGENTS.md`](../../AGENTS.md), [`../../CLAUDE.md`](../../CLAUDE.md), [`../../CONTRIBUTING.md`](../../CONTRIBUTING.md)를 먼저 읽는다.
- 기술 스택과 출시 범위는 [`../../spec/1-BACKGROUND.md`](../../spec/1-BACKGROUND.md)를 따른다.
- 인증·권한을 변경할 때는 [`../../spec/3-1-DESIGN-ARCHITECTURE.md`](../../spec/3-1-DESIGN-ARCHITECTURE.md)를 읽는다.
- DB 스키마나 API를 변경할 때는 [`../../spec/3-2-DESIGN-CONTRACT.md`](../../spec/3-2-DESIGN-CONTRACT.md)를 읽고 같은 PR에서 갱신한다.
- 테스트는 [`../../spec/5-TESTING.md`](../../spec/5-TESTING.md), 배포 제약은 [`../../spec/7-DEPLOYMENT.md`](../../spec/7-DEPLOYMENT.md)를 따른다.
- 컨테이너와 운영 절차는 [`../../docs/ops/deployment.md`](../../docs/ops/deployment.md), 장애 대응은 [`../../docs/ops/runbook.md`](../../docs/ops/runbook.md)를 참고한다.

## 현재 보일러플레이트 범위

- Java 21, Spring Boot 3.5, Gradle Kotlin DSL을 사용한다.
- 현재 허용된 HTTP 동작은 `/actuator/health`와 구글 OAuth 경로뿐이다. 기능 API는 아직 없다.
- 기능 API를 추가할 때 컨트롤러 경로는 `/api/v1`로 시작한다 ([`../../spec/3-2-DESIGN-CONTRACT.md`](../../spec/3-2-DESIGN-CONTRACT.md)). `/actuator`와 springdoc 경로는 버전을 붙이지 않는다.
- **인증 없이 열 경로는 `SecurityConfig`의 `PUBLIC_PATHS`에 명시한다.** 나머지는 전부 로그인이 필요하다.
- 별도 이슈 없이 기능 API, JWT 또는 Swagger를 추가하지 않는다. DB·JPA·Flyway·Security는 이미 들어와 있다.
- 새 작업은 이슈를 먼저 만들고 최신 `origin/develop`에서 이슈 번호가 포함된 브랜치를 생성한 뒤 진행한다.

## 검증

- Java 변경 후 `./gradlew spotlessCheck test`를 실행한다.
- 컨테이너 변경 후 `docker build --platform linux/amd64 .`로 빌드한다.
- health check는 인증 없이 200을 반환하고 응답의 `status` 값이 `UP`이어야 한다.
