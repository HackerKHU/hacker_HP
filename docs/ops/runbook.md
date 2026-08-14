> **이 문서는 유지합니다.** 증상 → 원인 표는 어떤 코드에도 적히지 않는 정보라, `.tf`와 워크플로가 생겨도 대체되지 않습니다.
> 같은 삽질을 두 번 하면 여기에 한 줄 추가하세요.

[← 문서 인덱스](../README.md)

# 런북 — 자주 터지는 것들

## 증상별 원인 / 해결

| 증상 | 원인 / 해결 |
|---|---|
| `exec format error` | 맥에서 arm64 빌드. `platforms: linux/amd64` + 태스크 정의 `X86_64` 일치 ([deployment.md](deployment.md)) |
| 태스크가 PENDING에서 안 넘어감 | `assign_public_ip = false` + NAT 없음 → ECR pull 불가 |
| `ResourceInitializationError` | Execution Role에 `ssm:GetParameters` 누락 ([infra.md](infra.md) ecs.tf) |
| 타겟 unhealthy 무한 반복 | `/actuator/health`가 Security에 막혀 401 / `startPeriod` 짧음 |
| DB 연결 타임아웃 | RDS SG에 ECS SG 미등록 |
| GHA `AccessDenied` on RegisterTaskDefinition | `iam:PassRole` 누락 ([infra.md](infra.md) cicd.tf) |
| GHA OIDC 인증 실패 | `permissions: id-token: write` 누락 또는 `sub` 조건 불일치 |
| terraform이 CI 배포를 롤백 | `ignore_changes = [task_definition]` 누락 |
| presigned 업로드 CORS 에러 | S3 `allowed_origins`에 localhost/Vercel 도메인 누락 |
| Vercel에서 API 호출 실패 | `vercel.json` destination이 ALB DNS와 불일치 |
| 태스크가 가끔 재시작 | Fargate Spot 회수. 정상 동작 ([결정 3](../../spec/3-3-DESIGN-DECISIONS.md)) |
| 첫 가입자가 계속 `PENDING` | 최초 관리자 승격을 안 했다. 아래 절차를 밟는다 |
| 관리자가 전부 사라짐 | 아래 절차로 복구한다 ([2-2 §2-2-7](../../spec/2-2-OPERATOR-REQUIREMENTS.md)) |
| 마지막 관리자가 **정지됨** | 승격 경로로 복구되지 않는다. 아래 "정지된 관리자밖에 없을 때"를 본다 |
| 승격 API가 계속 `403` | 조건 넷 중 하나가 어긋났다. **응답은 사유를 알려주지 않으므로 서버 로그를 본다** (`관리자 승격 거절` 줄) |

## 최초 관리자 승격

**배포 직후 반드시 한 번 해야 한다** ([7-DEPLOYMENT](../../spec/7-DEPLOYMENT.md) MUST). 안 하면 관리자가 0명이라 **아무도 가입을 승인할 수 없고**, 첫 가입자가 계속 `PENDING`으로 남는다. 마지막 관리자가 사라졌을 때의 복구 절차이기도 하다.

넷을 **모두** 만족해야 승격된다 ([3-3 결정 11](../../spec/3-3-DESIGN-DECISIONS.md)).

1. 활성 관리자가 0명
2. 요청자 이메일 == `ADMIN_BOOTSTRAP_EMAIL`
3. 본문 토큰 == `ADMIN_BOOTSTRAP_TOKEN`
4. **신청서 제출 완료** — 구글 로그인만으로는 안 된다

**① 정상 가입을 마친다.** 구글로 로그인하고 **신청 폼까지 제출한다.** 4번 조건이라 건너뛰면 승격되지 않는다.

**② 토큰을 조회한다.**

```bash
aws ssm get-parameter --name /hacker/dev/ADMIN_BOOTSTRAP_TOKEN   --with-decryption --query Parameter.Value --output text
```

> 파라미터 이름은 [infra.md](infra.md)의 `ssm.tf`가 원본이다. 환경이 늘면 그 경로도 함께 바뀐다.

**③ 브라우저에서 호출한다.** 로그인한 그 브라우저의 개발자 도구 콘솔에서 실행한다 — 쿠키 세 개(`SESSION`·`ACCESS_TOKEN`·CSRF)가 모두 필요해 `curl`로는 번거롭다.

