[← 문서 인덱스](../README.md)

# Claude Code 초기 세팅 지시서

> 이 문서는 **Claude Code에 순서대로 붙여넣을 프롬프트 모음**입니다.
> 레포 루트나 `docs/`에 두고, 각 Phase의 프롬프트 블록을 복사해서 사용하세요.
> Phase는 앞에서부터 순서대로 진행합니다. 앞 단계 산출물에 의존합니다.

---

## 사용법

1. 이 문서와 `docs/ops/infra.md`, `docs/ops/deployment.md` 등을 레포에 먼저 커밋
2. Claude Code 실행 → 각 Phase 프롬프트를 붙여넣기
3. Phase가 끝날 때마다 **커밋하고 다음으로**
4. 세션이 끊기면 `CLAUDE.md`가 컨텍스트를 복원해줍니다

**한 번에 다 시키지 마세요.** Phase 단위로 끊어야 검토가 가능하고, 잘못됐을 때 되돌리기 쉽습니다.

---

## 확정된 결정 사항 (참고용)

Claude Code가 물어보면 이대로 답하세요.

| 항목 | 결정 |
|---|---|
| 레포 | GitHub Organization 안에 **모노레포** ([결정 1](../../spec/3-3-DESIGN-DECISIONS.md)) |
| 백엔드 | Spring Boot 3.5 / Java 21 / Gradle (Kotlin DSL) |
| 프론트 | React + TypeScript + Vite → Vercel |
| DB | PostgreSQL 16 (RDS db.t4g.micro) |
| 인프라 | ECS Fargate **Spot** + ALB, NAT Gateway 없음 ([결정 2](../../spec/3-3-DESIGN-DECISIONS.md), [결정 3](../../spec/3-3-DESIGN-DECISIONS.md)) |
| 시크릿 | SSM Parameter Store (SecureString) ([결정 4](../../spec/3-3-DESIGN-DECISIONS.md)) |
| CI 인증 | GitHub OIDC (액세스 키 저장 안 함) |
| 이슈 관리 | Jira, 키 접두사 `HACK-` |
| 브랜치 | `main` / `develop` / `feature/HACK-12-설명` |
| 도메인 | **아직 없음.** Vercel rewrites 프록시로 우회 ([결정 5](../../spec/3-3-DESIGN-DECISIONS.md)) |

---

## Phase 0 — 레포 뼈대

```
모노레포 구조를 만들어줘.

apps/api/     Spring Boot 3.5, Java 21, Gradle Kotlin DSL
              의존성: web, data-jpa, security, validation, actuator,
                     flyway-core, flyway-database-postgresql,
                     postgresql, lombok
              패키지: com.hacker.api

apps/web/     React 19 + TypeScript + Vite
              react-router-dom, axios(또는 fetch wrapper)

infra/terraform/
spec/                 제품·설계 스펙 (이미 있음)
docs/
  ├─ ops/
  └─ guides/
.github/workflows/

루트에 README.md, CLAUDE.md, CONTRIBUTING.md, .gitignore 자리를 만들어줘.
.gitignore에는 terraform state 관련(*.tfstate, .terraform/, terraform.tfvars),
.env, build/, node_modules/ 포함.

각 앱은 아직 빈 상태여도 되지만 `./gradlew bootRun`과 `npm run dev`가
동작하는 수준까지는 맞춰줘.
```

**검증**: `cd apps/api && ./gradlew bootRun` → 8080 뜸 / `cd apps/web && npm run dev` → 5173 뜸

---

## Phase 1 — 커밋 컨벤션 강제 장치

> `CONTRIBUTING.md`와 `.github/PULL_REQUEST_TEMPLATE.md`는 이미 있습니다 (docs 재편 때 작성됨).
> 이 Phase는 그 규칙을 **강제하는 설정 파일만** 추가합니다. 규칙 자체를 다시 쓰지 마세요 — `CONTRIBUTING.md`가 원본입니다. 두 곳에 같은 규칙이 있으면 나중에 한쪽만 고치고 잊어버립니다.

```
CONTRIBUTING.md를 읽고, 거기 적힌 커밋/브랜치/PR 규칙을 강제하는
설정 파일들을 만들어줘. 규칙 자체는 다시 쓰지 말고 그대로 따를 것.

1. .husky/prepare-commit-msg
   브랜치명에서 HACK-숫자를 추출해 커밋 메시지 앞에 자동 삽입.
   이미 티켓 키가 있으면 중복 삽입하지 말 것.
   브랜치에 티켓 키가 없으면 조용히 통과(에러 내지 말 것).

2. .husky/commit-msg + commitlint.config.js
   @commitlint/config-conventional 기반.
   CONTRIBUTING.md의 type/scope 목록을 그대로 반영.
   subject-case 규칙은 한글이라 끄고, header-max-length는 100.

3. .github/workflows/lint-pr.yml
   amannn/action-semantic-pull-request로 PR 제목 형식 검사.
   허용 type/scope는 CONTRIBUTING.md 기준.

husky 설치 스크립트는 루트 package.json에 두고,
apps/web의 package.json과 충돌하지 않게 워크스페이스 설정도 확인해줘.
```

