package org.hackerkhu.hackerhp.domain.post.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.hackerkhu.hackerhp.domain.post.dto.PostAuthor;
import org.hackerkhu.hackerhp.domain.post.dto.PostCommentRequest;
import org.hackerkhu.hackerhp.domain.post.dto.PostCommentResponse;
import org.hackerkhu.hackerhp.domain.post.entity.PostComment;
import org.hackerkhu.hackerhp.domain.post.repository.PostCommentRepository;
import org.hackerkhu.hackerhp.domain.post.repository.PostRepository;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.domain.user.service.RequesterCheck;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자유 게시판 댓글 (#347).
 *
 * <p><b>수정·삭제 권한은 게시글과 똑같다</b> (3-3 결정 23 D2) — 수정은 작성자 본인만, 삭제는 활성 관리자 또는 작성자 본인이다. 게시글의 삭제 권한이 결정
 * 20에서 관리자 전용으로 시작해 작성자 본인까지 넓어진 전례를 그대로 물려받아, 댓글은 처음부터 그 최종 모양으로 만든다.
 */
@Service
public class PostCommentService {
  private static final Logger log = LoggerFactory.getLogger(PostCommentService.class);
  private static final Sort OLDEST_FIRST =
      Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));

  private final PostCommentRepository comments;
  private final PostRepository posts;
  private final UserRepository users;

  public PostCommentService(
      PostCommentRepository comments, PostRepository posts, UserRepository users) {
    this.comments = comments;
    this.posts = posts;
    this.users = users;
  }

  @Transactional(readOnly = true)
  public List<PostCommentResponse> list(Long postId) {
    requirePostExists(postId);
    List<PostComment> found = comments.findByPostId(postId, OLDEST_FIRST);
    Map<Long, User> authorsById = AuthorLookup.of(found, PostComment::getAuthorId, users);
    return found.stream()
        .map(
            comment ->
                PostCommentResponse.of(
                    comment, AuthorLookup.authorOf(comment.getAuthorId(), authorsById)))
        .toList();
  }

  @Transactional
  public PostCommentResponse write(Long authorId, Long postId, PostCommentRequest request) {
    User author = users.findByIdForUpdate(authorId).orElse(null);
    RequesterCheck.requireActive(author, authorId);
    requirePostExists(postId);
    Instant now = Instant.now();
    PostComment saved = comments.save(PostComment.write(postId, request.content(), authorId, now));
    log.info("댓글 등록: commentId={} postId={} authorId={}", saved.getId(), postId, authorId);
    // author는 방금 잠근 그 행이다 — 다시 조회하지 않고 그대로 쓴다.
    return PostCommentResponse.of(saved, PostAuthor.of(author));
  }

  @Transactional
  public PostCommentResponse edit(
      Long requesterId, Long postId, Long commentId, PostCommentRequest request) {
    User requester = users.findByIdForUpdate(requesterId).orElse(null);
    RequesterCheck.requireActive(requester, requesterId);
    PostComment comment = findInPostForUpdate(postId, commentId);
    if (!requesterId.equals(comment.getAuthorId())) {
      log.info("남의 댓글을 고치려 했다: requesterId={} commentId={}", requesterId, commentId);
      throw new BusinessException(ErrorCode.FORBIDDEN, "본인이 쓴 댓글만 수정할 수 있습니다.");
    }
    comment.edit(request.content(), Instant.now());
    log.info("댓글 수정: commentId={} postId={} authorId={}", commentId, postId, requesterId);
    // requester는 방금 소유자로 확인한 그 행이다 — 다시 조회하지 않고 그대로 쓴다.
    return PostCommentResponse.of(comment, PostAuthor.of(requester));
  }

  @Transactional
  public void delete(Long requesterId, Long postId, Long commentId) {
    User requester = users.findByIdForUpdate(requesterId).orElse(null);
    RequesterCheck.requireActive(requester, requesterId);
    PostComment comment = findInPostForUpdate(postId, commentId);
    requireOwnerOrActiveAdmin(requester, comment, requesterId);
    comments.delete(comment);
    log.info("댓글 삭제: commentId={} postId={} requesterId={}", commentId, postId, requesterId);
  }

  /** 댓글이 실제로 그 게시글 아래 있는지까지 확인한다 — 다른 글의 댓글 id를 넣으면 있어도 없는 것으로 다룬다. */
  private PostComment findInPostForUpdate(Long postId, Long commentId) {
    PostComment comment =
        comments
            .findByIdForUpdate(commentId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "댓글을 찾을 수 없습니다."));
    if (!postId.equals(comment.getPostId())) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "댓글을 찾을 수 없습니다.");
    }
    return comment;
  }

  private void requirePostExists(Long postId) {
    if (!posts.existsById(postId)) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "게시글을 찾을 수 없습니다.");
    }
  }

  private void requireOwnerOrActiveAdmin(User requester, PostComment comment, Long requesterId) {
    if (requester.getRole() == Role.ADMIN && requester.getStatus() == Status.ACTIVE) return;
    if (requesterId.equals(comment.getAuthorId())) return;
    log.info("남의 댓글을 삭제하려 했다: requesterId={} commentId={}", requesterId, comment.getId());
    throw new BusinessException(ErrorCode.FORBIDDEN, "본인이 쓴 댓글만 삭제할 수 있습니다.");
  }
}
