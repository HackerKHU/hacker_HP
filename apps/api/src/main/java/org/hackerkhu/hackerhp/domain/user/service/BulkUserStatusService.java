package org.hackerkhu.hackerhp.domain.user.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.hackerkhu.hackerhp.domain.audit.entity.AdminAction;
import org.hackerkhu.hackerhp.domain.audit.service.AdminActionRecorder;
import org.hackerkhu.hackerhp.domain.audit.service.AdminActionRecorder.ActionEntry;
import org.hackerkhu.hackerhp.domain.user.dto.BulkStatusChangeRequest.TargetStatus;
import org.hackerkhu.hackerhp.domain.user.dto.BulkStatusChangeResponse;
import org.hackerkhu.hackerhp.domain.user.dto.BulkStatusChangeResponse.Failure;
import org.hackerkhu.hackerhp.domain.user.dto.BulkStatusChangeResponse.Reason;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.global.auth.SessionSynchronizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 선택 회원 일괄 활성화·정지 (spec 2-2 §2-2-3, 3-2 §3-2-6, #313). */
@Service
public class BulkUserStatusService {

  private static final Logger log = LoggerFactory.getLogger(BulkUserStatusService.class);

  private final UserRepository users;
  private final AdminSuspensionPolicy suspensionPolicy;
  private final SessionSynchronizer sessions;
  private final AdminActionRecorder recorder;
  private final TransactionTemplate transaction;

  public BulkUserStatusService(
      UserRepository users,
      AdminSuspensionPolicy suspensionPolicy,
      SessionSynchronizer sessions,
      AdminActionRecorder recorder,
      PlatformTransactionManager transactionManager) {
    this.users = users;
    this.suspensionPolicy = suspensionPolicy;
    this.sessions = sessions;
    this.recorder = recorder;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  /**
   * 원본 배열 검증이 끝난 뒤 첫 등장만 남겨 한 트랜잭션에서 처리한다.
   *
   * <p>응답은 첫 등장 순서를 유지하지만, 요청자와 대상 행 잠금은 오름차순이다. 두 순서를 합치면 응답 계약이나 교착 방지 중 하나를 깨뜨린다.
   */
  public BulkStatusChangeResponse change(
      Long requesterId, List<Long> rawUserIds, TargetStatus targetStatus) {
    List<Long> targets = new ArrayList<>(new LinkedHashSet<>(rawUserIds));
    Applied applied = transaction.execute(ignored -> apply(requesterId, targets, targetStatus));

    /* 세션 반영은 커밋과 커넥션 반납이 끝난 뒤다 (spec 3-1 §3-1-5). */
    boolean reflected = true;
    if (targetStatus == TargetStatus.ACTIVE) {
      // 완화 변경: 끝까지 시도하되 실패는 SessionSynchronizer가 기록하고 200을 유지한다.
      sessions.refresh(applied.response().processed());
    } else {
      // 차단 강화: 첫 실패에서 멈추지 않고 전원 시도한 뒤 성공 여부를 합친다.
      reflected = sessions.refreshReporting(applied.response().processed());
    }

    // 이력은 세션 반영보다 뒤이고, 서로 다른 action을 target id 순으로 한 번에 남긴다.
    recorder.record(requesterId, applied.actions(), applied.occurredAt());

    if (!reflected) {
      throw new IllegalStateException("일괄 정지가 하나 이상의 세션에 반영되지 않았다: requesterId=" + requesterId);
    }

    log.info(
        "회원 일괄 상태 변경: requesterId={} targetStatus={} processed={} failed={} changed={}",
        requesterId,
        targetStatus,
        applied.response().processed().size(),
        applied.response().failed().size(),
        applied.actions().size());
    return applied.response();
  }

  private record Applied(
      BulkStatusChangeResponse response, List<ActionEntry> actions, Instant occurredAt) {}

  private Applied apply(Long requesterId, List<Long> targets, TargetStatus targetStatus) {
    SortedSet<Long> toLock =
        new TreeSet<>(Stream.concat(Stream.of(requesterId), targets.stream()).toList());
    Map<Long, User> locked = new LinkedHashMap<>();
    toLock.forEach(id -> users.findByIdForUpdate(id).ifPresent(user -> locked.put(id, user)));

    // 대상의 존재·role·status를 판단하기 전에 요청자를 먼저 재검증한다.
    RequesterCheck.requireActiveAdmin(locked.get(requesterId), requesterId);

    Instant occurredAt = Instant.now();
    List<Long> processed = new ArrayList<>();
    List<Failure> failed = new ArrayList<>();
    List<ActionEntry> actions = new ArrayList<>();

    for (Long targetId : targets) {
      User target = locked.get(targetId);
      if (target == null) {
        failed.add(new Failure(targetId, Reason.NOT_FOUND));
        continue;
      }

      if (targetStatus == TargetStatus.SUSPENDED) {
        applySuspension(targetId, target, processed, failed, actions);
      } else {
        applyActivation(targetId, target, processed, failed, actions);
      }
    }

    return new Applied(
        new BulkStatusChangeResponse(targetStatus, processed, failed), actions, occurredAt);
  }

  private void applyActivation(
      Long targetId,
      User target,
      List<Long> processed,
      List<Failure> failed,
      List<ActionEntry> actions) {
    switch (target.getStatus()) {
      case PENDING -> {
        if (target.getAppliedAt() == null) {
          failed.add(new Failure(targetId, Reason.NOT_APPLIED));
          return;
        }
        target.approve();
        actions.add(new ActionEntry(targetId, AdminAction.APPROVE));
      }
      case INACTIVE -> {
        target.restore();
        actions.add(new ActionEntry(targetId, AdminAction.REACTIVATE));
      }
      case SUSPENDED -> {
        target.reactivate();
        actions.add(new ActionEntry(targetId, AdminAction.ACTIVATE));
      }
      case ACTIVE -> {
        // 멱등 성공. 이력은 없지만 세션 재반영 대상에는 남긴다.
      }
    }
    processed.add(targetId);
  }

  private void applySuspension(
      Long targetId,
      User target,
      List<Long> processed,
      List<Failure> failed,
      List<ActionEntry> actions) {
    // #296 정책이 상태별 판정보다 먼저다. SUSPENDED ADMIN도 멱등 성공이 아니다.
    if (suspensionPolicy.blocksDirectSuspension(target, Status.SUSPENDED)) {
      failed.add(new Failure(targetId, Reason.ADMIN_SUSPEND_REQUIRES_ROLE_REVOCATION));
      return;
    }
    if (target.getStatus() == Status.PENDING) {
      failed.add(new Failure(targetId, Reason.PENDING_NOT_ALLOWED));
      return;
    }
    if (target.getStatus() != Status.SUSPENDED) {
      target.suspend();
      actions.add(new ActionEntry(targetId, AdminAction.SUSPEND));
    }
    processed.add(targetId);
  }
}
