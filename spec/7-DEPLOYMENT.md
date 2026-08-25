[← 스펙 인덱스](README.md)

# 7. 배포

배포에서 **지켜야 할 원칙**을 잡아준다. Terraform 코드, Dockerfile, GitHub Actions 워크플로 같은 실물은 [`docs/ops/`](../docs/ops/)가 원본이며 여기서 반복하지 않는다.

```text
§7-1   구성 요약        무엇이 어디서 도는가
§7-2   배포 원칙        어겼을 때 사고가 나는 것들
§7-3   공개 전 필수 조건  부원에게 열기 전에 반드시
```

| 문서 | 내용 |
|---|---|
| [docs/ops/infra.md](../docs/ops/infra.md) | Terraform, VPC, ECS, ALB, RDS, S3, IAM |
| [docs/ops/deployment.md](../docs/ops/deployment.md) | Docker, GitHub Actions, Vercel 설정 |
| [docs/ops/runbook.md](../docs/ops/runbook.md) | 증상별 원인과 디버깅 명령어 |

## 7-1 구성 요약

```text
브라우저 ──HTTPS──> Vercel (React) ──HTTPS──> ALB ──> ECS Fargate Spot (Spring Boot)
                                                         │
                                              RDS PostgreSQL (프라이빗 서브넷)
                                              S3 (presigned URL로 직결)
```

프론트엔드는 Vercel(`www.khuhacker.com`), API는 AWS(`api.khuhacker.com`)다. `/api/*`는 Vercel rewrites가 ALB로 프록시한다 ([3-3 결정 5](3-3-DESIGN-DECISIONS.md)). 파일은 브라우저와 S3가 presigned URL로 직접 주고받으므로 이 경로를 타지 않는다 ([2-1 §2-1-2·§2-1-4](2-1-USER-STORIES.md)).

