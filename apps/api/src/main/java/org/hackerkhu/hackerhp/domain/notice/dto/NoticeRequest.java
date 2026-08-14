package org.hackerkhu.hackerhp.domain.notice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /notices}, {@code PATCH /notices/{id}} 요청 (spec 3-2 §3-2-5).
 *
 * <p>공백만으로는 통과하지 않는다 — {@code title varchar(200) not null}, {@code content text not null}(3-2
 * §3-2-2)은 빈 문자열을 거부하지 않으므로 여기서 막는다.
 */
public record NoticeRequest(
    @NotBlank(message = "제목을 입력해 주세요.") @Size(max = 200, message = "제목이 너무 깁니다.") String title,
    @NotBlank(message = "내용을 입력해 주세요.") String content) {}
