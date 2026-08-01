> 상태: 초안 | 최종수정: 2026-08-01 | 담당: @somsumun

[← 문서 인덱스](../README.md)

# 미결정 사항

| # | 항목 | 비고 |
|---|---|---|
| 1 | 허용 학교 이메일 도메인 | 실제 도메인 확정 필요 |
| 2 | ~~파일 저장소~~ | ✅ 확정 — S3 ([adr/0004-ssm-parameter-store.md](../adr/0004-ssm-parameter-store.md), [ops/infra.md](../ops/infra.md)) |
| 3 | 세션 방식 | 서버 세션(쿠키) vs JWT |
| 4 | 최초 관리자 계정 생성 방법 | DB 직접 삽입 또는 초기화 스크립트 |
| 5 | 탈퇴/삭제 회원의 업로드 자료 처리 | 유지 권장 (업로더 표시만 대체) |
| 6 | ~~기술 스택~~ | ✅ 확정 — Spring Boot 3.5/Java 21, React 19+TS+Vite, PostgreSQL 16 ([guides/claude-code-setup.md](../guides/claude-code-setup.md)) |

---
[← 이전: 비기능 요구사항](07-non-functional.md) · [문서 인덱스로](../README.md)
