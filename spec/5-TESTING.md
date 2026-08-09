[← 스펙 인덱스](README.md)

# 5. 테스트와 오류 처리

무엇을 검증해야 머지할 수 있는지, 오류가 났을 때 어떻게 응답하는지를 잡아준다. **이 문서에 적힌 검증을 통과하지 못한 PR은 머지하지 않는다** (MUST).

> **출시 단계** — MVP에서는 인증·회원 관리·공지에 해당하는 테스트를 출시 조건으로 삼는다. 자료·즐겨찾기·S3·사진·회원 제거·권한 변경 테스트는 해당 `Post Launch` 기능을 구현하는 PR부터 적용한다. 구현되지 않은 기능의 테스트 때문에 MVP 출시를 막지 않되, 구현한 기능에 해당하는 MUST 테스트는 미룰 수 없다.

```text
§5-1   테스트 범위       무엇을 테스트하고 무엇을 안 하는가
§5-2   필수 테스트 사례   권한·경계 조건 중심
§5-3   수동 검증         자동화하기 어려운 것
§5-4   오류 응답 규칙     형식과 로깅
```

## 5-1 테스트 범위

동아리 프로젝트다. **커버리지 목표를 세우지 않는다.** 대신 **틀리면 사고가 나는 것**만 반드시 테스트한다.

| 대상 | 테스트 | 이유 |
|---|---|---|
| 권한 검사 | **MUST** | 틀리면 미승인자가 시험 자료를 본다 |
| 소유자 검사 (본인 것만 수정·삭제) | **MUST** | 틀리면 남의 자료가 지워진다 |
| 마지막 관리자의 본인 권한 회수·삭제·정지 차단 | **MUST** | 틀리면 시스템에 아무도 못 들어간다 |
| 관리자 부트스트랩(이메일+토큰) | **MUST** | 틀리면 이메일만 아는 공격자가 관리자를 탈취한다 |
| Status 전이 (`PENDING`/`SUSPENDED` 차단) | **MUST** | 틀리면 승인제가 무의미해진다 |
| presigned URL 발급 조건 (용량·확장자) | **MUST** | 틀리면 임의 파일이 버킷에 쌓인다 |
| 검색·필터 조합 | SHOULD | 틀리면 불편하지만 사고는 아니다 |
| 페이지네이션 경계 | SHOULD | |
| DTO 매핑, getter | 안 한다 | 프레임워크를 테스트하는 것이다 |

**API 계층은 통합 테스트로 검증한다** (SHOULD). Spring Security 필터를 태우지 않는 단위 테스트는 권한 버그를 잡지 못한다. `@SpringBootTest` + Testcontainers(또는 CI의 postgres service container)를 쓴다.

## 5-2 필수 테스트 사례

### 인증·권한

| # | 조건 | 기대 |
|---|---|---|
| T-01 | 비로그인으로 `GET /notices` | `401 UNAUTHENTICATED` |
| T-02 | `PENDING` 사용자로 `GET /notices` | `403 PENDING_APPROVAL` |
| T-03 | `SUSPENDED` 사용자로 로그인 | `403 SUSPENDED`, 세션 미발급 |
| T-04 | `USER`로 `POST /notices` | `403 FORBIDDEN` |
| T-05 | `USER`로 `GET /admin/users` | `403 FORBIDDEN` |
| T-06 | 없는 이메일로 로그인 / 틀린 비밀번호로 로그인 | **두 응답이 동일해야 한다** |
| T-07 | 가입 시 이메일 중복 | `409 DUPLICATE_EMAIL` |
| T-08 | 가입 시 허용 도메인이 아닌 이메일 | `400 VALIDATION_ERROR` |

### 소유자·안전장치

| # | 조건 | 기대 |
|---|---|---|
| T-09 | A가 올린 자료를 B(`USER`)가 `PATCH` | `403 FORBIDDEN` |
| T-10 | A가 올린 자료를 관리자가 `PATCH` | 성공 |
| T-11 | 활성 관리자가 **1명**일 때, 그 관리자가 자기 자신의 role을 `USER`로 변경 | `403 FORBIDDEN` |
| T-12 | 활성 관리자가 **2명**일 때, 한 명이 자기 자신의 role을 `USER`로 변경 | 성공 (활성 관리자 1명 남음) |
| T-13 | 활성 관리자가 1명일 때, 자기 자신을 `DELETE` | `403 FORBIDDEN` |
| T-14 | 활성 관리자가 1명일 때, 자기 자신의 status를 `SUSPENDED`로 변경 | `403 FORBIDDEN` |
| T-15 | 활성 관리자 2명이 **동시에** 각자 자기 자신을 회수/삭제/정지 시도 | 최소 한쪽은 실패한다 — 활성 관리자가 0명이 되면 안 된다 (MUST, [2-2 §2-2-7](2-2-OPERATOR-REQUIREMENTS.md) 원자성 요구사항) |

