package org.hackerkhu.hackerhp.global.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.testsupport.user.Accounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 로그인 세션 발급 (#127, spec 3-1 §3-1-5).
 *
 * <p><b>진짜 {@code SessionRepositoryFilter}를 통과시킨다.</b> 이 창의 정체가 "세션이 언제 저장소에 들어가는가"라서, 필터를 흉내 내면
 * 확인하려는 것이 사라진다 — 저장 시점도 {@code SESSION} 쿠키를 굽는 주체도 그 필터다.
 *
 * <p>인가 요청 세션도 <b>실제로 저장된 것</b>을 쓴다. 콜백 안에서 즉석으로 만든 세션은 저장소에 없어서, "옛 세션을 지웠는가"가 지우지 않아도 통과한다.
 */
@SpringBootTest
class LoginSessionIssueIntegrationTest extends AbstractIntegrationTest {

  private static final String GOOGLE_SUB = "sub-login";
  private static final String EMAIL = "member@khu.ac.kr";
  private static final String SESSION_COOKIE = "SESSION";

  @Autowired private LoginSuccessHandler handler;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepositoryFilter<? extends Session> sessionRepositoryFilter;
  @Autowired private SessionRepository<? extends Session> sessionRepository;
  @Autowired private FindByIndexNameSessionRepository<? extends Session> indexedSessions;
  @Autowired private SessionSynchronizer sessionSynchronizer;
  @Autowired private DefaultCookieSerializer cookieSerializer;
  @Autowired private LoginSessionIssuer issuer;
  @Autowired private JdbcTemplate jdbcTemplate;

  private TransactionTemplate transaction;
  private User member;

  @Autowired
  void buildTransactionTemplate(PlatformTransactionManager transactionManager) {
    this.transaction = new TransactionTemplate(transactionManager);
  }

  @BeforeEach
  void createAccount() {
    userRepository.deleteAll();
    member = userRepository.saveAndFlush(Accounts.applied(GOOGLE_SUB, EMAIL, "20250001"));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
    userRepository.deleteAll();
  }

  /* ------------------------------------------------------------------ 실행 */

  /** 브라우저가 들고 다니는 것. 값은 세션 id 그대로가 아니라 직렬화기가 감싼 것이다. */
  private record Browser(Cookie cookie, String sessionId) {}

  /**
   * 구글로 보내는 단계를 재현한다 — {@code HttpSessionOAuth2AuthorizationRequestRepository}가 세션을 만들고, 그 요청이 끝나며
   * 저장소에 <b>실제로 저장된다.</b>
   */
  private Browser startAuthorization() {
    MockHttpServletResponse response = new MockHttpServletResponse();
    run(
        new MockHttpServletRequest("GET", "/api/v1/oauth2/authorization/google"),
        response,
        (request, ignored) ->
            request.getSession(true).setAttribute("OAUTH2_AUTHORIZATION_REQUEST", "state"));
    return browser(response);
  }

  /** 콜백을 <b>핸들러로</b> 끝까지 밟는다. */
  private Login logIn() {
    Browser authorization = startAuthorization();
    // 저장됐는지부터 확인한다. 저장되지 않았다면 "지웠는가"를 물을 수 없다.
    assertThat(stored(authorization.sessionId())).isNotNull();
    return logIn(authorization);
  }

  /** 인가 단계를 미리 끝내 두고 콜백만 밟는다. 줄 세우기가 필요한 사례가 쓴다. */
  private Login logIn(Browser authorization) {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v1/login/oauth2/code/google");
    request.setCookies(authorization.cookie());
    request.setRequestedSessionId(authorization.sessionId());
    MockHttpServletResponse response = new MockHttpServletResponse();

    run(
        request,
        response,
        (wrapped, wrappedResponse) ->
            handler.onAuthenticationSuccess(wrapped, wrappedResponse, authentication()));

    return new Login(authorization.sessionId(), browser(response), response);
  }

  private record Login(String priorSessionId, Browser browser, MockHttpServletResponse response) {
    String sessionId() {
      return browser == null ? null : browser.sessionId();
    }
  }

  private void run(
      MockHttpServletRequest request, MockHttpServletResponse response, Callback body) {
    HttpServlet servlet =
        new HttpServlet() {
          @Override
          protected void service(HttpServletRequest wrapped, HttpServletResponse wrappedResponse)
              throws java.io.IOException {
            body.run(wrapped, wrappedResponse);
          }
        };
    try {
      sessionRepositoryFilter.doFilter(request, response, new MockFilterChain(servlet));
    } catch (Exception e) {
      throw new IllegalStateException("요청이 예외로 끝났다", e);
    }
  }

  @FunctionalInterface
  private interface Callback {
    void run(HttpServletRequest request, HttpServletResponse response) throws java.io.IOException;
  }

  /**
   * 응답이 실제로 굽는 {@code SESSION} 쿠키.
   *
   * <p><b>마지막에 구워진 것이 이긴다.</b> 세션을 저장소에 직접 만들면 필터가 자기 판단으로 쿠키를 덮어쓰거나 만료시키는데, 그러면 로그인이 조용히 깨진다 — 헤더를
   * 그대로 읽어 그 일이 없는지 본다.
   */
  private Browser browser(MockHttpServletResponse response) {
    List<String> headers =
        response.getHeaders(HttpHeaders.SET_COOKIE).stream()
            .filter(header -> header.startsWith(SESSION_COOKIE + "="))
            .toList();
    if (headers.isEmpty()) {
      return null;
    }
    String last = headers.get(headers.size() - 1);
    if (last.contains("Max-Age=0")) {
      return null;
    }
    String raw = last.substring((SESSION_COOKIE + "=").length()).split(";", 2)[0];
    if (raw.isBlank()) {
      return null;
    }

    /*
     * 값은 그대로 세션 id가 아니다 — DefaultCookieSerializer가 Base64로 감싼다.
     * 직렬화기에게 되읽게 해서 그 규칙을 여기 옮겨 적지 않는다.
     */
    Cookie cookie = new Cookie(SESSION_COOKIE, raw);
    MockHttpServletRequest carrier = new MockHttpServletRequest();
    carrier.setCookies(cookie);
    String id = cookieSerializer.readCookieValues(carrier).stream().findFirst().orElse(null);
    return id == null ? null : new Browser(cookie, id);
  }

  private Authentication authentication() {
    OidcIdToken idToken =
        OidcIdToken.withTokenValue("token").subject(GOOGLE_SUB).claim("email", EMAIL).build();
    DefaultOidcUser principal =
        new DefaultOidcUser(AuthorityUtils.createAuthorityList("ROLE_USER"), idToken);
    return new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities());
  }

  private Session stored(String sessionId) {
    return sessionId == null ? null : sessionRepository.findById(sessionId);
  }

  private Map<String, ? extends Session> sessionsOf(Long userId) {
    return indexedSessions.findByPrincipalName(String.valueOf(userId));
  }

  private void change(java.util.function.Consumer<User> apply) {
    Long id = member.getId();
    transaction.executeWithoutResult(
        ignored -> apply.accept(userRepository.findByIdForUpdate(id).orElseThrow()));
    sessionSynchronizer.refresh(List.of(id));
  }

  private Status statusInDatabase() {
    return userRepository.findById(member.getId()).orElseThrow().getStatus();
  }

  /* ------------------------------------------------------------------ 사례 */

  /**
   * T-198 — 발급의 기본. <b>쿠키가 살아 있고 그 세션이 이미 저장소에 있다.</b>
   *
   * <p>둘을 함께 보는 이유는 <b>한쪽만 맞기 쉽기 때문이다.</b> 저장은 됐는데 쿠키가 다른 세션을 가리키면 로그인이 안 되고, 쿠키는 맞는데 저장이 늦으면 이 이슈의
   * 창이 그대로 열려 있다.
   */
  @Test
  void issuesASessionThatIsAlreadyStored() {
    Login login = logIn();

    assertThat(login.sessionId()).isNotNull();
    Session session = stored(login.sessionId());
    assertThat(session).isNotNull();
    assertThat(session.<Long>getAttribute(AuthSession.USER_ID)).isEqualTo(member.getId());
    assertThat(session.<Status>getAttribute(AuthSession.STATUS)).isEqualTo(Status.PENDING);
    assertThat(session.<Role>getAttribute(AuthSession.ROLE)).isEqualTo(Role.USER);
    // 색인이 비면 상태 변경이 이 세션을 영영 찾지 못한다 (#85).
    assertThat(sessionsOf(member.getId())).containsKey(login.sessionId());
  }

  /** T-199 — 세션 고정 보호. 로그인 전후로 id가 바뀐다. */
  @Test
  void rotatesTheSessionId() {
    Login login = logIn();

    assertThat(login.priorSessionId()).isNotNull();
    assertThat(login.sessionId()).isNotEqualTo(login.priorSessionId());
  }

  /** T-200 — 인가 요청이 쓰던 세션이 저장소에서 사라진다. */
  @Test
  void discardsTheStoredAuthorizationSession() {
    Login login = logIn();

    assertThat(stored(login.priorSessionId())).isNull();
    assertThat(sessionsOf(member.getId())).hasSize(1);
  }

  /** T-201 — 로그인 전에 커밋된 승인은 그대로 담긴다. */
  @Test
  void carriesAChangeThatCommittedBeforeLogin() {
    change(User::approve);

    Login login = logIn();

    assertThat(stored(login.sessionId()).<Status>getAttribute(AuthSession.STATUS))
        .isEqualTo(Status.ACTIVE);
  }

  /** T-203 — 로그인 뒤에 도착한 변경도 그 세션에 반영된다. 저장소에 있으니 조회가 찾는다. */
  @Test
  void picksUpAChangeThatCommitsAfterLogin() {
    Login login = logIn();

    change(User::approve);

    assertThat(stored(login.sessionId()).<Status>getAttribute(AuthSession.STATUS))
        .isEqualTo(Status.ACTIVE);
  }

  /**
   * <b>T-202 — 이 이슈의 본체.</b> 정지가 <b>로그인이 계정 행을 잠근 사이에</b> 도착한다.
   *
   * <p>순서를 우연에 맡기지 않는다. 그냥 동시에 던지면 로그인이 인가 단계부터 밟느라 정지가 거의 항상 먼저 끝나고, 그러면 <b>읽은 뒤 저장 전에 잠금을 놓는 잘못된
   * 구현도 통과한다.</b>
   *
   * <p>그래서 셋을 줄 세운다 — 시험이 먼저 행을 잡고, 로그인과 정지를 차례로 그 잠금에 <b>대기시킨 뒤</b> 놓아준다. Postgres가 대기 순서대로 주므로
   * 로그인이 먼저 들어가고, 정지는 <b>로그인이 잠금을 쥔 동안</b> 기다린다.
   *
   * <p>잘못된 구현이라면 로그인이 계정을 읽자마자 잠금을 놓아 정지가 끼어들고, 그 정지의 세션 반영은 <b>아직 저장되지 않은 세션을 놓친다</b> — 뒤이어 저장된
   * 세션은 {@code ACTIVE}로 남아 <b>정지된 사람이 만료까지 계속 이용한다.</b>
   */
  @Test
  void aSuspensionArrivingDuringLoginCannotSlipBetweenReadAndStore() throws Exception {
    userRepository.deleteAll();
    member = userRepository.saveAndFlush(Accounts.approved(GOOGLE_SUB, EMAIL, "20250001"));
    Long id = member.getId();
    // 인가 단계를 미리 끝낸다. 로그인 스레드가 곧장 잠금 대기로 가야 줄 세우기가 성립한다.
    Browser authorization = startAuthorization();

    ExecutorService pool = Executors.newFixedThreadPool(3);
    CountDownLatch holding = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    int[] seen = {-1};
    try {
      pool.submit(
          () ->
              transaction.executeWithoutResult(
                  ignored -> {
                    userRepository.findByIdForUpdate(id).orElseThrow();
                    holding.countDown();
                    awaitQuietly(release);
                  }));
      assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

      Future<Login> loggingIn = pool.submit(() -> logIn(authorization));
      awaitWaitersOnUsers(1);
      Future<?> suspending = pool.submit(() -> suspendRecordingSessionsSeen(id, seen));
      awaitWaitersOnUsers(2);

      release.countDown();
      Login login = loggingIn.get(30, TimeUnit.SECONDS);
      suspending.get(30, TimeUnit.SECONDS);

      /*
       * 정지가 잠금을 잡은 순간 세션이 이미 저장소에 있어야 한다. 이것이 "저장까지가 잠금 안"의
       * 정의다 — 최종 상태만 보면 저장을 잠금 밖으로 뺀 구현도 대개 통과한다.
       */
      assertThat(seen[0]).as("정지가 잠금을 잡았을 때 로그인 세션이 이미 저장돼 있어야 한다").isEqualTo(1);

      assertThat(statusInDatabase()).isEqualTo(Status.SUSPENDED);
      Session session = stored(login.sessionId());
      assertThat(session).isNotNull();
      assertThat(session.<Status>getAttribute(AuthSession.STATUS))
          .as("세션이 DB와 어긋나면 정지된 사람이 만료까지 계속 이용한다")
          .isEqualTo(Status.SUSPENDED);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  /**
   * T-204 — 계정을 읽은 <b>뒤, 발급을 위해 잠그기 전에</b> 사라지면 아무것도 만들지 않는다 (2-2 §2-2-4 MUST).
   *
   * <p><b>핸들러 앞단에서 걸리는 것과 다른 경로다.</b> 첫 조회 전에 지우면 {@code findByGoogleSub}가 먼저 실패해 발급에 들어가지도 않는다 —
   * 그러면 이 분기가 회귀해도 잡히지 않는다. 그래서 발급만 따로 부른다.
   *
   * <p>세션을 만들어 두면 계정 없는 사람이 만료까지 인증된다 — 필터는 매 요청 {@code users}를 읽지 않는다(3-3 결정 12). 승인·정지와 달리
   * <b>재로그인으로 회복할 계정조차 없다.</b>
   */
  @Test
  void issuesNothingWhenTheAccountVanishesBeforeTheLock() {
    Browser authorization = startAuthorization();
    Long id = member.getId();
    transaction.executeWithoutResult(ignored -> userRepository.deleteById(id));

    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v1/login/oauth2/code/google");
    request.setCookies(authorization.cookie());
    MockHttpServletResponse response = new MockHttpServletResponse();
    boolean[] issued = {true};

    run(
        request,
        response,
        (wrapped, wrappedResponse) -> issued[0] = issuer.issue(wrapped, wrappedResponse, id, "/"));

    assertThat(issued[0]).isFalse();
    assertThat(sessionsOf(id)).isEmpty();
    assertThat(browser(response)).as("SESSION 쿠키를 굽지 않는다").isNull();
    assertThat(response.isCommitted()).as("응답을 내보내지 않는다").isFalse();
  }

  /** 앞단에서 걸리는 쪽 — 첫 조회부터 실패하면 계약대로 로그인 화면으로 되돌린다. */
  @Test
  void redirectsToLoginWhenTheAccountIsAlreadyGone() {
    Browser authorization = startAuthorization();
    Long id = member.getId();
    transaction.executeWithoutResult(ignored -> userRepository.deleteById(id));

    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v1/login/oauth2/code/google");
    request.setCookies(authorization.cookie());
    MockHttpServletResponse response = new MockHttpServletResponse();

    run(
        request,
        response,
        (wrapped, wrappedResponse) ->
            handler.onAuthenticationSuccess(wrapped, wrappedResponse, authentication()));

    assertThat(sessionsOf(id)).isEmpty();
    assertThat(response.getHeader(HttpHeaders.LOCATION)).contains("/login?error=failed");
  }

  /**
   * 정지하되, <b>잠금을 잡은 그 순간</b> 그 사람의 세션이 저장소에 몇 개 있는지 적어 둔다.
   *
   * <p>로그인이 먼저 줄을 섰으므로 여기 들어왔다는 것은 <b>로그인의 트랜잭션이 끝났다</b>는 뜻이다. 저장이 잠금 안에서 끝났다면 그때 세션은 이미 있다.
   */
  private void suspendRecordingSessionsSeen(Long userId, int[] seen) {
    transaction.executeWithoutResult(
        ignored -> {
          User locked = userRepository.findByIdForUpdate(userId).orElseThrow();
          seen[0] = sessionsOf(userId).size();
          locked.suspend();
        });
    sessionSynchronizer.refresh(List.of(userId));
  }

  /* ------------------------------------------------------------------ 대기 */

  /**
   * 행 잠금을 기다리는 트랜잭션이 그만큼 쌓일 때까지 기다린다.
   *
   * <p><b>{@code users} 관계 잠금을 보면 안 된다.</b> {@code SELECT ... FOR UPDATE}는 관계 잠금(RowShareLock)을 곧바로
   * 받고, 실제로 기다리는 것은 <b>먼저 그 행을 잡은 트랜잭션의 {@code transactionid}</b>다. 관계 쪽만 세면 대기가 영영 보이지 않는다.
   */
  private void awaitWaitersOnUsers(int expected) throws InterruptedException {
    for (int attempt = 0; attempt < 600; attempt++) {
      Integer waiting =
          jdbcTemplate.queryForObject(
              "SELECT count(*) FROM pg_locks"
                  + " WHERE locktype IN ('transactionid', 'tuple') AND NOT granted",
              Integer.class);
      if (waiting != null && waiting >= expected) {
        return;
      }
      TimeUnit.MILLISECONDS.sleep(50);
    }
    throw new IllegalStateException("잠금을 기다리는 트랜잭션이 " + expected + "개가 되지 않았다");
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
