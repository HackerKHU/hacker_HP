> **은퇴 조건 — `infra/terraform/*.tf`가 생기면 이 문서를 삭제합니다.**
> 아래 HCL 블록은 그때부터 `.tf` 파일의 복사본이 되고, 한쪽만 고치는 순간 문서가 거짓말을 시작합니다.
> 남길 것은 "적용 순서"(ECR 먼저 → RDS → 전체)뿐이며 [runbook.md](runbook.md)로 옮깁니다.
> 설계 근거와 트레이드오프는 이미 [spec/3-3](../../spec/3-3-DESIGN-DECISIONS.md)에 있습니다.

[← 문서 인덱스](../README.md)

# 인프라 (Terraform / AWS)

> **구성**: ECS Fargate(Spot) + ALB + RDS PostgreSQL + S3, NAT Gateway 없음
> **예산**: 월 약 3.5만원 (RDS 프리티어 적용 시)
> **도메인**: 지금은 없이 시작. Vercel 프록시로 우회 — 자세한 내용과 배포 전 필수 조건은 [deployment.md](deployment.md) 참고

`infra/terraform/`이 생기기 전에는 이 문서가 Terraform 구현 절차의 원본이다. 실제 코드가 생기면 문서 서두의 은퇴 조건에 따라 코드로 책임을 넘긴다.

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

설계 결정의 배경/트레이드오프는 [spec/3-3](../../spec/3-3-DESIGN-DECISIONS.md)에 개별 기록되어 있습니다.

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

## 사전 준비

### Terraform state 저장소

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

### Budgets 알림

**지금 설정하세요.** 월 5만원 초과 시 메일. Fargate Spot 회수가 잦아 태스크가 계속 재시작하거나, 실수로 태스크를 여러 개 띄우면 예산을 넘길 수 있습니다.

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
│  └─ terraform.tfvars             # .gitignore
└─ .github/workflows/
   ├─ ci.yml
   └─ deploy-api.yml
```

모듈 분리는 prod 환경을 추가할 때 합니다. 지금은 파일만 나눠도 충분합니다.

---

## Terraform 코드

### main.tf — provider, backend, locals

```hcl
terraform {
  required_version = ">= 1.10"
  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 5.0" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
  }
  backend "s3" {
    bucket       = "hacker-tfstate-<계정ID>"
    key          = "dev/terraform.tfstate"
    region       = "ap-northeast-2"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = "ap-northeast-2"
  default_tags {
    tags = { Project = "hacker", Env = "dev", ManagedBy = "terraform" }
  }
}

data "aws_caller_identity" "current" {}

locals {
  name   = "hacker"
  region = "ap-northeast-2"
  azs    = ["ap-northeast-2a", "ap-northeast-2c"]
}
```

### network.tf — VPC, 서브넷, 라우팅, S3 엔드포인트

```hcl
resource "aws_vpc" "main" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
}

resource "aws_subnet" "public" {
  count                   = 2
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "10.0.${count.index + 1}.0/24"
  availability_zone       = local.azs[count.index]
  map_public_ip_on_launch = true
  tags                    = { Name = "${local.name}-public-${count.index}" }
}

resource "aws_subnet" "private" {
  count             = 2
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.${count.index + 11}.0/24"
  availability_zone = local.azs[count.index]
  tags              = { Name = "${local.name}-private-${count.index}" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }
}

resource "aws_route_table_association" "public" {
  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# 프라이빗은 인터넷 경로 없음 = NAT 불필요
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id
}

resource "aws_route_table_association" "private" {
  count          = 2
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}

# S3 Gateway Endpoint — 무료
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${local.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.public.id, aws_route_table.private.id]
}
```

**보안그룹 3개 — 여기가 이 아키텍처의 핵심입니다.**

```hcl
resource "aws_security_group" "alb" {
  name   = "${local.name}-alb"
  vpc_id = aws_vpc.main.id

  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  # 도메인 붙이면 443 추가

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_security_group" "ecs" {
  name   = "${local.name}-ecs"
  vpc_id = aws_vpc.main.id

  ingress {
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]   # ★ CIDR 아님. SG 참조.
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]     # ECR pull, SSM, 외부 API
  }
}

