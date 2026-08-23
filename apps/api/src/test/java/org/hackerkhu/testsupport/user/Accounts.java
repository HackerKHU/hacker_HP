package org.hackerkhu.testsupport.user;

import org.hackerkhu.hackerhp.domain.user.entity.User;

/**
 * 상태별 계정을 만든다. 저장은 부르는 쪽이 한다.
 *
 * <p><b>가입은 네 단계다</b> (spec 3-1 §3-1-4) — 구글 로그인 → 신청서 제출 → 관리자 승인 → 이용. 상태를 손으로 조립하면 그 단계를 건너뛴 계정이
 * 생기고(예: 신청서 없는 {@code ACTIVE}), 그런 계정은 <b>실제로는 만들어질 수 없는 상태</b>라 테스트가 없는 문제를 잡거나 있는 문제를 놓친다.
 *
 * <p>그래서 여기서도 단계를 그대로 밟는다. {@link #approved}는 {@link #applied} 위에 서고, {@link #admin}은 그 위에 선다.
 */
public final class Accounts {

  /**
   * 구글이 주는 표시 이름. 이름을 신경 쓰지 않는 테스트가 쓰는 값이다.
   *
   * <p><b>계정의 이름은 여기서 정해지고 이후 바뀌지 않는다</b> (#224). 신청서는 이름을 받지 않으므로, 특정 이름이 필요한 테스트는 {@link
   * #signedInAs}로 계정을 만들 때 넘긴다.
   */
  private static final String GOOGLE_NAME = "구글이름";

  /** 학과를 특정하지 않는 테스트가 쓰는 기본값. Department.ALL에 있는 값이면 무엇이든 된다. */
  private static final String DEFAULT_DEPARTMENT = "컴퓨터공학과";

  private Accounts() {}

  /**
   * ① 구글 로그인만 마친 계정 — {@code PENDING}, <b>학번이 없다.</b>
   *
   * <p>승인 대상이 아니고({@code applied_at IS NULL}) 관리자 부트스트랩도 통과하지 못한다 (T-20·T-48).
   */
  public static User signedIn(String googleSub, String email) {
    return signedInAs(googleSub, email, GOOGLE_NAME);
  }

  /** ① 이름까지 정해서 만든다. 회원 목록의 정렬·검색처럼 <b>이름이 결과를 가르는</b> 테스트가 쓴다. */
  public static User signedInAs(String googleSub, String email, String name) {
    return User.createFromGoogle(googleSub, email, name);
  }

  /** ② 신청서까지 낸 계정 — {@code PENDING}, 승인 대상이다. */
  public static User applied(String googleSub, String email, String studentNo) {
    return applied(googleSub, email, studentNo, GOOGLE_NAME);
  }

  public static User applied(String googleSub, String email, String studentNo, String name) {
    return applied(googleSub, email, studentNo, name, DEFAULT_DEPARTMENT);
  }

  /**
   * {@code name}은 <b>구글 계정에 저장되는 이름</b>이다 (#224). 신청서가 받는 값이 아니라 계정을 만들 때 정해지고, 신청서 제출로 바뀌지 않는다.
   */
  public static User applied(
      String googleSub, String email, String studentNo, String name, String department) {
    User user = signedInAs(googleSub, email, name);
    user.submitApplication(studentNo, department);
    return user;
  }

  /** ③ 승인된 회원 — {@code ACTIVE}. */
  public static User approved(String googleSub, String email, String studentNo) {
    return approved(googleSub, email, studentNo, GOOGLE_NAME);
  }

  public static User approved(String googleSub, String email, String studentNo, String name) {
    User user = applied(googleSub, email, studentNo, name);
    user.approve();
    return user;
  }

  /** 승인된 회원 중 관리자 — {@code ACTIVE} + {@code ADMIN}. */
  public static User admin(String googleSub, String email, String studentNo) {
    return admin(googleSub, email, studentNo, GOOGLE_NAME);
  }

  public static User admin(String googleSub, String email, String studentNo, String name) {
    User user = approved(googleSub, email, studentNo, name);
    user.promoteToAdmin();
    return user;
  }

  /** 이용 중 정지된 회원 — 승인까지 받았다가 막힌 상태다. */
  public static User suspended(String googleSub, String email, String studentNo) {
    User user = approved(googleSub, email, studentNo);
    user.suspend();
    return user;
  }

  /** 정지된 관리자. 활성 관리자 수를 셀 때 <b>세지 않아야 하는</b> 계정이다 (T-169). */
  public static User suspendedAdmin(String googleSub, String email, String studentNo) {
    User user = admin(googleSub, email, studentNo);
    user.suspend();
    return user;
  }
}
