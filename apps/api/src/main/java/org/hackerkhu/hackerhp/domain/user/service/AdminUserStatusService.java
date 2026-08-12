package org.hackerkhu.hackerhp.domain.user.service;

import java.util.List;
import org.hackerkhu.hackerhp.domain.user.dto.AdminUserResponse;
import org.hackerkhu.hackerhp.domain.user.dto.StatusChangeRequest.Target;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.global.auth.SessionSynchronizer;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회원 상태 변경 — 정지와 해제 (spec 2-2 §2-2-3). */
@Service
public class AdminUserStatusService {

  private static final Logger log = LoggerFactory.getLogger(AdminUserStatusService.class);

  private final UserRepository userRepository;
  private final SessionSynchronizer sessionSynchronizer;

  public AdminUserStatusService(
      UserRepository userRepository, SessionSynchronizer sessionSynchronizer) {
    this.userRepository = userRepository;
    this.sessionSynchronizer = sessionSynchronizer;
  }

  /**
   * {@code ACTIVE} ↔ {@code SUSPENDED}.
   *
   * <p><b>정지는 즉시 차단이다</b> (2-2 §2-2-3 MUST). DB만 바꾸면 이미 로그인해 있는 사람은 세션 만료까지 그대로 쓴다 — 그래서 세션까지 함께
   * 갱신한다 (T-32).
   *
   * @param requesterId 요청한 관리자. 자기 자신을 정지하는 경우에만 안전장치가 필요하다
   */
  @Transactional
  public AdminUserResponse change(Long requesterId, Long targetId, Target target) {
    /*
     * 잠그는 순서를 하나로 정한다. 관리자 목록을 먼저 잠그고 대상 행을 잠근다.
     *
     * 반대로 하면 두 관리자가 각자 자기 자신을 정지할 때 서로가 쥔 행을 기다려 교착한다 —
     * A가 A행을 쥔 채 {A,B}를 원하고, B가 B행을 쥔 채 {A,B}를 원한다.
     *
     * 자기 정지가 아니면 이 잠금이 필요 없다. 남을 정지시켜 활성 관리자가 0명이 되는 경우는
     * 없다 — 요청자 자신이 활성 관리자이기 때문이다 (§2-2-7이 self만 막는 이유다).
     */
    if (target == Target.SUSPENDED && requesterId.equals(targetId)) {
      guardLastActiveAdmin();
    }

    User user =
        userRepository
            .findByIdForUpdate(targetId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "회원을 찾을 수 없습니다."));

    /*
     * 승인 대기 계정은 이 경로의 대상이 아니다. 계약이 정한 전이는 ACTIVE ↔ SUSPENDED뿐이고
     * (2-2 §2-2-3), 승인은 신청서를 낸 계정으로 한정되며 승인일시도 기록해야 한다.
     */
    if (user.getStatus() == Status.PENDING) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "승인 대기 중인 계정의 상태는 바꿀 수 없습니다.");
    }

    Status desired = target == Target.SUSPENDED ? Status.SUSPENDED : Status.ACTIVE;
    // 이미 그 상태면 아무것도 하지 않는다. 확인 창을 두 번 지나거나 낡은 목록에서 눌러도 오류가 아니다.
    if (user.getStatus() == desired) {
      return AdminUserResponse.from(user);
    }

    if (desired == Status.SUSPENDED) {
      user.suspend();
    } else {
      user.reactivate();
    }

    // 정지는 세션을 지우지 않고 갱신한다 — 지우면 401이 되어 화면이 정지 안내를 띄우지 못한다.
    sessionSynchronizer.refreshAfterCommit(List.of(user));

    log.info(
        "회원 상태 변경: requesterId={} targetId={} {} -> {}",
        requesterId,
        targetId,
        desired == Status.SUSPENDED ? Status.ACTIVE : Status.SUSPENDED,
        desired);
    return AdminUserResponse.from(user);
  }

  /**
   * <b>활성 관리자를 0명으로 만들지 않는다</b> (2-2 §2-2-7 MUST).
   *
   * <p>여기서 잠근 행들이 이 트랜잭션이 끝날 때까지 남으므로, 동시에 들어온 다른 자기 정지는 기다렸다가 <b>줄어든 수</b>를 보게 된다 (T-15).
   */
  private void guardLastActiveAdmin() {
    List<User> activeAdmins = userRepository.lockAll(Role.ADMIN, Status.ACTIVE);
    if (activeAdmins.size() <= 1) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "마지막 활성 관리자는 자기 자신을 정지할 수 없습니다.");
    }
  }
}
