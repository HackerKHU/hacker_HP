package org.hackerkhu.hackerhp.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 최초 관리자 승격 요청 (spec 3-3 결정 11).
 *
 * @param token SSM의 {@code ADMIN_BOOTSTRAP_TOKEN}. 이메일만 알아서는 이 경로를 탈 수 없게 하는 두 번째 조건이다
 */
@Schema(description = "최초 관리자 승격 요청")
public record BootstrapRequest(
    @Schema(description = "SSM에 등록된 부트스트랩 토큰") @NotBlank(message = "토큰이 필요합니다.") String token) {}
