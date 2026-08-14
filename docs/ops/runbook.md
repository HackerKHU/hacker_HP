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
| 관리자가 전부 사라짐 | 같은 절차로 복구한다 ([2-2 §2-2-7](../../spec/2-2-OPERATOR-REQUIREMENTS.md)) |
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
aws ssm get-parameter --name /hacker-hp/prod/admin-bootstrap-token   --with-decryption --query 'Parameter.Value' --output text
```

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
