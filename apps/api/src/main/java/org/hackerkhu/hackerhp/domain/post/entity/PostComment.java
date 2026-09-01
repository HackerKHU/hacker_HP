package org.hackerkhu.hackerhp.domain.post.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * spec/3-2-DESIGN-CONTRACT.md §3-2-6 {@code post_comments} — 자유 게시판 댓글 (#347).
 *
 * <p><b>{@code Post}와 같은 판단으로 만들었다.</b> 작성자를 연관관계로 매핑하지 않는다 — {@code author_id}는 {@code ON DELETE
 * SET NULL}이라(2-2 §2-2-4) 회원을 지워도 댓글은 남는다. 본문은 평문이다(3-3 결정 23 D1) — 저장할 때도 내보낼 때도 문자열을 건드리지 않는다.
 *
 * <p>{@code postId}도 연관관계로 매핑하지 않는다. 댓글은 항상 특정 게시글 아래에서만 조회·등록되므로(URL이 이미 {@code postId}를 담는다) 지연
 * 로딩으로 얻을 이점이 없고, {@code author_id}와 같은 이유로 값만 있으면 충분하다.
 */
@Entity
@Table(name = "post_comments")
public class PostComment {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "post_id", nullable = false)
  private Long postId;

  @Column(nullable = false)
  private String content;

  /** <b>{@code null}이면 탈퇴한 회원이다</b> (2-2 §2-2-4). */
  @Column(name = "author_id")
  private Long authorId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /** 등록 시 {@code createdAt}과 같은 값이 들어가고, 수정하면({@link #edit}) 그때부터 움직인다. */
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected PostComment() {}

  /**
   * 댓글을 쓴다.
   *
   * <p><b>{@code authorId}는 인증 주체에서만 온다</b> (MUST). 요청 본문으로 받으면 다른 사람 이름으로 댓글을 남길 수 있다.
   */
  public static PostComment write(Long postId, String content, Long authorId, Instant now) {
    PostComment comment = new PostComment();
    comment.postId = postId;
    comment.content = content;
    comment.authorId = authorId;
    comment.createdAt = now;
    comment.updatedAt = now;
    return comment;
  }

  /**
   * 수정. <b>보낸 것으로 통째로 바꾼다</b> — 게시글 수정({@link Post#edit})과 같은 판단이다. {@code authorId}·{@code
   * postId}는 건드리지 않는다: 소유자 검증은 저장 전 서비스가 이미 끝냈고, 이 메서드는 그 결과를 반영할 뿐이다.
   */
  public void edit(String content, Instant now) {
    this.content = content;
    this.updatedAt = now;
  }

  public Long getId() {
    return id;
  }

  public Long getPostId() {
    return postId;
  }

  public String getContent() {
    return content;
  }

  public Long getAuthorId() {
    return authorId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
