package org.hackerkhu.hackerhp.domain.user.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 선택 회원 일괄 상태 변경 요청 (spec 3-2 §3-2-6, #313). */
@Schema(description = "선택 회원 일괄 상태 변경 요청")
public record BulkStatusChangeRequest(
    @ArraySchema(
            arraySchema = @Schema(description = "상태를 바꿀 회원 id. 원본 배열 최대 100개"),
            schema = @Schema(minimum = "1"),
            minItems = 1,
            maxItems = 100)
        @NotEmpty(message = "상태를 바꿀 회원을 선택해 주세요.")
        @Size(min = 1, max = 100, message = "상태를 바꿀 회원은 1명 이상 100명 이하여야 합니다.")
        List<@NotNull(message = "상태를 바꿀 회원을 선택해 주세요.") @Positive(message = "회원 id는 양수여야 합니다.") Long>
            userIds,
    @Schema(
            description = "목표 상태",
            allowableValues = {"ACTIVE", "SUSPENDED"})
        @NotNull(message = "바꿀 상태를 지정해 주세요.")
        TargetStatus status) {

  /** 일괄 상태 변경이 받는 목표는 활성화와 정지 둘뿐이다. */
  public enum TargetStatus {
    ACTIVE,
    SUSPENDED
  }
}
