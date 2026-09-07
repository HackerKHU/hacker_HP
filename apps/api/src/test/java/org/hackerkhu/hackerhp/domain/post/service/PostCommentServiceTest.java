package org.hackerkhu.hackerhp.domain.post.service;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.hackerkhu.hackerhp.domain.post.dto.PostCommentRequest;
import org.hackerkhu.hackerhp.domain.post.entity.PostComment;
import org.hackerkhu.hackerhp.domain.post.repository.PostCommentRepository;
import org.hackerkhu.hackerhp.domain.post.repository.PostRepository;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.testsupport.user.Accounts;
import org.junit.jupiter.api.Test;

/**
 * 댓글 수정의 잠금 상호작용 계약. 실제 경쟁 결과는 {@code PostCommentIntegrationTest}가 검증한다 — {@code PostServiceTest}와
 * 같은 판단.
 */
class PostCommentServiceTest {

  private final PostCommentRepository comments = mock(PostCommentRepository.class);
  private final PostRepository posts = mock(PostRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final PostCommentService service = new PostCommentService(comments, posts, users);

  @Test
  void editLocksAccountThenCommentWithoutUsingAnUnlockedLookup() {
    long requesterId = 1L;
    long postId = 2L;
    long commentId = 3L;
    User requester = Accounts.approved("comment-lock", "comment-lock@khu.ac.kr", "20250001", "작성자");
    PostComment comment =
        PostComment.write(postId, "본문", requesterId, Instant.parse("2026-08-31T00:00:00Z"));
    when(users.findByIdForUpdate(requesterId)).thenReturn(Optional.of(requester));
    when(comments.findByIdForUpdate(commentId)).thenReturn(Optional.of(comment));
    service.edit(requesterId, postId, commentId, new PostCommentRequest("고친 내용"));

    var order = inOrder(users, comments);
    order.verify(users).findByIdForUpdate(requesterId);
    order.verify(comments).findByIdForUpdate(commentId);
    verify(comments, never()).findById(commentId);
  }
}
