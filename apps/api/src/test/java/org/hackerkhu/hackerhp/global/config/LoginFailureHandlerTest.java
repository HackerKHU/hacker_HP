package org.hackerkhu.hackerhp.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/** 콜백 실패가 어떤 주소로 되돌아가는지 (spec 3-2 §3-2-3, T-43·T-44). */
class LoginFailureHandlerTest {

  private final LoginFailureHandler handler = new LoginFailureHandler("/login");

  private String redirectFor(String oauthErrorCode) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    handler.onAuthenticationFailure(
        new MockHttpServletRequest(),
        response,
        new OAuth2AuthenticationException(new OAuth2Error(oauthErrorCode), oauthErrorCode));
    return response.getRedirectedUrl();
  }

  /* 계약의 사유는 그대로 화면에 전달된다. 화면이 이 값으로 안내 문구를 고른다. */
  @ParameterizedTest(name = "{0} → {1}")
  @CsvSource({
    "domain, /login?error=domain",
    "unverified, /login?error=unverified",
    "suspended, /login?error=suspended",
    "failed, /login?error=failed"
  })
  void contractCodesReachTheLoginPage(String code, String expected) throws Exception {
    assertThat(redirectFor(code)).isEqualTo(expected);
  }

  /*
   * T-44 — 계약에 없는 사유는 쿼리에 실리지 않는다.
   *
   * Spring 내부 코드는 주소창·브라우저 기록·리퍼러에 남고, 이용자가 스스로 고칠 수 있는 정보도 아니다.
   * 화이트리스트라 새 내부 코드가 생겨도 새어나가지 않는다.
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "invalid_state",
    "authorization_request_not_found",
    "invalid_token_response",
    "[invalid_user_info_response] 500 Internal Server Error"
  })
  void springInternalCodesNeverLeakIntoTheQuery(String springErrorCode) throws Exception {
    assertThat(redirectFor(springErrorCode)).isEqualTo("/login?error=failed");
  }

  /* OAuth2 예외가 아닌 인증 실패도 같은 경로로 되돌린다. */
  @Test
  void nonOauthFailuresAlsoRedirect() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationFailure(
        new MockHttpServletRequest(), response, new BadCredentialsException("내부 사정"));

    assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=failed");
  }

  /* T-43 — 본문이 아니라 리다이렉트다. 브라우저가 SPA 밖에 남지 않는다. */
  @Test
  void respondsWithRedirectNotJson() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationFailure(
        new MockHttpServletRequest(),
        response,
        new OAuth2AuthenticationException(new OAuth2Error("domain"), "domain"));

    assertThat(response.getStatus()).isEqualTo(302);
    assertThat(response.getContentAsString()).isEmpty();
  }
}
