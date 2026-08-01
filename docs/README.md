# 문서 인덱스

동아리 홈페이지 프로젝트(모노레포) 문서 모음입니다.

- **최종 수정**: 2026-08-01

## 처음 오셨다면

1. [product/01-overview.md](product/01-overview.md) — 뭘 만드는지
2. [architecture/auth.md](architecture/auth.md) — 권한 구조 (필수)
3. [ops/infra.md](ops/infra.md) → [ops/deployment.md](ops/deployment.md) — 어떻게 띄우는지
4. [../CONTRIBUTING.md](../CONTRIBUTING.md) — 어떻게 같이 개발하는지
5. [guides/claude-code-setup.md](guides/claude-code-setup.md) — Claude Code로 세팅을 이어서 진행하는 순서

## 문서 목록

| 문서 | 내용 | 최종수정 |
|---|---|---|
| [product/](product/) | 제품 요구사항 — 개요, 기능별 명세(NOTE/NOTICE/PHOTO/ADMIN), 화면, 비기능 요구사항, 미결정 사항 | 2026-08-01 |
| [architecture/](architecture/) | 기술 설계 — 인증/권한, 데이터 모델, API 명세 | 2026-08-01 |
| [ops/](ops/) | 인프라·배포 — Terraform/AWS, Docker/CI-CD/Vercel, 런북 | 2026-08-01 |
| [adr/](adr/) | 아키텍처 결정 기록 (ADR) | 2026-08-01 |
| [guides/](guides/) | 작업 가이드 — Claude Code 초기 세팅 지시서 | 2026-08-01 |
| [../CONTRIBUTING.md](../CONTRIBUTING.md) | 커밋 컨벤션, 브랜치 전략, PR 규칙 | 2026-08-01 |

## 빠르게 찾기

- 지금 뭘 만들어야 하는지 궁금하다 → [product/01-overview.md](product/01-overview.md)
- 로그인/가입 승인/권한 매트릭스가 궁금하다 → [architecture/auth.md](architecture/auth.md)
- API 엔드포인트 전체 목록이 필요하다 → [architecture/api.md](architecture/api.md)
- 스키마를 바꿔야 한다 → [architecture/data-model.md](architecture/data-model.md)
- 인프라를 처음부터 세팅해야 한다 → [ops/infra.md](ops/infra.md)
- 배포가 실패해서 원인을 찾아야 한다 → [ops/runbook.md](ops/runbook.md)
- 왜 이런 선택을 했는지 궁금하다 (NAT 없음, Spot, SSM 등) → [adr/](adr/)
- 커밋 메시지·브랜치명을 어떻게 지어야 할지 모르겠다 → [../CONTRIBUTING.md](../CONTRIBUTING.md)
