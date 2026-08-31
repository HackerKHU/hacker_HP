package org.hackerkhu.hackerhp.domain.user.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.hackerkhu.hackerhp.domain.audit.entity.AdminAction;
import org.hackerkhu.hackerhp.domain.audit.service.AdminActionRecorder;
import org.hackerkhu.hackerhp.domain.user.dto.RejectResponse;
import org.hackerkhu.hackerhp.domain.user.dto.RejectResponse.Failure;
import org.hackerkhu.hackerhp.domain.user.dto.RejectResponse.Reason;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 가입 일괄 거부 (spec 2-2 §2-2-2, 3-1 §3-1-4).
 *
 * <p><b>계정은 지우지 않는다.</b> 신청서에 받은 학번·학과·제출 시각만 지우고 {@code PENDING + applied_at NULL}인 미승인 상태로 되돌린다
 * (§2-2-2). 같은 {@code id}·구글 계정·세션으로 다시 신청할 수 있다.
 *
 * <p><b>대상은 {@code PENDING}뿐이다</b> (MUST). 이용 중인 회원을 이 경로로 초기화하면 회원 제거·정지 규칙을 우회한다 (§2-2-4).
 *
 * <p><b>세션을 갱신하거나 폐기하지 않는다.</b> 거부 전후의 인가 값은 모두 {@code USER/PENDING}이다. 기존 세션이 유지되어야 다음 요청에서 신청 폼으로
 * 돌아가 재신청할 수 있다.
 */
@Service
public class AdminUserRejectService {

  private static final Logger log = LoggerFactory.getLogger(AdminUserRejectService.class);

  private final UserRepository userRepository;
  private final AdminActionRecorder recorder;
  private final TransactionTemplate transaction;

  public AdminUserRejectService(
      UserRepository userRepository,
      AdminActionRecorder recorder,
      PlatformTransactionManager transactionManager) {
    this.userRepository = userRepository;
    this.recorder = recorder;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  /**
   * 고른 신청을 한 번에 거부한다.
   *
   * <p><b>실패를 예외로 던지지 않는다.</b> 한 건 때문에 트랜잭션이 되돌아가면 <b>성공한 거부까지 사라진다</b> — 관리자는 20명을 골랐는데 한 명이 이미
   * 승인됐다는 이유로 아무도 처리되지 않는다 (일괄 승인과 같은 규칙이다).
   */
  public RejectResponse reject(Long requesterId, List<Long> userIds) {
    Rejected applied = transaction.execute(ignored -> apply(requesterId, userIds));
    // 이미 미신청인 멱등 성공에는 새 조작이 없으므로 이력을 쌓지 않는다.
    recorder.record(requesterId, applied.changed(), AdminAction.REJECT, applied.occurredAt());
    return applied.response();
  }

  /** 거부가 <b>일어난 때</b>를 함께 돌려준다. 기록 시점에 잡으면 실제 순서와 어긋난다 (#143 리뷰). */
  private record Rejected(RejectResponse response, List<Long> changed, Instant occurredAt) {}

  private Rejected apply(Long requesterId, List<Long> userIds) {
    List<Long> rejected = new ArrayList<>();
    List<Long> changed = new ArrayList<>();
    List<Failure> failed = new ArrayList<>();

    /*
     * 요청자도 함께 잠근다. 인가는 세션 값으로 이루어지므로, 이 요청이 대상 행을 기다리는
     * 동안 다른 관리자가 요청자를 정지시켰을 수 있다. 잠금 순서는 저장소 전체에서 하나다 —
     * users 행은 id 오름차순.
     */
    List<Long> targets = userIds.stream().distinct().sorted().toList();
    List<Long> toLock =
        Stream.concat(Stream.of(requesterId), targets.stream()).distinct().sorted().toList();
    Map<Long, User> locked = new LinkedHashMap<>();
    toLock.forEach(
        id -> userRepository.findByIdForUpdate(id).ifPresent(found -> locked.put(id, found)));
    RequesterCheck.requireActiveAdmin(locked.get(requesterId), requesterId);

    for (Long userId : targets) {
      User user = locked.get(userId);
      if (user == null) {
        failed.add(new Failure(userId, Reason.NOT_FOUND));
        continue;
      }
      /*
       * PENDING이 아니면 거부하지 않는다. 목록에서 걸렀더라도 API를 직접 부르는 경로가
       * 남아 있고, 그 길로 ACTIVE를 초기화하면 §2-2-4의 규칙을 통째로 우회하게 된다.
       */
      if (user.getStatus() != Status.PENDING) {
        failed.add(new Failure(userId, Reason.NOT_PENDING));
        continue;
      }
      if (user.resetApplicationAfterRejection()) {
        changed.add(userId);
      }
      rejected.add(userId);
    }

    /*
     * 실제 변경 id를 따로 남긴다. 이력 저장이 실패하면 이 로그가 "누가 누구의 신청을
     * 초기화했나"의 마지막 단서다. rejected에는 멱등 성공도 들어가므로 그것만 남기면
     * 실제 변경과 no-op을 가를 수 없다 (#143 리뷰).
     */
    log.info(
        "가입 거부: 실제 변경 {}건 {}, 성공(멱등 포함) {}건 {}, 실패 {}건 {}",
        changed.size(),
        changed,
        rejected.size(),
        rejected,
        failed.size(),
        failed);
    return new Rejected(new RejectResponse(rejected, failed), changed, Instant.now());
  }
}
