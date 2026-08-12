package org.hackerkhu.hackerhp.domain.user.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
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
   * @param requesterId 요청한 관리자. <b>잠근 뒤 다시 확인한다</b> — 인가는 세션 값으로 이루어지므로 그 사이에 이 사람이 정지됐을 수 있다
   */
  @Transactional
  public AdminUserResponse change(Long requesterId, Long targetId, Target target) {
    Status desired = target == Target.SUSPENDED ? Status.SUSPENDED : Status.ACTIVE;
    Map<Long, User> locked = lockRowsInIdOrder(requesterId, targetId, desired);

    requireStillAdmin(locked.get(requesterId), requesterId);

    User user =
        locked.containsKey(targetId) ? locked.get(targetId) : orElseNotFound(); // 잠글 때 없던 행이다.

    /*
     * 승인 대기 계정은 이 경로의 대상이 아니다. 계약이 정한 전이는 ACTIVE ↔ SUSPENDED뿐이고
     * (2-2 §2-2-3), 승인은 신청서를 낸 계정으로 한정되며 승인일시도 기록해야 한다.
     */
    if (user.getStatus() == Status.PENDING) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "승인 대기 중인 계정의 상태는 바꿀 수 없습니다.");
    }

    if (desired == Status.SUSPENDED && isActiveAdmin(user)) {
      guardLastActiveAdmin(requesterId, targetId);
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

  /**
   * 이 요청이 건드릴 행을 <b>id 오름차순으로</b> 잠근다.
   *
   * <p><b>순서가 저장소 전체에서 하나여야 한다.</b> 일괄 승인도 대상들을 id 순으로 잠근다 — 한쪽이 범위째(예: 활성 관리자 전부) 먼저 잠그면 두 트랜잭션이
   * 엇갈린 순서로 같은 행들을 원하게 되어 교착한다. 승인 목록에 회원 M과 관리자 A가 함께 있고 M의 id가 더 작을 때가 그런 경우다.
   *
   * <p>잠그는 것은 셋이다 — <b>요청자</b>(권한을 다시 확인해야 한다), <b>대상</b>, 그리고 정지일 때 <b>활성 관리자 전부</b>(수를 세는 동안 바뀌면
   * 안 된다).
   */
  private Map<Long, User> lockRowsInIdOrder(Long requesterId, Long targetId, Status desired) {
    SortedSet<Long> ids = new TreeSet<>(List.of(requesterId, targetId));
    if (desired == Status.SUSPENDED) {
      ids.addAll(userRepository.findIdsByRoleAndStatus(Role.ADMIN, Status.ACTIVE));
    }
    Map<Long, User> locked = new LinkedHashMap<>();
    ids.forEach(id -> userRepository.findByIdForUpdate(id).ifPresent(user -> locked.put(id, user)));
    return locked;
  }

  /**
   * <b>인가는 세션 값으로 이루어진다.</b> 요청이 인증을 통과한 뒤에도 다른 관리자가 이 사람을 정지하거나 권한을 회수할 수 있고, 잠금을 기다리는 동안이면 그 사이가
   * 더 길다.
   *
   * <p>다시 확인하지 않으면 <b>이미 정지된 관리자의 대기 중 요청이 그대로 커밋된다.</b>
   */
  private static void requireStillAdmin(User requester, Long requesterId) {
    if (requester == null) {
      // 세션은 살아 있는데 계정이 사라졌다. 인증이 성립할 수 없는 상태다.
      throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
    // 코드를 상태별로 가른다 — 필터가 막았을 때와 같은 사유가 나가야 화면이 안내를 고른다 (§3-2-7).
    if (requester.getStatus() == Status.SUSPENDED) {
      throw new BusinessException(ErrorCode.SUSPENDED);
    }
    if (requester.getStatus() == Status.PENDING) {
      throw new BusinessException(ErrorCode.PENDING_APPROVAL);
    }
    if (requester.getRole() != Role.ADMIN) {
      log.info("권한이 회수된 관리자의 대기 중 요청을 거절했다: requesterId={}", requesterId);
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
  }

  private static boolean isActiveAdmin(User user) {
    return user.getRole() == Role.ADMIN && user.getStatus() == Status.ACTIVE;
  }

  private static User orElseNotFound() {
    throw new BusinessException(ErrorCode.NOT_FOUND, "회원을 찾을 수 없습니다.");
  }

  /**
   * <b>활성 관리자를 0명으로 만들지 않는다</b> (2-2 §2-2-7).
   *
   * <p><b>자기 정지만 막는 것으로는 부족하다.</b> 활성 관리자가 둘인데 서로를 동시에 정지하면 두 요청 모두 "남을 정지시키는 것"이라 자기 검사에 걸리지 않고,
   * 각자 다른 행만 잠근 채 커밋해 <b>0명이 된다.</b> 그래서 자기 정지 여부와 무관하게 "정지 뒤에도 활성 관리자가 남는가"를 본다.
   *
   * <p>세기 전에 그 행들을 이미 잠갔다. 동시에 들어온 다른 정지는 그 잠금을 기다렸다가 <b>줄어든 수</b>를 보게 된다 (T-15).
   */
  private void guardLastActiveAdmin(Long requesterId, Long targetId) {
    if (userRepository.countByRoleAndStatus(Role.ADMIN, Status.ACTIVE) > 1) {
      return;
    }
    throw new BusinessException(
        ErrorCode.FORBIDDEN,
        requesterId.equals(targetId)
            ? "마지막 활성 관리자는 자기 자신을 정지할 수 없습니다."
            : "마지막 활성 관리자는 정지할 수 없습니다. 다른 관리자를 먼저 활성화해 주세요.");
  }
}
