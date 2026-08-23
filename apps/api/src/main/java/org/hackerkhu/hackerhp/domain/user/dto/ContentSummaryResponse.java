package org.hackerkhu.hackerhp.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 제거하면 <b>무엇이 남는지</b> (spec 2-2 §2-2-4 MUST, 3-2 §3-2-6).
 *
 * <p><b>네 값을 항상 담는다</b> (MUST). {@code 0}을 빼면 화면이 "없음"과 "모름"을 가르지 못한다.
 *
 * <p>이 값은 <b>확인 창을 여는 시점의 참고치</b>이지 제거의 조건이 아니다. 그 사이 건수가 바뀌어도 제거는 그대로 진행한다 — 건수를 맞추려고 제거까지 막으면 확인
 * 창을 다시 열어도 같은 자리를 맴돌 수 있다.
 */
@Schema(description = "제거 시 남을 콘텐츠 건수")
public record ContentSummaryResponse(
    @Schema(description = "그 회원이 올린 자료") long notes,
    @Schema(description = "그 회원이 쓴 공지") long notices,
    @Schema(description = "그 회원이 올린 활동사진") long photos,
    @Schema(description = "그 회원이 쓴 게시글") long posts) {}
