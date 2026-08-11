package org.hackerkhu.hackerhp.global.config;

import java.util.Locale;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Component;

/**
 * 로그인을 허용할 구글 계정인지 판단한다.
 *
 * <p><b>매 로그인마다 검사한다</b> (spec 3-1 §3-1-5 MUST). 최초 가입 때 한 번만 확인하고 넘어가지 않는다 — 학교가 계정을 회수하거나 이메일 인증이
 * 풀린 경우를 놓친다.
 *
 * <p>거절 사유는 {@link LoginErrorCode}로만 표현한다. 그 값이 그대로 {@code /login?error=...}에 실리므로, 이메일·토큰·예외 메시지가
 * 섞이면 주소창과 브라우저 기록에 남는다 (3-2 §3-2-3 MUST).
 */
@Component
public class GoogleAccountPolicy {

  private final String allowedSuffix;

  public GoogleAccountPolicy(AuthProperties authProperties) {
    this.allowedSuffix = "@" + authProperties.allowedEmailDomain().toLowerCase(Locale.ROOT);
  }

  /**
   * @throws OAuth2AuthenticationException 허용하지 않는 계정일 때
   */
  public void verify(String email, Boolean emailVerified) {
    // 구글이 본인 인증을 마치지 않은 주소다. 도메인만 맞으면 남의 학교 주소를 주장할 수 있다.
    if (!Boolean.TRUE.equals(emailVerified)) {
      throw reject(LoginErrorCode.UNVERIFIED);
    }
    if (email == null || !email.toLowerCase(Locale.ROOT).endsWith(allowedSuffix)) {
      throw reject(LoginErrorCode.DOMAIN);
    }
  }

  /*
   * OAuth2Error의 errorCode에 사유를 싣는다. 실패 핸들러가 이 값을 읽어 리다이렉트 쿼리를 만든다.
   * description에는 아무것도 담지 않는다 — 응답 어딘가로 새어나갈 자리를 만들지 않는다.
   */
  private OAuth2AuthenticationException reject(LoginErrorCode code) {
    return new OAuth2AuthenticationException(new OAuth2Error(code.value()), code.value());
  }
}
