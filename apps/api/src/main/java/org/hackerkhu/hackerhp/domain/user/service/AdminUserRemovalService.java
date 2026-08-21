package org.hackerkhu.hackerhp.domain.user.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import org.hackerkhu.hackerhp.domain.audit.entity.AdminAction;
import org.hackerkhu.hackerhp.domain.audit.service.AdminActionRecorder;
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
 * 회원 제거 (spec 2-2 §2-2-4).
 *
 * <p><b>정지를 먼저 확정하고 나서 지운다</b> (MUST). 세션 폐기는 계정이 사라진 <b>뒤에</b> 일어나므로 실패할 수 있고, 실패해도 되돌릴 방법이 없다 —
 * 계정이 이미 없으니 트랜잭션을 물릴 수도, 같은 요청을 다시 보내 복구할 수도 없다. 그러면 {@code ACTIVE}·{@code ADMIN} 세션이 만료까지 그대로 인증에
 * 쓰인다.
 *
 * <p>정지를 먼저 확정하면 <b>어느 지점에서 실패하든 그 사람은 이미 막혀 있다.</b>
 *
 * <table>
 *   <caption>실패 지점별 결과</caption>
 *   <tr><th>어디서 실패하나<th>남는 상태<th>결과
 *   <tr><td>정지 반영<td>{@code SUSPENDED}<td>이미 차단. 재요청으로 복구
 *   <tr><td>삭제<td>{@code SUSPENDED}<td>이미 차단. 재요청으로 복구
 *   <tr><td>세션 폐기<td>계정은 없고 세션만<td>그 세션은 {@code SUSPENDED}라 {@code 403}으로 막힌다
 * </table>
 *
 * <p>정지 경로는 이미 즉시 차단이 보장된다 (§2-2-3 MUST) — <b>새 장치를 만드는 것이 아니라 검증된 차단을 앞에 세우는 것</b>이다.
 */
@Service
public class AdminUserRemovalService {

  private static final Logger log = LoggerFactory.getLogger(AdminUserRemovalService.class);

  private final UserRepository userRepository;
  private final AdminUserStatusService statusService;
  private final SessionSynchronizer sessionSynchronizer;
  private final AdminActionRecorder recorder;
  private final TransactionTemplate transaction;

  public AdminUserRemovalService(
      UserRepository userRepository,
      AdminUserStatusService statusService,
      SessionSynchronizer sessionSynchronizer,
      AdminActionRecorder recorder,
      PlatformTransactionManager transactionManager) {
    this.userRepository = userRepository;
    this.statusService = statusService;
    this.sessionSynchronizer = sessionSynchronizer;
    this.recorder = recorder;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  /**
   * 계정을 지운다.
   *
   * <p>그 사람이 올린 자료·공지·활동사진은 <b>남는다</b> — 작성자 자리만 비고 응답이 "탈퇴한 회원"을 채운다 (§2-2-4, 3-2 §3-2-2). 즐겨찾기는
   * 함께 사라진다. 그 보장은 FK가 한다.
   *
   * @return 본인을 지웠으면 {@code true} — 부르는 쪽이 <b>지금 요청의 세션과 토큰까지</b> 끝내야 한다
   */
  public boolean remove(Long requesterId, Long targetId) {
    /*
     * ① 정지를 먼저 확정한다. 여기서 마지막 활성 관리자 검사도 함께 걸린다 (§2-2-7) —
     * 정지가 막히면 제거도 막혀야 하고, 그 판단은 이미 그쪽에 있다. 두 번 볼 이유가 없다.
     *
     * 이미 SUSPENDED면 아무것도 바뀌지 않고 지나간다 (재요청이 복구 수단이다).
     */
    suspendFirst(requesterId, targetId);

    /*
     * ①이 세션에 실제로 닿았는지 확인하고 나서 지운다 (#197 리뷰).
     *
     * 세션 반영은 실패를 삼킨다 — 이미 커밋된 변경까지 실패한 것처럼 보이면 안 되기
     * 때문이다. 그 관용이 여기서는 위험하다: 반영이 조용히 실패했는데 계정을 지우면
     * 그 세션은 ACTIVE·ADMIN인 채로 남고, 계정이 없어 되돌릴 방법도 없다.
     *
     * 여기서 멈추면 대상은 SUSPENDED로 남는다 — 이미 차단된 상태이고, 관리자가 같은
     * 요청을 다시 보내 복구할 수 있다.
     */
    if (!sessionSynchronizer.refreshReporting(targetId)) {
      /*
       * 계약에 이 상황을 가리키는 코드가 없다 (§3-2-7). 그대로 올려 500 INTERNAL_ERROR가
       * 나가게 둔다 — 실제로 서버 쪽 장애이고, 관리자가 할 수 있는 일은 다시 시도하는 것뿐이다.
       */
      throw new IllegalStateException(
          "정지가 세션에 반영되지 않아 제거를 멈춘다: requesterId=" + requesterId + " targetId=" + targetId);
    }

    // ② 잠근 채 지운다.
    Instant removedAt = transaction.execute(ignored -> delete(requesterId, targetId));

    /*
     * ③ 세션 폐기는 커밋 뒤다 (3-1 §3-1-5). 계정이 사라졌으므로 갱신 경로가 그 사실을 보고
     * 세션을 지운다 — 필터는 매 요청 users를 읽지 않으므로(결정 12) 남기면 계정 없는
     * 사람이 만료까지 인증된다.
     */
    sessionSynchronizer.refresh(List.of(targetId));

    // ④ 이력은 세션보다 뒤다. 차단이 먼저이고 이력은 늦어도 되는 정보다.
    recorder.record(requesterId, targetId, AdminAction.REMOVE, removedAt);

    return requesterId.equals(targetId);
  }

  /**
   * 지우기 전에 정지를 확정한다.
   *
   * <p>대상이 {@code PENDING}이면 정지 경로를 탈 수 없다 (§2-2-3의 전이는 {@code ACTIVE} ↔ {@code SUSPENDED}뿐이다). 그
   * 계정은 <b>남긴 것이 없고 세션도 보호 API를 열지 못하므로</b> 그대로 지운다 — 가입 거부와 같은 상태다.
   */
  private void suspendFirst(Long requesterId, Long targetId) {
    User target =
        userRepository
            .findById(targetId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "회원을 찾을 수 없습니다."));
    if (target.getStatus() == Status.PENDING) {
      return;
    }
    statusService.change(
        requesterId,
        targetId,
        org.hackerkhu.hackerhp.domain.user.dto.StatusChangeRequest.Target.SUSPENDED);
  }

