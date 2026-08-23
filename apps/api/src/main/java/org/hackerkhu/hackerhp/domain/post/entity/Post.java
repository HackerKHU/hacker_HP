package org.hackerkhu.hackerhp.domain.post.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * spec/3-2-DESIGN-CONTRACT.md §3-2-2 {@code posts} — 자유 게시판 (#235·#236).
 *
 * <p><b>작성자를 연관관계로 매핑하지 않는다.</b> {@code author_id}는 {@code ON DELETE SET NULL}이라(2-2 §2-2-4) 회원을
 * 지워도 글은 남고 그 자리가 빈다. 엔티티로 물려 두면 조회마다 {@code users}를 함께 읽게 되는데, 목록이 필요한 것은 <b>이름 하나뿐이고 그마저 없을 수
 * 있다.</b> 이름은 조회 시점에 따로 붙인다 — {@code Note}와 같은 판단이다.
 *
 * <p><b>본문은 평문이다</b> (3-3 결정 16). 저장할 때도 내보낼 때도 문자열을 건드리지 않는다 — 마크다운으로 해석하지도, HTML을 정화하지도 않는다.
 */
@Entity
@Table(name = "posts")
public class Post {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String title;

  @Column(nullable = false)
  private String content;

  /** <b>{@code null}이면 탈퇴한 회원이다</b> (2-2 §2-2-4). */
  @Column(name = "author_id")
  private Long authorId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  /**
   * 수정 기능은 아직 없다 (3-3 결정 16). 그래도 열을 두는 것은 다른 테이블과 모양을 맞추기 위해서다 — 등록 시 {@code createdAt}과 같은 값이
   * 들어가고, 수정이 들어오면(#256) 그때부터 움직인다.
   */
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Post() {}

  /**
   * 글을 쓴다.
   *
   * <p><b>{@code authorId}는 인증 주체에서만 온다</b> (MUST, §3-2-5). 요청 본문으로 받으면 다른 사람 이름으로 글을 올릴 수 있다.
   */
  public static Post write(String title, String content, Long authorId, Instant now) {
    Post post = new Post();
    post.title = title;
    post.content = content;
    post.authorId = authorId;
    post.createdAt = now;
    post.updatedAt = now;
    return post;
  }

  public Long getId() {
    return id;
  }

  public String getTitle() {
    return title;
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
