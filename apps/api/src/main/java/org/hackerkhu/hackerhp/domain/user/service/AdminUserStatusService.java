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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 회원 상태 변경 — 정지와 해제 (spec 2-2 §2-2-3). */
@Service
public class AdminUserStatusService {

  private static final Logger log = LoggerFactory.getLogger(AdminUserStatusService.class);

  private final UserRepository userRepository;
  private final SessionSynchronizer sessionSynchronizer;
  private final AdminActionRecorder recorder;
  private final TransactionTemplate transaction;

  public AdminUserStatusService(
      UserRepository userRepository,
      SessionSynchronizer sessionSynchronizer,
      AdminActionRecorder recorder,
      PlatformTransactionManager transactionManager) {
    this.userRepository = userRepository;
    this.sessionSynchronizer = sessionSynchronizer;
    this.recorder = recorder;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  /**
   * {@code ACTIVE} ↔ {@code SUSPENDED}.
   *
   * <p><b>정지는 즉시 차단이다</b> (2-2 §2-2-3 MUST). DB만 바꾸면 이미 로그인해 있는 사람은 세션 만료까지 그대로 쓴다 — 그래서 세션까지 함께
   * 갱신한다 (T-32).
   *
   * @param requesterId 요청한 관리자. <b>잠근 뒤 다시 확인한다</b> — 인가는 세션 값으로 이루어지므로 그 사이에 이 사람이 정지됐을 수 있다
   */
  public AdminUserResponse change(Long requesterId, Long targetId, Target target) {
    Applied applied = transaction.execute(ignored -> apply(requesterId, targetId, target));
    AdminUserResponse changed = applied.response();

    /*
     * 세션 반영은 커밋 뒤다 (3-1 §3-1-5). @Transactional 대신 템플릿을 쓰는 이유가 이것이다 —
     * execute가 돌아온 시점에는 커밋도 끝나고 커넥션도 반납돼 있어, 갱신이 커넥션을 겹쳐 잡지
     * 않는다. 트랜잭션 안에서 부르면 되돌아간 변경이 세션에만 남는다.
     */
    sessionSynchronizer.refresh(List.of(changed.id()));

    /*
     * 이력은 세션 반영보다 뒤다. 정지는 즉시 차단이어야 하고(2-2 §2-2-3 MUST) 이력은 늦어도
     * 되는 정보다. 실패해도 변경은 이미 커밋돼 있다 (§2-2-7).
     *
     * 이미 그 상태였던 재요청은 남기지 않는다. 아무것도 바뀌지 않았는데 "정지했다"가 한 줄 더
     * 생기면, 나중에 이력을 읽는 사람이 두 번 정지된 것으로 읽는다.
     */
    if (applied.changed()) {
      recorder.record(
          requesterId,
          changed.id(),
          target == Target.SUSPENDED ? AdminAction.SUSPEND : AdminAction.ACTIVATE,
          applied.occurredAt());
    }
    return changed;
  }

  /**
   * 바뀌었는지를 함께 돌려준다.
   *
   * <p>응답만으로는 <b>"방금 정지했다"와 "이미 정지돼 있었다"를 가릴 수 없다.</b> 둘 다 같은 상태를 담아 돌아오는데, 이력에는 앞의 것만 남아야 한다.
   *
   * <p><b>시각도 여기서 나온다.</b> 이력을 남기는 시점에 잡으면 커밋과 세션 반영이 끝난 뒤라, 같은 회원을 두 관리자가 잇따라 건드릴 때 <b>실제와 반대 순서로
   * 남을 수 있다.</b> 계정 행을 잠근 채 잡으면 나중에 잠근 조작이 반드시 더 나중 시각을 갖는다.
   */
  private record Applied(AdminUserResponse response, boolean changed, Instant occurredAt) {}

  private Applied apply(Long requesterId, Long targetId, Target target) {
    Status desired = target == Target.SUSPENDED ? Status.SUSPENDED : Status.ACTIVE;
    Map<Long, User> locked = lockRowsInIdOrder(requesterId, targetId, desired);

    RequesterCheck.requireActiveAdmin(locked.get(requesterId), requesterId);

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

    boolean changed = user.getStatus() != desired;
    // 잠근 채로 잡는다. 이 시각이 이력의 "언제"가 된다.
    Instant occurredAt = Instant.now();
    if (changed) {
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

    // 이미 그 상태였더라도 위에서 세션을 다시 맞춘다 — 재요청이 갱신 실패의 복구 수단이다.
    return new Applied(AdminUserResponse.from(user), changed, occurredAt);
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
