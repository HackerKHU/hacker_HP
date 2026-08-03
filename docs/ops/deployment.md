> **은퇴 조건 — Dockerfile·`docker-compose.yml`·워크플로 실물이 생기면 이 문서를 삭제합니다.**
> 아래 코드 블록은 그때부터 실제 파일의 복사본입니다.
> 배포에서 지켜야 할 원칙은 [spec/7-DEPLOYMENT](../../spec/7-DEPLOYMENT.md)가 원본이고, 장애 대응은 [runbook.md](runbook.md)에 남습니다.

[← 문서 인덱스](../README.md)

# 배포 (Docker / GitHub Actions / Vercel)

인프라(VPC·ECS·RDS·S3) 자체는 [infra.md](infra.md) 참고. 이 문서는 "코드를 어떻게 컨테이너로 만들어 그 인프라 위로 올리는지"를 다룹니다.

## ⚠️ 도메인 없이 HTTPS 처리하기

ALB 기본 DNS(`xxx.ap-northeast-2.elb.amazonaws.com`)에는 **ACM 인증서를 붙일 수 없습니다.** 소유한 도메인이어야 발급되기 때문입니다. 그래서 지금은 ALB가 HTTP(80)만 받습니다.

문제는 Vercel이 HTTPS라 브라우저가 HTTP API 호출을 차단한다는 것(mixed content). 해결책은 **Vercel rewrites 프록시**입니다.

```json
// apps/web/vercel.json
{
  "rewrites": [
    {
      "source": "/api/:path*",
      "destination": "http://hacker-alb-xxxx.ap-northeast-2.elb.amazonaws.com/api/:path*"
    }
  ]
}
```

```
브라우저 ──HTTPS──> Vercel Edge ──HTTP──> ALB ──> ECS
```

브라우저는 Vercel하고만 통신하므로 mixed content가 없습니다. **덤으로 same-origin이 되어 쿠키 문제도 사라집니다** — `SameSite=None; Secure`가 필요 없고 `SameSite=Lax`로 충분해집니다. 프론트 코드에서는 그냥 `/api/...`로 호출하면 됩니다.

**파일은 이 프록시를 거치지 않습니다.** Vercel의 서버리스/Edge 함수는 요청 본문이 4.5MB로 제한되는데, 자료 파일 최대 용량은 20MB([3-3 §3-3-7](../../spec/3-3-DESIGN-DECISIONS.md))라 애초에 프록시를 통과할 수 없습니다. 그래서 파일은 presigned URL로 브라우저→S3 직접 업로드/다운로드하고([2-1 §2-1-2·§2-1-4](../../spec/2-1-USER-STORIES.md)), `/api/*` 프록시는 메타데이터를 주고받는 JSON 요청에만 씁니다.

### 지금 이 구성으로 하면 안 되는 것

Vercel↔ALB 구간이 평문 HTTP입니다. AWS 네트워크 내부가 아니라 공개 인터넷을 지나갑니다.

- ✅ 개발/테스트, 더미 데이터, 기능 검증 — 괜찮습니다
- ❌ **실제 부원 계정 생성, 진짜 비밀번호 입력, 시험 정보 업로드 — 하지 마세요**

로그인 비밀번호가 평문으로 네트워크를 지나가고, 학과 시험 정보나 정리본은 유출되면 곤란한 자료입니다. **부원들에게 공개하기 전에는 반드시 도메인 + ACM을 붙여야 합니다.** ([결정 5](../../spec/3-3-DESIGN-DECISIONS.md))

### 나중에 도메인 붙일 때 (10분)

1. 도메인 구매 (연 1.5만원 정도) — 동아리 회비로 결재 받기
2. ACM에서 인증서 발급 (무료), DNS 검증
3. ALB에 443 리스너 추가, 80은 443으로 리다이렉트
4. `api.동아리.com` A레코드 → ALB (alias)
5. `vercel.json` 프록시 제거 또는 destination을 HTTPS로 변경

Terraform 코드로는 `aws_lb_listener` 하나 추가 + 변수 하나 바꾸는 수준입니다. 지금 구조가 그 전환을 막지 않습니다.

