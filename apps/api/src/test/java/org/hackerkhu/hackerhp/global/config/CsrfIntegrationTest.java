package org.hackerkhu.hackerhp.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.global.auth.JwtProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 상태를 바꾸는 요청의 CSRF 검증 (spec 3-2 §3-2-3, T-35~T-39).
 *
 * <p>인증 쿠키는 브라우저가 자동으로 실어 보낸다. 그래서 <b>다른 사이트가 만든 요청도 로그인된 것처럼 도착한다</b> — 승인제 사이트에서 관리자 계정이 노려지면 회원
 * 일괄 승인·정지가 그렇게 일어난다.
 *
 * <p><b>토큰을 쿠키와 헤더에 나눠 싣는 방식이다</b>(이중 제출). 다른 사이트는 쿠키를 <b>읽지 못하므로</b> 헤더에 같은 값을 넣을 수 없다. 그래서 이름과 값
 * 비교가 이 방어의 전부이고, 여기서 이름을 문자열로 고정한다.
 *
 * <p>세션을 직접 붙이려고 Spring Session 자동 설정을 뺀다 — 이유는 {@code AuthControllerIntegrationTest}에 적어 두었다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CsrfIntegrationTest extends AbstractIntegrationTest {

  /** 계약이 정한 이름 (spec 3-2 §3-2-3). 상수를 참조하지 않고 적는 것이 이 테스트의 요점이다. */
  private static final String CSRF_COOKIE = "XSRF-TOKEN";

  private static final String CSRF_HEADER = "X-XSRF-TOKEN";

  private static final String APPLICATION_PATH = "/api/v1/auth/application";
  private static final String APPLICATION_BODY = "{\"studentNo\":\"20240001\",\"name\":\"본명\"}";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private JwtProvider jwtProvider;

  private User applicant;

  @BeforeEach
  void signIn() {
    userRepository.deleteAll();
    applicant =
        userRepository.saveAndFlush(User.createFromGoogle("sub-csrf", "csrf@khu.ac.kr", "구글이름"));
  }

  @AfterEach
  void clear() {
    userRepository.deleteAll();
  }

  /** 로그인한 브라우저가 자동으로 싣는 것 — 세션과 신원 토큰. CSRF 토큰은 화면이 직접 넣어야 한다. */
  private MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder builder) {
    return sessions.as(applicant, builder);
  }

  private MockHttpServletRequestBuilder submitApplication() {
    return signedIn(post(APPLICATION_PATH))
        .contentType(MediaType.APPLICATION_JSON)
        .content(APPLICATION_BODY);
  }

  /*
   * T-36 — 토큰 없이 상태를 바꾸려 하면 거부된다.
   *
   * 응답이 계약 형식이어야 화면이 코드로 분기한다. 403은 PENDING_APPROVAL·SUSPENDED·FORBIDDEN이
   * 함께 쓰므로, 본문 없이 상태 코드만 주면 화면이 원인을 가릴 수 없다.
   */
  @Test
  void writeWithoutCsrfTokenIsRejected() throws Exception {
    mockMvc
        .perform(submitApplication())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertThat(userRepository.findById(applicant.getId()).orElseThrow().getAppliedAt()).isNull();
  }

  /* T-37 — 쿠키와 헤더 값이 다르면 거부된다. 이중 제출의 핵심이다. */
  @Test
  void mismatchedCookieAndHeaderIsRejected() throws Exception {
    mockMvc
        .perform(
            submitApplication()
                .cookie(new Cookie(CSRF_COOKIE, "token-from-cookie"))
                .header(CSRF_HEADER, "different-token"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  /* 쿠키만 있고 헤더가 없어도 거부된다 — 쿠키는 브라우저가 알아서 싣기 때문에 그것만으로는 증거가 못 된다. */
  @Test
  void cookieWithoutHeaderIsRejected() throws Exception {
    mockMvc
        .perform(submitApplication().cookie(new Cookie(CSRF_COOKIE, "token")))
        .andExpect(status().isForbidden());
  }

  /* T-38 — 발급받은 토큰을 실으면 PENDING의 신청서 제출이 성공한다. 정상 경로가 막히면 안 된다. */
  @Test
  void writeWithMatchingCsrfTokenSucceeds() throws Exception {
    mockMvc
        .perform(
            submitApplication()
                .cookie(new Cookie(CSRF_COOKIE, "matching-token"))
                .header(CSRF_HEADER, "matching-token"))
        .andExpect(status().isNoContent());

    assertThat(userRepository.findById(applicant.getId()).orElseThrow().getAppliedAt()).isNotNull();
  }

  /*
   * 발급 경로가 준 값을 그대로 실으면 통과한다.
   *
   * 위 테스트는 아무 문자열이나 써서 "쿠키와 헤더가 같으면 통과"만 본다. 여기서는 GET /auth/csrf가
   * 실제로 내려준 값으로 한 바퀴를 돌려 발급과 검증이 같은 이름·같은 형식을 쓰는지 확인한다.
   */
  @Test
  void tokenFromIssuingEndpointIsAccepted() throws Exception {
    MvcResult issued = mockMvc.perform(get("/api/v1/auth/csrf")).andReturn();
    String token = csrfTokenFrom(issued);

    mockMvc
        .perform(
            submitApplication().cookie(new Cookie(CSRF_COOKIE, token)).header(CSRF_HEADER, token))
        .andExpect(status().isNoContent());
  }

  /*
   * T-143 — 헤더 이름이 계약과 같다.
   *
   * Spring의 기본 헤더 이름은 X-CSRF-TOKEN이다. 저장소를 바꾸다 이름이 그쪽으로 돌아가면 화면
   * (apps/web/src/api/client.ts)이 보내는 X-XSRF-TOKEN이 무시되어 모든 쓰기가 403이 된다.
   */
  @Test
  void contractHeaderNameIsTheOnlyOneAccepted() throws Exception {
    mockMvc
        .perform(
            submitApplication()
                .cookie(new Cookie(CSRF_COOKIE, "token"))
                .header("X-CSRF-TOKEN", "token"))
        .andExpect(status().isForbidden());
  }

  /* 쿠키 이름도 마찬가지다. 다른 이름으로 담으면 서버는 토큰이 없다고 본다. */
  @Test
  void contractCookieNameIsTheOnlyOneRead() throws Exception {
    mockMvc
        .perform(
            submitApplication()
                .cookie(new Cookie("CSRF-TOKEN", "token"))
                .header(CSRF_HEADER, "token"))
        .andExpect(status().isForbidden());
  }

  /*
   * T-36의 나머지 절반 — permitAll 경로도 CSRF 검사 대상이다.
   *
   * GET /auth/csrf는 인증 없이 열려 있지만, 같은 경로에 POST를 보내면 인가보다 먼저 CSRF 검사에
   * 걸려야 한다. ignoringRequestMatchers로 경로를 빼는 구현을 잡는 자리다.
   */
  @Test
  void permitAllPathIsStillCsrfProtected() throws Exception {
    mockMvc.perform(post("/api/v1/auth/csrf")).andExpect(status().isForbidden());
  }

  /* 안전한 메서드는 토큰 없이 통과한다. 조회까지 막으면 화면이 아무것도 못 그린다. */
  @Test
  void safeMethodsDoNotRequireAToken() throws Exception {
    mockMvc.perform(signedIn(get("/api/v1/auth/me"))).andExpect(status().isOk());
  }

  /* 로그아웃도 상태를 바꾸는 요청이다. 토큰을 실으면 성공한다. */
  @Test
  void logoutRequiresAndAcceptsTheToken() throws Exception {
    mockMvc.perform(signedIn(post("/api/v1/auth/logout"))).andExpect(status().isForbidden());

    mockMvc
        .perform(
            signedIn(post("/api/v1/auth/logout"))
                .cookie(new Cookie(CSRF_COOKIE, "token"))
                .header(CSRF_HEADER, "token"))
        .andExpect(status().isNoContent());
  }

  private static String csrfTokenFrom(MvcResult result) {
    Cookie cookie = result.getResponse().getCookie(CSRF_COOKIE);
    if (cookie != null) {
      return cookie.getValue();
    }
    // ResponseCookie로 쓰면 헤더에만 남는다. 브라우저가 보는 것도 이 헤더다.
    return result.getResponse().getHeaders("Set-Cookie").stream()
        .filter(header -> header.startsWith(CSRF_COOKIE + "="))
        .map(header -> header.substring((CSRF_COOKIE + "=").length()).split(";")[0])
        .findFirst()
        .orElseThrow(() -> new AssertionError("GET /auth/csrf가 토큰 쿠키를 내려주지 않았다"));
  }
}
