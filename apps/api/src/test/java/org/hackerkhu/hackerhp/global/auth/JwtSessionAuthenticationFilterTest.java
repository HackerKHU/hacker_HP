package org.hackerkhu.hackerhp.global.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * <b>토큰과 세션이 같은 사용자의 것일 때만</b> 인증이 성립하는지 (spec 3-1 §3-1-5 MUST, T-29·T-30·T-31).
 *
 * <p>이 대조가 이 설계의 시험대다. 빠지면 신원과 인가를 나눈 것이 그대로 취약점이 된다 — A의 토큰과 B의 세션을 합쳐 A의 신원으로 B의 권한을 쓸 수 있다.
 */
class JwtSessionAuthenticationFilterTest {

  private static final String SECRET = "filter-test-only-jwt-secret-32bytes-or-more";

  private final JwtProvider jwtProvider = new JwtProvider(SECRET, Duration.ofMinutes(30));
  private final AccessTokenCookie accessTokenCookie = new AccessTokenCookie(new ServerProperties());
  private final JwtSessionAuthenticationFilter filter =
      new JwtSessionAuthenticationFilter(jwtProvider, accessTokenCookie);

  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final MockHttpServletResponse response = new MockHttpServletResponse();

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private MockHttpSession sessionFor(Long userId, Role role) {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(AuthSession.USER_ID, userId);
    session.setAttribute(AuthSession.ROLE, role);
    session.setAttribute(AuthSession.STATUS, Status.ACTIVE);
    return session;
  }

  private void withToken(Long userId) {
    request.setCookies(new Cookie("ACCESS_TOKEN", jwtProvider.issue(userId)));
  }

  private Authentication runFilter() throws Exception {
    filter.doFilter(request, response, new MockFilterChain());
    return SecurityContextHolder.getContext().getAuthentication();
  }

  private boolean accessTokenCleared() {
    Cookie cookie = response.getCookie("ACCESS_TOKEN");
    return cookie != null && cookie.getMaxAge() == 0;
  }

  @Test
  void matchingTokenAndSessionAuthenticate() throws Exception {
    withToken(7L);
    request.setSession(sessionFor(7L, Role.ADMIN));

    Authentication authentication = runFilter();

    assertThat(authentication).isNotNull();
    assertThat(authentication.getPrincipal()).isEqualTo(7L);
    assertThat(authentication.getAuthorities())
        .extracting("authority")
        .containsExactly("ROLE_ADMIN");
  }

  /*
   * T-29 — A의 토큰과 B의 세션. 401로 끝나는 것으로 부족하고 양쪽을 폐기해야 한다.
   * 한쪽만 버리면 남은 쪽으로 계속 시도할 수 있다.
   */
  @Test
  void mismatchedUserDiscardsBothCredentials() throws Exception {
    withToken(7L);
    MockHttpSession otherUsersSession = sessionFor(8L, Role.ADMIN);
    request.setSession(otherUsersSession);

    assertThat(runFilter()).isNull();
    assertThat(otherUsersSession.isInvalid()).isTrue();
    assertThat(accessTokenCleared()).isTrue();
  }

  /*
   * T-30 — 서명이 유효해도 세션이 없으면 통과하지 못한다. 로그아웃과 강제 차단이
   * "세션을 지운다" 하나로 성립하는 근거다 (3-3 결정 12).
   */
  @Test
  void validTokenWithoutSessionIsRejected() throws Exception {
    withToken(7L);

    assertThat(runFilter()).isNull();
    assertThat(accessTokenCleared()).isTrue();
  }

  /* 세션은 있는데 로그인 상태가 아닌 경우도 같다 — 인가 요청만 담긴 세션이 그렇다. */
  @Test
  void tokenWithSessionThatHasNoUserIsRejected() throws Exception {
    withToken(7L);
    request.setSession(new MockHttpSession());

    assertThat(runFilter()).isNull();
  }

  /* T-31 — 세션만 있고 토큰이 없다. */
  @Test
  void sessionWithoutTokenIsRejected() throws Exception {
    MockHttpSession session = sessionFor(7L, Role.USER);
    request.setSession(session);

    assertThat(runFilter()).isNull();
    // 로그인 흐름 중일 수 있으므로 세션은 건드리지 않는다.
    assertThat(session.isInvalid()).isFalse();
  }

  @Test
  void forgedTokenIsRejected() throws Exception {
    JwtProvider attacker =
        new JwtProvider("another-secret-32bytes-or-more-for-hs256", Duration.ofMinutes(30));
    request.setCookies(new Cookie("ACCESS_TOKEN", attacker.issue(7L)));
    request.setSession(sessionFor(7L, Role.ADMIN));

    assertThat(runFilter()).isNull();
  }

  /* 자격이 하나도 없으면 아무 일도 하지 않는다. 비로그인 방문자에게 쿠키를 내려 보내지 않는다. */
  @Test
  void anonymousRequestIsUntouched() throws Exception {
    assertThat(runFilter()).isNull();
    assertThat(response.getCookies()).isEmpty();
  }
}
