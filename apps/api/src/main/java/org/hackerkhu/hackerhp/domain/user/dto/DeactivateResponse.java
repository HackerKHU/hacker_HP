package org.hackerkhu.hackerhp.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 학기 전환 — 일괄 비활성화 결과 (spec 3-2 §3-2-6, #230).
 *
 * <p><b>실패 배열이 없다</b> (MUST). 승인·거부와 다른 점이다 — 대상을 서버가 골랐으므로 <i>"이 사람은 대상이 아니었다"</i>가 성립하지 않는다. 조건에
 * 맞으면 전부 바뀐다.
 *
 * <p><b>실제로 바뀐 id만 담는다.</b> 이미 {@code INACTIVE}였던 사람은 들어가지 않는다 — 잘못 눌렀을 때 이 배열을 그대로 복구에 넣는 것이 되돌리는
 * 길인데, 남이 이미 내린 사람이 섞이면 <b>되돌리기가 그 사람까지 올린다.</b>
 *
 * <p><b>이 배열이 유일한 기록은 아니다.</b> 응답은 잃을 수 있으므로 되돌릴 근거는 {@code users.deactivated_at}에 따로 남는다.
 */
@Schema(description = "일괄 비활성화 결과")
public record DeactivateResponse(
    @Schema(description = "실제로 `INACTIVE`가 된 계정의 id. 이미 비활동이던 사람은 없다") List<Long> deactivated) {}
