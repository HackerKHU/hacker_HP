package org.hackerkhu.hackerhp.domain.post.service;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.hackerkhu.hackerhp.domain.post.dto.PostCreateRequest;
import org.hackerkhu.hackerhp.domain.post.entity.Post;
import org.hackerkhu.hackerhp.domain.post.repository.PostRepository;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.testsupport.user.Accounts;
import org.junit.jupiter.api.Test;

/** 게시글 수정의 잠금 상호작용 계약. 실제 경쟁 결과는 {@code PostIntegrationTest} T-492가 검증한다. */
class PostServiceTest {

  private final PostRepository posts = mock(PostRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private final PostService service = new PostService(posts, users);

  /** T-492 — 일반 조회로 되돌아가면 통합 테스트의 직렬화 근거가 사라진다. */
  @Test
  void editLocksAccountThenPostWithoutUsingAnUnlockedLookup() {
    long requesterId = 1L;
    long postId = 2L;
    User requester = Accounts.approved("post-lock", "post-lock@khu.ac.kr", "20250001", "작성자");
    Post post = Post.write("제목", "본문", requesterId, Instant.parse("2026-08-31T00:00:00Z"));
    when(users.findByIdForUpdate(requesterId)).thenReturn(Optional.of(requester));
    when(posts.findByIdForUpdate(postId)).thenReturn(Optional.of(post));
    service.edit(requesterId, postId, new PostCreateRequest("고친 제목", "고친 본문"));

    var order = inOrder(users, posts);
    order.verify(users).findByIdForUpdate(requesterId);
    order.verify(posts).findByIdForUpdate(postId);
    verify(posts, never()).findById(postId);
  }
}
