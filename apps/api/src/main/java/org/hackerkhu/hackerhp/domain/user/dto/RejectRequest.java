package org.hackerkhu.hackerhp.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 일괄 거부 요청 (spec 2-2 §2-2-2, 3-2 §3-2-6).
 *
 * <p>승인과 같은 화면에서 같은 체크박스로 고른다. 그래서 상한도 같다 — 한 번에 고를 수 있는 최대가 "현재 페이지 전부"다.
 *
 * <p><b>중복은 서비스가 걸러낸다.</b> 여기서 {@code distinct()}를 하면 {@code @Size}가 원본이 아니라 줄어든 목록을 보게 되어, 같은 id를
 * 101번 담은 요청이 상한을 그냥 통과한다 ({@link ApproveRequest}와 같은 이유다).
 */
@Schema(description = "일괄 거부 요청")
public record RejectRequest(
    @Schema(description = "가입 신청을 거부해 미승인 상태로 되돌릴 계정의 id. 최대 100개")
        @NotEmpty(message = "거부할 회원을 선택해 주세요.")
        @Size(max = 100, message = "한 번에 100명까지 거부할 수 있습니다.")
        List<@NotNull(message = "거부할 회원을 선택해 주세요.") Long> userIds) {}