**검증**: `feature/HACK-1-test` 브랜치에서 `git commit -m "feat(api): 테스트"` → `[HACK-1]`이 자동으로 붙는지

> 커밋 규칙은 **코드 작성 전에** 세팅하세요. 나중에 붙이면 이전 커밋들과 형식이 달라져 로그가 지저분해집니다.

---

## Phase 2 — CLAUDE.md

```
루트와 각 앱에 CLAUDE.md를 작성해줘.

## 루트 CLAUDE.md
- 프로젝트 한 줄 소개 (동아리 내부 웹사이트: 시험정보 공유, 과목 정리본,
  활동사진, 공지)
- 모노레포 구조 설명
- 스택 요약
- 자주 쓰는 명령어 (로컬 실행, 테스트, 빌드, terraform)
- 커밋 규칙 요약 3줄 + CONTRIBUTING.md 링크
- 작업 전 읽어야 할 문서 매핑:
    스키마 변경 → spec/3-2-DESIGN-CONTRACT.md
    API 추가/변경 → spec/3-2-DESIGN-CONTRACT.md
    권한 관련 → spec/3-1-DESIGN-ARCHITECTURE.md (필수)
    인프라 → docs/ops/infra.md
    배포 → docs/ops/deployment.md
    장애 → docs/ops/runbook.md
- 전역 금지사항:
    * 시크릿을 코드나 yml에 하드코딩 금지 (SSM Parameter Store 사용)
    * terraform.tfvars, *.tfstate 커밋 금지
    * S3 버킷을 퍼블릭으로 열지 말 것 (presigned URL만 사용)
    * main에 직접 push 금지

## apps/api/CLAUDE.md
- 패키지 구조 규칙 (도메인별 패키지: domain/notice/{controller,service,repository,entity,dto})
- 엔티티에 @Setter 금지, 생성자/정적 팩토리 + 의도가 드러나는 메서드로 상태 변경
- DTO와 엔티티 분리, 컨트롤러는 엔티티를 직접 반환하지 않음
- 예외는 커스텀 예외 + @RestControllerAdvice로 일괄 처리
- 마이그레이션은 Flyway만 사용, ddl-auto는 validate (create/update 금지)
- 새 API는 반드시 권한 검증을 명시 (spec/3-1-DESIGN-ARCHITECTURE.md 권한 매트릭스 참조)

## apps/web/CLAUDE.md
- any 금지, 타입은 명시
- API 호출은 src/api/ 아래 함수로 래핑, 컴포넌트에서 fetch 직접 호출 금지
- API base URL은 '/api' 고정 (Vercel rewrites 프록시 사용, 절대 URL 금지)
- 관리자 화면과 부원 화면 라우트 분리
- 상태관리는 일단 useState/Context로 시작, 라이브러리 도입은 ADR로 결정

각 파일은 200줄 이내로 간결하게. 장황한 설명 대신 규칙만.
```

**검증**: Claude Code 새 세션 열고 "커밋 규칙 뭐야?" 물었을 때 바로 답하는지

---

## Phase 3 — 문서 배치

```
기존에 작성해둔 인프라 문서를 docs/ 구조에 맞게 배치해줘.

1. infra-cicd-fargate.md를 세 개로 분리:
   - docs/ops/infra.md       → Terraform, VPC, ECS, ALB, RDS, S3, IAM
   - docs/ops/deployment.md  → Docker, GitHub Actions, Vercel 설정
   - docs/ops/runbook.md     → 자주 터지는 것들, 디버깅 명령어

2. docs/README.md 인덱스 작성
   - "처음 오셨다면" 읽는 순서 안내
   - 문서 목록 표 (문서 / 내용 / 최종수정)

3. 각 문서 상단에 상태 헤더 추가:
   > 상태: 초안 | 최종수정: YYYY-MM-DD | 담당: @username

4. docs/adr/ 에 지금까지의 결정을 ADR로 기록:
   0001-monorepo.md
   0002-no-nat-gateway.md      (NAT 월 5~6만원, 예산 초과가 이유)
   0003-fargate-spot.md
   0004-ssm-parameter-store.md (Secrets Manager 대비 비용)
   0005-vercel-proxy-no-domain.md (도메인 없이 HTTPS 우회, 공개 전 도메인 필수)

   ADR 형식: 제목 / 상태 / 날짜 / 배경 / 결정 / 이유 / 트레이드오프
   각 200자 내외로 짧게.
```

