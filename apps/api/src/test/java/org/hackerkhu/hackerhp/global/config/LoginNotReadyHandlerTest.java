package org.hackerkhu.hackerhp.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

/**
 * 구글 인증에 성공한 경로가 <b>로그인으로 이어지지 않는지</b> 본다.
 *
 * <p>#25·#26이 들어오면 이 테스트와 {@link LoginNotReadyHandler}를 함께 지운다.
 */
class LoginNotReadyHandlerTest {

  private final LoginNotReadyHandler handler = new LoginNotReadyHandler("/login");

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private static Authentication googleAuthentication() {
    return new OAuth2AuthenticationToken(
        new DefaultOAuth2User(
            List.of(new SimpleGrantedAuthority("OAUTH2_USER")),
            Map.of("sub", "google-sub-1", "email", "member@khu.ac.kr"),
            "sub"),
        List.of(new SimpleGrantedAuthority("OAUTH2_USER")),
        "google");
  }

  /*
   * T-139 — 허용 도메인 계정이 인증에 성공해도 세션이 남지 않는다.
   *
   * 도메인 검사를 지났다는 것은 "우리 학교 사람"이라는 뜻일 뿐 "가입이 승인된 회원"이 아니다.
   * 여기서 끊지 않으면 users 행도 status 확인도 없는 신원이 authenticated()를 통과한다.
   */
  @Test
  void successfulAuthenticationLeavesNoSession() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpSession session = new MockHttpSession();
    request.setSession(session);
    SecurityContextHolder.getContext().setAuthentication(googleAuthentication());

    handler.onAuthenticationSuccess(request, new MockHttpServletResponse(), googleAuthentication());

    assertThat(session.isInvalid()).isTrue();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  /* 사용자는 SPA의 로그인 화면으로 돌아간다. 콜백 경로의 빈 화면에 갇히지 않는다. */
  @Test
  void redirectsToLoginPage() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, googleAuthentication());

    assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=failed");
  }

  /* 세션이 없는 경우에도 터지지 않는다. */
  @Test
  void toleratesMissingSession() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, googleAuthentication());

    assertThat(response.getStatus()).isEqualTo(302);
  }
}