---

## Docker

### apps/api/Dockerfile

```dockerfile
# ---------- build ----------
FROM gradle:8.10-jdk21 AS build
WORKDIR /build

# 의존성 캐시 레이어 (소스만 바뀌면 재다운로드 안 함)
COPY build.gradle.kts settings.gradle.kts gradle.properties* ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon || true

COPY src ./src
RUN gradle bootJar --no-daemon -x test

# ---------- extract layers ----------
FROM eclipse-temurin:21-jre-alpine AS extract
WORKDIR /app
COPY --from=build /build/build/libs/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# ---------- runtime ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN apk add --no-cache curl && \
    addgroup -S app && adduser -S app -G app

COPY --from=extract /app/dependencies/          ./
COPY --from=extract /app/spring-boot-loader/    ./
COPY --from=extract /app/snapshot-dependencies/ ./
COPY --from=extract /app/application/           ./

USER app
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
```

레이어 추출을 쓰면 의존성 레이어가 재사용돼서 배포 시 업로드 용량이 수십 MB로 줄어듭니다.
`curl`은 컨테이너 헬스체크에 필요합니다.

> 로더 클래스명은 Spring Boot 3.2 이상 기준입니다. 3.1 이하면 `org.springframework.boot.loader.JarLauncher`.

### apps/api/.dockerignore

```
build/
.gradle/
.git/
*.md
src/test/
.env*
```

### docker-compose.yml (로컬 개발)

```yaml
services:
  postgres:
    image: postgres:16-alpine        # RDS와 메이저 버전 일치
    environment:
      POSTGRES_DB: hacker
      POSTGRES_USER: hacker
      POSTGRES_PASSWORD: localdev
    ports: ["5432:5432"]
    volumes: [pgdata:/var/lib/postgresql/data]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U hacker"]
      interval: 5s

  minio:                              # S3 호환. presigned URL 로직 그대로 테스트
    image: minio/minio
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
    ports: ["9000:9000", "9001:9001"]
    volumes: [miniodata:/data]

volumes:
  pgdata:
  miniodata:
```

S3 클라이언트는 `endpoint`만 바꾸면 MinIO ↔ S3가 전환됩니다. **코드 수정 없이** 로컬/운영이 같은 경로를 탑니다.

```yaml
# application-local.yml
app:
  s3:
    endpoint: http://localhost:9000
    path-style-access: true      # MinIO는 필수
    bucket: hacker-uploads
```

```yaml
# application-prod.yml
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
management:
  endpoints.web.exposure.include: health
  endpoint.health.probes.enabled: true
```

**`/actuator/health`를 Spring Security에서 `permitAll` 해야 합니다.** 안 하면 401이 나오고 ALB가 계속 unhealthy 판정 → 태스크 무한 재시작. 첫 배포 실패 원인 1위입니다.

---

## GitHub Actions

### .github/workflows/ci.yml

```yaml
name: CI

on:
  pull_request:
    branches: [main, develop]

jobs:
  api:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:16-alpine
        env:
          POSTGRES_DB: hacker_test
          POSTGRES_USER: test
          POSTGRES_PASSWORD: test
        ports: ["5432:5432"]
        options: >-
          --health-cmd pg_isready --health-interval 5s --health-retries 10
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: temurin
          cache: gradle
      - working-directory: apps/api
        run: ./gradlew build

  web:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: npm
          cache-dependency-path: apps/web/package-lock.json
      - working-directory: apps/web
        run: |
          npm ci
          npm run lint
          npm run build
```

### .github/workflows/deploy-api.yml

