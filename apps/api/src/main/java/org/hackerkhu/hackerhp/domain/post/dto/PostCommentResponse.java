package org.hackerkhu.hackerhp.domain.post.dto;

import java.time.Instant;
import org.hackerkhu.hackerhp.domain.post.entity.PostComment;

/**
 * 댓글 (spec 3-2 §3-2-6).
 *
 * <p><b>본문은 받은 그대로 나간다</b> (3-3 결정 23 D1, 게시글과 같은 판단). 서버가 정화하거나 변형하지 않는다.
 *
 * <p>댓글은 게시글과 달리 목록·상세를 나누지 않는다 — 본문 상한이 2,000자라 목록에 그대로 담아도 게시글 목록이 겪는 응답 크기 문제가 생기지 않는다.
 */
public record PostCommentResponse(
    Long id, String content, PostAuthor author, Instant createdAt, Instant updatedAt) {

  public static PostCommentResponse of(PostComment comment, PostAuthor author) {
    return new PostCommentResponse(
        comment.getId(),
        comment.getContent(),
        author,
        comment.getCreatedAt(),
        comment.getUpdatedAt());
  }
}
