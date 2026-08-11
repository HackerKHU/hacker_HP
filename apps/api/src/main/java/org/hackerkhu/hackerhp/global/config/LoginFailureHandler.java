package org.hackerkhu.hackerhp.global.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * 콜백 실패를 SPA의 로그인 화면으로 되돌린다 (spec 3-2 §3-2-3 MUST).
 *
 * <p>기본 처리는 콜백 경로에 오류 응답을 남긴다. 브라우저 전체가 이동한 흐름이라 <b>사용자가 SPA 밖의 빈 화면에 갇힌다</b> — 프론트엔드의 공통 오류 처리도
 * {@code request()}를 거치지 않아 동작하지 않는다 (T-43).
 *
 * <p>쿼리에 싣는 것은 {@link LoginErrorCode}에 있는 사유뿐이다 (T-44). Spring의 내부 코드나 예외 메시지를 그대로 쓰면 주소창·브라우저
 * 기록·리퍼러에 남고, 이용자가 스스로 고칠 수 있는 정보도 아니다.
 */
class LoginFailureHandler implements AuthenticationFailureHandler {

  private final String loginPagePath;

  LoginFailureHandler(String loginPagePath) {
    this.loginPagePath = loginPagePath;
  }

  @Override
  public void onAuthenticationFailure(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    LoginErrorCode code =
        exception instanceof OAuth2AuthenticationException oauthException
            ? LoginErrorCode.from(oauthException.getError().getErrorCode())
            : LoginErrorCode.FAILED;
    response.sendRedirect(loginPagePath + "?error=" + code.value());
  }
}
