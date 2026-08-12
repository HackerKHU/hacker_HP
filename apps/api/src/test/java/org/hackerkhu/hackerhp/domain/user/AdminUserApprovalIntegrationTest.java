package org.hackerkhu.hackerhp.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.stream.LongStream;
import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.domain.user.service.AdminUserApprovalService;
import org.hackerkhu.hackerhp.global.auth.AuthSession;
import org.hackerkhu.hackerhp.global.auth.JwtProvider;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
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
 * {@code POST /admin/users/approve} (spec 2-2 §2-2-2, 3-2 §3-2-6).
 *
 * <p>가입 승인은 <b>되돌릴 수 없고 여러 명을 한 번에 처리한다.</b> 그래서 "무엇이 대상인가"와 "무엇이 실패했는가"를 조밀하게 본다 — 관리자는 응답의 건수를
 * 그대로 읽어 안내하고, 실패한 사람에게 신청서를 내라고 연락한다.
 */
@SpringBootTest(
    properties =
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.autoconfigure.session.SessionAutoConfiguration")
@AutoConfigureMockMvc
@Import(InMemorySessionConfig.class)
class AdminUserApprovalIntegrationTest extends AbstractIntegrationTest {

  private static final String PATH = "/api/v1/admin/users/approve";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private JwtProvider jwtProvider;
  @Autowired private AdminUserApprovalService approvalService;

  private User admin;
  private User applicant;
  private User anotherApplicant;
  private User neverApplied;
  private User alreadyActive;
  private User suspended;

  @BeforeEach
  void createAccounts() {
    userRepository.deleteAll();

    User toPromote = approved("sub-admin", "admin@khu.ac.kr", "20200001", "관리자");
    toPromote.promoteToAdmin();
    admin = userRepository.saveAndFlush(toPromote);

    applicant = userRepository.saveAndFlush(applied("sub-a", "a@khu.ac.kr", "20240101", "신청자일"));
    anotherApplicant =
        userRepository.saveAndFlush(applied("sub-b", "b@khu.ac.kr", "20240102", "신청자이"));
    // 구글 로그인만 해봤다. 학번이 없다.
    neverApplied =
        userRepository.saveAndFlush(User.createFromGoogle("sub-c", "c@khu.ac.kr", "미신청"));
    alreadyActive =
        userRepository.saveAndFlush(approved("sub-d", "d@khu.ac.kr", "20240104", "이미회원"));

    User toSuspend = approved("sub-e", "e@khu.ac.kr", "20240105", "정지회원");
    toSuspend.suspend();
    suspended = userRepository.saveAndFlush(toSuspend);
  }

  @AfterEach
  void clear() {
    userRepository.deleteAll();
  }

  private static User applied(String googleSub, String email, String studentNo, String name) {
    User user = User.createFromGoogle(googleSub, email, "구글이름");
    user.submitApplication(studentNo, name);
    return user;
  }

  private static User approved(String googleSub, String email, String studentNo, String name) {
    User user = applied(googleSub, email, studentNo, name);
    user.approve();
    return user;
  }

  private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder builder) {
    MockHttpSession session = new MockHttpSession();
    AuthSession.store(session, user);
    return builder
        .session(session)
        .cookie(new Cookie("ACCESS_TOKEN", jwtProvider.issue(user.getId())));
  }

  private MockHttpServletRequestBuilder approveAs(User caller, String body) {
    return Csrf.with(as(caller, post(PATH))).contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private MockHttpServletRequestBuilder approve(String body) {
    return approveAs(admin, body);
  }

  private static String ids(Long... userIds) {
    return "{\"userIds\":["
        + String.join(",", List.of(userIds).stream().map(String::valueOf).toList())
        + "]}";
  }

  private Status statusOf(User user) {
    return userRepository.findById(user.getId()).orElseThrow().getStatus();
  }

  /* ---------------------------------------------------------------- 승인 */

  /** 완료 조건 — 여러 계정이 한 요청으로 ACTIVE가 되고 승인일시가 채워진다. */
  @Test
  void approvesEveryoneSelectedInOneRequest() throws Exception {
    mockMvc
        .perform(approve(ids(applicant.getId(), anotherApplicant.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approved.length()").value(2))
        .andExpect(jsonPath("$.failed.length()").value(0));

    User saved = userRepository.findById(applicant.getId()).orElseThrow();
    assertThat(saved.getStatus()).isEqualTo(Status.ACTIVE);
    assertThat(saved.getApprovedAt()).isNotNull();
    assertThat(statusOf(anotherApplicant)).isEqualTo(Status.ACTIVE);
  }

  /* ---------------------------------------------------------------- 실패 */

  /**
   * T-49 — <b>목록을 우회해</b> 미신청 계정의 id를 직접 보낸다.
   *
   * <p>화면은 그 계정을 승인 대상에서 잠그지만(T-73) API를 직접 부르는 경로가 남아 있다. 통과시키면 <b>{@code student_no}가 비어 있는
   * {@code ACTIVE}</b>가 만들어지는데, 신청 API는 {@code PENDING} 전용이라 나중에 채울 방법이 없다.
   */
  @Test
  void accountThatNeverAppliedIsCountedAsFailure() throws Exception {
    mockMvc
        .perform(approve(ids(neverApplied.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approved.length()").value(0))
        .andExpect(jsonPath("$.failed[0].userId").value(neverApplied.getId()))
        .andExpect(jsonPath("$.failed[0].reason").value("NOT_APPLIED"));

    assertThat(statusOf(neverApplied)).isEqualTo(Status.PENDING);
  }

  /**
   * 이미 승인된 계정은 사유가 다르다.
   *
   * <p>전부 {@code NOT_APPLIED}로 뭉개면 <b>화면이 "신청서를 내지 않은 계정입니다"라고 안내한다</b> — 신청서를 내고 이미 승인까지 받은 사람에게
   * 하는 거짓말이다.
   */
  @Test
  void alreadyApprovedAccountFailsForADifferentReason() throws Exception {
    mockMvc
        .perform(approve(ids(alreadyActive.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.failed[0].reason").value("NOT_PENDING"));
  }

  /** 정지된 계정도 승인 대상이 아니다. 승인으로 정지가 풀리면 안 된다 — 해제는 #31의 일이다. */
  @Test
  void suspendedAccountIsNotRevivedByApproval() throws Exception {
    mockMvc
        .perform(approve(ids(suspended.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.failed[0].reason").value("NOT_PENDING"));

    assertThat(statusOf(suspended)).isEqualTo(Status.SUSPENDED);
  }

  @Test
  void missingAccountIsCountedAsFailure() throws Exception {
    mockMvc
        .perform(approve(ids(999_999L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.failed[0].reason").value("NOT_FOUND"));
  }

  /**
   * <b>부분 실패가 성공을 되돌리지 않는다</b> (완료 조건 — "나머지는 정상 처리된다").
   *
   * <p>실패를 예외로 던지면 관리자가 20명을 골랐을 때 한 명이 신청서를 내지 않았다는 이유로 <b>아무도 승인되지 않는다.</b>
   */
  @Test
  void failuresDoNotRollBackTheSuccesses() throws Exception {
    mockMvc
        .perform(
            approve(ids(applicant.getId(), neverApplied.getId(), alreadyActive.getId(), 999_999L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approved.length()").value(1))
        .andExpect(jsonPath("$.approved[0]").value(applicant.getId()))
        .andExpect(jsonPath("$.failed.length()").value(3));

    assertThat(statusOf(applicant)).isEqualTo(Status.ACTIVE);
    assertThat(statusOf(neverApplied)).isEqualTo(Status.PENDING);
  }

  /* ---------------------------------------------------------------- 입력 */

  /**
   * 같은 id가 두 번 와도 한 번만 센다.
   *
   * <p>화면은 배열 길이를 그대로 "N명을 승인했습니다"로 읽는다. 거르지 않으면 <b>건수가 부풀려진다.</b>
   */
  @Test
  void duplicatedIdIsCountedOnce() throws Exception {
    mockMvc
        .perform(approve(ids(applicant.getId(), applicant.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.approved.length()").value(1));
  }

  @Test
  void emptySelectionIsRejected() throws Exception {
    mockMvc
        .perform(approve("{\"userIds\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  /** 상한은 목록의 페이지 크기와 같다. 화면이 한 번에 고를 수 있는 최대가 "현재 페이지 전부"다 (T-75). */
  @Test
  void tooManyIdsAreRejected() throws Exception {
    String body = ids(LongStream.rangeClosed(1, 101).boxed().toArray(Long[]::new));
    mockMvc
        .perform(approve(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  /**
   * <b>중복으로 상한을 우회할 수 없다.</b>
   *
   * <p>요청 DTO에서 중복을 걸러내면 {@code @Size}가 원본이 아니라 줄어든 목록을 보게 되어, 같은 id를 101번 담은 요청이 한 건으로 줄어 상한을 그냥
   * 통과한다. 상한은 원본 배열에 걸어야 뜻이 있다.
   */
  @Test
  void repeatingOneIdDoesNotSlipPastTheLimit() throws Exception {
    Long[] repeated = new Long[101];
    java.util.Arrays.fill(repeated, applicant.getId());
    mockMvc
        .perform(approve(ids(repeated)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    assertThat(statusOf(applicant)).isEqualTo(Status.PENDING);
  }

  /* ---------------------------------------------------------------- 권한 */

  /** T-05의 형태. 승인은 관리자만 할 수 있다. */
  @Test
  void memberCannotApprove() throws Exception {
    mockMvc
        .perform(approveAs(alreadyActive, ids(applicant.getId())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertThat(statusOf(applicant)).isEqualTo(Status.PENDING);
  }

  /**
   * T-148의 형태 — <b>권한 검사가 본문보다 먼저다.</b>
   *
   * <p>{@code @PreAuthorize}에만 기대면 MVC가 본문을 먼저 역직렬화해 {@code 400}이 나간다. 권한이 없다는 사실이 본문 모양에 가려진다.
   */
  @Test
  void memberWithABrokenBodyStillGetsForbidden() throws Exception {
    mockMvc
        .perform(approveAs(alreadyActive, "{\"userIds\":"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  /** <b>CSRF 토큰이 없으면 승인되지 않는다.</b> 없으면 다른 사이트가 관리자 대신 회원을 승인한다 (5-TESTING §5-1 MUST). */
  @Test
  void approvalWithoutCsrfTokenIsRejected() throws Exception {
    mockMvc
        .perform(
            as(admin, post(PATH))
                .contentType(MediaType.APPLICATION_JSON)
                .content(ids(applicant.getId())))
        .andExpect(status().isForbidden());

    assertThat(statusOf(applicant)).isEqualTo(Status.PENDING);
  }

  /**
   * 요청이 인가를 지난 <b>뒤에</b> 요청자가 정지되면 그 승인은 커밋되지 않는다 (T-172).
   *
   * <p>인가는 세션 값으로 이루어지므로, 대상 행을 기다리는 동안 다른 관리자가 이 사람을 정지시켰을 수 있다. 다시 확인하지 않으면 <b>정지된 관리자가 회원을
   * 활성화한다.</b>
   */
  @Test
  void suspendedRequesterCannotFinishAPendingApproval() {
    User toSuspend = userRepository.findById(admin.getId()).orElseThrow();
    toSuspend.suspend();
    userRepository.saveAndFlush(toSuspend);

    assertThatThrownBy(() -> approvalService.approve(admin.getId(), List.of(applicant.getId())))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.SUSPENDED));

    assertThat(statusOf(applicant)).isEqualTo(Status.PENDING);
  }

  @Test
  void anonymousIsUnauthenticated() throws Exception {
    mockMvc
        .perform(
            Csrf.with(post(PATH))
                .contentType(MediaType.APPLICATION_JSON)
                .content(ids(applicant.getId())))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }
}
