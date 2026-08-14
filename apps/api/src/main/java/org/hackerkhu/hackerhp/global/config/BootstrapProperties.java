package org.hackerkhu.hackerhp.global.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 최초 관리자 승격에 쓰는 값 (spec 3-3 결정 11). 운영 값은 SSM에 있다 ({@code ADMIN_BOOTSTRAP_EMAIL}, {@code
 * ADMIN_BOOTSTRAP_TOKEN}).
 *
 * <p><b>값이 없어도 기동은 된다.</b> {@code allowed-email-domain}·{@code jwt.secret}과 달리 이것은 <b>일회성 운영
 * 경로</b>다. 기동 조건으로 묶으면 로컬·테스트가 이 값을 채워야만 뜨는데 그 대가로 얻는 것이 없다 — 값이 없으면 그 경로를 <b>닫으므로</b>({@link
 * #configured()}) 무방비가 되지 않는다. 빈 값이 일치로 취급되는 일도 없다.
 *
 * <p>운영에서는 태스크 정의가 두 값을 {@code secrets}로 <b>항상</b> 주입한다. <b>토큰을 바꿀 때 파라미터를 지우면 안 된다</b> — 주입이 실패해 새
 * 태스크가 기동 전에 죽는다. 값만 바꾸고 재배포한다 ({@code docs/ops/runbook.md}).
 *
 * @param email 최초 관리자가 될 사람의 이메일. 이것만으로는 승격되지 않는다
 * @param token SSM 접근 권한이 있는 사람만 아는 무작위 시크릿
 */
@ConfigurationProperties(prefix = "app.auth.bootstrap")
public record BootstrapProperties(String email, String token) {

  /** 둘 다 있어야 이 경로가 열린다. 하나만 있으면 설정이 덜 된 것이므로 닫아 둔다. */
  public boolean configured() {
    return hasText(email) && hasText(token);
  }

  /** 이메일은 비밀이 아니다. 운영자가 SSM에 대문자로 넣어도 동작해야 한다. */
  public boolean matchesEmail(String candidate) {
    return configured()
        && email.trim().equalsIgnoreCase(candidate == null ? null : candidate.trim());
  }

  /**
   * 토큰은 <b>상수 시간으로 비교한다</b> (3-3 결정 11 MUST).
   *
   * <p>{@code equals}는 다른 글자를 만나는 순간 끝나므로, 응답이 돌아오는 시간으로 <b>앞에서부터 한 글자씩 맞춰 갈 수 있다.</b> 길이가 달라도
   * {@link MessageDigest#isEqual}은 전체를 훑는다.
   */
  public boolean matchesToken(String candidate) {
    if (!configured() || candidate == null) {
      return false;
    }
    return MessageDigest.isEqual(
        token.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
