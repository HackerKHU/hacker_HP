package org.hackerkhu.hackerhp.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;

import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.domain.user.service.AdminUserRemovalService;
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
 * <b>정지를 확정한 뒤 지우기 전까지의 창</b> (spec 2-2 §2-2-4 MUST, #58, #197 리뷰 2차).
 *
 * <p>제거는 ① 정지 확정 → ①' 세션 반영 확인 → ② 삭제 순서다. ①은 <b>커밋되며 잠금을 놓으므로</b>, ②가 행을 잠글 때까지 다른 관리자가 {@code
 * PATCH .../status}로 대상을 다시 {@code ACTIVE}로 돌릴 수 있다. 그러면 그 사람의 세션도 {@code ACTIVE}로 되살아난 채 계정만 사라진다
 * — "정지가 먼저 확정된다"는 전제가 무너진다.
 *
 * <p><b>그 창을 실제 시간 경합으로 재현하지 않는다.</b> 두 스레드를 띄우면 언제 어긋나는지가 스케줄러에 달려 있어 조용히 통과하는 날이 생긴다. 대신 <b>끼어드는
 * 지점을 지목한다</b> — ①'가 끝나는 순간에 대상을 되살려 두고, ②가 그것을 보고 멈추는지 본다.
 */
@SpringBootTest
class AdminUserRemovalRaceIntegrationTest extends AbstractIntegrationTest {

  @Autowired private AdminUserRemovalService removalService;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  /**
   * 세션 반영 자리를 <b>끼어드는 지점</b>으로 쓴다.
   *
   * <p>여기를 고른 이유는 ①과 ② 사이에 있는 유일한 지점이기 때문이다. 세션 저장소를 실제로 건드리지 않는 것은 이 테스트가 보려는 것이 아니다 — 세션 쪽은
   * {@code AdminRoleChangeSessionIntegrationTest}가 본다.
   */
  @MockitoBean private SessionSynchronizer sessionSynchronizer;

  private User admin;
  private User target;

  @BeforeEach
  void setUp() {
    clearAll();
    admin = userRepository.saveAndFlush(Accounts.admin("sub-ad", "ad@khu.ac.kr", "20200000"));
    // 혼자면 마지막 활성 관리자라 ①이 먼저 막는다. 남을 사람을 하나 둔다.
    userRepository.saveAndFlush(Accounts.admin("sub-ad2", "ad2@khu.ac.kr", "20200001"));
    target = userRepository.saveAndFlush(Accounts.approved("sub-m", "m@khu.ac.kr", "20250002"));
  }

  @AfterEach
  void clear() {
    clearAll();
  }

  private void clearAll() {
    jdbcTemplate.update("DELETE FROM admin_actions");
    userRepository.deleteAll();
  }

  private Status statusOf(User user) {
    return userRepository.findById(user.getId()).orElseThrow().getStatus();
  }

  /** ①'가 끝나는 순간에 다른 관리자가 대상을 되살린다. 커밋된 UPDATE라야 ②의 트랜잭션이 본다. */
  private void reactivateTargetWhenSessionsAreSynced() {
    Mockito.when(sessionSynchronizer.refreshReporting(anyLong()))
        .thenAnswer(
            invocation -> {
              Long userId = invocation.getArgument(0);
              jdbcTemplate.update("UPDATE users SET status = 'ACTIVE' WHERE id = ?", userId);
              return true;
            });
  }

  /**
   * T-271. <b>되살아난 계정은 지우지 않는다.</b>
   *
   * <p>{@code 409 CONCURRENT_CHANGE}로 멈춘다. 관리자가 목록을 새로고침하고 다시 판단해야 하는 상황이지, 여기서 조용히 다시 정지시킬 일이 아니다.
   */
  @Test
  void removalStopsWhenTheTargetWasReactivatedInTheWindow() {
    reactivateTargetWhenSessionsAreSynced();

    assertThatThrownBy(() -> removalService.remove(admin.getId(), target.getId()))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CONCURRENT_CHANGE));

    assertThat(userRepository.existsById(target.getId())).as("지우지 않는다").isTrue();
    assertThat(statusOf(target)).isEqualTo(Status.ACTIVE);
  }

  /**
   * 아무도 끼어들지 않으면 그대로 지운다.
   *
   * <p><b>이 테스트가 위의 것을 지킨다.</b> 없으면 "무조건 멈춘다"로 고쳐도 위가 통과한다.
   */
  @Test
  void removalGoesThroughWhenNothingInterferes() {
    Mockito.when(sessionSynchronizer.refreshReporting(anyLong())).thenReturn(true);

    assertThatCode(() -> removalService.remove(admin.getId(), target.getId()))
        .doesNotThrowAnyException();

    assertThat(userRepository.existsById(target.getId())).isFalse();
  }
}
