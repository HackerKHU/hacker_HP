[← 문서 인덱스](../README.md)

# 인프라 (Terraform / AWS)

> **원본은 [`infra/terraform/*.tf`](../../infra/terraform/)다.** 이 문서는 설계 근거와 구성도, 처음 세팅하는 사람을 위한 사전 준비만 남긴다.
> 적용 순서는 [runbook.md](runbook.md)에 있다. 설계 트레이드오프는 [spec/3-3](../../spec/3-3-DESIGN-DECISIONS.md)에 있다.

> **구성**: ECS Fargate(Spot) + ALB + RDS PostgreSQL + S3, NAT Gateway 없음
> **예산**: 월 약 3.5만원 (RDS 프리티어 적용 시)
> **도메인**: 지금은 없이 시작. Vercel 프록시로 우회 — 자세한 내용과 배포 전 필수 조건은 [deployment.md](deployment.md) 참고

## 설계 결정 요약

| 항목 | 선택 | 이유 |
|---|---|---|
| 컨테이너 | **ECS Fargate (Spot)** | 서버 관리 불필요. Spot이면 온디맨드 대비 약 1/3 |
| 로드밸런서 | **ALB** | 무중단 배포, 헬스체크, 나중에 ACM 붙이기 쉬움 |
| NAT Gateway | **안 씀** | 월 5~6만원. 예산 전체를 잡아먹음 ([결정 2](../../spec/3-3-DESIGN-DECISIONS.md)) |
| 태스크 위치 | **퍼블릭 서브넷** + `assign_public_ip` | NAT 대신. SG 인바운드를 ALB로만 제한 |
| RDS | **프라이빗 서브넷** | 아웃바운드가 필요 없어 NAT 없이 완전 격리 가능 |
| 시크릿 | **SSM Parameter Store** | SecureString이 Standard 티어에서 무료 ([결정 4](../../spec/3-3-DESIGN-DECISIONS.md)) |
| CI 인증 | **GitHub OIDC** | AWS 액세스 키를 저장하지 않음 |

## 구성도

```
                       [ Vercel ] React
                            │
                            │  /api/*  → rewrites 프록시 (deployment.md)
                            ↓
 ┌──────────────────── VPC 10.0.0.0/16 ──────────────────────────┐
 │                                                                │
 │  public-a 10.0.1.0/24          public-c 10.0.2.0/24           │
 │    ├─ ALB ────────────────────────── ALB                       │
 │    │    SG: 0.0.0.0/0 :80                                      │
 │    │                                                           │
 │    └─ ECS Task (Fargate Spot)                                  │
 │         assign_public_ip = true  ← NAT 대신 ECR pull 경로       │
 │         SG: inbound = ALB SG 만, :8080                         │
 │              │                                                 │
 │              ↓                                                 │
 │  private-a 10.0.11.0/24        private-c 10.0.12.0/24         │
 │    └─ RDS PostgreSQL 16 (db.t4g.micro)                         │
 │         SG: inbound = ECS SG 만, :5432 / egress 없음           │
 │                                                                │
 └──────────┬─────────────────────────┬───────────────────────────┘
      Internet Gateway         S3 Gateway Endpoint (무료)
                                      │
                                  S3 (presigned URL)
```

퍼블릭/프라이빗 서브넷 각 2개. 태스크는 1개만 띄우지만 **ALB와 RDS 서브넷 그룹이 최소 2개 AZ를 요구**합니다.

## 처음 세팅하는 사람을 위한 사전 준비

### Terraform state 저장소 (완료 — #43)

```bash
export AWS_REGION=ap-northeast-2
export ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)

aws s3api create-bucket \
  --bucket hacker-tfstate-$ACCOUNT_ID \
  --region $AWS_REGION \
  --create-bucket-configuration LocationConstraint=$AWS_REGION

aws s3api put-bucket-versioning \
  --bucket hacker-tfstate-$ACCOUNT_ID \
  --versioning-configuration Status=Enabled
```

DynamoDB 락 테이블은 불필요합니다. Terraform 1.10+ 의 S3 네이티브 락(`use_lockfile = true`)을 씁니다. 팀 전원 버전을 1.10 이상으로 통일하세요.

### Budgets 알림 (완료 — #43)

월 예산 초과 시 메일. Fargate Spot 회수가 잦아 태스크가 계속 재시작하거나, 실수로 태스크를 여러 개 띄우면 예산을 넘길 수 있습니다. 계정 통화 설정에 따라 `BudgetLimit.Unit`이 `USD`만 지원될 수 있습니다.

### `terraform.tfvars` 채우기

