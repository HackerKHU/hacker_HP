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
   * @param requesterId 요청한 관리자. 안내 문구를 자기 정지와 남을 정지로 가르는 데 쓴다
   */
  @Transactional
  public AdminUserResponse change(Long requesterId, Long targetId, Target target) {
    Status desired = target == Target.SUSPENDED ? Status.SUSPENDED : Status.ACTIVE;

    /*
     * 잠그는 순서를 하나로 정한다 — 활성 관리자 집합을 먼저, 대상 행을 나중.
     *
     * 반대로 하면 두 정지 요청이 서로가 쥔 행을 기다려 교착한다. 대상이 관리자가 아니어도
     * 집합을 잠근다. 잠글 행이 몇 개뿐이고, "대상이 관리자인지" 먼저 읽어 정하려 들면 그
     * 읽기와 잠금 사이가 다시 경쟁 구간이 된다.
     */
    List<User> activeAdmins =
        desired == Status.SUSPENDED ? userRepository.lockAll(Role.ADMIN, Status.ACTIVE) : List.of();

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

    if (desired == Status.SUSPENDED && isActiveAdmin(user)) {
      guardLastActiveAdmin(activeAdmins, requesterId, targetId);
    }

    if (user.getStatus() != desired) {
      if (desired == Status.SUSPENDED) {
        user.suspend();
      } else {
        user.reactivate();
      }
      log.info(
          "회원 상태 변경: requesterId={} targetId={} {} -> {}",
          requesterId,
          targetId,
          desired == Status.SUSPENDED ? Status.ACTIVE : Status.SUSPENDED,
          desired);
    }

    /*
     * 이미 그 상태였더라도 세션을 다시 맞춘다.
     *
     * 세션 갱신 실패는 예외로 올리지 않고 기록만 한다(SessionSynchronizer). 그러면 같은 요청을
     * 다시 보내는 것이 유일한 복구 수단인데, 여기서 일찍 돌아가 버리면 그 재시도가 아무 일도
     * 하지 않는다 — 정지된 사람이 만료까지 계속 쓰게 된다.
     *
     * 정지는 세션을 지우지 않고 갱신한다. 지우면 401이 되어 화면이 정지 안내를 띄우지 못한다.
     */
    sessionSynchronizer.refreshAfterCommit(List.of(user));
    return AdminUserResponse.from(user);
  }

  private static boolean isActiveAdmin(User user) {
    return user.getRole() == Role.ADMIN && user.getStatus() == Status.ACTIVE;
  }

  /**
   * <b>활성 관리자를 0명으로 만들지 않는다</b> (2-2 §2-2-7).
   *
   * <p><b>자기 정지만 막는 것으로는 부족하다.</b> 활성 관리자가 둘인데 서로를 동시에 정지하면 두 요청 모두 "남을 정지시키는 것"이라 자기 검사에 걸리지 않고,
   * 각자 다른 행만 잠근 채 커밋해 <b>0명이 된다.</b> 그래서 자기 정지 여부와 무관하게 "정지 뒤에도 활성 관리자가 남는가"를 본다.
   *
   * <p>세는 대상은 <b>이 트랜잭션이 잠가 둔 집합</b>이다. 동시에 들어온 다른 정지는 그 잠금을 기다렸다가 <b>줄어든 수</b>를 보게 된다 (T-15).
   *
   * <p>{@code SUSPENDED}인 관리자는 애초에 이 집합에 없다 (MUST). 로그인할 수 없으므로 DB에 role만 남아 있어도 운영을 보장하지 못한다.
   */
  private static void guardLastActiveAdmin(
      List<User> activeAdmins, Long requesterId, Long targetId) {
    if (activeAdmins.size() > 1) {
      return;
    }
    throw new BusinessException(
        ErrorCode.FORBIDDEN,
        requesterId.equals(targetId)
            ? "마지막 활성 관리자는 자기 자신을 정지할 수 없습니다."
            : "마지막 활성 관리자는 정지할 수 없습니다. 다른 관리자를 먼저 활성화해 주세요.");
  }
}
