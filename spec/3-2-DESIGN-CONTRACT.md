[← 스펙 인덱스](README.md)

# 3-2. 계약 — 데이터 모델과 API

프론트엔드와 백엔드가 공유하는 계약을 잡아준다. **스키마나 엔드포인트를 바꾸기 전에 이 문서를 확인하고, 바뀌면 같은 PR에서 갱신한다** (MUST). Flyway 마이그레이션(`V*__*.sql`)은 §3-2-2의 정의를 그대로 반영해야 한다.

> **출시 단계** — MVP는 인증, 공지, 회원 목록·승인·상태 변경 API를 우선 구현한다. 가입 거부, 자료·즐겨찾기·사진·회원 제거·권한 변경 API는 계약을 유지하되 `Post Launch`에서 구현한다.

구현된 API는 Swagger UI와 OpenAPI JSON(`/v3/api-docs`)으로 확인할 수 있어야 한다 (MUST). 엔드포인트를 구현하거나 변경하는 PR은 접근 권한, 요청·응답 스키마, 상태 코드와 대표 오류 응답을 OpenAPI 명세에도 함께 반영한다. Swagger는 이 문서를 대체하지 않으며, 이 문서는 기능·권한의 원본이고 OpenAPI는 구현 시점의 상세 계약이다.

```text
§3-2-1   ERD              테이블 관계
§3-2-2   테이블 정의       컬럼과 제약
§3-2-3   API — 인증
§3-2-4   API — 자료·즐겨찾기
§3-2-5   API — 공지·사진
§3-2-6   API — 회원 관리
§3-2-7   공통 에러 코드
§3-2-8   공통 페이지 응답
```

## 3-2-1 ERD

```mermaid
erDiagram
  USERS ||--o{ NOTES : uploads
  USERS ||--o{ BOOKMARKS : saves
  NOTES ||--o{ BOOKMARKS : saved_in
  NOTES ||--o{ NOTE_FILES : has
  USERS ||--o{ NOTICES : writes
  USERS ||--o{ PHOTOS : uploads
```

## 3-2-2 테이블 정의

### users

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| `id` | bigint | PK, auto | |
| `google_sub` | varchar(255) | UNIQUE, NOT NULL | 구글 계정 식별자 (ID 토큰의 `sub`) |
| `email` | varchar(255) | UNIQUE, NOT NULL | 학교 이메일 (`khu.ac.kr`) |
| `student_no` | varchar(20) | UNIQUE, NULL | 학번 (신원 확인용). 신청서 제출 시 채운다 |
| `name` | varchar(50) | NOT NULL | 최초에는 구글 프로필, 신청 시 본인이 정정 |
| `role` | enum | NOT NULL, default `USER` | `USER`, `ADMIN` |
| `status` | enum | NOT NULL, default `PENDING` | `PENDING`, `ACTIVE`, `SUSPENDED` |
| `created_at` | datetime | NOT NULL | 계정 생성일시 (첫 구글 로그인) |
| `applied_at` | datetime | NULL | 신청서 제출일시 |
| `approved_at` | datetime | NULL | 승인일시 |