resource "aws_security_group" "rds" {
  name   = "${local.name}-rds"
  vpc_id = aws_vpc.main.id

  ingress {
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs.id]
  }
  # egress 없음
}
```

태스크가 퍼블릭 서브넷에 있어도 **인바운드가 ALB SG로만 열려 있어서** 외부에서 태스크의 퍼블릭 IP로 직접 접근할 수 없습니다. CIDR이 아니라 SG를 참조하는 게 포인트입니다.

### database.tf — RDS

```hcl
resource "random_password" "db" {
  length  = 32
  special = false        # RDS가 일부 특수문자를 거부. 껐다가 삽질하지 말 것
}

resource "aws_db_subnet_group" "main" {
  name       = "${local.name}-db"
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_db_instance" "main" {
  identifier     = "${local.name}-dev"
  engine         = "postgres"
  engine_version = "16.4"
  instance_class = "db.t4g.micro"

  allocated_storage     = 20
  max_allocated_storage = 0        # 오토스케일 끔 (프리티어 20GB 초과 방지)
  storage_type          = "gp2"
  storage_encrypted     = true

  db_name  = "hacker"
  username = "hacker_admin"
  password = random_password.db.result

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false
  multi_az               = false

  backup_retention_period      = 7
  performance_insights_enabled = false
  monitoring_interval          = 0

  skip_final_snapshot = true       # dev 한정
  deletion_protection = false      # dev 한정
  apply_immediately   = true
}
```

> **dev DB를 직접 보고 싶을 때:** 프라이빗이라 로컬에서 못 붙습니다. 정답은 **로컬 개발은 docker postgres로 하는 것**이고, dev RDS를 꼭 봐야 하면 ECS Exec으로 태스크에 들어가서 psql을 쓰세요 ([runbook.md](runbook.md)). 편하다고 `publicly_accessible = true`로 열면 스캔 봇이 며칠 안에 찾아옵니다.

### storage.tf — S3, ECR

```hcl
resource "aws_s3_bucket" "uploads" {
  bucket = "${local.name}-uploads-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket_public_access_block" "uploads" {
  bucket                  = aws_s3_bucket.uploads.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_cors_configuration" "uploads" {
  bucket = aws_s3_bucket.uploads.id
  cors_rule {
    allowed_headers = ["*"]
    allowed_methods = ["GET", "PUT", "POST", "HEAD"]
    allowed_origins = [
      "http://localhost:5173",
      "https://<vercel-도메인>.vercel.app"
    ]
    expose_headers  = ["ETag"]
    max_age_seconds = 3000
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "uploads" {
  bucket = aws_s3_bucket.uploads.id
  rule {
    id     = "abort-incomplete-uploads"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload { days_after_initiation = 7 }
  }
}
```

**버킷은 완전 비공개, presigned URL로만 접근합니다.** 시험 정보와 과목 정리본은 승인된 부원만 봐야 하는 자료라, 퍼블릭으로 열어두면 URL만 알면 누구나 받아갈 수 있습니다. 되돌리기 어려운 종류의 실수예요.

S3 업로드는 프록시를 안 거치므로 **CORS 설정은 여전히 필요합니다.** localhost도 꼭 넣으세요. presigned 업로드 실패 원인 1위입니다.

키 네이밍 ([spec/3-2-DESIGN-CONTRACT.md](../../spec/3-2-DESIGN-CONTRACT.md) 기준 — `notes`/`photos` 외에 별도 테이블 없음):

```
notes/{uuid}.{ext}               # note_files.note_id로 note와 연결 (presigned 업로드 시점엔 noteId가 아직 없음)
photos/{photoId}/{uuid}.jpg      # 사진은 서버가 리사이즈 후 업로드하므로 photoId를 알고 있음
photos/{photoId}/thumb/{uuid}.jpg
```

```hcl
resource "aws_ecr_repository" "api" {
  name                 = "${local.name}-api"
  image_tag_mutability = "MUTABLE"
  force_delete         = true
  image_scanning_configuration { scan_on_push = true }
}

resource "aws_ecr_lifecycle_policy" "api" {
  repository = aws_ecr_repository.api.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 5 }
      action       = { type = "expire" }
    }]
  })
}
```

### ssm.tf — Parameter Store

```hcl
resource "random_password" "jwt" {
  length  = 64
  special = false
}

