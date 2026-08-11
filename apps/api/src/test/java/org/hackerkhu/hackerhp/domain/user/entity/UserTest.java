package org.hackerkhu.hackerhp.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

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
