package org.hackerkhu.hackerhp.global.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 신원 토큰을 담는 쿠키.
 *
 * <p><b>{@code httpOnly}다</b> (spec 3-1 §3-1-5 MUST). {@code localStorage}에 두지 않는다 — 스크립트가 읽을 수 있으면
 * XSS 하나로 토큰이 빠져나간다.
 *
 * <p>{@code Secure}는 세션 쿠키 설정({@code server.servlet.session.cookie.secure})을 따라간다. 두 쿠키가 같은 조건에서
 * 오가야 하므로 값을 따로 두지 않는다 — 한쪽만 로컬 예외를 두면 다른 쪽이 조용히 어긋난다.
 */
@Component
public class AccessTokenCookie {

  static final String NAME = "ACCESS_TOKEN";

  private final boolean secure;

  public AccessTokenCookie(ServerProperties serverProperties) {
    Boolean configured = serverProperties.getServlet().getSession().getCookie().getSecure();
    // 설정이 없으면 켠다. 빠뜨렸을 때 평문으로 나가는 쪽이 위험하다.
    this.secure = configured == null || configured;
  }

  public Optional<String> read(HttpServletRequest request) {
    return Optional.ofNullable(request.getCookies()).stream()
        .flatMap(Arrays::stream)
        .filter(cookie -> NAME.equals(cookie.getName()))
        .map(jakarta.servlet.http.Cookie::getValue)
        .filter(value -> value != null && !value.isBlank())
        .findFirst();
  }

  public void write(HttpServletResponse response, String token, Duration maxAge) {
    response.addHeader(HttpHeaders.SET_COOKIE, build(token, maxAge).toString());
  }

  /** 브라우저가 들고 있는 토큰을 버리게 한다. 로그아웃과 자격 불일치 시 쓴다 (T-29). */
  public void clear(HttpServletResponse response) {
    response.addHeader(HttpHeaders.SET_COOKIE, build("", Duration.ZERO).toString());
  }

  private ResponseCookie build(String value, Duration maxAge) {
    return ResponseCookie.from(NAME, value)
        .httpOnly(true)
        .secure(secure)
        // 교차 사이트 요청에는 실리지 않는다. CSRF 토큰 검증(#83)과 별개의 방어선이다.
        .sameSite("Lax")
        .path("/")
        .maxAge(maxAge)
        .build();
  }
}
