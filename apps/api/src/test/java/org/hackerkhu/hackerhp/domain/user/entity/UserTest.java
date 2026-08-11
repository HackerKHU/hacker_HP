package org.hackerkhu.hackerhp.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class UserTest {

  private static User loggedInWithGoogle() {
    return User.createFromGoogle("google-sub-1", "member@khu.ac.kr", "구글이름");
  }

  private static User applied() {
    User user = loggedInWithGoogle();
    user.submitApplication("20240003", "본명");
    return user;
  }

  @Test
  void createFromGoogleLeavesApplicationFieldsEmpty() {
    User user = loggedInWithGoogle();

    // 구글은 학번을 주지 않는다. 신청서를 내기 전까지 비어 있어야 한다.
    assertThat(user.getStudentNo()).isNull();
    assertThat(user.getAppliedAt()).isNull();
    assertThat(user.getRole()).isEqualTo(Role.USER);
    assertThat(user.getStatus()).isEqualTo(Status.PENDING);
  }

  @Test
  void submitApplicationFillsStudentNoAndAppliedAt() {
    User user = loggedInWithGoogle();

    user.submitApplication("20240003", "본명");

    assertThat(user.getStudentNo()).isEqualTo("20240003");
    assertThat(user.getName()).isEqualTo("본명");
    assertThat(user.getAppliedAt()).isNotNull();
  }

  @Test
  void submitApplicationAgainBeforeApprovalUpdatesContent() {
    User user = applied();

    user.submitApplication("20240099", "정정한이름");

    assertThat(user.getStudentNo()).isEqualTo("20240099");
    assertThat(user.getName()).isEqualTo("정정한이름");
  }

  /*
   * 승인 후에는 이 경로로 학번을 바꿀 수 없다 (spec §3-1-4). 허용하면 관리자가 심사한 내용과
   * 저장된 내용이 달라진다.
   */
  @Test
  void submitApplicationAfterApprovalThrows() {
    User user = applied();
    user.approve();

    assertThatThrownBy(() -> user.submitApplication("20240099", "다른이름"))
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
  @ParameterizedTest(name = "학번={0} 이름={1}")
  @CsvSource(
      value = {
        "'', 김신입",
        "'   ', 김신입",
        "20240003, ''",
        "20240003, '   '",
        "null, 김신입",
        "20240003, null"
      },
      nullValues = "null")
  void submitApplicationRejectsBlankValues(String studentNo, String name) {
    User user = loggedInWithGoogle();

    assertThatThrownBy(() -> user.submitApplication(studentNo, name))
        .isInstanceOf(IllegalArgumentException.class);

    // 거부됐으면 신청 상태가 남아서는 안 된다 — 남으면 승인 대상이 된다.
    assertThat(user.getAppliedAt()).isNull();
    assertThat(user.getStudentNo()).isNull();
    assertThatThrownBy(user::approve).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void submitApplicationTrimsSurroundingWhitespace() {
    User user = loggedInWithGoogle();

    user.submitApplication("  20240003  ", "  본명  ");

    assertThat(user.getStudentNo()).isEqualTo("20240003");
    assertThat(user.getName()).isEqualTo("본명");
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