```yaml
name: Deploy API

on:
  push:
    branches: [main]
    paths:
      - 'apps/api/**'              # 모노레포 — API 바뀔 때만
      - '.github/workflows/deploy-api.yml'
  workflow_dispatch:

concurrency:
  group: deploy-api
  cancel-in-progress: false

env:
  AWS_REGION: ap-northeast-2
  ECR_REPO: hacker-api
  ECS_CLUSTER: hacker-cluster
  ECS_SERVICE: hacker-api
  TASK_FAMILY: hacker-api
  CONTAINER_NAME: api

permissions:
  id-token: write                  # ★ OIDC 필수
  contents: read

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ secrets.AWS_ROLE_ARN }}
          aws-region: ${{ env.AWS_REGION }}

      - id: ecr
        uses: aws-actions/amazon-ecr-login@v2

      - uses: docker/setup-buildx-action@v3

      - name: Build & Push
        uses: docker/build-push-action@v6
        with:
          context: apps/api
          platforms: linux/amd64   # ★ 태스크 정의의 X86_64와 일치
          push: true
          tags: |
            ${{ steps.ecr.outputs.registry }}/${{ env.ECR_REPO }}:${{ github.sha }}
            ${{ steps.ecr.outputs.registry }}/${{ env.ECR_REPO }}:latest
          cache-from: type=gha
          cache-to: type=gha,mode=max

      - name: Download current task definition
        run: |
          aws ecs describe-task-definition \
            --task-definition ${{ env.TASK_FAMILY }} \
            --query taskDefinition > task-definition.json

      - name: Update image in task definition
        id: taskdef
        uses: aws-actions/amazon-ecs-render-task-definition@v1
        with:
          task-definition: task-definition.json
          container-name: ${{ env.CONTAINER_NAME }}
          image: ${{ steps.ecr.outputs.registry }}/${{ env.ECR_REPO }}:${{ github.sha }}

      - name: Deploy to ECS
        uses: aws-actions/amazon-ecs-deploy-task-definition@v2
        with:
          task-definition: ${{ steps.taskdef.outputs.task-definition }}
          service: ${{ env.ECS_SERVICE }}
          cluster: ${{ env.ECS_CLUSTER }}
          wait-for-service-stability: true

      - name: Health check
        run: |
          for i in $(seq 1 20); do
            if curl -sf http://${{ secrets.ALB_DNS }}/actuator/health; then
              echo "✅ deployed"; exit 0
            fi
            echo "waiting... ($i/20)"; sleep 15
          done
          echo "❌ health check failed"; exit 1
```

**이미지 태그를 `github.sha`로 씁니다.** `latest`만 쓰면 "배포했는데 옛날 코드가 도는" 사고가 반드시 생깁니다. 롤백도 SHA만 있으면 간단합니다.

### GitHub Secrets

Settings → Secrets and variables → Actions:

| 이름 | 값 |
|---|---|
| `AWS_ROLE_ARN` | `terraform output gha_role_arn` |
| `ALB_DNS` | `terraform output alb_dns_name` |

**AWS 액세스 키는 하나도 필요 없습니다.** OIDC로 매번 임시 자격증명을 받습니다.

### Vercel

- **Root Directory**: `apps/web`
- **Ignored Build Step**: `git diff --quiet HEAD^ HEAD -- ./`
- `vercel.json`에 위 rewrites 설정
- 프론트 코드에서는 `fetch('/api/...')` — 별도 base URL 불필요

---

## 앱 / 배포 체크리스트

**앱**
- [ ] Dockerfile + .dockerignore
- [ ] `docker compose up` 로컬 postgres/minio 확인
- [ ] `/actuator/health` permitAll 설정
- [ ] Flyway 마이그레이션
- [ ] `application-local.yml` / `application-prod.yml` 분리

**배포**
- [ ] GitHub Secrets 2개
- [ ] `deploy-api.yml` 수동 실행 성공
- [ ] Vercel Root Directory = `apps/web`
- [ ] `vercel.json` rewrites에 ALB DNS
- [ ] 프론트에서 `/api/...` 호출 성공 → **최초 배포(도메인 없는 dev 환경) 완료**

**공개 전 (필수)**
- [ ] 도메인 구매 → ACM 인증서 → ALB 443 리스너
- [ ] `vercel.json` 프록시 정리
- [ ] RDS `deletion_protection = true`, `skip_final_snapshot = false`
- [ ] 그전까지 **실제 부원 계정·비밀번호·시험 자료는 올리지 않기**

---
[← 이전: 인프라](infra.md) · [다음: 런북 →](runbook.md)