resource "aws_ssm_parameter" "db_url" {
  name  = "/hacker/dev/DB_URL"
  type  = "SecureString"
  value = "jdbc:postgresql://${aws_db_instance.main.address}:5432/${aws_db_instance.main.db_name}"
}

resource "aws_ssm_parameter" "db_username" {
  name  = "/hacker/dev/DB_USERNAME"
  type  = "SecureString"
  value = aws_db_instance.main.username
}

resource "aws_ssm_parameter" "db_password" {
  name  = "/hacker/dev/DB_PASSWORD"
  type  = "SecureString"
  value = random_password.db.result
}

resource "aws_ssm_parameter" "jwt_secret" {
  name  = "/hacker/dev/JWT_SECRET"
  type  = "SecureString"
  value = random_password.jwt.result
}

resource "random_password" "admin_bootstrap_token" {
  length  = 32
  special = false
}

resource "aws_ssm_parameter" "admin_bootstrap_email" {
  name  = "/hacker/dev/ADMIN_BOOTSTRAP_EMAIL"
  type  = "SecureString"
  value = "<최초-관리자-이메일>"   # apply 전에 실제 이메일로 바꿀 것
}

resource "aws_ssm_parameter" "admin_bootstrap_token" {
  name  = "/hacker/dev/ADMIN_BOOTSTRAP_TOKEN"
  type  = "SecureString"
  value = random_password.admin_bootstrap_token.result
}
```

SecureString은 Standard 티어에서 무료입니다(Advanced만 유료). ECS 태스크 정의의 `secrets` 블록에서 바로 참조하므로 앱 코드에 비밀번호가 남지 않습니다.

**`JWT_SECRET`은 필요합니다.** 신원 증명을 JWT로 하기로 확정했습니다([3-3 결정 12](../../spec/3-3-DESIGN-DECISIONS.md)). 인가 상태를 담는 서버 세션은 RDS에 저장하므로 위 `DB_*` 파라미터를 그대로 쓰며, 세션용 파라미터를 따로 만들지 않습니다.

**구글 OAuth 자격도 필요합니다.** 가입·로그인을 구글로 처리하므로([3-3 결정 13](../../spec/3-3-DESIGN-DECISIONS.md)) `GOOGLE_CLIENT_ID`와 `GOOGLE_CLIENT_SECRET`을 같은 방식으로 SecureString에 둡니다. 값은 Google Cloud Console의 OAuth 클라이언트에서 발급받아 넣습니다 — Terraform이 만들 수 있는 값이 아니라 `random_password`를 쓰지 않습니다.

```hcl
resource "aws_ssm_parameter" "google_client_id" {
  name  = "/hacker/dev/GOOGLE_CLIENT_ID"
  type  = "SecureString"
  value = var.google_client_id     # terraform.tfvars (커밋하지 않음)
}

resource "aws_ssm_parameter" "google_client_secret" {
  name  = "/hacker/dev/GOOGLE_CLIENT_SECRET"
  type  = "SecureString"
  value = var.google_client_secret
}
```

두 값은 ECS 태스크 정의의 `secrets` 블록에도 연결해야 컨테이너가 읽습니다(아래 `ecs.tf` 참고). SSM에 만들어두기만 하면 주입되지 않습니다.

승인된 redirect URI는 **프론트엔드 오리진** 기준으로 등록합니다. 브라우저는 Vercel과만 통신하므로 ALB 주소를 등록하면 콜백이 다른 오리진에 떨어져 쿠키가 붙지 않습니다.

```
https://<vercel-도메인>/api/v1/login/oauth2/code/google
```

### ⚠️ redirect URI를 환경변수로 고정하세요

**Spring이 만들어내는 `redirect_uri`를 그대로 두면 로그인이 콜백 전에 거부됩니다.**

Spring Security OAuth2 Client는 기본적으로 **들어온 요청의 scheme·host**로 `redirect_uri`를 조립해 구글에 보냅니다. 그런데 이 구성은 프록시가 두 겹입니다.

```
브라우저 ──HTTPS──> Vercel Edge ──HTTP──> ALB ──> ECS
   원본 호스트          여기서 바뀜        여기서도 바뀜
```

ECS에 도착한 요청의 host는 ALB DNS이고 scheme은 `http`입니다. 그대로 조립하면 `http://hacker-alb-xxxx.../api/v1/login/oauth2/code/google`이 되어 구글에 등록한 값과 달라지고, 구글이 `redirect_uri_mismatch`로 거부합니다.

