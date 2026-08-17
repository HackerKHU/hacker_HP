package org.hackerkhu.hackerhp.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 로그인 리다이렉트가 SPA를 벗어나지 않는지 (spec 3-2 §3-2-3 MUST, #130).
 *
 * <p><b>이 검증은 실제 서블릿 컨테이너에서만 성립한다.</b> {@link LoginFailureHandlerTest}는 {@code
 * MockHttpServletResponse}로 핸들러만 확인하는데, 핸들러는 처음부터 {@code /login?error=...}를 정확히 만들고 있었다. 망가뜨리는 것은 그
 * 뒤 Tomcat이다 — {@code sendRedirect()}의 Location을 요청 {@code Host}로 절대화하고, 이 API는 언제나 프록시 뒤에 있어 그
 * Host가 API 오리진이다. 그래서 단위 테스트가 초록불인 채로 로그인한 사람이 SPA 밖 JSON 화면에 갇혀 있었다.
 *
 * <p>확인하는 것은 {@code server.tomcat.use-relative-redirects}가 켜져 있다는 사실 하나다. 그 설정이 사라지면 프록시 뒤에서만 재현되는
 * 버그가 조용히 돌아온다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginRedirectOriginIntegrationTest extends AbstractIntegrationTest {

  @LocalServerPort private int port;

  /**
   * 콜백 실패는 상대 경로로 되돌아간다.
   *
   * <p>{@code state}가 세션의 인증 요청과 맞지 않아 실패한다. 구글과 통신하기 전에 걸리므로 네트워크가 필요 없다. 사유가 {@code failed}인 것은
   * 계약대로다 — Spring 내부 코드({@code authorization_request_not_found})는 쿼리에 싣지 않는다 (T-44).
   *
   * <p>성공 경로({@code sendRedirect("/")})도 같은 Tomcat 설정을 지나므로 이 검증이 함께 지킨다. 성공을 직접 부르려면 구글 토큰 교환을 통째로
   * 대역으로 세워야 하는데, 그것은 이 설정이 아니라 대역을 검증하게 된다.
   *
   * <p><b>리다이렉트를 따라가지 않는 클라이언트를 쓴다.</b> 따라가면 {@code /login}에서 받은 마지막 응답만 남아 Location을 볼 수 없다 —
   * {@code TestRestTemplate}으로는 이 검증을 쓸 수 없다.
   */
  @Test
  void callbackFailureRedirectsWithoutAnOrigin() throws Exception {
    HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(
                URI.create(
                    "http://localhost:"
                        + port
                        + "/api/v1/login/oauth2/code/google?code=bogus&state=bogus"))
            .GET()
            .build();

    HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());

    assertThat(response.statusCode()).isEqualTo(302);
    assertThat(response.headers().firstValue("Location"))
        .as("절대 URL이면 브라우저가 SPA가 아니라 API 서버로 이동한다")
        .hasValue("/login?error=failed");
  }
}
