package org.hackerkhu.hackerhp.domain.photo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * spec/3-2-DESIGN-CONTRACT.md §3-2-2 {@code photo_likes} (#346, 3-3 결정 27).
 *
 * <p><b>회원·사진을 연관관계로 매핑하지 않는다.</b> 양쪽 FK가 {@code ON DELETE CASCADE}라 주인이 사라지면 이 행도 함께 사라진다 — {@code
 * Bookmark}와 같은 판단이다. 이 표가 답하는 것은 <b>"그 조합이 있나"</b>뿐이다.
 *
 * <p>고쳐 쓰지 않는다. 좋아요는 있거나 없거나다.
 *
 * <p><b>이 엔티티로 새로 만들지 않는다.</b> 담기·빼기는 {@code PhotoLikeRepository}의 한 문장짜리 질의가 한다 — 확인하고 저장하는 방식은
 * 동시에 도착한 요청을 가르지 못한다 ({@code Bookmark}와 같은 이유). 여기 있는 것은 <b>읽기 위한 매핑</b>이다.
 */
@Entity
@Table(name = "photo_likes")
@IdClass(PhotoLikeId.class)
public class PhotoLike {

  @Id
  @Column(name = "user_id", nullable = false, updatable = false)
  private Long userId;

  @Id
  @Column(name = "photo_id", nullable = false, updatable = false)
  private Long photoId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected PhotoLike() {}

  public Long getUserId() {
    return userId;
  }

  public Long getPhotoId() {
    return photoId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
