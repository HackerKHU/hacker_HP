package org.hackerkhu.hackerhp.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.hackerkhu.hackerhp.domain.user.dto.BulkStatusChangeRequest.TargetStatus;

/** 선택 회원 일괄 상태 변경 결과 (spec 3-2 §3-2-6, #313). */
@Schema(description = "선택 회원 일괄 상태 변경 결과")
public record BulkStatusChangeResponse(
    @Schema(description = "요청한 목표 상태", requiredMode = Schema.RequiredMode.REQUIRED)
        TargetStatus targetStatus,
    @Schema(
            description = "목표 상태가 됐거나 이미 목표 상태였던 회원 id",
            requiredMode = Schema.RequiredMode.REQUIRED)
        List<Long> processed,
    @Schema(description = "처리하지 못한 회원과 사유", requiredMode = Schema.RequiredMode.REQUIRED)
        List<Failure> failed) {

  @Schema(name = "BulkStatusChangeFailure", description = "일괄 상태 변경에 실패한 한 건")
  public record Failure(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long userId,
      @Schema(
              description = "항목별 실패 사유",
              requiredMode = Schema.RequiredMode.REQUIRED,
              allowableValues = {
                "NOT_FOUND",
                "NOT_APPLIED",
                "PENDING_NOT_ALLOWED",
                "ADMIN_SUSPEND_REQUIRES_ROLE_REVOCATION"
              })
          Reason reason) {}

  @Schema(name = "BulkStatusChangeFailureReason", description = "일괄 상태 변경 항목별 실패 사유")
  public enum Reason {
    NOT_FOUND,
    NOT_APPLIED,
    PENDING_NOT_ALLOWED,
    ADMIN_SUSPEND_REQUIRES_ROLE_REVOCATION
  }
}
