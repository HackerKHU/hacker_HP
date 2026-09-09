package org.hackerkhu.hackerhp.domain.post.dto;

import java.time.Instant;
import org.hackerkhu.hackerhp.domain.post.entity.Post;

/**
 * 목록의 한 행 (spec 3-2 §3-2-5).
 *
 * <p><b>본문을 담지 않는다</b> (MUST). 상세에서만 준다 — 본문 상한이 10,000자라 20건이면 그것만으로 응답이 200KB가 된다. 자료 목록이 파일을 개수만
 * 담는 것과 같은 판단이다.
 *
 * <p><b>{@code likeCount}·{@code likedByMe}는 항상 함께 온다</b> (#345, 3-3 결정 26). 개수만 보이고 내가 눌렀는지 모르면
 * 화면이 좋아요 버튼을 채울지 비울지 정할 수 없다.
 *
 * <p><b>{@code commentCount}는 목록이 답한다</b> (#374, 3-3 결정 30). 댓글이 달린 글을 목록에서 가르려면 여기 있어야 한다 — 글마다 따로
 * 물으면 한 페이지에 요청이 스무 번 나가고, 숫자가 행마다 늦게 채워진다. 상세에는 담지 않는다: 그쪽은 댓글을 통째로 내려주므로 화면이 배열 길이로 안다.
 */
public record PostSummaryResponse(
    Long id,
    String title,
    PostAuthor author,
    Instant createdAt,
    long likeCount,
    boolean likedByMe,
    long commentCount) {

  public static PostSummaryResponse of(
      Post post, PostAuthor author, long likeCount, boolean likedByMe, long commentCount) {
    return new PostSummaryResponse(
        post.getId(),
        post.getTitle(),
        author,
        post.getCreatedAt(),
        likeCount,
        likedByMe,
        commentCount);
  }
}
