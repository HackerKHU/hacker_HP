package org.hackerkhu.hackerhp.domain.user.entity;

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
 * spec/3-2-DESIGN-CONTRACT.md §3-2-2 users. Role/Status는 별도 필드로 분리한다 —
 * spec/3-1-DESIGN-ARCHITECTURE.md §3-1-2.
 */
@Entity
@Table(name = "users")
public class User {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true)
  private String email;

  @Column(name = "student_no", nullable = false, unique = true)
  private String studentNo;

  @Column(nullable = false)
  private String name;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Role role;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "approved_at")
  private Instant approvedAt;

  protected User() {}

  private User(
      String email,
      String studentNo,
      String name,
      String passwordHash,
      Role role,
      Status status,
      Instant createdAt,
      Instant approvedAt) {
    this.email = email;
    this.studentNo = studentNo;
    this.name = name;
    this.passwordHash = passwordHash;
    this.role = role;
    this.status = status;
    this.createdAt = createdAt;
    this.approvedAt = approvedAt;
  }

  /** AUTH-01 가입 신청. USER/PENDING으로 생성한다 — spec/3-1-DESIGN-ARCHITECTURE.md §3-1-4. */
  public static User applyForMembership(
      String email, String studentNo, String name, String passwordHash) {
    return new User(
        email, studentNo, name, passwordHash, Role.USER, Status.PENDING, Instant.now(), null);
  }

  /** ADM-02 가입 승인. PENDING → ACTIVE, 승인일시를 기록한다. */
  public void approve() {
    this.status = Status.ACTIVE;
    this.approvedAt = Instant.now();
  }

  /** ADM-03 회원 상태 변경 — 정지. */
  public void suspend() {
    this.status = Status.SUSPENDED;
  }

  /** ADM-03 회원 상태 변경 — 정지 해제. SUSPENDED 상태에서만 허용한다. */
  public void reactivate() {
    if (this.status != Status.SUSPENDED) {
      throw new IllegalStateException("SUSPENDED 상태에서만 정지 해제할 수 있습니다: " + this.status);
    }
    this.status = Status.ACTIVE;
  }

  /** ADM-05 권한 부여. */
  public void promoteToAdmin() {
    this.role = Role.ADMIN;
  }

  /** ADM-05 권한 회수. */
  public void demoteToUser() {
    this.role = Role.USER;
  }

  public Long getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getStudentNo() {
    return studentNo;
  }

  public String getName() {
    return name;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public Role getRole() {
    return role;
  }

  public Status getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getApprovedAt() {
    return approvedAt;
  }
}
