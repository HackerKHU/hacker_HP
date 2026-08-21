package org.hackerkhu.hackerhp.global.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
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

  /** 콜백을 <b>핸들러로</b> 끝까지 밟는다. 배선이 맞는지 보는 쪽이다. */
  private Login logIn() {
    return callback(
        (wrapped, wrappedResponse) ->
            handler.onAuthenticationSuccess(wrapped, wrappedResponse, authentication()));
  }

  /**
   * 같은 순서를 밟되 <b>저장과 대조 사이에</b> 끼어든다.
   *
   * <p>핸들러 밖에서는 그 자리를 잡을 수 없다 — 저장(`sendRedirect`)과 대조(`settle`)가 한 메서드 안에 붙어 있기 때문이다. 그래서 발급 절차를
   * 그대로 펼쳐 놓고 그 사이에 변경을 끼운다. <b>이 이슈가 말하는 두 순서 중 하나가 정확히 그 자리다.</b>
   */
  private Login logInWhile(Runnable between) {
    return callback(
        (wrapped, wrappedResponse) -> {
          String sessionId = issuer.open(wrapped, member).getId();
          // 응답이 나가면서 필터가 세션을 저장한다. 여기까지가 "저장"이다.
          wrappedResponse.sendRedirect("/");
          between.run();
          issuer.settle(sessionId, member.getId());
        });
  }

  /**
   * 콜백이 도착한 상태를 만든다.
   *
   * <p>인가 요청이 쓰던 세션이 <b>이미 있는 상태</b>로 시작한다 — 구글로 보내는 단계에서 {@code
   * HttpSessionOAuth2AuthorizationRequestRepository}가 만든 것이다.
   */
  private Login callback(Callback body) {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/v1/login/oauth2/code/google");
    MockHttpServletResponse response = new MockHttpServletResponse();
    String[] prior = new String[1];

    HttpServlet servlet =
        new HttpServlet() {
          @Override
          protected void service(HttpServletRequest wrapped, HttpServletResponse wrappedResponse)
              throws java.io.IOException {
            prior[0] = wrapped.getSession(true).getId();
            body.run(wrapped, wrappedResponse);
          }
        };

    try {
      sessionRepositoryFilter.doFilter(request, response, new MockFilterChain(servlet));
    } catch (Exception e) {
      throw new IllegalStateException("로그인 흐름이 예외로 끝났다", e);
    }
    return new Login(prior[0], issuedSessionId(response));
  }

  @FunctionalInterface
  private interface Callback {
    void run(HttpServletRequest request, HttpServletResponse response) throws java.io.IOException;
  }

  private record Login(String priorSessionId, String sessionId) {}

  /**
   * 브라우저가 실제로 들고 가게 되는 {@code SESSION} 값.
   *
   * <p><b>마지막에 구워진 것이 이긴다.</b> 세션을 저장소에 직접 만들면 필터가 자기 판단으로 쿠키를 덮어쓰거나 만료시키는데, 그러면 로그인이 조용히 깨진다 — 헤더를
   * 그대로 읽어 그 일이 없는지 본다.
   */
  private String issuedSessionId(MockHttpServletResponse response) {
    List<String> cookies =
        response.getHeaders(HttpHeaders.SET_COOKIE).stream()
            .filter(header -> header.startsWith(SESSION_COOKIE + "="))
            .toList();
    if (cookies.isEmpty()) {
      return null;
    }
    String last = cookies.get(cookies.size() - 1);
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
    MockHttpServletRequest carrier = new MockHttpServletRequest();
    carrier.setCookies(new Cookie(SESSION_COOKIE, raw));
    return cookieSerializer.readCookieValues(carrier).stream().findFirst().orElse(null);
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

  private void inAnotherTransaction(java.util.function.Consumer<User> change) {
    Long id = member.getId();
    transaction.executeWithoutResult(
        ignored -> change.accept(userRepository.findByIdForUpdate(id).orElseThrow()));
  }

  /* ------------------------------------------------------------------ 사례 */

  /**
   * 발급의 기본 — <b>쿠키가 살아 있고 그 세션이 이미 저장소에 있다.</b>
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

  /** 세션 고정 보호 — 로그인 전후로 id가 바뀐다. */
  @Test
  void rotatesTheSessionId() {
    Login login = logIn();

    assertThat(login.priorSessionId()).isNotNull();
    assertThat(login.sessionId()).isNotEqualTo(login.priorSessionId());
  }

  /** 인가 요청이 쓰던 세션이 유령으로 남지 않는다. */
  @Test
  void discardsTheAuthorizationRequestSession() {
    Login login = logIn();

    assertThat(stored(login.priorSessionId())).isNull();
    assertThat(sessionsOf(member.getId())).hasSize(1);
  }

  /**
   * <b>이 이슈의 본체 — 변경이 대조보다 먼저 커밋되는 순서.</b>
   *
   * <p>예전에는 이 자리의 승인이 <b>아직 저장되지 않은 세션을 놓쳐</b> 세션이 {@code PENDING}으로 남았다. 그 사람은 승인됐는데도 만료까지 {@code
   * 403 PENDING_APPROVAL}을 받았다.
   */
  @Test
  void picksUpAChangeThatCommitsBeforeTheCheck() {
    Login login = logInWhile(() -> inAnotherTransaction(User::approve));

    assertThat(stored(login.sessionId()).<Status>getAttribute(AuthSession.STATUS))
        .isEqualTo(Status.ACTIVE);
  }

  /**
   * 나머지 한 순서 — 변경이 <b>대조가 끝난 뒤</b> 도착한다.
   *
   * <p>이쪽은 세션이 이미 저장소에 있으므로 {@link SessionSynchronizer}의 조회가 반드시 찾는다. <b>두 순서가 모두 닫혀야 창이 닫힌
   * 것이다.</b>
   */
  @Test
  void picksUpAChangeThatCommitsAfterTheCheck() {
    Login login = logIn();

    inAnotherTransaction(User::approve);
    sessionSynchronizer.refresh(List.of(member.getId()));

    assertThat(stored(login.sessionId()).<Status>getAttribute(AuthSession.STATUS))
        .isEqualTo(Status.ACTIVE);
  }

  /** 정지가 겹쳐도 같다. <b>이쪽이 실제 피해가 나는 방향이다</b> — 정지된 사람이 계속 이용한다 (2-2 §2-2-3 "즉시 차단" MUST). */
  @Test
  void picksUpASuspensionThatCommitsBeforeTheCheck() {
    userRepository.deleteAll();
    member = userRepository.saveAndFlush(Accounts.approved(GOOGLE_SUB, EMAIL, "20250001"));

    Login login = logInWhile(() -> inAnotherTransaction(User::suspend));

    assertThat(stored(login.sessionId()).<Status>getAttribute(AuthSession.STATUS))
        .isEqualTo(Status.SUSPENDED);
  }

  /**
   * 로그인 도중 계정이 사라지면 <b>세션을 남기지 않는다</b> (2-2 §2-2-4 MUST).
   *
   * <p>남기면 계정 없는 사람이 만료까지 인증된다 — 필터는 매 요청 {@code users}를 읽지 않는다(3-3 결정 12). 승인·정지와 달리 <b>재로그인으로 회복할
   * 계정조차 없다.</b>
   */
  @Test
  void leavesNoSessionWhenTheAccountIsGone() {
    Long id = member.getId();
    Login login =
        logInWhile(() -> transaction.executeWithoutResult(i -> userRepository.deleteById(id)));

    assertThat(stored(login.sessionId())).isNull();
    assertThat(sessionsOf(id)).isEmpty();
  }
}
