package org.hackerkhu.hackerhp.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 회원 상태 변경 요청 (spec 2-2 §2-2-3, 3-2 §3-2-6).
 *
 * @param status 바꿀 상태
 */
@Schema(description = "회원 상태 변경 요청")
public record StatusChangeRequest(
    @Schema(description = "바꿀 상태") @NotNull(message = "바꿀 상태를 지정해 주세요.") Target status) {

  /**
   * 바꿀 수 있는 상태. <b>{@code PENDING}은 없다.</b>
   *
   * <p>계약이 정한 전이는 {@code ACTIVE} ↔ {@code SUSPENDED}뿐이다 (2-2 §2-2-3). 값을 두 개로 두면 명세에도 그 둘만 드러나고,
   * {@code "PENDING"}을 보낸 요청은 서비스에 닿기 전 역직렬화 단계에서 {@code 400}으로 끊긴다.
   *
   * <p>승인은 이 경로가 아니라 {@code POST /admin/users/approve}다 — 승인 대상은 신청서를 낸 계정으로 한정되고 승인일시도 기록해야 한다.
   */
  public enum Target {
    ACTIVE,
    SUSPENDED
  }
}
