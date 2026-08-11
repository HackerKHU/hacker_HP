package org.hackerkhu.hackerhp.global.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * 구글 인증이 성공해도 <b>로그인을 성립시키지 않는다.</b> 계정 조회(#25)와 세션 발급(#26)이 들어올 때까지만 있는 임시 처리다.
 *
 * <p>인증은 JWT와 서버 세션이 <b>함께</b> 성립해야 한다 (spec 3-1 §3-1-5 MUST). 세션에는 사용자 id·{@code role}·{@code
 * status}가 들어가고, 매 요청 JWT의 {@code sub}와 대조된다. 지금은 {@code users} 행을 만드는 코드도 JWT를 발급하는 코드도 없어 그 어느 쪽도
 * 채울 수 없다.
 *
 * <p>여기서 끊지 않으면 기본 {@code oauth2Login()}이 {@code OAuth2AuthenticationToken}을 그대로 세션에 저장한다. 그러면
 * <b>계정도 승인 상태 확인도 없는 신원이 {@code .anyRequest().authenticated()}를 통과한다.</b> 허용 도메인 검사({@link
 * GoogleAccountPolicy})를 지났다는 것은 "우리 학교 사람"이라는 뜻일 뿐, "가입이 승인된 회원"이라는 뜻이 아니다.
 *
 * <p><b>#26이 이 클래스를 지운다.</b> 남아 있으면 로그인이 영영 성립하지 않으므로 잊힐 수가 없다.
 */
class LoginNotReadyHandler implements AuthenticationSuccessHandler {

  private static final Logger log = LoggerFactory.getLogger(LoginNotReadyHandler.class);

  private final String redirectUrl;

  LoginNotReadyHandler(String loginPagePath) {
    this.redirectUrl = loginPagePath + "?error=" + LoginErrorCode.FAILED.value();
  }

  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException {
    log.warn("구글 인증은 통과했지만 계정 처리와 세션 발급이 아직 없다 (#25·#26). 로그인을 취소한다.");

    // 필터가 이미 SecurityContext를 세션에 저장한 뒤 이 핸들러를 부른다. 지우는 것으로는
    // 부족하고 세션 자체를 버려야 저장된 신원이 남지 않는다.
    SecurityContextHolder.clearContext();
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }

    response.sendRedirect(redirectUrl);
  }
}
