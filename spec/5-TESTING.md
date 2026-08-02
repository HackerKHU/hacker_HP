[← 스펙 인덱스](README.md)

# 5. 테스트와 오류 처리

무엇을 검증해야 머지할 수 있는지, 오류가 났을 때 어떻게 응답하는지를 잡아준다. **이 문서에 적힌 검증을 통과하지 못한 PR은 머지하지 않는다** (MUST).

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
| 본인 권한 회수·삭제 차단 | **MUST** | 틀리면 시스템에 아무도 못 들어간다 |
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
| T-01 | 비로그인으로 `GET /notes` | `401 UNAUTHENTICATED` |
| T-02 | `PENDING` 사용자로 `GET /notes` | `403 PENDING_APPROVAL` |
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
| T-11 | 관리자가 **자기 자신**의 role을 `USER`로 변경 | `403 FORBIDDEN` |
| T-12 | 관리자가 **자기 자신**을 `DELETE` | `403 FORBIDDEN` |

### 데이터 무결성

| # | 조건 | 기대 |
|---|---|---|
| T-13 | 자료 삭제 | `note_files`·`bookmarks` 레코드가 함께 사라진다 |
| T-14 | 같은 `(user, note)`로 즐겨찾기 두 번 | 두 번째는 실패하거나 멱등 처리 |
| T-15 | `category=EXAM`인데 `exam_type=NULL`로 INSERT | DB CHECK 제약 위반 |
| T-16 | 같은 학번으로 두 번 가입 | UNIQUE 제약 위반 |

### 파일

| # | 조건 | 기대 |
|---|---|---|
| T-17 | 20MB 초과 파일로 presigned URL 요청 | `413 FILE_TOO_LARGE` |
| T-18 | 허용되지 않은 확장자로 요청 | `415 UNSUPPORTED_FILE_TYPE` |
| T-19 | presigned URL 유효시간 경과 후 접근 | S3가 거부 |
| T-20 | S3 오브젝트 키를 직접 브라우저로 열기 | 403 (버킷 비공개) |

**T-17은 발급 조건만 검사하는 것으로 부족하다.** presigned PUT은 실제 업로드 용량을 강제하지 못하므로, 등록(③) 단계에서 실제 오브젝트 크기를 확인하는 경로까지 테스트한다 (MUST) — [2-1 §2-1-2](2-1-USER-STORIES.md).

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
