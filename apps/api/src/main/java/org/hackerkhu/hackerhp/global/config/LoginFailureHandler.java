package org.hackerkhu.hackerhp.global.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * 콜백 실패를 SPA의 로그인 화면으로 되돌린다 (spec 3-2 §3-2-3 MUST).
 *
 * <p>기본 처리는 콜백 경로에 오류 응답을 남긴다. 브라우저 전체가 이동한 흐름이라 <b>사용자가 SPA 밖의 빈 화면에 갇힌다</b> — 프론트엔드의 공통 오류 처리도
 * {@code request()}를 거치지 않아 동작하지 않는다 (T-43).
 *
 * <p>쿼리에 싣는 것은 {@link LoginErrorCode}에 있는 사유뿐이다 (T-44). Spring의 내부 코드나 예외 메시지를 그대로 쓰면 주소창·브라우저
 * 기록·리퍼러에 남고, 이용자가 스스로 고칠 수 있는 정보도 아니다.
 */
@Component
public class LoginFailureHandler implements AuthenticationFailureHandler {

  /** SPA의 로그인 화면. 서버 경로가 아니라 프론트엔드 라우트다. */
  private static final String LOGIN_PAGE_PATH = "/login";

  @Override
  public void onAuthenticationFailure(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    LoginErrorCode code =
        exception instanceof OAuth2AuthenticationException oauthException
            ? LoginErrorCode.from(oauthException.getError().getErrorCode())
            : LoginErrorCode.FAILED;
    redirect(response, code);
  }

  /**
   * 인증 필터 <b>바깥에서</b> 실패했을 때 쓴다. 성공 처리 도중 터진 예외는 {@code AbstractAuthenticationProcessingFilter}가 이미
   * 성공 경로에 들어선 뒤라, 이 핸들러가 자동으로 불리지 않고 브라우저에 500이 남는다.
   */
  public void redirect(HttpServletResponse response, LoginErrorCode code) throws IOException {
    response.sendRedirect(LOGIN_PAGE_PATH + "?error=" + code.value());
  }
}
