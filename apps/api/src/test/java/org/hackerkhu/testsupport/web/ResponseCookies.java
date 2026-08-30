package org.hackerkhu.testsupport.web;

import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 응답이 쿠키를 <b>버렸는지</b> 본다.
 *
 * <p><b>{@code getCookie(name)}을 쓰면 안 된다</b> (#299). 그것은 <b>첫 번째</b> 헤더만 준다. 토큰을 버리는 경로(로그아웃·탈퇴·자기
 * 제거)에서는 응답에 {@code ACCESS_TOKEN} 헤더가 <b>둘</b> 실린다 — 인증 필터가 쓴 갱신이 먼저고, 컨트롤러가 붙인 폐기가 나중이다.
 *
 * <p>같은 이름·경로의 쿠키는 <b>나중 것이 앞 것을 덮으므로</b>(RFC 6265) 브라우저에 남는 것은 폐기다. 첫 번째만 보면 <b>정반대로 읽는다</b> — 실제로
 * 그랬고, 재발급을 넣자 로그아웃 사례 둘이 함께 깨졌다.
 */
public final class ResponseCookies {

  private ResponseCookies() {}

  /** 그 이름으로 나간 {@code Set-Cookie} 전부. 응답에 붙은 순서 그대로다. */
  public static List<String> headers(MvcResult result, String name) {
    return result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
        .filter(header -> header.startsWith(name + "="))
        .toList();
  }

  /**
   * <b>브라우저가 이 응답을 처리하고 나면 그 쿠키가 사라지는가.</b>
   *
   * <p>마지막 헤더만 본다 — 그것이 이기는 값이다.
   */
  public static boolean discarded(MvcResult result, String name) {
    List<String> headers = headers(result, name);
    return !headers.isEmpty() && headers.getLast().contains("Max-Age=0");
  }
}
