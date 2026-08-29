package org.hackerkhu.hackerhp.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.audit.entity.AdminAction;
import org.hackerkhu.hackerhp.domain.audit.entity.AdminActionLog;
import org.hackerkhu.hackerhp.domain.audit.repository.AdminActionLogRepository;
import org.hackerkhu.hackerhp.domain.auth.repository.BootstrapAttemptRepository;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.global.auth.JwtProvider;
import org.hackerkhu.testsupport.user.Accounts;
import org.hackerkhu.testsupport.web.Csrf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code POST /auth/bootstrap-admin} (spec 3-3 결정 11, T-16~T-20).
 *
 * <p>넷을 <b>모두</b> 통과해야 승격한다 — 활성 관리자 0명, 이메일 일치, 토큰 일치, 신청서 제출 완료.
 *
 * <p>거절은 <b>사유를 가리지 않고</b> 전부 같은 응답이다. 사유가 갈리면 "이메일은 맞았고 토큰만 틀렸다"를 알아낼 수 있다.
 */
@SpringBootTest(
    properties = {
      "ADMIN_BOOTSTRAP_EMAIL=founder@khu.ac.kr",
      "ADMIN_BOOTSTRAP_TOKEN=bootstrap-token-for-tests"
    })
@AutoConfigureMockMvc
class AdminBootstrapIntegrationTest extends AbstractIntegrationTest {

  private static final String PATH = "/api/v1/auth/bootstrap-admin";
  private static final String TOKEN = "bootstrap-token-for-tests";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private BootstrapAttemptRepository attempts;
  @Autowired private JwtProvider jwtProvider;
  @Autowired private AdminActionLogRepository adminActions;

  private User founder;

  @BeforeEach
  void createAccounts() {
    adminActions.deleteAll();
    attempts.deleteAll();
    userRepository.deleteAll();
    // 최초 관리자가 될 사람 — 정상 가입 절차를 마친 PENDING이다.
    founder =
        userRepository.saveAndFlush(
            Accounts.applied("sub-founder", "founder@khu.ac.kr", "20200001"));
  }

  @AfterEach
  void clear() {
    adminActions.deleteAll();
    attempts.deleteAll();
    userRepository.deleteAll();
  }