**환경별로 절대 URI를 명시합니다.**

```yaml
# application-prod.yml
spring:
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: ${GOOGLE_CLIENT_ID}
            client-secret: ${GOOGLE_CLIENT_SECRET}
            redirect-uri: ${OAUTH_REDIRECT_URI}   # https://<vercel-도메인>/api/v1/login/oauth2/code/google
            scope: [openid, email, profile]

app:
  auth:
    allowed-email-domain: ${ALLOWED_EMAIL_DOMAIN}   # khu.ac.kr
```

`OAUTH_REDIRECT_URI`와 `ALLOWED_EMAIL_DOMAIN`은 시크릿이 아니므로 SSM SecureString이 아니라 태스크 정의의 `environment`에 둡니다.

**허용 도메인은 설정 키 `app.auth.allowed-email-domain` 하나로 관리합니다** ([3-1 §3-1-4](../../spec/3-1-DESIGN-ARCHITECTURE.md)). 값을 코드에 하드코딩하지 않고, 이 키가 비어 있으면 기동에 실패하도록 둡니다 — 기본값을 코드에 심어두면 설정 누락이 조용히 지나가고 도메인 제한이 무력해집니다. 로컬은 `application-local.yml`에 같은 키를 적고, 태스크 정의는 위 `environment` 항목으로 주입합니다.

forwarded header(`server.forward-headers-strategy`)로 원본 scheme·host를 복원하는 방법도 있습니다. 다만 이 경로에서는 **Vercel과 ALB 두 곳이 헤더를 건드리므로** 무엇이 어떤 값을 남기는지에 의존하게 됩니다. 명시적 URI가 더 적은 가정으로 같은 결과를 냅니다.

로컬 개발은 `http://localhost:5173/api/v1/login/oauth2/code/google`을 별도 승인 URI로 등록하고 `application-local.yml`에서 같은 키를 덮어씁니다. Vite dev 프록시가 `/api`를 8080으로 넘기므로 브라우저 기준 오리진은 5173입니다.

`ADMIN_BOOTSTRAP_EMAIL`·`ADMIN_BOOTSTRAP_TOKEN`은 [spec 결정 11](../../spec/3-3-DESIGN-DECISIONS.md)의 최초 관리자 승격에 씁니다. `apply` 후 토큰 값을 확인하려면:

```bash
aws ssm get-parameter --name /hacker/dev/ADMIN_BOOTSTRAP_TOKEN \
  --with-decryption --query Parameter.Value --output text
```

이 값을 최초 관리자가 될 사람에게 안전한 채널(직접 전달, 사설 DM 등)로 알려주고, 그 사람이 가입 후 `POST /auth/bootstrap-admin`을 호출할 때 쓰게 합니다. 커밋 로그나 공개 채널에 남기지 않습니다.

**이 값의 소유자는 Terraform입니다.** 회전은 `terraform apply -replace=random_password.admin_bootstrap_token`으로 합니다 — `aws ssm put-parameter`로 직접 덮으면 다음 `apply`가 드리프트로 보고 되살립니다. 파라미터 자체를 지우면 안 됩니다: 태스크 정의가 `secrets`로 항상 참조하므로 **새 태스크가 기동 전에 실패합니다** ([runbook.md](runbook.md)).

### alb.tf — ALB, 타겟 그룹, 리스너

```hcl
resource "aws_lb" "main" {
  name               = "${local.name}-alb"
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.public[*].id

  idle_timeout               = 60
  enable_deletion_protection = false
}

resource "aws_lb_target_group" "api" {
  name        = "${local.name}-api-tg"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = aws_vpc.main.id
  target_type = "ip"                    # ★ Fargate awsvpc는 ip 타겟

  deregistration_delay = 30             # 기본 300초는 배포가 너무 느림

  health_check {
    path                = "/actuator/health"
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}

# 도메인 생기면 추가:
# resource "aws_lb_listener" "https" { port = 443, protocol = "HTTPS", certificate_arn = ... }
# 그리고 위 http 리스너의 default_action을 redirect로 변경
```

### ecs.tf — IAM 역할 2개, 클러스터, 태스크 정의, 서비스