Terraform이 만들어낼 수 없는 값들입니다 — [`infra/terraform/terraform.tfvars.example`](../../infra/terraform/terraform.tfvars.example)을 복사해 채웁니다. 구글 클라이언트 자격은 Google Cloud Console에서 발급받고(#82), redirect URI·CORS 오리진은 Vercel 도메인이 정해져야 알 수 있습니다.

`terraform.tfvars`는 `.gitignore` 대상입니다. **절대 커밋하지 않습니다** — `sensitive = true`를 붙여도 `terraform plan` 출력에만 안 찍힐 뿐, `tfstate`에는 평문으로 들어갑니다.

## 디렉토리 구조

```
/
├─ apps/
│  ├─ api/
│  └─ web/
│     └─ vercel.json
├─ docker-compose.yml              # 로컬 개발 (postgres)
├─ infra/terraform/
│  ├─ main.tf
│  ├─ network.tf
│  ├─ database.tf
│  ├─ storage.tf
│  ├─ ssm.tf
│  ├─ alb.tf
│  ├─ ecs.tf
│  ├─ cicd.tf
│  ├─ variables.tf
│  ├─ outputs.tf
│  ├─ terraform.tfvars.example
│  └─ terraform.tfvars             # .gitignore
└─ .github/workflows/
   ├─ ci.yml
   └─ deploy-api.yml
```

모듈 분리는 prod 환경을 추가할 때 합니다. 지금은 파일만 나눠도 충분합니다.

## IAM 역할 2개 — 헷갈리기 쉬운 부분

| 역할 | 누가 쓰나 | 권한 |
|---|---|---|
| **Execution Role** | ECS 서비스 자체 | ECR pull, 로그 전송, SSM 파라미터 조회 |
| **Task Role** | 컨테이너 안의 앱 코드 | S3 read/write |

앱에서 S3에 파일 올리는 권한은 **Task Role**입니다. Execution Role에 넣으면 동작하지 않습니다.
Task Role을 제대로 붙이면 **앱에 AWS 액세스 키를 넣을 필요가 없습니다.**

## `/actuator/health`를 permitAll 해야 하는 이유

**Spring Security에서 `permitAll` 해야 합니다.** 안 하면 401이 나오고 ALB가 계속 unhealthy 판정 → 태스크 무한 재시작. 첫 배포 실패 원인 1위입니다.

## OAuth redirect URI를 환경변수로 고정해야 하는 이유

**Spring이 만들어내는 `redirect_uri`를 그대로 두면 로그인이 콜백 전에 거부됩니다.**

Spring Security OAuth2 Client는 기본적으로 **들어온 요청의 scheme·host**로 `redirect_uri`를 조립해 구글에 보냅니다. 그런데 이 구성은 프록시가 두 겹입니다.

```
브라우저 ──HTTPS──> Vercel Edge ──HTTP──> ALB ──> ECS
   원본 호스트          여기서 바뀜        여기서도 바뀜
```

ECS에 도착한 요청의 host는 ALB DNS이고 scheme은 `http`입니다. 그대로 조립하면 `http://hacker-alb-xxxx.../api/v1/login/oauth2/code/google`이 되어 구글에 등록한 값과 달라지고, 구글이 `redirect_uri_mismatch`로 거부합니다.

**환경별로 절대 URI를 명시합니다** — `infra/terraform/ecs.tf`의 `OAUTH_REDIRECT_URI` 환경변수, `application-prod.yml`의 `redirect-uri: ${OAUTH_REDIRECT_URI}`.

forwarded header(`server.forward-headers-strategy`)로 원본 scheme·host를 복원하는 방법도 있습니다. 다만 이 경로에서는 **Vercel과 ALB 두 곳이 헤더를 건드리므로** 무엇이 어떤 값을 남기는지에 의존하게 됩니다. 명시적 URI가 더 적은 가정으로 같은 결과를 냅니다.

로컬 개발은 `http://localhost:5173/api/v1/login/oauth2/code/google`을 별도 승인 URI로 등록하고 `application-local.yml`에서 같은 키를 덮어씁니다. Vite dev 프록시가 `/api`를 8080으로 넘기므로 브라우저 기준 오리진은 5173입니다.

**허용 도메인은 설정 키 `app.auth.allowed-email-domain` 하나로 관리합니다** ([3-1 §3-1-4](../../spec/3-1-DESIGN-ARCHITECTURE.md)). 값을 코드에 하드코딩하지 않고, 이 키가 비어 있으면 기동에 실패하도록 둡니다.

`ADMIN_BOOTSTRAP_EMAIL`·`ADMIN_BOOTSTRAP_TOKEN`은 [spec 결정 11](../../spec/3-3-DESIGN-DECISIONS.md)의 최초 관리자 승격에 씁니다. `apply` 후 토큰 값을 확인하려면:

```bash
aws ssm get-parameter --name /hacker/dev/ADMIN_BOOTSTRAP_TOKEN \
  --with-decryption --query Parameter.Value --output text
```

이 값을 최초 관리자가 될 사람에게 안전한 채널(직접 전달, 사설 DM 등)로 알려주고, 그 사람이 가입 후 `POST /auth/bootstrap-admin`을 호출할 때 쓰게 합니다. 커밋 로그나 공개 채널에 남기지 않습니다.

**이 값의 소유자는 Terraform입니다.** `aws ssm put-parameter`로 직접 덮지 않습니다 — 다음 `apply`가 드리프트로 보고 되살립니다. 회전·복구 절차는 [runbook.md](runbook.md)에 있습니다.

## dev DB를 직접 보고 싶을 때

프라이빗이라 로컬에서 못 붙습니다. 정답은 **로컬 개발은 docker postgres로 하는 것**이고, dev RDS를 꼭 봐야 하면 ECS Exec으로 태스크에 들어가서 psql을 쓰세요 ([runbook.md](runbook.md)). 편하다고 `publicly_accessible = true`로 열면 스캔 봇이 며칠 안에 찾아옵니다.

## S3 버킷은 완전 비공개

**퍼블릭으로 열지 않고 presigned URL로만 접근합니다.** 시험 정보와 과목 정리본은 승인된 부원만 봐야 하는 자료라, 퍼블릭으로 열어두면 URL만 알면 누구나 받아갈 수 있습니다. 되돌리기 어려운 종류의 실수예요.

S3 업로드는 프록시를 안 거치므로 **CORS 설정은 여전히 필요합니다.** localhost도 꼭 넣으세요. presigned 업로드 실패 원인 1위입니다.

### 자료 파일의 키 구조 (#53)

| 키 | 무엇 | 수명 |
|---|---|---|
| `notes/uploads/{userId}/{uuid}.{ext}` | 올렸지만 아직 등록 안 된 파일 | **하루** — 라이프사이클 규칙이 걷어감 |
| `notes/{uuid}.{ext}` | 등록된 자료의 파일 | 자료를 지울 때까지 |

**임시 자리를 따로 두는 이유**는, 브라우저가 파일만 올리고 등록을 부르지 않는 일이 흔하기 때문입니다 — 창을 닫거나 네트워크가 끊기거나 그냥 마음을 바꿉니다. 한 자리만 쓰면 그렇게 남은 파일과 멀쩡히 등록된 파일이 섞여 **무엇을 지워도 되는지 알 수 없습니다.**

**키에 업로더 id가 박혀 있는 것도 의도입니다.** 등록 API는 "올라온 키 목록"을 그대로 받으므로, 이 대조가 없으면 **키 문자열만 알면 남이 올린 파일을 자기 자료로 등록할 수 있습니다.**

버킷 이름은 태스크 정의가 `S3_BUCKET`으로 주입합니다. **없으면 API가 기동하지 않습니다** — 설정 누락이 조용히 지나가면 업로드가 엉뚱한 곳을 가리키게 됩니다.

## 예상 비용

| 항목 | 월 |
|---|---|
| Fargate Spot 0.5vCPU/1GB × 1 | 약 9,000원 |
| ALB (고정비 + 최소 LCU) | 약 24,000원 |
| RDS db.t4g.micro | 0원 (프리티어) / 약 19,000원 |
| S3, ECR, CloudWatch Logs | 약 3,000원 |
| **합계** | **약 36,000원** (프리티어 만료 시 약 55,000원) |

**초과할 것 같으면 줄일 수 있는 곳:**
- CloudWatch Logs 보존 14일 → 7일
- 개발 안 하는 시간에 `desired_count = 0` (EventBridge Scheduler)
- RDS 프리티어 만료 시 → 인스턴스 중지(7일마다 자동 재시작되므로 스케줄러 필요)

ALB가 고정비 중 제일 크지만, 빼면 무중단 배포와 나중의 HTTPS 전환이 어려워집니다. 그대로 두는 걸 권합니다.

> 비용은 대략치입니다. Spot 할인율은 시점에 따라 변하므로 확정 전 AWS Pricing Calculator로 한 번 확인하세요.

## 인프라 체크리스트

- [x] Budgets 알림 (월 $38 ≈ 5만원) — #43
- [x] tfstate S3 버킷 (`hacker-tfstate-415368001031`, 버저닝) — #43
- [x] `terraform.tfvars`·`*.tfstate`가 `.gitignore`에 있다
- [ ] `terraform.tfvars` 채우기 (`#82` 완료 후 구글 클라이언트 자격 필요)
- [ ] `terraform validate` 통과
- [ ] `terraform apply` 단계별 완료 ([runbook.md](runbook.md) 최초 적용 순서)
- [ ] `curl http://<ALB_DNS>/actuator/health` → 200

---
[다음: 배포 (Docker / GitHub Actions / Vercel) →](deployment.md)
