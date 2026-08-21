package org.hackerkhu.hackerhp.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

  /** {@code BootstrapAttemptLimiter}의 상수와 같아야 한다. */
  private static final int PER_ACCOUNT_LIMIT = 5;

  private static final int GLOBAL_LIMIT = 20;

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
   * 몇 번 틀린 뒤 성공하면 <b>시도 기록이 지워진다.</b>
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

  /**
   * <b>전체 상한이 계정 갈아타기를 막는다.</b>
   *
   * <p>계정별 상한만 있으면 계정을 바꿔가며 계속 두드릴 수 있다. IP를 셀 수 없는 구조에서(브라우저가 프록시를 거쳐 도착한다) 그 자리를 메우는 것이 전체 상한이다.
   */
  @Test
  void theGlobalLimitStopsAccountHopping() throws Exception {
    // 계정별 상한에 걸리지 않게 나눠서 전체 상한을 채운다.
    for (int account = 0; account < GLOBAL_LIMIT / PER_ACCOUNT_LIMIT; account++) {
      User hopper =
          userRepository.saveAndFlush(
              Accounts.applied(
                  "sub-hop-" + account, "hop" + account + "@khu.ac.kr", "2030000" + account));
      for (int attempt = 0; attempt < PER_ACCOUNT_LIMIT; attempt++) {
        failOnce(hopper);
      }
    }
    assertThat(attempts.findAll()).hasSize(GLOBAL_LIMIT);

    /*
     * founder는 한 번도 시도하지 않았고 토큰도 맞다. 그래도 막힌다 — 전체가 잠겼기 때문이다.
     * 이 사례가 없으면 전역 조회나 상수가 회귀해도 계정별 사례만으로 통과한다.
     */
    mockMvc
        .perform(bootstrap(founder, TOKEN))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.message").value("승격할 수 없습니다."));
    assertThat(roleOf(founder)).isEqualTo(Role.USER);
  }

  /**
   * <b>병렬로 보내도 상한을 넘지 못한다.</b>
   *
   * <p>세어 보고 나중에 기록하면 동시에 도착한 요청들이 <b>모두 같은 옛 카운트를 읽고 전부 통과한다</b> — 병렬로 보내는 것만으로 상한이 무의미해진다 (#187
   * 리뷰). 순차 사례만으로는 그 구현도 통과하므로 여기서 겨루게 한다.
   */
  @Test
  void parallelAttemptsCannotOvershootTheLimit() throws Exception {
    int burst = PER_ACCOUNT_LIMIT * 3;
    ExecutorService pool = Executors.newFixedThreadPool(burst);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<?>> shots = new ArrayList<>();
      for (int shot = 0; shot < burst; shot++) {
        shots.add(
            pool.submit(
                () -> {
                  start.await();
                  // 잠긴 뒤의 요청도 같은 403이라 결과를 가리지 않고 보낸다.
                  mockMvc.perform(bootstrap(founder, WRONG)).andExpect(status().isForbidden());
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> shot : shots) {
        shot.get(60, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(attempts.findAll()).as("병렬로 보내도 잡히는 자리는 상한까지다").hasSize(PER_ACCOUNT_LIMIT);
    assertThat(roleOf(founder)).isEqualTo(Role.USER);
  }
}
