package org.hackerkhu.hackerhp.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.audit.entity.AdminAction;
import org.hackerkhu.hackerhp.domain.audit.entity.AdminActionLog;
import org.hackerkhu.hackerhp.domain.audit.repository.AdminActionLogRepository;
import org.hackerkhu.hackerhp.domain.audit.service.AdminActionRecorder;
import org.hackerkhu.hackerhp.domain.user.dto.BulkStatusChangeRequest.TargetStatus;
import org.hackerkhu.hackerhp.domain.user.dto.BulkStatusChangeResponse;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.domain.user.service.AdminSuspensionPolicy;
import org.hackerkhu.hackerhp.domain.user.service.AdminUserRoleService;
import org.hackerkhu.hackerhp.domain.user.service.BulkUserStatusService;
import org.hackerkhu.hackerhp.global.auth.SessionSynchronizer;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.hackerkhu.testsupport.auth.TestSessions.SignedIn;
import org.hackerkhu.testsupport.user.Accounts;
import org.hackerkhu.testsupport.web.Csrf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

/** {@code PATCH /api/v1/admin/users/status} (#313). */
@SpringBootTest
@AutoConfigureMockMvc
class BulkUserStatusIntegrationTest extends AbstractIntegrationTest {

  private static final String PATH = "/api/v1/admin/users/status";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private AdminActionLogRepository actions;
  @Autowired private AdminActionRecorder recorder;
  @Autowired private AdminSuspensionPolicy suspensionPolicy;
  @Autowired private AdminUserRoleService roleService;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private BulkUserStatusService service;

  private User admin;
  private User member;

  @BeforeEach
  void setUp() {
    actions.deleteAll();
    users.deleteAll();
    admin = users.saveAndFlush(Accounts.admin("bulk-admin", "bulk-admin@khu.ac.kr", "21000001"));
    member =
        users.saveAndFlush(Accounts.approved("bulk-member", "bulk-member@khu.ac.kr", "21000002"));
  }

  @AfterEach
  void clear() {
    actions.deleteAll();
    users.deleteAll();
  }

