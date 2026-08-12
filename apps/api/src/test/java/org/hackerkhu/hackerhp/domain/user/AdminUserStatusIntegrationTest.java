package org.hackerkhu.hackerhp.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.user.dto.StatusChangeRequest.Target;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.domain.user.service.AdminUserStatusService;
import org.hackerkhu.hackerhp.global.auth.AuthSession;
import org.hackerkhu.hackerhp.global.auth.JwtProvider;
import org.hackerkhu.testsupport.session.InMemorySessionConfig;
import org.hackerkhu.testsupport.web.Csrf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code PATCH /admin/users/{id}/status} (spec 2-2 §2-2-3, §2-2-7).
 *
 * <p>정지가 <b>기존 세션까지</b> 닿는지(T-32)는 실제 세션 저장소가 필요해 {@code SessionSynchronizationIntegrationTest}가
 * 본다. 여기서는 전이 규칙과 안전장치를 확인한다.
 */
@SpringBootTest(
    properties =
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.session.SessionAutoConfiguration")
@AutoConfigureMockMvc
@Import(InMemorySessionConfig.class)
class AdminUserStatusIntegrationTest extends AbstractIntegrationTest {

  private static final String BASE = "/api/v1/admin/users/";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private JwtProvider jwtProvider;
  @Autowired private AdminUserStatusService statusService;

  private User admin;
  private User member;
  private User suspended;
  private User applicant;

  @BeforeEach
  void createAccounts() {
    userRepository.deleteAll();

    admin =
        userRepository.saveAndFlush(promoted(approved("sub-ad", "admin@khu.ac.kr", "20200001")));
    member = userRepository.saveAndFlush(approved("sub-us", "user@khu.ac.kr", "20240101"));

    User toSuspend = approved("sub-sp", "suspended@khu.ac.kr", "20240102");
    toSuspend.suspend();
    suspended = userRepository.saveAndFlush(toSuspend);

    User pending = User.createFromGoogle("sub-pd", "pending@khu.ac.kr", "신청자");
    pending.submitApplication("20240103", "신청자");
    applicant = userRepository.saveAndFlush(pending);
  }

  @AfterEach
  void clear() {
    userRepository.deleteAll();
  }

  private static User approved(String googleSub, String email, String studentNo) {
    User user = User.createFromGoogle(googleSub, email, "이름");
    user.submitApplication(studentNo, "본명");
    user.approve();
    return user;
  }

  private static User promoted(User user) {
    user.promoteToAdmin();
    return user;
  }

  private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder builder) {
    MockHttpSession session = new MockHttpSession();
    AuthSession.store(session, user);
    return builder
        .session(session)
        .cookie(new Cookie("ACCESS_TOKEN", jwtProvider.issue(user.getId())));
  }

  private MockHttpServletRequestBuilder change(User caller, Long targetId, String body) {
    return Csrf.with(as(caller, patch(BASE + targetId + "/status")))
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
        .andExpect(jsonPath("$.message").value("마지막 활성 관리자는 자기 자신을 정지할 수 없습니다."));

    assertThat(statusOf(admin)).isEqualTo(Status.ACTIVE);
  }

  /** 막아야 하는 것은 "마지막 1명이 사라지는 것"이지 "자기 자신을 건드리는 것"이 아니다 (§2-2-7). */
  @Test
  void adminCanSuspendThemselvesWhenAnotherAdminRemains() throws Exception {
    userRepository.saveAndFlush(promoted(approved("sub-ad2", "admin2@khu.ac.kr", "20200002")));

    mockMvc
        .perform(change(admin.getId(), "SUSPENDED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUSPENDED"));
  }

  /** 정지된 관리자는 세지 않는다 (MUST). 로그인할 수 없으므로 DB에 role만 남아 있어도 운영을 보장하지 못한다. */
  @Test
  void suspendedAdminDoesNotCountAsAGuard() throws Exception {
    User inactive = promoted(approved("sub-ad3", "admin3@khu.ac.kr", "20200003"));
    inactive.suspend();
    userRepository.saveAndFlush(inactive);

    mockMvc
        .perform(change(admin.getId(), "SUSPENDED"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  /**
   * T-15 — 활성 관리자 둘이 <b>동시에</b> 각자 자기 자신을 정지한다.
   *
   * <p>세는 것과 바꾸는 것이 한 연산이 아니면 둘 다 "관리자 2명"을 보고 통과해 <b>0명이 된다.</b> 최소 한쪽은 실패해야 한다 (§2-2-7 MUST).
   */
  @Test
  void twoAdminsSuspendingThemselvesAtOnceCannotBothSucceed() throws Exception {
    User other =
        userRepository.saveAndFlush(promoted(approved("sub-ad4", "a4@khu.ac.kr", "20200004")));

    CyclicBarrier ready = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Boolean>> results =
          pool.invokeAll(
              List.of(selfSuspend(admin.getId(), ready), selfSuspend(other.getId(), ready)));

      long succeeded = results.stream().filter(AdminUserStatusIntegrationTest::succeeded).count();
      assertThat(succeeded).isLessThanOrEqualTo(1);
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    // 무엇보다 중요한 것 — 활성 관리자가 남아 있어야 한다.
    assertThat(
            userRepository.findAll().stream().filter(AdminUserStatusIntegrationTest::activeAdmin))
        .isNotEmpty();
  }

  private Callable<Boolean> selfSuspend(Long adminId, CyclicBarrier ready) {
    return () -> {
      ready.await(10, TimeUnit.SECONDS);
      statusService.change(adminId, adminId, Target.SUSPENDED);
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

  private static boolean activeAdmin(User user) {
    return user.getRole() == org.hackerkhu.hackerhp.domain.user.entity.Role.ADMIN
        && user.getStatus() == Status.ACTIVE;
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
            as(admin, patch(BASE + member.getId() + "/status"))
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
