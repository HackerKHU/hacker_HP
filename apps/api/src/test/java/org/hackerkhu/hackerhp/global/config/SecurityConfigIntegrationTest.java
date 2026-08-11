package org.hackerkhu.hackerhp.global.config;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/** 시큐리티 필터 체인을 실제로 태워서 본다. 필터를 우회하는 단위 테스트는 권한 버그를 잡지 못한다 (spec/5-TESTING §5-1). */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigIntegrationTest extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;

  /*
   * T-128 — 미인증 요청은 401이고, 본문이 계약 형식이다 (§3-2-7).
   *
   * 이 응답은 필터에서 나온다. advice는 DispatcherServlet 안에서만 돌아 여기 관여하지 못하므로,
   * #22의 ErrorResponseWriter를 진입점에 꽂아 형식을 맞췄다. 형식이 갈라지면 화면이 코드로
   * 분기하지 못한다 (T-114).
   */
  @Test
  void unauthenticatedRequestReturnsContractBody() throws Exception {
    mockMvc
        .perform(get("/api/v1/notices"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
        .andExpect(jsonPath("$.message").value("로그인이 필요합니다."));
  }

  /* T-129 — 로그인 시작 경로가 구글로 보낸다 (#21 완료 조건). */
  @Test
  void authorizationEndpointRedirectsToGoogle() throws Exception {
    mockMvc
        .perform(get("/api/v1/oauth2/authorization/google"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrlPattern("https://accounts.google.com/o/oauth2/**"));
  }

  /*
   * T-130 — 구글에 보내는 redirect_uri가 설정값 그대로다.
   *
   * Spring은 기본적으로 들어온 요청의 scheme·host로 이 값을 조립한다. 이 구성은 프록시가 두 겹이라
   * (Vercel → ALB) 조립한 값이 구글 등록값과 어긋나 redirect_uri_mismatch로 거부된다
   * (docs/ops/infra.md). MockMvc의 호스트는 localhost:80이므로, 조립했다면 여기서 드러난다.
   */
  @Test
  void redirectUriIsFixedByConfigurationNotByRequestHost() throws Exception {
    mockMvc
        .perform(get("/api/v1/oauth2/authorization/google"))
        .andExpect(
            header()
                .string(
                    HttpHeaders.LOCATION,
                    containsString(
                        "redirect_uri=http://localhost:5173/api/v1/login/oauth2/code/google")));
  }

  /*
   * T-131 — state 파라미터가 붙는다. 끄면 로그인 CSRF가 열린다 (3-2 §3-2-3 MUST, T-40).
   * 구글 로그인 시작은 GET이라 CSRF 토큰 검증 대상이 아니고, state가 그 역할을 대신한다.
   */
  @Test
  void authorizationRequestCarriesStateParameter() throws Exception {
    mockMvc
        .perform(get("/api/v1/oauth2/authorization/google"))
        .andExpect(header().string(HttpHeaders.LOCATION, containsString("state=")));
  }

  /*
   * T-132 — 프레임워크 기본 경로에는 남아 있지 않다 (#21 완료 조건).
   *
   * Vercel rewrites가 /api/*만 프록시하므로 기본값(/oauth2/...)이면 브라우저 요청이 ALB에 닿지 않는다.
   * 로컬에서는 잘 되고 배포하면 안 되는 종류라, 경로가 되돌아오는 것을 여기서 막는다.
   */
  @Test
  void frameworkDefaultOauthPathIsNotExposed() throws Exception {
    mockMvc
        .perform(get("/oauth2/authorization/google"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }
}
