package org.hackerkhu.hackerhp.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.audit.repository.AdminActionLogRepository;
import org.hackerkhu.hackerhp.domain.auth.repository.BootstrapAttemptRepository;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 관리자 승격 시도 제한 (#144, spec 3-3 결정 11).
 *
 * <p><b>이 경로가 열리는 시점이 문제다.</b> 방어선은 "활성 관리자 0명" 하나인데, 그 조건이 성립하는 순간 — 최초 배포 직후, 관리자 사고 복구 중 — 은 정확히
 * 아무도 막을 수 없는 시점이다.
 */
@SpringBootTest(
    properties = {
      "ADMIN_BOOTSTRAP_EMAIL=founder@khu.ac.kr",
      "ADMIN_BOOTSTRAP_TOKEN=bootstrap-token-for-tests"
    })
@AutoConfigureMockMvc
class BootstrapAttemptLimitIntegrationTest extends AbstractIntegrationTest {

  private static final String PATH = "/api/v1/auth/bootstrap-admin";
  private static final String TOKEN = "bootstrap-token-for-tests";
  private static final String WRONG = "wrong-token";

  /** {@code BootstrapAttemptLimiter.PER_ACCOUNT_LIMIT}와 같아야 한다. */
  private static final int PER_ACCOUNT_LIMIT = 5;

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private BootstrapAttemptRepository attempts;
  @Autowired private AdminActionLogRepository adminActions;

  private User founder;

  @BeforeEach
  void createAccounts() {
    attempts.deleteAll();
    adminActions.deleteAll();
    userRepository.deleteAll();
    founder =
        userRepository.saveAndFlush(
            Accounts.applied("sub-founder", "founder@khu.ac.kr", "20200001"));
  }

  @AfterEach
  void clear() {
    attempts.deleteAll();
    adminActions.deleteAll();
    userRepository.deleteAll();
  }

  private MockHttpServletRequestBuilder bootstrap(User caller, String token) {
    return Csrf.with(sessions.as(caller, post(PATH)))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"token\":\"" + token + "\"}");
  }

  private void failOnce(User caller) throws Exception {
    mockMvc.perform(bootstrap(caller, WRONG)).andExpect(status().isForbidden());
  }

  private Role roleOf(User user) {
    return userRepository.findById(user.getId()).orElseThrow().getRole();
  }

  /* ---------------------------------------------------------------- 센다 */

  /**
   * 실패가 세어진다.
   *
   * <p><b>승격 트랜잭션 밖에서 세야 한다.</b> 거절은 예외로 나가고 그 트랜잭션은 되돌아가므로, 안에서 세면 기록이 함께 사라져 <b>아무것도 세지지 않는다.</b>
   */
  @Test
  void countsFailedAttempts() throws Exception {
    failOnce(founder);
    failOnce(founder);

    assertThat(attempts.findAll()).hasSize(2);
  }

  /** 상한을 넘기면 <b>맞는 토큰으로도</b> 통과하지 못한다. 이것이 없으면 무제한으로 추측할 수 있다. */
  @Test
  void locksTheAccountAfterTheLimit() throws Exception {
    for (int attempt = 0; attempt < PER_ACCOUNT_LIMIT; attempt++) {
      failOnce(founder);
    }

    mockMvc.perform(bootstrap(founder, TOKEN)).andExpect(status().isForbidden());
    assertThat(roleOf(founder)).isEqualTo(Role.USER);
  }

  /**
   * <b>잠긴 것도 같은 응답이다</b> (3-2 §3-2-3 MUST).
   *
   * <p>응답이 달라지면 잠기는 시점을 재서 <b>토큰이 맞았는지를 역으로 알아낼 수 있다</b> — 틀린 토큰만 세어진다면, 잠기지 않는 시도가 곧 맞는 토큰이다.
   */
  @Test
  void aLockedRequestLooksLikeEveryOtherRejection() throws Exception {
    mockMvc
        .perform(bootstrap(founder, WRONG))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.message").value("승격할 수 없습니다."));

    for (int attempt = 1; attempt < PER_ACCOUNT_LIMIT; attempt++) {
      failOnce(founder);
    }

    mockMvc
        .perform(bootstrap(founder, TOKEN))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.message").value("승격할 수 없습니다."));
  }

  /** 잠금은 <b>그 계정만</b>이다. 한 사람이 잠겼다고 다른 사람의 정상 절차가 막히면 안 된다. */
  @Test
  void lockingOneAccountDoesNotBlockAnother() throws Exception {
    User other =
        userRepository.saveAndFlush(Accounts.applied("sub-other", "other@khu.ac.kr", "20240101"));
    for (int attempt = 0; attempt < PER_ACCOUNT_LIMIT; attempt++) {
      failOnce(other);
    }

    // 자격을 갖춘 사람은 그대로 승격된다.
    mockMvc.perform(bootstrap(founder, TOKEN)).andExpect(status().isNoContent());
    assertThat(roleOf(founder)).isEqualTo(Role.ADMIN);
  }

  /* ------------------------------------------------------------ 막지 않는다 */

  /**
   * <b>정상적인 1회 승격은 영향받지 않는다</b> (완료 조건).
   *
   * <p>최초 배포 절차가 이 제한 때문에 막히면 안 된다.
   */
  @Test
  void theNormalSinglePromotionIsUntouched() throws Exception {
    mockMvc.perform(bootstrap(founder, TOKEN)).andExpect(status().isNoContent());

    assertThat(roleOf(founder)).isEqualTo(Role.ADMIN);
    assertThat(attempts.findAll()).isEmpty();
  }

  /**
   * 몇 번 틀린 뒤 성공하면 <b>실패 기록이 지워진다.</b>
   *
   * <p>토큰을 잘못 붙여넣는 것은 흔한 일이고, 남겨 두면 <b>바로 다음에 마지막 관리자 사고가 났을 때 복구가 막힌다.</b>
   */
  @Test
  void succeedingClearsTheFailuresOfThatAccount() throws Exception {
    failOnce(founder);
    failOnce(founder);

    mockMvc.perform(bootstrap(founder, TOKEN)).andExpect(status().isNoContent());

    assertThat(attempts.findAll()).isEmpty();
  }
}
