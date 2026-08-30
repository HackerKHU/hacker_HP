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
  private final AdminSuspensionPolicy suspensionPolicy;
  private final SessionSynchronizer sessionSynchronizer;
  private final AdminActionRecorder recorder;
  private final TransactionTemplate transaction;

  public AdminUserStatusService(
      UserRepository userRepository,
      AdminSuspensionPolicy suspensionPolicy,
      SessionSynchronizer sessionSynchronizer,
      AdminActionRecorder recorder,
      PlatformTransactionManager transactionManager) {
    this.userRepository = userRepository;
    this.suspensionPolicy = suspensionPolicy;
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
    return run(requesterId, targetId, target, Authority.ADMIN, true);
  }

  /**
   * 관리자 회원 제거가 삭제 전에 확정하는 내부 정지 (spec 2-2 §2-2-4).
   *
   * <p>직접 정지 정책을 적용하지 않는다. 계정 삭제 뒤 세션 폐기가 실패해도 이미 {@code SUSPENDED}여야 접근이 차단되기 때문이다. 외부 상태 PATCH나
   * 일괄 상태 변경에서 이 메서드를 사용하면 안 된다.
   */
  void suspendForRemoval(Long requesterId, Long targetId) {
    run(requesterId, targetId, Target.SUSPENDED, Authority.ADMIN, false);
  }

  /**
   * 본인 탈퇴가 지우기 전에 밟는 정지 (spec 3-2 {@code DELETE /auth/me}, #223).
   *
   * <p><b>관리자 자격을 요구하지 않는다.</b> {@link #change}를 그대로 쓰면 {@link RequesterCheck#requireActiveAdmin}에
   * 걸려 <b>일반 부원의 탈퇴가 첫 단계에서 {@code 403}이 된다.</b> 여기서 인가의 근거는 관리자 자격이 아니라 <b>요청자와 대상이 같다는 것</b>이다.
   *
   * <p><b>나머지는 전부 같다</b> — 행 잠금, 마지막 활성 관리자 검사, 세션 반영 확인, {@code SUSPEND} 이력. 그 규칙들은 "누가 눌렀나"와 무관하게
   * 성립한다.
   */
  public void suspendSelf(Long userId) {
    run(userId, userId, Target.SUSPENDED, Authority.SELF, false);
  }

  /**
   * 요청자를 무엇으로 확인하는가.
   *
   * <p>이 값이 가르는 것은 <b>그 한 줄뿐이다.</b> 나머지 절차를 갈라놓으면 두 벌이 되고, 계정을 지우는 경로에서 한쪽만 고쳐진다.
   */
  private enum Authority {
    /** 관리자가 남(또는 자기)에게 하는 조작. */
    ADMIN,
    /** 본인이 자기에게 하는 조작 — 탈퇴의 선행 정지. */
    SELF
  }

  private AdminUserResponse run(
      Long requesterId,
      Long targetId,
      Target target,
      Authority authority,
      boolean enforceDirectSuspensionPolicy) {
    Applied applied =
        transaction.execute(
            ignored ->
                apply(requesterId, targetId, target, authority, enforceDirectSuspensionPolicy));
    AdminUserResponse changed = applied.response();

    /*
     * 세션 반영은 커밋 뒤다 (3-1 §3-1-5). @Transactional 대신 템플릿을 쓰는 이유가 이것이다 —
     * execute가 돌아온 시점에는 커밋도 끝나고 커넥션도 반납돼 있어, 갱신이 커넥션을 겹쳐 잡지
     * 않는다. 트랜잭션 안에서 부르면 되돌아간 변경이 세션에만 남는다.
     *
     * 정지는 반영을 확인해야 성공이다 (#197 리뷰 3차). 세션 반영은 실패를 삼키므로,
     * 확인하지 않으면 저장소 장애로 반영이 실패해도 200이 나가고 그 사람은 만료(30분)까지
     * 계속 쓴다 — "정지는 즉시 차단"(§2-2-3 MUST)이 조용히 깨진다.
     *
     * 해제는 반대 방향이라 그냥 알린다. 늦게 닿아도 그 사람이 아직 못 쓰는 것뿐이다.
     */
    boolean reflected = true;
    if (changed.status() == Status.SUSPENDED) {
      reflected = sessionSynchronizer.refreshReporting(changed.id());
    } else {
      sessionSynchronizer.refresh(List.of(changed.id()));
    }

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

    /*
     * 이력을 남긴 뒤에 던진다. 변경은 이미 커밋됐으므로 여기서 곧장 빠져나가면
     * "누가 무엇을 했는지"만 사라진다.
     */
    if (!reflected) {
      throw SessionSynchronizer.notReflected("정지", changed.id());
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

  private Applied apply(
      Long requesterId,
      Long targetId,
      Target target,
      Authority authority,
      boolean enforceDirectSuspensionPolicy) {
    Status desired = target == Target.SUSPENDED ? Status.SUSPENDED : Status.ACTIVE;
    Map<Long, User> locked =
        lockRowsInIdOrder(requesterId, targetId, desired, enforceDirectSuspensionPolicy);

    if (authority == Authority.ADMIN) {
      RequesterCheck.requireActiveAdmin(locked.get(requesterId), requesterId);
    } else {
      // 본인 탈퇴. 이용할 수 있는 계정인지만 본다 — 잠근 뒤 다시 보는 이유는 같다.
      RequesterCheck.requireActive(locked.get(requesterId), requesterId);
    }

    User user =
        locked.containsKey(targetId) ? locked.get(targetId) : orElseNotFound(); // 잠글 때 없던 행이다.

    /*
     * 요청자 재검증과 대상 존재 확인 뒤, 다른 상태 전이 검사와 마지막 활성 관리자 검사보다
     * 먼저 본다 (#296). 대상이 ADMIN이면 자기/타인, 현재 상태, 활성 관리자 수와 무관하게
     * 같은 FORBIDDEN이다. 잠금 후 값을 쓰므로 권한 회수와 엇갈려도 당시 최신 role로 판단한다.
     *
     * 삭제·탈퇴의 내부 선행 정지는 세션 폐기 실패를 안전하게 막는 절차라 이 정책을 타지 않는다.
     */
    if (enforceDirectSuspensionPolicy) {
      suspensionPolicy.requireDirectSuspensionAllowed(user, desired);
    }

    /*
     * 승인 대기 계정은 이 경로의 대상이 아니다. 계약이 정한 전이는 ACTIVE ↔ SUSPENDED뿐이고
     * (2-2 §2-2-3), 승인은 신청서를 낸 계정으로 한정되며 승인일시도 기록해야 한다.
     */
    if (user.getStatus() == Status.PENDING) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "승인 대기 중인 계정의 상태는 바꿀 수 없습니다.");
    }

    /*
     * 비활동 계정을 이 경로로 되살리지 않는다 (2-2 §2-2-3 MUST, #228). 정지는 허용한다 —
     * 비활동 부원도 곧바로 정지할 수 있어야 하고, 안전 조치가 두 단계가 되면 안 된다.
     *
     * 복구만 막는 이유는 경로가 하나여야 하기 때문이다. 여기서 받으면 이력이 ACTIVATE(정지
     * 해제)로 남아 REACTIVATE(학기 복구)와 섞이고, 학기 전환 규칙을 거치지 않은 개별 복구가
     * 생긴다. 한 명만 올려야 하면 복구 API에 그 한 명을 넣는다.
     */
    if (user.getStatus() == Status.INACTIVE && desired == Status.ACTIVE) {
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR, "비활동 계정은 이 경로로 되살릴 수 없습니다. 학기 복구를 써 주세요.");
    }

    if (desired == Status.SUSPENDED && isActiveAdmin(user)) {
      guardLastActiveAdmin(requesterId, targetId, authority);
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
   * <p>외부 상태 PATCH는 요청자와 대상만 잠근다. 대상이 {@code ADMIN}이면 정책에서 거절되고, {@code USER}이면 활성 관리자 수가 바뀌지 않기
   * 때문이다. 삭제·탈퇴의 내부 선행 정지만 <b>활성 관리자 전부</b>를 더해 수를 세는 동안 바뀌지 않게 한다.
   */
  private Map<Long, User> lockRowsInIdOrder(
      Long requesterId, Long targetId, Status desired, boolean enforceDirectSuspensionPolicy) {
    SortedSet<Long> ids = new TreeSet<>(List.of(requesterId, targetId));
    if (desired == Status.SUSPENDED && !enforceDirectSuspensionPolicy) {
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
  private void guardLastActiveAdmin(Long requesterId, Long targetId, Authority authority) {
    if (userRepository.countByRoleAndStatus(Role.ADMIN, Status.ACTIVE) > 1) {
      return;
    }
    throw new BusinessException(
        ErrorCode.FORBIDDEN, lastActiveAdminMessage(requesterId, targetId, authority));
  }

  /**
   * 사유는 같아도 <b>사용자가 할 수 있는 일이 다르다.</b>
   *
   * <p>탈퇴하려는 사람에게 "정지할 수 없습니다"라고 답하면, 자기가 무엇을 눌렀는지와 응답이 어긋나 <b>다음에 무엇을 해야 하는지 알 수 없다.</b> 막는 조건은
   * 하나이므로 검사도 하나로 두고, 문구만 들어온 문을 따른다.
   */
  private static String lastActiveAdminMessage(
      Long requesterId, Long targetId, Authority authority) {
    if (authority == Authority.SELF) {
      return "마지막 활성 관리자는 탈퇴할 수 없습니다. 다른 관리자를 먼저 지정해 주세요.";
    }
    return requesterId.equals(targetId)
        ? "마지막 활성 관리자는 자기 자신을 정지할 수 없습니다."
        : "마지막 활성 관리자는 정지할 수 없습니다. 다른 관리자를 먼저 활성화해 주세요.";
  }
}
