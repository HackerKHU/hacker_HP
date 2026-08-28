package org.hackerkhu.hackerhp.domain.user.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import org.hackerkhu.hackerhp.domain.audit.entity.AdminAction;
import org.hackerkhu.hackerhp.domain.audit.service.AdminActionRecorder;
import org.hackerkhu.hackerhp.domain.user.dto.AdminUserResponse;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 권한 부여·회수 (spec 2-2 §2-2-5).
 *
 * <p><b>Role만 바꾼다. Status는 건드리지 않는다</b> (§2-2-5). 권한을 회수한다고 정지되는 것이 아니고, 그 반대도 아니다.
 *
 * <p><b>회수 뒤에 활성 관리자가 남는지 본다</b> (§2-2-7 MUST). 자기 대상인지와 무관하다 — 활성 관리자가 둘일 때 서로의 권한을 동시에 회수하면 두 요청
 * 모두 "남을 회수하는 것"이라 자기 검사에 걸리지 않고, 각자 다른 행만 잠근 채 커밋해 <b>0명이 된다.</b>
 */
@Service
public class AdminUserRoleService {

  private static final Logger log = LoggerFactory.getLogger(AdminUserRoleService.class);

  private final UserRepository userRepository;
  private final SessionSynchronizer sessionSynchronizer;
  private final AdminActionRecorder recorder;
  private final TransactionTemplate transaction;

  public AdminUserRoleService(
      UserRepository userRepository,
      SessionSynchronizer sessionSynchronizer,
      AdminActionRecorder recorder,
      PlatformTransactionManager transactionManager) {
    this.userRepository = userRepository;
    this.sessionSynchronizer = sessionSynchronizer;
    this.recorder = recorder;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  public AdminUserResponse change(Long requesterId, Long targetId, Role desired) {
    Applied applied = transaction.execute(ignored -> apply(requesterId, targetId, desired));
    AdminUserResponse changed = applied.response();

    /*
     * 세션 반영은 커밋 뒤다 (3-1 §3-1-5). 권한이 회수된 사람의 다음 관리자 API 요청이
     * 403이 되어야 한다 (T-34) — DB만 바꾸면 세션 만료까지 그대로 쓴다.
     *
     * 회수는 반영을 확인해야 성공이다 (#197 리뷰 3차). 세션 반영은 실패를 삼키므로,
     * 확인하지 않으면 저장소 장애로 반영이 실패해도 200이 나가고 그 사람은 만료까지
     * ADMIN으로 관리자 API를 계속 부른다 — 관리자는 회수된 줄 안다.
     *
     * 부여는 반대 방향이라 그냥 알린다. 늦게 닿아도 그 사람이 아직 못 쓰는 것뿐이다.
     */
    boolean reflected = true;
    if (changed.role() == Role.USER) {
      reflected = sessionSynchronizer.refreshReporting(changed.id());
    } else {
      sessionSynchronizer.refresh(List.of(changed.id()));
    }

    // 이력은 세션 반영보다 뒤다. 차단이 먼저이고 이력은 늦어도 되는 정보다 (§2-2-7).
    if (applied.changed()) {
      recorder.record(
          requesterId,
          changed.id(),
          desired == Role.ADMIN ? AdminAction.GRANT_ADMIN : AdminAction.REVOKE_ADMIN,
          applied.occurredAt());
    }

    /*
     * 이력을 남긴 뒤에 던진다. 변경은 이미 커밋됐으므로 여기서 곧장 빠져나가면
     * "누가 무엇을 했는지"만 사라진다.
     */
    if (!reflected) {
      throw SessionSynchronizer.notReflected("권한 회수", changed.id());
    }
    return changed;
  }

  /** 바뀌었는지를 함께 돌려준다. 응답만으로는 "방금 회수했다"와 "이미 USER였다"를 가릴 수 없다 (#143). */
  private record Applied(AdminUserResponse response, boolean changed, Instant occurredAt) {}

  private Applied apply(Long requesterId, Long targetId, Role desired) {
    Map<Long, User> locked = lockRowsInIdOrder(requesterId, targetId, desired);
    RequesterCheck.requireActiveAdmin(locked.get(requesterId), requesterId);

    User user = locked.get(targetId);
    if (user == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "회원을 찾을 수 없습니다.");
    }

    /*
     * 승인 대기 계정은 이 경로의 대상이 아니다. 신청서를 낸 뒤 승인을 거쳐야 ACTIVE가 되는데
     * (3-1 §3-1-4), 그 전에 관리자로 만들면 승인일시가 없는 ADMIN이 생긴다.
     */
    if (user.getStatus() == Status.PENDING) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "승인 대기 중인 계정의 권한은 바꿀 수 없습니다.");
    }

    /*
     * 비활동 계정도 이 경로의 대상이 아니다 (2-2 §2-2-5 MUST, #228). 관리자로 만들면
     * 자료를 못 보는 관리자가 생긴다 — 회원 관리 화면에서 남의 자료를 지울 수는 있는데
     * 자기는 목록을 열지 못한다. 올려야 할 사람이면 학기 복구를 먼저 한다.
     */
    if (user.getStatus() == Status.INACTIVE) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "비활동 계정의 권한은 바꿀 수 없습니다. 먼저 복구해 주세요.");
    }

    // 회수가 활성 관리자를 0명으로 만들면 막는다. 지금 활성 관리자인 경우에만 해당한다.
    if (desired == Role.USER && isActiveAdmin(user)) {
      guardLastActiveAdmin(requesterId, targetId);
    }

    boolean changed = user.getRole() != desired;
    // 잠근 채로 잡는다. 이 시각이 이력의 "언제"가 된다 (#143 리뷰).
    Instant occurredAt = Instant.now();
    if (changed) {
      if (desired == Role.ADMIN) {
        user.promoteToAdmin();
      } else {
        user.demoteToUser();
      }
      log.info("회원 권한 변경: requesterId={} targetId={} -> {}", requesterId, targetId, desired);
    }
    return new Applied(AdminUserResponse.from(user), changed, occurredAt);
  }

  /**
   * <b>잠금 순서는 저장소 전체에서 하나다</b> — {@code users} 행은 id 오름차순.
   *
   * <p>회수일 때는 활성 관리자 전부를 함께 잠근다. 수를 세는 동안 그 행들이 바뀌면 안 된다 (§2-2-7의 원자성 요구).
   */
  private Map<Long, User> lockRowsInIdOrder(Long requesterId, Long targetId, Role desired) {
    SortedSet<Long> ids = new TreeSet<>(List.of(requesterId, targetId));
    if (desired == Role.USER) {
      ids.addAll(userRepository.findIdsByRoleAndStatus(Role.ADMIN, Status.ACTIVE));
    }
    Map<Long, User> locked = new LinkedHashMap<>();
    ids.forEach(id -> userRepository.findByIdForUpdate(id).ifPresent(user -> locked.put(id, user)));
    return locked;
  }

  private static boolean isActiveAdmin(User user) {
    return user.getRole() == Role.ADMIN && user.getStatus() == Status.ACTIVE;
  }

  private void guardLastActiveAdmin(Long requesterId, Long targetId) {
    if (userRepository.countByRoleAndStatus(Role.ADMIN, Status.ACTIVE) > 1) {
      return;
    }
    throw new BusinessException(
        ErrorCode.FORBIDDEN,
        requesterId.equals(targetId) ? "마지막 관리자는 스스로 권한을 회수할 수 없습니다." : "마지막 관리자의 권한은 회수할 수 없습니다.");
  }
}
