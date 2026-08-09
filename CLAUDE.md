# hacker_HP

동아리 내부용 웹사이트. 회원 승인제로 운영하며 시험·과목 정리본 공유, 공지, 활동사진 아카이브를 제공한다.

## 구조

모노레포. 현재 `apps/api`와 `apps/web` 보일러플레이트, `spec/`·`docs/`가 있으며, `infra/`는 다음 단계에서 만든다. 현재 상태·범위·역할·협업 흐름은 [README.md](README.md)가 통합 진입점이다.

## 현재 MVP

1차 출시는 **공개 랜딩·인증·회원 관리·공지**만 구현한다. 랜딩은 로그인 없이 열리는 정적 페이지다 ([spec/3-3 결정 8](spec/3-3-DESIGN-DECISIONS.md#3-3-9-결정-8--공개-랜딩-페이지를-1차-출시에-포함하고-정적으로-구현한다)). 상세 범위와 역할은 [README.md](README.md), 제품 스펙 원본은 [spec/1-BACKGROUND.md §1-6](spec/1-BACKGROUND.md#1-6-1차-출시-범위)이다.

```
apps/api            Spring Boot 3.5 / Java 21 / Gradle Kotlin DSL / PostgreSQL 16
apps/web            React 19 + TypeScript + Vite → Vercel
infra/terraform     ECS Fargate Spot + ALB + RDS + S3 (NAT Gateway 없음)
spec/               제품·설계 스펙 — 무엇을 왜 만드는가
docs/               운영 문서 — 어떻게 띄우고 고치는가
```

## 작업 전에 읽을 문서

| 하려는 일 | 읽을 것 |
|---|---|
| 권한 관련 (필수) | [spec/3-1-DESIGN-ARCHITECTURE.md](spec/3-1-DESIGN-ARCHITECTURE.md) |
| 스키마·API 변경 | [spec/3-2-DESIGN-CONTRACT.md](spec/3-2-DESIGN-CONTRACT.md) |
| 기능 요구사항 확인 | [spec/2-1-USER-STORIES.md](spec/2-1-USER-STORIES.md), [spec/2-2-OPERATOR-REQUIREMENTS.md](spec/2-2-OPERATOR-REQUIREMENTS.md) |
| 테스트 기준 | [spec/5-TESTING.md](spec/5-TESTING.md) |
| 왜 이렇게 했는지 | [spec/3-3-DESIGN-DECISIONS.md](spec/3-3-DESIGN-DECISIONS.md) |
| 인프라 | [docs/ops/infra.md](docs/ops/infra.md) |
| 배포 | [docs/ops/deployment.md](docs/ops/deployment.md) |
| 장애 대응 | [docs/ops/runbook.md](docs/ops/runbook.md) |
| 커밋·브랜치·PR | [CONTRIBUTING.md](CONTRIBUTING.md) |

문서에 적힌 내용을 바꾸는 변경이면 **같은 PR에서 문서도 갱신한다.** 갱신 규칙은 [spec/README.md](spec/README.md) "변경 원칙"에 있다.

## PR을 올릴 때

- **PR과 이슈에는 항상 본인을 assignee로 지정한다** (`gh pr create --assignee @me`). 누가 들고 있는 작업인지 목록에서 바로 보여야 한다. 리뷰어와 별개다 — assignee는 "내가 끝까지 책임진다"는 표시다.
- 일반 작업 PR은 `develop`으로 보내고, `main`에는 `release/vX.Y.Z` 브랜치만 PR을 보낸다. release 브랜치 생성·동기화 절차는 [CONTRIBUTING.md](CONTRIBUTING.md)를 따른다.
- 형식·머지 방식은 [CONTRIBUTING.md](CONTRIBUTING.md)를 따른다.

## 전역 금지

- 시크릿을 코드나 yml에 하드코딩하지 않는다. SSM Parameter Store를 쓴다
- `terraform.tfvars`, `*.tfstate`를 커밋하지 않는다. tfstate에 DB 비밀번호가 평문으로 들어간다
- S3 버킷을 퍼블릭으로 열지 않는다. presigned URL만 쓴다
- `main`에 직접 push하지 않는다
- 권한 매트릭스에 없는 조합이 필요하면 추측하지 말고 먼저 물어본다

## apps/api 규칙

- 패키지는 도메인별로: `domain/notice/{controller,service,repository,entity,dto}`
- 엔티티에 `@Setter`를 두지 않는다. 생성자·정적 팩토리로 만들고, 상태 변경은 의도가 드러나는 메서드로 한다
- DTO와 엔티티를 분리한다. 컨트롤러가 엔티티를 직접 반환하지 않는다
- 예외는 커스텀 예외 + `@RestControllerAdvice`로 일괄 처리한다
- 마이그레이션은 Flyway만 쓴다. `ddl-auto`는 `validate` (create/update 금지)
- 컨트롤러 경로는 `/api/v1`로 시작한다. `/actuator`와 springdoc 경로(`/v3/api-docs`, Swagger UI)는 예외다
- 새 API에는 `@PreAuthorize`로 권한을 명시한다. [3-1](spec/3-1-DESIGN-ARCHITECTURE.md) 권한 매트릭스와 일치해야 한다
- 자료 파일 바이트를 서버가 받지 않는다. presigned URL 발급만 한다 ([2-1 §2-1-2](spec/2-1-USER-STORIES.md))
- `/actuator/health`는 `permitAll`. 빠지면 ALB 헬스체크가 401로 실패해 태스크가 무한 재시작한다

## apps/web 규칙

- `any` 금지
- API 호출은 `src/api/` 아래 함수로 감싼다. 컴포넌트에서 fetch를 직접 부르지 않는다
- API base URL은 `/api/v1` 고정. 절대 URL 금지 (Vercel rewrites 프록시를 탄다)
- 관리자 화면과 부원 화면의 라우트를 분리한다
- 상태관리는 `useState`/Context로 시작한다. 라이브러리 도입은 ADR로 결정한다

## 아직 없는 것

API에는 Spotless, Web에는 Biome이 적용되어 있다. 커밋 메시지 훅(husky + commitlint)은 별도 이슈로 도입한다. 지금은 PR 제목 린트만 있다.
