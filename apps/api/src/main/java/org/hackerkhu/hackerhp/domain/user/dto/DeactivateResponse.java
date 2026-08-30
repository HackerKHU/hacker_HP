package org.hackerkhu.hackerhp.domain.user.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 학기 전환 — 일괄 비활성화 결과 (spec 3-2 §3-2-6, #230).
 *
 * <p>전원 경로에는 실패 배열이 없고, 선택 경로만 대상별 실패를 돌려준다. {@link JsonInclude.Include#NON_NULL}로 전원 경로의 JSON을 기존
 * {@code {"deactivated": [...]}}와 정확히 같게 유지한다.
 *
 * <p><b>실제로 바뀐 id만 담는다.</b> 이미 {@code INACTIVE}였던 사람은 들어가지 않는다 — 잘못 눌렀을 때 이 배열을 그대로 복구에 넣는 것이 되돌리는
 * 길인데, 남이 이미 내린 사람이 섞이면 <b>되돌리기가 그 사람까지 올린다.</b>
 *
 * <p><b>이 배열이 유일한 기록은 아니다.</b> 응답은 잃을 수 있으므로 되돌릴 근거는 {@code users.deactivated_at}에 따로 남는다.
 */
@Schema(description = "일괄 비활성화 결과")
public record DeactivateResponse(
    @Schema(description = "실제로 `INACTIVE`가 된 계정의 id. 이미 비활동이던 사람은 없다") List<Long> deactivated,
    @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "선택 경로에서 비활성화하지 못한 계정과 사유. 전원 경로에서는 필드 자체가 없다")
        List<Failure> failed) {

  /** 기존 전원 경로의 응답 모양을 유지한다. */
  public DeactivateResponse(List<Long> deactivated) {
    this(deactivated, null);
  }

  @Schema(name = "DeactivateFailure", description = "선택 비활성화에 실패한 한 건")
  public record Failure(
      Long userId,
      @Schema(
              description = "선택 비활성화 실패 사유",
              allowableValues = {"NOT_FOUND", "NOT_ACTIVE_USER"})
          Reason reason) {}

  @Schema(name = "DeactivateFailureReason", description = "선택 비활성화 실패 사유")
  public enum Reason {
    /** 그 id의 계정이 없다. */
    NOT_FOUND,
    /** 선택 대상인 {@code ACTIVE}/{@code SUSPENDED USER}가 아니다. 관리자·대기·비활동 계정을 보호한다. */
    NOT_ACTIVE_USER
  }
}
