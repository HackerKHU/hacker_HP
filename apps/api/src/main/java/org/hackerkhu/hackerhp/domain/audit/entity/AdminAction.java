package org.hackerkhu.hackerhp.domain.audit.entity;

/**
 * 이력에 남는 조작 (spec 2-2 §2-2-7, #143).
 *
 * <p><b>성공한 조작만 있다.</b> 거절된 시도는 여기 오지 않는다 — 그것은 반복 시도를 막는 문제라 <a
 * href="https://github.com/HackerKHU/hacker_HP/issues/144">#144</a>가 다룬다.
 *
 * <p>이름이 DB의 {@code CHECK} 제약과 같아야 한다 ({@code V5__admin_actions.sql}). 값을 더하면 마이그레이션도 함께 고친다.
 */
public enum AdminAction {

  /** 가입 승인 — {@code PENDING} → {@code ACTIVE} (2-2 §2-2-2). */
  APPROVE,

  /** 정지 — {@code ACTIVE} → {@code SUSPENDED} (2-2 §2-2-3). */
  SUSPEND,

  /** 정지 해제 — {@code SUSPENDED} → {@code ACTIVE}. */
  ACTIVATE,

  /**
   * 최초 관리자 승격 (3-3 결정 11).
   *
   * <p><b>무엇으로 승격했는지는 남기지 않는다.</b> 토큰은 시크릿이라 기록에 넣지 않는다.
   */
  PROMOTE_ADMIN,

  /** 가입 거부 — 신청 정보를 비우고 같은 계정을 미승인 상태로 되돌린다 (2-2 §2-2-2). */
  REJECT,

  /** 회원 제거 (2-2 §2-2-4). <b>대상 계정은 사라지지만 이력은 남는다</b> — 그래서 이 표에 FK가 없다. */
  REMOVE,

  /**
   * 본인 탈퇴 — {@code DELETE /auth/me} (2-2 §2-2-4, #223).
   *
   * <p><b>{@link #REMOVE}와 가른다.</b> 계정이 사라지고 나면 남는 것은 숫자 id뿐이라, 뭉치면 <i>"관리자가 지웠다"</i>와 <i>"본인이
   * 나갔다"</i>를 영영 가를 수 없다 — <i>"저는 탈퇴한 적 없는데 계정이 사라졌다"</i>가 이 이력이 답해야 할 바로 그 질문이다.
   *
   * <p>{@code actor_id}와 {@code target_id}가 같다. 그 조합은 {@link #PROMOTE_ADMIN}에 이미 있다.
   *
   * <p><b>같은 사람이 관리 화면으로 자기를 지우면 {@link #REMOVE}다.</b> 어느 문으로 들어왔는지가 기록에 남아야 한다.
   */
  WITHDRAW,

  /**
   * 학기 전환 — 비활성화 (2-2 §2-2-3, #228 #230). {@code ACTIVE} → {@code INACTIVE}.
   *
   * <p><b>학기마다 전원이 대상이라 행이 많이 쌓이는데, 그것이 이 이력의 목적이다</b> — <i>"저는 지난 학기에 활동했는데 왜 자료가 안 보이나요"</i>에 답할
   * 수 있어야 한다.
   */
  DEACTIVATE,

  /**
   * 학기 전환 — 복구 (2-2 §2-2-3, #228 #230). {@code INACTIVE} → {@code ACTIVE}.
   *
   * <p><b>{@link #ACTIVATE}(정지 해제)와 가른다.</b> 도착지는 같지만 있었던 일이 다르다 — 뭉치면 이력을 읽어도 정지가 풀린 것인지 학기가 바뀐
   * 것인지 알 수 없다. {@link #SUSPEND}와 {@link #ACTIVATE}를 가른 것과 같은 이유다.
   */
  REACTIVATE,

  /** 관리자 권한 부여 (2-2 §2-2-5). */
  GRANT_ADMIN,

  /**
   * 관리자 권한 회수 (2-2 §2-2-5).
   *
   * <p>부여와 <b>가른다.</b> 하나로 뭉치면 이력을 읽어도 어느 방향인지 알 수 없다 — {@link #SUSPEND}와 {@link #ACTIVATE}를 가른 것과
   * 같은 이유다.
   */
  REVOKE_ADMIN
}
