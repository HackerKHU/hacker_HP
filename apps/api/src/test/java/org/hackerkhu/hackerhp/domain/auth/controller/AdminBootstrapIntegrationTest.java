package org.hackerkhu.hackerhp.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
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
 * {@code POST /auth/bootstrap-admin} (spec 3-3 결정 11, T-16~T-20).
 *
 * <p>넷을 <b>모두</b> 통과해야 승격한다 — 활성 관리자 0명, 이메일 일치, 토큰 일치, 신청서 제출 완료.
 *
 * <p>거절은 <b>사유를 가리지 않고</b> 전부 같은 응답이다. 사유가 갈리면 "이메일은 맞았고 토큰만 틀렸다"를 알아낼 수 있다.
 */
@SpringBootTest(
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.autoconfigure.session.SessionAutoConfiguration",
      "ADMIN_BOOTSTRAP_EMAIL=founder@khu.ac.kr",
      "ADMIN_BOOTSTRAP_TOKEN=bootstrap-token-for-tests"
    })
@AutoConfigureMockMvc
@Import(InMemorySessionConfig.class)
class AdminBootstrapIntegrationTest extends AbstractIntegrationTest {

  private static final String PATH = "/api/v1/auth/bootstrap-admin";
  private static final String TOKEN = "bootstrap-token-for-tests";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private JwtProvider jwtProvider;

  private User founder;

  @BeforeEach
  void createAccounts() {
    userRepository.deleteAll();
    // 최초 관리자가 될 사람 — 정상 가입 절차를 마친 PENDING이다.
    founder = userRepository.saveAndFlush(applied("sub-founder", "founder@khu.ac.kr", "20200001"));
  }

  @AfterEach
  void clear() {
    userRepository.deleteAll();
  }

  private static User applied(String googleSub, String email, String studentNo) {
    User user = User.createFromGoogle(googleSub, email, "구글이름");
    user.submitApplication(studentNo, "본명");
    return user;
  }

  private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder builder) {
    MockHttpSession session = new MockHttpSession();
    AuthSession.store(session, user);
    return builder
        .session(session)
        .cookie(new Cookie("ACCESS_TOKEN", jwtProvider.issue(user.getId())));
  }

  private MockHttpServletRequestBuilder bootstrap(User caller, String token) {
    return Csrf.with(as(caller, post(PATH)))
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
    User upper = userRepository.saveAndFlush(applied("sub-up", "FOUNDER@KHU.AC.KR", "20200009"));

    mockMvc.perform(bootstrap(upper, TOKEN)).andExpect(status().isNoContent());
    assertThat(reload(upper).getRole()).isEqualTo(Role.ADMIN);
  }

  /**
   * 마지막 관리자 사고의 복구 경로 (2-2 §2-2-7).
   *
   * <p>이미 승인된 회원이면 <b>role만 바꾼다.</b> {@code approve()}를 다시 부르면 승인일이 오늘로 덮여 실제 승인일이 사라진다.
   */
  @Test
  void recoversAnAlreadyApprovedMemberWithoutRewritingApprovedAt() throws Exception {
    // 이메일이 유일해야 하므로 최초 계정을 먼저 지운다.
    userRepository.deleteAll();
    User member = applied("sub-ok", "founder@khu.ac.kr", "20200002");
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
    User other = userRepository.saveAndFlush(applied("sub-other", "other@khu.ac.kr", "20240101"));

    mockMvc.perform(bootstrap(other, TOKEN)).andExpect(status().isForbidden());

    assertThat(reload(other).getRole()).isEqualTo(Role.USER);
  }

  /** T-19 — 관리자가 이미 있으면 이 경로는 아무 일도 하지 않는다. 이것이 상시 개방의 방어선이다. */
  @Test
  void doesNothingWhileAnActiveAdminExists() throws Exception {
    User existing = applied("sub-admin", "admin@khu.ac.kr", "20200003");
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
    userRepository.deleteAll();
    User banned = applied("sub-ban", "founder@khu.ac.kr", "20200004");
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
            as(founder, post(PATH))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + TOKEN + "\"}"))
        .andExpect(status().isForbidden());

    assertThat(reload(founder).getRole()).isEqualTo(Role.USER);
  }
}
