package org.hackerkhu.hackerhp.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 학기 전환 — 일괄 복구 결과 (spec 3-2 §3-2-6, #230).
 *
 * <p>모양과 규약은 일괄 승인과 같다 — 일부가 실패해도 전체는 {@code 200}이고, <b>실패가 성공을 되돌리지 않는다.</b> 한 건 때문에 트랜잭션이 되돌아가면
 * 관리자가 20명을 골랐을 때 한 명이 이미 활동 중이라는 이유로 <b>아무도 복구되지 않는다.</b>
 */
@Schema(description = "일괄 복구 결과. 일부가 실패해도 상태 코드는 200이다")
public record ReactivateResponse(
    @Schema(description = "`ACTIVE`로 되돌린 계정의 id") List<Long> reactivated,
    @Schema(description = "복구하지 못한 계정과 그 사유") List<Failure> failed) {

  @Schema(description = "복구하지 못한 한 건")
  public record Failure(Long userId, Reason reason) {}

  @Schema(description = "복구 실패 사유")
  public enum Reason {
    /** 그 id의 계정이 없다. */
    NOT_FOUND,
    /**
     * {@code INACTIVE}가 아니다.
     *
     * <p><b>정지된 계정을 이 경로로 풀 수 없다</b> — 풀린다면 학기 복구가 정지 해제를 겸하게 되어, 관리자가 명단을 붙여넣는 것만으로 정지가 사라진다.
     */
    NOT_INACTIVE
  }
}
