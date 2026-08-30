package org.hackerkhu.hackerhp.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 학기 전환 — 일괄 비활성화 요청 (spec 3-2 §3-2-6, #295).
 *
 * <p>요청 자체와 {@link #userIds()}가 모두 선택 사항이다. 본문 없음·필드 누락·{@code null}·빈 배열은 기존 계약대로 {@code ACTIVE}
 * 일반 부원 전원을 내리고, 값이 하나라도 있을 때만 선택 경로다.
 *
 * @param userIds 비활성화할 계정의 id. 비어 있으면 전원, 값이 있으면 최대 100개
 */
@Schema(description = "일괄 비활성화 요청. 생략하거나 빈 값이면 활성 일반 부원 전원이 대상이다")
public record DeactivateRequest(
    @Schema(description = "선택 비활성화할 계정의 id. 생략·null·빈 배열이면 전원, 값이 있으면 최대 100개")
        @Size(max = 100, message = "한 번에 100명까지 비활성화할 수 있습니다.")
        List<@NotNull(message = "비활성화할 회원을 선택해 주세요.") @Positive(message = "회원 id는 양수여야 합니다.") Long>
            userIds) {}
