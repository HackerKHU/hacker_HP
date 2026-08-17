package org.hackerkhu.testsupport.web;

import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 요청에 CSRF 토큰을 <b>계약대로</b> 싣는다 — 쿠키 {@code XSRF-TOKEN}, 헤더 {@code X-XSRF-TOKEN} (spec 3-2 §3-2-3).
 *
 * <p><b>{@code SecurityMockMvcRequestPostProcessors.csrf()}를 쓰지 않는다.</b> 그것은 애플리케이션 컨텍스트의 {@code
 * CsrfFilter}에 손을 뻗어 토큰 저장소를 세션 기반 대역으로 <b>바꿔 버린다.</b> 컨텍스트는 테스트 클래스 사이에 캐시되므로 그 교체가 남고, 뒤에 도는 테스트는
 * 우리가 설정한 쿠키 방식을 더 이상 쓰지 못한다 — 실제로 그렇게 세 건이 순서에 따라 깨졌다.
 *
 * <p>여기서 하는 것은 <b>브라우저가 하는 일과 같다.</b> 화면은 쿠키에서 값을 읽어 헤더에 실어 보낸다 ({@code
 * apps/web/src/api/client.ts}). 그래서 이 방식은 부작용이 없을 뿐 아니라 실제 경로에 더 가깝다.
 */
public final class Csrf {

  public static final String COOKIE = "XSRF-TOKEN";
  public static final String HEADER = "X-XSRF-TOKEN";

  private static final String TOKEN = "test-csrf-token";

  private Csrf() {}

  public static MockHttpServletRequestBuilder with(MockHttpServletRequestBuilder builder) {
    return with(builder, TOKEN);
  }

  public static MockHttpServletRequestBuilder with(
      MockHttpServletRequestBuilder builder, String token) {
    return builder.cookie(new Cookie(COOKIE, token)).header(HEADER, token);
  }
}
