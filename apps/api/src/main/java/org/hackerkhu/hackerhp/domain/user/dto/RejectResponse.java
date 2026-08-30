package org.hackerkhu.hackerhp.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 일괄 거부 결과 (spec 3-2 §3-2-6).
 *
 * <p><b>일부가 실패해도 {@code 200}이다.</b> 실패는 예외가 아니라 결과로 돌려준다 — 한 건 때문에 되돌리면 <b>성공한 거부까지 사라진다</b>
 * ({@link ApproveResponse}와 같은 규칙이다).
 */
@Schema(description = "일괄 거부 결과")
public record RejectResponse(
    @Schema(description = "미승인 상태로 되돌린 계정의 id. 이미 미신청 상태였던 멱등 성공도 포함한다") List<Long> rejected,
    @Schema(description = "미승인 상태로 되돌리지 못한 계정과 그 사유") List<Failure> failed) {

  @Schema(description = "거부하지 못한 한 건")
  public record Failure(Long userId, Reason reason) {}

  /** 거부가 거절되는 사유. */
  @Schema(description = "거부 실패 사유")
  public enum Reason {
    /** 그 id의 계정이 없다. 목록을 우회해 직접 호출한 경로다. */
    NOT_FOUND,

    /**
     * {@code PENDING}이 아니다.
     *
     * <p><b>이 경로로 이용 중인 회원의 신청 정보를 초기화할 수 없다.</b> 회원 제거·정지는 별도 규칙을 따른다 (2-2 §2-2-4).
     */
    NOT_PENDING
  }
}