**IAM 역할 2개 — 헷갈리기 쉬운 부분입니다.**

| 역할 | 누가 쓰나 | 권한 |
|---|---|---|
| **Execution Role** | ECS 서비스 자체 | ECR pull, 로그 전송, SSM 파라미터 조회 |
| **Task Role** | 컨테이너 안의 앱 코드 | S3 read/write |

앱에서 S3에 파일 올리는 권한은 **Task Role**입니다. Execution Role에 넣으면 동작하지 않습니다.
Task Role을 제대로 붙이면 **앱에 AWS 액세스 키를 넣을 필요가 없습니다.**

```hcl
data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# --- Execution Role ---
resource "aws_iam_role" "ecs_execution" {
  name               = "${local.name}-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy_attachment" "ecs_execution" {
  role       = aws_iam_role.ecs_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# 시크릿 주입에 필요 — 이거 빼먹으면 태스크가 ResourceInitializationError로 죽음
resource "aws_iam_role_policy" "ecs_execution_ssm" {
  role = aws_iam_role.ecs_execution.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["ssm:GetParameters"]
      Resource = "arn:aws:ssm:${local.region}:${data.aws_caller_identity.current.account_id}:parameter/hacker/dev/*"
    }]
  })
}

# --- Task Role ---
resource "aws_iam_role" "ecs_task" {
  name               = "${local.name}-ecs-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy" "ecs_task" {
  role = aws_iam_role.ecs_task.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
        Resource = "${aws_s3_bucket.uploads.arn}/*"
      },
      {
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = aws_s3_bucket.uploads.arn
      },
      {
        # ECS Exec 용 (컨테이너 접속 디버깅)
        Effect = "Allow"
        Action = [
          "ssmmessages:CreateControlChannel",
          "ssmmessages:CreateDataChannel",
          "ssmmessages:OpenControlChannel",
          "ssmmessages:OpenDataChannel"
        ]
        Resource = "*"
      }
    ]
  })
}
```

**클러스터 + Spot 용량 공급자:**

```hcl
resource "aws_ecs_cluster" "main" {
  name = "${local.name}-cluster"

  setting {
    name  = "containerInsights"
    value = "disabled"          # 켜면 CloudWatch 요금 발생
  }
}

resource "aws_ecs_cluster_capacity_providers" "main" {
  cluster_name       = aws_ecs_cluster.main.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE_SPOT"    # 온디맨드 대비 약 1/3
    weight            = 1
  }
}

resource "aws_cloudwatch_log_group" "api" {
  name              = "/ecs/${local.name}-api"
  retention_in_days = 14          # 무제한으로 두면 요금이 쌓임
}
```

> **Fargate Spot:** AWS가 용량을 회수하면 태스크가 내려가고 다시 뜹니다. 2분 전 알림이 오고 ALB가 드레이닝하므로 대부분 무중단이지만, 순간적으로 응답이 끊길 수 있습니다. dev 환경엔 문제없습니다. 나중에 안정성이 필요하면 온디맨드 weight를 섞으세요. ([결정 3](../../spec/3-3-DESIGN-DECISIONS.md))

**태스크 정의:**

