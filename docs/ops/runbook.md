> 상태: 초안 | 최종수정: 2026-08-01 | 담당: @somsumun

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
| 태스크가 가끔 재시작 | Fargate Spot 회수. 정상 동작 ([adr/0003](../adr/0003-fargate-spot.md)) |

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
