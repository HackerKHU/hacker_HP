package org.hackerkhu.hackerhp.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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

  /** 구글 계정 식별자(ID 토큰의 sub). 이메일이 아니라 이 값이 계정의 신원 키다. */
  @Column(name = "google_sub", nullable = false, unique = true)
  private String googleSub;

  @Column(nullable = false, unique = true)
  private String email;

  /** 구글이 주지 않으므로 계정 생성 시점에는 비어 있다. 신청서 제출 시 채워진다. */
  @Column(name = "student_no", unique = true)
  private String studentNo;

  @Column(nullable = false)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Role role;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status;

  /** 계정 생성일시(첫 구글 로그인). "가입 신청일"은 {@link #appliedAt}이다. */
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /** 신청서 제출일시. 승인 대상인지를 가르는 기준이다 — spec/3-2 §3-2-2. */
  @Column(name = "applied_at")
  private Instant appliedAt;

  @Column(name = "approved_at")
  private Instant approvedAt;

  /**
   * 낙관적 잠금 — spec/3-1-DESIGN-ARCHITECTURE.md §3-1-4의 직렬화 요구.
   *
   * <p>아래 상태 검사들은 <b>각 트랜잭션이 읽어둔 인메모리 값</b>만 본다. 신청서 제출과 관리자 승인이 같은 행을 동시에 읽으면 둘 다 통과하고, 나중에
   * flush되는 쪽이 앞의 변경을 덮어쓴다 — 승인된 계정의 학번이 승인 뒤에 바뀐다. 버전 충돌로 한쪽을 실패시켜야 막을 수 있다.
   */
  @Version
  @Column(nullable = false)
  private Long version;

  protected User() {}

  private User(String googleSub, String email, String name, Role role, Status status) {
    this.googleSub = googleSub;
    this.email = email;
    this.name = name;
    this.role = role;
    this.status = status;
    this.createdAt = Instant.now();
  }

  /**
   * ① 첫 구글 로그인. USER/PENDING으로 생성한다 — spec/3-1-DESIGN-ARCHITECTURE.md §3-1-4.
   *
   * <p>학번은 구글이 주지 않으므로 여기서 채우지 않는다. {@link #submitApplication}이 채운다.
   */
  public static User createFromGoogle(String googleSub, String email, String name) {
    return new User(googleSub, email, name, Role.USER, Status.PENDING);
  }

  /**
   * ② 신청서 제출. 승인 심사에 필요한 학번과 이름을 받는다 — §3-1-4.
   *
   * <p>승인 전까지 다시 제출해 고칠 수 있다. <b>ACTIVE는 이 경로로 학번을 바꿀 수 없다</b> — 관리자가 심사한 내용과 저장된 내용이 달라진다.
   */
  public void submitApplication(String studentNo, String name) {
    if (this.status != Status.PENDING) {
      throw new IllegalStateException("PENDING 상태에서만 신청서를 낼 수 있습니다: " + this.status);
    }
    String trimmedStudentNo = requireNotBlank(studentNo, "학번");
    String trimmedName = requireNotBlank(name, "이름");

    this.studentNo = trimmedStudentNo;
    this.name = trimmedName;
    this.appliedAt = Instant.now();
  }

  /**
   * 공백은 신청서로 인정하지 않는다 — spec/3-2 §3-2-3, T-52.
   *
   * <p>DB의 {@code NOT NULL}·{@code UNIQUE}는 빈 문자열을 거르지 않는다. 여기서 막지 않으면 {@code ""}를 낸 계정이 {@code
   * applied_at}을 얻어 {@link #approve()}의 신청 여부 검사를 통과하고, 식별 정보가 없는 계정이 승인된다.
   *
   * <p><b>상태를 바꾸기 전에 검사한다.</b> 저장한 뒤 거부하면 계약과 달리 {@code applied_at}이 남는다.
   */
  private static String requireNotBlank(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + "을(를) 입력해주세요.");
    }
    return value.trim();
  }

  /**
   * 구글이 준 이메일이 저장된 값과 다르면 갱신한다 (MUST) — spec/3-2 §3-2-2.
   *
   * <p>갱신하지 않으면 회원 목록·검색과 {@code GET /auth/me}에 옛 주소가 남는다.
   */
  public void updateEmail(String email) {
    this.email = email;
  }

  /**
   * ③ 가입 승인. PENDING → ACTIVE, 승인일시를 기록한다.
   *
   * <p>신청서를 낸 계정만 승인할 수 있다 — spec/3-2 §3-2-6. 이 검사가 없으면 학번이 비어 있는 ACTIVE 계정이 만들어지는데, 신청 API는
   * PENDING 전용이라 나중에 채울 방법이 없다.
   */
  public void approve() {
    if (this.appliedAt == null) {
      throw new IllegalStateException("신청서를 제출한 계정만 승인할 수 있습니다.");
    }
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

  public String getGoogleSub() {
    return googleSub;
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

  public Role getRole() {
    return role;
  }

  public Status getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getAppliedAt() {
    return appliedAt;
  }

  public Instant getApprovedAt() {
    return approvedAt;
  }
}
