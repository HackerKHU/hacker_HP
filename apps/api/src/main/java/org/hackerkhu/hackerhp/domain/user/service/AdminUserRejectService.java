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
import org.hackerkhu.hackerhp.global.auth.SessionSynchronizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 가입 일괄 거부 (spec 2-2 §2-2-2, 3-1 §3-1-4).
 *
 * <p><b>계정 레코드를 지운다. 별도 상태를 두지 않는다</b> (§2-2-2). 거부된 사람은 같은 이메일로 재신청할 수 있어야 하는데, 상태로 남기면 그 계정이
 * {@code email}·{@code google_sub} UNIQUE를 붙잡아 <b>다시 가입할 수 없다.</b>
 *
 * <p><b>대상은 {@code PENDING}뿐이다</b> (MUST). 이용 중인 회원을 이 경로로 지우면 "제거"가 되는데, 그쪽은 세션 폐기·정지 선행·콘텐츠 처리 같은
 * 규칙이 따로 붙는다 (§2-2-4). 거부는 <b>남긴 것이 없는 계정</b>을 지우는 일이라 그 규칙이 필요 없다.
 *
 * <p><b>세션은 갱신하지 않고 폐기한다.</b> 지울 계정에는 세션에 써넣을 값이 없다 — {@link SessionSynchronizer}가 계정을 찾지 못하면 그 사람의
 * 세션을 지운다 (#127).
 */
@Service
public class AdminUserRejectService {

  private static final Logger log = LoggerFactory.getLogger(AdminUserRejectService.class);

  private final UserRepository userRepository;
  private final SessionSynchronizer sessionSynchronizer;
  private final AdminActionRecorder recorder;
  private final TransactionTemplate transaction;

  public AdminUserRejectService(
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
   * 고른 신청을 한 번에 거부한다.
   *
   * <p><b>실패를 예외로 던지지 않는다.</b> 한 건 때문에 트랜잭션이 되돌아가면 <b>성공한 거부까지 사라진다</b> — 관리자는 20명을 골랐는데 한 명이 이미
   * 승인됐다는 이유로 아무도 처리되지 않는다 (일괄 승인과 같은 규칙이다).
   */
  public RejectResponse reject(Long requesterId, List<Long> userIds) {
    Rejected applied = transaction.execute(ignored -> apply(requesterId, userIds));
    RejectResponse result = applied.response();

    /*
     * 세션 폐기는 커밋 뒤다 (3-1 §3-1-5). 계정이 사라졌으므로 갱신할 값이 없고, 갱신 경로가
     * 그 사실을 보고 세션을 지운다 — 남기면 계정 없는 사람이 만료까지 인증된다 (§2-2-4).
     */
    sessionSynchronizer.refresh(result.rejected());

    // 이력은 세션보다 뒤다. 차단이 먼저이고 이력은 늦어도 되는 정보다 (§2-2-7).
    recorder.record(requesterId, result.rejected(), AdminAction.REJECT, applied.occurredAt());
    return result;
  }

  /** 거부가 <b>일어난 때</b>를 함께 돌려준다. 기록 시점에 잡으면 실제 순서와 어긋난다 (#143 리뷰). */
  private record Rejected(RejectResponse response, Instant occurredAt) {}

  private Rejected apply(Long requesterId, List<Long> userIds) {
    List<Long> rejected = new ArrayList<>();
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
       * 남아 있고, 그 길로 ACTIVE를 지우면 §2-2-4의 규칙을 통째로 우회하게 된다.
       */
      if (user.getStatus() != Status.PENDING) {
        failed.add(new Failure(userId, Reason.NOT_PENDING));
        continue;
      }
      userRepository.delete(user);
      rejected.add(userId);
    }

    /*
     * 거부된 id를 함께 남긴다. 이력 저장이 실패하면 이 로그가 "누가 누구를 거부했나"의
     * 마지막 단서인데, 건수만으로는 누구인지 알 수 없어 보정할 수도 없다 (#143 리뷰).
     */
    log.info("가입 거부: 성공 {}건 {}, 실패 {}건 {}", rejected.size(), rejected, failed.size(), failed);
    return new Rejected(new RejectResponse(rejected, failed), Instant.now());
  }
}
