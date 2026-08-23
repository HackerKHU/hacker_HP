package org.hackerkhu.hackerhp.global.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.testsupport.user.Accounts;
import org.hackerkhu.testsupport.web.Csrf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 승인 대기·정지 계정이 콘텐츠에 닿지 못하는지 (spec 3-1 §3-1-2, T-02·T-32).
 *
 * <p>막는 것만큼 <b>열어둔 것</b>이 중요하다. 신청 API를 막으면 아무도 신청서를 낼 수 없고, {@code GET /auth/me}를 막으면 화면이 정지 안내를
 * 띄우지 못한다. 통과 목록의 항목마다 사례를 하나씩 둔다.
 *
 * <p>세션을 직접 붙이려고 Spring Session 자동 설정을 뺀다 — 이유는 {@code AuthControllerIntegrationTest}에 적어 두었다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountStatusAccessIntegrationTest extends AbstractIntegrationTest {

  /** 지금 인증 영역에서 상태 통제를 받는 유일한 경로다. 공지 API(#32)가 오면 T-01·T-02가 그쪽으로 덮인다. */
  private static final String PROTECTED_PATH = "/v3/api-docs";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private JwtProvider jwtProvider;

  private User pending;
  private User active;
  private User suspended;

  @BeforeEach
  void createAccounts() {
    userRepository.deleteAll();
    pending =
        userRepository.saveAndFlush(User.createFromGoogle("sub-p", "pending@khu.ac.kr", "대기자"));
    active =
        userRepository.saveAndFlush(Accounts.approved("sub-a", "active@khu.ac.kr", "20240001"));
    User toSuspend = Accounts.approved("sub-s", "suspended@khu.ac.kr", "20240002");
    toSuspend.suspend();
    suspended = userRepository.saveAndFlush(toSuspend);
  }

  @AfterEach
  void clear() {
    userRepository.deleteAll();
  }

  /*
   * T-02 — 승인 대기 계정은 콘텐츠에 닿지 못한다.
   *
   * 코드가 FORBIDDEN이면 화면이 "승인 대기"와 "권한 없음"을 구별하지 못해 대기 안내를 띄우지 못한다.
   */
  @Test
  void pendingIsBlockedWithItsOwnCode() throws Exception {
    mockMvc
        .perform(sessions.as(pending, get(PROTECTED_PATH)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PENDING_APPROVAL"))
        .andExpect(jsonPath("$.message").value("승인 대기 중인 계정입니다."));
  }

  /*
   * T-32 — 정지된 세션도 마찬가지다.
   *
   * 세션을 지우지 않고 상태만 갱신하는 이유가 이것이다 (3-3 결정 12). 지웠다면 401이 되어
   * 화면이 정지인지 단순 만료인지 구별하지 못한다.
   */
  @Test
  void suspendedIsBlockedWithItsOwnCode() throws Exception {
    mockMvc
        .perform(sessions.as(suspended, get(PROTECTED_PATH)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUSPENDED"))
        .andExpect(jsonPath("$.message").value("정지된 계정입니다."));
  }

  @Test
  void activeMemberPassesThrough() throws Exception {
    mockMvc.perform(sessions.as(active, get(PROTECTED_PATH))).andExpect(status().isOk());
  }

  /*
   * 비로그인은 이 필터가 건드리지 않는다. 401로 끝나야 한다 — 403을 내면 "로그인하면 되는 상황"이
   * "권한이 없는 상황"으로 보인다.
   */
  @Test
  void anonymousStillGetsUnauthenticated() throws Exception {
    mockMvc
        .perform(get(PROTECTED_PATH))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  /*
   * 신청 API는 열려 있어야 한다 (완료 조건). 막으면 <b>아무도 신청서를 낼 수 없다</b> —
   * 신청 전 계정도 PENDING이기 때문이다.
   */
  @Test
  void pendingCanStillSubmitTheApplication() throws Exception {
    mockMvc
        .perform(
            Csrf.with(sessions.as(pending, post("/api/v1/auth/application")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentNo\":\"20249999\",\"department\":\"컴퓨터공학과\"}"))
        .andExpect(status().isNoContent());
  }

  /*
   * 신청 API는 PENDING에게만 열린다.
   *
   * 상태와 무관하게 열면, 정지된 사람이 제출했을 때 인가 규칙이 거절해 FORBIDDEN이 나간다.
   * 그러면 화면은 정지를 알아채지 못하고 "권한이 없습니다"만 띄운 채 남는다 (T-116) —
   * 대기 중에 정지당한 사람이 딱 이 경로를 밟는다.
   */
  @Test
  void suspendedSubmittingTheApplicationLearnsItIsSuspended() throws Exception {
    mockMvc
        .perform(
            Csrf.with(sessions.as(suspended, post("/api/v1/auth/application")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentNo\":\"20249998\",\"department\":\"컴퓨터공학과\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUSPENDED"));
  }

  /* 화면은 이 값으로 신청 폼과 대기 안내 중 무엇을 보일지 가른다 (§3-1-6). */
  @Test
  void pendingCanStillReadItsOwnProfile() throws Exception {
    mockMvc
        .perform(sessions.as(pending, get("/api/v1/auth/me")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING"));
  }

  /*
   * 정지된 사람에게도 열어야 한다 (T-115).
   *
   * 막으면 화면이 상태를 몰라 정지 안내를 띄우지 못한다. 스펙이 "정지는 세션을 지우지 않고 상태를
   * 갱신한다"고 정한 이유가 화면이 그것을 읽기 위해서다.
   */
  @Test
  void suspendedCanStillReadItsOwnProfile() throws Exception {
    mockMvc
        .perform(sessions.as(suspended, get("/api/v1/auth/me")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUSPENDED"));
  }

  /* 정지된 사람이 안내 화면에서 나갈 수 있어야 한다. */
  @Test
  void suspendedCanStillLogOut() throws Exception {
    mockMvc
        .perform(Csrf.with(sessions.as(suspended, post("/api/v1/auth/logout"))))
        .andExpect(status().isNoContent());
  }

  /* 위 두 쓰기 요청에 필요한 토큰을 받는 경로다. 막으면 신청도 로그아웃도 못 한다. */
  @Test
  void blockedAccountsCanStillGetACsrfToken() throws Exception {
    mockMvc
        .perform(sessions.as(pending, get("/api/v1/auth/csrf")))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(sessions.as(suspended, get("/api/v1/auth/csrf")))
        .andExpect(status().isNoContent());
  }

  /* 헬스체크는 어떤 상태에서도 열린다. 막히면 ALB가 태스크를 무한 재시작한다. */
  @Test
  void healthCheckIsNeverBlocked() throws Exception {
    mockMvc.perform(sessions.as(suspended, get("/actuator/health"))).andExpect(status().isOk());
  }
}
