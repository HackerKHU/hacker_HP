[← 문서 인덱스](../README.md) · [ADR 목록](.)

# 0004. 시크릿은 SSM Parameter Store로 관리한다

- **상태**: 승인됨
- **날짜**: 2026-08-01

## 배경

DB 비밀번호, JWT 시크릿 등을 어디에 둘지 결정해야 했다. 후보는 AWS Secrets Manager와 SSM Parameter Store(SecureString)였다.

## 결정

SSM Parameter Store의 `SecureString` 타입으로 시크릿을 관리하고, ECS 태스크 정의의 `secrets` 블록에서 직접 참조한다. Secrets Manager는 쓰지 않는다.

## 이유

SecureString은 Standard 티어에서 완전히 무료다. Secrets Manager는 시크릿 하나당 월 0.4달러가 청구된다. 이 프로젝트가 필요로 하는 자동 로테이션 같은 Secrets Manager 전용 기능은 지금 단계에서 불필요하다. 어느 쪽이든 앱 코드에 시크릿이 하드코딩되지 않는다는 목표는 동일하게 달성된다.

## 트레이드오프

자동 로테이션, 크로스 계정 공유 같은 Secrets Manager의 고급 기능을 포기한다. 필요해지면 마이그레이션은 Terraform 리소스 교체 수준이라 크지 않다.
