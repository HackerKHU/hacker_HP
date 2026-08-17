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
브라우저 ──HTTPS──> Vercel (React) ──HTTP──> ALB ──> ECS Fargate Spot (Spring Boot)
                                                         │
                                              RDS PostgreSQL (프라이빗 서브넷)
                                              S3 (presigned URL로 직결)
```

프론트엔드는 Vercel, API는 AWS다. `/api/*`는 Vercel rewrites가 ALB로 프록시한다 ([3-3 결정 5](3-3-DESIGN-DECISIONS.md)). 파일은 브라우저와 S3가 presigned URL로 직접 주고받으므로 이 경로를 타지 않는다.

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

**아래를 마치기 전에는 실제 부원 계정·시험 자료를 올리지 않는다** (MUST).

- [ ] 도메인 구매 → ACM 인증서 발급 → ALB에 443 리스너 추가, 80은 443으로 리다이렉트
- [ ] `vercel.json`의 프록시를 제거하거나 destination을 HTTPS로 변경
- [ ] ALB 보안그룹에서 평문 80 인바운드 정리
- [ ] RDS `deletion_protection = true`, `skip_final_snapshot = false`
- [ ] [1-BACKGROUND §1-5](1-BACKGROUND.md)의 미결정 항목이 모두 비었는지 확인
- [ ] [5-TESTING §5-2](5-TESTING.md) 중 이번 출시 범위에 해당하는 MUST 테스트가 모두 통과하는지 확인

현재 Vercel↔ALB 구간이 평문이고 ALB 자체도 인터넷에 열려 있어, **인증 쿠키(JWT·세션)가 그대로 네트워크를 지난다.** 가로채면 그 계정으로 로그인한 것과 같다. 자체 비밀번호는 없지만([3-3 결정 13](3-3-DESIGN-DECISIONS.md#3-3-14-결정-13--가입로그인을-구글-oauth로-한다)) 세션을 훔치는 데는 비밀번호가 필요 없다. 학과 시험 정보와 정리본은 유출되면 곤란한 자료다.

---
[← 이전: 테스트](5-TESTING.md) · [스펙 인덱스로](README.md)
