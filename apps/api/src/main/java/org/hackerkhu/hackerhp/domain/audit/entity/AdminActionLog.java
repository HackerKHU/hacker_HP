package org.hackerkhu.hackerhp.domain.audit.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * spec/3-2-DESIGN-CONTRACT.md §3-2-2 {@code admin_actions} (#143).
 *
 * <p><b>{@code actor_id}·{@code target_id}는 {@code users}를 가리키지만 연관관계로 매핑하지 않는다.</b> DB에 FK가 없기
 * 때문이고, FK가 없는 이유는 <b>이력이 현재 상태에 종속되면 안 되기 때문이다</b> — 회원을 지웠다고 "누구를 정지했는지"가 사라지면 이력의 존재 이유가 없다
 * ({@code V5__admin_actions.sql}).
 *
 * <p>그래서 여기서 꺼낼 수 있는 것은 <b>숫자 id뿐이다.</b> 이름·이메일은 계정을 지운 뒤에도 남기지 않는다 (2-2 §2-2-4).
 *
 * <p>고쳐 쓰지 않는다. 이력은 <b>일어난 일</b>이라 나중에 달라질 수 없다.
 *
 * <p><b>시각을 받아서 담는다.</b> 여기서 {@code Instant.now()}를 부르면 조작이 아니라 <b>기록이 일어난 때</b>가 남는다 — 기록은 커밋과 세션
 * 반영까지 끝난 뒤라, 같은 회원을 두 관리자가 잇따라 건드리면 <b>실제와 반대 순서로 남을 수 있다.</b>
 */
@Entity
@Table(name = "admin_actions")
public class AdminActionLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "actor_id", nullable = false, updatable = false)
  private Long actorId;

  @Column(name = "target_id", nullable = false, updatable = false)
  private Long targetId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private AdminAction action;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected AdminActionLog() {}

  private AdminActionLog(Long actorId, Long targetId, AdminAction action, Instant createdAt) {
    this.actorId = actorId;
    this.targetId = targetId;
    this.action = action;
    this.createdAt = createdAt;
  }

  /**
   * @param occurredAt <b>조작이 일어난 때.</b> 계정 행을 잠근 채 잡은 값이어야 한다 — 그 잠금이 같은 대상에 대한 순서를 세워 주므로, 나중에 잠근
   *     조작은 반드시 더 나중 시각을 갖는다
   */
  public static AdminActionLog of(
      Long actorId, Long targetId, AdminAction action, Instant occurredAt) {
    return new AdminActionLog(actorId, targetId, action, occurredAt);
  }

  public Long getId() {
    return id;
  }

  public Long getActorId() {
    return actorId;
  }

  public Long getTargetId() {
    return targetId;
  }

  public AdminAction getAction() {
    return action;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
