package org.hackerkhu.hackerhp.global.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * 신원 증명 토큰을 만들고 읽는다 (spec 3-3 결정 12).
 *
 * <p><b>{@code sub}(사용자 id) 말고는 아무것도 담지 않는다</b> (MUST). {@code role}·{@code status}를 담으면 관리자가
 * 정지·승인해도 토큰이 만료될 때까지 옛 값이 살아 있어, "다음 요청부터 차단·해제"라는 요구사항을 어긴다. 그 두 값은 서버 세션이 들고 있다.
 */
@Component
public class JwtProvider {

  private static final MacAlgorithm ALGORITHM = MacAlgorithm.HS256;

  private final JwtEncoder encoder;
  private final JwtDecoder decoder;
  private final Duration expiry;

  @Autowired
  public JwtProvider(JwtProperties properties) {
    this(properties.secret(), properties.expiry());
  }

  JwtProvider(String secret, Duration expiry) {
    SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM.getName());
    this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(ALGORITHM).build();
    this.expiry = expiry;
  }

  public Duration expiry() {
    return expiry;
  }

  public String issue(Long userId) {
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .subject(String.valueOf(userId))
            .issuedAt(now)
            .expiresAt(now.plus(expiry))
            .build();
    return encoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(ALGORITHM).build(), claims))
        .getTokenValue();
  }

  /**
   * 서명과 만료를 확인하고 사용자 id를 꺼낸다.
   *
   * <p>실패를 예외가 아니라 빈 값으로 돌려준다. 호출부(필터)는 위조든 만료든 <b>같은 결론</b>—인증하지 않는다—에 이르므로, 사유를 나눠 봤자 응답이 달라지지
   * 않는다. 사유를 응답에 담으면 오히려 공격자에게 힌트가 된다.
   */
  public Optional<Long> readUserId(String token) {
    try {
      return Optional.of(Long.valueOf(decoder.decode(token).getSubject()));
    } catch (JwtException | NumberFormatException e) {
      return Optional.empty();
    }
  }
}
