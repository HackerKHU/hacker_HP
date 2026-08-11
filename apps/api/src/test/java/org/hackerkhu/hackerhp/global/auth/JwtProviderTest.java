package org.hackerkhu.hackerhp.global.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.junit.jupiter.api.Test;

/** 신원 토큰이 담는 것과 담지 않는 것 (spec 3-3 결정 12). */
class JwtProviderTest {

  private static final String SECRET = "unit-test-only-jwt-secret-32bytes-or-more";

  private final JwtProvider provider = new JwtProvider(SECRET, Duration.ofMinutes(30));

  @Test
  void issuedTokenCarriesTheUserId() {
    String token = provider.issue(42L);

    assertThat(provider.readUserId(token)).contains(42L);
  }

  /*
   * role·status를 담지 않는다 (MUST). 담으면 관리자가 정지·승인해도 토큰이 만료될 때까지 옛 값이
   * 살아 있어, "다음 요청부터 차단·해제"라는 요구사항을 어긴다.
   */
  @Test
  void payloadCarriesNothingButIdentity() {
    String payload = decodePayload(provider.issue(42L));

    assertThat(payload).doesNotContainIgnoringCase("role");
    assertThat(payload).doesNotContainIgnoringCase("status");
    assertThat(payload).doesNotContainIgnoringCase("email");
  }

  /*
   * 만료된 토큰은 서명이 맞아도 거부한다.
   *
   * 발급기로는 만료된 토큰을 만들 수 없어(발급 시점보다 이른 만료를 거부한다) 직접 서명한다.
   * 디코더의 기본 시계 오차 허용이 60초라 그보다 넉넉히 지난 시각을 쓴다.
   */
  @Test
  void expiredTokenIsRejected() throws Exception {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .subject("42")
            .issueTime(Date.from(now.minus(Duration.ofMinutes(10))))
            .expirationTime(Date.from(now.minus(Duration.ofMinutes(5))))
            .build();
    SignedJWT expired = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
    expired.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));

    assertThat(provider.readUserId(expired.serialize())).isEmpty();
  }

  /* 다른 키로 서명한 토큰은 통하지 않는다 — 이것이 통하면 누구나 아무 사용자로 로그인한다. */
  @Test
  void tokenSignedWithAnotherSecretIsRejected() {
    JwtProvider attacker =
        new JwtProvider("another-secret-32bytes-or-more-for-hs256", Duration.ofMinutes(30));

    assertThat(provider.readUserId(attacker.issue(42L))).isEmpty();
  }

  @Test
  void tamperedTokenIsRejected() {
    String token = provider.issue(42L);
    String tampered = token.substring(0, token.length() - 2) + "xx";

    assertThat(provider.readUserId(tampered)).isEmpty();
  }

  @Test
  void garbageIsRejectedWithoutThrowing() {
    assertThat(provider.readUserId("not-a-token")).isEmpty();
    assertThat(provider.readUserId("")).isEmpty();
  }

  private static String decodePayload(String token) {
    String[] parts = token.split("\\.");
    return new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
  }
}