> ✅ 이 Phase는 2026-08-01에 완료되었습니다. 산출물은 [docs/README.md](../README.md)부터 확인하세요.

---

## Phase 4 — Docker + 로컬 개발 환경

> 인프라(Phase 6)보다 먼저 로컬 개발 루프를 완성합니다. 도메인이 없어 배포해도 실사용은 어차피 못 하니, 인프라 비용을 쓰기 전에 로컬에서 기능부터 만들 수 있게 합니다.

```
docs/ops/deployment.md 를 읽고 만들어줘.

1. apps/api/Dockerfile
   멀티스테이지 + Spring Boot layertools 레이어 추출.
   런타임은 eclipse-temurin:21-jre-alpine, curl 설치(헬스체크용), non-root 유저.
   ENTRYPOINT에서 $JAVA_OPTS 사용.

2. apps/api/.dockerignore

3. 루트 docker-compose.yml
   postgres:16-alpine (RDS와 메이저 버전 일치) + minio
   healthcheck 포함

4. apps/api/src/main/resources/
   application.yml         공통
   application-local.yml   docker-compose 연결, S3 endpoint를 MinIO로,
                           path-style-access: true
   application-prod.yml    환경변수 주입(${DB_URL} 등), ddl-auto: validate

5. S3 클라이언트 설정 클래스
   endpoint와 path-style-access를 프로퍼티로 받아서
   로컬(MinIO)과 운영(S3)이 코드 변경 없이 전환되게

6. Flyway 초기 마이그레이션 디렉토리
   src/main/resources/db/migration/ (V1__init.sql은 Phase 5에서)

7. Spring Security 설정
   /actuator/health 는 permitAll   ← 이거 빠지면 ALB 헬스체크가 401로 실패해서
                                      태스크가 무한 재시작함
   나머지는 일단 authenticated

로컬에서 `docker compose up -d && ./gradlew bootRun` 으로
DB 연결까지 되는 걸 확인할 수 있게 해줘.
```

**검증**: `curl localhost:8080/actuator/health` → `{"status":"UP"}`

---

## Phase 5 — 공지사항 CRUD (관통 테스트용 첫 기능)

> 파일 업로드도 없고 권한도 단순해서(조회 ACTIVE, 쓰기 ADMIN) 로컬→AWS 배포 파이프라인을 관통시켜보기에 적당합니다. 자료·사진·회원관리 같은 나머지 기능은 Phase 8에서 로컬로 계속 만듭니다.

```
spec/3-2-DESIGN-CONTRACT.md 와 auth.md 를 읽고 공지사항(notices) 기능만 구현해줘.
다른 도메인(자료/사진/회원관리)은 아직 만들지 마. 아래는 참고용 요약이고,
필드·제약은 반드시 원본 문서 기준으로 맞출 것.

1. Flyway V1__init.sql
   users, notices   (나머지 테이블은 Phase 8에서 추가)

2. 엔티티 + Repository
   CLAUDE.md 규칙 준수 (setter 금지, 정적 팩토리)

3. 인증/인가 (auth.md 기준 — role과 status는 분리된 별개 필드)
   - role: USER | ADMIN
   - status: PENDING | ACTIVE | SUSPENDED
   - 회원가입 → role=USER, status=PENDING으로 생성
   - 관리자 승인 → status만 ACTIVE로 전환 (role은 그대로 USER)
   - PENDING은 인증 API를 제외한 모든 API에서 403 PENDING_APPROVAL (auth.md AUTH-04)

4. 공지사항 CRUD (spec/3-2-DESIGN-CONTRACT.md 기준)
   - 조회: ACTIVE 이상 (role 무관)
   - 생성/수정/삭제/고정 토글: ADMIN만

권한 검증은 메서드 시큐리티(@PreAuthorize)로 명시적으로 표시해줘.
```

**검증**: `docker compose up -d && ./gradlew bootRun` 상태에서 회원가입→승인→로그인→공지 CRUD가 로컬에서 전부 동작하는지 확인

---

## Phase 6 — Terraform

