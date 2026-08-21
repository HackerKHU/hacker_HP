package org.hackerkhu.hackerhp.domain.note.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * spec/3-2-DESIGN-CONTRACT.md §3-2-2 {@code bookmarks}.
 *
 * <p><b>회원·자료를 연관관계로 매핑하지 않는다.</b> 양쪽 FK가 {@code ON DELETE CASCADE}라 주인이 사라지면 이 행도 함께 사라진다 — 엔티티로
 * 물려 두면 목록을 그릴 때마다 {@code users}·{@code notes}를 따라 읽게 되는데, 이 표가 답하는 것은 <b>"그 조합이 있나"</b>뿐이다.
 *
 * <p>고쳐 쓰지 않는다. 즐겨찾기는 있거나 없거나다.
 *
 * <p><b>이 엔티티로 새로 만들지 않는다.</b> 담기·빼기는 {@code BookmarkRepository}의 한 문장짜리 질의가 한다 — 확인하고 저장하는 방식은 동시에
 * 도착한 요청을 가르지 못한다. 여기 있는 것은 <b>읽기 위한 매핑</b>이다.
 */
@Entity
@Table(name = "bookmarks")
@IdClass(BookmarkId.class)
public class Bookmark {

  @Id
  @Column(name = "user_id", nullable = false, updatable = false)
  private Long userId;

  @Id
  @Column(name = "note_id", nullable = false, updatable = false)
  private Long noteId;

  /** 내가 표시한 시각. <b>{@code GET /bookmarks}의 정렬 기준</b>이다 — 자료의 등록 시각이 아니다. */
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected Bookmark() {}

  public Long getUserId() {
    return userId;
  }

  public Long getNoteId() {
    return noteId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
