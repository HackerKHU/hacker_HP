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

기동 로그에서 Flyway가 `V1__init.sql`을 자동 적용하는 것을 확인할 수 있다.

### `Migration checksum mismatch`로 기동이 실패하면

배포된 환경이 없는 동안에는 `V1__init.sql`을 직접 고친다. 새 컬럼을 만들었다 지우는 `ALTER TABLE` 이력을 첫 배포 전부터 남기지 않기 위해서다. 대신 **이미 옛 `V1`을 적용한 로컬 DB는 다시 만들어야 한다.**

```bash
docker compose down -v && docker compose up -d
```

**`flyway repair`로는 부족하다.** 체크섬 기록만 맞춰줄 뿐 이미 만들어진 테이블은 그대로라, 새 컬럼이 없는 상태로 `ddl-auto=validate`가 실패한다. 볼륨을 지우고 다시 만들어야 한다.

로컬 DB의 데이터는 사라진다. 아직 API에 데이터를 넣는 엔드포인트가 없어 손으로 넣은 것이 아니면 잃을 것이 없다.

## 실행

```bash
./gradlew bootRun
```

```bash
curl http://localhost:8080/actuator/health
```

정상 응답의 `status` 값은 `UP`이다. 현재 설정에서는 liveness와 readiness 그룹도 함께 반환한다.

## 검증

```bash
./gradlew spotlessCheck test
docker build --platform linux/amd64 .
```

기능 구현 전에는 [`AGENTS.md`](AGENTS.md)와 연결된 상위 스펙·컨벤션 문서를 확인한다.
