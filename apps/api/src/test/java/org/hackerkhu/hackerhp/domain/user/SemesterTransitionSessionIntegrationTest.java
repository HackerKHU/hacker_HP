package org.hackerkhu.hackerhp.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.audit.repository.AdminActionLogRepository;
import org.hackerkhu.hackerhp.domain.audit.service.AdminActionRecorder;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.domain.user.service.SemesterTransitionService;
import org.hackerkhu.hackerhp.global.auth.SessionSynchronizer;
import org.hackerkhu.testsupport.auth.TestSessions.SignedIn;
import org.hackerkhu.testsupport.user.Accounts;
import org.hackerkhu.testsupport.web.Csrf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 학기 전환이 <b>이미 로그인해 있는 세션에 닿는가</b> (spec 2-2 §2-2-3 §2-2-5, T-342 ~ T-344, #230).
 *
 * <p>여기서 재는 것은 상태가 바뀌었는지가 아니라 <b>그 변경이 세션까지 갔는가</b>다. 인가는 매 요청 세션 값으로 판단하고 필터는 {@code users}를 다시 읽지
 * 않으므로(3-3 결정 12), <b>세션에 닿지 않으면 비활동이 된 사람이 만료(30분)까지 자료를 계속 받아 간다.</b>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SemesterTransitionSessionIntegrationTest extends AbstractIntegrationTest {

  private static final String BASE = "/api/v1/admin/users";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private AdminActionLogRepository actions;
  @Autowired private AdminActionRecorder recorder;
  @Autowired private PlatformTransactionManager transactionManager;

  private User admin;
  private User member;

  @BeforeEach
  void setUp() {
    actions.deleteAll();
    userRepository.deleteAll();
    admin = userRepository.saveAndFlush(Accounts.admin("sub-ad", "ad@khu.ac.kr", "20200001"));
    member = userRepository.saveAndFlush(Accounts.approved("sub-m", "m@khu.ac.kr", "20250001"));
  }

  @AfterEach
  void clear() {
    actions.deleteAll();
    userRepository.deleteAll();
  }

  /**
   * T-342. 이용 중인 회원의 <b>기존 세션</b>이 다음 자료 요청에서 막힌다.
   *
   * <p>이것이 이 이슈의 완료 조건이다 — DB만 바뀌고 세션이 그대로면 <b>관리자는 내렸다고 믿는데 그 사람은 계속 받아 간다.</b>
   */
  @Test
  void aLiveSessionIsBlockedFromNotesRightAfterTheTransition() throws Exception {
    SignedIn signedIn = sessions.signIn(member);
    mockMvc.perform(signedIn.on(get("/api/v1/notes"))).andExpect(status().isOk());

    mockMvc
        .perform(Csrf.with(sessions.signIn(admin).on(post(BASE + "/deactivate"))))
        .andExpect(status().isOk());

    mockMvc
        .perform(signedIn.on(get("/api/v1/notes")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("INACTIVE"));
  }

  /** 같은 세션이 공지는 그대로 쓴다 — 세션을 지우지 않고 갱신하기 때문이다. */
  @Test
  void thatSameSessionStillReadsNotices() throws Exception {
    SignedIn signedIn = sessions.signIn(member);

    mockMvc
        .perform(Csrf.with(sessions.signIn(admin).on(post(BASE + "/deactivate"))))
        .andExpect(status().isOk());

    mockMvc.perform(signedIn.on(get("/api/v1/notices"))).andExpect(status().isOk());
  }

  /* ------------------------------------------------- 반영이 실패하면 (T-343·T-344) */

  /**
   * T-343. 세션 반영이 실패하면 {@code 500}이고 <b>상태 변경은 되돌아가지 않는다.</b>
   *
   * <p>되돌리면 관리자는 실패로 읽는데 DB는 이미 바뀐 어중간한 상태가 된다. 그대로 두고 <b>같은 요청을 다시 보내는 것이 복구 수단</b>이다.
   */
  @Test
  void aFailedRefreshStillLeavesTheChangeCommitted() {
    SessionSynchronizer failing = Mockito.mock(SessionSynchronizer.class);
    Mockito.when(failing.refreshReporting(anyCollection())).thenReturn(false);

    // 서비스가 아니라 이 사례만 실패하는 동기화기를 쓴다 — 다른 사례가 함께 흔들리지 않는다.
    SemesterTransitionService withFailure = serviceWith(failing);

    assertThatThrownBy(() -> withFailure.deactivate(admin.getId()))
        .isInstanceOf(IllegalStateException.class);

    User after = userRepository.findById(member.getId()).orElseThrow();
    assertThat(after.getStatus()).as("변경은 되돌아가지 않는다").isEqualTo(Status.INACTIVE);
    /*
     * T-360. 500 응답에서는 deactivated 목록을 받을 수 없으므로, 이 시각이 직전 배치를
     * 되돌릴 유일한 근거다. 상태만 재면 여기가 NULL인 회귀도 통과한다.
     */
    assertThat(after.getDeactivatedAt()).as("되돌릴 근거가 남아야 한다").isNotNull();
  }

  /**
   * T-344. 실패 뒤 <b>같은 요청을 다시 보내면 이미 {@code INACTIVE}인 사람도 다시 반영된다.</b>
   *
   * <p>여기가 이 기능에서 가장 놓치기 쉬운 자리다. 반영 대상을 {@code deactivated}(= 이번에 바뀐 사람)로 좁힌 구현은 T-339 ~ T-343을 모두
   * 통과하면서 <b>여기서만 깨진다</b> — 이미 내려간 사람이 재요청의 대상에서 빠져 세션이 영영 낡은 채 남는다.
   */
  @Test
  void aRetryRefreshesEvenThoseAlreadyInactive() {
    List<Collection<Long>> refreshed = new ArrayList<>();
    SessionSynchronizer recording = Mockito.mock(SessionSynchronizer.class);
    Mockito.when(recording.refreshReporting(anyCollection()))
        .thenAnswer(
            invocation -> {
              refreshed.add(List.copyOf(invocation.getArgument(0)));
              return true;
            });
    SemesterTransitionService recorded = serviceWith(recording);

    recorded.deactivate(admin.getId());
    recorded.deactivate(admin.getId());

    assertThat(refreshed).hasSize(2);
    assertThat(refreshed.get(1))
        .as("두 번째는 아무도 바꾸지 않지만 이미 INACTIVE인 사람을 대상에 담아야 한다")
        .contains(member.getId());
  }

  /** 대상이 <b>바뀐 사람보다 넓다</b> — 이미 비활동이던 사람도 첫 요청부터 대상이다. */
  @Test
  void theRefreshTargetIsWiderThanTheChangedSet() {
    User alreadyInactive =
        userRepository.saveAndFlush(Accounts.inactive("sub-i", "i@khu.ac.kr", "20250002"));
    List<Collection<Long>> refreshed = new ArrayList<>();
    SessionSynchronizer recording = Mockito.mock(SessionSynchronizer.class);
    Mockito.when(recording.refreshReporting(anyCollection()))
        .thenAnswer(
            invocation -> {
              refreshed.add(List.copyOf(invocation.getArgument(0)));
              return true;
            });

    serviceWith(recording).deactivate(admin.getId());

    assertThat(refreshed)
        .singleElement()
        .satisfies(ids -> assertThat(ids).contains(member.getId(), alreadyInactive.getId()));
  }

  /** 동기화기만 갈아끼운 서비스. 다른 사례가 진짜 동기화기를 그대로 쓰게 둔다. */
  private SemesterTransitionService serviceWith(SessionSynchronizer synchronizer) {
    return new SemesterTransitionService(
        userRepository, synchronizer, recorder, transactionManager);
  }
}
