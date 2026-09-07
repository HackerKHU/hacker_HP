package org.hackerkhu.hackerhp.domain.photo.service;

import java.time.Instant;
import org.hackerkhu.hackerhp.domain.photo.repository.PhotoLikeRepository;
import org.hackerkhu.hackerhp.domain.photo.repository.PhotoRepository;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.domain.user.service.RequesterCheck;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 활동사진 좋아요 누르기·떼기 (spec 2-1 §2-1-7, 3-2 §3-2-5, #346, 3-3 결정 27).
 *
 * <p><b>둘 다 멱등이다.</b> 이미 누른 것을 또 눌러도, 떼지 않은 것을 또 떼도 성공이다 — {@code BookmarkService}와 같은 이유다.
 *
 * <p><b>토글이 아니다.</b> 같은 요청이 상태를 뒤집으면 재시도가 위험해진다. 화면은 응답의 {@code likedByMe}를 보고 누를지 뗄지 고른다.
 *
 * <p><b>사진 조회 권한과 같다</b> ({@code ACTIVE}·{@code INACTIVE}) — 활동사진은 자료 갈래가 아니라 {@link
 * RequesterCheck#requireActive}만 쓴다.
 */
@Service
public class PhotoLikeService {

  private final PhotoLikeRepository likes;
  private final PhotoRepository photos;
  private final UserRepository users;

  public PhotoLikeService(PhotoLikeRepository likes, PhotoRepository photos, UserRepository users) {
    this.likes = likes;
    this.photos = photos;
    this.users = users;
  }

  /**
   * <b>저장 직전에 요청자를 다시 확인한다</b> (spec 3-1 §3-1-4 MUST, {@code BookmarkService}와 같은 이유).
   *
   * <p>활동사진 좋아요는 자료 갈래가 아니라 {@link RequesterCheck#requireActive}만 쓴다 — {@code INACTIVE}는 막히지 않는다.
   */
  private void lockAndCheck(Long userId) {
    RequesterCheck.requireActive(users.findByIdForUpdate(userId).orElse(null), userId);
  }

  /**
   * 누른다. 이미 눌러져 있으면 아무것도 하지 않는다.
   *
   * <p><b>사진이 있는지 먼저 본다.</b> 없는 id를 그대로 넣으면 FK 위반이 {@code 500}으로 나간다.
   */
  @Transactional
  public void add(Long userId, Long photoId) {
    lockAndCheck(userId);
    if (!photos.existsById(photoId)) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "사진을 찾을 수 없습니다.");
    }
    try {
      likes.insertIgnoringDuplicate(userId, photoId, Instant.now());
    } catch (DataIntegrityViolationException e) {
      // 위 확인과 이 삽입 사이에 그 사진이 지워졌다 — BookmarkService.add()와 같은 경합이다.
      throw new BusinessException(ErrorCode.NOT_FOUND, "사진을 찾을 수 없습니다.");
    }
  }

  /**
   * 뗀다. 눌려 있지 않아도 성공이다.
   *
   * <p><b>사진이 있는지 보지 않는다.</b> 사진이 지워지면 좋아요도 함께 사라지므로({@code ON DELETE CASCADE}) 뗄 것이 이미 없다.
   */
  @Transactional
  public void remove(Long userId, Long photoId) {
    lockAndCheck(userId);
    likes.deleteLike(userId, photoId);
  }
}
