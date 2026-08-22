package org.hackerkhu.hackerhp;

import org.hackerkhu.hackerhp.global.auth.JwtProvider;
import org.hackerkhu.testsupport.auth.TestSessions;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Postgres 16 Testcontainers 공유 베이스. spec 3-2 스키마를 실제 DB로 검증할 때 상속한다.
 *
 * <p><b>컨테이너를 JVM 전체에서 하나만 쓴다.</b> {@code @Testcontainers}와 {@code @Container}를 쓰면 JUnit이 테스트 클래스마다
 * 컨테이너를 껐다 켜는데, Spring 컨텍스트는 설정이 같으면 <b>캐시되어 재사용</b>된다. 그러면 두 번째 클래스부터 컨텍스트가 이미 죽은 컨테이너의 포트를 들고 있어
 * {@code Connection is not available}로 전부 실패한다.
 *
 * <p>정적 초기화로 한 번만 띄우고 끄지 않는다. 정리는 Testcontainers의 Ryuk 컨테이너가 JVM 종료 시 맡는다.
 *
 * <p><b>세션 자동설정을 끄지 않는다</b> (#90). 끄면 {@code MockHttpSession}을 쓸 수 있어 편하지만, 테스트마다 어느 방식인지 골라야 하고
 * 설정이 다른 컨텍스트가 여러 벌 뜬다. 여기서 {@link #sessions}가 진짜 저장소에 세션을 만들어 주므로 고를 것이 없다.
 *
 * <p><b>구글 OAuth 설정값은 가짜다.</b> 이 값들이 없으면 컨텍스트가 아예 뜨지 않는다 — {@code client-id}는 기본값 없는 자리표시자이고 {@code
 * allowed-email-domain}은 비면 검증에 걸린다(의도한 동작이다, #21). 여기서 채우는 것은 그 기동 조건을 만족시키기 위해서이지 구글과 통신하기 위해서가
 * 아니다. 실제 로그인 흐름은 콜백을 다루는 #25부터 스텁으로 검증한다.
 */
@TestPropertySource(
    properties = {
      "GOOGLE_CLIENT_ID=test-client-id",
      "GOOGLE_CLIENT_SECRET=test-client-secret",
      "OAUTH_REDIRECT_URI=http://localhost:5173/api/v1/login/oauth2/code/google",
      "ALLOWED_EMAIL_DOMAIN=khu.ac.kr",
      "JWT_SECRET=integration-test-only-jwt-secret-32bytes-or-more",
      // 버킷 이름은 기동 조건이다 (설정 누락이 조용히 지나가면 안 된다). 값은 가짜여도
      // 되는데, 테스트는 FileStorage를 갈아끼워 실제 S3를 부르지 않기 때문이다 (#53).
      "S3_BUCKET=test-uploads",
      /*
       * 컨텍스트마다 커넥션 풀이 한 벌씩 뜬다. 설정이 다른 테스트가 늘어날수록 캐시된
       * 컨텍스트도 늘어나는데, 기본 풀 크기(10)를 그대로 두면 컨테이너의 접속 상한(100)에
       * 먼저 부딪혀 "sorry, too many clients already"로 뒤늦게 뜬 컨텍스트가 통째로 죽는다.
       *
       * 테스트 한 벌이 동시에 쥐는 커넥션은 많아야 둘이다 (세션 반영이 겹쳐 잡는 경우, #145).
       */
      "spring.datasource.hikari.maximum-pool-size=4",
      "spring.datasource.hikari.minimum-idle=0"
    })
public abstract class AbstractIntegrationTest {

  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired private SessionRepository<? extends Session> sessionRepository;
  @Autowired private DefaultCookieSerializer cookieSerializer;
  @Autowired private JwtProvider jwtProvider;

  /**
   * 로그인한 상태를 만드는 도구 (#90).
   *
   * <p>여기 두는 이유는 <b>고를 것을 없애기 위해서다.</b> 전에는 테스트마다 세션을 손으로 조립하고 어느 방식을 쓸지 골라야 했다 — 그 선택이 함정이었다.
   */
  protected TestSessions sessions;

  @BeforeEach
  void prepareTestSessions() {
    this.sessions = new TestSessions(sessionRepository, cookieSerializer, jwtProvider);
  }
}