**비밀번호 컬럼이 없다.** 인증은 구글이 담당하며 자체 비밀번호를 받지도 저장하지도 않는다 ([3-3 결정 13](3-3-DESIGN-DECISIONS.md#3-3-14-결정-13--가입로그인을-구글-oauth로-한다)).

계정의 신원 키는 `email`이 아니라 **`google_sub`** 이다 (MUST). 이메일은 학교 정책에 따라 바뀔 수 있지만 `sub`는 구글 계정에 영구적으로 고정된다. 로그인 시 `google_sub`로 기존 계정을 찾는다.

`student_no`는 NULL을 허용한다. 구글이 학번을 주지 않으므로 계정 생성 시점에는 비어 있고, 신청서 제출 시 채워진다 ([3-1 §3-1-4](3-1-DESIGN-ARCHITECTURE.md)). UNIQUE는 그대로 유지한다 (MUST) — 한 학번으로 여러 계정을 만드는 것을 막는다. PostgreSQL의 UNIQUE는 NULL을 서로 다른 값으로 보므로 미신청 계정이 여럿이어도 충돌하지 않는다.

**승인 대상은 `status = 'PENDING' AND applied_at IS NOT NULL`이다** (MUST). 구글 로그인만 하고 신청하지 않은 계정을 관리자의 승인 목록에서 제외한다.

### 세션 테이블

인가 상태를 서버 세션으로 관리하므로([3-3 결정 12](3-3-DESIGN-DECISIONS.md#3-3-13-결정-12--인증은-jwt-인가-상태는-서버-세션으로-나눈다)) 세션 저장용 테이블이 RDS에 필요하다. Spring Session JDBC가 요구하는 스키마를 쓰며, 컬럼 정의는 이 문서가 아니라 Spring Session 쪽이 원본이다.

`ddl-auto`가 `validate`이므로 이 테이블도 **Flyway 마이그레이션에 포함해야 한다** (MUST). 애플리케이션 테이블이 아니라 인증 기반이므로 위 ERD에는 넣지 않는다.

### notes

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| `id` | bigint | PK, auto | |
| `category` | enum | NOT NULL | `EXAM`, `SUBJECT` |
| `title` | varchar(200) | NOT NULL | |
| `subject_name` | varchar(100) | NOT NULL | 과목명 |
| `professor` | varchar(50) | NULL | 교수명 |
| `year` | int | NOT NULL | 개설 연도 |
| `semester` | enum | NOT NULL | `SPRING`, `FALL` |
| `exam_type` | enum | NULL | `MIDTERM`, `FINAL` |
| `uploader_id` | bigint | FK → users.id | |
| `created_at` | datetime | NOT NULL | |
| `updated_at` | datetime | NOT NULL | |

- 인덱스: `(category, created_at)`, `(subject_name)`, `(year, semester)`
- **CHECK 제약** (MUST): `category = 'EXAM'`이면 `exam_type IS NOT NULL`, `category = 'SUBJECT'`면 `exam_type IS NULL`. 애플리케이션 검증에만 맡기지 않는다.

### note_files

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| `id` | bigint | PK, auto | |
| `note_id` | bigint | FK → notes.id, **ON DELETE CASCADE** | |
| `original_name` | varchar(255) | NOT NULL | 업로드 당시 파일명 |
| `stored_path` | varchar(500) | NOT NULL | S3 오브젝트 키 |
| `size_bytes` | bigint | NOT NULL | |

파일은 S3에만 저장한다. **서버 디스크에 저장하지 않는다** (MUST). 저장 키 형식은 `notes/{uuid}.{ext}`이며, presigned 업로드 시점에는 `note_id`가 아직 없으므로 키에 포함하지 않는다.

### bookmarks

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| `user_id` | bigint | PK, FK → users.id, **ON DELETE CASCADE** | |
| `note_id` | bigint | PK, FK → notes.id, **ON DELETE CASCADE** | |
| `created_at` | datetime | NOT NULL | |

복합 PK `(user_id, note_id)`로 중복 등록을 막는다. 양쪽 FK 모두 CASCADE를 건다 (MUST) — [2-1 §2-1-3](2-1-USER-STORIES.md)이 자료 삭제 시 즐겨찾기도 지워진다고 정의하고 있다.

### notices

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| `id` | bigint | PK, auto | |
| `title` | varchar(200) | NOT NULL | |
| `content` | text | NOT NULL | |
| `is_pinned` | boolean | NOT NULL, default false | 상단 고정 여부 |
| `author_id` | bigint | FK → users.id | |
| `created_at` | datetime | NOT NULL | |
| `updated_at` | datetime | NOT NULL | |

정렬 기준: `is_pinned DESC, created_at DESC`

### photos

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| `id` | bigint | PK, auto | |
| `caption` | varchar(200) | NULL | |
| `stored_path` | varchar(500) | NOT NULL | 리사이즈된 이미지의 S3 오브젝트 키 |
| `uploader_id` | bigint | FK → users.id | |
| `created_at` | datetime | NOT NULL | |

저장 키 형식: `photos/{photoId}/{uuid}.jpg`, 썸네일은 `photos/{photoId}/thumb/{uuid}.jpg`

---

Base path: `/api/v1`. 아래 표의 경로는 모두 이 base path 뒤에 붙는다 — `/auth/me`의 실제 URL은 `/api/v1/auth/me`다.

**경로에 버전을 붙인다** (MUST). 응답 필드를 지우거나 의미를 바꾸는 등 기존 클라이언트를 깨는 변경이 필요하면, `/api/v1`을 고치지 않고 `/api/v2`를 새로 연다 ([3-3 결정 9](3-3-DESIGN-DECISIONS.md#3-3-10-결정-9--api-경로에-버전을-붙인다)). 필드 추가처럼 호환되는 변경은 `v1` 안에서 한다.

버전을 붙이지 않는 경로가 두 개 있다. `/actuator/health`는 ALB 헬스체크가 쓰는 운영 경로이고, `/v3/api-docs`와 Swagger UI는 springdoc이 제공하는 경로다. 둘 다 클라이언트 계약이 아니므로 `/api/v1` 아래에 두지 않는다.

권한 컬럼은 [3-1 §3-1-3](3-1-DESIGN-ARCHITECTURE.md) 매트릭스와 반드시 일치해야 한다 (MUST).

## 3-2-3 API — 인증

| Method | Path | 권한 | 설명 |
|---|---|---|---|
| GET | `/auth/csrf` | 비로그인 | CSRF 토큰 발급 |
| GET | `/oauth2/authorization/google` | 비로그인 | 구글 로그인 시작 (가입 겸용) |
| GET | `/login/oauth2/code/google` | 비로그인 | 구글 콜백. 성공 시 계정 생성 또는 조회 후 세션 발급 |
| POST | `/auth/application` | PENDING | 신청서 제출·수정. body: `{ "studentNo": "...", "name": "..." }` |
| POST | `/auth/logout` | 로그인 | 로그아웃 |
| GET | `/auth/me` | 로그인 | 내 정보 + role/status/신청 여부 |
| POST | `/auth/bootstrap-admin` | 로그인 + **신청서 제출 완료** | 최초 관리자 승격/마지막 관리자 복구. body: `{ "token": "..." }` — [3-3 결정 11](3-3-DESIGN-DECISIONS.md) |

**`POST /auth/bootstrap-admin`은 `applied_at IS NOT NULL`인 계정만 호출할 수 있다** (MUST). 신청서를 내지 않은 계정이 이 경로로 곧장 `ACTIVE`가 되면 `student_no`가 비어 있는 관리자가 만들어지는데, 신청 API는 `PENDING` 전용이라 나중에 채울 방법이 없다. 이 조건이 빠지면 최초 관리자만 학번 없는 계정으로 남는다.

**`POST /auth/signup`과 `POST /auth/login`은 없다.** 자체 비밀번호를 쓰지 않으므로 두 엔드포인트가 사라졌다 ([3-3 결정 13](3-3-DESIGN-DECISIONS.md#3-3-14-결정-13--가입로그인을-구글-oauth로-한다)).

### 구글 OAuth 경로

두 경로는 Spring Security OAuth2 Client가 제공하며, **base path `/api/v1` 아래에 오도록 설정한다** (MUST). 실제 URL은 `/api/v1/oauth2/authorization/google`과 `/api/v1/login/oauth2/code/google`이다.

프레임워크 기본값(`/oauth2/...`, `/login/...`)을 그대로 두면 Vercel rewrites가 `/api/*`만 프록시하므로 브라우저 요청이 ALB에 닿지 않는다 ([3-3 결정 5](3-3-DESIGN-DECISIONS.md#3-3-5-결정-5--도메인-없이-vercel-프록시로-https를-우회한다)). 프록시 규칙을 늘리는 대신 서버 경로를 옮긴다.

구글 콘솔에 등록할 redirect URI도 **프론트엔드 오리진** 기준이다. 브라우저는 Vercel과만 통신하므로 ALB 주소를 등록하면 콜백이 다른 오리진에 떨어져 쿠키가 붙지 않는다.

`GET /auth/me`는 신청서 제출 여부를 함께 반환한다. 프론트엔드가 `PENDING` 사용자에게 신청 폼을 보일지 대기 안내를 보일지 이 값으로 가른다 ([3-1 §3-1-6](3-1-DESIGN-ARCHITECTURE.md)).

### CSRF 토큰

인증 쿠키가 자동 전송되므로 상태를 바꾸는 요청은 CSRF 토큰을 검증한다 ([3-3 결정 12](3-3-DESIGN-DECISIONS.md#3-3-13-결정-12--인증은-jwt-인가-상태는-서버-세션으로-나눈다)).

| 항목 | 값 |
|---|---|
| 쿠키 이름 | `XSRF-TOKEN` — **`httpOnly`가 아니다.** 클라이언트가 읽어 헤더에 실어야 한다 |
| 헤더 이름 | `X-XSRF-TOKEN` |
| 검증 대상 | `POST`, `PATCH`, `DELETE` 등 상태를 바꾸는 모든 요청 |
| 발급 경로 | `GET /auth/csrf` |

**`POST /auth/application`도 검증 대상이다** (MUST). `PENDING` 사용자만 호출할 수 있지만 세션 쿠키가 자동 전송되므로 다른 사이트가 대신 학번을 제출하게 만들 수 있다.

구글 로그인 시작(`GET /oauth2/authorization/google`)은 `GET`이라 CSRF 검증 대상이 아니다. 대신 **OAuth `state` 파라미터가 같은 역할을 한다** (MUST). Spring Security OAuth2 Client가 기본으로 생성·검증하므로 이를 끄지 않는다 — 끄면 로그인 CSRF(피해자를 공격자 계정에 로그인시키는 공격)가 열린다.

세션도 토큰도 없는 최초 진입에는 발급 경로가 필요하다. **클라이언트는 첫 상태 변경 요청 전에 `GET /auth/csrf`를 호출해 `XSRF-TOKEN` 쿠키를 받는다** (MUST). 이 엔드포인트는 응답 본문 없이 쿠키만 내려주며 비로그인으로 접근할 수 있다.

토큰이 없거나 쿠키와 헤더 값이 다르면 `403 FORBIDDEN`을 반환한다.

### 로그인 후 신원 조회

클라이언트는 로그인(구글 콜백 처리)이 끝난 뒤 `GET /auth/me`로 `role`·`status`·신청 여부를 조회한다 (MUST).

신원 조회 경로를 하나로 유지하기 위함이다. 콜백 응답에 사용자 정보를 실으면 같은 값을 두 곳에서 만들게 되고, 새로고침으로 세션을 복구할 때는 어차피 `GET /auth/me`를 쓰므로 흐름이 갈라진다.

## 3-2-4 API — 자료·즐겨찾기

| Method | Path | 권한 | 설명 |
|---|---|---|---|
| GET | `/notes` | ACTIVE | 목록·검색·필터 |
| GET | `/notes/filters` | ACTIVE | 필터 옵션(과목/교수/연도) 목록 |
| GET | `/notes/{id}` | ACTIVE | 상세 |
| POST | `/notes/upload-url` | ACTIVE | 파일별 presigned PUT URL 발급 |
| POST | `/notes` | ACTIVE | 메타데이터 등록 (JSON) — body에 업로드 완료된 파일 키 목록 |
| PATCH | `/notes/{id}` | 본인/ADMIN | 수정 |
| DELETE | `/notes/{id}` | 본인/ADMIN | 삭제 |
| GET | `/notes/{id}/files/{fileId}` | ACTIVE | presigned GET URL 발급 (파일 바이트는 서버를 거치지 않음) |
| GET | `/bookmarks` | ACTIVE | 내 즐겨찾기 목록 |
| POST | `/notes/{id}/bookmark` | ACTIVE | 추가 |
| DELETE | `/notes/{id}/bookmark` | ACTIVE | 해제 |

**`GET /notes` 쿼리 파라미터**

| 이름 | 타입 | 설명 |
|---|---|---|
| `category` | string | `EXAM` \| `SUBJECT` |
| `q` | string | 통합 검색어 (제목/과목/교수) |
| `subject` | string | 과목 필터 |
| `professor` | string | 교수 필터 |
| `year` | int | 연도 필터 |
| `semester` | string | `SPRING` \| `FALL` |
| `examType` | string | `MIDTERM` \| `FINAL` |
| `sort` | string | `latest`(기본) \| `title` |
| `page` | int | 0부터 시작 |
| `size` | int | 기본 20 |

## 3-2-5 API — 공지·사진

| Method | Path | 권한 | 설명 |
|---|---|---|---|
| GET | `/notices` | ACTIVE | 목록 (고정 우선 정렬) |
| GET | `/notices/{id}` | ACTIVE | 상세 |
| POST | `/notices` | ADMIN | 등록 |
| PATCH | `/notices/{id}` | ADMIN | 수정 |
| DELETE | `/notices/{id}` | ADMIN | 삭제 |
| PATCH | `/notices/{id}/pin` | ADMIN | 고정 토글 |
| GET | `/photos` | ACTIVE | 목록 |
| POST | `/photos` | ADMIN | 다중 업로드 (multipart, 서버 리사이즈) |
| DELETE | `/photos/{id}` | ADMIN | 삭제 |

> `POST /photos`의 업로드 경로는 미결정이다 ([1-BACKGROUND §1-5](1-BACKGROUND.md) #5).

## 3-2-6 API — 회원 관리

| Method | Path | 권한 | 설명 |
|---|---|---|---|
| GET | `/admin/users` | ADMIN | 목록 — `status`, `role`, `q`, `sort`, `page`, `size` |
| POST | `/admin/users/approve` | ADMIN | 일괄 승인 — body: `{ "userIds": [1,2,3] }` |

**`GET /admin/users`의 "가입 신청일"은 `applied_at`이다** (MUST). `created_at`(첫 구글 로그인)이 아니다 — 표시·정렬 모두 `applied_at`을 쓴다 ([2-2 §2-2-1](2-2-OPERATOR-REQUIREMENTS.md)). 응답에는 두 값을 모두 담되 화면이 무엇을 "신청일"로 부르는지 어긋나지 않게 한다.

**승인 대상 목록은 `status = 'PENDING' AND applied_at IS NOT NULL`로 거른다** (MUST). 신청하지 않은 계정은 승인 UI에 나타나지 않는다.

`POST /admin/users/approve`에 신청하지 않은 계정의 id가 섞여 오면 그 건은 실패로 집계한다 (MUST). 목록에서 걸렀더라도 API를 직접 부르는 경로가 남아 있다.
| POST | `/admin/users/reject` | ADMIN | 일괄 거부 — body: `{ "userIds": [1,2,3] }` |
| PATCH | `/admin/users/{id}/status` | ADMIN | `ACTIVE` ↔ `SUSPENDED` (본인을 `SUSPENDED`로: 마지막 활성 관리자면 차단) |
| PATCH | `/admin/users/{id}/role` | ADMIN | 권한 부여/회수 (본인 대상: 마지막 활성 관리자면 차단) |
| DELETE | `/admin/users/{id}` | ADMIN | 회원 제거 (본인 대상: 마지막 활성 관리자면 차단) |

## 3-2-7 공통 에러 코드

| HTTP | 코드 | 상황 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | 입력값 검증 실패 |
| 401 | `UNAUTHENTICATED` | 미로그인 |
| 403 | `PENDING_APPROVAL` | `PENDING` 사용자의 일반 API 접근 |
| 403 | `SUSPENDED` | 정지된 계정의 로그인 시도 |
| 403 | `FORBIDDEN` | 권한 부족 / 마지막 활성 관리자의 본인 권한 회수·삭제·정지 시도 / 허용 도메인이 아닌 구글 계정의 로그인 |
| 404 | `NOT_FOUND` | 리소스 없음 |
| 409 | `DUPLICATE_STUDENT_NO` | 신청서의 학번이 다른 계정에 이미 쓰이고 있음 |
| 413 | `FILE_TOO_LARGE` | 파일 용량 초과 |
| 415 | `UNSUPPORTED_FILE_TYPE` | 허용되지 않는 확장자 |

에러 응답은 커스텀 예외 + `@RestControllerAdvice`로 일괄 처리한다 (MUST). 응답 형식은 [5-TESTING §5-4](5-TESTING.md)에 있다.

## 3-2-8 공통 페이지 응답

목록 API는 모두 페이지 응답을 쓴다. MVP는 `GET /notices`, `GET /admin/users`, `Post Launch`는 `GET /notes`, `GET /bookmarks`, `GET /photos`가 대상이다.

공통 요청 파라미터는 `page`(0부터 시작), `size`(기본 20)다.

응답 형태는 Spring Data `PagedModel`로 고정한다 (MUST).

```json
{
  "content": [],
  "page": { "size": 20, "number": 0, "totalElements": 300, "totalPages": 15 }
}
```

서버는 `@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)`를 전역에 한 번 적용한다 (MUST).

`Page` 객체를 그대로 직렬화하지 않는다 (MUST). Spring 3.3+는 이 방식의 구조 안정성을 보장하지 않고 경고를 남기며, `pageable`·`sort`·`offset` 같은 내부 구현 필드가 응답에 노출된다.

---
[← 이전: 아키텍처](3-1-DESIGN-ARCHITECTURE.md) · [다음: 결정 기록 →](3-3-DESIGN-DECISIONS.md)