```
docs/ops/infra.md 를 읽고 infra/terraform/ 아래에 실제 파일을 만들어줘.

파일 분리:
  main.tf       provider, backend, locals
  network.tf    VPC, 서브넷, IGW, 라우트테이블, S3 엔드포인트, 보안그룹 3개
  database.tf   RDS, 서브넷그룹, random_password
  storage.tf    S3, ECR, 라이프사이클
  ssm.tf        Parameter Store
  alb.tf        ALB, 타겟그룹, 리스너
  ecs.tf        클러스터, 용량공급자, 태스크정의, 서비스, IAM 역할 2개, 로그그룹
  cicd.tf       GitHub OIDC provider, IAM 역할
  variables.tf
  outputs.tf
  terraform.tfvars.example

문서의 코드 블록을 그대로 쓰되, placeholder는 변수로 빼줘:
  <계정ID>, <ORG>/<REPO>, <vercel-도메인>
  → var.github_repo, var.vercel_origin 등으로

주의사항 (문서에도 있지만 다시 확인):
- ECS SG의 ingress는 CIDR이 아니라 aws_security_group.alb.id 참조
- 태스크는 퍼블릭 서브넷 + assign_public_ip = true (NAT 없음)
- Execution Role에 ssm:GetParameters 권한 필수
- Task Role과 Execution Role 혼동 금지 (S3 권한은 Task Role)
- GitHub Actions 역할에 iam:PassRole 필수
- aws_ecs_service에 lifecycle { ignore_changes = [task_definition, desired_count] }
- OIDC의 sub 조건을 * 로 열지 말 것

terraform validate 와 terraform fmt 까지 통과시켜줘.
그 다음 docs/ops/infra.md의 적용 순서(네트워크 → ECR → 이미지 push → RDS → 전체)를
따라 실제로 apply까지 진행해줘. Phase 5에서 배포할 코드가 이미 있으니
이번엔 plan에서 멈추지 않고 갑니다.
```

**검증**: `terraform init && terraform validate` 통과, `curl http://<ALB_DNS>/actuator/health` → 200

---

## Phase 7 — GitHub Actions + 관통 배포 확인

```
docs/ops/deployment.md 를 읽고 워크플로를 만들어줘.

1. .github/workflows/ci.yml
   PR 트리거. api 잡(postgres service container + gradlew build)과
   web 잡(npm ci, lint, build) 분리.

2. .github/workflows/deploy-api.yml
   main push + paths 필터(apps/api/**) + workflow_dispatch
   concurrency group으로 동시 배포 방지

   단계:
   - OIDC로 AWS 인증 (permissions: id-token: write 필수)
   - ECR 로그인
   - docker build & push, platforms: linux/amd64
     태그는 github.sha 와 latest 둘 다
     cache-from/to: type=gha
   - describe-task-definition → render-task-definition으로 이미지 교체
   - amazon-ecs-deploy-task-definition, wait-for-service-stability: true
   - ALB 헬스체크 폴링 (최대 5분)

3. README에 필요한 GitHub Secrets 목록 정리
   AWS_ROLE_ARN, ALB_DNS

주의:
- 이미지 태그로 latest만 쓰지 말 것 (배포했는데 옛 코드가 도는 사고)
- linux/amd64 와 태스크 정의의 X86_64가 일치해야 함
```

**관통 확인**: `deploy-api.yml`을 실제로 실행해서 Phase 5의 공지사항 API가 ALB를 통해 응답하는지 확인하세요 (`/actuator/health`뿐 아니라 실제 `/api/notices` 호출까지). 확인되면 도메인을 사기 전까지 비용을 막아둡니다:

```bash
aws ecs update-service --cluster hacker-cluster --service hacker-api --desired-count 0
```

Phase 8(나머지 기능)은 로컬에서 계속 진행하고, 도메인을 산 뒤 `desired-count`를 다시 올려 재배포합니다.

---

## Phase 8 — 나머지 기능 (자료 / 사진 / 회원 관리)

> Phase 5에서 공지사항은 이미 구현했습니다. 여기서는 나머지 도메인을 로컬에서 계속 만듭니다. 배포는 도메인이 생긴 뒤 다시 합니다 (Phase 7 참고).