```js
await fetch('/api/v1/auth/csrf', { credentials: 'include' })          // 쿠키를 먼저 받는다
const csrf = document.cookie.match(/XSRF-TOKEN=([^;]+)/)[1]
const res = await fetch('/api/v1/auth/bootstrap-admin', {
  method: 'POST',
  credentials: 'include',
  headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrf },
  body: JSON.stringify({ token: '<②에서 받은 값>' }),
})
res.status   // 204면 성공
```

**④ 확인한다.** 새로고침하면 관리자 화면이 열린다 — 승격은 **기존 세션에 즉시 반영되므로** 재로그인이 필요 없다 ([3-1 §3-1-5](../../spec/3-1-DESIGN-ARCHITECTURE.md)).

### 토큰을 바꿔야 할 때

**파라미터를 지우지 않는다.** 태스크 정의가 두 값을 `secrets`로 항상 참조하므로([infra.md](infra.md) `ecs.tf`), 지우면 **새 태스크가 기동 전에 실패한다** — 배포나 Spot 회수 뒤 대체 태스크가 뜨지 못해 서비스가 통째로 멈춘다.

값만 바꾸고 **재배포한다.** SSM 값은 컨테이너가 시작할 때 환경변수로 주입되므로, 이미 돌고 있는 태스크에는 반영되지 않는다.

**Terraform을 통해 바꾼다.** 이 파라미터의 값은 `random_password.admin_bootstrap_token`이 소유한다([infra.md](infra.md) `ssm.tf`) — `aws ssm put-parameter`로 직접 덮으면 **다음 `terraform apply`가 그것을 드리프트로 보고 옛 토큰을 되살린다.**

```bash
terraform apply -replace=random_password.admin_bootstrap_token
aws ecs update-service --cluster hacker-cluster --service hacker-api --force-new-deployment
```

Terraform을 쓸 수 없는 상황이라 CLI로 급히 바꿨다면, **그 값을 잊지 말고 Terraform 쪽에도 반영한다** — 하지 않으면 다음 `apply`에 조용히 되돌아간다.

### 정지된 관리자밖에 없을 때

마지막 활성 관리자가 `SUSPENDED`가 되어 0명이 된 경우는 **위 절차로 복구되지 않는다.** 그 계정은 로그인 단계에서 거절되고, 세션이 남아 있어도 승격 경로가 정지된 계정을 거절하며, 다른 계정은 `ADMIN_BOOTSTRAP_EMAIL`과 달라 실패한다.

1. 신청서까지 마친 **다른 계정**을 준비한다 (없으면 그 사람이 먼저 가입·신청한다).
2. `ADMIN_BOOTSTRAP_EMAIL`을 그 주소로 바꾼다 — 값의 소유자가 Terraform이므로 `.tf`에서 고쳐 `apply`한다.
3. ECS를 재배포한다. SSM 값은 컨테이너가 시작할 때 읽는다.
4. 그 계정으로 위의 승격 절차를 밟는다.
5. 복구한 뒤 정지됐던 계정을 관리자 화면에서 해제한다.

> **토큰을 채팅·이슈·커밋에 남기지 않는다.** 안전한 채널로 최초 관리자에게만 전달한다 ([infra.md](infra.md)).
>
> `403`이 나오면 **응답만으로는 무엇이 틀렸는지 알 수 없다** — 사유를 알려주면 토큰을 추측할 수 있기 때문이다. CloudWatch 로그에서 `관리자 승격 거절` 줄을 찾으면 사유가 적혀 있다.

## 디버깅 명령어

```bash
# 컨테이너 접속
aws ecs execute-command --cluster hacker-cluster \
  --task <task-id> --container api --interactive --command "/bin/sh"

# 로그
aws logs tail /ecs/hacker-api --follow

# 서비스 이벤트 (배포 실패 원인이 여기 찍힘)
aws ecs describe-services --cluster hacker-cluster \
  --services hacker-api --query "services[0].events[:10]"
```

dev RDS를 직접 들여다봐야 할 때도 이 컨테이너 접속으로 들어가서 `psql`을 씁니다 (RDS가 프라이빗 서브넷이라 로컬에서 직접 못 붙습니다).

---
[← 이전: 배포](deployment.md) · [문서 인덱스로](../README.md)
