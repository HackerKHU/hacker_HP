package org.hackerkhu.hackerhp.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

/** 허용 도메인·이메일 인증 검사 (spec 3-1 §3-1-5 MUST). */
class GoogleAccountPolicyTest {

  private final GoogleAccountPolicy policy =
      new GoogleAccountPolicy(new AuthProperties("khu.ac.kr"));

  @Test
  void allowsVerifiedAccountOnAllowedDomain() {
    assertThatCode(() -> policy.verify("member@khu.ac.kr", true)).doesNotThrowAnyException();
  }

  /* 구글이 대소문자를 어떻게 주든 같은 주소다. */
  @Test
  void domainCheckIsCaseInsensitive() {
    assertThatCode(() -> policy.verify("Member@KHU.AC.KR", true)).doesNotThrowAnyException();
  }

  /*
   * T-08·T-53·T-54·T-136 — 허용 도메인이 아닌 계정은 인증되지 않는다.
   *
   * 이 목록이 검사 방식을 못박는다 (spec 3-1 §3-1-4 MUST).
   *   notkhu.ac.kr / cs.khu.ac.kr  → endsWith(도메인)로 짜면 통과한다
   *   khu.ac.kr.evil.com           → 공격자가 자기 도메인의 하위에 붙인 것이다
   *   khu.ac.kr@gmail.com          → contains로 짜면 통과한다
   *   a@b@khu.ac.kr                → 마지막 @를 기준으로 자르면 통과한다
   */
  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(
      strings = {
        "someone@gmail.com",
        "someone@notkhu.ac.kr",
        "someone@cs.khu.ac.kr",
        "someone@khu.ac.kr.evil.com",
        "someone@evilkhu.ac.kr",
        "khu.ac.kr@gmail.com",
        "a@b@khu.ac.kr",
        "khu.ac.kr",
        "@khu.ac.kr.",
        ""
      })
  void rejectsAccountOutsideAllowedDomain(String email) {
    assertThatThrownBy(() -> policy.verify(email, true))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .hasMessage(LoginErrorCode.DOMAIN.value());
  }

  /*
   * T-42 — hd 주장만 khu.ac.kr이고 email이 다르면 거부한다.
   *
   * 이 검사에는 hd가 아예 들어오지 않는다. 구글의 hd는 로그인 화면에서 계정을 좁혀주는 힌트일 뿐
   * 위조를 막지 못하므로, 서버는 ID 토큰의 email만 본다 (spec 3-1 §3-1-4 MUST).
   * 판단 근거를 email 하나로 좁혀두는 것이 곧 이 요구사항을 만족시키는 방법이다.
   */
  @Test
  void decisionRestsOnEmailAlone() {
    assertThatThrownBy(() -> policy.verify("someone@gmail.com", true))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .hasMessage(LoginErrorCode.DOMAIN.value());
  }

  /*
   * 인증되지 않은 주소는 도메인이 맞아도 거절한다. 구글이 소유를 확인해 주지 않은 값이라,
   * 통과시키면 남의 학교 주소를 주장하는 계정이 들어온다.
   */
  @Test
  void rejectsUnverifiedEmailEvenOnAllowedDomain() {
    assertThatThrownBy(() -> policy.verify("member@khu.ac.kr", false))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .hasMessage(LoginErrorCode.UNVERIFIED.value());
  }

  @Test
  void rejectsMissingEmailVerifiedClaim() {
    assertThatThrownBy(() -> policy.verify("member@khu.ac.kr", null))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .hasMessage(LoginErrorCode.UNVERIFIED.value());
  }

  @Test
  void rejectsMissingEmail() {
    assertThatThrownBy(() -> policy.verify(null, true))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .hasMessage(LoginErrorCode.DOMAIN.value());
  }

  /*
   * T-137 — 계약에 없는 사유는 전부 failed다. Spring 내부 코드가 그대로 쿼리에 실리면
   * 주소창·브라우저 기록·리퍼러에 남고, 이용자가 스스로 고칠 수 있는 정보도 아니다.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {"invalid_state", "authorization_request_not_found", "invalid_token_response"})
  void unknownReasonsCollapseToFailed(String springErrorCode) {
    assertThat(LoginErrorCode.from(springErrorCode)).isEqualTo(LoginErrorCode.FAILED);
  }

  @Test
  void knownReasonsSurvive() {
    assertThat(LoginErrorCode.from("domain")).isEqualTo(LoginErrorCode.DOMAIN);
    assertThat(LoginErrorCode.from(null)).isEqualTo(LoginErrorCode.FAILED);
  }
}
