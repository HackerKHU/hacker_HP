package org.hackerkhu.hackerhp.domain.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.audit.entity.AdminAction;
import org.hackerkhu.hackerhp.domain.audit.entity.AdminActionLog;
import org.hackerkhu.hackerhp.domain.audit.repository.AdminActionLogRepository;
import org.hackerkhu.hackerhp.domain.audit.service.AdminActionRecorder;
import org.hackerkhu.hackerhp.domain.user.dto.StatusChangeRequest.Target;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.domain.user.service.AdminUserApprovalService;
import org.hackerkhu.hackerhp.domain.user.service.AdminUserStatusService;
import org.hackerkhu.testsupport.user.Accounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 조작 이력 (#143, spec 2-2 §2-2-7).
 *
 * <p><b>"누가 누구를 언제 정지했나"에 답할 수 있어야 한다.</b> 그동안은 {@code log.info}로만 남았고 그 로그는 보존 기간이 지나면 사라진다.
 */
@SpringBootTest
class AdminActionLogIntegrationTest extends AbstractIntegrationTest {

  @Autowired private AdminUserApprovalService approvalService;
  @Autowired private AdminUserStatusService statusService;
  @Autowired private AdminActionRecorder recorder;
  @Autowired private AdminActionLogRepository logs;
  @Autowired private UserRepository userRepository;

  private TransactionTemplate transaction;
  private User admin;

  @Autowired
  void buildTransactionTemplate(PlatformTransactionManager transactionManager) {
    this.transaction = new TransactionTemplate(transactionManager);
  }

  @BeforeEach
  void createAdmin() {
    logs.deleteAll();
    userRepository.deleteAll();
    admin = userRepository.saveAndFlush(Accounts.admin("sub-admin", "admin@khu.ac.kr", "20200000"));
  }

  @AfterEach
  void clear() {
    logs.deleteAll();
    userRepository.deleteAll();
  }

  private User applied(String suffix) {
    return userRepository.saveAndFlush(
        Accounts.applied("sub-" + suffix, suffix + "@khu.ac.kr", "2025" + suffix));
  }

  private User approved(String suffix) {
    return userRepository.saveAndFlush(
        Accounts.approved("sub-" + suffix, suffix + "@khu.ac.kr", "2025" + suffix));
  }

  private List<AdminActionLog> historyOf(User user) {
    return logs.findByTargetIdOrderByIdAsc(user.getId());
  }

  /* ---------------------------------------------------------------- 남는다 */

  /** 일괄 승인은 <b>대상마다 한 행</b>이다. 뭉치면 "이 사람이 언제 승인됐나"를 물을 수 없다. */
  @Test
  void recordsOneRowPerApprovedMember() {
    User first = applied("0001");
    User second = applied("0002");

    approvalService.approve(admin.getId(), List.of(first.getId(), second.getId()));

    assertThat(logs.findAll()).hasSize(2);
    assertThat(historyOf(first))
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.getActorId()).isEqualTo(admin.getId());
              assertThat(entry.getTargetId()).isEqualTo(first.getId());
              assertThat(entry.getAction()).isEqualTo(AdminAction.APPROVE);
              assertThat(entry.getCreatedAt()).isNotNull();
            });
    assertThat(historyOf(second)).singleElement();
  }

  /** 실패한 건은 남기지 않는다. 아무것도 바뀌지 않았다. */
  @Test
  void doesNotRecordFailedApprovals() {
    // 신청서를 내지 않은 계정이라 승인 대상이 아니다 (T-49).
    User notApplied = userRepository.saveAndFlush(Accounts.signedIn("sub-none", "none@khu.ac.kr"));
    User ok = applied("0003");

    approvalService.approve(admin.getId(), List.of(notApplied.getId(), ok.getId()));

    assertThat(historyOf(ok)).hasSize(1);
    assertThat(historyOf(notApplied)).isEmpty();
  }

  /** 정지와 해제가 <b>서로 다른 동작으로</b> 남는다. 둘을 뭉치면 이력을 읽어도 방향을 모른다. */
  @Test
  void recordsSuspensionAndReactivationSeparately() {
    User member = approved("0004");

    statusService.change(admin.getId(), member.getId(), Target.SUSPENDED);
    statusService.change(admin.getId(), member.getId(), Target.ACTIVE);

    List<AdminActionLog> history = historyOf(member);
    assertThat(history)
        .extracting(AdminActionLog::getAction)
        .containsExactly(AdminAction.SUSPEND, AdminAction.ACTIVATE);
    /*
     * 시각이 조작 순서를 따른다. 이력의 목적이 "언제"라서, 순서가 뒤집히면 남긴 의미가 없다.
     * 시각은 계정 행을 잠근 채 잡는다 — 기록 시점에 잡으면 커밋과 세션 반영이 끝난 뒤라
     * 잇따른 조작끼리 어긋날 수 있다 (#143 리뷰).
     */
    assertThat(history.get(0).getCreatedAt()).isBeforeOrEqualTo(history.get(1).getCreatedAt());
  }

  /**
   * <b>이미 그 상태였던 재요청은 남기지 않는다.</b>
   *
   * <p>재요청은 세션 갱신 실패의 복구 수단이라 허용되는데(§3-2-6), 그때마다 한 줄이 더 생기면 <b>이력을 읽는 사람이 두 번 정지된 것으로 읽는다.</b>
   */
  @Test
  void doesNotRecordARepeatThatChangedNothing() {
    User member = approved("0005");

    statusService.change(admin.getId(), member.getId(), Target.SUSPENDED);
    statusService.change(admin.getId(), member.getId(), Target.SUSPENDED);

    assertThat(historyOf(member))
        .extracting(AdminActionLog::getAction)
        .containsExactly(AdminAction.SUSPEND);
    assertThat(userRepository.findById(member.getId()).orElseThrow().getStatus())
        .isEqualTo(Status.SUSPENDED);
  }

  /* ------------------------------------------------------------ 조건이 아니다 */

  /**
   * <b>변경 트랜잭션 안에서 부르면 끊는다.</b>
   *
   * <p>그 안에서 부르면 기록 실패가 조작을 되돌린다 — 완료 조건과 정반대다. 조용히 어긋나는 종류라 여기서 막는다.
   */
  @Test
  void refusesToRecordInsideATransaction() {
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored ->
                        recorder.record(
                            admin.getId(), admin.getId(), AdminAction.SUSPEND, Instant.now())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("커밋된 뒤");
  }

  /** 대상이 없으면 아무것도 남기지 않는다. 한 명도 승인되지 않은 요청이 그렇다. */
  @Test
  void recordsNothingWithoutTargets() {
    recorder.record(admin.getId(), List.of(), AdminAction.APPROVE, Instant.now());

    assertThat(logs.findAll()).isEmpty();
  }
}
