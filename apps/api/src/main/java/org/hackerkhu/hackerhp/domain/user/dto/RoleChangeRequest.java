package org.hackerkhu.hackerhp.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.hackerkhu.hackerhp.domain.user.entity.Role;

/**
 * {@code PATCH /admin/users/{id}/role}의 본문 (spec 2-2 §2-2-5, 3-2 §3-2-6).
 *
 * <p><b>토글이 아니라 원하는 권한을 말한다.</b> 상태 변경({@link StatusChangeRequest})과 같은 모양이다 — 뒤집는 방식은 화면이 들고 있는 값이
 * 낡았을 때 <b>의도와 반대로 바꾼다.</b>
 */
public record RoleChangeRequest(
    @Schema(description = "바꿀 권한") @NotNull(message = "바꿀 권한을 지정해 주세요.") Role role) {}
