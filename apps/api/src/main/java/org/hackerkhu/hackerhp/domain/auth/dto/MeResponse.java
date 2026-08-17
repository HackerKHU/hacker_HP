package org.hackerkhu.hackerhp.domain.auth.dto;

import java.time.Instant;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;

/**
 * {@code GET /auth/me} 응답. 형태는 {@code apps/web/src/api/types.ts}의 {@code User}가 원본이다.
 *
 * <p><b>신청 여부를 알려주는 별도 boolean을 두지 않는다</b> (spec 3-2 §3-2-3 MUST). {@code appliedAt}에 값이 있으면 제출한
 * 것이다. 같은 사실을 두 필드로 말하면 어긋나는 자리가 생기고, 어긋나면 화면이 신청 폼과 대기 안내 중 틀린 쪽을 고른다.
 *
 * <p>{@code version}은 담지 않는다. 동시성 제어용 컬럼이지 사용자에게 보여줄 값이 아니다.
 */
public record MeResponse(
    Long id,
    String email,
    String studentNo,
    String name,
    String department,
    Role role,
    Status status,
    Instant createdAt,
    Instant appliedAt,
    Instant approvedAt) {

  public static MeResponse from(User user) {
    return new MeResponse(
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
