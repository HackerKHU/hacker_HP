> **원본은 [`apps/api/Dockerfile`](../../apps/api/Dockerfile)·[`docker-compose.yml`](../../docker-compose.yml)·[`.github/workflows/`](../../.github/workflows/)다.** 이 문서는 그 실물이 왜 이런 모양인지, 처음 세팅하는 사람이 무엇을 채워야 하는지만 남긴다.
> 배포에서 지켜야 할 원칙과 프록시 구조의 배경은 [spec/7-DEPLOYMENT](../../spec/7-DEPLOYMENT.md)가 원본이고, 장애 대응은 [runbook.md](runbook.md)에 있다.

[← 문서 인덱스](../README.md)

# 배포 (Docker / GitHub Actions / Vercel)

인프라(VPC·ECS·RDS·S3) 자체는 [infra.md](infra.md) 참고. 이 문서는 "코드를 어떻게 컨테이너로 만들어 그 인프라 위로 올리는지"를 다룹니다.

## API 프록시

프론트는 `www.khuhacker.com`(Vercel), API는 `api.khuhacker.com`(ALB)입니다. 브라우저가 API를 직접 부르지 않고 `apps/web/vercel.json`의 rewrites로 프록시합니다. 프록시가 필요한 이유(same-origin 쿠키 — mixed content는 [결정 15](../../spec/3-3-DESIGN-DECISIONS.md#3-3-16-결정-15--api에-커스텀-도메인과-acm-인증서를-붙인다) 이후 해당 없음), rewrites 순서가 중요한 이유, 파일 업로드가 이 경로를 안 타는 이유는 [spec/7-DEPLOYMENT §7-1](../../spec/7-DEPLOYMENT.md#7-1-구성-요약)에 있습니다.

```
브라우저 ──HTTPS──> Vercel Edge ──HTTPS──> ALB ──> ECS
```

---

## Docker

**`apps/api/Dockerfile`** — 멀티스테이지 빌드(build → extract → runtime)로 레이어 추출을 씁니다. 의존성 레이어가 재사용돼 배포 시 업로드 용량이 수십 MB로 줄어듭니다. JVM 옵션은 ECS에서 `JAVA_TOOL_OPTIONS`로 주입하며 `java`가 자동으로 읽습니다.

> 로더 클래스명(`org.springframework.boot.loader.launch.JarLauncher`)은 Spring Boot 3.2 이상 기준입니다. 3.1 이하면 `org.springframework.boot.loader.JarLauncher`.

**`docker-compose.yml`** (로컬 개발) — Postgres와, 자료·활동사진(#207·#57)이 공용으로 쓰는 로컬 S3 대역 MinIO를 띄웁니다 (`global/storage`, #213). `docker compose up -d`로 함께 뜨고, MinIO 웹 콘솔(`http://localhost:9001`, `minioadmin`/`minioadmin`)로 버킷 안의 오브젝트를 눈으로 확인할 수 있습니다. **테스트는 실제 버킷이 필요 없습니다** — 자료 쪽은 `FileStorage`를 가짜(`FakeFileStorage`)로 갈아끼우고, 활동사진 쪽은 Testcontainers가 별도로 MinIO를 띄웁니다. `docker compose`의 MinIO는 `./gradlew bootRun`으로 서버를 직접 띄워 볼 때만 쓰입니다.

**`application-local.yml` / `application-prod.yml`** 분리 — 로컬은 위 docker-compose 값을, 운영은 `${DB_URL}` 등 환경변수를 읽습니다. `/actuator/health`가 Spring Security에서 `permitAll`이어야 하는 이유는 [spec/7-DEPLOYMENT §7-2](../../spec/7-DEPLOYMENT.md#7-2-배포-원칙)에 있습니다 — 빠뜨리면 첫 배포 실패 원인 1위입니다.

---

## 출시 브랜치

브랜치 전략의 원본은 [`CONTRIBUTING.md`](../../CONTRIBUTING.md)다. 배포할 버전이 준비되면 최신 `origin/develop`에서 SemVer 형식의 release 브랜치를 만든다.

```bash
git fetch origin
git switch -c release/v0.1.0 origin/develop
git push -u origin release/v0.1.0
```

release 브랜치에서는 배포 후보를 검증하고 출시를 막는 수정만 PR로 받는다. 검증이 끝나면 `release/v0.1.0 → main` PR을 Merge commit으로 병합한다. release에서 추가 수정이 발생했다면 배포 후 `release/v0.1.0 → develop` 동기화 PR을 먼저 병합하고 release 브랜치를 삭제한다.

출시 차단 수정은 현재 검증 중인 release 브랜치에서 수정 브랜치를 만든 뒤 같은 release로 PR을 보낸다.

```bash
git fetch origin
git switch -c fix/42-release-blocker origin/release/v0.1.0
git push -u origin fix/42-release-blocker
```

release 브랜치 삭제는 ruleset으로 보호된다. `develop` 동기화와 배포 확인을 모두 끝낸 뒤 ruleset 우회 권한을 가진 Organization Owner 또는 저장소 관리자가 삭제한다.

`develop → main` 직접 PR과 release 브랜치에서의 새 기능 개발은 하지 않는다.

---

## GitHub Actions

**`.github/workflows/ci.yml`** — PR마다 API(`./gradlew build`)와 웹(`npm ci && npm run lint && npm run build`)을 각각 검증합니다.

**`.github/workflows/deploy-api.yml`** — `main`에 `apps/api/**`가 바뀐 push에서만 돈다. 이미지를 커밋 SHA로 태그하는 이유, 배포 서킷 브레이커, `ignore_changes` 필요성 같은 원칙은 [spec/7-DEPLOYMENT §7-2](../../spec/7-DEPLOYMENT.md#7-2-배포-원칙)에 있습니다.

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
- 프론트 코드에서는 `fetch('/api/v1/...')` — 별도 base URL 불필요

---
[← 이전: 인프라](infra.md) · [다음: 런북 →](runbook.md)
