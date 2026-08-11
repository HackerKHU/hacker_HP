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

  private final String allowedDomain;

  public GoogleAccountPolicy(AuthProperties authProperties) {
    this.allowedDomain = authProperties.allowedEmailDomain().toLowerCase(Locale.ROOT);
  }

  /**
   * @throws OAuth2AuthenticationException 허용하지 않는 계정일 때
   */
  public void verify(String email, Boolean emailVerified) {
    // 구글이 본인 인증을 마치지 않은 주소다. 도메인만 맞으면 남의 학교 주소를 주장할 수 있다.
    if (!Boolean.TRUE.equals(emailVerified)) {
      throw reject(LoginErrorCode.UNVERIFIED);
    }
    if (!isAllowedDomain(email)) {
      throw reject(LoginErrorCode.DOMAIN);
    }
  }

  /**
   * <b>{@code @} 뒤를 잘라 설정값과 정확히 일치하는지 본다</b> (spec 3-1 §3-1-4 MUST).
   *
   * <p>{@code endsWith}로 검사하면 {@code user@notkhu.ac.kr}이 통과한다. 하위 도메인({@code user@cs.khu.ac.kr})도
   * 정확 일치가 아니므로 거부한다 — 필요해지면 허용 목록을 늘리는 것이 결정이고, 접미사 검사로 흘리는 것은 결정이 아니다.
   *
   * <p><b>첫 {@code @}를 기준으로 자른다.</b> 마지막 {@code @}를 쓰면 {@code a@b@khu.ac.kr}처럼 {@code @}가 여럿인 주소가
   * 통과한다.
   */
  private boolean isAllowedDomain(String email) {
    if (email == null) {
      return false;
    }
    int at = email.indexOf('@');
    return at >= 0 && email.substring(at + 1).toLowerCase(Locale.ROOT).equals(allowedDomain);
  }

  /*
   * OAuth2Error의 errorCode에 사유를 싣는다. 실패 핸들러가 이 값을 읽어 리다이렉트 쿼리를 만든다.
   * description에는 아무것도 담지 않는다 — 응답 어딘가로 새어나갈 자리를 만들지 않는다.
   */
  private OAuth2AuthenticationException reject(LoginErrorCode code) {
    return new OAuth2AuthenticationException(new OAuth2Error(code.value()), code.value());
  }
}
