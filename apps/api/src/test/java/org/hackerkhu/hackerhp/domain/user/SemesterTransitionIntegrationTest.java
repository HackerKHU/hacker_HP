package org.hackerkhu.hackerhp.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.audit.entity.AdminAction;
import org.hackerkhu.hackerhp.domain.audit.entity.AdminActionLog;
import org.hackerkhu.hackerhp.domain.audit.repository.AdminActionLogRepository;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.domain.user.service.SemesterTransitionService;
import org.hackerkhu.testsupport.user.Accounts;
import org.hackerkhu.testsupport.web.Csrf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 학기 전환 — 일괄 비활성화와 복구 (spec 2-2 §2-2-3, T-339 ~ T-345·T-350·T-351·T-366·T-367, #228 #230).
 *
 * <p>이 묶음에서 어려운 것은 <b>대상이 좁은가</b>와 <b>되돌릴 수 있는가</b> 둘이다. 조건을 잘못 잡으면 정지가 풀리고, 되돌릴 근거를 응답에만 두면 그 응답을
 * 잃는 순간 <b>원래 비활동이던 사람과 방금 내려간 사람을 가를 수 없다.</b>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SemesterTransitionIntegrationTest extends AbstractIntegrationTest {

  private static final String BASE = "/api/v1/admin/users";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private AdminActionLogRepository actions;
  @Autowired private SemesterTransitionService transitionService;

  private User admin;
  private User member;

  @BeforeEach
  void setUp() {
    clearAll();
    admin = save(Accounts.admin("sub-ad", "ad@khu.ac.kr", "20200001"));
    member = save(Accounts.approved("sub-m", "m@khu.ac.kr", "20250001"));
  }

  @AfterEach
  void clear() {
    clearAll();
  }

  private void clearAll() {
    actions.deleteAll();
    userRepository.deleteAll();
  }

  private User save(User user) {
    return userRepository.saveAndFlush(user);
  }

  private User reload(User user) {
    return userRepository.findById(user.getId()).orElseThrow();
  }

  private MockHttpServletRequestBuilder deactivate() {
    return Csrf.with(sessions.signIn(admin).on(post(BASE + "/deactivate")));
  }

  private MockHttpServletRequestBuilder reactivate(String body) {
    return Csrf.with(
        sessions
            .signIn(admin)
            .on(post(BASE + "/reactivate").contentType("application/json").content(body)));
  }

  private List<AdminActionLog> historyOf(Long userId) {
    return actions.findAll().stream().filter(a -> a.getTargetId().equals(userId)).toList();
  }

  /* ------------------------------------------------------------ 대상의 좁기 */

  /**
   * T-339. <b>{@code ACTIVE}인 일반 부원만</b> 내려간다.
   *
   * <p>{@code SUSPENDED}를 반드시 포함한다 — 정지된 계정이 {@code INACTIVE}가 되면 <b>정지가 풀린다.</b> 비활동은 자료 말고 다 되기
   * 때문이다. 조건을 {@code role = 'USER'}로만 쓴 구현이 정확히 여기서 깨진다.
   */
  @Test
  void onlyActiveOrdinaryMembersGoDown() throws Exception {
    User suspended = save(Accounts.suspended("sub-s", "s@khu.ac.kr", "20250002"));
    User pending = save(Accounts.applied("sub-p", "p@khu.ac.kr", "20250003"));
    User otherAdmin = save(Accounts.admin("sub-ad2", "ad2@khu.ac.kr", "20200002"));

    mockMvc.perform(deactivate()).andExpect(status().isOk());

    assertThat(reload(member).getStatus()).isEqualTo(Status.INACTIVE);
    assertThat(reload(suspended).getStatus()).as("정지가 풀리면 안 된다").isEqualTo(Status.SUSPENDED);
    assertThat(reload(pending).getStatus()).as("승인 절차를 건너뛰면 안 된다").isEqualTo(Status.PENDING);
    assertThat(reload(admin).getStatus()).as("관리자는 대상이 아니다").isEqualTo(Status.ACTIVE);
    assertThat(reload(otherAdmin).getStatus()).isEqualTo(Status.ACTIVE);
  }

  /** T-340. 응답은 <b>실제로 바뀐 id</b>뿐이다. */
  @Test
  void theResponseCarriesOnlyTheIdsThatActuallyChanged() throws Exception {
    User alreadyInactive = save(Accounts.inactive("sub-i", "i@khu.ac.kr", "20250009"));

    mockMvc
        .perform(deactivate())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deactivated.length()").value(1))
        .andExpect(jsonPath("$.deactivated[0]").value(member.getId()));

    assertThat(reload(alreadyInactive).getStatus()).isEqualTo(Status.INACTIVE);
  }

  /** T-341. 멱등하다 — 두 번째는 빈 배열이고 아무것도 바뀌지 않는다. */
  @Test
  void aSecondRunChangesNothing() throws Exception {
    mockMvc.perform(deactivate()).andExpect(status().isOk());

    mockMvc
        .perform(deactivate())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deactivated.length()").value(0));
  }

  /* --------------------------------------------------------- 되돌릴 수 있는가 */

  /**
   * T-366. <b>한 배치는 같은 {@code deactivatedAt}을 갖는다.</b>
   *
   * <p>행마다 따로 찍으면 한 배치가 시각으로 갈려 "직전 배치"를 고를 수 없다 — 되돌릴 근거가 그 값이므로 갈리면 되돌리기가 반쪽이 된다.
   */
  @Test
  void oneBatchSharesOneTimestamp() throws Exception {
    User second = save(Accounts.approved("sub-m2", "m2@khu.ac.kr", "20250004"));
    User third = save(Accounts.approved("sub-m3", "m3@khu.ac.kr", "20250005"));

    mockMvc.perform(deactivate()).andExpect(status().isOk());

    List<Instant> stamps =
        List.of(member, second, third).stream().map(u -> reload(u).getDeactivatedAt()).toList();
    assertThat(stamps).doesNotContainNull();
    assertThat(stamps).containsOnly(stamps.getFirst());
  }

  /**
   * T-367. {@code INACTIVE}를 벗어나면 <b>{@code deactivatedAt}이 지워진다</b> — 복구든 정지든.
   *
   * <p>남겨 두면 지금 비활동이 아닌 사람이 "직전 배치"에 섞여 <b>되돌리기가 엉뚱한 사람을 올린다.</b>
   */
  @Test
  void leavingInactiveClearsTheTimestamp() throws Exception {
    User toSuspend = save(Accounts.approved("sub-m2", "m2@khu.ac.kr", "20250004"));
    mockMvc.perform(deactivate()).andExpect(status().isOk());
    assertThat(reload(member).getDeactivatedAt()).isNotNull();

    mockMvc
        .perform(reactivate("{\"userIds\":[" + member.getId() + "]}"))
        .andExpect(status().isOk());
    assertThat(reload(member).getDeactivatedAt()).as("복구하면 지워진다").isNull();

    mockMvc
        .perform(
            Csrf.with(
                sessions
                    .signIn(admin)
                    .on(
                        patch(BASE + "/" + toSuspend.getId() + "/status")
                            .contentType("application/json")
                            .content("{\"status\":\"SUSPENDED\"}"))))
        .andExpect(status().isOk());
    assertThat(reload(toSuspend).getDeactivatedAt()).as("정지시켜도 지워진다").isNull();
  }

  /** 목록 응답이 그 값을 실어 화면이 직전 배치를 고를 수 있다. */
  @Test
  void theMemberListCarriesTheTimestamp() throws Exception {
    mockMvc.perform(deactivate()).andExpect(status().isOk());

    mockMvc
        .perform(sessions.signIn(admin).on(get(BASE + "?status=INACTIVE")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].deactivatedAt").exists());
  }

  /* ------------------------------------------------------------------ 복구 */

  /** T-345. 섞어 보내면 되는 것만 되고 나머지는 사유와 함께 돌아온다. */
  @Test
  void restoringAMixedListReportsEachFailure() throws Exception {
    User suspended = save(Accounts.suspended("sub-s", "s@khu.ac.kr", "20250002"));
    mockMvc.perform(deactivate()).andExpect(status().isOk());

    mockMvc
        .perform(
            reactivate("{\"userIds\":[" + member.getId() + "," + suspended.getId() + ",999999]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reactivated.length()").value(1))
        .andExpect(jsonPath("$.reactivated[0]").value(member.getId()))
        .andExpect(jsonPath("$.failed.length()").value(2))
        /*
         * 사유를 각각 확인한다. 길이만 재면 둘이 뒤바뀌거나 reason이 잘못 직렬화돼도
         * 통과하는데, 화면은 이 값으로 "정지된 계정이라 복구할 수 없다"와 "없는
         * 계정이다"를 갈라 안내한다.
         */
        .andExpect(
            jsonPath("$.failed[?(@.userId == " + suspended.getId() + ")].reason")
                .value("NOT_INACTIVE"))
        .andExpect(jsonPath("$.failed[?(@.userId == 999999)].reason").value("NOT_FOUND"));

    assertThat(reload(member).getStatus()).isEqualTo(Status.ACTIVE);
    assertThat(reload(suspended).getStatus()).as("이 경로로 정지를 풀 수 없다").isEqualTo(Status.SUSPENDED);
  }

  /** 빈 배열은 요청 자체가 틀린 것이다. */
  @Test
  void anEmptyRestoreListIsRejected() throws Exception {
    mockMvc
        .perform(reactivate("{\"userIds\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  /* ------------------------------------------------------------------ 이력 */

  /**
   * T-350. 대상마다 한 행이고 <b>두 방향이 갈려 있다.</b>
   *
   * <p>뭉치면 이력을 읽어도 내려간 것인지 올라온 것인지 알 수 없다. 정지 해제({@code ACTIVATE})와도 갈라야 한다 — 도착지는 같지만 있었던 일이 다르다.
   */
  @Test
  void theHistoryTellsTheTwoDirectionsApart() throws Exception {
    mockMvc.perform(deactivate()).andExpect(status().isOk());
    mockMvc
        .perform(reactivate("{\"userIds\":[" + member.getId() + "]}"))
        .andExpect(status().isOk());

    assertThat(historyOf(member.getId()))
        .extracting(AdminActionLog::getAction)
        .containsExactly(AdminAction.DEACTIVATE, AdminAction.REACTIVATE);
  }

  /** T-351. 아무것도 바꾸지 않은 재요청은 이력에 남지 않는다. */
  @Test
  void aNoOpRerunAddsNoHistory() throws Exception {
    mockMvc.perform(deactivate()).andExpect(status().isOk());
    int after = actions.findAll().size();

    mockMvc.perform(deactivate()).andExpect(status().isOk());

    assertThat(actions.findAll()).hasSize(after);
  }

  /**
   * T-359. 두 관리자가 <b>동시에</b> 전환을 실행해도 한 id는 한 응답에만 담긴다.
   *
   * <p>순차로 두 번 부르는 T-341은 <b>조회 후 갱신하는 구현도 통과한다.</b> 그 구현은 동시 호출에서만 깨진다 — 둘이 같은 {@code ACTIVE} 집합을
   * 읽어 양쪽 응답에 같은 id가 담기고 이력도 두 벌 쌓인다. 그러면 한쪽 응답으로 되돌리기를 하면 <b>남이 방금 내린 사람까지 올라온다.</b>
   */
  @Test
  void twoTransitionsAtOnceNeverClaimTheSameMember() throws Exception {
    User second = save(Accounts.approved("sub-m2", "m2@khu.ac.kr", "20250004"));

    CyclicBarrier ready = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Long> claimed = Collections.synchronizedList(new ArrayList<>());
    try {
      pool.invokeAll(List.of(running(ready, claimed), running(ready, claimed)));
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    assertThat(claimed).as("한 id가 양쪽 응답에 담기면 안 된다").doesNotHaveDuplicates();
    assertThat(claimed).containsExactlyInAnyOrder(member.getId(), second.getId());
    assertThat(actions.findAll()).as("이력도 대상마다 한 행이다").hasSize(2);
  }

  private Callable<Boolean> running(CyclicBarrier ready, List<Long> claimed) {
    return () -> {
      ready.await(10, TimeUnit.SECONDS);
      claimed.addAll(transitionService.deactivate(admin.getId()).deactivated());
      return true;
    };
  }

  /**
   * T-369. 교체한 {@code CHECK} 제약이 <b>열한 값을 전부</b> 받는다.
   *
   * <p>새 값 둘만 저장하는 사례로는 부족하다 — 제약에서 기존 값 하나를 빠뜨려도 <b>{@link
   * org.hackerkhu.hackerhp.domain.audit.service.AdminActionRecorder}가 실패를 삼켜</b> 화면에는 아무 일도 없어 보인 채
   * 감사 기록만 조용히 빈다. 실제 마이그레이션이 돈 DB에 하나씩 넣어 본다.
   */
  @Test
  void everyAdminActionValueSurvivesTheCheckConstraint() {
    Instant at = Instant.now();
    for (AdminAction action : AdminAction.values()) {
      actions.save(AdminActionLog.of(admin.getId(), member.getId(), action, at));
    }
    actions.flush();

    assertThat(actions.findAll())
        .extracting(AdminActionLog::getAction)
        .containsExactlyInAnyOrder(AdminAction.values());
  }

  /* ------------------------------------------------------------------ 인가 */

  /** 일반 부원은 부를 수 없다. 마지막 활성 관리자 보호는 `ADMIN`을 대상에서 빼므로 자동으로 지켜진다. */
  @Test
  void anOrdinaryMemberCannotRunTheTransition() throws Exception {
    mockMvc
        .perform(Csrf.with(sessions.signIn(member).on(post(BASE + "/deactivate"))))
        .andExpect(status().isForbidden());

    assertThat(reload(member).getStatus()).isEqualTo(Status.ACTIVE);
  }

  /** 전환이 끝나도 <b>활성 관리자는 그대로 남는다</b> — 아무도 운영할 수 없는 상태가 되지 않는다. */
  @Test
  void theTransitionNeverLeavesZeroActiveAdmins() throws Exception {
    mockMvc.perform(deactivate()).andExpect(status().isOk());

    assertThat(reload(admin).getStatus()).isEqualTo(Status.ACTIVE);
    assertThat(reload(admin).getRole())
        .isEqualTo(org.hackerkhu.hackerhp.domain.user.entity.Role.ADMIN);
  }
}
