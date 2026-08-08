# hacker_HP API

Java 21과 Spring Boot 3.5로 만든 서버 애플리케이션이다. 현재 보일러플레이트는 기능 API를 제공하지 않으며 Actuator health check만 노출한다.

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
