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

  /** 코드를 상태별로 가른다 — 필터가 막았을 때와 같은 사유가 나가야 화면이 안내를 고른다 (§3-2-7). */
  static void requireActiveAdmin(User requester, Long requesterId) {
    requireActive(requester, requesterId);
    if (requester.getRole() != Role.ADMIN) {
      log.info("권한이 회수된 관리자의 대기 중 요청을 거절했다: requesterId={}", requesterId);
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
  }
}
