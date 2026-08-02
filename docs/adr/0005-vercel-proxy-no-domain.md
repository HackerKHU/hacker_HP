[← 문서 인덱스](../README.md) · [ADR 목록](.)

# 0005. 도메인 없이 Vercel 프록시로 HTTPS를 우회한다

- **상태**: 승인됨 (임시 조치, 공개 전 재검토 필수)
- **날짜**: 2026-08-01

## 배경

ALB의 기본 DNS(`*.elb.amazonaws.com`)에는 ACM 인증서를 발급할 수 없어 ALB가 HTTP만 제공한다. 프론트엔드는 Vercel(HTTPS)에 있어, 브라우저가 mixed content로 HTTP API 호출을 차단한다. 도메인은 아직 구매 전이다.

## 결정

`apps/web/vercel.json`의 rewrites로 `/api/*` 요청을 Vercel Edge가 ALB(HTTP)로 프록시한다. 브라우저는 Vercel과만 HTTPS로 통신한다.

## 이유

도메인 구매·ACM 발급 없이도 기능 개발과 통합 테스트를 즉시 시작할 수 있다. 부수 효과로 same-origin이 되어 쿠키 설정도 `SameSite=Lax`로 단순해진다.

## 트레이드오프

Vercel↔ALB 구간은 평문 HTTP로 공개 인터넷을 지난다. **비밀번호·시험 자료 등 실제 데이터를 이 상태로 다루면 안 된다.** 도메인 구매 후 ALB에 443 리스너를 추가하고 프록시를 제거해야 하며, 그 전까지는 개발/테스트 용도로만 사용한다 ([ops/deployment.md](../ops/deployment.md), [product/08-open-issues.md](../product/08-open-issues.md)).
