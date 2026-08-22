package org.hackerkhu.hackerhp.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;

import java.util.List;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.audit.entity.AdminAction;
import org.hackerkhu.hackerhp.domain.audit.entity.AdminActionLog;
import org.hackerkhu.hackerhp.domain.audit.repository.AdminActionLogRepository;
import org.hackerkhu.hackerhp.domain.user.dto.StatusChangeRequest.Target;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.domain.user.service.AdminUserRoleService;
import org.hackerkhu.hackerhp.domain.user.service.AdminUserStatusService;
import org.hackerkhu.hackerhp.global.auth.SessionSynchronizer;
import org.hackerkhu.testsupport.user.Accounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * <b>차단이 강해지는 변경은 세션에 닿아야 성공이다</b> (spec 2-2 §2-2-3 §2-2-5 MUST, #197 리뷰 3차).
 *
 * <p>정지와 권한 회수는 "즉시 차단"을 약속한다. 그런데 세션 반영은 <b>실패를 삼키는 경로다</b> — 이미 커밋된 변경까지 실패한 것처럼 보이면 안 되기 때문이다. 그
 * 관용을 그대로 두면 세션 저장소 장애에 <b>DB만 바뀐 채 {@code 200}이 나가고</b>, 그 사람은 만료(30분)까지 계속 쓴다. 관리자는 차단된 줄 안다.
 *
 * <p>반대 방향(해제·권한 부여)은 실패해도 <b>그 사람이 아직 못 쓰는 것뿐</b>이라 알리지 않는다. 실패로 알리면 되레 관리자를 헷갈리게 한다.
 *
 * <p>세션 저장소 장애를 실제로 만들 수 없으므로 <b>반영 결과만 갈아끼운다.</b> 세션이 실제로 갱신되는지는 {@code
 * AdminRoleChangeSessionIntegrationTest}·{@code SessionSynchronizationIntegrationTest}가 본다.
 */
@SpringBootTest
class AdminBlockingChangeReportsSyncFailureTest extends AbstractIntegrationTest {

  @Autowired private AdminUserStatusService statusService;
  @Autowired private AdminUserRoleService roleService;
  @Autowired private UserRepository userRepository;
  @Autowired private AdminActionLogRepository actions;

  @MockitoBean private SessionSynchronizer sessionSynchronizer;

  private User admin;
  private User member;

  @BeforeEach
  void setUp() {
    clearAll();
    admin = userRepository.saveAndFlush(Accounts.admin("sub-ad", "ad@khu.ac.kr", "20200000"));
    // 혼자면 마지막 활성 관리자라 자기 회수가 §2-2-7에 걸린다.
    userRepository.saveAndFlush(Accounts.admin("sub-ad2", "ad2@khu.ac.kr", "20200001"));
    member = userRepository.saveAndFlush(Accounts.approved("sub-m", "m@khu.ac.kr", "20250002"));
  }

  @AfterEach
  void clear() {
    clearAll();
  }

  private void clearAll() {
    actions.deleteAll();
    userRepository.deleteAll();
  }

  private void sessionSyncFails() {
    Mockito.when(sessionSynchronizer.refreshReporting(anyLong())).thenReturn(false);
  }

  private void sessionSyncSucceeds() {
    Mockito.when(sessionSynchronizer.refreshReporting(anyLong())).thenReturn(true);
  }

  private List<AdminAction> historyOf(User user) {
    return actions.findByTargetIdOrderByIdAsc(user.getId()).stream()
        .map(AdminActionLog::getAction)
        .toList();
  }

  private User reload(User user) {
    return userRepository.findById(user.getId()).orElseThrow();
  }

  /** T-273 — 정지가 세션에 닿지 않으면 성공으로 답하지 않는다. */
  @Test
  void suspendFailsLoudlyWhenTheSessionDidNotFollow() {
    sessionSyncFails();

    assertThatThrownBy(() -> statusService.change(admin.getId(), member.getId(), Target.SUSPENDED))
        .isInstanceOf(IllegalStateException.class);

    // 변경은 이미 커밋됐다. 되돌리지 않는다 — 재요청이 복구 수단이다.
    assertThat(reload(member).getStatus()).isEqualTo(Status.SUSPENDED);
    // 이력을 남긴 뒤에 던진다. 여기서 곧장 빠져나가면 "누가 무엇을 했는지"만 사라진다.
    assertThat(historyOf(member)).containsExactly(AdminAction.SUSPEND);
  }

  /** T-274 — 권한 회수도 같다. 회수됐다고 답해 놓고 그 사람이 관리자로 남으면 안 된다. */
  @Test
  void revokeFailsLoudlyWhenTheSessionDidNotFollow() {
    sessionSyncSucceeds();
    roleService.change(admin.getId(), member.getId(), Role.ADMIN);

    sessionSyncFails();
    assertThatThrownBy(() -> roleService.change(admin.getId(), member.getId(), Role.USER))
        .isInstanceOf(IllegalStateException.class);

    assertThat(reload(member).getRole()).isEqualTo(Role.USER);
    assertThat(historyOf(member))
        .containsExactly(AdminAction.GRANT_ADMIN, AdminAction.REVOKE_ADMIN);
  }

  /**
   * 재요청이 복구 수단이다.
   *
   * <p><b>값이 이미 목표와 같아도 반영을 건너뛰지 않아야</b> 성립한다 — 건너뛰면 한 번 실패한 정지는 영영 세션에 닿지 못한다.
   */
  @Test
  void retryingTheSameRequestRecoversTheSession() {
    sessionSyncFails();
    assertThatThrownBy(() -> statusService.change(admin.getId(), member.getId(), Target.SUSPENDED))
        .isInstanceOf(IllegalStateException.class);

    sessionSyncSucceeds();
    assertThatCode(() -> statusService.change(admin.getId(), member.getId(), Target.SUSPENDED))
        .doesNotThrowAnyException();

    // 아무것도 바뀌지 않은 재요청이라 이력은 늘지 않는다 (#143).
    assertThat(historyOf(member)).containsExactly(AdminAction.SUSPEND);
  }

  /**
   * <b>완화되는 변경은 조용히 넘어간다.</b>
   *
   * <p>이 테스트가 위의 것들을 지킨다. 없으면 "무조건 확인한다"로 고쳐도 위가 전부 통과한다.
   */
  @Test
  void liftingARestrictionDoesNotFailEvenIfTheSessionDidNotFollow() {
    sessionSyncSucceeds();
    statusService.change(admin.getId(), member.getId(), Target.SUSPENDED);

    sessionSyncFails();
    assertThatCode(() -> statusService.change(admin.getId(), member.getId(), Target.ACTIVE))
        .doesNotThrowAnyException();
    assertThatCode(() -> roleService.change(admin.getId(), member.getId(), Role.ADMIN))
        .doesNotThrowAnyException();
  }
}
