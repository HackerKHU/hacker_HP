package org.hackerkhu.hackerhp.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 일괄 승인 결과 (spec 3-2 §3-2-6).
 *
 * <p><b>건수 필드를 따로 두지 않는다.</b> 배열 길이가 곧 건수다 — {@code successCount}를 따로 두면 배열과 어긋날 자리가 생긴다.
 *
 * <p><b>실패에 {@code userId}를 담는다.</b> 건수만으로는 운영자가 조치할 수 없다 — "1명 실패"로는 누구에게 신청서를 내라고 안내할지 모른다. 이름은
 * 화면이 이미 들고 있으므로 id면 충분하다.
 *
 * <p>일부가 실패해도 전체는 {@code 200}이다. 요청 자체의 실패가 아니다 — 권한 없음 같은 실패는 §3-2-7의 오류 규약을 그대로 쓴다.
 */
@Schema(description = "일괄 승인 결과. 일부가 실패해도 상태 코드는 200이다")
public record ApproveResponse(
    @Schema(description = "승인된 계정의 id") List<Long> approved,
    @Schema(description = "승인하지 못한 계정과 그 사유") List<Failure> failed) {

  @Schema(description = "승인하지 못한 한 건")
  public record Failure(Long userId, Reason reason) {}

  /**
   * 승인이 거절되는 사유.
   *
   * <p>계약이 명시한 것은 {@link #NOT_APPLIED} 하나지만 <b>실제로 실패하는 경우는 셋이다.</b> 전부 하나로 뭉개면 이미 승인된 사람에게 "신청서를
   * 내지 않았다"고 안내하게 된다 — 거짓말이다.
   */
  @Schema(description = "승인 실패 사유")
  public enum Reason {
    /** 그 id의 계정이 없다. 목록을 우회해 직접 호출한 경로다 (T-49). */
    NOT_FOUND,
    /** 이미 승인됐거나 정지된 계정이다. 승인 대상은 {@code PENDING}뿐이다. */
    NOT_PENDING,
    /**
     * 구글 로그인만 하고 신청서를 내지 않았다 (3-2 §3-2-6 MUST).
     *
     * <p>승인하면 <b>학번이 빈 {@code ACTIVE}가 만들어지는데</b> 신청 API는 {@code PENDING} 전용이라 나중에 채울 방법이 없다.
     */
    NOT_APPLIED
  }
}
