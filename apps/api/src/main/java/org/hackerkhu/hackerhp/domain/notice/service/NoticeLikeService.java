package org.hackerkhu.hackerhp.domain.notice.service;

import java.time.Instant;
import org.hackerkhu.hackerhp.domain.notice.repository.NoticeLikeRepository;
import org.hackerkhu.hackerhp.domain.notice.repository.NoticeRepository;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.domain.user.service.RequesterCheck;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공지 좋아요 누르기·떼기 (spec 2-1 §2-1-6, 3-2 §3-2-5, #343, 3-3 결정 24).
 *
 * <p><b>둘 다 멱등이다.</b> 이미 누른 것을 또 눌러도, 떼지 않은 것을 또 떼도 성공이다 — {@code BookmarkService}와 같은 이유다. 화면에서 두
 * 번 누르거나 목록·상세에서 각각 누르는 일은 흔한데, 그때 오류를 주면 사용자에게 의미 없는 안내를 띄워야 한다.
 *
 * <p><b>토글이 아니다.</b> 같은 요청이 상태를 뒤집으면 재시도가 위험해진다 — 응답이 오는 길에 끊겨 클라이언트가 다시 보내면 방금 누른 것이 조용히 빠진다. 화면은
 * 응답의 {@code likedByMe}를 보고 누를지 뗄지 고른다.
 *
 * <p><b>공지 조회 권한과 같다</b> ({@code ACTIVE}·{@code INACTIVE}) — 좋아요는 자료 갈래가 아니라 {@link
 * RequesterCheck#requireActive}만 쓴다.
 */
@Service
public class NoticeLikeService {

  private final NoticeLikeRepository likes;
  private final NoticeRepository notices;
  private final UserRepository users;

  public NoticeLikeService(
      NoticeLikeRepository likes, NoticeRepository notices, UserRepository users) {
    this.likes = likes;
    this.notices = notices;
    this.users = users;
  }

  /**
   * <b>저장 직전에 요청자를 다시 확인한다</b> (spec 3-1 §3-1-4 MUST, {@code BookmarkService}와 같은 이유).
   *
   * <p>필터는 세션 값을 본다. {@code ACTIVE}·{@code INACTIVE}로 통과한 요청이 잠금을 기다리는 사이에 관리자가 그 계정을 정지시킬 수 있고,
   * 그러면 대기 중이던 누르기·떼기가 그대로 커밋된다.
   */
  private void lockAndCheck(Long userId) {
    RequesterCheck.requireActive(users.findByIdForUpdate(userId).orElse(null), userId);
  }

  /**
   * 누른다. 이미 눌러져 있으면 아무것도 하지 않는다.
   *
   * <p><b>공지가 있는지 먼저 본다.</b> 없는 id를 그대로 넣으면 FK 위반이 {@code 500}으로 나간다.
   */
  @Transactional
  public void add(Long userId, Long noticeId) {
    lockAndCheck(userId);
    if (!notices.existsById(noticeId)) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "공지를 찾을 수 없습니다.");
    }
    try {
      likes.insertIgnoringDuplicate(userId, noticeId, Instant.now());
    } catch (DataIntegrityViolationException e) {
      // 위 확인과 이 삽입 사이에 그 공지가 지워졌다 — BookmarkService.add()와 같은 경합이다.
      throw new BusinessException(ErrorCode.NOT_FOUND, "공지를 찾을 수 없습니다.");
    }
  }

  /**
   * 뗀다. 눌려 있지 않아도 성공이다.
   *
   * <p><b>공지가 있는지 보지 않는다.</b> 공지가 지워지면 좋아요도 함께 사라지므로({@code ON DELETE CASCADE}) 뗄 것이 이미 없고, 없는 공지에
   * {@code 404}를 주면 화면이 지울 수 없는 좋아요 표시를 들고 있게 된다.
   */
  @Transactional
  public void remove(Long userId, Long noticeId) {
    lockAndCheck(userId);
    likes.deleteLike(userId, noticeId);
  }
}
