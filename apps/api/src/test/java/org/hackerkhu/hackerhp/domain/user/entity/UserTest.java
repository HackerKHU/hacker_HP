package org.hackerkhu.hackerhp.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

class UserTest {

  private static User loggedInWithGoogle() {
    return User.createFromGoogle("google-sub-1", "member@khu.ac.kr", "구글이름");
  }

  private static User applied() {
    User user = loggedInWithGoogle();
    user.submitApplication("20240003", "컴퓨터공학과");
    return user;
  }

  @Test
  void createFromGoogleLeavesApplicationFieldsEmpty() {
    User user = loggedInWithGoogle();

    // 구글은 학번을 주지 않는다. 신청서를 내기 전까지 비어 있어야 한다.
    assertThat(user.getStudentNo()).isNull();
    assertThat(user.getDepartment()).isNull();
    assertThat(user.getAppliedAt()).isNull();
    assertThat(user.getRole()).isEqualTo(Role.USER);
    assertThat(user.getStatus()).isEqualTo(Status.PENDING);
  }

  @Test
  void submitApplicationFillsStudentNoAndAppliedAt() {
    User user = loggedInWithGoogle();

    user.submitApplication("20240003", "컴퓨터공학과");

    assertThat(user.getStudentNo()).isEqualTo("20240003");
    assertThat(user.getDepartment()).isEqualTo("컴퓨터공학과");
    assertThat(user.getAppliedAt()).isNotNull();
  }

  /*
   * 신청서는 이름을 건드리지 않는다 (#224). 이름은 구글 계정에서 온 값이고, 신청서로 바꿀 수 있게
   * 두면 화면을 잠가도 API로 우회된다 — 인자가 없는 것이 곧 그 통제다.
   */
  @Test
  void submitApplicationDoesNotTouchTheGoogleName() {
    User user = loggedInWithGoogle();

    user.submitApplication("20240003", "컴퓨터공학과");

    assertThat(user.getName()).isEqualTo("구글이름");
  }

  @Test
  void submitApplicationAgainBeforeApprovalUpdatesContent() {
    User user = applied();

    user.submitApplication("20240099", "인공지능학과");

    assertThat(user.getStudentNo()).isEqualTo("20240099");
    assertThat(user.getDepartment()).isEqualTo("인공지능학과");
    // 다시 내도 이름은 그대로다.
    assertThat(user.getName()).isEqualTo("구글이름");
  }

  @Test
  void rejectionResetsOnlyApplicationDataAndKeepsTheAccountIdentity() {
    User user = applied();
    String googleSub = user.getGoogleSub();
    String email = user.getEmail();
    String name = user.getName();

    boolean changed = user.resetApplicationAfterRejection();

    assertThat(changed).isTrue();
    assertThat(user.getGoogleSub()).isEqualTo(googleSub);
    assertThat(user.getEmail()).isEqualTo(email);
    assertThat(user.getName()).isEqualTo(name);
    assertThat(user.getStudentNo()).isNull();
    assertThat(user.getDepartment()).isNull();
    assertThat(user.getAppliedAt()).isNull();
    assertThat(user.getApprovedAt()).isNull();
    assertThat(user.getDeactivatedAt()).isNull();
    assertThat(user.getRole()).isEqualTo(Role.USER);
    assertThat(user.getStatus()).isEqualTo(Status.PENDING);
  }

  @Test
  void rejectingAnAlreadyUnappliedAccountIsIdempotent() {
    User user = loggedInWithGoogle();

    assertThat(user.resetApplicationAfterRejection()).isFalse();
    assertThat(user.getStatus()).isEqualTo(Status.PENDING);
    assertThat(user.getAppliedAt()).isNull();
  }

  @Test
  void rejectionClearsLegacyApplicationFieldsWithoutNormalizingOtherFields() {
    User user = loggedInWithGoogle();
    Instant approvedAt = Instant.parse("2026-08-01T00:00:00Z");
    Instant deactivatedAt = Instant.parse("2026-08-02T00:00:00Z");
    ReflectionTestUtils.setField(user, "studentNo", "legacy-student-no");
    ReflectionTestUtils.setField(user, "department", "컴퓨터공학과");
    ReflectionTestUtils.setField(user, "approvedAt", approvedAt);
    ReflectionTestUtils.setField(user, "deactivatedAt", deactivatedAt);
    ReflectionTestUtils.setField(user, "role", Role.ADMIN);

    boolean changed = user.resetApplicationAfterRejection();

    assertThat(changed).isTrue();
    assertThat(user.getStudentNo()).isNull();
    assertThat(user.getDepartment()).isNull();
    assertThat(user.getAppliedAt()).isNull();
    assertThat(user.getApprovedAt()).isEqualTo(approvedAt);
    assertThat(user.getDeactivatedAt()).isEqualTo(deactivatedAt);
    assertThat(user.getRole()).isEqualTo(Role.ADMIN);
    assertThat(user.getStatus()).isEqualTo(Status.PENDING);
  }