  private MockHttpServletRequestBuilder bootstrap(User caller, String token) {
    return Csrf.with(sessions.as(caller, post(PATH)))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"token\":\"" + token + "\"}");
  }

  private User reload(User user) {
    return userRepository.findById(user.getId()).orElseThrow();
  }

  /* ---------------------------------------------------------------- 성공 */

  /**
   * T-16 — 넷을 모두 통과한다.
   *
   * <p><b>{@code student_no}가 채워져 있어야 한다.</b> 신청서를 낸 계정만 승격되므로 학번 없는 관리자가 만들어지지 않는다.
   *
   * <p>이 사례는 {@code AccountStatusFilter}가 이 경로를 {@code PENDING}에게 열어 두는지도 함께 확인한다 — 막혀 있으면 최초 관리자는
   * 영영 승격할 수 없다.
   */
  @Test
  void promotesTheFounder() throws Exception {
    mockMvc.perform(bootstrap(founder, TOKEN)).andExpect(status().isNoContent());

    User promoted = reload(founder);
    assertThat(promoted.getRole()).isEqualTo(Role.ADMIN);
    assertThat(promoted.getStatus()).isEqualTo(Status.ACTIVE);
    assertThat(promoted.getApprovedAt()).isNotNull();
    assertThat(promoted.getStudentNo()).isEqualTo("20200001");
  }

  /** 운영자가 SSM에 대문자로 넣어도 동작해야 한다. 이메일은 비밀이 아니다. */
  @Test
  void emailComparisonIgnoresCase() throws Exception {
    User upper =
        userRepository.saveAndFlush(Accounts.applied("sub-up", "FOUNDER@KHU.AC.KR", "20200009"));

    mockMvc.perform(bootstrap(upper, TOKEN)).andExpect(status().isNoContent());
    assertThat(reload(upper).getRole()).isEqualTo(Role.ADMIN);
  }

  /**
   * 대소문자만 다른 두 계정이 함께 있어도 <b>관리자는 하나만 생긴다.</b>
   *
   * <p>{@code users.email}의 {@code UNIQUE}는 대소문자를 구분하는데 이 경로는 구분하지 않고 견주므로, <b>자격을 만족하는 행이 둘일 수
   * 있다.</b> 각자 자기 행만 잠그면 둘 다 "활성 관리자 0명"을 보고 통과한다 — 같은 이메일을 쓰는 계정을 전부 잠가 한 줄로 세운다.
   */
  @Test
  void onlyOneOfTwoCaseVariantsCanBePromoted() throws Exception {
    User upper =
        userRepository.saveAndFlush(Accounts.applied("sub-up", "FOUNDER@KHU.AC.KR", "20200009"));

    mockMvc.perform(bootstrap(founder, TOKEN)).andExpect(status().isNoContent());
    mockMvc.perform(bootstrap(upper, TOKEN)).andExpect(status().isForbidden());

    assertThat(reload(founder).getRole()).isEqualTo(Role.ADMIN);
    assertThat(reload(upper).getRole()).isEqualTo(Role.USER);
  }

  /**
   * 마지막 관리자 사고의 복구 경로 (2-2 §2-2-7).
   *
   * <p>이미 승인된 회원이면 <b>role만 바꾼다.</b> {@code approve()}를 다시 부르면 승인일이 오늘로 덮여 실제 승인일이 사라진다.
   */
  @Test
  void recoversAnAlreadyApprovedMemberWithoutRewritingApprovedAt() throws Exception {
    // 이메일이 유일해야 하므로 최초 계정을 먼저 지운다.
    attempts.deleteAll();
    userRepository.deleteAll();
    User member = Accounts.applied("sub-ok", "founder@khu.ac.kr", "20200002");
    member.approve();
    User saved = userRepository.saveAndFlush(member);
    // 저장된 값을 기준으로 잡는다 — 메모리의 Instant는 나노초, DB는 마이크로초라 그대로 비교하면 어긋난다.
    Instant approvedBefore = reload(saved).getApprovedAt();

    mockMvc.perform(bootstrap(saved, TOKEN)).andExpect(status().isNoContent());

    User promoted = reload(saved);
    assertThat(promoted.getRole()).isEqualTo(Role.ADMIN);
    assertThat(promoted.getApprovedAt()).isEqualTo(approvedBefore);
  }

  /**
   * T-358. <b>비활동 계정도 {@code ACTIVE}로 올린 뒤 승격한다</b> (#228 리뷰, #229).
   *
   * <p>거절하면 자격을 갖춘 그 계정이 마침 비활동일 때 <b>복구 경로가 통째로 막힌다.</b> 그렇다고 상태를 그대로 두고 role만 바꾸면 {@code
   * ADMIN}/{@code INACTIVE}가 생기는데, 이 문이 열리는 조건이 <i>"활성 관리자 0명"</i>이라 그 조합은 <b>0명을 그대로 둔 채 남는다</b> —
   * 몇 번을 불러도 복구되지 않는다.
   *
   * <p>승인일시는 덮지 않는다. 이미 승인된 계정이다.
   */
  @Test
  void promotesAnInactiveAccountAfterRestoringItToActive() throws Exception {
    attempts.deleteAll();
    userRepository.deleteAll();
    User member = Accounts.applied("sub-in", "founder@khu.ac.kr", "20200007");
    member.approve();
    member.deactivate(Instant.now());
    User saved = userRepository.saveAndFlush(member);
    Instant approvedBefore = reload(saved).getApprovedAt();

    mockMvc.perform(bootstrap(saved, TOKEN)).andExpect(status().isNoContent());

    User promoted = reload(saved);
    assertThat(promoted.getRole()).isEqualTo(Role.ADMIN);
    assertThat(promoted.getStatus()).as("ADMIN/INACTIVE 조합을 만들지 않는다").isEqualTo(Status.ACTIVE);
    assertThat(promoted.getApprovedAt()).isEqualTo(approvedBefore);
  }

  /**
   * <b>같은 요청을 다시 보내는 것이 복구 수단이어야 한다.</b>
   *
   * <p>승격은 커밋됐는데 세션 갱신이 실패하는 경우가 있다 — 그 실패는 예외로 올리지 않는다(이미 커밋된 변경까지 실패한 것처럼 보이면 안 되기 때문이다). 그러면 본인이
   * 할 수 있는 일은 다시 부르는 것뿐인데, "활성 관리자가 이미 있다"로 거절하면 <b>재로그인 전까지 관리자 화면을 열 수 없다.</b>
   *
   * <p>재요청은 아무것도 바꾸지 않고 세션만 다시 맞춘다.
   */
  @Test
  void repeatingTheCallAfterPromotionIsAllowed() throws Exception {
    mockMvc.perform(bootstrap(founder, TOKEN)).andExpect(status().isNoContent());
    User promoted = reload(founder);

    mockMvc.perform(bootstrap(founder, TOKEN)).andExpect(status().isNoContent());

    User again = reload(founder);
    assertThat(again.getRole()).isEqualTo(Role.ADMIN);
    assertThat(again.getStatus()).isEqualTo(Status.ACTIVE);
    assertThat(again.getApprovedAt()).isEqualTo(promoted.getApprovedAt());
  }

  /**
   * 승격이 이력에 남는다 (#143).
   *
   * <p>스스로에게 하는 조작이라 행위자와 대상이 같다. 그래도 남기는 것은 <b>"관리자가 언제 어떻게 생겼는가"의 유일한 기록</b>이기 때문이다 — 상시 열려 있는
   * 문이라 더 그렇다.
   *
   * <p><b>토큰은 남지 않는다.</b> 시크릿이다 (2-2 §2-2-7).
   */
  @Test
  void recordsThePromotion() throws Exception {
    mockMvc.perform(bootstrap(founder, TOKEN)).andExpect(status().isNoContent());

    assertThat(adminActions.findByTargetIdOrderByIdAsc(founder.getId()))
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.getAction()).isEqualTo(AdminAction.PROMOTE_ADMIN);
              assertThat(entry.getActorId()).isEqualTo(founder.getId());
            });
  }

  /** 이미 승격된 계정의 재요청은 남기지 않는다. 아무것도 바뀌지 않았다. */
  @Test
  void doesNotRecordARepeatedPromotion() throws Exception {
    mockMvc.perform(bootstrap(founder, TOKEN)).andExpect(status().isNoContent());
    mockMvc.perform(bootstrap(founder, TOKEN)).andExpect(status().isNoContent());

    assertThat(adminActions.findByTargetIdOrderByIdAsc(founder.getId()))
        .extracting(AdminActionLog::getAction)
        .containsExactly(AdminAction.PROMOTE_ADMIN);
  }

  /** 거절된 시도는 이력에 남지 않는다 — 반복 시도를 다루는 것은 #144다. */
  @Test
  void doesNotRecordRejectedAttempts() throws Exception {
    mockMvc.perform(bootstrap(founder, "wrong-token")).andExpect(status().isForbidden());

    assertThat(adminActions.findAll()).isEmpty();
  }

  /** 재요청이라도 토큰이 틀리면 통과하지 못한다. 이 문은 이메일·토큰을 지난 사람에게만 열린다. */
  @Test
  void repeatingWithAWrongTokenIsStillRejected() throws Exception {
    mockMvc.perform(bootstrap(founder, TOKEN)).andExpect(status().isNoContent());

    mockMvc.perform(bootstrap(founder, "wrong-token")).andExpect(status().isForbidden());
  }

  /* ---------------------------------------------------------------- 거절 */

  /** T-17 — 이메일은 맞지만 토큰이 다르다. */
  @Test
  void wrongTokenIsRejected() throws Exception {
    mockMvc
        .perform(bootstrap(founder, "wrong-token"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertThat(reload(founder).getRole()).isEqualTo(Role.USER);
  }

  /** T-18 — 토큰은 맞지만 다른 사람이다. 토큰만 알아서는 아무나 관리자가 될 수 없다. */
  @Test
  void anotherEmailIsRejectedEvenWithTheRightToken() throws Exception {
    User other =
        userRepository.saveAndFlush(Accounts.applied("sub-other", "other@khu.ac.kr", "20240101"));

    mockMvc.perform(bootstrap(other, TOKEN)).andExpect(status().isForbidden());

    assertThat(reload(other).getRole()).isEqualTo(Role.USER);
  }

  /** T-19 — 관리자가 이미 있으면 이 경로는 아무 일도 하지 않는다. 이것이 상시 개방의 방어선이다. */
  @Test
  void doesNothingWhileAnActiveAdminExists() throws Exception {
    User existing = Accounts.applied("sub-admin", "admin@khu.ac.kr", "20200003");
    existing.approve();
    existing.promoteToAdmin();
    userRepository.saveAndFlush(existing);

    mockMvc.perform(bootstrap(founder, TOKEN)).andExpect(status().isForbidden());

    assertThat(reload(founder).getRole()).isEqualTo(Role.USER);
  }

  /**
   * T-20 — 구글 로그인만 하고 신청서를 내지 않았다.
   *
   * <p>통과시키면 <b>학번 없는 관리자</b>가 만들어지는데, 신청 API는 {@code PENDING} 전용이라 나중에 채울 방법이 없다.
   */
  @Test
  void accountWithoutAnApplicationIsRejected() throws Exception {
    attempts.deleteAll();
    userRepository.deleteAll();
    User justSignedIn =
        userRepository.saveAndFlush(User.createFromGoogle("sub-new", "founder@khu.ac.kr", "이름"));

    mockMvc.perform(bootstrap(justSignedIn, TOKEN)).andExpect(status().isForbidden());

    User after = reload(justSignedIn);
    assertThat(after.getRole()).isEqualTo(Role.USER);
    assertThat(after.getStatus()).isEqualTo(Status.PENDING);
  }

  /** 정지된 계정이 이 경로로 되살아나면 안 된다. */
  @Test
  void suspendedAccountIsRejected() throws Exception {
    attempts.deleteAll();
    userRepository.deleteAll();
    User banned = Accounts.applied("sub-ban", "founder@khu.ac.kr", "20200004");
    banned.approve();
    banned.suspend();
    User saved = userRepository.saveAndFlush(banned);

    mockMvc.perform(bootstrap(saved, TOKEN)).andExpect(status().isForbidden());

    User after = reload(saved);
    assertThat(after.getRole()).isEqualTo(Role.USER);
    assertThat(after.getStatus()).isEqualTo(Status.SUSPENDED);
  }

  /* ---------------------------------------------------------------- 입력 */

  @Test
  void emptyTokenIsRejected() throws Exception {
    mockMvc
        .perform(bootstrap(founder, ""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  /* ---------------------------------------------------------------- 권한 */

  @Test
  void anonymousIsUnauthenticated() throws Exception {
    mockMvc
        .perform(
            Csrf.with(post(PATH))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + TOKEN + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

    assertThat(reload(founder).getRole()).isEqualTo(Role.USER);
  }

  /** CSRF 토큰이 없으면 다른 사이트가 로그인한 사람 대신 이 경로를 부를 수 있다. */
  @Test
  void withoutCsrfTokenIsRejected() throws Exception {
    mockMvc
        .perform(
            sessions
                .as(founder, post(PATH))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + TOKEN + "\"}"))
        .andExpect(status().isForbidden());

    assertThat(reload(founder).getRole()).isEqualTo(Role.USER);
  }
}
