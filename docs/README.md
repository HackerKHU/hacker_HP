# 운영 문서

인프라·배포 실물 절차와 작업 가이드입니다.

**"무엇을 왜 만드는가"는 [`spec/`](../spec/README.md)이 원본**이고, 여기는 "어떻게 띄우고 어떻게 고치는가"만 다룹니다.

| 문서 | 내용 | 수명 |
|---|---|---|
| [ops/runbook.md](ops/runbook.md) | 증상별 원인과 디버깅 명령어 | **유지** |
| [ops/infra.md](ops/infra.md) | Terraform, VPC, ECS, ALB, RDS, S3, IAM | `infra/terraform/` 생기면 삭제 |
| [ops/deployment.md](ops/deployment.md) | Docker, GitHub Actions, Vercel 설정 | 워크플로·Dockerfile 실물 생기면 삭제 |
| [guides/claude-code-setup.md](guides/claude-code-setup.md) | Phase별 세팅 지시서 | 마지막 Phase 끝나면 삭제 |
| [guides/mvp-delivery.md](guides/mvp-delivery.md) | MVP 범위·역할·작업 방식 | 1차 출시 회고 후 삭제 |

**이 폴더는 대부분 비계입니다.** 지금은 코드가 없어 이 문서들이 유일한 원본이지만, 해당 코드가 생기는 순간 코드의 복사본이 됩니다. 복사본을 남겨두면 한쪽만 고쳐져서 문서가 거짓말을 시작합니다. 각 문서 서두에 은퇴 조건을 적어뒀고, 해당 코드를 넣는 PR에서 같이 지웁니다.

`runbook.md`만 영구 문서입니다. "증상 → 원인"은 어떤 코드에도 적히지 않는 정보라서요.

> 각 문서의 최종 수정일·작성자는 `git log <파일경로>`로 확인하세요. 문서 안에 손으로 적어두면 곧 틀린 정보가 됩니다.

## 빠르게 찾기

- 인프라를 처음부터 세팅해야 한다 → [ops/infra.md](ops/infra.md)
- 배포가 실패해서 원인을 찾아야 한다 → [ops/runbook.md](ops/runbook.md)
- 배포에서 지켜야 할 원칙이 궁금하다 → [spec/7-DEPLOYMENT.md](../spec/7-DEPLOYMENT.md)
- 뭘 만드는지, 권한 매트릭스가 어떻게 되는지 → [spec/README.md](../spec/README.md)
- 왜 이런 선택을 했는지 (NAT 없음, Spot, SSM 등) → [spec/3-3-DESIGN-DECISIONS.md](../spec/3-3-DESIGN-DECISIONS.md)
- 커밋 메시지·브랜치명을 어떻게 지어야 할지 → [CONTRIBUTING.md](../CONTRIBUTING.md)
- 지금 무엇을 누가 먼저 구현하는지 → [guides/mvp-delivery.md](guides/mvp-delivery.md)
