package org.hackerkhu.hackerhp.domain.user.entity;

/** 계정 상태. spec/3-1-DESIGN-ARCHITECTURE.md §3-1-2 — Role과 분리해서 관리한다. */
public enum Status {
  PENDING,
  ACTIVE,
  SUSPENDED
}
