package org.hackerkhu.hackerhp.domain.post.dto;

import jakarta.validation.constraints.NotBlank;
import org.hackerkhu.hackerhp.global.validation.CodePointSize;

/**
 * 댓글 등록·수정 (spec 3-2 §3-2-6). {@code PATCH}도 같은 모양을 쓴다 — 수정은 <b>보낸 것으로 통째로 바꾸므로</b> 등록과 받는 값이
 * 같다({@code PostCreateRequest}와 같은 판단).
 *
 * <p><b>작성자를 받지 않는다</b> (MUST). 인증 주체의 id로만 정한다 — 게시글 등록과 같은 규칙이다.
 *
 * <p><b>상한은 {@code @Size}가 아니라 {@link CodePointSize}로 센다</b> — 이유는 {@code PostCreateRequest}와 같다.
 * {@code post_comments.content}에 {@code CHECK (LENGTH(content) <= 2000)}이 걸려 있고 PostgreSQL은 코드포인트를
 * 세므로, UTF-16 길이를 세는 {@code @Size}를 쓰면 이모지가 두 글자로 잡혀 DB가 받아 줄 댓글을 API가 먼저 거절한다.
 */
public record PostCommentRequest(
    @NotBlank(message = "내용을 입력해 주세요.")
        @CodePointSize(max = 2000, message = "내용은 2,000자까지 쓸 수 있습니다.")
        String content) {}
