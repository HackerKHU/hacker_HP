package org.hackerkhu.hackerhp.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.global.auth.JwtProvider;
import org.hackerkhu.testsupport.auth.TestSessions.SignedIn;
import org.hackerkhu.testsupport.user.Accounts;
import org.hackerkhu.testsupport.web.Csrf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code GET /auth/me}와 {@code POST /auth/logout} (spec 3-2 §3-2-3, T-46·T-47).
 *
 * <p><b>Spring Session 자동 설정을 뺀다.</b> 켜져 있으면 {@code SessionRepositoryFilter}가 요청을 감싸고 {@code
 * SESSION} 쿠키로 자기 세션을 찾으므로, 테스트가 붙인 세션을 서버가 보지 못해 전부 401이 된다. 여기서 볼 것은 <b>로그인한 세션이 무엇을 돌려주는가</b>이지
 * 그 세션이 어디에 저장되는가가 아니다 — 저장소 스키마는 {@code SchemaMigrationIntegrationTest}가 따로 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerIntegrationTest extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private JwtProvider jwtProvider;

  private User member;
  private SignedIn signedIn;

  @BeforeEach
  void signIn() {
    userRepository.deleteAll();
    member = userRepository.saveAndFlush(Accounts.signedIn("sub-me", "auth-me@khu.ac.kr"));
    signedIn = sessions.signIn(member);
  }

  /*
   * 뒤에도 지운다. 이 테스트는 트랜잭션 롤백에 기대지 않아 행이 그대로 남고, 같은 이메일을 쓰는
   * 다른 테스트가 UNIQUE 제약에 걸린다 — 실제로 SchemaMigrationIntegrationTest가 그렇게 깨졌다.
   */
  @AfterEach
  void signOut() {
    userRepository.deleteAll();
  }

  /** 로그인한 브라우저가 보내는 것 — 세션 쿠키와 토큰 쿠키가 함께 간다. */
  private MockHttpServletRequestBuilder asMember(MockHttpServletRequestBuilder builder) {
    return signedIn.on(builder);
  }

  /* T-46 — 첫 로그인 직후. 화면은 이 값으로 신청 폼을 띄울지 대기 안내를 띄울지 가른다. */
  @Test
  void meRightAfterFirstLoginShowsPendingWithoutApplication() throws Exception {
    mockMvc
        .perform(asMember(get("/api/v1/auth/me")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.role").value("USER"))
        .andExpect(jsonPath("$.studentNo").doesNotExist())
        .andExpect(jsonPath("$.department").doesNotExist())
        .andExpect(jsonPath("$.appliedAt").doesNotExist())
        .andExpect(jsonPath("$.approvedAt").doesNotExist());
  }

  /* T-47·T-182 — 신청서를 낸 뒤. appliedAt에 값이 있는 것이 곧 "신청했다"는 뜻이다. */
  @Test
  void meAfterApplicationShowsStudentNoAndAppliedAt() throws Exception {
    member.submitApplication("20240001", "본명", "컴퓨터공학과");
    userRepository.saveAndFlush(member);

    mockMvc
        .perform(asMember(get("/api/v1/auth/me")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.studentNo").value("20240001"))
        .andExpect(jsonPath("$.name").value("본명"))
        .andExpect(jsonPath("$.department").value("컴퓨터공학과"))
        .andExpect(jsonPath("$.appliedAt").isNotEmpty());
  }

  /*
   * 응답 형태가 apps/web/src/api/types.ts의 User와 같아야 한다. 필드가 늘거나 줄면 화면이 깨진다.
   * 특히 version(동시성 제어용)이 새어 나가면 안 된다.
   */
  @Test
  void meReturnsExactlyTheContractFields() throws Exception {
    mockMvc
        .perform(asMember(get("/api/v1/auth/me")))
        .andExpect(jsonPath("$.id").value(member.getId()))
        .andExpect(jsonPath("$.email").value("auth-me@khu.ac.kr"))
        // 날짜는 ISO 문자열이다. 숫자로 나가면 화면이 그대로 표시하지 못한다.
        .andExpect(jsonPath("$.createdAt").isString())
        .andExpect(jsonPath("$.version").doesNotExist())
        // null인 네 필드(studentNo·department·appliedAt·approvedAt)는 JSON에 남으므로 열 개다.
        .andExpect(jsonPath("$").value(aMapWithSize(10)));
  }

  /**
   * <b>비로그인에게는 {@code 204}다. 오류가 아니다</b> (#190).
   *
   * <p>화면은 랜딩을 포함해 최초 렌더마다 이것을 부른다. 실패로 답하면 <b>비로그인 방문자마다 실패 응답이 하나씩 남고</b>, 브라우저가 콘솔에 남기는 그 줄은 앱이
   * 지울 수 없어 진짜 오류가 묻힌다.
   */
  @Test
  void meAnswersNoContentForAGuest() throws Exception {
    mockMvc
        .perform(get("/api/v1/auth/me"))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));
  }

  /** 열려 있다고 해서 <b>계정 정보가 나가지는 않는다.</b> 비로그인에게 나가는 것은 "세션이 없다"뿐이다. */
  @Test
  void meLeaksNothingToAGuest() throws Exception {
    String body =
        mockMvc.perform(get("/api/v1/auth/me")).andReturn().getResponse().getContentAsString();

    assertThat(body).isEmpty();
  }

  @Test
  void logoutInvalidatesTheSession() throws Exception {
    mockMvc
        .perform(Csrf.with(asMember(post("/api/v1/auth/logout"))))
        .andExpect(status().isNoContent());

    // 저장소에서 사라져야 한다 — 응답 쿠키만 지우면 서버에는 살아 있다.
    assertThat(signedIn.storedInRepository()).isFalse();
  }

  /*
   * 로그아웃 뒤에는 쿠키에 남은 토큰이 쓸모없다 (T-30). 세션을 지우는 것만으로 로그아웃이
   * 성립한다는 결정 12의 근거를 실제 요청으로 확인한다.
   */
  @Test
  void tokenLeftAfterLogoutNoLongerAuthenticates() throws Exception {
    // 보호된 경로로 확인한다. /auth/me는 비로그인에게도 열려 있어(#190) 여기서는 204로 답한다.
    mockMvc
        .perform(get("/api/v1/notices").cookie(sessions.token(member)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

    // 그 토큰으로 /auth/me를 불러도 계정 정보는 나가지 않는다.
    mockMvc
        .perform(get("/api/v1/auth/me").cookie(sessions.token(member)))
        .andExpect(status().isNoContent());
  }

  /*
   * 세션도 토큰도 없는 최초 진입에서 발급된다 (T-35). 화면은 첫 상태 변경 요청 전에 이것을 부르고,
   * 실패하면 그 요청 자체를 보내지 않는다 — 이 경로가 없으면 로그아웃 버튼도 동작하지 않는다.
   */
  @Test
  void csrfTokenIsIssuedToAnonymousCallers() throws Exception {
    MvcResult result =
        mockMvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isNoContent()).andReturn();

    /*
     * Set-Cookie 헤더로 확인한다. CookieCsrfTokenRepository는 ResponseCookie를 헤더로 쓰므로
     * getCookie()로는 잡히지 않을 수 있고, 실제로 브라우저가 보는 것도 이 헤더다.
     */
    String setCookie =
        result.getResponse().getHeaders("Set-Cookie").stream()
            .filter(header -> header.startsWith("XSRF-TOKEN="))
            .findFirst()
            .orElse(null);

    assertThat(setCookie).isNotNull();
    assertThat(setCookie).doesNotStartWith("XSRF-TOKEN=;");
    // 화면이 읽어 헤더에 실어야 하므로 httpOnly가 아니다 (3-2 §3-2-3).
    assertThat(setCookie).doesNotContainIgnoringCase("HttpOnly");
  }
}