  private MockHttpServletRequestBuilder requestAs(User caller, String body) {
    return Csrf.with(sessions.as(caller, patch(PATH)))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private MockHttpServletRequestBuilder request(String body) {
    return requestAs(admin, body);
  }

  private static String body(String status, Long... ids) {
    return "{\"userIds\":["
        + String.join(",", Arrays.stream(ids).map(String::valueOf).toList())
        + "],\"status\":\""
        + status
        + "\"}";
  }

  private Status statusOf(User user) {
    return users.findById(user.getId()).orElseThrow().getStatus();
  }

  /* -------------------------------------------------------------- 요청 계약 */

  @Test
  void missingBodyIsRejectedWithoutChangingAnything() throws Exception {
    mockMvc
        .perform(Csrf.with(sessions.as(admin, patch(PATH))).contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    assertThat(statusOf(member)).isEqualTo(Status.ACTIVE);
  }

  @Test
  void topLevelNullIsRejectedWithoutChangingAnything() throws Exception {
    mockMvc
        .perform(request("null"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    assertThat(statusOf(member)).isEqualTo(Status.ACTIVE);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"userIds\":null,\"status\":\"ACTIVE\"}",
        "{\"userIds\":[],\"status\":\"ACTIVE\"}",
        "{\"userIds\":[null],\"status\":\"ACTIVE\"}",
        "{\"userIds\":[0],\"status\":\"ACTIVE\"}",
        "{\"userIds\":[-1],\"status\":\"ACTIVE\"}",
        "{\"userIds\":[1]}",
        "{\"userIds\":[1],\"status\":null}",
        "{\"userIds\":[1],\"status\":\"INACTIVE\"}"
      })
  void invalidBodiesAreRejected(String invalid) throws Exception {
    mockMvc
        .perform(request(invalid))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    assertThat(statusOf(member)).isEqualTo(Status.ACTIVE);
  }

  @Test
  void rawHundredIsAllowedBeforeDeduplication() throws Exception {
    Long[] repeated = new Long[100];
    Arrays.fill(repeated, member.getId());

    mockMvc
        .perform(request(body("ACTIVE", repeated)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.processed.length()").value(1))
        .andExpect(jsonPath("$.processed[0]").value(member.getId()));
  }

  @Test
  void rawHundredAndOneIsRejectedEvenWhenEveryIdIsTheSame() throws Exception {
    Long[] repeated = new Long[101];
    Arrays.fill(repeated, member.getId());

    mockMvc
        .perform(request(body("SUSPENDED", repeated)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    assertThat(statusOf(member)).isEqualTo(Status.ACTIVE);
  }

  /* -------------------------------------------------------------- ACTIVE 전이·이력 */

  @Test
  void activeBatchPreservesFirstAppearanceAndRecordsHeterogeneousTransitions() throws Exception {
    User applied = users.saveAndFlush(Accounts.applied("bulk-a", "bulk-a@khu.ac.kr", "21000003"));
    User notApplied = users.saveAndFlush(Accounts.signedIn("bulk-n", "bulk-n@khu.ac.kr"));
    User inactive = users.saveAndFlush(Accounts.inactive("bulk-i", "bulk-i@khu.ac.kr", "21000004"));
    User suspended =
        users.saveAndFlush(Accounts.suspended("bulk-s", "bulk-s@khu.ac.kr", "21000005"));
    long missing = 999_999L;

    mockMvc
        .perform(
            request(
                body(
                    "ACTIVE",
                    inactive.getId(),
                    missing,
                    applied.getId(),
                    member.getId(),
                    notApplied.getId(),
                    inactive.getId(),
                    suspended.getId(),
                    missing)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetStatus").value("ACTIVE"))
        .andExpect(jsonPath("$.processed[0]").value(inactive.getId()))
        .andExpect(jsonPath("$.processed[1]").value(applied.getId()))
        .andExpect(jsonPath("$.processed[2]").value(member.getId()))
        .andExpect(jsonPath("$.processed[3]").value(suspended.getId()))
        .andExpect(jsonPath("$.failed[0].userId").value(missing))
        .andExpect(jsonPath("$.failed[0].reason").value("NOT_FOUND"))
        .andExpect(jsonPath("$.failed[1].userId").value(notApplied.getId()))
        .andExpect(jsonPath("$.failed[1].reason").value("NOT_APPLIED"));

    User approved = users.findById(applied.getId()).orElseThrow();
    assertThat(approved.getStatus()).isEqualTo(Status.ACTIVE);
    assertThat(approved.getApprovedAt()).isNotNull();
    assertThat(users.findById(inactive.getId()).orElseThrow().getDeactivatedAt()).isNull();
    assertThat(statusOf(suspended)).isEqualTo(Status.ACTIVE);
    assertThat(statusOf(notApplied)).isEqualTo(Status.PENDING);

    List<AdminActionLog> history =
        actions.findAll().stream().sorted(Comparator.comparing(AdminActionLog::getId)).toList();
    assertThat(history)
        .extracting(AdminActionLog::getTargetId)
        .containsExactlyElementsOf(
            List.of(inactive.getId(), applied.getId(), suspended.getId()).stream()
                .sorted()
                .toList());
    assertThat(history)
        .allSatisfy(
            entry -> assertThat(entry.getCreatedAt()).isEqualTo(history.get(0).getCreatedAt()));
    assertThat(history)
        .extracting(AdminActionLog::getAction)
        .containsExactlyInAnyOrder(
            AdminAction.REACTIVATE, AdminAction.APPROVE, AdminAction.ACTIVATE);
  }

  /** 과거 비정상 조합도 ACTIVE 목표에서는 상태 규칙으로 정상화한다. */
  @Test
  void inactiveAdminCanBeNormalizedToActive() throws Exception {
    User inactiveAdmin = Accounts.inactive("bulk-ia", "bulk-ia@khu.ac.kr", "21000006");
    inactiveAdmin.promoteToAdmin();
    inactiveAdmin = users.saveAndFlush(inactiveAdmin);

    mockMvc
        .perform(request(body("ACTIVE", inactiveAdmin.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.processed[0]").value(inactiveAdmin.getId()));

    assertThat(statusOf(inactiveAdmin)).isEqualTo(Status.ACTIVE);
    assertThat(actions.findByTargetIdOrderByIdAsc(inactiveAdmin.getId()))
        .extracting(AdminActionLog::getAction)
        .containsExactly(AdminAction.REACTIVATE);
  }

  /* ----------------------------------------------------------- SUSPENDED 전이 */

  @Test
  void suspendedBatchReturnsEveryPolicyFailureAndKeepsIdempotentSuccesses() throws Exception {
    User inactive =
        users.saveAndFlush(Accounts.inactive("bulk-i2", "bulk-i2@khu.ac.kr", "21000007"));
    User already =
        users.saveAndFlush(Accounts.suspended("bulk-s2", "bulk-s2@khu.ac.kr", "21000008"));
    User applied = users.saveAndFlush(Accounts.applied("bulk-p2", "bulk-p2@khu.ac.kr", "21000009"));
    User notApplied = users.saveAndFlush(Accounts.signedIn("bulk-p3", "bulk-p3@khu.ac.kr"));
    User otherAdmin =
        users.saveAndFlush(Accounts.admin("bulk-ad2", "bulk-ad2@khu.ac.kr", "21000010"));
    User suspendedAdmin =
        users.saveAndFlush(Accounts.suspendedAdmin("bulk-ad3", "bulk-ad3@khu.ac.kr", "21000011"));
    long missing = 888_888L;

    mockMvc
        .perform(
            request(
                body(
                    "SUSPENDED",
                    already.getId(),
                    otherAdmin.getId(),
                    member.getId(),
                    applied.getId(),
                    inactive.getId(),
                    notApplied.getId(),
                    suspendedAdmin.getId(),
                    missing)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.processed[0]").value(already.getId()))
        .andExpect(jsonPath("$.processed[1]").value(member.getId()))
        .andExpect(jsonPath("$.processed[2]").value(inactive.getId()))
        .andExpect(jsonPath("$.failed[0].reason").value("ADMIN_SUSPEND_REQUIRES_ROLE_REVOCATION"))
        .andExpect(jsonPath("$.failed[1].reason").value("PENDING_NOT_ALLOWED"))
        .andExpect(jsonPath("$.failed[2].reason").value("PENDING_NOT_ALLOWED"))
        .andExpect(jsonPath("$.failed[3].reason").value("ADMIN_SUSPEND_REQUIRES_ROLE_REVOCATION"))
        .andExpect(jsonPath("$.failed[4].reason").value("NOT_FOUND"));

    assertThat(statusOf(member)).isEqualTo(Status.SUSPENDED);
    assertThat(statusOf(inactive)).isEqualTo(Status.SUSPENDED);
    assertThat(users.findById(inactive.getId()).orElseThrow().getDeactivatedAt()).isNull();
    assertThat(statusOf(applied)).isEqualTo(Status.PENDING);
    assertThat(statusOf(otherAdmin)).isEqualTo(Status.ACTIVE);
    assertThat(actions.findAll())
        .extracting(AdminActionLog::getTargetId)
        .containsExactlyInAnyOrder(member.getId(), inactive.getId());
  }

  @Test
  void roleRevocationThenSeparateSuspensionSucceeds() throws Exception {
    User otherAdmin =
        users.saveAndFlush(Accounts.admin("bulk-ad4", "bulk-ad4@khu.ac.kr", "21000012"));

    mockMvc
        .perform(
            Csrf.with(
                    sessions.as(
                        admin, patch("/api/v1/admin/users/" + otherAdmin.getId() + "/role")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"USER\"}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(request(body("SUSPENDED", otherAdmin.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.processed[0]").value(otherAdmin.getId()));

    assertThat(actions.findByTargetIdOrderByIdAsc(otherAdmin.getId()))
        .extracting(AdminActionLog::getAction)
        .containsExactly(AdminAction.REVOKE_ADMIN, AdminAction.SUSPEND);
  }

  /* -------------------------------------------------------------- 세션 */

  @Test
  void activationRefreshesAPendingSessionAndSuspensionBlocksAnActiveSession() throws Exception {
    User applicant =
        users.saveAndFlush(Accounts.applied("bulk-live", "bulk-live@khu.ac.kr", "21000013"));
    SignedIn applicantSession = sessions.signIn(applicant);
    SignedIn memberSession = sessions.signIn(member);

    mockMvc.perform(request(body("ACTIVE", applicant.getId()))).andExpect(status().isOk());
    mockMvc.perform(applicantSession.on(get("/api/v1/notices"))).andExpect(status().isOk());

    mockMvc.perform(request(body("SUSPENDED", member.getId()))).andExpect(status().isOk());
    mockMvc
        .perform(memberSession.on(get("/api/v1/notices")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SUSPENDED"));
  }

  @Test
  void suspensionRefreshFailureKeepsStateAndHistoryAndRetryIsIdempotent() {
    SessionSynchronizer failing = Mockito.mock(SessionSynchronizer.class);
    Mockito.when(failing.refreshReporting(anyCollection())).thenReturn(false);
    BulkUserStatusService first = serviceWith(failing);

    assertThatThrownBy(
            () -> first.change(admin.getId(), List.of(member.getId()), TargetStatus.SUSPENDED))
        .isInstanceOf(IllegalStateException.class);
    assertThat(statusOf(member)).isEqualTo(Status.SUSPENDED);
    assertThat(actions.findByTargetIdOrderByIdAsc(member.getId()))
        .extracting(AdminActionLog::getAction)
        .containsExactly(AdminAction.SUSPEND);

    SessionSynchronizer recovered = Mockito.mock(SessionSynchronizer.class);
    Mockito.when(recovered.refreshReporting(anyCollection())).thenReturn(true);
    serviceWith(recovered).change(admin.getId(), List.of(member.getId()), TargetStatus.SUSPENDED);

    ArgumentCaptor<Collection<Long>> refreshed = ArgumentCaptor.forClass(Collection.class);
    Mockito.verify(recovered).refreshReporting(refreshed.capture());
    assertThat(refreshed.getValue()).containsExactly(member.getId());
    assertThat(actions.findByTargetIdOrderByIdAsc(member.getId()))
        .extracting(AdminActionLog::getAction)
        .containsExactly(AdminAction.SUSPEND);
  }

  @Test
  void activeBatchRefreshesEveryProcessedIdIncludingIdempotentOnes() {
    User inactive = users.saveAndFlush(Accounts.inactive("bulk-r", "bulk-r@khu.ac.kr", "21000014"));
    SessionSynchronizer recording = Mockito.mock(SessionSynchronizer.class);

    serviceWith(recording)
        .change(admin.getId(), List.of(member.getId(), inactive.getId()), TargetStatus.ACTIVE);

    ArgumentCaptor<Collection<Long>> refreshed = ArgumentCaptor.forClass(Collection.class);
    Mockito.verify(recording).refresh(refreshed.capture());
    assertThat(refreshed.getValue()).containsExactly(member.getId(), inactive.getId());
  }

  /* ---------------------------------------------------------- 동시성·요청자 */

  @Test
  void concurrentSuspensionBatchesCreateOneTransitionHistory() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return service.change(
                      admin.getId(), List.of(member.getId()), TargetStatus.SUSPENDED);
                }));
      }
      start.countDown();
      for (Future<?> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(statusOf(member)).isEqualTo(Status.SUSPENDED);
    assertThat(actions.findByTargetIdOrderByIdAsc(member.getId()))
        .extracting(AdminActionLog::getAction)
        .containsExactly(AdminAction.SUSPEND);
  }

  @Test
  void concurrentOppositeBatchesRecordOnlyTheTransitionsThatActuallyHappened() throws Exception {
    member.suspend();
    users.saveAndFlush(member);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<?> activate =
          pool.submit(
              () -> {
                start.await();
                return service.change(admin.getId(), List.of(member.getId()), TargetStatus.ACTIVE);
              });
      Future<?> suspend =
          pool.submit(
              () -> {
                start.await();
                return service.change(
                    admin.getId(), List.of(member.getId()), TargetStatus.SUSPENDED);
              });
      start.countDown();
      activate.get(10, TimeUnit.SECONDS);
      suspend.get(10, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    Status finalStatus = statusOf(member);
    List<AdminAction> history =
        actions.findByTargetIdOrderByIdAsc(member.getId()).stream()
            .map(AdminActionLog::getAction)
            .toList();
    if (finalStatus == Status.ACTIVE) {
      assertThat(history).containsExactly(AdminAction.ACTIVATE);
    } else {
      assertThat(finalStatus).isEqualTo(Status.SUSPENDED);
      assertThat(history)
          .containsExactlyInAnyOrder(AdminAction.ACTIVATE, AdminAction.SUSPEND)
          .hasSize(2);
    }
  }

  @Test
  void roleRevocationRaceUsesTheLatestLockedRole() throws Exception {
    User targetAdmin =
        users.saveAndFlush(Accounts.admin("bulk-race-ad", "bulk-race-ad@khu.ac.kr", "21000016"));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    BulkStatusChangeResponse bulk;
    try {
      Future<?> revoke =
          pool.submit(
              () -> {
                start.await();
                return roleService.change(admin.getId(), targetAdmin.getId(), Role.USER);
              });
      Future<BulkStatusChangeResponse> suspend =
          pool.submit(
              () -> {
                start.await();
                return service.change(
                    admin.getId(), List.of(targetAdmin.getId()), TargetStatus.SUSPENDED);
              });
      start.countDown();
      revoke.get(10, TimeUnit.SECONDS);
      bulk = suspend.get(10, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    User after = users.findById(targetAdmin.getId()).orElseThrow();
    assertThat(after.getRole()).isEqualTo(Role.USER);
    List<AdminActionLog> history = actions.findByTargetIdOrderByIdAsc(targetAdmin.getId());
    if (bulk.processed().contains(targetAdmin.getId())) {
      assertThat(after.getStatus()).isEqualTo(Status.SUSPENDED);
      assertThat(history)
          .extracting(AdminActionLog::getAction)
          .containsExactlyInAnyOrder(AdminAction.REVOKE_ADMIN, AdminAction.SUSPEND);
      assertThat(history).hasSize(2);
    } else {
      assertThat(after.getStatus()).isEqualTo(Status.ACTIVE);
      assertThat(bulk.failed())
          .singleElement()
          .satisfies(
              failure ->
                  assertThat(failure.reason())
                      .isEqualTo(
                          BulkStatusChangeResponse.Reason.ADMIN_SUSPEND_REQUIRES_ROLE_REVOCATION));
      assertThat(history)
          .extracting(AdminActionLog::getAction)
          .containsExactly(AdminAction.REVOKE_ADMIN);
    }
  }

  @Test
  void requesterIsRevalidatedBeforeAnyTargetIsChanged() {
    admin.demoteToUser();
    users.saveAndFlush(admin);

    assertThatThrownBy(
            () -> service.change(admin.getId(), List.of(member.getId()), TargetStatus.SUSPENDED))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    assertThat(statusOf(member)).isEqualTo(Status.ACTIVE);
    assertThat(actions.findAll()).isEmpty();
  }

  /* --------------------------------------------------------------- 보안 */

  @Test
  void unauthenticatedUserAndMissingCsrfAreRejected() throws Exception {
    mockMvc
        .perform(
            Csrf.with(patch(PATH))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("SUSPENDED", member.getId())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

    mockMvc
        .perform(
            sessions
                .as(admin, patch(PATH))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("SUSPENDED", member.getId())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    assertThat(statusOf(member)).isEqualTo(Status.ACTIVE);
  }

  @Test
  void userIsRejectedBeforeMalformedBodyIsParsed() throws Exception {
    User user =
        users.saveAndFlush(Accounts.approved("bulk-user", "bulk-user@khu.ac.kr", "21000015"));

    mockMvc
        .perform(requestAs(user, "{"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    assertThat(statusOf(member)).isEqualTo(Status.ACTIVE);
  }

  private BulkUserStatusService serviceWith(SessionSynchronizer synchronizer) {
    return new BulkUserStatusService(
        users, suspensionPolicy, synchronizer, recorder, transactionManager);
  }
}
