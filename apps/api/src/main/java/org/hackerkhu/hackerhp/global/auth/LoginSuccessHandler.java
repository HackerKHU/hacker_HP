package org.hackerkhu.hackerhp.global.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.service.GoogleAccountService;
import org.hackerkhu.hackerhp.global.config.LoginErrorCode;
import org.hackerkhu.hackerhp.global.config.LoginFailureHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * 구글 인증이 끝난 뒤 <b>세션과 토큰을 함께 발급하고</b> SPA로 되돌린다 (spec 3-1 §3-1-5).
 *
 * <p>둘 중 하나만 주면 다음 요청이 인증되지 않는다. 신원은 토큰이, 인가 상태는 세션이 담당하며 둘이 같은 사용자의 것이어야 인증이 성립한다.
 *
 * <p><b>성공은 항상 {@code /}로 되돌린다</b> (spec 3-2 §3-2-3 MUST). 사용자 정보를 응답에 싣지 않는다 — 화면은 {@code GET
 * /auth/me}로 갈 곳을 정하고, 새로고침으로 세션을 복구할 때도 같은 경로를 쓴다. 여기서 정보를 실으면 같은 값을 두 곳에서 만들게 된다.
 */
@Component
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

  private static final String SPA_ROOT = "/";

  private static final Logger log = LoggerFactory.getLogger(LoginSuccessHandler.class);

  private final GoogleAccountService accountService;
  private final JwtProvider jwtProvider;
  private final AccessTokenCookie accessTokenCookie;
  private final LoginFailureHandler failureHandler;
  private final LoginSessionIssuer sessionIssuer;

  public LoginSuccessHandler(
      GoogleAccountService accountService,
      JwtProvider jwtProvider,
      AccessTokenCookie accessTokenCookie,
      LoginFailureHandler failureHandler,
      LoginSessionIssuer sessionIssuer) {
    this.accountService = accountService;
    this.jwtProvider = jwtProvider;
    this.accessTokenCookie = accessTokenCookie;
    this.failureHandler = failureHandler;
    this.sessionIssuer = sessionIssuer;
  }

  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException {
    /*
     * 계정은 콜백 처리(#25)에서 이미 만들어졌거나 갱신됐다. 여기서 다시 읽는 이유는 구글이 준 신원에는
     * 우리 users.id도 role·status도 없기 때문이다. 로그인 한 번에 조회 한 번이다.
     */
    OidcUser googleUser = (OidcUser) authentication.getPrincipal();

    User user;
    try {
      user = accountService.findByGoogleSub(googleUser.getSubject());
    } catch (RuntimeException e) {
      /*
       * 여기서 터진 예외는 실패 핸들러가 받지 못한다. 인증 필터가 이미 성공 경로에 들어섰기 때문에,
       * 그대로 두면 브라우저에 계약(3-2 §3-2-3)과 다른 500이 남고 사용자가 SPA 밖 빈 화면에 갇힌다.
       */
      log.warn("로그인 성공 처리 중 계정을 읽지 못했다. sub={}", googleUser.getSubject(), e);
      failureHandler.redirect(response, LoginErrorCode.FAILED);
      return;
    }

    String sessionId = sessionIssuer.open(request, user).getId();
    accessTokenCookie.write(response, jwtProvider.issue(user.getId()), jwtProvider.expiry());

    /*
     * 이 요청의 SecurityContext는 버린다. 인증은 다음 요청부터 JwtSessionAuthenticationFilter가
     * 토큰과 세션을 대조해 세운다 — 여기 남겨 두면 그 대조를 거치지 않은 인증이 한 번 존재하게 된다.
     */
    SecurityContextHolder.clearContext();

    /*
     * 여기서 응답이 커밋되고, 그때 SessionRepositoryFilter가 세션을 저장하며 SESSION 쿠키를 굽는다.
     * 세션을 저장소에 넣는 시점이 여기다 (#127).
     */
    response.sendRedirect(SPA_ROOT);

    /*
     * 대조는 저장 뒤에 한다. 앞에서 하면 아직 없는 세션을 두고 대조하는 셈이라 창이 닫히지 않는다.
     *
     * 그래서 실패를 응답으로 알릴 수 없다 — 응답은 이미 나갔다. 대신 세션을 거둬들인다.
     * 다음 요청이 401이 되고 화면이 로그인으로 되돌리므로 결과는 로그인 실패와 같다.
     */
    sessionIssuer.settle(sessionId, user.getId());
  }
}
