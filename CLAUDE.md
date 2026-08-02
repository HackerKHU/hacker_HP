# hacker_HP

동아리 내부용 웹사이트. 회원 승인제로 운영하며 시험·과목 정리본 공유, 공지, 활동사진 아카이브를 제공한다.

## 구조

모노레포. 지금은 `docs/`만 있고 나머지는 순서대로 만든다 ([docs/guides/claude-code-setup.md](docs/guides/claude-code-setup.md)).

```
apps/api            Spring Boot 3.5 / Java 21 / Gradle Kotlin DSL / PostgreSQL 16
apps/web            React 19 + TypeScript + Vite → Vercel
infra/terraform     ECS Fargate Spot + ALB + RDS + S3 (NAT Gateway 없음)
docs/               제품·설계·운영 문서
```

## 작업 전에 읽을 문서

| 하려는 일 | 읽을 것 |
|---|---|
| 권한 관련 (필수) | [docs/architecture/auth.md](docs/architecture/auth.md) |
| 스키마 변경 | [docs/architecture/data-model.md](docs/architecture/data-model.md) |
| API 추가·변경 | [docs/architecture/api.md](docs/architecture/api.md) |
| 인프라 | [docs/ops/infra.md](docs/ops/infra.md) |
| 배포 | [docs/ops/deployment.md](docs/ops/deployment.md) |
| 장애 대응 | [docs/ops/runbook.md](docs/ops/runbook.md) |
| 왜 이렇게 했는지 | [docs/adr/](docs/adr/) |
| 커밋·브랜치·PR | [CONTRIBUTING.md](CONTRIBUTING.md) |

문서에 적힌 내용을 바꾸는 변경이면 **같은 PR에서 문서도 갱신한다.**

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
- 새 API에는 `@PreAuthorize`로 권한을 명시한다. [auth.md](docs/architecture/auth.md) 권한 매트릭스와 일치해야 한다
- 파일 바이트를 서버가 받지 않는다. presigned URL 발급만 한다 ([02-notes.md](docs/product/02-notes.md) NOTE-04)
- `/actuator/health`는 `permitAll`. 빠지면 ALB 헬스체크가 401로 실패해 태스크가 무한 재시작한다

## apps/web 규칙

- `any` 금지
- API 호출은 `src/api/` 아래 함수로 감싼다. 컴포넌트에서 fetch를 직접 부르지 않는다
- API base URL은 `/api` 고정. 절대 URL 금지 (Vercel rewrites 프록시를 탄다)
- 관리자 화면과 부원 화면의 라우트를 분리한다
- 상태관리는 `useState`/Context로 시작한다. 라이브러리 도입은 ADR로 결정한다

## 아직 없는 것

`apps/`가 생기면 스타일 검사를 붙인다 — api는 spotless, web은 biome. 커밋 메시지 훅(husky + commitlint)도 그때 같이 넣는다. 지금은 PR 제목 린트만 있다.
