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

**미합의 항목을 이 문서에 쌓아 두지 않는다.** 계약이 말하지 않는 것을 발견하면 **이슈로 올리고**, 정해지면 그 내용을 해당 절에 바로 적는다. 예전에는 §3-2-9에 모아 두었는데, 확정한 뒤 지우는 것을 놓치거나 오래된 브랜치가 되살려 **이미 정해진 것이 미정으로 보이는 일이 생겼다** (2026-08-15, #140).

## 3-2-1 ERD

```mermaid
erDiagram
  USERS |o--o{ NOTES : uploads
  USERS ||--o{ BOOKMARKS : saves
  NOTES ||--o{ BOOKMARKS : saved_in
  NOTES ||--o{ NOTE_FILES : has
  USERS |o--o{ NOTICES : writes
  USERS |o--o{ PHOTOS : uploads
```

`admin_actions`는 ERD에 넣지 않는다. `users`를 가리키지만 **FK가 없어 관계가 아니고**, 그렇게 둔 이유가 바로 "이력은 현재 상태에 종속되지 않는다"이기 때문이다 — 선으로 이으면 정반대로 읽힌다.

**작성자 쪽이 `|o`(0 또는 1)인 것은 오타가 아니다.** 회원을 지워도 자료·공지·사진은 남고 작성자만 비므로([2-2 §2-2-4](2-2-OPERATOR-REQUIREMENTS.md#2-2-4-회원-제거)), 작성자가 없는 행이 정상으로 존재한다. `BOOKMARKS`만 `||`인데, 즐겨찾기는 주인과 함께 사라져 주인 없는 행이 생기지 않기 때문이다.

## 3-2-2 테이블 정의

> **아래 표는 DB 컬럼 이름이다. JSON 응답의 필드 이름과는 층위가 다르다** — 컬럼은 `is_pinned`, JSON은 `isPinned`다. 서버는 Jackson을 Spring Boot 기본값(Java 필드명 그대로)으로 직렬화한다 — 엔티티·DTO를 camelCase로 쓰므로 JSON도 자연히 camelCase다 (확정, 2026-08-13, #33).

### 작성자를 내려주는 규칙

자료·공지·활동사진의 작성자를 응답에 담을 때는 **`uploaderName`**(자료·사진)과 **`authorName`**(공지)을 쓴다 — `string`, **null이 아니다** (MUST).

작성자 행이 없으면(`uploader_id`/`author_id`가 `NULL`) 서버가 그 자리에 **`"탈퇴한 회원"`** 을 넣는다. **`null`을 내려보내고 화면이 알아서 채우게 하지 않는다** — 화면마다 다른 문구를 쓰게 되고, 문구를 바꾸려면 웹을 배포해야 한다.

`uploaderId`/`authorId`를 함께 내릴 때는 그쪽이 `null`이 될 수 있다. **"본인 것만 수정·삭제" 판단은 id로 한다** ([3-1 §3-1-3](3-1-DESIGN-ARCHITECTURE.md)) — 이름으로 견주면 "탈퇴한 회원"끼리 서로의 자료를 지울 수 있다.

근거는 [2-2 §2-2-4](2-2-OPERATOR-REQUIREMENTS.md#2-2-4-회원-제거)다.

### users

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| `id` | bigint | PK, auto | |
| `google_sub` | varchar(255) | UNIQUE, NOT NULL | 구글 계정 식별자 (ID 토큰의 `sub`) |
| `email` | varchar(255) | UNIQUE, NOT NULL | 학교 이메일 (`khu.ac.kr`) |
| `student_no` | varchar(20) | UNIQUE, NULL | 학번 (신원 확인용). 신청서 제출 시 채운다 |
| `name` | varchar(50) | NOT NULL | 최초에는 구글 프로필, 신청 시 본인이 정정 |
| `department` | varchar(50) | NULL | 학과. 정해진 목록에서 선택 (자유 입력 아님). 신청서 제출 시 채운다 |
| `role` | enum | NOT NULL, default `USER` | `USER`, `ADMIN` |
| `status` | enum | NOT NULL, default `PENDING` | `PENDING`, `ACTIVE`, `SUSPENDED` |
| `created_at` | datetime | NOT NULL | 계정 생성일시 (첫 구글 로그인) |
| `applied_at` | datetime | NULL | 신청서 제출일시 |
| `approved_at` | datetime | NULL | 승인일시 |
| `version` | bigint | NOT NULL, default 0 | 낙관적 잠금용. 아래 참고 |

**비밀번호 컬럼이 없다.** 인증은 구글이 담당하며 자체 비밀번호를 받지도 저장하지도 않는다 ([3-3 결정 13](3-3-DESIGN-DECISIONS.md#3-3-14-결정-13--가입로그인을-구글-oauth로-한다)).

계정의 신원 키는 `email`이 아니라 **`google_sub`** 이다 (MUST). 이메일은 학교 정책에 따라 바뀔 수 있지만 `sub`는 구글 계정에 영구적으로 고정된다. 로그인 시 `google_sub`로 기존 계정을 찾는다.

**기존 계정에 붙일 때 구글이 준 이메일이 저장된 값과 다르면 `email`을 갱신한다** (MUST). 갱신하지 않으면 회원 목록·검색과 `GET /auth/me`에 옛 주소가 남아, `google_sub`를 신원 키로 고른 이유가 절반만 실현된다. 갱신된 이메일도 허용 도메인 검증을 통과해야 한다.

**새 이메일이 다른 계정에 이미 쓰이고 있으면 로그인을 거부한다** (MUST). 학교가 주소를 회수해 다른 사람에게 재할당하면 `email` UNIQUE에 걸린다. 이때 **이메일로 기존 행을 찾아 붙이지 않는다** — 서로 다른 구글 계정이 한 사용자로 합쳐져 남의 자료에 접근하게 된다.

`/login?error=failed`로 되돌리고 서버 로그에 두 계정의 `id`를 남긴다. 관리자가 어느 쪽이 유효한 계정인지 판단해 정리해야 하는 상황이며, 자동으로 해결하지 않는다.

`student_no`는 NULL을 허용한다. 구글이 학번을 주지 않으므로 계정 생성 시점에는 비어 있고, 신청서 제출 시 채워진다 ([3-1 §3-1-4](3-1-DESIGN-ARCHITECTURE.md)). UNIQUE는 그대로 유지한다 (MUST) — 한 학번으로 여러 계정을 만드는 것을 막는다. PostgreSQL의 UNIQUE는 NULL을 서로 다른 값으로 보므로 미신청 계정이 여럿이어도 충돌하지 않는다.

**`department`는 정해진 목록에서만 고른다** (MUST) — 자유 입력이 아니다. `컴공`/`컴퓨터공학과`/`소프트웨어융합대학 컴퓨터공학부`처럼 표기가 제각각이 되면 회원 목록에서 학과로 걸러보는 것이 사실상 불가능해진다. 목록은 경희대 서울·국제캠퍼스 전체 학과이며, 별도 관리 화면 없이 `apps/api`의 `Department` 클래스(`domain/user/entity` 패키지)에 코드로 고정한다 — 학과 개편이 잦지 않아 관리 화면을 따로 둘 만큼의 빈도가 아니다.

**지금 이 목록은 세 곳에 복제되어 있다** — 위 `Department.ALL`, `V3__add_department.sql`의 CHECK 제약, 그리고 신청 화면이 쓰는 `apps/web`의 `features/auth/departments.ts`다. 서버가 목록을 내려주는 API가 없어서 화면이 사본을 갖는다. **한 곳만 고치면 그 학과 지원자의 가입이 막힌다** — 웹에만 있으면 `400`, CHECK에만 없으면 저장이 터진다. 세 벌이 어긋나면 `apps/web`의 `departments.test.ts`가 원본 파일을 직접 읽어 CI에서 실패시킨다. 사본을 없애는 것은 `GET /api/v1/departments`를 만드는 별도 작업이다 (#166).

신규 신청은 `department`를 **필수**로 받는다 (MUST) — 관리자가 승인 심사에서 실제로 참고하는 값이다. 다만 **컬럼 자체는 `NULL`을 허용한다**: 이 필드가 생기기 전에 이미 승인된 기존 회원은 값이 없고, 일괄 채우지 않는다 — 잘못 추정한 기본값을 넣느니 비워 두고 개별적으로 보완하는 쪽을 택했다. 그래서 "필수"는 DB 제약이 아니라 `POST /auth/application`의 검증이 담당한다 (아래).

**승인 대상은 `status = 'PENDING' AND applied_at IS NOT NULL`이다** (MUST). 구글 로그인만 하고 신청하지 않은 계정을 관리자의 승인 목록에서 제외한다.

`version`은 개인정보가 아니라 **동시성 제어용 컬럼**이다. 신청서 제출과 관리자 승인이 같은 행을 동시에 고칠 때 한쪽만 성공하게 만든다 ([3-1 §3-1-4](3-1-DESIGN-ARCHITECTURE.md)의 직렬화 요구). 상태 검사만으로는 두 트랜잭션이 각자 읽어둔 값을 보고 모두 통과하므로, 나중에 쓰는 쪽이 앞의 변경을 덮는다.

### 세션 테이블

인가 상태를 서버 세션으로 관리하므로([3-3 결정 12](3-3-DESIGN-DECISIONS.md#3-3-13-결정-12--인증은-jwt-인가-상태는-서버-세션으로-나눈다)) 세션 저장용 테이블이 RDS에 필요하다. Spring Session JDBC가 요구하는 스키마를 쓰며, 컬럼 정의는 이 문서가 아니라 Spring Session 쪽이 원본이다.

`ddl-auto`가 `validate`이므로 이 테이블도 **Flyway 마이그레이션에 포함해야 한다** (MUST). 애플리케이션 테이블이 아니라 인증 기반이므로 위 ERD에는 넣지 않는다.

`V2__session.sql`이 그 마이그레이션이며, 내용은 `spring-session-jdbc` jar의 `schema-postgresql.sql`을 그대로 옮긴 것이다. **손으로 고치지 않는다** — 컬럼 하나만 어긋나도 세션 저장이 런타임에 실패한다. Spring Session 버전을 올릴 때 그 jar의 스키마와 대조하고, 달라졌으면 새 마이그레이션을 만든다. 스키마 생성은 Flyway가 맡으므로 `spring.session.jdbc.initialize-schema`는 `never`다 — 둘 다 만들려 들면 기동이 실패한다.

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
| `uploader_id` | bigint | NULL, FK → users.id, **ON DELETE SET NULL** | `NULL`이면 탈퇴한 회원 |
| `created_at` | datetime | NOT NULL | |
| `updated_at` | datetime | NOT NULL | |

- 인덱스: `(category, created_at)`, `(subject_name)`, `(year, semester)`
- **CHECK 제약** (MUST): `category = 'EXAM'`이면 `exam_type IS NOT NULL`, `category = 'SUBJECT'`면 `exam_type IS NULL`. 애플리케이션 검증에만 맡기지 않는다.
- `uploader_id`는 **`ON DELETE SET NULL`이다** (MUST) — [2-2 §2-2-4](2-2-OPERATOR-REQUIREMENTS.md#2-2-4-회원-제거)가 회원을 지워도 자료는 남긴다고 정했다. 기본값(`NO ACTION`)으로 두면 자료를 올린 회원은 삭제 자체가 FK 위반으로 막힌다.

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

**작성자 FK가 `SET NULL`인데 여기만 `CASCADE`인 이유** — 즐겨찾기는 그 사람이 *본* 기록이지 *남긴* 것이 아니다. 주인이 없어지면 남길 이유도 없다 ([2-2 §2-2-4](2-2-OPERATOR-REQUIREMENTS.md#2-2-4-회원-제거)).

### notices

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| `id` | bigint | PK, auto | |
| `title` | varchar(200) | NOT NULL | |
| `content` | text | NOT NULL | |
| `is_pinned` | boolean | NOT NULL, default false | 상단 고정 여부 |
| `author_id` | bigint | NULL, FK → users.id, **ON DELETE SET NULL** | `NULL`이면 탈퇴한 회원 |
| `created_at` | datetime | NOT NULL | |
| `updated_at` | datetime | NOT NULL | |

정렬 기준: `is_pinned DESC, created_at DESC`

`author_id`도 `notes.uploader_id`와 같은 이유로 **`ON DELETE SET NULL`이다** (MUST). 공지는 동아리의 기록이라 작성한 관리자가 나가도 남아야 한다 ([2-2 §2-2-4](2-2-OPERATOR-REQUIREMENTS.md#2-2-4-회원-제거)). **`V1__init.sql`은 `ON DELETE` 절 없이 만들어졌으므로 회원 제거 기능(#58)에서 마이그레이션으로 맞춘다.**

### photos

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| `id` | bigint | PK, auto | |
| `caption` | varchar(200) | NULL | |
| `stored_path` | varchar(500) | NOT NULL | 리사이즈된 이미지의 S3 오브젝트 키 |
| `uploader_id` | bigint | NULL, FK → users.id, **ON DELETE SET NULL** | `NULL`이면 탈퇴한 회원 |
| `created_at` | datetime | NOT NULL | |

저장 키 형식: `photos/{photoId}/{uuid}.jpg`, 썸네일은 `photos/{photoId}/thumb/{uuid}.jpg`. 업로드 경로(원본을 어디에 잠깐 두고 어떻게 리사이즈본으로 바뀌는지)는 [1-BACKGROUND §1-5](1-BACKGROUND.md) 확정 사항, API는 아래 `POST /photos/upload-url`·`POST /photos`를 따른다.

`uploader_id`는 `ADMIN`만 채워진다(사진 업로드는 `ADMIN` 전용이다). 그래도 **`ON DELETE SET NULL`이다** (MUST) — 관리자도 삭제 대상이 될 수 있고, 활동사진은 아카이브라 남아야 한다.

### admin_bootstrap_attempts

관리자 승격 시도 (#144). 한 행은 **"자리를 잡았다"** 는 뜻이고, 승격에 성공하면 그 계정의 것을 지우므로 남아 있는 것은 실질적으로 실패한 시도다. 성공한 조작은 `admin_actions`에 남는다 — 목적도 보존 주기도 달라 섞지 않는다.

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| `id` | bigint | PK, auto | |
| `account_id` | bigint | NOT NULL, **FK 없음** | 시도한 계정 |
| `created_at` | datetime | NOT NULL | |

- 인덱스: `(account_id, created_at)`, `(created_at)`

`admin_actions`와 같은 이유로 **`users` FK가 없다** — 잠금 판단이 `users`의 생명주기에 묶이면 안 된다. **창을 벗어난 행은 판단에 쓰이지 않으므로 지운다.**

### admin_actions

관리자 조작 이력 ([2-2 §2-2-7](2-2-OPERATOR-REQUIREMENTS.md#2-2-7-안전장치)). "누가 누구를 언제 정지했나"에 답하기 위한 것이다.

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| `id` | bigint | PK, auto | |
| `actor_id` | bigint | NOT NULL, **FK 없음** | 조작한 관리자 |
| `target_id` | bigint | NOT NULL, **FK 없음** | 대상 |
| `action` | enum | NOT NULL | `APPROVE`, `SUSPEND`, `ACTIVATE`, `PROMOTE_ADMIN` |
| `created_at` | datetime | NOT NULL | |

- 인덱스: `(target_id, created_at DESC)`, `(actor_id, created_at DESC)`

**여기만 `users`를 가리키는 FK가 없다** (MUST). 다른 테이블처럼 `ON DELETE SET NULL`을 걸면 **회원을 지우는 순간 "누구를 정지했는지"가 사라져** 이력의 존재 이유가 무너진다. 자료·공지와 성격이 다르다 — 그쪽은 보여줄 콘텐츠라 작성자 표시가 필요하지만, **이력은 일어난 일의 기록이라 현재 상태에 종속되면 안 된다.**

**이름·이메일 같은 스냅샷은 두지 않는다** (MUST). 계정을 지운 뒤에도 개인정보가 남는다 ([§2-2-4](2-2-OPERATOR-REQUIREMENTS.md#2-2-4-회원-제거)). 남는 것은 숫자 id뿐이다.

**고쳐 쓰지 않는다.** 이력은 일어난 일이라 나중에 달라질 수 없다.

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
| POST | `/auth/application` | PENDING | 신청서 제출·수정. body: `{ "studentNo": "...", "name": "...", "department": "..." }` |
| POST | `/auth/logout` | 로그인 | 로그아웃 |
| GET | `/auth/me` | **비로그인 포함 전체** | 로그인이면 내 정보, 아니면 `204` |
| POST | `/auth/bootstrap-admin` | 로그인 + **신청서 제출 완료** | 최초 관리자 승격/마지막 관리자 복구. body: `{ "token": "..." }` — [3-3 결정 11](3-3-DESIGN-DECISIONS.md) |

**`POST /auth/signup`과 `POST /auth/login`은 없다.** 자체 비밀번호를 쓰지 않으므로 두 엔드포인트가 사라졌다 ([3-3 결정 13](3-3-DESIGN-DECISIONS.md#3-3-14-결정-13--가입로그인을-구글-oauth로-한다)).

### `POST /auth/application` — 신청서

**`studentNo`·`name`·`department`는 공백이 아니어야 한다** (MUST). 셋 중 하나라도 비었거나 공백뿐이면 `400 VALIDATION_ERROR`를 반환하고 **`applied_at`을 기록하지 않는다** (MUST).

PostgreSQL의 `NOT NULL`·`UNIQUE`는 빈 문자열을 거부하지 않는다. 검증이 없으면 `""`를 제출한 계정이 `applied_at`을 얻어 승인 대상이 되고, 식별 정보가 없는 채로 관리자 부트스트랩까지 통과한다.

**`department`는 정해진 목록에 있는 값만 받는다** (MUST). 목록에 없는 값이면 `400 VALIDATION_ERROR`다 — 자유 입력을 허용하면 §3-2-2가 막으려는 표기 불일치가 API 쪽에서 다시 생긴다.

### `POST /auth/bootstrap-admin` — 신청 완료 계정만

**`applied_at IS NOT NULL`인 계정만 호출할 수 있다** (MUST). 신청서를 내지 않은 계정이 이 경로로 곧장 `ACTIVE`가 되면 `student_no`가 비어 있는 관리자가 만들어지는데, 신청 API는 `PENDING` 전용이라 나중에 채울 방법이 없다. 이 조건이 빠지면 최초 관리자만 학번 없는 계정으로 남는다.

성공하면 `204`다. 본문은 없다 — 화면이 쓰는 경로가 아니라 운영자가 한 번 부르는 경로이고, 결과는 `GET /auth/me`로 확인한다.

**거절은 사유를 가르지 않는다** (MUST, 2026-08-14 #89). 활성 관리자가 이미 있든, 이메일이 다르든, 토큰이 틀리든, 신청서를 내지 않았든, **정지된 계정이든** 전부 같은 `403 FORBIDDEN`과 같은 문구다. 사유가 갈리면 *"이메일은 맞았고 토큰만 틀렸다"* 를 알아낼 수 있어 무차별 대입의 탐색 공간이 줄어든다. 진짜 사유는 서버 로그에만 남기고, **토큰은 로그에도 남기지 않는다.**

**시도 횟수를 제한한다** (MUST, #144). 한 계정은 **15분에 5회**, 이 경로 전체는 **15분에 20회**까지 시도할 수 있다. 계정을 갈아타며 두드리는 것을 전체 상한이 막는다.

**확인과 자리 잡기가 한 연산이어야 한다** (MUST). 세어 보고 나중에 기록하면 **동시에 도착한 요청들이 모두 같은 옛 카운트를 읽고 전부 통과한다** — 병렬로 보내는 것만으로 상한이 무의미해진다. 시도를 시작하기 전에 잠금 아래에서 자리를 잡는다.

**잠긴 동안의 요청은 세지 않는다** (MUST). 세면 두드릴수록 창이 뒤로 밀려 **"기다리면 풀린다"가 참이 아니게 된다** — 사고 대응 중인 운영자가 재시도하다 영영 못 들어가는 쪽이 더 위험하다.

**잠긴 것도 위와 같은 `403`이고 문구도 같다** (MUST). 응답이 달라지면 **잠기는 시점을 재서 토큰이 맞았는지를 역으로 알아낼 수 있다** — 틀린 토큰만 세어진다면, 잠기지 않는 시도가 곧 맞는 토큰이다. 잠금은 로그에만 남는다.

**IP는 세지 않는다.** 브라우저가 Vercel 프록시를 거쳐 도착하므로([deployment.md](../docs/ops/deployment.md)) 서버가 보는 주소는 프록시의 것이고, `X-Forwarded-For`는 우리가 통제하지 않는 구간을 지나며 요청자가 값을 넣을 수 있다 — **믿을 수 없는 값으로 나누면 버킷만 늘어나 제한이 사라진다.**

**승격에 성공하면 그 계정의 시도 기록을 지운다** (MUST). 토큰을 몇 번 잘못 붙여넣고 성공하는 것은 흔한 일이라, 남겨 두면 **바로 다음 사고의 복구가 막힌다.**

**설정값(`ADMIN_BOOTSTRAP_EMAIL`·`ADMIN_BOOTSTRAP_TOKEN`)이 없으면 이 경로는 닫힌다** — 응답은 위와 같아서 설정 여부조차 밖에서 알 수 없다. 값이 없다고 기동을 막지는 않는다: 일회성 운영 경로를 기동 조건으로 묶으면 나중에 토큰을 회전하거나 지우는 순간 API 전체가 죽는다.

**이미 승인된 계정이 호출하면 `role`만 바꾼다** (MUST). 마지막 관리자 사고의 복구 경로로 쓰일 때가 그렇다 ([2-2 §2-2-7](2-2-OPERATOR-REQUIREMENTS.md)) — 다시 승인 처리하면 `approved_at`이 오늘로 덮여 **실제 승인일이 사라진다.**

### 구글 OAuth 경로

두 경로는 Spring Security OAuth2 Client가 제공하며, **base path `/api/v1` 아래에 오도록 설정한다** (MUST). 실제 URL은 `/api/v1/oauth2/authorization/google`과 `/api/v1/login/oauth2/code/google`이다.

프레임워크 기본값(`/oauth2/...`, `/login/...`)을 그대로 두면 Vercel rewrites가 `/api/*`만 프록시하므로 브라우저 요청이 ALB에 닿지 않는다 ([3-3 결정 5](3-3-DESIGN-DECISIONS.md#3-3-5-결정-5--도메인-없이-vercel-프록시로-https를-우회한다)). 프록시 규칙을 늘리는 대신 서버 경로를 옮긴다.

구글 콘솔에 등록할 redirect URI도 **프론트엔드 오리진** 기준이다. 브라우저는 Vercel과만 통신하므로 ALB 주소를 등록하면 콜백이 다른 오리진에 떨어져 쿠키가 붙지 않는다.

### `GET /auth/me` — 세션 확인

**비로그인에게도 열려 있고, 세션이 없으면 `204`다** (MUST, 2026-08-22, #190). 오류가 아니다.

화면은 랜딩을 포함해 **최초 렌더마다** 이것을 부른다([3-1 §3-1-3](3-1-DESIGN-ARCHITECTURE.md)). `401`로 답하면 **비로그인 방문자마다 실패 응답이 하나씩 남고**, 브라우저가 콘솔에 남기는 그 줄은 앱이 지울 수 없어 진짜 오류가 묻힌다.

**비로그인에게 나가는 것은 "세션이 없다"뿐이다** (MUST). 본문이 없으므로 계정 정보가 실릴 자리도 없다. `GET /auth/csrf`를 연 것과 같은 성격이다.

**로그인 사용자의 `200` 응답은 바뀌지 않았다.** 그래서 `authenticated` 같은 필드로 감싸지 않는다 — 감싸면 화면·픽스처·기존 사례가 전부 따라 움직이는데, 얻는 것은 같다.

**세션은 있는데 계정이 사라진 경우도 `204`다.** 화면에게 그 상태는 "로그인되어 있지 않다"와 같고, 실제로 다음 요청부터 인증이 성립하지 않는다.

> **결합 검사가 느슨해진 것이 아니다.** `JwtSessionAuthenticationFilter`는 이 경로에서도 그대로 돌아, 토큰과 세션이 어긋나면 인증을 세우지 않고 **양쪽 쿠키를 폐기한다** (T-29). 다만 그 결과가 `401`이 아니라 `204`일 뿐이다.

`GET /auth/me`는 신청서 제출 여부를 함께 반환한다. 프론트엔드가 `PENDING` 사용자에게 신청 폼을 보일지 대기 안내를 보일지 이 값으로 가른다 ([3-1 §3-1-6](3-1-DESIGN-ARCHITECTURE.md)).

**신청 여부는 `appliedAt`(스키마의 `applied_at`)으로 판단한다** (MUST). 값이 있으면 제출한 것이다. 같은 사실을 알려주는 별도 boolean 필드를 두지 않는다 — 두 값이 어긋나는 자리가 생기고, 어긋나면 화면이 폼과 안내 중 틀린 쪽을 고른다.

### 콜백은 항상 SPA로 되돌린다

**구글 콜백은 성공이든 실패든 JSON을 반환하지 않는다** (MUST). 브라우저 전체가 이동한 흐름이므로, 오류 본문을 그대로 내보내면 사용자가 SPA 밖의 빈 화면에 갇힌다. `request()`를 거치지 않아 프론트엔드의 공통 오류 처리도 동작하지 않는다.

| 결과 | 리다이렉트 |
|---|---|
| 성공 | `/` — 이후 SPA가 `GET /auth/me`로 상태를 판단해 알맞은 화면으로 보낸다 |
| 실패 | `/login?error={코드}` |

실패 코드는 이용자에게 무엇을 해야 하는지 알려줄 수 있는 것만 쓴다 (MUST).

| 코드 | 상황 | 로그인 화면이 보여줄 것 |
|---|---|---|
| `domain` | 허용 도메인이 아닌 계정 | "`khu.ac.kr` 계정으로 로그인하세요" |
| `unverified` | `email_verified`가 거짓 | 구글에서 이메일 인증을 마치라는 안내 |
| `suspended` | 정지된 계정 | 정지 안내 ([3-1 §3-1-5](3-1-DESIGN-ARCHITECTURE.md)) |
| `failed` | 그 외 (`state` 불일치, 토큰 교환 실패 등) | 일반 오류. 원인을 자세히 알리지 않는다 |

**쿼리 파라미터에 이메일·토큰·예외 메시지를 담지 않는다** (MUST). 주소창과 브라우저 기록, 리퍼러에 남는다.

§3-2-7의 상태 코드(`403 SUSPENDED`, `403 FORBIDDEN` 등)는 **API 호출에만 쓴다.** 콜백 실패는 상태 코드가 아니라 위 표의 리다이렉트로 알린다.

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

**`sort`는 `latest`·`title`만 받는다** (MUST). 그 밖의 값은 기본값으로 본다 — 화면이 조합해 보내는 값이라 `400`으로 막을 이유가 없다. **Spring Data의 속성 정렬(`?sort=title,asc`)이 아니다** — 그대로 넘기면 없는 속성 이름 하나에 `500`이 난다 (2026-08-21, #52).

**정렬의 마지막 기준은 언제나 `id`다** (MUST). 같은 시각에 등록됐거나 제목이 같은 자료가 여럿이면 순서가 정해지지 않아, 페이지를 넘길 때마다 배치가 달라진다 — **같은 자료가 두 번 보이거나 아예 빠지고, 훑는 사람은 그것을 알아채지 못한다.**

**있을 수 없는 필터 조합은 오류가 아니라 결과 0건이다.** `category=SUBJECT&examType=MIDTERM`이 그렇다 — 조회에 검증을 넣으면 화면이 필터를 조합하는 순간마다 `400`을 받는다. 그 짝을 강제하는 것은 등록 경로와 §3-2-2의 CHECK 제약이다.

**`GET /notes` 응답** (2026-08-21 확정, #52)

목록의 한 행은 `id`·`category`·`title`·`subjectName`·`professor`·`year`·`semester`·`examType`·`uploader`·`fileCount`·`createdAt`이다.

**파일은 개수만 담는다.** 목록에서 쓰는 것은 "첨부가 있나"뿐인데 파일 목록을 전부 실으면 20건 × N개가 된다. 내용은 상세가 준다.

**`GET /notes/{id}` 응답**

목록의 항목에 `files`와 `updatedAt`이 더해진다. `files`의 각 항목은 `id`·`originalName`·`sizeBytes`다.

**`stored_path`(S3 오브젝트 키)는 어디에도 담지 않는다** (MUST). 버킷이 비공개라 키를 알아도 열 수 없고, 키 구조를 밖에 드러낼 이유가 없다. 파일을 받는 길은 presigned URL을 발급하는 `GET /notes/{id}/files/{fileId}`뿐이며, 그래서 파일 `id`가 필요하다.

**`GET /notes/filters` 응답**

```json
{ "subjects": ["네트워크", "운영체제"], "professors": ["김교수"], "years": [2025, 2024] }
```

**실제 등록된 값에서 만든다** (MUST, [2-1 §2-1-1](2-1-USER-STORIES.md)). 없는 과목을 고를 수 있으면 결과가 늘 0건이고, 등록된 과목이 빠지면 찾을 방법이 사라진다. 교수명이 없는 자료는 옵션을 만들지 않는다 — 화면이 빈 항목을 그린다.

**학기·시험 구분은 담지 않는다.** 값이 enum으로 고정이라 등록 현황과 무관하고 화면이 이미 안다.

### 즐겨찾기 (2026-08-21 확정, #56)

**자료 목록·상세 응답에 `bookmarked`(boolean)가 있다** (MUST). 목록에서도 추가·해제하므로([2-1 §2-1-5](2-1-USER-STORIES.md)) 화면이 별표를 채울지 비울지 알아야 한다 — 없으면 `GET /bookmarks`를 통째로 받아 대조해야 하고, 즐겨찾기가 많으면 그것부터 문제가 된다.

**`POST`·`DELETE` 둘 다 멱등이다** (MUST).

| 요청 | |
|---|---|
| 이미 담긴 자료에 `POST` | `204`. 아무것도 바뀌지 않는다 |
| 담기지 않은 자료에 `DELETE` | `204` |
| 없는 자료에 `POST` | `404 NOT_FOUND` — 확인 뒤 그 자료가 지워진 경우도 같다. FK 위반이 `500`으로 새지 않는다 |
| 없는 자료에 `DELETE` | `204`. 자료가 지워지면 즐겨찾기도 함께 사라져 뺄 것이 이미 없다 |

**멱등은 DB에서 보장한다** (MUST). 확인하고 저장하는 방식은 **동시에 도착한 요청들이 모두 "없다"를 읽고 지나가고**, 읽고 지우는 방식은 뒤의 요청이 지울 것을 잃어 터진다 — 재시도가 예상되는 경로라 그러면 안 된다. 담기는 있으면 넘어가는 한 문장으로, 빼기는 읽지 않는 한 문장으로 끝낸다.

**토글이 아니다** (MUST). 같은 요청이 상태를 뒤집으면 **재시도가 방금 담은 것을 조용히 뺀다** — 응답이 오는 길에 끊겨 클라이언트가 다시 보내는 일은 흔하고, 그때 사용자는 한 번 눌렀는데 별표가 꺼진 것을 보게 되며 오류는 하나도 나지 않는다. 화면은 `bookmarked`를 보고 담을지 뺄지 고르므로 **사용자가 보는 동작은 토글과 같다.**

**`GET /bookmarks` 응답은 `GET /notes`와 같은 형태다.** 같은 카드를 그리는 화면이라 응답이 다르면 화면이 두 벌이 된다. **그 목록의 `bookmarked`는 언제나 `true`다** (MUST) — 목록에 있다는 것이 곧 담겨 있다는 뜻이라 다시 묻지 않는다. 한 번 더 물으면 그 사이에 해제된 항목이 `false`로 돌아와 이 계약이 깨진다.

**정렬은 내가 표시한 순서(최신)** 다 — 자료의 등록 시각이 아니다. 이 화면의 기준은 "언제 올라온 자료인가"가 아니라 "언제 내가 담았나"다. 마지막 기준으로 자료 `id`를 붙인다(§3-2-4의 정렬 규칙과 같은 이유).

**검색·필터는 받지 않는다.** 이미 본인이 추린 목록이다.

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
| POST | `/photos/upload-url` | ADMIN | 원본 파일별 presigned PUT URL 발급 (다중) |
| POST | `/photos` | ADMIN | 메타데이터 등록 (JSON) — body에 업로드 완료된 원본 파일 키 목록. 서버가 그 키들을 S3에서 읽어 리사이즈한 뒤 최종 위치에 저장하고 사진마다 행을 만든다 |
| DELETE | `/photos/{id}` | ADMIN | 삭제 |

### `POST /photos` — 업로드 경로 (확정, [1-BACKGROUND §1-5](1-BACKGROUND.md) #5)

**원본은 presigned PUT으로 브라우저→S3 직접 올리고, 리사이즈는 서버가 동기로 한다** (MUST). 서버 리사이즈가 필요해([2-1 §2-1-7](2-1-USER-STORIES.md) MUST) `POST /photos`가 원본을 그대로 받는 방식(multipart)은 Vercel 프록시의 요청 본문 4.5MB 제한에 걸린다 — 요즘 휴대폰 사진은 그보다 흔히 크다. presigned PUT은 Vercel을 거치지 않고 브라우저가 S3에 직접 쓰므로 이 제한과 무관하다.

절차:

1. 관리자가 `POST /photos/upload-url`을 불러 올릴 개수만큼 presigned PUT URL을 받는다. 원본은 `photos/uploads/{uuid}.{ext}`에 임시로 쓴다 — 이 시점엔 `photoId`가 없다 (`note_files`의 `notes/{uuid}.{ext}`와 같은 이유, §3-2-2).
2. 브라우저가 각 URL로 원본을 S3에 직접 올린다.
3. 관리자가 `POST /photos`를 불러 업로드된 원본 키 목록(과 캡션)을 보낸다.
4. **서버가 그 요청 처리 중에** 각 원본을 S3에서 읽어(이 트래픽도 Vercel을 거치지 않는다) 리사이즈하고, 최종 키(`photos/{photoId}/{uuid}.jpg`, 썸네일 `photos/{photoId}/thumb/{uuid}.jpg`)에 다시 쓴 뒤 DB 행을 만든다.
5. 리사이즈가 끝나면 임시 원본(`photos/uploads/...`)을 지운다 — 남겨 둘 이유가 없고, 쌓이면 스토리지 비용만 는다.

**대안이었던 "Lambda 등으로 비동기 리사이즈"는 채택하지 않는다.** 지금 규모(부원 수, 업로드 빈도)에서 얻는 이득보다 새 인프라(이벤트 트리거, 별도 실행 환경)와 "업로드 직후엔 리사이즈본이 아직 없는" 처리 지연을 다루는 비용이 크다. 업로드량이 실제로 늘면 그때 다시 검토한다.

### 성공 응답 본문

`POST /notices`, `PATCH /notices/{id}`, `PATCH /notices/{id}/pin`은 저장된 공지를 본문으로 돌려준다 (확정, 2026-08-13, #33·#34) — `POST`는 `201`, 두 `PATCH`는 `200`이다. 화면은 등록·수정 응답 본문의 `id`로 상세 화면으로 이동한다. `DELETE /notices/{id}`는 본문 없이 `204`다.

공지 응답은 **`authorId`(`null` 가능)와 `authorName`(`null` 아님)을 함께 담는다** (MUST, #58) — 규칙은 [§3-2-2 "작성자를 내려주는 규칙"](#작성자를-내려주는-규칙)과 같다. 작성자가 제거되어 `author_id`가 `NULL`이면 서버가 `authorName`에 `"탈퇴한 회원"`을 넣는다.

## 3-2-6 API — 회원 관리

| Method | Path | 권한 | 설명 |
|---|---|---|---|
| GET | `/admin/users` | ADMIN | 목록 — `status`, `role`, `q`, `applied`, `sort`, `page`, `size` |
| POST | `/admin/users/approve` | ADMIN | 일괄 승인 — body: `{ "userIds": [1,2,3] }` |
| POST | `/admin/users/reject` | ADMIN | 일괄 거부 — body: `{ "userIds": [1,2,3] }` |
| PATCH | `/admin/users/{id}/status` | ADMIN | `ACTIVE` ↔ `SUSPENDED` (본인을 `SUSPENDED`로: 마지막 활성 관리자면 차단) |
| PATCH | `/admin/users/{id}/role` | ADMIN | 권한 부여/회수 (본인 대상: 마지막 활성 관리자면 차단) |
| GET | `/admin/users/{id}/content-summary` | ADMIN | 제거 확인 창이 쓰는 건수 — 그 회원이 남길 자료·공지·사진 |
| DELETE | `/admin/users/{id}` | ADMIN | 회원 제거 (본인 대상: 마지막 활성 관리자면 차단) |

### 목록 파라미터

`GET /admin/users`의 파라미터는 아래로 확정한다 (2026-08-12, #29). 프론트엔드가 먼저 구현하고 확인을 요청했던 두 항목이 여기에 흡수됐다.

| 파라미터 | 값 | 비고 |
|---|---|---|
| `status` | `PENDING` \| `ACTIVE` \| `SUSPENDED` | |
| `role` | `USER` \| `ADMIN` | |
| `q` | 문자열 | 이름·학번·이메일 통합 검색. **대소문자를 가리지 않는 부분 일치**다. 공백뿐이면 거르지 않는다 |
| `applied` | `true` \| `false` | **신청서 제출 여부** (`applied_at`의 유무) |
| `sort` | `name` \| `studentNo` \| `appliedAt` | Spring Data 형식이다 — `sort=name`은 `name` 오름차순, `sort=appliedAt,desc`도 받는다 |

**`sort`에 그 밖의 값이 오면 `400 VALIDATION_ERROR`다** (MUST). 조용히 무시하지 않는다 — 관리자는 정렬이 적용된 줄 알고 그 순서를 신뢰해 승인·정지를 누른다. 정렬을 보내지 않으면 **가입 신청일 최신순**이고, **값이 없는 행은 언제나 뒤로 간다** (MUST). PostgreSQL은 `DESC`에서 널을 맨 앞에 올리므로, 그대로 두면 신청조차 하지 않은 계정이 승인 대기자보다 위에 온다.

**`applied`가 필요한 이유는 `status=PENDING`만으로는 승인 대기를 고를 수 없기 때문이다.** 구글 로그인만 해보고 신청서를 내지 않은 계정도 `PENDING`이다. **승인 대상 집합은 `status=PENDING&applied=true`다** — 아래 절이 말하는 `status = 'PENDING' AND applied_at IS NOT NULL`과 같다.

화면에서 거르는 것은 답이 아니다. 서버가 20건을 주고 화면이 그중 일부를 버리면 페이지마다 보이는 건수가 들쭉날쭉하고, 총 건수와 총 페이지 수가 실제와 어긋난다 — 관리자가 "12명 남았다"고 읽는 숫자가 틀리게 된다.

### 일괄 승인 응답

`POST /admin/users/approve`의 응답은 아래로 확정한다 (2026-08-13, #30). 프론트엔드가 먼저 구현하고 확인을 요청했던 형태를 그대로 받았다.

```json
{
  "approved": [1, 2],
  "failed": [{ "userId": 3, "reason": "NOT_APPLIED" }]
}
```

| 항목 | 이유 |
|---|---|
| 건수 필드를 두지 않는다 | 배열 길이가 곧 건수다. `successCount`를 따로 두면 배열과 어긋날 자리가 생긴다 |
| 실패에 `userId`를 담는다 | 건수만으로는 운영자가 조치할 수 없다. "1명 실패"로는 누구에게 신청서를 내라고 안내할지 모른다 |
| 일부 실패도 `200` | 요청 자체의 실패가 아니다. 권한 없음 같은 실패는 §3-2-7의 규약을 그대로 쓴다 |

**`reason`은 셋이다** (MUST). 계약이 명시한 실패는 `NOT_APPLIED` 하나였지만 실제로 거절되는 경우는 셋이고, 하나로 뭉개면 **이미 승인된 사람에게 "신청서를 내지 않았다"고 안내하게 된다.**

| `reason` | 상황 |
|---|---|
| `NOT_FOUND` | 그 id의 계정이 없다 |
| `NOT_PENDING` | 이미 승인됐거나 정지된 계정이다 |
| `NOT_APPLIED` | 구글 로그인만 하고 신청서를 내지 않았다 |

**요청자의 권한을 잠근 뒤 다시 확인한다** (MUST). 상태 변경과 같은 이유다 — 인가는 세션 값으로 이루어지므로, 대상 행을 기다리는 동안 다른 관리자가 요청자를 정지시켰을 수 있다. 확인하지 않으면 **정지된 관리자가 회원을 활성화한다.**

**실패는 성공을 되돌리지 않는다** (MUST). 한 건 때문에 트랜잭션이 되돌아가면 관리자가 20명을 골랐을 때 한 명이 신청서를 내지 않았다는 이유로 아무도 승인되지 않는다.

요청의 `userIds`는 **최대 100개**이고 빈 배열은 `400 VALIDATION_ERROR`다. 상한은 회원 목록의 페이지 크기와 같다 — 화면이 한 번에 고를 수 있는 최대가 "현재 페이지 전부"이기 때문이다 ([5-TESTING T-75](5-TESTING.md#5-2-필수-테스트-사례)). 같은 id가 두 번 오면 한 번만 센다.

### 상태 변경

`PATCH /admin/users/{id}/status`의 동작을 아래로 확정한다 (2026-08-13, #31).

```json
요청  { "status": "SUSPENDED" }
응답  200 — 갱신된 회원 (목록의 한 행과 같은 형태)
```

**본문으로 갱신된 회원을 돌려준다** (MUST). 화면이 재조회 없이 그 행을 고칠 수 있어야 한다.

| 요청 | |
|---|---|
| `ACTIVE` → `SUSPENDED`, `SUSPENDED` → `ACTIVE` | 허용 |
| **이미 그 상태** | 아무것도 하지 않고 `200` + 현재 상태. 확인 창을 두 번 지나거나 낡은 목록에서 눌러도 오류가 아니다 |
| **대상이 `PENDING`** | `400 VALIDATION_ERROR`. 계약이 정한 전이는 `ACTIVE` ↔ `SUSPENDED`뿐이다 ([2-2 §2-2-3](2-2-OPERATOR-REQUIREMENTS.md)) — 이 경로로 승인시키면 승인일시가 기록되지 않고 신청 여부도 확인하지 않는다 |
| `status`가 `ACTIVE`·`SUSPENDED`가 아님 | `400 VALIDATION_ERROR` |
| 없는 `id` | `404 NOT_FOUND` |
| **정지 뒤 활성 관리자가 0명이 됨** | `403 FORBIDDEN` ([§2-2-7](2-2-OPERATOR-REQUIREMENTS.md) MUST). 자기 대상인지와 무관하다 |

### 가입 거부 (2026-08-22 확정, #58)

```json
요청  { "userIds": [1, 2, 3] }
응답  200 { "rejected": [1], "failed": [{ "userId": 2, "reason": "NOT_PENDING" }] }
```

**일부가 실패해도 `200`이다** — 승인과 같은 규칙이다. 한 건 때문에 되돌리면 성공한 거부까지 사라진다. 상한도 승인과 같은 **100개**다(같은 화면에서 같은 체크박스로 고른다).

**대상은 `PENDING`뿐이다** (MUST). 이용 중인 회원을 이 경로로 지우면 "제거"가 되는데, 그쪽은 세션 폐기·정지 선행 같은 규칙이 따로 붙는다([2-2 §2-2-4](2-2-OPERATOR-REQUIREMENTS.md#2-2-4-회원-제거)). 그 건은 `NOT_PENDING`으로 집계한다.

| `reason` | |
|---|---|
| `NOT_FOUND` | 그 id의 계정이 없다 |
| `NOT_PENDING` | `PENDING`이 아니다 — 제거 경로를 우회하는 것을 막는다 |

**계정 레코드를 지운다. 별도 상태를 두지 않는다** ([2-2 §2-2-2](2-2-OPERATOR-REQUIREMENTS.md)). 상태로 남기면 그 계정이 `email`·`google_sub` UNIQUE를 붙잡아 **같은 사람이 다시 가입할 수 없다.**

### 권한 부여·회수

```json
요청  { "role": "ADMIN" }
응답  200 — 갱신된 회원 (목록의 한 행과 같은 형태)
```

**뒤집는 것이 아니라 원하는 권한을 말한다** — 상태 변경과 같은 모양이다. 화면이 들고 있는 값이 낡았을 때 의도와 반대로 바뀌지 않는다.

| 요청 | |
|---|---|
| 이미 그 권한 | 아무것도 하지 않고 `200`. **이력도 쌓이지 않는다** |
| 대상이 `PENDING` | `400 VALIDATION_ERROR` — 승인일시 없는 `ADMIN`이 생긴다 |
| 없는 `id` | `404 NOT_FOUND` |
| **회수 뒤 활성 관리자가 0명** | `403 FORBIDDEN` ([§2-2-7](2-2-OPERATOR-REQUIREMENTS.md) MUST). 자기 대상인지와 무관하다 |

**Role만 바꾼다. Status는 건드리지 않는다** ([2-2 §2-2-5](2-2-OPERATOR-REQUIREMENTS.md)). 회수는 **그 사람의 기존 세션에도 즉시 반영된다** (T-34).

### 회원 제거

성공하면 `204`다. 본문은 없다.

| 요청 | |
|---|---|
| 없는 `id` | `404 NOT_FOUND` |
| **제거 뒤 활성 관리자가 0명** | `403 FORBIDDEN` |
| **본인 제거** | `204`. 응답이 **세션과 토큰 쿠키를 함께 버린다** (MUST) |

**본인을 지우면 지금 요청의 세션도 끝난다** (MUST). 저장소에서 세션 행을 지워도 **이 요청에 붙어 있는 세션은 응답을 내보낼 때 다시 저장되어** 방금 지운 `ADMIN` 세션이 되살아난다.

### 제거 영향 조회

`GET /admin/users/{id}/content-summary` — 제거 확인 창이 **"무엇이 남는지"** 를 보여주려면 필요하다 ([2-2 §2-2-4](2-2-OPERATOR-REQUIREMENTS.md#2-2-4-회원-제거) MUST).

```json
응답  200 { "notes": 12, "notices": 3, "photos": 0 }
```

**세 값 모두 항상 담는다** (MUST). `0`을 빼면 화면이 "없음"과 "모름"을 가르지 못한다.

없는 `id`는 `404 NOT_FOUND`다. 이 값은 **확인 창을 여는 시점의 참고치**이지 제거의 조건이 아니다 — 그 사이 건수가 바뀌어도 제거는 그대로 진행한다. 건수를 맞추려고 제거까지 막으면 확인 창을 다시 열어도 같은 자리를 맴돌 수 있다.

`Post Launch`다. 자료·사진 테이블이 생기기 전에는 셀 대상이 없다.

**정지는 기존 세션에 즉시 반영된다** (MUST) — 세션을 지우지 않고 갱신하므로 다음 요청이 `403 SUSPENDED`다 ([3-1 §3-1-5](3-1-DESIGN-ARCHITECTURE.md), T-32).

**이미 그 상태인 요청도 세션을 다시 맞춘다** (MUST). 세션 갱신 실패는 예외로 올리지 않으므로 같은 요청을 다시 보내는 것이 유일한 복구 수단인데, 일찍 돌아가 버리면 그 재시도가 아무 일도 하지 않는다 — 정지된 사람이 만료까지 계속 쓴다.

**활성 관리자 수 확인과 변경은 한 연산이다** (§2-2-7 MUST). 관련된 행을 잠근 뒤에 세므로, 동시에 들어온 다른 정지는 그 잠금을 기다렸다가 줄어든 수를 보고 막힌다 — 각자 자기 자신을 정지하든 **서로를 정지하든** 마찬가지다 ([5-TESTING T-15](5-TESTING.md#5-2-필수-테스트-사례)).

**요청자의 권한을 잠근 뒤 다시 확인한다** (MUST). 인가는 세션 값으로 이루어지므로 요청이 인증을 통과한 뒤에도 다른 관리자가 그 사람을 정지하거나 권한을 회수할 수 있다 — 확인하지 않으면 **이미 정지된 관리자의 대기 중 요청이 그대로 커밋된다.** 이때의 코드는 필터가 막았을 때와 같다(`SUSPENDED`·`PENDING_APPROVAL`·`FORBIDDEN`, §3-2-7).

### 신청일과 승인 대상

**`GET /admin/users`의 "가입 신청일"은 `applied_at`이다** (MUST). `created_at`(첫 구글 로그인)이 아니다 — 표시·정렬 모두 `applied_at`을 쓴다 ([2-2 §2-2-1](2-2-OPERATOR-REQUIREMENTS.md)). 응답에는 두 값을 모두 담되 화면이 무엇을 "신청일"로 부르는지 어긋나지 않게 한다.

**승인 대상 목록은 `status = 'PENDING' AND applied_at IS NOT NULL`로 거른다** (MUST). 신청하지 않은 계정은 **승인 대상에 포함되지 않는다.**

여기서 말하는 것은 **승인의 대상 집합**이지 회원 목록 화면이 아니다 (2026-08-10 명확화). `GET /admin/users`는 모든 회원을 보여주는 화면이고 신청하지 않은 계정도 관리자가 봐야 한다 — 그 사람이 존재한다는 것 자체가 운영 정보다. 요점은 **그 계정이 승인 대상에서 빠지는 것**이고, 화면은 이를 상태 표시("미승인")와 선택 불가로 드러낸다 ([5-TESTING T-73](5-TESTING.md#5-2-필수-테스트-사례)).

`POST /admin/users/approve`에 신청하지 않은 계정의 id가 섞여 오면 그 건은 실패로 집계하고 그 계정의 상태를 바꾸지 않는다 (MUST). 목록에서 걸렀더라도 API를 직접 부르는 경로가 남아 있다.

## 3-2-7 공통 에러 코드

| HTTP | 코드 | 상황 |
|---|---|---|
| 400 | `VALIDATION_ERROR` | 입력값 검증 실패 |
| 401 | `UNAUTHENTICATED` | 미로그인 |
| 403 | `PENDING_APPROVAL` | `PENDING` 사용자의 일반 API 접근 |
| 403 | `SUSPENDED` | **정지된 계정의 보호 API 접근.** 이용 중 정지된 세션의 다음 요청이 이 코드다 ([2-2 §2-2-3](2-2-OPERATOR-REQUIREMENTS.md) MUST — 정지는 세션을 지우지 않고 갱신한다). 로그인 시도가 막히는 경우는 이 코드가 아니라 §3-2-3의 `/login?error=suspended` 리다이렉트다 |
| 403 | `FORBIDDEN` | 권한 부족 / 마지막 활성 관리자의 본인 권한 회수·삭제·정지 시도 / 허용 도메인이 아닌 구글 계정의 로그인 / **`ACTIVE` 계정의 `POST /auth/application` 호출** ([3-1 §3-1-6](3-1-DESIGN-ARCHITECTURE.md) MUST) |
| 404 | `NOT_FOUND` | 리소스 없음 |
| 409 | `DUPLICATE_STUDENT_NO` | 신청서의 학번이 다른 계정에 이미 쓰이고 있음 |
| 413 | `FILE_TOO_LARGE` | 파일 용량 초과 |
| 415 | `UNSUPPORTED_FILE_TYPE` | 허용되지 않는 확장자 |

에러 응답은 커스텀 예외 + `@RestControllerAdvice`로 일괄 처리한다 (MUST). 응답 형식은 [5-TESTING §5-4](5-TESTING.md)에 있다.

## 3-2-8 공통 페이지 응답

목록 API는 모두 페이지 응답을 쓴다. MVP는 `GET /notices`, `GET /admin/users`, `Post Launch`는 `GET /notes`, `GET /bookmarks`, `GET /photos`가 대상이다.

공통 요청 파라미터는 `page`(0부터 시작), `size`(기본 20, **상한 100**)다. 상한을 두지 않으면 Spring 기본값인 2000까지 한 번에 요청할 수 있다.

응답 형태는 Spring Data `PagedModel`로 고정한다 (MUST).

```json
{
  "content": [],
  "page": { "size": 20, "number": 0, "totalElements": 300, "totalPages": 15 }
}
```

서버는 `spring.data.web.pageable.serialization-mode: via-dto`를 전역에 한 번 설정한다 (MUST).

**같은 뜻의 `@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)`를 쓰지 않는다** (2026-08-12, #29에서 확인). 그 애너테이션을 붙이는 순간 Spring Boot의 자동설정이 통째로 물러나 `default-page-size`·`max-page-size` 설정이 **조용히 무시된다** — 응답 형태는 맞는데 `size=2000`이 그대로 통과한다.

`Page` 객체를 그대로 직렬화하지 않는다 (MUST). Spring 3.3+는 이 방식의 구조 안정성을 보장하지 않고 경고를 남기며, `pageable`·`sort`·`offset` 같은 내부 구현 필드가 응답에 노출된다.

---
[← 이전: 아키텍처](3-1-DESIGN-ARCHITECTURE.md) · [다음: 결정 기록 →](3-3-DESIGN-DECISIONS.md)
