package org.hackerkhu.hackerhp.domain.user.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.hackerkhu.hackerhp.domain.audit.entity.AdminAction;
import org.hackerkhu.hackerhp.domain.audit.service.AdminActionRecorder;
import org.hackerkhu.hackerhp.domain.user.dto.ApproveResponse;
import org.hackerkhu.hackerhp.domain.user.dto.ApproveResponse.Failure;
import org.hackerkhu.hackerhp.domain.user.dto.ApproveResponse.Reason;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.global.auth.SessionSynchronizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 가입 일괄 승인 (spec 2-2 §2-2-2, 3-1 §3-1-4 ③). */
@Service
public class AdminUserApprovalService {

  private static final Logger log = LoggerFactory.getLogger(AdminUserApprovalService.class);

  private final UserRepository userRepository;
  private final SessionSynchronizer sessionSynchronizer;
  private final AdminActionRecorder recorder;
  private final TransactionTemplate transaction;

  public AdminUserApprovalService(
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
   * 고른 계정을 한 번에 승인한다.
   *
   * <p><b>실패를 예외로 던지지 않는다.</b> 한 건 때문에 트랜잭션이 되돌아가면 <b>성공한 승인까지 사라진다</b> — 관리자는 20명을 골랐는데 한 명이 신청서를
   * 내지 않았다는 이유로 아무도 승인되지 않는다. 계약도 "그 건은 실패로 집계하고 그 계정의 상태를 바꾸지 않는다"고 한다 (3-2 §3-2-6 MUST).
   *
   * <p><b>id 오름차순으로 잠근다.</b> 두 관리자가 겹치는 목록을 서로 다른 순서로 보내면 각자 상대가 쥔 행을 기다려 교착한다. 순서를 하나로 정해 두면 그런 짝이
   * 생기지 않는다. 요청자 행도 같은 순서에 끼워 넣는다.
   *
   * <p><b>중복은 여기서 걸러낸다.</b> 요청 DTO에서 걸러내면 {@code @Size} 상한이 원본이 아니라 줄어든 목록을 보게 되어, 같은 id를 101번 담은
   * 요청이 상한을 그냥 통과한다. 거르지 않고 두면 응답의 건수가 부풀려진다 — 화면은 배열 길이를 그대로 "N명을 승인했습니다"로 읽는다.
   */
  public ApproveResponse approve(Long requesterId, List<Long> userIds) {
    Approved applied = transaction.execute(ignored -> apply(requesterId, userIds));
    ApproveResponse result = applied.response();

    /*
     * 세션 반영은 커밋 뒤다 (3-1 §3-1-5). 승인된 회원이 세션 만료까지 403 PENDING_APPROVAL에
     * 갇히지 않게 하려는 것이고(T-33), 선택된 전원을 넘긴다 (2-2 §2-2-2 — 한 명도 빠지지 않는다).
     */
    sessionSynchronizer.refresh(result.approved());

    /*
     * 이력은 세션 반영보다 뒤다. 정지든 승인이든 반영이 먼저 끝나야 하고(2-2 §2-2-3 즉시 차단),
     * 이력은 늦어도 되는 정보다. 실패해도 승인은 이미 커밋돼 있다 (§2-2-7).
     *
     * 실패한 건은 남기지 않는다 — 아무것도 바뀌지 않았기 때문이다.
     */
    recorder.record(requesterId, result.approved(), AdminAction.APPROVE, applied.occurredAt());
    return result;
  }

  /**
   * 승인이 <b>일어난 때</b>를 함께 돌려준다.
   *
   * <p>이력을 남기는 시점에 잡으면 커밋과 세션 반영이 끝난 뒤라 실제 조작 순서와 어긋날 수 있다 — 같은 회원을 다른 관리자가 곧이어 정지시키면 정지가 승인보다 앞선
   * 것으로 남는다. 계정 행을 잠근 채 잡아 그 잠금이 순서를 세우게 한다.
   */
  private record Approved(ApproveResponse response, Instant occurredAt) {}

  private Approved apply(Long requesterId, List<Long> userIds) {
    List<Long> approved = new ArrayList<>();
    List<Failure> failed = new ArrayList<>();

    /*
     * 요청자도 함께 잠근다. 인가는 세션 값으로 이루어지므로, 이 요청이 대상 행을 기다리는 동안
     * 다른 관리자가 요청자를 정지시켰을 수 있다 — 그대로 두면 정지된 관리자의 승인이 커밋된다.
     * 잠금 순서는 저장소 전체에서 하나다: users 행은 id 오름차순.
     */
    List<Long> targets = userIds.stream().distinct().sorted().toList();
    List<Long> toLock =
        Stream.concat(Stream.of(requesterId), targets.stream()).distinct().sorted().toList();
    Map<Long, User> locked = new LinkedHashMap<>();
    toLock.forEach(
        id -> userRepository.findByIdForUpdate(id).ifPresent(found -> locked.put(id, found)));
    RequesterCheck.requireActiveAdmin(locked.get(requesterId), requesterId);

    for (Long userId : targets) {
      /*
       * 잠근 채로 다시 읽는다. 화면이 목록을 그린 뒤 관리자가 확인 창을 누르기까지의 사이에
       * 상태가 바뀔 수 있고, 신청서 제출도 같은 행을 노린다 (T-56). 잠그지 않으면 각자
       * 읽어둔 값을 보고 둘 다 통과해, 관리자가 심사한 내용과 저장된 내용이 달라진다.
       */
      User user = locked.get(userId);
      if (user == null) {
        failed.add(new Failure(userId, Reason.NOT_FOUND));
        continue;
      }
      if (user.getStatus() != Status.PENDING) {
        failed.add(new Failure(userId, Reason.NOT_PENDING));
        continue;
      }
      // 목록에서 걸렀더라도 API를 직접 부르는 경로가 남아 있다 (T-49).
      if (user.getAppliedAt() == null) {
        failed.add(new Failure(userId, Reason.NOT_APPLIED));
        continue;
      }
      user.approve();
      approved.add(userId);
    }

    /*
     * 승인된 id를 함께 남긴다. 이력 저장이 실패하면 이 로그가 "누가 누구를 승인했나"의
     * 마지막 단서인데, 건수만으로는 누구인지 알 수 없어 보정할 수도 없다.
     */
    log.info("가입 승인: 성공 {}건 {}, 실패 {}건 {}", approved.size(), approved, failed.size(), failed);
    return new Approved(new ApproveResponse(approved, failed), Instant.now());
  }
}
