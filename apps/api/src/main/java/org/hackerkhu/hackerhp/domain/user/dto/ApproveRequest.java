package org.hackerkhu.hackerhp.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 일괄 승인 요청 (spec 3-2 §3-2-6).
 *
 * <p>가입 신청은 계속 쌓이므로 <b>한 건씩 처리하는 방식으로는 운영이 안 된다</b> (2-2 §2-2-2). 화면은 체크박스로 여러 명을 골라 한 번에 보낸다.
 *
 * @param userIds 승인할 계정의 id. 상한은 회원 목록의 페이지 크기와 같다 — 화면이 한 번에 고를 수 있는 최대가 "현재 페이지 전부"이기 때문이다 (T-75)
 */
@Schema(description = "일괄 승인 요청")
public record ApproveRequest(
    @Schema(description = "승인할 계정의 id. 최대 100개")
        @NotEmpty(message = "승인할 회원을 선택해 주세요.")
        @Size(max = 100, message = "한 번에 100명까지 승인할 수 있습니다.")
        List<@NotNull(message = "승인할 회원을 선택해 주세요.") Long> userIds) {

  /**
   * 같은 id가 두 번 오면 한 번만 센다.
   *
   * <p>거르지 않으면 <b>응답의 건수가 부풀려진다</b> — 화면은 배열 길이를 그대로 "N명을 승인했습니다"로 읽는다 (§3-2-9). 행 승인과 전체 선택이 겹치는
   * 경로에서 실제로 나올 수 있다.
   */
  public ApproveRequest {
    userIds = userIds == null ? null : userIds.stream().distinct().toList();
  }
}
