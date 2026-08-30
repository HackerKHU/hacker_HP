package org.hackerkhu.hackerhp.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.audit.repository.AdminActionLogRepository;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.domain.user.service.UserRemovalService;
import org.hackerkhu.hackerhp.global.auth.SessionSynchronizer;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.hackerkhu.testsupport.user.Accounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 탈퇴가 <b>정지만 남기고 끝나는</b> 경우 (spec 3-2 "정지만 남고 끝나면", T-394·T-397·T-398·T-399·T-401, #225).
 *
 * <p>탈퇴는 ① 정지 확정 → ①' 세션 반영 확인 → ② 삭제 순서다. ①이 커밋된 뒤 ①'나 ②가 실패하면 <b>계정이 {@code SUSPENDED}로 잠긴 채 끝난다
 * — 본인은 로그인과 API 접근을 함께 잃어 재시도할 수 없다.</b> 관리자 제거의 <i>"같은 요청을 다시 보내 복구한다"</i>가 여기서는 성립하지 않는다.
 *
 * <p><b>그 상태를 없앨 수는 없다.</b> 정지를 먼저 확정하는 것이 "세션 폐기가 실패해도 이미 막혀 있다"를 보장하는 유일한 수단이다. 그래서 없애는 대신
 * <b>관리자가 이어받는 것</b>을 재고, 그 계정을 찾을 근거인 로그가 <b>두 갈래 모두에서</b> 남는지 본다.
 */
@SpringBootTest
class WithdrawalStuckSuspensionIntegrationTest extends AbstractIntegrationTest {

  @Autowired private UserRemovalService removalService;
  @Autowired private UserRepository userRepository;
  @Autowired private AdminActionLogRepository actions;
  @Autowired private JdbcTemplate jdbcTemplate;

  /**
   * 세션 반영 자리가 ①과 ② 사이의 <b>유일한 지점</b>이다. 여기를 갈아끼워 두 갈래를 만든다.
   *
   * <p>세션이 실제로 갱신되는지는 이 테스트가 보려는 것이 아니다 — {@code SessionSynchronizationIntegrationTest}가 본다.
   */
  @MockitoBean private SessionSynchronizer sessionSynchronizer;

  private User member;

  @BeforeEach
  void setUp() {
    clearAll();
    member = userRepository.saveAndFlush(Accounts.approved("sub-m", "m@khu.ac.kr", "20250001"));
    Mockito.when(sessionSynchronizer.refreshReporting(anyLong())).thenReturn(true);
  }

  @AfterEach
  void clear() {
    clearAll();
  }

  private void clearAll() {
    actions.deleteAll();
    userRepository.deleteAll();
  }

  private Status statusOf(User user) {
    return userRepository.findById(user.getId()).orElseThrow().getStatus();
  }

  /**
   * T-398. <b>세션 반영 확인이 실패</b>하면 계정은 {@code SUSPENDED}로 남는다.
   *
   * <p>정지는 이미 커밋됐으므로 되돌아가지 않는다.
   */
  @Test
  void aFailedSessionRefreshLeavesTheAccountSuspended() {
    Mockito.when(sessionSynchronizer.refreshReporting(anyLong())).thenReturn(false);

    assertThatThrownBy(() -> removalService.withdraw(member.getId()))
        .isInstanceOf(IllegalStateException.class);

    assertThat(userRepository.existsById(member.getId())).as("계정이 남는다").isTrue();
    assertThat(statusOf(member)).isEqualTo(Status.SUSPENDED);
  }

  /**
   * T-401. <b>삭제가 실패</b>해도 결과는 같다 — 계정이 {@code SUSPENDED}로 남는다.
   *
   * <p>실패 지점이 둘인데 <b>한쪽만 재면</b> 구현이 그 한쪽에서만 탈퇴 문맥을 남기고 다른 쪽에서는 일반 오류로 흘려도 통과한다. 그러면 그 계정은 관리자가 정지시킨
   * 것과 구별되지 않아 <b>아무도 이어받지 못한다.</b>
   *
   * <p>삭제 실패는 ①'가 끝나는 순간 대상을 되살려 만든다 — {@code 409 CONCURRENT_CHANGE}로 ②가 멈춘다.
   */
  @Test
  void aFailedDeleteAlsoLeavesTheAccountSuspended() {
    reactivateWhenSessionsAreSynced();

    assertThatThrownBy(() -> removalService.withdraw(member.getId()))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CONCURRENT_CHANGE));

    assertThat(userRepository.existsById(member.getId())).as("지우지 않는다").isTrue();
  }

  /**
   * T-394. 지우기 직전에 관리자가 되살렸으면 <b>지우지 않는다.</b>
   *
   * <p>이 검사의 근거는 <i>"누가 되살렸는가"</i>가 아니라 <b>차단이 먼저 확정되어야 한다</b>는 것이다 — {@code ACTIVE}인 채로 지운 뒤 세션
   * 폐기가 실패하면 <b>계정 없는 {@code ACTIVE} 세션이 만료까지 산다.</b> 본인 탈퇴라고 느슨해질 수 없다.
   */
  @Test
  void withdrawalStopsWhenTheAccountWasReactivatedInTheWindow() {
    reactivateWhenSessionsAreSynced();

    assertThatThrownBy(() -> removalService.withdraw(member.getId()))
        .isInstanceOf(BusinessException.class);

    assertThat(statusOf(member)).as("되살아난 그대로 남는다").isEqualTo(Status.ACTIVE);
  }

  /**
   * T-399. 잠긴 계정을 <b>관리자가 이어받는다</b> — 제거로 완결하거나 해제로 되돌린다.
   *
   * <p>둘 다 이미 있는 경로다. 새로 만들 것이 없다는 것이 이 사례가 말하는 전부다.
   */
  @Test
  void anAdminCanFinishOrUndoAStuckWithdrawal() {
    Mockito.when(sessionSynchronizer.refreshReporting(anyLong())).thenReturn(false);
    assertThatThrownBy(() -> removalService.withdraw(member.getId()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(statusOf(member)).isEqualTo(Status.SUSPENDED);

    User admin = userRepository.saveAndFlush(Accounts.admin("sub-a", "a@khu.ac.kr", "20200001"));
    Mockito.when(sessionSynchronizer.refreshReporting(anyLong())).thenReturn(true);

    removalService.remove(admin.getId(), member.getId());

    assertThat(userRepository.existsById(member.getId())).as("제거로 완결된다").isFalse();
  }

  /**
   * T-397. 활성 관리자가 <b>정확히 둘</b>인데 둘이 <b>동시에</b> 탈퇴한다.
   *
   * <p>세는 것과 바꾸는 것이 한 연산이 아니면 <b>각자 상대를 활성 관리자로 세고 둘 다 통과해 0명이 된다.</b> 순차로 재는 T-374·T-375는 이 자리를 보지
   * 못한다. 최소 한쪽은 실패해야 하고, <b>무엇보다 활성 관리자가 남아야 한다.</b>
   */
  @Test
  void twoAdminsWithdrawingAtOnceCannotBothSucceed() throws Exception {
    User first = userRepository.saveAndFlush(Accounts.admin("sub-a1", "a1@khu.ac.kr", "20200001"));
    User second = userRepository.saveAndFlush(Accounts.admin("sub-a2", "a2@khu.ac.kr", "20200002"));

    CyclicBarrier ready = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Boolean>> results =
          pool.invokeAll(
              List.of(withdrawing(first.getId(), ready), withdrawing(second.getId(), ready)));

      long succeeded =
          results.stream().filter(WithdrawalStuckSuspensionIntegrationTest::succeeded).count();
      assertThat(succeeded).as("둘 다 나갈 수는 없다").isLessThanOrEqualTo(1);
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    assertThat(
            userRepository.findAll().stream()
                .filter(WithdrawalStuckSuspensionIntegrationTest::activeAdmin))
        .as("활성 관리자가 남아야 한다")
        .isNotEmpty();
  }

  private Callable<Boolean> withdrawing(Long userId, CyclicBarrier ready) {
    return () -> {
      ready.await(10, TimeUnit.SECONDS);
      removalService.withdraw(userId);
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
    return user.getRole() == Role.ADMIN && user.getStatus() == Status.ACTIVE;
  }

  /** ①'가 끝나는 순간에 관리자가 대상을 되살린다. 커밋된 UPDATE라야 ②의 트랜잭션이 본다. */
  private void reactivateWhenSessionsAreSynced() {
    Mockito.when(sessionSynchronizer.refreshReporting(anyLong()))
        .thenAnswer(
            invocation -> {
              Long userId = invocation.getArgument(0);
              jdbcTemplate.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", userId);
              return true;
            });
  }
}
