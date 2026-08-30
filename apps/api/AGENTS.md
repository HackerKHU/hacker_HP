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
- 현재 허용된 HTTP 동작은 `/actuator/health`, 구글 OAuth 경로, `GET /auth/csrf`, `GET /auth/me`, `GET /auth/me/content-summary`, `DELETE /auth/me`, `POST /auth/logout`, `POST /auth/application`, `POST /auth/bootstrap-admin`, `GET /admin/users`, `POST /admin/users/approve`, `PATCH /admin/users/status`, `PATCH /admin/users/{id}/status`, 그리고 API 문서(`/v3/api-docs`, Swagger UI)다.
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
- **여러 건을 한 번에 처리하는 API는 실패를 예외로 던지지 않는다.** 한 건 때문에 트랜잭션이 되돌아가면 성공한 것까지 사라진다. 실패는 사유와 함께 결과로 돌려주고 전체는 `200`이다 (§3-2-6 일괄 승인).
- **세션에 쓸 값은 갱신하는 쪽이 잠근 채 직접 읽는다.** 바뀐 값을 넘겨받으면 앞뒤 갱신이 엇갈릴 때 옛 값이 새 값을 덮는다. 행 잠금은 DB 변경만 직렬화한다 — 세션 저장은 잠금이 풀린 뒤라 순서가 뒤집힐 수 있고, 늦게 도착한 해제가 정지를 지운다.
- **`role`·`status`를 바꾸는 서비스는 `SessionSynchronizer.refresh(ids)`를 부른다** ([3-1 §3-1-5](../../spec/3-1-DESIGN-ARCHITECTURE.md) MUST). 빠뜨리면 정지해도 계속 쓰고 승인해도 계속 막힌다 — DB만 보면 멀쩡해 보여서 알아채기 어렵다. 세션을 지우지 않고 갱신한다.
- **커넥션을 겹쳐 잡는 작업에는 동시 실행 상한을 둔다.** 하나를 쥔 채 다음 것을 기다리는 스레드가 풀 크기만큼 모이면 서로를 막고, 그 기다림이 삼켜지면 API는 성공한 채로 뒷일만 어긋난다.
- **그 호출은 트랜잭션 밖이어야 한다.** 안에서 부르면 되돌아간 변경이 세션에만 남고, 커밋 콜백에서 부르면 아직 반납되지 않은 커넥션 위에 커넥션을 겹쳐 잡는다. `@Transactional` 대신 `TransactionTemplate`으로 경계를 코드에 드러내고 그 뒤에 부른다 — 잘못된 자리에서 부르면 `SessionSynchronizer`가 막는다.
- **`users` 행은 언제나 `id` 오름차순으로 잠근다.** 순서가 서비스마다 다르면 두 트랜잭션이 엇갈린 순서로 같은 행들을 원해 교착한다 — 범위째 잠그는 것(`WHERE role='ADMIN' ... FOR UPDATE`)이 특히 위험하다. 잠글 대상은 먼저 **잠금 없이** 훑어 id를 모으고, 그 id들을 정렬해 하나씩 잠근다.
- **여러 행을 함께 봐야 하는 검사는 그 행들을 잠근 뒤에 센다** ([2-2 §2-2-7](../../spec/2-2-OPERATOR-REQUIREMENTS.md) MUST). 세는 것과 바꾸는 것이 따로면 동시에 들어온 두 요청이 둘 다 통과한다 — 활성 관리자가 0명이 되는 식이다.
- **인가가 끝난 뒤에도 요청자의 권한은 바뀔 수 있다.** 인가는 세션 값으로 이루어지므로, 되돌릴 수 없는 조작 전에는 **잠근 요청자 행으로 다시 확인한다.** 하지 않으면 이미 정지된 관리자의 대기 중 요청이 그대로 커밋된다.
- 권한 판단은 세션 값이다. **저장 직전에 상태가 바뀔 수 있는 작업은 행을 잠그고 다시 확인한다** ([3-1 §3-1-4](../../spec/3-1-DESIGN-ARCHITECTURE.md) MUST).
- 기능 API를 추가할 때 컨트롤러 경로는 `/api/v1`로 시작한다 ([`../../spec/3-2-DESIGN-CONTRACT.md`](../../spec/3-2-DESIGN-CONTRACT.md)). `/actuator`와 springdoc 경로는 버전을 붙이지 않는다.
- **인증 없이 열 경로는 `SecurityConfig`의 `PUBLIC_PATHS`에 명시한다.** 나머지는 전부 로그인이 필요하다.
- 별도 이슈 없이 기능 API, JWT 또는 Swagger를 추가하지 않는다. DB·JPA·Flyway·Security는 이미 들어와 있다.
- 새 작업은 이슈를 먼저 만들고 최신 `origin/develop`에서 이슈 번호가 포함된 브랜치를 생성한 뒤 진행한다.

## 검증

- Java 변경 후 `./gradlew spotlessCheck test`를 실행한다.
- **통합 테스트는 `AbstractIntegrationTest`를 상속한다.** 로그인 상태는 `sessions.as(user, request)` 한 줄로 만들고, 계정은 `testsupport.user.Accounts`의 팩토리를 쓴다 — 상태를 손으로 조립하면 실제로는 만들어질 수 없는 계정(예: 신청서 없는 `ACTIVE`)이 생겨 없는 문제를 잡거나 있는 문제를 놓친다.
- **`spring.autoconfigure.exclude`로 세션 자동설정을 끄지 않는다.** 끄면 `MockHttpSession`을 쓸 수 있어 편하지만 **테스트마다 어느 방식인지 골라야 하고**, 설정이 다른 컨텍스트가 여러 벌 떠 전체가 느려진다. `sessions`가 진짜 저장소에 세션을 만들어 준다.
- **테스트에서 `SecurityMockMvcRequestPostProcessors.csrf()`를 쓰지 않는다.** 그것은 애플리케이션 컨텍스트의 `CsrfFilter`에 손을 뻗어 토큰 저장소를 세션 기반 대역으로 바꾸고, 컨텍스트는 클래스 사이에 캐시되므로 그 교체가 남는다 — 뒤에 도는 테스트가 순서에 따라 깨진다. `testsupport.web.Csrf`로 쿠키·헤더를 직접 싣는다(브라우저가 하는 일과 같다).
- 컨테이너 변경 후 `docker build --platform linux/amd64 .`로 빌드한다.
- health check는 인증 없이 200을 반환하고 응답의 `status` 값이 `UP`이어야 한다.
