package org.hackerkhu.hackerhp.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /auth/application} 요청 (spec 3-2 §3-2-3).
 *
 * <p><b>둘 다 공백이 아니어야 한다</b> (MUST). PostgreSQL의 {@code NOT NULL}·{@code UNIQUE}는 빈 문자열을 거부하지 않으므로
 * 서버가 막아야 한다. 통과시키면 식별 정보가 없는 계정이 {@code applied_at}을 얻어 승인 대상이 되고, 관리자 부트스트랩까지 지나간다 (T-52).
 *
 * <p>메시지는 화면에 그대로 뜬다. 어떤 값을 고쳐야 하는지 알려준다 (T-108).
 */
public record ApplicationRequest(
    @NotBlank(message = "학번을 입력해 주세요.") String studentNo,
    @NotBlank(message = "이름을 입력해 주세요.") String name) {}
