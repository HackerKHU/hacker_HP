package org.hackerkhu.hackerhp.domain.post.dto;

import java.time.Instant;
import org.hackerkhu.hackerhp.domain.post.entity.Post;

/**
 * 목록의 한 행 (spec 3-2 §3-2-5).
 *
 * <p><b>본문을 담지 않는다</b> (MUST). 상세에서만 준다 — 본문 상한이 10,000자라 20건이면 그것만으로 응답이 200KB가 된다. 자료 목록이 파일을 개수만
 * 담는 것과 같은 판단이다.
 */
public record PostSummaryResponse(Long id, String title, PostAuthor author, Instant createdAt) {

  public static PostSummaryResponse of(Post post, PostAuthor author) {
    return new PostSummaryResponse(post.getId(), post.getTitle(), author, post.getCreatedAt());
  }
}