  @Test
  void rejectionCannotResetAnActiveMember() {
    User user = applied();
    user.approve();

    assertThatThrownBy(user::resetApplicationAfterRejection)
        .isInstanceOf(IllegalStateException.class);
    assertThat(user.getStatus()).isEqualTo(Status.ACTIVE);
    assertThat(user.getStudentNo()).isEqualTo("20240003");
  }

  /*
   * 학과는 정해진 목록에 있는 값만 받는다 (spec 3-2 §3-2-2, §3-2-3 MUST). 자유 입력을 허용하면
   * 회원 목록에서 학과로 걸러보는 것이 무의미해진다.
   */
  @Test
  void submitApplicationRejectsDepartmentNotInTheFixedList() {
    User user = loggedInWithGoogle();

    assertThatThrownBy(() -> user.submitApplication("20240003", "존재하지않는학과"))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(user.getAppliedAt()).isNull();
    assertThat(user.getDepartment()).isNull();
  }

  /*
   * 승인 후에는 이 경로로 학번을 바꿀 수 없다 (spec §3-1-4). 허용하면 관리자가 심사한 내용과
   * 저장된 내용이 달라진다.
   */
  @Test
  void submitApplicationAfterApprovalThrows() {
    User user = applied();
    user.approve();

    assertThatThrownBy(() -> user.submitApplication("20240099", "컴퓨터공학과"))
        .isInstanceOf(IllegalStateException.class);
  }

  /*
   * 신청서를 내지 않은 계정을 승인하면 학번이 빈 ACTIVE가 만들어지는데, 신청 API는 PENDING
   * 전용이라 나중에 채울 방법이 없다 (spec §3-2-6).
   */
  @Test
  void approveWithoutApplicationThrows() {
    User user = loggedInWithGoogle();

    assertThatThrownBy(user::approve).isInstanceOf(IllegalStateException.class);
  }

  /*
   * T-52 — 공백 신청서는 거부하고 applied_at을 남기지 않는다 (spec §3-2-3).
   *
   * DB의 NOT NULL·UNIQUE는 빈 문자열을 거르지 않는다. 여기서 통과시키면 식별 정보가 없는
   * 계정이 applied_at을 얻어 approve()의 검사까지 통과한다.
   */
  @ParameterizedTest(name = "학번={0} 학과={1}")
  @CsvSource(
      value = {
        "'', 컴퓨터공학과",
        "'   ', 컴퓨터공학과",
        "null, 컴퓨터공학과",
        "20240003, ''",
        "20240003, '   '",
        "20240003, null"
      },
      nullValues = "null")
  void submitApplicationRejectsBlankValues(String studentNo, String department) {
    User user = loggedInWithGoogle();

    assertThatThrownBy(() -> user.submitApplication(studentNo, department))
        .isInstanceOf(IllegalArgumentException.class);

    // 거부됐으면 신청 상태가 남아서는 안 된다 — 남으면 승인 대상이 된다.
    assertThat(user.getAppliedAt()).isNull();
    assertThat(user.getStudentNo()).isNull();
    assertThatThrownBy(user::approve).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void submitApplicationTrimsSurroundingWhitespace() {
    User user = loggedInWithGoogle();

    user.submitApplication("  20240003  ", "  컴퓨터공학과  ");

    assertThat(user.getStudentNo()).isEqualTo("20240003");
    assertThat(user.getDepartment()).isEqualTo("컴퓨터공학과");
  }

  @Test
  void updateEmailReplacesStoredAddress() {
    User user = loggedInWithGoogle();

    user.updateEmail("changed@khu.ac.kr");

    assertThat(user.getEmail()).isEqualTo("changed@khu.ac.kr");
  }

  @Test
  void reactivateFromSuspendedSetsActive() {
    User user = applied();
    user.approve();
    user.suspend();

    user.reactivate();

    assertThat(user.getStatus()).isEqualTo(Status.ACTIVE);
  }

  @Test
  void reactivateFromPendingThrows() {
    User user = applied();

    assertThatThrownBy(user::reactivate).isInstanceOf(IllegalStateException.class);
  }
}