  private Instant delete(Long requesterId, Long targetId) {
    Map<Long, User> locked = lockRowsInIdOrder(requesterId, targetId);

    /*
     * 본인을 지울 때는 요청자를 다시 확인하지 않는다.
     *
     * ①이 방금 이 사람을 SUSPENDED로 만들었으므로, 여기서 "요청자는 ACTIVE ADMIN이어야
     * 한다"를 다시 보면 자기 손으로 만든 상태에 걸려 본인 제거가 영영 실패한다.
     *
     * 인가가 느슨해지는 것은 아니다 — ①의 상태 변경이 같은 검사를 이미 통과했고(그것이
     * 실패하면 여기까지 오지 못한다), 마지막 활성 관리자 검사도 거기서 함께 걸린다.
     */
    if (!requesterId.equals(targetId)) {
      RequesterCheck.requireActiveAdmin(locked.get(requesterId), requesterId);
    }

    User target = locked.get(targetId);
    if (target == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "회원을 찾을 수 없습니다.");
    }

    /*
     * ①이 만든 차단이 아직 서 있는지 확인한다 (#197 리뷰 2차).
     *
     * ①은 커밋되며 잠금을 놓는다. 그 사이에 다른 관리자가 PATCH .../status로 대상을 다시
     * ACTIVE로 돌리면, 여기까지 오는 동안 그 사람의 세션도 ACTIVE로 되살아난다 — 그대로
     * 지우면 "정지를 먼저 확정한다"는 전제가 무너진 채 계정만 사라지고, 세션 폐기가
     * 실패하면 계정 없는 ACTIVE 세션이 만료까지 인증된다.
     *
     * 지우지 않고 멈춘다. 대상은 다른 관리자가 의도적으로 되살린 ACTIVE 상태이므로,
     * 여기서 조용히 다시 정지시키는 것보다 관리자에게 되돌려 판단하게 하는 편이 맞다.
     * 같은 요청을 다시 보내면 ①부터 다시 밟는다.
     *
     * ★ 이 검사가 §2-2-7의 "활성 관리자 0명" 도 함께 막는다. 활성이 아닌 계정은 활성
     *   관리자로 세지 않으므로, 여기를 통과한 대상을 지워도 그 수는 줄지 않는다. 잠근
     *   행을 기준으로 보기 때문에 동시에 들어온 다른 조작은 기다렸다가 줄어든 수를
     *   본다 — "0명이 되는" 조작이 남아 있다면 그쪽이 자기 검사에 걸린다.
     */
    if (target.getStatus() == Status.ACTIVE) {
      throw new BusinessException(ErrorCode.CONCURRENT_CHANGE);
    }

    // 잠근 채로 잡는다. 이 시각이 이력의 "언제"가 된다 (#143 리뷰).
    Instant removedAt = Instant.now();
    userRepository.delete(target);
    log.warn("회원 제거: requesterId={} targetId={}", requesterId, targetId);
    return removedAt;
  }

  /**
   * <b>잠금 순서는 저장소 전체에서 하나다</b> — {@code users} 행은 id 오름차순.
   *
   * <p>활성 관리자 전부를 함께 잠근다. 정지 단계에서 이미 검사했지만 그 잠금은 커밋과 함께 풀리므로, 삭제까지 오는 사이에 그 수가 달라질 수 있다.
   */
  private Map<Long, User> lockRowsInIdOrder(Long requesterId, Long targetId) {
    SortedSet<Long> ids = new TreeSet<>(List.of(requesterId, targetId));
    ids.addAll(userRepository.findIdsByRoleAndStatus(Role.ADMIN, Status.ACTIVE));
    Map<Long, User> locked = new LinkedHashMap<>();
    ids.forEach(id -> userRepository.findByIdForUpdate(id).ifPresent(user -> locked.put(id, user)));
    return locked;
  }
}
