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

  /** 구글이 주는 표시 이름. 신청서의 본명과 다를 수 있다는 것이 이 값의 요점이다. */
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
    return User.createFromGoogle(googleSub, email, GOOGLE_NAME);
  }

  /** ② 신청서까지 낸 계정 — {@code PENDING}, 승인 대상이다. */
  public static User applied(String googleSub, String email, String studentNo) {
    return applied(googleSub, email, studentNo, "본명");
  }

  public static User applied(String googleSub, String email, String studentNo, String name) {
    return applied(googleSub, email, studentNo, name, DEFAULT_DEPARTMENT);
  }

  public static User applied(
      String googleSub, String email, String studentNo, String name, String department) {
    User user = signedIn(googleSub, email);
    user.submitApplication(studentNo, name, department);
    return user;
  }

  /** ③ 승인된 회원 — {@code ACTIVE}. */
  public static User approved(String googleSub, String email, String studentNo) {
    return approved(googleSub, email, studentNo, "본명");
  }

  public static User approved(String googleSub, String email, String studentNo, String name) {
    User user = applied(googleSub, email, studentNo, name);
    user.approve();
    return user;
  }

  /** 승인된 회원 중 관리자 — {@code ACTIVE} + {@code ADMIN}. */
  public static User admin(String googleSub, String email, String studentNo) {
    return admin(googleSub, email, studentNo, "본명");
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
