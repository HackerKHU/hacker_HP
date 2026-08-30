package org.hackerkhu.hackerhp.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.hackerkhu.hackerhp.domain.audit.repository.AdminActionLogRepository;
import org.hackerkhu.hackerhp.domain.user.dto.StatusChangeRequest.Target;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.domain.user.service.AdminSuspensionPolicy;
import org.hackerkhu.hackerhp.domain.user.service.AdminUserStatusService;
import org.hackerkhu.hackerhp.global.auth.AuthSession;
import org.hackerkhu.hackerhp.global.auth.JwtProvider;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
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
import org.springframework.session.Session;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code PATCH /admin/users/{id}/status} (spec 2-2 §2-2-3, §2-2-7).
 *
 * <p>정지가 <b>기존 세션까지</b> 닿는지(T-32)는 실제 세션 저장소가 필요해 {@code SessionSynchronizationIntegrationTest}가
 * 본다. 여기서는 전이 규칙과 안전장치를 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminUserStatusIntegrationTest extends AbstractIntegrationTest {

  private static final String BASE = "/api/v1/admin/users/";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private AdminActionLogRepository actions;
  @Autowired private JwtProvider jwtProvider;
  @Autowired private AdminUserStatusService statusService;

  private User admin;
  private User member;
  private User suspended;
  private User applicant;

  @BeforeEach
  void createAccounts() {
    actions.deleteAll();
    userRepository.deleteAll();

    admin = userRepository.saveAndFlush(Accounts.admin("sub-ad", "admin@khu.ac.kr", "20200001"));
    member = userRepository.saveAndFlush(Accounts.approved("sub-us", "user@khu.ac.kr", "20240101"));

    User toSuspend = Accounts.approved("sub-sp", "suspended@khu.ac.kr", "20240102");
    toSuspend.suspend();
    suspended = userRepository.saveAndFlush(toSuspend);

    User pending = User.createFromGoogle("sub-pd", "pending@khu.ac.kr", "신청자");
    pending.submitApplication("20240103", "컴퓨터공학과");
    applicant = userRepository.saveAndFlush(pending);
  }

  @AfterEach
  void clear() {
    actions.deleteAll();
    userRepository.deleteAll();
  }

  private MockHttpServletRequestBuilder change(User caller, Long targetId, String body) {
    return Csrf.with(sessions.as(caller, patch(BASE + targetId + "/status")))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private MockHttpServletRequestBuilder change(Long targetId, String status) {
    return change(admin, targetId, "{\"status\":\"" + status + "\"}");
  }

  private Status statusOf(User user) {
    return userRepository.findById(user.getId()).orElseThrow().getStatus();
  }

  /* ---------------------------------------------------------------- 전이 */

  /** 완료 조건 — 갱신된 회원을 돌려준다. 화면이 재조회 없이 그 행을 고칠 수 있다. */
  @Test
  void suspendsAnActiveMember() throws Exception {
    mockMvc
        .perform(change(member.getId(), "SUSPENDED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(member.getId()))
        .andExpect(jsonPath("$.status").value("SUSPENDED"))
        .andExpect(jsonPath("$.googleSub").doesNotExist());

    assertThat(statusOf(member)).isEqualTo(Status.SUSPENDED);
  }

  @Test
  void reactivatesASuspendedMember() throws Exception {
    mockMvc
        .perform(change(suspended.getId(), "ACTIVE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));

    assertThat(statusOf(suspended)).isEqualTo(Status.ACTIVE);
  }

  /** 확인 창을 두 번 지나거나 낡은 목록에서 눌러도 오류가 아니다. */
  @Test
  void changingToTheSameStatusIsANoOp() throws Exception {
    mockMvc
        .perform(change(suspended.getId(), "SUSPENDED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUSPENDED"));

    assertThat(statusOf(suspended)).isEqualTo(Status.SUSPENDED);
  }

  /**
   * 승인 대기 계정은 이 경로의 대상이 아니다.
   *
   * <p>계약이 정한 전이는 {@code ACTIVE} ↔ {@code SUSPENDED}뿐이다 (2-2 §2-2-3). 여기로 승인시키면 <b>승인일시가 기록되지 않고 신청
   * 여부도 확인하지 않는다.</b>
   */
  @Test
  void pendingAccountCannotBeChangedHere() throws Exception {
    mockMvc
        .perform(change(applicant.getId(), "ACTIVE"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    assertThat(statusOf(applicant)).isEqualTo(Status.PENDING);
  }

  /** 계약에 없는 값은 서비스에 닿기 전에 끊긴다. */
  @Test
  void pendingIsNotAValidTarget() throws Exception {
    mockMvc
        .perform(change(member.getId(), "PENDING"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    assertThat(statusOf(member)).isEqualTo(Status.ACTIVE);
  }

  @Test
  void missingStatusIsRejected() throws Exception {
    mockMvc
        .perform(change(admin, member.getId(), "{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void missingMemberIsNotFound() throws Exception {
    mockMvc
        .perform(change(999_999L, "SUSPENDED"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  /* ------------------------------------------------------------ 안전장치 */

  /**
   * <b>활성 관리자가 0명이 되면 아무도 운영할 수 없다</b> (2-2 §2-2-7 MUST).
   *
   * <p>화면은 활성 관리자가 몇 명인지 모르므로 미리 막지 않는다. 서버가 거부하고 화면은 그 사유를 그대로 보여준다 (T-80).
   */
  @Test
  void lastActiveAdminCannotSuspendThemselves() throws Exception {
    mockMvc
        .perform(change(admin.getId(), "SUSPENDED"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.message").value(AdminSuspensionPolicy.MESSAGE));

    assertThat(statusOf(admin)).isEqualTo(Status.ACTIVE);
    assertThat(actions.findByTargetIdOrderByIdAsc(admin.getId())).isEmpty();
  }

  /** 관리자 수가 둘 이상이어도 직접 정지는 막힌다. 상태·role·세션·감사에 변화가 없어야 한다. */
  @Test
  void anAdminCannotDirectlySuspendAnotherAdminAndNothingChanges() throws Exception {
    User other =
        userRepository.saveAndFlush(Accounts.admin("sub-ad2", "admin2@khu.ac.kr", "20200002"));
    SignedIn existingSession = sessions.signIn(other);

    mockMvc
        .perform(change(other.getId(), "SUSPENDED"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.message").value(AdminSuspensionPolicy.MESSAGE));

    User unchanged = userRepository.findById(other.getId()).orElseThrow();
    assertThat(unchanged.getRole()).isEqualTo(Role.ADMIN);
    assertThat(unchanged.getStatus()).isEqualTo(Status.ACTIVE);
    assertThat(actions.findByTargetIdOrderByIdAsc(other.getId())).isEmpty();

    Session session = existingSession.repository().findById(existingSession.id());
    assertThat(session).isNotNull();
    assertThat(session.<Role>getAttribute(AuthSession.ROLE)).isEqualTo(Role.ADMIN);
    assertThat(session.<Status>getAttribute(AuthSession.STATUS)).isEqualTo(Status.ACTIVE);
  }

  /** 정지된 관리자는 세지 않는다 (MUST). 로그인할 수 없으므로 DB에 role만 남아 있어도 운영을 보장하지 못한다. */
  @Test
  void suspendedAdminIsRejectedByTheSamePolicy() throws Exception {
    User inactive = Accounts.admin("sub-ad3", "admin3@khu.ac.kr", "20200003");
    inactive.suspend();
    userRepository.saveAndFlush(inactive);

    mockMvc
        .perform(change(admin.getId(), "SUSPENDED"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.message").value(AdminSuspensionPolicy.MESSAGE));
  }

  /** 과거·비정상 ADMIN 조합도 자동 보정하지 않고 같은 정책으로 거절한다. */
  @Test
  void everyAdminStatusIsRejectedWithoutMutationOrAudit() throws Exception {
    User pendingAdmin = Accounts.applied("sub-pa", "pa@khu.ac.kr", "20200011");
    pendingAdmin.promoteToAdmin();
    pendingAdmin = userRepository.saveAndFlush(pendingAdmin);

    User inactiveAdmin = Accounts.inactive("sub-ia", "ia@khu.ac.kr", "20200012");
    inactiveAdmin.promoteToAdmin();
    inactiveAdmin = userRepository.saveAndFlush(inactiveAdmin);

    User suspendedAdmin =
        userRepository.saveAndFlush(Accounts.suspendedAdmin("sub-sa", "sa@khu.ac.kr", "20200013"));

    for (User target : List.of(pendingAdmin, inactiveAdmin, suspendedAdmin)) {
      Status before = target.getStatus();
      mockMvc
          .perform(change(target.getId(), "SUSPENDED"))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("FORBIDDEN"))
          .andExpect(jsonPath("$.message").value(AdminSuspensionPolicy.MESSAGE));

      User unchanged = userRepository.findById(target.getId()).orElseThrow();
      assertThat(unchanged.getRole()).isEqualTo(Role.ADMIN);
      assertThat(unchanged.getStatus()).isEqualTo(before);
      assertThat(actions.findByTargetIdOrderByIdAsc(target.getId())).isEmpty();
    }
  }

  /** 기존 SUSPENDED ADMIN은 자동 보정하지 않으며, 해제하여 복구하는 기존 길도 유지한다. */
  @Test
  void aSuspendedAdminCanStillBeReactivated() throws Exception {
    User target =
        userRepository.saveAndFlush(Accounts.suspendedAdmin("sub-ar", "ar@khu.ac.kr", "20200014"));

    mockMvc
        .perform(change(target.getId(), "ACTIVE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("ADMIN"))
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  /**
   * <b>자기 정지만 막는 것으로는 부족하다.</b>
   *
   * <p>둘이 서로를 정지하면 두 요청 모두 "남을 정지시키는 것"이라 자기 검사에 걸리지 않는다. 잠근 집합을 세지 않으면 각자 다른 행만 잠근 채 커밋해 <b>0명이
   * 된다.</b>
   */
  @Test
  void concurrentAdminSuspensionsOfEachOtherAreBothRejected() throws Exception {
    User other = userRepository.saveAndFlush(Accounts.admin("sub-ad5", "a5@khu.ac.kr", "20200005"));

    CyclicBarrier ready = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      // 서로를 정지시킨다 — 요청자와 대상이 엇갈린다.
      List<Future<Boolean>> results =
          pool.invokeAll(
              List.of(
                  suspend(admin.getId(), other.getId(), ready),
                  suspend(other.getId(), admin.getId(), ready)));

      assertThat(results.stream().filter(AdminUserStatusIntegrationTest::succeeded).count())
          .isZero();
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    assertThat(statusOf(admin)).isEqualTo(Status.ACTIVE);
    assertThat(statusOf(other)).isEqualTo(Status.ACTIVE);
    assertThat(actions.findAll()).isEmpty();
  }

  /** 마지막 관리자 여부나 자기/타인 여부보다 직접 정지 정책이 우선한다. */
  @Test
  void theSamePolicyAppliesToTheLastAndNonLastAdmin() throws Exception {
    User other = userRepository.saveAndFlush(Accounts.admin("sub-ad7", "a7@khu.ac.kr", "20200007"));
    suspendDirectly(other);

    assertThatThrownBy(() -> statusService.change(admin.getId(), admin.getId(), Target.SUSPENDED))
        .isInstanceOf(BusinessException.class)
        .hasMessage(AdminSuspensionPolicy.MESSAGE);
    assertThatThrownBy(() -> statusService.change(admin.getId(), other.getId(), Target.SUSPENDED))
        .isInstanceOf(BusinessException.class)
        .hasMessage(AdminSuspensionPolicy.MESSAGE);
  }

  /* --------------------------------------------------------- 요청자 재검증 */

  /**
   * <b>인가는 세션 값으로 이루어진다.</b> 요청이 인증을 통과한 뒤 다른 관리자가 이 사람을 정지하면, 그 대기 중인 요청은 <b>정지된 관리자의 쓰기</b>가 된다.
   *
   * <p>필터는 이미 지나갔으므로 여기서 다시 확인하지 않으면 그대로 커밋된다.
   */
  @Test
  void suspendedRequesterCannotFinishAPendingWrite() {
    User other = userRepository.saveAndFlush(Accounts.admin("sub-ad6", "a6@khu.ac.kr", "20200006"));
    suspendDirectly(other);

    assertThatThrownBy(() -> statusService.change(other.getId(), admin.getId(), Target.SUSPENDED))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.SUSPENDED.getMessage())
        .satisfies(
            e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.SUSPENDED));

    assertThat(statusOf(admin)).isEqualTo(Status.ACTIVE);
  }

  /** 권한이 회수된 경우도 같다. 사유는 상태가 아니라 권한이므로 코드가 다르다 (§3-2-7). */
  @Test
  void demotedRequesterCannotFinishAPendingWrite() {
    assertThatThrownBy(() -> statusService.change(member.getId(), admin.getId(), Target.SUSPENDED))
        .isInstanceOf(BusinessException.class)
        .hasMessage(ErrorCode.FORBIDDEN.getMessage())
        .satisfies(
            e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

    assertThat(statusOf(admin)).isEqualTo(Status.ACTIVE);
  }

  private void suspendDirectly(User user) {
    User found = userRepository.findById(user.getId()).orElseThrow();
    found.suspend();
    userRepository.saveAndFlush(found);
  }

  /**
   * T-15 — 활성 관리자 둘이 <b>동시에</b> 각자 자기 자신을 정지한다.
   *
   * <p>세는 것과 바꾸는 것이 한 연산이 아니면 둘 다 "관리자 2명"을 보고 통과해 <b>0명이 된다.</b> 최소 한쪽은 실패해야 한다 (§2-2-7 MUST).
   */
  @Test
  void concurrentSelfSuspensionsAreBothRejected() throws Exception {
    User other = userRepository.saveAndFlush(Accounts.admin("sub-ad4", "a4@khu.ac.kr", "20200004"));

    CyclicBarrier ready = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Boolean>> results =
          pool.invokeAll(
              List.of(
                  suspend(admin.getId(), admin.getId(), ready),
                  suspend(other.getId(), other.getId(), ready)));

      long succeeded = results.stream().filter(AdminUserStatusIntegrationTest::succeeded).count();
      assertThat(succeeded).isZero();
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    assertThat(statusOf(admin)).isEqualTo(Status.ACTIVE);
    assertThat(statusOf(other)).isEqualTo(Status.ACTIVE);
    assertThat(actions.findAll()).isEmpty();
  }

  private Callable<Boolean> suspend(Long requesterId, Long targetId, CyclicBarrier ready) {
    return () -> {
      ready.await(10, TimeUnit.SECONDS);
      statusService.change(requesterId, targetId, Target.SUSPENDED);
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

  /* ---------------------------------------------------------------- 권한 */

  @Test
  void memberCannotChangeStatus() throws Exception {
    mockMvc
        .perform(change(member, suspended.getId(), "{\"status\":\"ACTIVE\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertThat(statusOf(suspended)).isEqualTo(Status.SUSPENDED);
  }

  /** T-148의 형태 — 권한 검사가 본문보다 먼저다. 깨진 본문에 가려 400이 나가면 안 된다. */
  @Test
  void memberWithABrokenBodyStillGetsForbidden() throws Exception {
    mockMvc
        .perform(change(member, suspended.getId(), "{\"status\":"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  /** CSRF 토큰이 없으면 다른 사이트가 관리자 대신 회원을 정지시킨다 (5-TESTING §5-1 MUST). */
  @Test
  void changeWithoutCsrfTokenIsRejected() throws Exception {
    mockMvc
        .perform(
            sessions
                .as(admin, patch(BASE + member.getId() + "/status"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"SUSPENDED\"}"))
        .andExpect(status().isForbidden());

    assertThat(statusOf(member)).isEqualTo(Status.ACTIVE);
  }

  @Test
  void anonymousIsUnauthenticated() throws Exception {
    mockMvc
        .perform(
            Csrf.with(patch(BASE + member.getId() + "/status"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"SUSPENDED\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }
}
