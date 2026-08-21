package org.hackerkhu.hackerhp.global.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 자격 증명 결합을 <b>실제 필터 체인과 실제 세션 저장소로</b> 확인한다 (spec 5-TESTING §5-2 MUST, T-29·T-30·T-31).
 *
 * <p>필터를 직접 부르는 단위 테스트만으로는 부족하다. 필터 순서가 어긋나거나, Spring Session이 {@code SESSION} 쿠키로 세션을 복원하는 경로가
 * 깨지거나, 폐기가 응답 쿠키까지 이어지지 않는 경우를 잡지 못한다. 여기서는 저장소에 세션을 만들어 진짜 쿠키를 실어 보낸다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationBindingIntegrationTest extends AbstractIntegrationTest {

  /**
   * 결합이 깨졌을 때 <b>막히는지</b>를 보는 자리.
   *
   * <p>{@code /auth/me}를 쓸 수 없다 — 최초 진입의 세션 확인이라 비로그인에게도 열려 있어(#190), 결합이 깨져도 {@code 401}이 아니라
   * {@code 204}로 답한다. 그것은 그것대로 옳지만 <b>"막힌다"를 보여주지는 못한다.</b>
   *
   * <p>{@code AccountStatusFilter}는 인증이 성립하지 않은 요청을 건드리지 않으므로, 상태와 무관하게 {@code 401}이 나온다.
   */
  private static final String PROTECTED_PATH = "/api/v1/notices";

  /** 정상 결합이 <b>무엇을 돌려주는지</b> 보는 자리. 상태와 무관하게 열려 있다. */
  private static final String IDENTITY_PATH = "/api/v1/auth/me";

  private static final String SESSION_COOKIE = "SESSION";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private JwtProvider jwtProvider;
  // JdbcSession 타입이 공개되어 있지 않아 인터페이스로 받는다.
  @Autowired private SessionRepository<? extends Session> sessionRepository;
  @Autowired private JdbcIndexedSessionRepository jdbcSessions;
  @Autowired private DefaultCookieSerializer cookieSerializer;

  private User alice;
  private User bob;

  @BeforeEach
  void createAccounts() {
    userRepository.deleteAll();
    alice = userRepository.saveAndFlush(User.createFromGoogle("sub-a", "alice@khu.ac.kr", "앨리스"));
    bob = userRepository.saveAndFlush(User.createFromGoogle("sub-b", "bob@khu.ac.kr", "밥"));
  }

  @AfterEach
  void clear() {
    userRepository.deleteAll();
  }

  /** 로그인한 것과 같은 상태의 세션을 저장소에 만들고, 브라우저가 받았을 쿠키를 돌려준다. */
  private SignedInSession signIn(User user) {
    Session session = sessionRepository.createSession();
    session.setAttribute(AuthSession.USER_ID, user.getId());
    session.setAttribute(AuthSession.ROLE, user.getRole());
    session.setAttribute(AuthSession.STATUS, user.getStatus());
    save(session);

    MockHttpServletResponse carrier = new MockHttpServletResponse();
    cookieSerializer.writeCookieValue(
        new CookieSerializer.CookieValue(new MockHttpServletRequest(), carrier, session.getId()));
    return new SignedInSession(session.getId(), carrier.getCookie(SESSION_COOKIE));
  }

  private record SignedInSession(String id, Cookie cookie) {}

  @SuppressWarnings("unchecked")
  private void save(Session session) {
    ((SessionRepository<Session>) sessionRepository).save(session);
  }

  private Cookie accessToken(User user) {
    return new Cookie("ACCESS_TOKEN", jwtProvider.issue(user.getId()));
  }

  private static boolean expired(MvcResult result, String name) {
    Cookie cookie = result.getResponse().getCookie(name);
    return cookie != null && cookie.getMaxAge() == 0;
  }

  @Test
  void matchingCredentialsPassThroughTheWholeChain() throws Exception {
    SignedInSession signedIn = signIn(alice);

    mockMvc
        .perform(get(IDENTITY_PATH).cookie(signedIn.cookie(), accessToken(alice)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("alice@khu.ac.kr"));
  }

  /**
   * <b>공개 경로에서도 결합은 그대로 검사된다</b> (#190).
   *
   * <p>{@code /auth/me}가 비로그인에게 열렸다고 해서 <b>남의 세션에 내 토큰을 붙이는 것이 통하면 안 된다.</b> 인증이 서지 않으므로 계정 정보는 나가지
   * 않고({@code 204}), 필터는 양쪽 쿠키를 그대로 폐기한다.
   */
  @Test
  void aPublicPathStillRefusesMismatchedCredentials() throws Exception {
    SignedInSession bobsSession = signIn(bob);

    MvcResult result =
        mockMvc
            .perform(get(IDENTITY_PATH).cookie(bobsSession.cookie(), accessToken(alice)))
            .andExpect(status().isNoContent())
            .andReturn();

    assertThat(result.getResponse().getContentAsString()).isEmpty();
    assertThat(expired(result, "ACCESS_TOKEN")).isTrue();
    assertThat(expired(result, SESSION_COOKIE)).isTrue();
    assertThat(jdbcSessions.findById(bobsSession.id())).isNull();
  }

  /*
   * T-29 — 앨리스의 토큰과 밥의 세션.
   *
   * 401로 끝나는 것으로 부족하다. 앨리스의 신원으로 밥의 role이 쓰이지 않아야 하고, 응답이 양쪽 쿠키를
   * 폐기해야 한다. 세션은 저장소에서도 사라져야 한다 — 응답 쿠키만 지우면 서버에는 살아 있다.
   */
  @Test
  void aliceTokenWithBobSessionIsRejectedAndBothDiscarded() throws Exception {
    SignedInSession bobsSession = signIn(bob);

    MvcResult result =
        mockMvc
            .perform(get(PROTECTED_PATH).cookie(bobsSession.cookie(), accessToken(alice)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andReturn();

    assertThat(expired(result, "ACCESS_TOKEN")).isTrue();
    assertThat(expired(result, SESSION_COOKIE)).isTrue();
    assertThat(jdbcSessions.findById(bobsSession.id())).isNull();
  }

  /* T-30 — 서명이 유효한 토큰만 있고 세션이 없다. 로그아웃·만료 이후의 상태다. */
  @Test
  void tokenWithoutSessionIsRejected() throws Exception {
    mockMvc
        .perform(get(PROTECTED_PATH).cookie(accessToken(alice)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  /* T-31 — 세션만 있고 토큰이 없다. */
  @Test
  void sessionWithoutTokenIsRejected() throws Exception {
    SignedInSession signedIn = signIn(alice);

    mockMvc
        .perform(get(PROTECTED_PATH).cookie(signedIn.cookie()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

    // 로그인 흐름 중일 수 있으므로 세션은 지우지 않는다.
    assertThat(jdbcSessions.findById(signedIn.id())).isNotNull();
  }
}