```hcl
resource "aws_ecs_task_definition" "api" {
  family                   = "${local.name}-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "512"       # 0.5 vCPU
  memory                   = "1024"      # JVM이라 512는 빠듯함
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"   # runbook.md의 빌드 플랫폼 주의 참고
  }

  container_definitions = jsonencode([{
    name      = "api"
    image     = "${aws_ecr_repository.api.repository_url}:latest"
    essential = true

    portMappings = [{ containerPort = 8080, protocol = "tcp" }]

    environment = [
      { name = "SPRING_PROFILES_ACTIVE", value = "prod" },
      { name = "AWS_REGION", value = local.region },
      { name = "S3_BUCKET", value = aws_s3_bucket.uploads.id },
      # 프록시가 두 겹이라 Spring이 조립한 redirect_uri는 구글 등록값과 어긋난다. 절대 URI로 고정한다.
      { name = "OAUTH_REDIRECT_URI", value = var.oauth_redirect_uri },
      # 가입을 허용할 학교 이메일 도메인. 시크릿이 아니므로 SSM이 아니라 여기에 둔다.
      { name = "ALLOWED_EMAIL_DOMAIN", value = var.allowed_email_domain },
      { name = "JAVA_TOOL_OPTIONS", value = "-XX:MaxRAMPercentage=70 -XX:+UseSerialGC" }
    ]

    secrets = [
      { name = "DB_URL",               valueFrom = aws_ssm_parameter.db_url.arn },
      { name = "DB_USERNAME",          valueFrom = aws_ssm_parameter.db_username.arn },
      { name = "DB_PASSWORD",          valueFrom = aws_ssm_parameter.db_password.arn },
      { name = "JWT_SECRET",           valueFrom = aws_ssm_parameter.jwt_secret.arn },
      { name = "ADMIN_BOOTSTRAP_EMAIL", valueFrom = aws_ssm_parameter.admin_bootstrap_email.arn },
      { name = "ADMIN_BOOTSTRAP_TOKEN", valueFrom = aws_ssm_parameter.admin_bootstrap_token.arn },
      { name = "GOOGLE_CLIENT_ID",     valueFrom = aws_ssm_parameter.google_client_id.arn },
      { name = "GOOGLE_CLIENT_SECRET", valueFrom = aws_ssm_parameter.google_client_secret.arn }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.api.name
        "awslogs-region"        = local.region
        "awslogs-stream-prefix" = "api"
      }
    }

    healthCheck = {
      command     = ["CMD-SHELL", "curl -f http://localhost:8080/actuator/health || exit 1"]
      interval    = 30
      timeout     = 5
      retries     = 3
      startPeriod = 90          # JVM 부팅 시간. 짧으면 계속 죽음
    }
  }])
}
```

**서비스:**

```hcl
resource "aws_ecs_service" "api" {
  name            = "${local.name}-api"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.api.arn
  desired_count   = 1

  capacity_provider_strategy {
    capacity_provider = "FARGATE_SPOT"
    weight            = 1
  }

  enable_execute_command = true      # 디버깅용 컨테이너 접속

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.ecs.id]
    assign_public_ip = true          # ★ NAT 없이 ECR pull 하려면 필수
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "api"
    container_port   = 8080
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true                  # 배포 실패 시 자동 롤백
  }

  health_check_grace_period_seconds = 120

  # CI가 태스크 정의를 갱신하므로 terraform이 되돌리지 않게
  lifecycle {
    ignore_changes = [task_definition, desired_count]
  }

  depends_on = [aws_lb_listener.http]
}
```

`ignore_changes` 빼먹으면 CI가 배포한 걸 다음 `terraform apply`가 롤백시킵니다.
`deployment_circuit_breaker`는 켜두세요. 배포가 실패하면 자동으로 이전 버전으로 돌아가고, 안 켜면 실패한 태스크가 무한 재시도하면서 요금만 씁니다.

### cicd.tf — GitHub OIDC

```hcl
resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]
}

resource "aws_iam_role" "github_actions" {
  name = "${local.name}-github-actions"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = { "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com" }
        StringLike = {
          # ★ 반드시 특정 레포로 제한
          "token.actions.githubusercontent.com:sub" = "repo:<ORG>/<REPO>:*"
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "github_actions" {
  role = aws_iam_role.github_actions.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" },
      {
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability", "ecr:CompleteLayerUpload",
          "ecr:InitiateLayerUpload", "ecr:PutImage", "ecr:UploadLayerPart",
          "ecr:BatchGetImage", "ecr:DescribeImages"
        ]
        Resource = aws_ecr_repository.api.arn
      },
      {
        Effect = "Allow"
        Action = [
          "ecs:RegisterTaskDefinition", "ecs:DescribeTaskDefinition",
          "ecs:DescribeServices", "ecs:UpdateService"
        ]
        Resource = "*"
      },
      {
        # ★ 이거 빼먹으면 RegisterTaskDefinition이 AccessDenied로 실패함
        Effect   = "Allow"
        Action   = ["iam:PassRole"]
        Resource = [aws_iam_role.ecs_execution.arn, aws_iam_role.ecs_task.arn]
      }
    ]
  })
}
```

`sub` 조건을 `*`로 열면 **다른 사람의 레포에서도 이 역할을 가져다 씁니다.** 반드시 조직/레포명으로 고정하세요.

`iam:PassRole` 누락이 ECS 배포 파이프라인 실패 원인 1위입니다.

### variables.tf — 사람이 넣어야 하는 값

