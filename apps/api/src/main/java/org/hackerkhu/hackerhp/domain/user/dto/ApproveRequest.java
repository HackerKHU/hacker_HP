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
 * @param userIds 승인할 계정의 id. 상한은 회원 목록의 페이지 크기와 같다 — 화면이 한 번에 고를 수 있는 최대가 "현재 페이지 전부"이기 때문이다
 *     (T-75). <b>중복은 서비스가 걸러낸다</b>
 */
@Schema(description = "일괄 승인 요청")
public record ApproveRequest(
    @Schema(description = "승인할 계정의 id. 최대 100개")
        @NotEmpty(message = "승인할 회원을 선택해 주세요.")
        @Size(max = 100, message = "한 번에 100명까지 승인할 수 있습니다.")
        List<@NotNull(message = "승인할 회원을 선택해 주세요.") Long> userIds) {

  /*
   * 여기서 중복을 걸러내지 않는다.
   *
   * Bean Validation은 역직렬화와 compact constructor가 끝난 뒤에 돈다. 여기서 distinct()를
   * 하면 @Size(max = 100)이 원본이 아니라 줄어든 목록을 보게 되어, 같은 id를 101번 담은
   * 요청이 한 건으로 줄어 상한을 그냥 통과한다. 상한은 원본 배열에 걸어야 뜻이 있다.
   *
   * 중복 제거는 검증을 지난 뒤 서비스가 한다.
   */
}
