package org.hackerkhu.hackerhp.domain.photo.entity;

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

/** spec/3-2-DESIGN-CONTRACT.md §3-2-2 photos. */
@Entity
@Table(name = "photos")
public class Photo {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(length = 200)
  private String caption;

  @Column(name = "stored_path", nullable = false, length = 500)
  private String storedPath;

  /** 회원을 지워도 사진은 아카이브로 남는다 — {@code ON DELETE SET NULL} (spec 2-2 §2-2-4 MUST). */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "uploader_id")
  private User uploader;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected Photo() {}

  private Photo(String caption, String storedPath, User uploader, Instant createdAt) {
    this.caption = caption;
    this.storedPath = storedPath;
    this.uploader = uploader;
    this.createdAt = createdAt;
  }

  /**
   * 리사이즈가 끝나기 전에 행을 먼저 만든다 — 최종 저장 키({@code photos/{photoId}/{uuid}.jpg})에 이 행의 id가 들어가서, id를 먼저
   * 확보해야 한다. {@code storedPath}는 이 시점엔 임시값이고 {@link #assignStoredPath}가 실제 값으로 바꾼다.
   */
  public static Photo upload(String caption, String temporaryStoredPath, User uploader) {
    return new Photo(caption, temporaryStoredPath, uploader, Instant.now());
  }

  public void assignStoredPath(String storedPath) {
    this.storedPath = storedPath;
  }

  public Long getId() {
    return id;
  }

  public String getCaption() {
    return caption;
  }

  public String getStoredPath() {
    return storedPath;
  }

  public User getUploader() {
    return uploader;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
