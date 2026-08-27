package org.hackerkhu.hackerhp.domain.post.dto;

import jakarta.validation.constraints.NotBlank;
import org.hackerkhu.hackerhp.global.validation.CodePointSize;

/**
 * 글 등록·수정 (spec 3-2 §3-2-5). {@code PATCH}(#256)도 같은 모양을 쓴다 — 수정은 <b>보낸 것으로 통째로 바꾸므로</b> 등록과 받는 값이
 * 같다({@code NoticeRequest}와 같은 판단).
 *
 * <p><b>작성자를 받지 않는다</b> (MUST). 인증 주체의 id로만 정한다 — 받으면 다른 사람 이름으로 글을 올릴 수 있다. 공지 등록·자료 등록과 같은 규칙이다.
 *
 * <p><b>{@code @NotBlank}는 상한과 별개로 필요하다.</b> DB의 {@code NOT NULL}은 빈 문자열과 공백 문자열을 막지 않는다 — 이것이 빠지면
 * 내용 없는 글이 저장된다 (T-325).
 *
 * <p><b>상한은 {@code @Size}가 아니라 {@link CodePointSize}로 센다</b> (#236 리뷰). 본문에는 {@code CHECK
 * (LENGTH(content) <= 10000)}이 걸려 있고 PostgreSQL은 코드포인트를 세므로, UTF-16 길이를 세는 {@code @Size}를 쓰면 이모지가 두
 * 글자로 잡혀 DB가 받아 줄 글을 API가 먼저 거절한다 (T-332·T-333).
 */
public record PostCreateRequest(
    @NotBlank(message = "제목을 입력해 주세요.") @CodePointSize(max = 200, message = "제목은 200자까지 쓸 수 있습니다.")
        String title,
    @NotBlank(message = "내용을 입력해 주세요.")
        @CodePointSize(max = 10000, message = "내용은 10,000자까지 쓸 수 있습니다.")
        String content) {}
