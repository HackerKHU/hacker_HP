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
- 현재 허용된 HTTP 동작은 `/actuator/health`, 구글 OAuth 경로, `GET /auth/csrf`, `GET /auth/me`, `POST /auth/logout`, `POST /auth/application`, `GET /admin/users`, 그리고 API 문서(`/v3/api-docs`, Swagger UI)다.
- **API 문서는 로그인해야 볼 수 있다** (#23에서 정했다). `permitAll`에 문서 경로를 더하지 않는다 — 승인제 사이트라 명세가 공개되면 엔드포인트·필드·검증 규칙이 전부 드러난다.
- **새 API에는 `@Operation`과 `@ApiResponse`를 붙인다.** 응답 코드 설명에는 계약의 에러 코드를 적는다 (`AuthController`가 본보기다).
- **인증은 `ACCESS_TOKEN`(JWT)과 세션이 함께 있어야 성립한다.** 한쪽만으로 통과시키는 코드를 넣지 않는다 ([3-1 §3-1-5](../../spec/3-1-DESIGN-ARCHITECTURE.md) MUST).
- **모든 엔드포인트는 접근 규칙을 선언한다.** `@PreAuthorize`로 권한을 적거나, 인증 없이 열 것이면 `@PublicApi(reason = "...")`로 그렇게 말한다. 빠뜨리면 `EndpointAuthorizationGuardTest`가 실패한다 (T-146).
- **관리자 API에는 `hasRole('ADMIN')`만 적는다.** `ACTIVE` 조건은 `AccountStatusFilter`가 인가보다 먼저 보장한다 — 같은 규칙을 두 곳에 두면 한쪽만 고쳐진다 (T-147).
- **권한은 `ROLE_*`와 `STATUS_*` 두 축이다.** 세션이 둘 다 authority로 내보내므로 `@PreAuthorize`가 매트릭스([3-1 §3-1-3](../../spec/3-1-DESIGN-ARCHITECTURE.md))를 그대로 옮겨 적을 수 있다 — 신청서 제출은 `hasAuthority('STATUS_PENDING')`, 관리자 API는 `hasRole('ADMIN')`. **Status를 서비스 안에서 따로 검사하지 않는다.** 매트릭스와 코드가 갈라지고, 검사를 빠뜨린 API가 조용히 열린다.

  `STATUS_*`를 인가에 직접 쓰는 것은 **`PENDING` 전용 경로뿐이다.** 나머지는 `AccountStatusFilter`가 이미 `ACTIVE`만 통과시키므로 다시 적지 않는다.
- **상태 차단은 인가보다 먼저다.** `AccountStatusFilter`가 `PENDING`·`SUSPENDED`를 각각의 코드로 막는다 — `@PreAuthorize`에 맡기면 사유가 `FORBIDDEN` 하나로 뭉개져 화면이 안내를 고르지 못한다. **인증 영역에 API를 더하면 기본적으로 막히므로**, 신청·대기에 필요한 경로만 그 필터의 통과 목록에 넣는다.
- **목록 API는 페이지 응답을 쓴다** ([3-2 §3-2-8](../../spec/3-2-DESIGN-CONTRACT.md)). `spring.data.web.pageable`을 설정으로 두고 `@EnableSpringDataWebSupport`는 붙이지 않는다 — 붙이는 순간 Boot 자동설정이 물러나 `max-page-size`가 조용히 무시된다.
- **정렬할 수 있는 필드는 화이트리스트로 검사하고, 목록에 없으면 거절한다.** 무시하면 화면은 정렬된 줄 알고 틀린 순서를 신뢰한다. 값이 없는 행은 언제나 뒤로 보낸다 — PostgreSQL은 `DESC`에서 널을 맨 앞에 올린다.
- **`Pageable`에 `NULLS LAST`를 실을 수 없다.** Spring Data는 Criteria 질의에 그것을 적용하지 못하고 `UnsupportedOperationException`을 던진다. 순서가 널의 위치까지 정해야 하면 `Specification` 안에서 `query.orderBy(...)`로 만든다(건수 질의에는 붙이지 않는다).
- **검색어를 `LIKE`에 넣기 전에 `%`·`_`를 escape한다.** 하지 않으면 `_`가 "아무 글자 하나"로 해석되어 찾지 않은 행이 결과에 섞인다.
- 권한 판단은 세션 값이다. **저장 직전에 상태가 바뀔 수 있는 작업은 행을 잠그고 다시 확인한다** ([3-1 §3-1-4](../../spec/3-1-DESIGN-ARCHITECTURE.md) MUST).
- 기능 API를 추가할 때 컨트롤러 경로는 `/api/v1`로 시작한다 ([`../../spec/3-2-DESIGN-CONTRACT.md`](../../spec/3-2-DESIGN-CONTRACT.md)). `/actuator`와 springdoc 경로는 버전을 붙이지 않는다.
- **인증 없이 열 경로는 `SecurityConfig`의 `PUBLIC_PATHS`에 명시한다.** 나머지는 전부 로그인이 필요하다.
- 별도 이슈 없이 기능 API, JWT 또는 Swagger를 추가하지 않는다. DB·JPA·Flyway·Security는 이미 들어와 있다.
- 새 작업은 이슈를 먼저 만들고 최신 `origin/develop`에서 이슈 번호가 포함된 브랜치를 생성한 뒤 진행한다.

## 검증

- Java 변경 후 `./gradlew spotlessCheck test`를 실행한다.
- **테스트에서 `SecurityMockMvcRequestPostProcessors.csrf()`를 쓰지 않는다.** 그것은 애플리케이션 컨텍스트의 `CsrfFilter`에 손을 뻗어 토큰 저장소를 세션 기반 대역으로 바꾸고, 컨텍스트는 클래스 사이에 캐시되므로 그 교체가 남는다 — 뒤에 도는 테스트가 순서에 따라 깨진다. `testsupport.web.Csrf`로 쿠키·헤더를 직접 싣는다(브라우저가 하는 일과 같다).
- 컨테이너 변경 후 `docker build --platform linux/amd64 .`로 빌드한다.
- health check는 인증 없이 200을 반환하고 응답의 `status` 값이 `UP`이어야 한다.
