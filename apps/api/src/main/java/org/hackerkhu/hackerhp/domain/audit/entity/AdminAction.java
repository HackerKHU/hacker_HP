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
  PROMOTE_ADMIN
}
