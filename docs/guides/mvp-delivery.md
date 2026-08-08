[← 문서 인덱스](../README.md)

# MVP 진행 계획

> **은퇴 조건 — 1차 출시 회고가 끝나면 삭제합니다.** 장기 제품 범위는 [`spec/`](../../spec/README.md), 작업 규칙은 [`CONTRIBUTING.md`](../../CONTRIBUTING.md)가 원본입니다.

## 목표

인증·회원 관리·공지 기능으로 1차 운영을 시작한다. 이번 출시는 전체 제품 완성이 아니라 실제 사용자 흐름과 배포 기반을 검증하는 MVP다.

## 범위

| 단계 | 기능 |
|---|---|
| MVP | 가입, 로그인, 로그아웃, 승인 대기 |
| MVP | 관리자 가입 승인, 회원 상태 관리, 회원 관리 화면 |
| MVP | 공지 CRUD, 상단 고정 |
| MVP | 프론트·API 통합, Vercel·ECS Fargate 배포 |
| Post Launch | 자료 CRUD·검색·즐겨찾기, S3 |
| Post Launch | 활동사진 |
| Post Launch | 가입 거부, 회원 제거, 관리자 권한 부여·회수 |

범위의 제품 스펙 원본은 [spec/1-BACKGROUND §1-6](../../spec/1-BACKGROUND.md#1-6-1차-출시-범위)이다.

## 역할

| 담당 | MVP 책임 |
|---|---|
| 경현 | PM, 프론트엔드, API 통합, Vercel |
| 수민 | 공지 백엔드, 인프라 |
| 승원 | 인증·권한·회원 관리 백엔드 |
| Claude 봇 | PR 보조 리뷰 |
| 팀 전체 | 통합 검증, 배포 리허설 |

수민과 승원은 각자 맡은 백엔드 도메인을 병렬로 진행하고 서로의 PR을 리뷰한다. Claude 봇 리뷰는 사람 리뷰를 대체하지 않는다.

## API 협업

백엔드 담당자는 구현 전에 Swagger/OpenAPI에 다음 내용을 먼저 잡고 프론트 담당자와 확인한다.

- Method와 path
- 접근 권한
- 요청·응답 스키마와 예시
- 성공·오류 상태 코드

구현 중 계약이 바뀌면 코드, OpenAPI, [`spec/3-2-DESIGN-CONTRACT.md`](../../spec/3-2-DESIGN-CONTRACT.md)를 같은 PR에서 갱신한다.

## 작업 관리

- GitHub Issues가 백로그의 단일 원본이다.
- `MVP`와 `Post Launch` 마일스톤으로 범위를 나눈다.
- 이슈마다 담당자 한 명과 완료 조건을 둔다.
- 한 사람의 동시 진행 이슈는 최대 2개다.
- PR에는 `Closes #이슈번호`를 적는다.
- 기능 PR 머지 시 이슈 자동 종료를 사용하려면 저장소 default branch를 `develop`으로 설정한다.
- 세부 결정은 별도 결정 이슈로 만들고, 해당 구현 이슈보다 먼저 끝낸다.

브랜치·커밋·리뷰 규칙은 [`CONTRIBUTING.md`](../../CONTRIBUTING.md)를 따른다.

## 완료 기준

이슈는 구현, 테스트, 필요한 문서와 OpenAPI 갱신, 통합 확인, PR 머지가 모두 끝나야 완료다.
