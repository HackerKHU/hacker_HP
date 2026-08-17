package org.hackerkhu.hackerhp.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;

/**
 * {@code GET /admin/users}의 한 행. 표시 항목은 spec 2-2 §2-2-1이 원본이다 — 이름, 학번, 이메일, Role, Status, 가입 신청일,
 * 승인일.
 *
 * <p><b>{@code MeResponse}와 필드가 같지만 공유하지 않는다.</b> 지금 같을 뿐 둘은 갈라진다 — 관리자 목록에는 운영에 필요한 값이 붙고({@code
 * GET /auth/me}에는 보일 이유가 없다), 내 정보에는 개인 설정이 붙는다. 공유해 두면 한쪽의 요구가 다른 쪽 응답을 조용히 바꾼다.
 *
 * <p><b>엔티티를 그대로 내보내지 않는 이유가 여기 있다.</b> {@code google_sub}는 구글 계정 식별자이고 {@code version}은 동시성 제어용
 * 컬럼이다. 둘 다 관리자 화면이 쓸 값이 아니고, 나가면 되돌릴 수 없다.
 */
@Schema(description = "회원 목록의 한 행")
public record AdminUserResponse(
    Long id,
    String email,
    @Schema(description = "신청서를 내기 전에는 비어 있다. 구글이 학번을 주지 않는다") String studentNo,
    String name,
    @Schema(description = "신청서를 내기 전이거나, 이 필드가 생기기 전에 승인된 회원이면 비어 있다") String department,
    Role role,
    Status status,
    @Schema(description = "계정 생성 시각(첫 구글 로그인). **가입 신청일이 아니다**") Instant createdAt,
    @Schema(description = "가입 신청일 — 신청서를 제출한 시각. 화면이 \"가입 신청일\"로 부르는 값이다") Instant appliedAt,
    Instant approvedAt) {

  public static AdminUserResponse from(User user) {
    return new AdminUserResponse(
        user.getId(),
        user.getEmail(),
        user.getStudentNo(),
        user.getName(),
        user.getDepartment(),
        user.getRole(),
        user.getStatus(),
        user.getCreatedAt(),
        user.getAppliedAt(),
        user.getApprovedAt());
  }
}