Terraform이 만들어낼 수 없는 값들입니다. 구글 클라이언트 자격은 Google Cloud Console에서 발급받고, redirect URI는 Vercel 도메인이 정해져야 알 수 있습니다.

```hcl
variable "google_client_id" {
  description = "Google Cloud Console OAuth 클라이언트 ID"
  type        = string
  sensitive   = true
}

variable "google_client_secret" {
  description = "Google Cloud Console OAuth 클라이언트 시크릿"
  type        = string
  sensitive   = true
}

variable "oauth_redirect_uri" {
  description = "구글에 등록한 승인 redirect URI. 프론트엔드 오리진 기준이다"
  type        = string
  # 예: https://hacker-hp.vercel.app/api/v1/login/oauth2/code/google
}

variable "allowed_email_domain" {
  description = "가입을 허용할 학교 이메일 도메인"
  type        = string
  default     = "khu.ac.kr"
}
```

```hcl
# terraform.tfvars — .gitignore 대상. 커밋하지 않는다
google_client_id     = "xxxxxxxx.apps.googleusercontent.com"
google_client_secret = "GOCSPX-xxxxxxxx"
oauth_redirect_uri   = "https://hacker-hp.vercel.app/api/v1/login/oauth2/code/google"
```

`sensitive = true`를 붙이면 `terraform plan` 출력에 값이 찍히지 않습니다. **다만 `tfstate`에는 평문으로 들어가므로** 커밋 금지 규칙은 그대로입니다.

`allowed_email_domain`은 시크릿이 아니라 기본값을 둡니다. 도메인이 바뀌면 이 한 줄만 고칩니다.

### outputs.tf

```hcl
output "alb_dns_name"       { value = aws_lb.main.dns_name }
output "ecr_repository_url" { value = aws_ecr_repository.api.repository_url }
output "s3_bucket"          { value = aws_s3_bucket.uploads.id }
output "ecs_cluster"        { value = aws_ecs_cluster.main.name }
output "ecs_service"        { value = aws_ecs_service.api.name }
output "gha_role_arn"       { value = aws_iam_role.github_actions.arn }
output "rds_endpoint"       { value = aws_db_instance.main.address }
```

---

## 적용 순서

의존성 때문에 한 번에 안 됩니다. **ECR이 비어 있으면 ECS 서비스가 이미지를 못 찾아 무한 재시도**하므로 순서가 중요합니다.

```bash
cd infra/terraform
terraform init

# 1) 네트워크
terraform apply -target=aws_vpc.main \
                -target=aws_subnet.public -target=aws_subnet.private \
                -target=aws_internet_gateway.main

# 2) ECR 먼저 (이미지를 넣어야 ECS가 뜸)
terraform apply -target=aws_ecr_repository.api

# 3) 첫 이미지 push
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
REGISTRY=$ACCOUNT.dkr.ecr.ap-northeast-2.amazonaws.com
aws ecr get-login-password --region ap-northeast-2 \
  | docker login --username AWS --password-stdin $REGISTRY

docker buildx build --platform linux/amd64 \
  -t $REGISTRY/hacker-api:latest ./apps/api --push

# 4) RDS (5~10분 소요)
terraform apply -target=aws_db_instance.main

# 5) 전체
terraform plan -out=tfplan
terraform apply tfplan
```

**검증:**

```bash
curl http://$(terraform output -raw alb_dns_name)/actuator/health
# {"status":"UP"} 나오면 최초 배포 확인 완료
```

이 URL을 `vercel.json`의 `destination`에 넣으세요 (자세한 내용은 [deployment.md](deployment.md)).

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

## .gitignore

```
infra/terraform/.terraform/
infra/terraform/*.tfstate
infra/terraform/*.tfstate.*
infra/terraform/terraform.tfvars
infra/terraform/*.tfplan
.env
```

`*.tfstate`에는 **DB 비밀번호가 평문으로** 들어갑니다. 절대 커밋 금지.

## 인프라 체크리스트

- [ ] Budgets 알림 (월 5만원)
- [ ] tfstate S3 버킷
- [ ] `terraform apply` 단계별 완료
- [ ] `curl http://<ALB_DNS>/actuator/health` → 200

---
[다음: 배포 (Docker / GitHub Actions / Vercel) →](deployment.md)