### 관리자 부트스트랩

| # | 조건 | 기대 |
|---|---|---|
| T-16 | 활성 관리자 0명, 이메일·토큰 모두 `ADMIN_BOOTSTRAP_EMAIL`/`ADMIN_BOOTSTRAP_TOKEN`과 일치 | 승격 성공 — `role=ADMIN`, `status=ACTIVE`, `approved_at` 기록됨 |
| T-17 | 활성 관리자 0명, 이메일은 일치하나 토큰 불일치 | `403 FORBIDDEN` |
| T-18 | 활성 관리자 0명, `ADMIN_BOOTSTRAP_EMAIL`과 다른 이메일의 사용자가 올바른 토큰으로 호출 | `403 FORBIDDEN` |
| T-19 | 활성 관리자가 이미 1명 이상 존재할 때 올바른 토큰으로 호출 | `403 FORBIDDEN` |

### 데이터 무결성

| # | 조건 | 기대 |
|---|---|---|
| T-20 | 자료 삭제 | `note_files`·`bookmarks` 레코드가 함께 사라진다 |
| T-21 | 같은 `(user, note)`로 즐겨찾기 두 번 | 두 번째는 실패하거나 멱등 처리 |
| T-22 | `category=EXAM`인데 `exam_type=NULL`로 INSERT | DB CHECK 제약 위반 |
| T-23 | 같은 학번으로 두 번 가입 | UNIQUE 제약 위반 |

### 파일

| # | 조건 | 기대 |
|---|---|---|
| T-24 | 20MB 초과 파일로 presigned URL 요청 | `413 FILE_TOO_LARGE` |
| T-25 | 허용되지 않은 확장자로 요청 | `415 UNSUPPORTED_FILE_TYPE` |
| T-26 | presigned URL 유효시간 경과 후 접근 | S3가 거부 |
| T-27 | S3 오브젝트 키를 직접 브라우저로 열기 | 403 (버킷 비공개) |

**T-24는 발급 조건만 검사하는 것으로 부족하다.** presigned PUT은 실제 업로드 용량을 강제하지 못하므로, 등록(③) 단계에서 실제 오브젝트 크기를 확인하는 경로까지 테스트한다 (MUST) — [2-1 §2-1-2](2-1-USER-STORIES.md).

## 5-3 수동 검증

자동화 비용이 큰 것들이다. 관련 기능을 건드린 PR에서 확인하고 결과를 본문에 적는다 (MUST).

- [ ] 로컬 `docker compose up -d` 후 `./gradlew bootRun` → DB 연결 확인
- [ ] `curl localhost:8080/actuator/health` → `{"status":"UP"}`
- [ ] 브라우저에서 파일 업로드 → S3 콘솔에서 오브젝트 확인 → 다운로드 링크로 열림
- [ ] 승인 대기 계정으로 로그인 → 대기 안내 화면으로 리다이렉트되는지
- [ ] 관리자 화면 URL을 `USER` 계정으로 직접 입력 → 접근 차단되는지
- [ ] 배포 후 `curl http://<ALB_DNS>/actuator/health` → 200

## 5-4 오류 응답 규칙

- 모든 오류는 커스텀 예외 + `@RestControllerAdvice`로 일괄 처리한다 (MUST). 컨트롤러에서 `try-catch`로 응답을 만들지 않는다.
- 응답 본문 형식:

  ```json
  { "code": "PENDING_APPROVAL", "message": "승인 대기 중인 계정입니다." }
  ```

- `message`는 사용자에게 보여줄 수 있는 문장으로 쓴다 (MUST). **스택 트레이스, SQL, 내부 경로를 응답에 담지 않는다.**
- 인증 실패 메시지는 원인을 구분하지 않는다 (MUST) — T-06.
- 서버 오류(5xx)는 로그에 스택 트레이스를 남기고, 응답에는 일반 메시지만 반환한다.
- 코드 목록은 [3-2 §3-2-7](3-2-DESIGN-CONTRACT.md)이 원본이다. 새 코드를 추가하면 그 표를 같은 PR에서 갱신한다 (MUST).

---
[← 이전: 결정 기록](3-3-DESIGN-DECISIONS.md) · [다음: 배포 →](7-DEPLOYMENT.md)
