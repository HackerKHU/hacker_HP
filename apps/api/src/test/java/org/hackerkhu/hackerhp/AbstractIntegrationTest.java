package org.hackerkhu.hackerhp;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Postgres 16 Testcontainers 공유 베이스. spec 3-2 스키마를 실제 DB로 검증할 때 상속한다.
 *
 * <p><b>컨테이너를 JVM 전체에서 하나만 쓴다.</b> {@code @Testcontainers}와 {@code @Container}를 쓰면 JUnit이 테스트 클래스마다
 * 컨테이너를 껐다 켜는데, Spring 컨텍스트는 설정이 같으면 <b>캐시되어 재사용</b>된다. 그러면 두 번째 클래스부터 컨텍스트가 이미 죽은 컨테이너의 포트를 들고 있어
 * {@code Connection is not available}로 전부 실패한다.
 *
 * <p>정적 초기화로 한 번만 띄우고 끄지 않는다. 정리는 Testcontainers의 Ryuk 컨테이너가 JVM 종료 시 맡는다.
 */
abstract class AbstractIntegrationTest {

  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    POSTGRES.start();
  }
}
