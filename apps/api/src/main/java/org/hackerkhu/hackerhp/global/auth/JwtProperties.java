package org.hackerkhu.hackerhp.global.auth;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT 서명과 수명 설정.
 *
 * <p>{@code secret}이 없으면 기동에 실패한다. 기본값을 코드에 심어두면 <b>누구나 아는 키로 토큰을 위조할 수 있다.</b> 운영 값은 SSM의 {@code
 * /hacker/dev/JWT_SECRET}에서 온다 (docs/ops/infra.md).
 *
 * @param secret HMAC 서명 키. HS256은 <b>256비트(32바이트) 이상</b>을 요구하고, 짧으면 기동 시점에 터진다
 * @param expiry 토큰 수명. <b>세션 만료와 같게 둔다</b> — 세션이 없으면 토큰이 살아 있어도 인증이 성립하지 않으므로(3-3 결정 12), 토큰만 더 오래
 *     살려 둘 이유가 없다
 */
@Validated
@ConfigurationProperties(prefix = "app.auth.jwt")
public record JwtProperties(@NotNull @Size(min = 32) String secret, @NotNull Duration expiry) {}
