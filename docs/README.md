# 운영 문서

인프라·배포 실물 절차와 작업 가이드입니다.

**"무엇을 왜 만드는가"는 [`spec/`](../spec/README.md)이 원본**이고, 여기는 "어떻게 띄우고 어떻게 고치는가"만 다룹니다.

| 문서 | 내용 |
|---|---|
| [ops/infra.md](ops/infra.md) | Terraform, VPC, ECS, ALB, RDS, S3, IAM |
| [ops/deployment.md](ops/deployment.md) | Docker, GitHub Actions, Vercel 설정 |
| [ops/runbook.md](ops/runbook.md) | 증상별 원인과 디버깅 명령어 |
| [guides/claude-code-setup.md](guides/claude-code-setup.md) | Claude Code로 세팅을 이어서 진행하는 Phase별 지시서 |

> 각 문서의 최종 수정일·작성자는 `git log <파일경로>`로 확인하세요. 문서 안에 손으로 적어두면 곧 틀린 정보가 됩니다.

## 빠르게 찾기

- 인프라를 처음부터 세팅해야 한다 → [ops/infra.md](ops/infra.md)
- 배포가 실패해서 원인을 찾아야 한다 → [ops/runbook.md](ops/runbook.md)
- 배포에서 지켜야 할 원칙이 궁금하다 → [spec/7-DEPLOYMENT.md](../spec/7-DEPLOYMENT.md)
- 뭘 만드는지, 권한 매트릭스가 어떻게 되는지 → [spec/README.md](../spec/README.md)
- 왜 이런 선택을 했는지 (NAT 없음, Spot, SSM 등) → [spec/3-3-DESIGN-DECISIONS.md](../spec/3-3-DESIGN-DECISIONS.md)
- 커밋 메시지·브랜치명을 어떻게 지어야 할지 → [CONTRIBUTING.md](../CONTRIBUTING.md)
