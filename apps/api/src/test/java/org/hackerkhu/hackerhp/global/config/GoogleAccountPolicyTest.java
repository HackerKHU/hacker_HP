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
   * T-136 — 허용 도메인이 아닌 계정은 인증되지 않는다.
   *
   * 마지막 둘이 이 검사의 핵심이다. 문자열 포함(contains)으로 짰다면 통과해 버린다 —
   * 공격자가 도메인을 이름에 넣거나 자기 도메인의 하위에 붙이면 그만이다.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "someone@gmail.com",
        "someone@khu.ac.kr.evil.com",
        "someone@evilkhu.ac.kr",
        "khu.ac.kr@gmail.com"
      })
  void rejectsAccountOutsideAllowedDomain(String email) {
    assertThatThrownBy(() -> policy.verify(email, true))
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
