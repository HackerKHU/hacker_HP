package org.hackerkhu.hackerhp.domain.user.entity;

/** 계정 상태. spec/3-1-DESIGN-ARCHITECTURE.md §3-1-2 — Role과 분리해서 관리한다. */
public enum Status {
  PENDING,
  ACTIVE,

  /**
   * 이번 학기에 활동하지 않는 부원 (#228).
   *
   * <p><b>자료 기능 전체가 막히고 나머지는 {@link #ACTIVE}와 같다.</b> 로그인도 된다 — {@link #SUSPENDED}와 다른 점이 그것이다. 어디서
   * 막는지는 {@code AccountStatusFilter}에 있다.
   *
   * <p><b>언제나 {@code USER}다</b> (3-1 §3-1-2 MUST). 비활동 관리자는 자료를 못 보면서 남의 자료를 지울 수 있게 되므로 만들지 않는다.
   */
  INACTIVE,
  SUSPENDED
}
