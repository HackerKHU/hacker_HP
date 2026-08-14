package org.hackerkhu.hackerhp.global.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.testsupport.user.Accounts;
import org.hackerkhu.testsupport.web.AdminOnlyTestController;
import org.hackerkhu.testsupport.web.Csrf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 관리자 전용 규칙이 매트릭스대로 도는지 (spec 3-1 §3-1-3, T-04·T-05의 형태).
 *
 * <p>MVP의 관리자 API는 아직 없다(#29~#31·#32~#34). <b>규칙의 조합을 먼저 확인해 두는 것</b>이 이 테스트의 목적이다 — 먼저 쓰는 사람이 틀린
 * 조합을 고르면 뒤따르는 것들이 그것을 베낀다.
 *
 * <p>확인하는 것은 <b>{@code hasRole('ADMIN')}만 적어도 안전한가</b>이다. 매트릭스의 {@code ADMIN} 열은 "{@code ADMIN}이면서
 * {@code ACTIVE}"인데, {@code ACTIVE} 조건을 인가에 적지 않고 {@link AccountStatusFilter}에 맡겼기 때문이다.
 *
 * <p><b>쓰기도 함께 본다.</b> 조회만 확인하면 규칙이 반쪽만 증명된다 — MVC는 메서드를 부르기 전에 본문을 역직렬화하고 {@code @Valid}를 돌리므로,
 * {@code @PreAuthorize}에만 기대면 깨진 본문을 보낸 비관리자가 {@code 403}이 아니라 {@code 400}을 받는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminAuthorizationIntegrationTest.AdminEndpointConfig.class)
class AdminAuthorizationIntegrationTest extends AbstractIntegrationTest {

  /* 실제 관리자 API가 올 자리다. SecurityConfig가 이 접두사에 거는 규칙을 함께 확인하려면 그 아래여야 한다. */
  private static final String ADMIN_PATH = "/api/v1/admin/__test";

  private static final String VALID_BODY = "{\"value\":\"ok\"}";

  /* 본문 검증이 인가보다 먼저 돌면 400이 나가는 입력. 그 순서가 뒤집혔는지 보는 미끼다. */
  private static final String BROKEN_BODY = "{\"value\":";

  @TestConfiguration
  static class AdminEndpointConfig {
    @Bean
    AdminOnlyTestController adminOnlyTestController() {
      return new AdminOnlyTestController();
    }
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private JwtProvider jwtProvider;

  private User admin;
  private User member;
  private User pending;
  private User suspendedAdmin;

  @BeforeEach
  void createAccounts() {
    userRepository.deleteAll();
    admin = userRepository.saveAndFlush(Accounts.admin("sub-ad", "admin@khu.ac.kr", "20240001"));
    member = userRepository.saveAndFlush(Accounts.approved("sub-us", "user@khu.ac.kr", "20240002"));
    pending =
        userRepository.saveAndFlush(User.createFromGoogle("sub-pd", "pending@khu.ac.kr", "대기자"));
    User toSuspend = Accounts.admin("sub-sa", "suspended@khu.ac.kr", "20240003");
    toSuspend.suspend();
    suspendedAdmin = userRepository.saveAndFlush(toSuspend);
  }

  @AfterEach
  void clear() {
    userRepository.deleteAll();
  }

  @Test
  void activeAdminPassesThrough() throws Exception {
    mockMvc.perform(sessions.as(admin, get(ADMIN_PATH))).andExpect(status().isOk());
  }

  /* T-05의 형태 — 권한이 모자라면 FORBIDDEN이다. 상태 때문이 아니라는 것이 이 코드의 뜻이다. */
  @Test
  void activeUserIsForbidden() throws Exception {
    mockMvc
        .perform(sessions.as(member, get(ADMIN_PATH)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  /*
   * hasRole('ADMIN')만 적어도 안전한 이유.
   *
   * 정지된 관리자는 인가에 닿기 전에 AccountStatusFilter가 막는다. 그래서 인가 규칙에 ACTIVE 조건을
   * 반복해 적지 않는다 — 같은 규칙이 두 곳에 있으면 한쪽만 고쳐진다.
   *
   * 코드도 SUSPENDED여야 한다. FORBIDDEN이면 화면이 "권한 없음"으로 읽어 정지 안내를 띄우지 못한다.
   */
  @Test
  void suspendedAdminIsBlockedByStatusNotByRole() throws Exception {
    mockMvc
        .perform(sessions.as(suspendedAdmin, get(ADMIN_PATH)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUSPENDED"));
  }

  /* 승인 대기도 마찬가지다. 권한이 아니라 상태가 이유다. */
  @Test
  void pendingIsBlockedByStatusNotByRole() throws Exception {
    mockMvc
        .perform(sessions.as(pending, get(ADMIN_PATH)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PENDING_APPROVAL"));
  }

  @Test
  void anonymousIsUnauthenticated() throws Exception {
    mockMvc
        .perform(get(ADMIN_PATH))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  @Test
  void activeAdminCanWrite() throws Exception {
    mockMvc
        .perform(write(admin, VALID_BODY))
        .andExpect(status().isOk())
        .andExpect(content().string("ok"));
  }

  /*
   * 여기가 요점이다.
   *
   * 인가를 @PreAuthorize에만 맡기면, 본문이 깨졌을 때 Jackson이 먼저 걸려 400 INVALID_INPUT이 나간다.
   * 권한이 없다는 사실이 본문 모양에 가려지는 것이다. SecurityConfig가 /api/v1/admin/** 에 거는 규칙은
   * 그보다 앞에 있어 본문을 읽기 전에 끊는다.
   */
  @Test
  void activeUserIsForbiddenBeforeTheBodyIsRead() throws Exception {
    mockMvc
        .perform(write(member, BROKEN_BODY))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  /* 상태 차단도 본문보다 먼저다. 정지된 관리자는 권한이 아니라 상태 때문에 막힌다. */
  @Test
  void suspendedAdminIsBlockedBeforeTheBodyIsRead() throws Exception {
    mockMvc
        .perform(write(suspendedAdmin, BROKEN_BODY))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUSPENDED"));
  }

  private MockHttpServletRequestBuilder write(User user, String body) {
    return Csrf.with(sessions.as(user, post(ADMIN_PATH)))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }
}
