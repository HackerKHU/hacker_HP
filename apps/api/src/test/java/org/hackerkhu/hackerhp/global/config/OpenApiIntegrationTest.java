package org.hackerkhu.hackerhp.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.global.auth.AuthSession;
import org.hackerkhu.hackerhp.global.auth.JwtProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * API 명세가 나오는지, 그리고 <b>아무나 볼 수는 없는지</b> 확인한다 (#23).
 *
 * <p>세션을 직접 붙이려고 Spring Session 자동 설정을 뺀다 — 이유는 {@code AuthControllerIntegrationTest}에 적어 두었다.
 */
@SpringBootTest(
    properties =
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.session.SessionAutoConfiguration")
@AutoConfigureMockMvc
class OpenApiIntegrationTest extends AbstractIntegrationTest {

  private static final String API_DOCS = "/v3/api-docs";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private JwtProvider jwtProvider;

  private User member;

  @BeforeEach
  void signIn() {
    userRepository.deleteAll();
    member = userRepository.saveAndFlush(User.createFromGoogle("sub-doc", "doc@khu.ac.kr", "구글이름"));
  }

  @AfterEach
  void clear() {
    userRepository.deleteAll();
  }

  private MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder builder) {
    MockHttpSession session = new MockHttpSession();
    AuthSession.store(session, member);
    return builder
        .session(session)
        .cookie(new Cookie("ACCESS_TOKEN", jwtProvider.issue(member.getId())));
  }

  /*
   * 문서는 로그인해야 볼 수 있다 (#23에서 정했다).
   *
   * 승인제 사이트라 명세가 공개되면 엔드포인트·필드·검증 규칙이 전부 드러난다. permitAll에
   * 문서 경로를 더하는 변경을 여기서 잡는다.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {API_DOCS, "/swagger-ui/index.html"})
  void documentationRequiresLogin(String path) throws Exception {
    mockMvc
        .perform(get(path))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  @Test
  void loggedInMemberCanReadTheSpecification() throws Exception {
    mockMvc
        .perform(signedIn(get(API_DOCS)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.openapi").isString())
        .andExpect(jsonPath("$.info.title").value("hacker_HP API"));
  }

  /* 구현된 인증 API가 빠짐없이 실린다. 컨트롤러를 더하고 문서를 잊는 것을 잡는다. */
  @Test
  void everyImplementedAuthEndpointAppears() throws Exception {
    mockMvc
        .perform(signedIn(get(API_DOCS)))
        .andExpect(jsonPath("$.paths['/api/v1/auth/csrf'].get").exists())
        .andExpect(jsonPath("$.paths['/api/v1/auth/me'].get").exists())
        .andExpect(jsonPath("$.paths['/api/v1/auth/application'].post").exists())
        .andExpect(jsonPath("$.paths['/api/v1/auth/logout'].post").exists());
  }

  /*
   * 인증은 쿠키 <b>두 개</b>가 함께 있어야 성립한다 (3-1 §3-1-5). 명세도 그렇게 읽혀야 한다.
   *
   * 요구사항 하나에 두 스킴을 담으면 AND다. 따로 담으면 OR가 되어 "둘 중 하나면 된다"로 읽히는데,
   * 그것은 이 설계가 막으려는 조합이다 (T-30·T-31).
   */
  @Test
  void specificationSaysBothCookiesAreRequiredTogether() throws Exception {
    mockMvc
        .perform(signedIn(get(API_DOCS)))
        .andExpect(jsonPath("$.security[0].accessToken").exists())
        .andExpect(jsonPath("$.security[0].session").exists())
        .andExpect(jsonPath("$.security[1]").doesNotExist())
        .andExpect(jsonPath("$.components.securitySchemes.accessToken.name").value("ACCESS_TOKEN"))
        .andExpect(jsonPath("$.components.securitySchemes.accessToken.in").value("cookie"))
        .andExpect(jsonPath("$.components.securitySchemes.session.name").value("SESSION"))
        .andExpect(jsonPath("$.components.securitySchemes.csrfToken.name").value("X-XSRF-TOKEN"))
        .andExpect(jsonPath("$.components.securitySchemes.csrfToken.in").value("header"));
  }

  /* 토큰 발급 경로는 세션도 토큰도 없는 최초 진입에 필요하다 — 명세에도 인증이 필요 없다고 적힌다. */
  @Test
  void csrfEndpointIsDocumentedAsOpen() throws Exception {
    mockMvc
        .perform(signedIn(get(API_DOCS)))
        .andExpect(jsonPath("$.paths['/api/v1/auth/csrf'].get.security").isEmpty());
  }

  /* 상태를 바꾸는 요청은 CSRF 토큰이 필요하다는 것도 명세에 나와야 한다. */
  @Test
  void writeOperationsDocumentTheCsrfRequirement() throws Exception {
    mockMvc
        .perform(signedIn(get(API_DOCS)))
        .andExpect(
            jsonPath("$.paths['/api/v1/auth/application'].post.security[?(@.csrfToken)]").exists())
        .andExpect(
            jsonPath("$.paths['/api/v1/auth/logout'].post.security[?(@.csrfToken)]").exists());
  }
}
