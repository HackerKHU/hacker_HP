# hacker_HP API

Java 21과 Spring Boot 3.5로 만든 서버 애플리케이션이다. 회원 가입·승인, 공지 스키마(Flyway V1)까지 반영되어 있으며, DB 연결 없이는 기동하지 않는다.

## 로컬 개발 환경

로컬 Postgres를 직접 설치하지 않고 저장소 루트의 `docker-compose.yml`로 띄운다.

```bash
docker compose up -d
```

DB가 준비되면 `local` 프로파일로 API를 기동한다. `application-local.yml`이 `docker-compose.yml`의 접속 정보(`localhost:5432`, DB/사용자 `hacker`)를 그대로 사용하도록 되어 있다.

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

기동 로그에서 Flyway가 `V1__init.sql`과 `V2__session.sql`을 자동 적용하는 것을 확인할 수 있다.

### 구글 OAuth 자격 (로그인을 실제로 눌러볼 때만)

로그인 흐름을 시험하려면 로컬용 구글 클라이언트 ID·시크릿이 필요하다. 값은 **저장소에 적지 않는다** — 팀에서 안전한 채널로 받아 환경변수로 넣는다 (#82).

```bash
GOOGLE_CLIENT_ID=... GOOGLE_CLIENT_SECRET=... \
  ./gradlew bootRun --args='--spring.profiles.active=local'
```

**`local` 프로파일은 두 값이 없어도 기동된다.** 자리를 채우는 가짜 값이 기본값으로 들어 있어서다. 그 상태로 로그인 시작 경로에 가면 구글이 거부할 뿐, 서버를 띄워 헬스체크나 다른 API를 보는 데는 지장이 없다.

기본값을 빈 문자열로 두면 안 된다. Spring Boot가 `Client id of registration 'google' must not be empty.`로 컨텍스트를 통째로 죽여서, 로그인과 무관한 작업까지 구글 자격을 받아야 시작할 수 있게 된다.

`JWT_SECRET`은 로컬용 고정값이 `application-local.yml`에 들어 있어 따로 넣지 않아도 된다. **운영에서는 없으면 기동에 실패한다** — 기본값을 심어두면 누구나 아는 키로 토큰을 위조할 수 있다.

**`local` 프로파일 없이 `bootRun`하면 기동에 실패한다.** `GOOGLE_CLIENT_ID`·`OAUTH_REDIRECT_URI`가 기본값 없는 자리표시자이고, `app.auth.allowed-email-domain`은 비면 검증에 걸린다. 의도한 동작이다 — 허용 도메인 기본값을 코드에 심어두면 설정 누락이 조용히 지나가고 아무 구글 계정이나 가입할 수 있게 된다.

### `Migration checksum mismatch`로 기동이 실패하면

배포된 환경이 없는 동안에는 `V1__init.sql`을 직접 고친다. 새 컬럼을 만들었다 지우는 `ALTER TABLE` 이력을 첫 배포 전부터 남기지 않기 위해서다. 대신 **이미 옛 `V1`을 적용한 로컬 DB는 다시 만들어야 한다.**

```bash
docker compose down -v && docker compose up -d
```

**`flyway repair`로는 부족하다.** 체크섬 기록만 맞춰줄 뿐 이미 만들어진 테이블은 그대로라, 새 컬럼이 없는 상태로 `ddl-auto=validate`가 실패한다. 볼륨을 지우고 다시 만들어야 한다.

로컬 DB의 데이터는 사라진다. 아직 API에 데이터를 넣는 엔드포인트가 없어 손으로 넣은 것이 아니면 잃을 것이 없다.

## 확인

```bash
curl http://localhost:8080/actuator/health
```

정상 응답의 `status` 값은 `UP`이다. 현재 설정에서는 liveness와 readiness 그룹도 함께 반환한다. **이 경로는 인증 없이 열려 있다** — 막히면 ALB 헬스체크가 401로 실패해 태스크가 무한 재시작한다.

그 밖의 경로는 로그인해야 한다. 비로그인으로 부르면 `401`과 함께 아래 본문이 온다.

```json
{ "code": "UNAUTHENTICATED", "message": "로그인이 필요합니다." }
```

**인증은 쿠키 두 개가 함께 있어야 성립한다** ([3-3 결정 12](../../spec/3-3-DESIGN-DECISIONS.md)). 하나만으로는 통과하지 못하므로, `curl`로 보호된 경로를 부르려면 브라우저로 로그인해 받은 두 쿠키를 모두 실어야 한다.

| 쿠키 | 담는 것 | 특징 |
|---|---|---|
| `ACCESS_TOKEN` | 누구인지 (JWT `sub`) | `httpOnly` |
| `SESSION` | 지금 무엇을 할 수 있는지 (`role`·`status`) | 값은 RDS에 있다 |

**로그아웃은 세션을 지우는 것으로 성립한다.** 쿠키에 토큰이 남아 있어도 세션이 없으면 다음 요청은 `401`이다.

## API 문서

```
http://localhost:8080/swagger-ui/index.html
http://localhost:8080/v3/api-docs
```

**로그인해야 열린다** — 승인제 사이트라 명세를 공개하지 않는다 (#23). 같은 브라우저에서 구글 로그인을 마친 뒤 열면 된다.

Swagger UI의 "Try it out"은 **조회만 동작한다.** 인증 쿠키가 `httpOnly`라 화면이 넣어줄 수 없고, 쓰기에 필요한 `X-XSRF-TOKEN` 헤더도 UI가 채우지 못한다. 쓰기 확인은 화면과 테스트가 한다.

## 검증

```bash
./gradlew spotlessCheck test
docker build --platform linux/amd64 .
```

기능 구현 전에는 [`AGENTS.md`](AGENTS.md)와 연결된 상위 스펙·컨벤션 문서를 확인한다.