**프록시를 거치는 이유는 이제 same-origin 하나다** ([결정 15](3-3-DESIGN-DECISIONS.md#3-3-16-결정-15--api에-커스텀-도메인과-acm-인증서를-붙인다)). 브라우저가 웹 도메인 하나와만 통신하므로 **쿠키가 `SameSite=Lax`로 충분하고** `SameSite=None; Secure`가 필요 없다.

> 원래는 mixed content도 이유였다 — ALB가 HTTP만 제공하던 시절 브라우저가 평문 API 호출을 차단했다. `api.khuhacker.com`에 인증서가 붙으면서 그 조건은 사라졌고, 남은 이유는 쿠키뿐이다. `api.khuhacker.com`을 프론트 코드가 직접 부르면 CORS와 크로스 사이트 쿠키 설정이 따라붙으므로 절대 URL은 쓰지 않는다(`fetch('/api/v1/...')`만 쓴다).

**rewrites 규칙 순서가 중요하다.** Vercel은 위에서부터 첫 번째로 맞는 규칙을 적용하므로 `/api/*` 규칙이 SPA fallback(`/(.*) → /index.html`) **위에** 있어야 한다. 아래에 두면 fallback이 API 요청까지 삼켜 로그인이 되지 않는다. 프록시 `source`도 `/api/v1/:path*`가 아니라 `/api/:path*`로 둔다 — 나중에 `/api/v2`가 생겨도 rewrites 설정을 건드리지 않기 위해서다.

**파일 업로드·다운로드는 이 프록시를 거치지 않는다.** Vercel의 서버리스/Edge 함수는 요청 본문이 4.5MB로 제한되는데, 자료 파일 최대 용량은 20MB([3-3 §3-3-7](3-3-DESIGN-DECISIONS.md))라 애초에 프록시를 통과할 수 없다. 그래서 파일은 presigned URL로 브라우저→S3 직접 업로드/다운로드하고, `/api/*` 프록시는 메타데이터를 주고받는 JSON 요청에만 쓴다.

**구간별 암호화**: ALB는 ACM 인증서로 443을 받고 80은 443으로 리다이렉트한다(`infra/terraform/alb.tf`). 브라우저↔Vercel, Vercel↔ALB 모두 HTTPS라 공개 인터넷을 지나는 평문 구간이 없다. ALB↔ECS 구간만 HTTP인데, VPC 내부이고 ECS 보안그룹이 ALB 보안그룹에서 오는 트래픽만 받는다. 인증 쿠키(JWT·세션)를 가로채면 그 계정으로 로그인한 것과 같으므로([결정 13](3-3-DESIGN-DECISIONS.md)) 이 조건이 깨지면 부원 공개를 멈춰야 한다 ([결정 5](3-3-DESIGN-DECISIONS.md)).

## 7-2 배포 원칙

- **이미지 태그로 `latest`만 쓰지 않는다** (MUST). 커밋 SHA로 태그해야 "배포했는데 옛 코드가 도는" 사고를 막고 롤백이 가능하다.
- **`/actuator/health`는 `permitAll`이어야 한다** (MUST). 빠지면 ALB 헬스체크가 401을 받아 태스크가 무한 재시작한다.
- **마이그레이션은 Flyway만 쓰고 `ddl-auto`는 `validate`로 둔다** (MUST). `create`/`update`는 운영 데이터를 날린다.
- **시크릿은 SSM에서 주입한다** (MUST). 이미지나 워크플로 파일에 값을 넣지 않는다.
- **`terraform.tfvars`와 `*.tfstate`를 커밋하지 않는다** (MUST). tfstate에는 DB 비밀번호가 평문으로 들어간다.
- 배포 서킷 브레이커(`rollback = true`)를 켜둔다 (MUST). 실패한 배포가 자동으로 되돌아간다.
- CI가 태스크 정의를 갱신하므로 `aws_ecs_service`에 `lifecycle { ignore_changes = [task_definition, desired_count] }`를 둔다 (MUST). 없으면 다음 `terraform apply`가 CI 배포를 롤백한다.
- 빌드 플랫폼은 `linux/amd64`로 고정한다 (MUST). 태스크 정의의 `X86_64`와 어긋나면 `exec format error`가 난다.
- **최초 배포 후, `ADMIN_BOOTSTRAP_EMAIL`로 가입하고 `POST /auth/bootstrap-admin`을 호출해 관리자를 승격한다** (MUST) — [3-3 결정 11](3-3-DESIGN-DECISIONS.md). 안 하면 첫 가입자가 계속 `PENDING`으로 남고, 승인해 줄 관리자가 아무도 없다. 토큰 값은 `docs/ops/infra.md`의 안내대로 SSM에서 조회한다.

**개발 순서** — 인프라를 통째로 세우기 전에 로컬에서 기능 하나를 완성하고, 그것으로 배포 경로를 한 번만 관통한다 (SHOULD). 관통이 끝나면 `desired_count = 0`으로 내려 고정비를 막고, 나머지 기능은 로컬에서 쌓는다. 기능 없이 완성된 인프라는 `/actuator/health` 200 외에는 검증할 방법이 없다.

## 7-3 공개 전 필수 조건

**아래를 마치기 전에는 실제 부원 계정·시험 자료를 올리지 않는다** (MUST). 전부 충족되어 v0.1.8부터 공개 운영 중이다 (#48, 2026-08-23).

- [x] 도메인 구매 → ACM 인증서 발급 → ALB에 443 리스너 추가, 80은 443으로 리다이렉트 (#156)
- [x] `vercel.json`의 프록시를 제거하거나 destination을 HTTPS로 변경 (#156)
- [x] ALB 보안그룹에서 평문 80 인바운드 정리 — 80은 443 리다이렉트 전용으로만 열려 있고 앱 트래픽은 타지 않는다
- [x] RDS `deletion_protection = true`, `skip_final_snapshot = false`
- [x] [1-BACKGROUND §1-5](1-BACKGROUND.md)의 미결정 항목이 모두 비었는지 확인
- [x] [5-TESTING §5-2](5-TESTING.md) 중 이번 출시 범위에 해당하는 MUST 테스트가 모두 통과하는지 확인 — CI가 매 PR마다 전체 스위트를 돌린다

위 조건이 깨지면(예: 인증서 만료, ALB가 평문 80으로 앱 트래픽을 받기 시작) **인증 쿠키(JWT·세션)가 그대로 네트워크를 지난다.** 가로채면 그 계정으로 로그인한 것과 같다. 자체 비밀번호는 없지만([3-3 결정 13](3-3-DESIGN-DECISIONS.md#3-3-14-결정-13--가입로그인을-구글-oauth로-한다)) 세션을 훔치는 데는 비밀번호가 필요 없다. 학과 시험 정보와 정리본은 유출되면 곤란한 자료라, 이 조건이 깨지면 부원 공개를 멈춘다.

---
[← 이전: 테스트](5-TESTING.md) · [스펙 인덱스로](README.md)
