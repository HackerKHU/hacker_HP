package org.hackerkhu.hackerhp.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.audit.entity.AdminAction;
import org.hackerkhu.hackerhp.domain.audit.entity.AdminActionLog;
import org.hackerkhu.hackerhp.domain.audit.repository.AdminActionLogRepository;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.domain.user.service.AdminUserRoleService;
import org.hackerkhu.hackerhp.domain.user.service.GoogleAccountService;
import org.hackerkhu.testsupport.auth.TestSessions.SignedIn;
import org.hackerkhu.testsupport.user.Accounts;
import org.hackerkhu.testsupport.web.Csrf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 가입 거부·회원 제거·권한 변경 (#58, spec 2-2 §2-2-2 §2-2-4 §2-2-5 §2-2-7).
 *
 * <p><b>막는 것만큼 여는 것이 중요하다.</b> 자기 자신을 무조건 막으면 관리자가 여럿이어도 서로 대신 처리해야 하고, 아무것도 막지 않으면 활성 관리자가 0명이 되어
 * 아무도 시스템에 들어갈 수 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminUserManagementIntegrationTest extends AbstractIntegrationTest {

  private static final String BASE = "/api/v1/admin/users";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private AdminActionLogRepository actions;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private AdminUserRoleService roleService;
  @Autowired private GoogleAccountService googleAccountService;

  private User admin;

  @BeforeEach
  void setUp() {
    clearAll();
    admin = userRepository.saveAndFlush(Accounts.admin("sub-admin", "admin@khu.ac.kr", "20200000"));
  }

  @AfterEach
  void clear() {
    clearAll();
  }

  private void clearAll() {
    jdbcTemplate.update("DELETE FROM bookmarks");
    jdbcTemplate.update("DELETE FROM note_files");
    jdbcTemplate.update("DELETE FROM notes");
    jdbcTemplate.update("DELETE FROM notices");
    jdbcTemplate.update("DELETE FROM posts");
    jdbcTemplate.update("DELETE FROM photos");
    actions.deleteAll();
    userRepository.deleteAll();
  }

  /* ------------------------------------------------------------------ 도구 */

  private MockHttpServletRequestBuilder rejectRequest(User caller, List<Long> ids) {
    String body = ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
    return Csrf.with(sessions.as(caller, post(BASE + "/reject")))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"userIds\":[" + body + "]}");
  }

  private MockHttpServletRequestBuilder roleRequest(User caller, Long targetId, Role role) {
    return Csrf.with(sessions.as(caller, patch(BASE + "/" + targetId + "/role")))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"role\":\"" + role + "\"}");
  }

  private MockHttpServletRequestBuilder removeRequest(User caller, Long targetId) {
    return Csrf.with(sessions.as(caller, delete(BASE + "/" + targetId)));
  }

  private User reload(User user) {
    return userRepository.findById(user.getId()).orElseThrow();
  }

  private boolean exists(User user) {
    return userRepository.existsById(user.getId());
  }

  private List<AdminAction> historyOf(User user) {
    return actions.findByTargetIdOrderByIdAsc(user.getId()).stream()
        .map(AdminActionLog::getAction)
        .toList();
  }

  /* ------------------------------------------------------------------ 거부 */

  /** 거부는 계정을 유지한 채 신청 정보를 지우고, 같은 id와 세션으로 다시 신청할 수 있게 한다 (§2-2-2). */
  @Test
  void rejectResetsTheApplicationAndKeepsTheAccountSessionForResubmission() throws Exception {
    User applicant =
        userRepository.saveAndFlush(Accounts.applied("sub-a", "a@khu.ac.kr", "20250001"));
    Long originalId = applicant.getId();
    /*
     * PostgreSQL TIMESTAMP는 마이크로초 정밀도로 저장한다. Linux의 Instant.now()는 나노초까지
     * 가질 수 있으므로 saveAndFlush가 돌려준 저장 전 객체와 재조회 값을 비교하면 CI에서만
     * 끝 세 자리가 달라진다. 거부가 바꾸면 안 되는 것은 DB에 저장된 생성 시각이다.
     */
    var originalCreatedAt = reload(applicant).getCreatedAt();
    SignedIn applicantSession = sessions.signIn(applicant);

    mockMvc
        .perform(rejectRequest(admin, List.of(applicant.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rejected[0]").value(applicant.getId()))
        .andExpect(jsonPath("$.failed").isEmpty());

    User reset = reload(applicant);
    assertThat(reset.getId()).isEqualTo(originalId);
    assertThat(reset.getGoogleSub()).isEqualTo("sub-a");
    assertThat(reset.getEmail()).isEqualTo("a@khu.ac.kr");
    assertThat(reset.getName()).isEqualTo(applicant.getName());
    assertThat(reset.getCreatedAt()).isEqualTo(originalCreatedAt);
    assertThat(reset.getStatus()).isEqualTo(Status.PENDING);
    assertThat(reset.getRole()).isEqualTo(Role.USER);
    assertThat(reset.getAppliedAt()).isNull();
    assertThat(reset.getStudentNo()).isNull();
    assertThat(reset.getDepartment()).isNull();
    assertThat(reset.getApprovedAt()).isNull();
    assertThat(reset.getDeactivatedAt()).isNull();
    assertThat(applicantSession.storedInRepository()).isTrue();

    User loggedBackIn = googleAccountService.login("sub-a", "a@khu.ac.kr", "바뀌어도덮지않는구글이름");
    assertThat(loggedBackIn.getId()).isEqualTo(originalId);
    assertThat(userRepository.count()).isEqualTo(2);

    mockMvc
        .perform(applicantSession.on(get("/api/v1/auth/me")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(originalId))
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.appliedAt").doesNotExist())
        .andExpect(jsonPath("$.studentNo").doesNotExist())
        .andExpect(jsonPath("$.department").doesNotExist());

    mockMvc
        .perform(
            Csrf.with(applicantSession.on(post("/api/v1/auth/application")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentNo\":\"20259999\",\"department\":\"인공지능학과\"}"))
        .andExpect(status().isNoContent());

    User resubmitted = reload(applicant);
    assertThat(resubmitted.getId()).isEqualTo(originalId);
    assertThat(resubmitted.getStudentNo()).isEqualTo("20259999");
    assertThat(resubmitted.getDepartment()).isEqualTo("인공지능학과");
    assertThat(resubmitted.getAppliedAt()).isNotNull();
    assertThat(applicantSession.storedInRepository()).isTrue();
    assertThat(historyOf(applicant)).containsExactly(AdminAction.REJECT);
  }

  /** 이미 미신청이면 목표 상태이므로 성공 응답에는 포함하지만, 실제 변경과 감사 이력은 반복하지 않는다. */
  @Test
  void rejectingAnAlreadyUnappliedAccountIsIdempotentWithoutAnotherAuditRow() throws Exception {
    User applicant =
        userRepository.saveAndFlush(Accounts.applied("sub-a", "a@khu.ac.kr", "20250001"));

    mockMvc.perform(rejectRequest(admin, List.of(applicant.getId()))).andExpect(status().isOk());
    mockMvc
        .perform(rejectRequest(admin, List.of(applicant.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rejected[0]").value(applicant.getId()))
        .andExpect(jsonPath("$.failed").isEmpty());

    assertThat(exists(applicant)).isTrue();
    assertThat(reload(applicant).getAppliedAt()).isNull();
    assertThat(historyOf(applicant)).containsExactly(AdminAction.REJECT);
  }

  /** 거부로 신청서 학번을 비우므로, 다른 미승인 계정이 그 학번을 새 신청에 사용할 수 있다. */
  @Test
  void rejectionReleasesTheStudentNumberForAnotherApplication() throws Exception {
    User applicant =
        userRepository.saveAndFlush(Accounts.applied("sub-a", "a@khu.ac.kr", "20250001"));
    User other = userRepository.saveAndFlush(Accounts.signedIn("sub-other", "other@khu.ac.kr"));

    mockMvc.perform(rejectRequest(admin, List.of(applicant.getId()))).andExpect(status().isOk());
    mockMvc
        .perform(
            Csrf.with(sessions.as(other, post("/api/v1/auth/application")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"studentNo\":\"20250001\",\"department\":\"컴퓨터공학과\"}"))
        .andExpect(status().isNoContent());

    assertThat(reload(other).getStudentNo()).isEqualTo("20250001");
    assertThat(reload(applicant).getStudentNo()).isNull();
  }

  /**
   * <b>이용 중인 회원은 이 경로로 지울 수 없다.</b>
   *
   * <p>그것은 "제거"이고, 세션 폐기·정지 선행·콘텐츠 처리 같은 규칙이 따로 붙는다 (§2-2-4). 목록에서 걸렀더라도 API를 직접 부르는 경로가 남아 있다.
   */
  @Test
  void rejectRefusesAnActiveMember() throws Exception {
    User member =
        userRepository.saveAndFlush(Accounts.approved("sub-m", "m@khu.ac.kr", "20250002"));

    mockMvc
        .perform(rejectRequest(admin, List.of(member.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rejected").isEmpty())
        .andExpect(jsonPath("$.failed[0].reason").value("NOT_PENDING"));

    assertThat(exists(member)).isTrue();
    assertThat(reload(member).getStatus()).isEqualTo(Status.ACTIVE);
    assertThat(reload(member).getStudentNo()).isEqualTo("20250002");
  }

  /** 활동·비활동·정지 회원과 없는 id는 혼합 요청에서도 모두 실패로 남고 어느 계정도 바뀌지 않는다. */
  @Test
  void rejectReportsEveryNonPendingStateAndMissingIdWithoutMutation() throws Exception {
    User active =
        userRepository.saveAndFlush(
            Accounts.approved("sub-active", "active@khu.ac.kr", "20251001"));
    User inactive =
        userRepository.saveAndFlush(
            Accounts.inactive("sub-inactive", "inactive@khu.ac.kr", "20251002"));
    User suspended =
        userRepository.saveAndFlush(
            Accounts.suspended("sub-suspended", "suspended@khu.ac.kr", "20251003"));

    mockMvc
        .perform(
            rejectRequest(
                admin, List.of(active.getId(), inactive.getId(), suspended.getId(), 999_999L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rejected").isEmpty())
        .andExpect(jsonPath("$.failed[0].reason").value("NOT_PENDING"))
        .andExpect(jsonPath("$.failed[1].reason").value("NOT_PENDING"))
        .andExpect(jsonPath("$.failed[2].reason").value("NOT_PENDING"))
        .andExpect(jsonPath("$.failed[3].reason").value("NOT_FOUND"));

    assertThat(reload(active).getStatus()).isEqualTo(Status.ACTIVE);
    assertThat(reload(inactive).getStatus()).isEqualTo(Status.INACTIVE);
    assertThat(reload(inactive).getDeactivatedAt()).isNotNull();
    assertThat(reload(suspended).getStatus()).isEqualTo(Status.SUSPENDED);
    assertThat(historyOf(active)).isEmpty();
    assertThat(historyOf(inactive)).isEmpty();
    assertThat(historyOf(suspended)).isEmpty();
  }

  /** 일부가 실패해도 성공한 건은 살아남는다. 한 건 때문에 되돌리면 나머지까지 사라진다. */
  @Test
  void rejectKeepsSuccessesWhenSomeFail() throws Exception {
    User applicant =
        userRepository.saveAndFlush(Accounts.applied("sub-a", "a@khu.ac.kr", "20250001"));

    mockMvc
        .perform(rejectRequest(admin, List.of(applicant.getId(), 999_999L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rejected.length()").value(1))
        .andExpect(jsonPath("$.failed[0].reason").value("NOT_FOUND"));

    assertThat(exists(applicant)).isTrue();
    assertThat(reload(applicant).getAppliedAt()).isNull();
    assertThat(historyOf(applicant)).containsExactly(AdminAction.REJECT);
  }

  /* -------------------------------------------------------------- 권한 변경 */

  /** 부여와 회수가 <b>서로 다른 동작으로</b> 이력에 남는다 — 뭉치면 방향을 알 수 없다. */
  @Test
  void grantAndRevokeAreRecordedSeparately() throws Exception {
    User member =
        userRepository.saveAndFlush(Accounts.approved("sub-m", "m@khu.ac.kr", "20250002"));

    mockMvc.perform(roleRequest(admin, member.getId(), Role.ADMIN)).andExpect(status().isOk());
    assertThat(reload(member).getRole()).isEqualTo(Role.ADMIN);

    mockMvc.perform(roleRequest(admin, member.getId(), Role.USER)).andExpect(status().isOk());
    assertThat(reload(member).getRole()).isEqualTo(Role.USER);

    assertThat(historyOf(member))
        .containsExactly(AdminAction.GRANT_ADMIN, AdminAction.REVOKE_ADMIN);
  }

  /** 이미 활동 중인 회원의 승격은 상태를 그대로 둔다. */
  @Test
  void changingRoleLeavesStatusAlone() throws Exception {
    User member =
        userRepository.saveAndFlush(Accounts.approved("sub-m", "m@khu.ac.kr", "20250002"));

    mockMvc.perform(roleRequest(admin, member.getId(), Role.ADMIN)).andExpect(status().isOk());

    assertThat(reload(member).getStatus()).isEqualTo(Status.ACTIVE);
  }

  /** #324. 비활동·정지 일반 회원의 승격은 중간 invalid 조합 없이 활동 관리자로 끝난다. */
  @Test
  void grantingAdminNormalizesInactiveAndSuspendedMembersToActive() throws Exception {
    User inactive =
        userRepository.saveAndFlush(Accounts.inactive("sub-i", "inactive@khu.ac.kr", "20250003"));
    User suspended =
        userRepository.saveAndFlush(Accounts.suspended("sub-s", "suspended@khu.ac.kr", "20250004"));

    for (User member : List.of(inactive, suspended)) {
      mockMvc
          .perform(roleRequest(admin, member.getId(), Role.ADMIN))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.role").value("ADMIN"))
          .andExpect(jsonPath("$.status").value("ACTIVE"))
          .andExpect(jsonPath("$.deactivatedAt").isEmpty());

      User promoted = reload(member);
      assertThat(promoted.getRole()).isEqualTo(Role.ADMIN);
      assertThat(promoted.getStatus()).isEqualTo(Status.ACTIVE);
      assertThat(promoted.getDeactivatedAt()).isNull();
      assertThat(historyOf(promoted)).containsExactly(AdminAction.GRANT_ADMIN);
    }
  }

  /** 이미 그 권한이면 아무것도 바뀌지 않고, <b>이력도 쌓이지 않는다</b> (#143). */
  @Test
  void repeatingARoleChangeRecordsNothingNew() throws Exception {
    User member =
        userRepository.saveAndFlush(Accounts.approved("sub-m", "m@khu.ac.kr", "20250002"));

    mockMvc.perform(roleRequest(admin, member.getId(), Role.ADMIN)).andExpect(status().isOk());
    mockMvc.perform(roleRequest(admin, member.getId(), Role.ADMIN)).andExpect(status().isOk());

    assertThat(historyOf(member)).containsExactly(AdminAction.GRANT_ADMIN);
  }

  /** 승인 대기 계정은 이 경로의 대상이 아니다 — 승인일시 없는 {@code ADMIN}이 생긴다. */
  @Test
  void pendingAccountCannotBecomeAdmin() throws Exception {
    User applicant =
        userRepository.saveAndFlush(Accounts.applied("sub-a", "a@khu.ac.kr", "20250001"));

    mockMvc
        .perform(roleRequest(admin, applicant.getId(), Role.ADMIN))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  /* -------------------------------------------------------------- 안전장치 */

  /**
   * <b>마지막 활성 관리자는 자기 권한을 회수할 수 없다</b> (§2-2-7).
   *
   * <p>막는 기준은 "자기 자신인가"가 아니라 <b>"이 조작 뒤에도 활성 관리자가 남는가"</b>다.
   */
  @Test
  void theLastActiveAdminCannotRevokeThemselves() throws Exception {
    mockMvc
        .perform(roleRequest(admin, admin.getId(), Role.USER))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertThat(reload(admin).getRole()).isEqualTo(Role.ADMIN);
  }

  /**
   * <b>관리자가 둘이면 자기 자신도 건드릴 수 있다</b> (§2-2-7).
   *
   * <p>자기 대상을 무조건 막으면, 관리자가 여럿이어도 다른 사람이 대신 처리해줘야 하는 불필요한 제약이 생긴다.
   */
  @Test
  void anAdminMayRevokeThemselvesWhenAnotherRemains() throws Exception {
    userRepository.saveAndFlush(Accounts.admin("sub-a2", "a2@khu.ac.kr", "20200001"));

    mockMvc.perform(roleRequest(admin, admin.getId(), Role.USER)).andExpect(status().isOk());

    assertThat(reload(admin).getRole()).isEqualTo(Role.USER);
  }

  /** 정지된 관리자는 활성 관리자로 세지 않는다 — role만 남아 있어도 시스템을 운영하지 못한다. */
  @Test
  void aSuspendedAdminDoesNotCountAsAnActiveOne() throws Exception {
    userRepository.saveAndFlush(Accounts.suspendedAdmin("sub-sa", "sa@khu.ac.kr", "20200002"));

    mockMvc.perform(roleRequest(admin, admin.getId(), Role.USER)).andExpect(status().isForbidden());
  }

  /**
   * T-272 — 활성 관리자 둘이 <b>동시에 각자 자기 권한을 회수한다</b> (§2-2-7 MUST, #197 리뷰 2차).
   *
   * <p><b>순차 테스트로는 못 잡는다.</b> 한 번에 하나씩 부르면 뒤엣것이 이미 줄어든 수를 보고 자기 검사에 걸린다. 둘이 동시에 오면 <b>같은 "관리자 2명"을
   * 보고 둘 다 통과해</b> 0명이 된다.
   *
   * <p><b>서로를 회수하는 조합이 아니라 각자 자기 것을 회수하는 조합이어야 한다.</b> 서로를 대상으로 하면 두 요청이 같은 두 행({@code
   * requester}·{@code target})을 잠그므로 활성 관리자 전부를 모으는 코드가 없어도 자연히 줄이 선다 — 그 조합으로는 이 사례를 재현하지 못한다. 각자
   * 자기 것을 회수하면 잠그는 행이 자기 하나뿐이라 <b>모아 잠그는 코드만이 유일한 방어</b>가 된다.
   */
  @Test
  void twoAdminsRevokingThemselvesAtOnceCannotBothSucceed() throws Exception {
    User other = userRepository.saveAndFlush(Accounts.admin("sub-a3", "a3@khu.ac.kr", "20200003"));

    CyclicBarrier ready = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Boolean>> results =
          pool.invokeAll(
              List.of(
                  revoke(admin.getId(), admin.getId(), ready),
                  revoke(other.getId(), other.getId(), ready)));

      assertThat(results.stream().filter(AdminUserManagementIntegrationTest::succeeded).count())
          .as("둘 다 성공하면 활성 관리자가 0명이 된다")
          .isLessThanOrEqualTo(1);
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    // 무엇보다 중요한 것 — 아무도 시스템에 들어가지 못하는 상태가 되지 않는다.
    assertThat(userRepository.countByRoleAndStatus(Role.ADMIN, Status.ACTIVE)).isPositive();
  }

  private Callable<Boolean> revoke(Long requesterId, Long targetId, CyclicBarrier ready) {
    return () -> {
      ready.await(10, TimeUnit.SECONDS);
      roleService.change(requesterId, targetId, Role.USER);
      return true;
    };
  }

  private static boolean succeeded(Future<Boolean> result) {
    try {
      return Boolean.TRUE.equals(result.get());
    } catch (Exception e) {
      return false;
    }
  }

  /** 마지막 활성 관리자는 제거도 막힌다 — 정지·회수와 같은 규칙이다. */
  @Test
  void theLastActiveAdminCannotBeRemoved() throws Exception {
    mockMvc.perform(removeRequest(admin, admin.getId())).andExpect(status().isForbidden());

    assertThat(exists(admin)).isTrue();
  }

  /* ------------------------------------------------------------------ 제거 */

  /** 제거하면 계정이 사라지고 이력에 남는다. */
  @Test
  void removeDeletesTheAccountAndRecordsIt() throws Exception {
    User member =
        userRepository.saveAndFlush(Accounts.approved("sub-m", "m@khu.ac.kr", "20250002"));

    mockMvc.perform(removeRequest(admin, member.getId())).andExpect(status().isNoContent());

    assertThat(exists(member)).isFalse();
    // 계정은 사라졌지만 이력은 남는다 — admin_actions에 FK가 없는 이유다.
    assertThat(historyOf(member)).contains(AdminAction.REMOVE);
  }

  /**
   * <b>제거 전에 정지가 먼저 확정된다</b> (§2-2-4 MUST).
   *
   * <p>세션 폐기는 계정이 사라진 뒤라 실패해도 되돌릴 수 없다. 정지가 먼저면 어느 지점에서 실패하든 이미 막혀 있다 — 그 흔적이 이력에 남는다.
   */
  @Test
  void removeSuspendsFirst() throws Exception {
    User member =
        userRepository.saveAndFlush(Accounts.approved("sub-m", "m@khu.ac.kr", "20250002"));

    mockMvc.perform(removeRequest(admin, member.getId())).andExpect(status().isNoContent());

    assertThat(historyOf(member)).containsExactly(AdminAction.SUSPEND, AdminAction.REMOVE);
  }

  /**
   * <b>제거된 회원의 세션이 하나도 남지 않는다</b> (§2-2-4 MUST).
   *
   * <p>필터는 매 요청 {@code users}를 읽지 않으므로(결정 12) 남기면 계정 없는 사람이 만료까지 인증된다.
   */
  @Test
  void removeLeavesNoSession() throws Exception {
    User member =
        userRepository.saveAndFlush(Accounts.approved("sub-m", "m@khu.ac.kr", "20250002"));
    var signedIn = sessions.signIn(member);
    assertThat(signedIn.storedInRepository()).isTrue();

    mockMvc.perform(removeRequest(admin, member.getId())).andExpect(status().isNoContent());

    assertThat(signedIn.storedInRepository()).isFalse();
  }

  /** 승인 대기 계정도 제거할 수 있다. 정지 경로를 탈 수 없으므로 그대로 지운다. */
  @Test
  void removeWorksForAPendingAccount() throws Exception {
    User applicant =
        userRepository.saveAndFlush(Accounts.applied("sub-a", "a@khu.ac.kr", "20250001"));

    mockMvc.perform(removeRequest(admin, applicant.getId())).andExpect(status().isNoContent());

    assertThat(exists(applicant)).isFalse();
    assertThat(historyOf(applicant)).containsExactly(AdminAction.REMOVE);
  }

  /** 신청서를 내지 않은 승인 전 계정도 같은 제거 경로를 쓰며, 남아 있는 세션과 계정을 함께 지운다. */
  @Test
  void removeWorksForAPendingAccountThatNeverApplied() throws Exception {
    User pending = userRepository.saveAndFlush(Accounts.signedIn("sub-u", "unapplied@khu.ac.kr"));
    SignedIn pendingSession = sessions.signIn(pending);
    assertThat(pending.getAppliedAt()).isNull();

    mockMvc.perform(removeRequest(admin, pending.getId())).andExpect(status().isNoContent());

    assertThat(exists(pending)).isFalse();
    assertThat(pendingSession.storedInRepository()).isFalse();
    assertThat(historyOf(pending)).containsExactly(AdminAction.REMOVE);
  }

  /** 없는 회원은 {@code 404}다. */
  @Test
  void removingAMissingMemberIsNotFound() throws Exception {
    mockMvc
        .perform(removeRequest(admin, 999_999L))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  /* ------------------------------------------------------ 남긴 것은 남는다 */

  /**
   * <b>제거해도 자료·공지는 남고 작성자만 빈다</b> (§2-2-4 MUST).
   *
   * <p>{@code notices.author_id}는 {@code V1}에 {@code ON DELETE} 절 없이 만들어져 있었다 — 그대로면 <b>공지를 한 번이라도
   * 쓴 관리자는 삭제 자체가 FK 위반으로 실패한다.</b>
   */
  @Test
  void removeKeepsWhatTheyPostedAndOnlyClearsTheAuthor() throws Exception {
    User member =
        userRepository.saveAndFlush(Accounts.approved("sub-m", "m@khu.ac.kr", "20250002"));
    Long noteId = insertNote(member.getId());
    Long noticeId = insertNotice(member.getId());

    mockMvc.perform(removeRequest(admin, member.getId())).andExpect(status().isNoContent());

    assertThat(uploaderOfNote(noteId)).isNull();
    assertThat(authorOfNotice(noticeId)).isNull();
  }

  /**
   * <b>빈 작성자 자리를 응답이 "탈퇴한 회원"으로 채운다</b> (§2-2-4, 3-2 §3-2-2 MUST).
   *
   * <p>FK가 비우는 것까지는 앞 테스트가 본다. 그런데 <b>화면이 읽는 것은 응답이다</b> — 서버가 채우지 않으면 목록·상세·자료마다 각자 다른 문구를 쓰거나 작성자
   * 줄이 통째로 비어 "글이 깨진 것"처럼 보인다. 문구를 서버 한 곳에 두는 것이 이 계약의 요지다.
   */
  @Test
  void aRemovedAuthorShowsAsWithdrawnInTheResponse() throws Exception {
    User member =
        userRepository.saveAndFlush(Accounts.approved("sub-m", "m@khu.ac.kr", "20250002", "김부원"));
    Long noticeId = insertNotice(member.getId());

    mockMvc
        .perform(sessions.as(admin, get("/api/v1/notices/" + noticeId)))
        .andExpect(status().isOk())
        // 표시 이름이라 학번 끝 두 자리가 붙는다 (#301, 3-2 §3-2-2).
        .andExpect(jsonPath("$.authorName").value("김부원02"));

    mockMvc.perform(removeRequest(admin, member.getId())).andExpect(status().isNoContent());

    mockMvc
        .perform(sessions.as(admin, get("/api/v1/notices/" + noticeId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authorId").doesNotExist())
        .andExpect(jsonPath("$.authorName").value("탈퇴한 회원"));
  }

  /** 즐겨찾기는 <b>함께 사라진다</b> — 그 사람만 보던 목록이라 남길 이유가 없다. */
  @Test
  void removeDeletesTheirBookmarks() throws Exception {
    User member =
        userRepository.saveAndFlush(Accounts.approved("sub-m", "m@khu.ac.kr", "20250002"));
    Long noteId = insertNote(member.getId());
    jdbcTemplate.update(
        "INSERT INTO bookmarks (user_id, note_id, created_at) VALUES (?, ?, now())",
        member.getId(),
        noteId);

    mockMvc.perform(removeRequest(admin, member.getId())).andExpect(status().isNoContent());

    assertThat(countBookmarks()).isZero();
  }

  /* -------------------------------------------------------- 제거 영향 조회 */

  /** <b>세 값을 항상 담는다</b> — {@code 0}을 빼면 화면이 "없음"과 "모름"을 가르지 못한다. */
  @Test
  void contentSummaryAlwaysCarriesEveryCount() throws Exception {
    User member =
        userRepository.saveAndFlush(Accounts.approved("sub-m", "m@khu.ac.kr", "20250002"));
    insertNote(member.getId());
    insertNote(member.getId());
    insertNotice(member.getId());
    insertPost(member.getId());

    mockMvc
        .perform(sessions.as(admin, get(BASE + "/" + member.getId() + "/content-summary")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.notes").value(2))
        .andExpect(jsonPath("$.notices").value(1))
        .andExpect(jsonPath("$.photos").value(0))
        // 콘텐츠 종류가 늘면 이 응답도 늘어야 한다 (#236) — 빠지면 관리자가 게시글이
        // 남는다는 사실을 보지 못한 채 되돌릴 수 없는 제거를 한다.
        .andExpect(jsonPath("$.posts").value(1));
  }

  @Test
  void contentSummaryOfAMissingMemberIsNotFound() throws Exception {
    mockMvc
        .perform(sessions.as(admin, get(BASE + "/999999/content-summary")))
        .andExpect(status().isNotFound());
  }

  /* ------------------------------------------------------------------ 권한 */

  /** 셋 다 {@code ADMIN} 전용이다. 일반 회원은 {@code 403}이다. */
  @Test
  void allThreePathsAreAdminOnly() throws Exception {
    User member =
        userRepository.saveAndFlush(Accounts.approved("sub-m", "m@khu.ac.kr", "20250002"));

    mockMvc.perform(rejectRequest(member, List.of(1L))).andExpect(status().isForbidden());
    mockMvc
        .perform(roleRequest(member, admin.getId(), Role.USER))
        .andExpect(status().isForbidden());
    mockMvc.perform(removeRequest(member, admin.getId())).andExpect(status().isForbidden());
    mockMvc
        .perform(sessions.as(member, get(BASE + "/" + admin.getId() + "/content-summary")))
        .andExpect(status().isForbidden());

    assertThat(exists(admin)).isTrue();
  }

  /* ------------------------------------------------------------------ SQL */

  private Long insertNote(Long uploaderId) {
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO notes (category, title, subject_name, year, semester, uploader_id,
                           created_at, updated_at)
        VALUES ('SUBJECT', '정리', '운영체제', 2025, 'SPRING', ?, now(), now()) RETURNING id
        """,
        Long.class,
        uploaderId);
  }

  private void insertPost(Long authorId) {
    jdbcTemplate.update(
        "INSERT INTO posts (title, content, author_id, created_at, updated_at)"
            + " VALUES ('제목', '본문', ?, NOW(), NOW())",
        authorId);
  }

  private Long insertNotice(Long authorId) {
    return jdbcTemplate.queryForObject(
        """
        INSERT INTO notices (title, content, is_pinned, author_id, created_at, updated_at)
        VALUES ('공지', '본문', false, ?, now(), now()) RETURNING id
        """,
        Long.class,
        authorId);
  }

  private Long uploaderOfNote(Long noteId) {
    return jdbcTemplate.queryForObject(
        "SELECT uploader_id FROM notes WHERE id = ?", Long.class, noteId);
  }

  private Long authorOfNotice(Long noticeId) {
    return jdbcTemplate.queryForObject(
        "SELECT author_id FROM notices WHERE id = ?", Long.class, noticeId);
  }

  private Integer countBookmarks() {
    return jdbcTemplate.queryForObject("SELECT count(*) FROM bookmarks", Integer.class);
  }
}
