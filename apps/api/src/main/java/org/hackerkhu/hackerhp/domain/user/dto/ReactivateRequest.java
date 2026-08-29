package org.hackerkhu.hackerhp.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 학기 전환 — 일괄 복구 요청 (spec 3-2 §3-2-6, #230).
 *
 * <p><b>비활성화와 모양이 다르다</b> (2-2 §2-2-3). 내리는 것은 매 학기 전원이 대상이라 고를 것이 없지만, <b>올라올 사람은 매번 다르다</b> —
 * 조건으로 전원을 올리면 비활성화가 무의미해진다.
 *
 * @param userIds 복구할 계정의 id. 상한·중복 규약은 일괄 승인과 같다 ({@link ApproveRequest})
 */
@Schema(description = "일괄 복구 요청")
public record ReactivateRequest(
    @Schema(description = "복구할 계정의 id. 최대 100개")
        @NotEmpty(message = "복구할 회원을 선택해 주세요.")
        @Size(max = 100, message = "한 번에 100명까지 복구할 수 있습니다.")
        List<@NotNull(message = "복구할 회원을 선택해 주세요.") Long> userIds) {

  /* 중복 제거는 검증을 지난 뒤 서비스가 한다 — 이유는 ApproveRequest에 적혀 있다. */
}
