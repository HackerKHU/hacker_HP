package org.hackerkhu.hackerhp.domain.notice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hackerkhu.hackerhp.domain.user.entity.User;

/** spec/3-2-DESIGN-CONTRACT.md §3-2-2 notices. */
@Entity
@Table(name = "notices")
public class Notice {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String title;

  @Column(nullable = false, columnDefinition = "text")
  private String content;

  @Column(name = "is_pinned", nullable = false)
  private boolean pinned;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "author_id")
  private User author;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Notice() {}

  private Notice(
      String title,
      String content,
      boolean pinned,
      User author,
      Instant createdAt,
      Instant updatedAt) {
    this.title = title;
    this.content = content;
    this.pinned = pinned;
    this.author = author;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public static Notice write(String title, String content, User author) {
    Instant now = Instant.now();
    return new Notice(title, content, false, author, now, now);
  }

  public void edit(String title, String content) {
    this.title = title;
    this.content = content;
    this.updatedAt = Instant.now();
  }

  public void pin() {
    this.pinned = true;
    this.updatedAt = Instant.now();
  }

  public void unpin() {
    this.pinned = false;
    this.updatedAt = Instant.now();
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

  public boolean isPinned() {
    return pinned;
  }

  public User getAuthor() {
    return author;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
