package org.hackerkhu.hackerhp.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hackerkhu.hackerhp.AbstractIntegrationTest;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.global.auth.JwtProvider;
import org.hackerkhu.testsupport.auth.TestSessions.SignedIn;
import org.hackerkhu.testsupport.web.Csrf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code POST /auth/application} (spec 3-1 §3-1-4 ②, T-50·T-51·T-52·T-56·T-320).
 *
 * <p>세션을 직접 붙이려고 Spring Session 자동 설정을 뺀다 — 이유는 {@code AuthControllerIntegrationTest}에 적어 두었다. 실제
 * 세션 저장소를 태우는 확인은 {@code AuthenticationBindingIntegrationTest}가 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApplicationApiIntegrationTest extends AbstractIntegrationTest {

  private static final String PATH = "/api/v1/auth/application";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private JwtProvider jwtProvider;

  private User applicant;

  @BeforeEach
  void signIn() {
    userRepository.deleteAll();
    applicant =
        userRepository.saveAndFlush(
            User.createFromGoogle("sub-ap", "apply@khu.ac.kr", GOOGLE_NAME));
  }

  @AfterEach
  void clear() {
    userRepository.deleteAll();
  }

  /** 로그인한 브라우저가 보내는 것. 세션의 status가 곧 권한이므로 저장된 값을 그대로 담는다. */
  /** 그 사람이 신청서를 내는 요청. 본문·CSRF·쿠키를 한 번에 싣는다. */
  private MockHttpServletRequestBuilder submit(User user, String body) {
    return Csrf.with(sessions.as(user, post(PATH)))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  /** 학과를 특정하지 않는 테스트가 쓰는 기본값. {@code Department.ALL}에 있는 값이면 무엇이든 된다. */
  private static final String DEFAULT_DEPARTMENT = "컴퓨터공학과";

  /** 계정을 만들 때 구글이 준 이름. <b>신청서는 이것을 바꾸지 못한다</b> (#224). */
  private static final String GOOGLE_NAME = "구글이름";

  private static String body(String studentNo) {
    return body(studentNo, DEFAULT_DEPARTMENT);
  }

  private static String body(String studentNo, String department) {
    return "{\"studentNo\":\"%s\",\"department\":\"%s\"}".formatted(studentNo, department);
  }

  private User reload() {
    return userRepository.findById(applicant.getId()).orElseThrow();
  }

  @Test
  void pendingCanSubmitApplication() throws Exception {
    mockMvc.perform(submit(applicant, body("20240001"))).andExpect(status().isNoContent());

    User saved = reload();
    assertThat(saved.getStudentNo()).isEqualTo("20240001");
    assertThat(saved.getDepartment()).isEqualTo(DEFAULT_DEPARTMENT);
    // 이름은 신청서가 받는 값이 아니다. 계정의 구글 이름이 그대로 남는다 (#224).
    assertThat(saved.getName()).isEqualTo(GOOGLE_NAME);
    // applied_at이 있는 것이 곧 "신청했다"는 뜻이다 — 승인 대상이 되는 기준이다 (§3-2-2).
    assertThat(saved.getAppliedAt()).isNotNull();
  }

  /*
   * T-320 — 화면을 잠그는 것만으로는 부족하다 (#224). 폼이 이름을 읽기 전용으로 보여줘도 API를 직접 부르면
   * 그만이므로, 서버가 본문의 name을 아예 받지 않아야 한다. 계약에 그 필드가 없으니 담아 보내도
   * 역직렬화 단계에서 버려진다 — 저장된 이름은 구글 값 그대로다.
   */
  @Test
  void nameSmuggledInTheBodyIsIgnored() throws Exception {
    mockMvc
        .perform(
            submit(
                applicant,
                "{\"studentNo\":\"20240001\",\"name\":\"위조한이름\",\"department\":\"컴퓨터공학과\"}"))
        .andExpect(status().isNoContent());

    User saved = reload();
    assertThat(saved.getName()).isEqualTo(GOOGLE_NAME);
    // 나머지는 정상 처리된다 — name이 섞였다고 요청 전체가 거부되지는 않는다.
    assertThat(saved.getStudentNo()).isEqualTo("20240001");
    assertThat(saved.getAppliedAt()).isNotNull();
  }

  /* T-51 — 승인 대기 중에는 다시 내 고칠 수 있다. */
  @Test
  void pendingCanResubmitToCorrectTheApplication() throws Exception {
    mockMvc.perform(submit(applicant, body("20240001"))).andExpect(status().isNoContent());

    mockMvc.perform(submit(reload(), body("20240099", "인공지능학과"))).andExpect(status().isNoContent());

    User saved = reload();
    assertThat(saved.getStudentNo()).isEqualTo("20240099");
    assertThat(saved.getDepartment()).isEqualTo("인공지능학과");
  }

  /*
   * T-50 — ACTIVE는 거부된다. 승인 후에 이 경로로 학번을 바꿀 수 있으면 관리자가 심사한 내용과
   * 저장된 내용이 달라진다.
   */
  @Test
  void activeCannotSubmitApplication() throws Exception {
    approve();

    mockMvc
        .perform(submit(reload(), body("20240099")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertThat(reload().getStudentNo()).isEqualTo("20240001");
  }

  /*
   * T-56의 관찰 가능한 결과 — 승인이 먼저 끝난 계정의 제출은 거부된다.
   *
   * 세션에는 아직 PENDING이 들어 있는 상태로 보낸다. 권한 검사만 통과시키고 저장할 때 상태를 다시
   * 보지 않으면, 승인된 계정의 학번이 승인 뒤에 바뀐다. 동시 실행 자체는 UserConcurrencyIntegrationTest가
   * 본다.
   */
  @Test
  void staleSessionCannotOverwriteAnApprovedApplication() throws Exception {
    // 승인 전에 발급된 세션 — 아직 PENDING을 들고 있다.
    SignedIn fromBeforeApproval = sessions.signIn(applicant);
    approve();

    mockMvc
        .perform(
            Csrf.with(fromBeforeApproval.on(post(PATH)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("20249999")))
        .andExpect(status().isForbidden());

    assertThat(reload().getStudentNo()).isEqualTo("20240001");
    assertThat(reload().getDepartment()).isEqualTo(DEFAULT_DEPARTMENT);
  }

  /*
   * T-52 — 공백은 신청서로 인정하지 않는다. 거부됐으면 applied_at이 남지 않아야 한다.
   * 남으면 식별 정보 없는 계정이 승인 대상이 된다.
   */
  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(
      strings = {
        "{\"studentNo\":\"  \",\"department\":\"컴퓨터공학과\"}",
        "{\"studentNo\":\"20240001\",\"department\":\"   \"}"
      })
  void blankValuesAreRejectedWithoutRecordingApplication(String blankBody) throws Exception {
    mockMvc
        .perform(submit(applicant, blankBody))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    assertThat(reload().getAppliedAt()).isNull();
    assertThat(reload().getStudentNo()).isNull();
  }

  /**
   * T-502 — <b>학번은 숫자만 받는다</b> (spec 3-2 §3-2-3 MUST, #328).
   *
   * <p>학교 학번이 전부 숫자임을 확인하고 정한 규칙이다. 한때는 정반대였다 — <i>"편입·교환학생·대학원처럼 형태가 다른 학번이 실제로 있다"</i> 며 형식을 걸지
   * 않았는데(#38), 그 전제가 사실이 아니었다.
   *
   * <p><b>거부됐으면 {@code applied_at}이 남지 않아야 한다</b> — T-52와 같은 이유다. 남으면 형식이 어긋난 계정이 승인 대상이 된다.
   */
  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(strings = {"편입2025", "2024-0001", "EX20240001", "٢٠٢٤", "😀A"})
  void nonNumericStudentNoIsRejected(String studentNo) throws Exception {
    mockMvc
        .perform(submit(applicant, body(studentNo, "컴퓨터공학과")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.message").value("학번을 숫자로 입력해 주세요."));

    assertThat(reload().getAppliedAt()).isNull();
    assertThat(reload().getStudentNo()).isNull();
  }

  /**
   * T-503 — <b>자릿수는 가리지 않는다.</b>
   *
   * <p>폼 예시가 10자리지만 그것이 실제 자릿수라는 근거가 없다 — 길이를 규칙으로 굳히면 그 길이가 아닌 학번을 가진 사람이 가입하지 못하고, 승인 뒤에는 정정할 경로도
   * 없다 ([#178]).
   */
  @Test
  void anyNumericLengthIsAccepted() throws Exception {
    mockMvc.perform(submit(applicant, body("12345", "컴퓨터공학과"))).andExpect(status().isNoContent());

    assertThat(reload().getStudentNo()).isEqualTo("12345");
  }

  /**
   * T-504 — <b>공백 제거가 검증보다 먼저다.</b>
   *
   * <p>{@code "2024 0001"}은 안쪽 공백이 지워져 {@code "20240001"}이 되므로 숫자 규칙을 통과한다. 순서가 뒤집히면 <b>정상 학번이 형식
   * 위반으로 거절된다.</b>
   */
  @Test
  void spacingIsStrippedBeforeTheNumericCheck() throws Exception {
    mockMvc
        .perform(submit(applicant, body("2024 0001", "컴퓨터공학과")))
        .andExpect(status().isNoContent());

    assertThat(reload().getStudentNo()).isEqualTo("20240001");

    mockMvc
        .perform(submit(applicant, body("12345 12345 12345 12345", "컴퓨터공학과")))
        .andExpect(status().isNoContent());

    assertThat(reload().getStudentNo()).isEqualTo("12345123451234512345");
  }

  /*
   * T-181 — 학과는 정해진 목록에 있는 값만 받는다 (spec 3-2 §3-2-2, §3-2-3 MUST). 자유 입력을 허용하면
   * 회원 목록에서 학과로 걸러보는 것이 무의미해진다.
   */
  @Test
  void departmentNotInTheFixedListIsRejected() throws Exception {
    mockMvc
        .perform(submit(applicant, body("20240001", "존재하지않는학과")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    assertThat(reload().getAppliedAt()).isNull();
    assertThat(reload().getDepartment()).isNull();
  }

  /* T-24 — 한 학번으로 여러 계정을 만들 수 없다. 화면은 이 코드로 무엇을 고쳐야 하는지 안다. */
  @Test
  void studentNoAlreadyUsedByAnotherAccountIsRejected() throws Exception {
    User other =
        userRepository.saveAndFlush(User.createFromGoogle("sub-other", "other@khu.ac.kr", "다른사람"));
    mockMvc.perform(submit(other, body("20240001"))).andExpect(status().isNoContent());

    mockMvc
        .perform(submit(applicant, body("20240001")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_STUDENT_NO"));

    assertThat(reload().getAppliedAt()).isNull();
  }

  /* 같은 학번을 그대로 다시 내는 것은 중복이 아니다 — 자기 자신은 세지 않는다. */
  @Test
  void resubmittingTheSameStudentNoIsNotADuplicate() throws Exception {
    mockMvc.perform(submit(applicant, body("20240001"))).andExpect(status().isNoContent());

    mockMvc.perform(submit(reload(), body("20240001", "인공지능학과"))).andExpect(status().isNoContent());

    assertThat(reload().getDepartment()).isEqualTo("인공지능학과");
  }

  /*
   * ACTIVE의 거부는 본문과 무관하다 (T-50).
   *
   * MVC는 컨트롤러 메서드를 부르기 전에 본문을 역직렬화하고 @Valid를 돌린다. 그래서 @PreAuthorize에만
   * 기대면 깨진 본문을 보낸 ACTIVE가 403이 아니라 400을 받는다 — 계약이 본문에 따라 달라진다.
   */
  @Test
  void activeIsRejectedRegardlessOfTheRequestBody() throws Exception {
    approve();
    User active = reload();

    mockMvc
        .perform(submit(active, "{\"studentNo\":\"\",\"department\":\"\"}"))
        .andExpect(status().isForbidden());
    mockMvc.perform(submit(active, "{\"studentNo\":")).andExpect(status().isForbidden());
  }

  /* 길이는 컬럼과 맞춘다 — student_no varchar(20), department varchar(50) (§3-2-2). */
  @Test
  void valuesLongerThanTheColumnAreRejected() throws Exception {
    mockMvc
        .perform(submit(applicant, body("1".repeat(21))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.message").value("학번이 너무 깁니다."));

    mockMvc
        .perform(submit(applicant, body("20240001", "가".repeat(51))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    assertThat(reload().getAppliedAt()).isNull();
  }

  /*
   * 보이지 않는 공백만 담아 보내면 거부된다.
   *
   * NBSP(U+00A0)는 @NotBlank도 String.isBlank()도 공백으로 보지 않는다. 걸러내지 않으면 식별 정보가
   * 없는 계정이 applied_at을 얻어 승인 대상이 된다 (T-52와 같은 사고).
   */
  @Test
  void invisibleWhitespaceOnlyIsRejected() throws Exception {
    mockMvc
        .perform(submit(applicant, body(" ​")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    assertThat(reload().getAppliedAt()).isNull();
    assertThat(reload().getStudentNo()).isNull();
  }

  /*
   * 학번 사이에 보이지 않는 공백을 끼워 유일성을 피해 갈 수 없다.
   *
   * 정규화하지 않으면 DB는 "2024 0001"과 "20240001"을 다른 값으로 보아, 같은 학번으로 계정을
   * 하나 더 만들 수 있다.
   */
  @Test
  void invisibleWhitespaceCannotSmuggleADuplicateStudentNo() throws Exception {
    User other =
        userRepository.saveAndFlush(User.createFromGoogle("sub-other", "other@khu.ac.kr", "다른사람"));
    mockMvc.perform(submit(other, body("20240001"))).andExpect(status().isNoContent());

    mockMvc
        .perform(submit(applicant, body("2024 0001")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_STUDENT_NO"));
  }

  /*
   * 학과명 안쪽의 공백은 정당하다("환경학 및 환경공학과"). 앞뒤만 털고 보이지 않는 문자를 보통 공백으로
   * 바꾼다 — 목록의 문자열과 정확히 일치해야 통과한다.
   */
  @Test
  void departmentKeepsItsInnerSpacing() throws Exception {
    mockMvc
        .perform(submit(applicant, body("20240001", "  환경학 및 환경공학과  ")))
        .andExpect(status().isNoContent());

    assertThat(reload().getDepartment()).isEqualTo("환경학 및 환경공학과");
  }

  @Test
  void anonymousCannotSubmitApplication() throws Exception {
    mockMvc
        .perform(Csrf.with(post(PATH)).contentType(MediaType.APPLICATION_JSON).content(body("2")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  private void approve() {
    User user = reload();
    user.submitApplication("20240001", DEFAULT_DEPARTMENT);
    user.approve();
    userRepository.saveAndFlush(user);
  }
}
