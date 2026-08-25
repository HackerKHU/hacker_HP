package org.hackerkhu.hackerhp.domain.post.dto;

import java.time.Instant;
import org.hackerkhu.hackerhp.domain.post.entity.Post;

/**
 * 상세 (spec 3-2 §3-2-5). 목록에 {@code content}와 {@code updatedAt}이 더해진다.
 *
 * <p><b>본문은 받은 그대로 나간다</b> (MUST, 3-3 결정 16). 서버가 정화하거나 변형하지 않는다 — 이스케이프는 화면이 텍스트 노드로 그리면서 한다.
 */
public record PostDetailResponse(
    Long id,
    String title,
    String content,
    PostAuthor author,
    Instant createdAt,
    Instant updatedAt) {

  public static PostDetailResponse of(Post post, PostAuthor author) {
    return new PostDetailResponse(
        post.getId(),
        post.getTitle(),
        post.getContent(),
        author,
        post.getCreatedAt(),
        post.getUpdatedAt());
  }
}
