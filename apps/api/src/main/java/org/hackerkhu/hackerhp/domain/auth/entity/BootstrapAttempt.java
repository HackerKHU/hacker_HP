package org.hackerkhu.hackerhp.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 관리자 승격 <b>실패</b> 시도 (spec 3-3 결정 11, #144).
 *
 * <p>성공한 조작은 {@code admin_actions}에 남는다 (#143). 둘을 섞지 않는 이유는 목적도 보존 주기도 다르기 때문이다 — 이력은 영구히 남고 여기는
 * 창이 지나면 버린다.
 */
@Entity
@Table(name = "admin_bootstrap_attempts")
public class BootstrapAttempt {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "account_id", nullable = false, updatable = false)
  private Long accountId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected BootstrapAttempt() {}

  private BootstrapAttempt(Long accountId, Instant createdAt) {
    this.accountId = accountId;
    this.createdAt = createdAt;
  }

  public static BootstrapAttempt failedAt(Long accountId, Instant createdAt) {
    return new BootstrapAttempt(accountId, createdAt);
  }

  public Long getId() {
    return id;
  }

  public Long getAccountId() {
    return accountId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