```
spec/2-1-USER-STORIES.md, 04-photos.md, 05-admin.md 와
spec/3-2-DESIGN-CONTRACT.md, api.md 를 읽고 그대로 구현해줘.
아래는 참고용 요약이고, 필드·제약·엔티티 목록은 반드시 원본 문서 기준으로 맞출 것 —
문서에 없는 테이블이나 필드를 임의로 추가하지 마.

1. Flyway 마이그레이션 추가
   notes, note_files, bookmarks, photos
   (users, notices는 Phase 5에서 이미 생성됨)

2. 엔티티 + Repository
   CLAUDE.md 규칙 준수 (setter 금지, 정적 팩토리)

3. 자료(NOTE) API
   - POST /notes/upload-url — presigned PUT URL 발급 (파일은 S3로 직접 업로드)
   - POST /notes — 메타데이터 등록 (JSON, 업로드된 파일 키 포함)
   - GET /notes, /notes/{id}, /notes/filters — 목록·검색·필터·상세
   - PATCH·DELETE /notes/{id} — 본인 또는 ADMIN만
   - GET /notes/{id}/files/{fileId} — presigned GET URL 발급
   - 즐겨찾기: GET /bookmarks, POST·DELETE /notes/{id}/bookmark

4. 활동사진(PHOTO) API
   - GET /photos — 목록
   - POST /photos — 다중 업로드 (multipart, 서버에서 리사이즈 후 S3 저장). ADMIN만
   - DELETE /photos/{id} — ADMIN만

5. 회원 관리(ADMIN) API
   - GET /admin/users — 목록·검색·필터
   - POST /admin/users/approve, /admin/users/reject — 일괄 처리
   - PATCH /admin/users/{id}/status, /admin/users/{id}/role — 본인 대상 요청은 403
   - DELETE /admin/users/{id} — 본인 대상 요청은 403

권한 검증은 메서드 시큐리티(@PreAuthorize)로 명시적으로 표시하고,
spec/3-2-DESIGN-CONTRACT.md의 권한 컬럼과 정확히 일치시켜줘.
```

**검증**: 로컬에서 자료 업로드→다운로드, 사진 업로드, 회원 승인/정지/권한 변경 흐름이 전부 동작하는지 확인

---

## Phase 9 — 프론트 + Vercel 연결

```
1. apps/web/vercel.json
   /api/:path* 를 ALB DNS로 rewrites
   (terraform output alb_dns_name 값을 넣어야 함 — 나에게 물어봐줘)

2. src/api/ 아래 API 호출 래퍼
   base URL은 '/api' 고정. 절대 URL 쓰지 말 것.

3. 라우팅 골격
   /login, /signup
   /notices, /subjects, /exams, /photos   (부원)
   /admin/*                                (관리자)

4. 역할 기반 라우트 가드

Vercel 대시보드 설정도 README에 정리해줘:
- Root Directory: apps/web
- Ignored Build Step: git diff --quiet HEAD^ HEAD -- ./
```

---

## 최종 검증

- [ ] `docker compose up -d && ./gradlew bootRun` → 로컬 실행 (Phase 4)
- [ ] 공지사항 CRUD 로컬 동작 확인 (Phase 5)
- [ ] `terraform validate` 통과, `terraform apply` (문서의 적용 순서 준수 — ECR에 이미지 먼저) (Phase 6)
- [ ] `curl http://<ALB_DNS>/actuator/health` → 200
- [ ] `deploy-api.yml` 수동 실행 성공, `/api/notices` 관통 확인 (Phase 7)
- [ ] 관통 확인 후 `desired-count 0`으로 비용 정지
- [ ] 나머지 기능(자료/사진/회원관리) 로컬 구현 완료 (Phase 8)
- [ ] 도메인 구매 후 `desired-count` 복구 + 재배포
- [ ] Vercel 배포 후 프론트에서 `/api/...` 호출 성공 (Phase 9)
- [ ] `feature/HACK-1-test` 브랜치 커밋 시 티켓 키 자동 삽입 확인

---

## Claude Code에 반복해서 상기시킬 것

세션이 길어지면 놓치기 쉬운 것들입니다. 필요할 때 이 문장을 그대로 붙여넣으세요.

```
작업 전에 CLAUDE.md와 관련 docs/ 문서를 먼저 읽어줘.
시크릿은 SSM Parameter Store를 쓰고 코드에 하드코딩하지 마.
새 API를 만들면 spec/3-1-DESIGN-ARCHITECTURE.md의 권한 매트릭스에 맞게
@PreAuthorize를 명시하고, 매트릭스에 없으면 나에게 먼저 물어봐.
```

---

## 미해결 / 나중에 결정할 것

- **도메인 구매** — 회비 결재 후. 부원들에게 공개하기 전에는 반드시 필요합니다.
  현재 Vercel↔ALB 구간이 평문 HTTP라 로그인 비밀번호가 그대로 오갑니다.
  **그전까지 실제 부원 계정이나 시험 자료를 올리지 마세요.**
- prod 환경 분리 (`infra/terraform/envs/`)
- 이미지 리사이징 (활동사진 썸네일)
- RDS 백업/복구 절차 문서화
- 상태관리 라이브러리 도입 여부 (ADR로)

---
[문서 인덱스로](../README.md)
