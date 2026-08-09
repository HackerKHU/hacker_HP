# hacker_HP

동아리 구성원이 공지와 학습 자료를 공유하는 승인제 내부 웹사이트다. 현재 저장소는 스펙이 확정된 구현 착수 단계이며, 1차 출시는 공개 랜딩·인증·회원 관리·공지에 집중한다. 랜딩만 로그인 없이 열리며 정적으로 구현한다 ([spec/3-3 결정 8](spec/3-3-DESIGN-DECISIONS.md#3-3-9-결정-8--공개-랜딩-페이지를-1차-출시에-포함하고-정적으로-구현한다)).

## 현재 출시 범위

전체 제품 범위와 1차 구현 범위를 분리한다. 연기된 기능의 요구사항은 삭제하지 않고 `Post Launch` 백로그에서 관리한다.

| 단계 | 기능 |
|---|---|
| MVP | 가입, 로그인, 로그아웃, 승인 대기 |
| MVP | 관리자 가입 승인, 회원 상태 관리, 회원 관리 화면 |
| MVP | 공지 CRUD, 상단 고정 |
| MVP | React 프론트엔드, Spring API, PostgreSQL, Vercel·ECS Fargate 배포 |
| Post Launch | 자료 CRUD·검색·즐겨찾기, S3 업로드·다운로드 |
| Post Launch | 활동사진 조회·업로드·리사이즈 |
| Post Launch | 가입 거부, 회원 제거, 관리자 권한 부여·회수 |

범위 변경의 원본은 [배경과 1차 출시 범위](spec/1-BACKGROUND.md#1-6-1차-출시-범위), 결정 이유는 [결정 7](spec/3-3-DESIGN-DECISIONS.md#3-3-8-결정-7--1차-출시를-인증회원-관리공지로-제한한다)이다.

## 역할

| 담당 | 책임 |
|---|---|
| 경현 | PM, 프론트엔드, API 통합, Vercel |
| 수민 | 공지 백엔드, 인프라 |
| 승원 | 인증·권한·회원 관리 백엔드 |
| Codex | PR 리뷰 |
| 팀 전체 | 통합 검증, 배포 리허설 |

수민과 승원은 맡은 백엔드 도메인을 병렬로 진행한다. PR은 Codex 리뷰 결과를 확인하고 필요한 피드백을 반영한 뒤 머지한다. 사람 리뷰는 선택 사항이다.

## 아키텍처

```text
브라우저 ──HTTPS──> Vercel (React 19 + TypeScript + Vite)
                         │ /api rewrite
                         ▼
                 ALB ──> ECS Fargate Spot (Spring Boot 3.5 / Java 21)
                                  │
                                  └──> PostgreSQL 16 (RDS)

Post Launch 파일 경로: 브라우저 <──presigned URL──> S3
```

NAT Gateway는 사용하지 않는다. ECS 태스크는 퍼블릭 서브넷에 배치하되 ALB 보안그룹에서 들어오는 트래픽만 허용하고, RDS는 프라이빗 서브넷에 둔다. 상세 근거는 [설계 결정](spec/3-3-DESIGN-DECISIONS.md), 실제 구성 절차는 [인프라 문서](docs/ops/infra.md)에 있다.

## 협업 흐름

| 항목 | 규칙 |
|---|---|
| 백로그 | GitHub Issues가 단일 원본 |
| 마일스톤 | `MVP`, `Post Launch` |
| WIP | 한 사람당 `In Progress` 최대 2개 |
| 기본 브랜치 | `develop`; `main`은 배포 브랜치 |
| 브랜치 | 일반 작업은 `{type}/{issue-number}-{slug}`, 출시는 `release/vX.Y.Z` |
| 출시 흐름 | `develop → release/vX.Y.Z → main` |
| PR 연결 | 본문에 `Closes #이슈번호` |
| API 계약 | 구현 전에 Swagger/OpenAPI로 합의하고 같은 PR에서 갱신 |
| 리뷰 | Codex 리뷰 확인 및 피드백 반영, 사람 리뷰는 선택 |

세부 브랜치·커밋·PR 규칙은 [기여 가이드](CONTRIBUTING.md)를 따른다.

## 문서 지도

이 README는 현재 상태를 한 번에 파악하는 진입점이다. 아래 문서는 변경 책임이 다른 상세 원본이므로 합치지 않고 유지한다.

| 확인할 내용 | 원본 |
|---|---|
| 전체 제품 범위, MVP, 미결정 사항 | [spec/1-BACKGROUND.md](spec/1-BACKGROUND.md) |
| 사용자 기능과 화면 | [spec/2-1-USER-STORIES.md](spec/2-1-USER-STORIES.md) |
| 관리자 기능 | [spec/2-2-OPERATOR-REQUIREMENTS.md](spec/2-2-OPERATOR-REQUIREMENTS.md) |
| 인증·권한 매트릭스 | [spec/3-1-DESIGN-ARCHITECTURE.md](spec/3-1-DESIGN-ARCHITECTURE.md) |
| DB·API 계약 | [spec/3-2-DESIGN-CONTRACT.md](spec/3-2-DESIGN-CONTRACT.md) |
| 설계 결정과 트레이드오프 | [spec/3-3-DESIGN-DECISIONS.md](spec/3-3-DESIGN-DECISIONS.md) |
| 테스트와 오류 응답 | [spec/5-TESTING.md](spec/5-TESTING.md) |
| 배포 원칙 | [spec/7-DEPLOYMENT.md](spec/7-DEPLOYMENT.md) |
| 인프라·배포·장애 대응 | [docs/README.md](docs/README.md) |
| 이슈·브랜치·커밋·PR | [CONTRIBUTING.md](CONTRIBUTING.md) |

현재 `apps/api`에는 health check가 가능한 서버 기반, `apps/web`에는 최소 시작 화면과 검증 도구가 구성되어 있다. 기능 구현 전에 [미결정 사항](spec/1-BACKGROUND.md#1-5-미결정-사항) 중 해당 기능의 선행 결정을 닫고, 변경된 권한·스키마·API는 코드와 같은 PR에서 문서화한다.
