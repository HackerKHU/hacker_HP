package org.hackerkhu.hackerhp.domain.user.service;

import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 되돌릴 수 없는 관리자 조작을 저장하기 <b>직전에</b> 요청자의 권한을 다시 확인한다.
 *
 * <p><b>인가는 세션 값으로 이루어진다.</b> 요청이 필터를 지난 뒤에도 다른 관리자가 이 사람을 정지하거나 권한을 회수할 수 있고, 잠금을 기다리는 동안이면 그 사이가
 * 더 길다. 다시 확인하지 않으면 <b>이미 정지된 관리자의 대기 중 요청이 그대로 커밋된다.</b>
 *
 * <p>확인은 <b>잠근 행</b>으로 한다. 잠그지 않고 읽으면 확인과 저장 사이가 다시 열린다.
 */
public final class RequesterCheck {

  private static final Logger log = LoggerFactory.getLogger(RequesterCheck.class);

  private RequesterCheck() {}

  /**
   * <b>이용할 수 있는 계정인가</b> — 관리자 조작이 아닌 쓰기도 같은 창을 갖는다 (#207 리뷰).
   *
   * <p>자료 등록처럼 되돌리기 번거로운 작업도 인가를 지난 뒤 정지될 수 있다. 세션 반영이 끝나기 전에 시작된 요청은 <b>옛 {@code ACTIVE} 값으로 필터를
   * 통과한 채</b> 저장 직전까지 온다.
   */
  public static void requireActive(User requester, Long requesterId) {
    if (requester == null) {
      // 세션은 살아 있는데 계정이 사라졌다. 인증이 성립할 수 없는 상태다.
      throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
    if (requester.getStatus() == Status.SUSPENDED) {
      log.info("정지된 계정의 대기 중 요청을 거절했다: requesterId={}", requesterId);
      throw new BusinessException(ErrorCode.SUSPENDED);
    }
    if (requester.getStatus() == Status.PENDING) {
      throw new BusinessException(ErrorCode.PENDING_APPROVAL);
    }
  }

  /**
   * <b>자료를 쓸 수 있는 계정인가</b> (#228, #229).
   *
   * <p>{@link #requireActive}는 {@code INACTIVE}를 통과시킨다 — 그것이 맞다. 비활동 부원은 공지·활동사진·게시판·마이페이지를 그대로 쓰고,
   * 여기서 막으면 <b>게시판 글쓰기까지 함께 막힌다.</b> 자료 경로만 이 검사를 쓴다.
   *
   * <p><b>필터만으로는 부족하다.</b> {@code AccountStatusFilter}는 세션 값을 보는데, 세션이 {@code ACTIVE}인 채로 통과한 요청이
   * 잠금을 기다리는 사이에 관리자가 학기 전환을 누를 수 있다. 그러면 <b>대기 중이던 등록·수정·삭제가 그대로 커밋된다</b> — 정지에 같은 창이 있어 {@link
   * #requireActive}를 둔 것과 같은 자리다.
   *
   * <p>코드는 {@code INACTIVE}다. 여기서 {@code FORBIDDEN}으로 뭉개면 <b>필터가 막았을 때와 사유가 달라져</b> 화면이 같은 상황에 다른
   * 안내를 띄운다.
   */
  public static void requireNoteAccess(User requester, Long requesterId) {
    requireActive(requester, requesterId);
    if (requester.getStatus() == Status.INACTIVE) {
      log.info("비활동 계정의 대기 중 자료 요청을 거절했다: requesterId={}", requesterId);
      throw new BusinessException(ErrorCode.INACTIVE);
    }
  }

  /** 코드를 상태별로 가른다 — 필터가 막았을 때와 같은 사유가 나가야 화면이 안내를 고른다 (§3-2-7). */
  static void requireActiveAdmin(User requester, Long requesterId) {
    requireActive(requester, requesterId);
    if (requester.getRole() != Role.ADMIN) {
      log.info("권한이 회수된 관리자의 대기 중 요청을 거절했다: requesterId={}", requesterId);
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
  }
}
