package org.hackerkhu.hackerhp.domain.audit.service;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import org.hackerkhu.hackerhp.domain.audit.entity.AdminAction;
import org.hackerkhu.hackerhp.domain.audit.entity.AdminActionLog;
import org.hackerkhu.hackerhp.domain.audit.repository.AdminActionLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 조작을 이력에 남긴다 (spec 2-2 §2-2-7, #143).
 *
 * <p><b>기록은 조작의 조건이 아니다.</b> 남기지 못해도 승인·정지 자체는 성공한다 — 여기서 예외를 올리면 <b>이미 커밋된 조작까지 실패한 것처럼 보이고</b>,
 * 관리자는 "정지 실패"로 읽고 다시 누른다. 실제로는 정지돼 있다.
 *
 * <p>그래서 <b>변경이 커밋된 뒤에 부른다</b> (MUST). 변경 트랜잭션 안에서 부르면 반대가 된다 — 기록이 실패할 때 승인까지 되돌아간다.
 *
 * <p><b>세션 반영보다 뒤다.</b> 정지는 즉시 차단이어야 하고(2-2 §2-2-3 MUST) 이력은 늦어도 되는 정보다. 순서를 뒤집으면 기록이 느릴 때 차단이 밀린다.
 */
@Service
public class AdminActionRecorder {

  /** 서로 다른 action을 한 번의 배치로 기록하기 위한 항목. */
  public record ActionEntry(Long targetId, AdminAction action) {}

  private static final Logger log = LoggerFactory.getLogger(AdminActionRecorder.class);

  private final AdminActionLogRepository logs;
  private final TransactionTemplate transaction;

  public AdminActionRecorder(
      AdminActionLogRepository logs, PlatformTransactionManager transactionManager) {
    this.logs = logs;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  public void record(Long actorId, Long targetId, AdminAction action, Instant occurredAt) {
    record(actorId, List.of(targetId), action, occurredAt);
  }

  /**
   * <b>대상마다 한 행을 남긴다.</b> 일괄 승인을 한 행으로 뭉치면 "이 사람이 언제 승인됐나"를 물을 수 없다 — 이력의 주된 질문이 그것이다.
   *
   * @param actorId 조작한 관리자. 대상이 비었으면 아무것도 남기지 않는다
   * @param occurredAt <b>조작이 일어난 때.</b> 여기서 잡지 않는다 — 이 메서드는 커밋과 세션 반영이 끝난 뒤에 불리므로, 여기서 잡은 시각은 조작
   *     순서와 어긋날 수 있다 ({@link AdminActionLog#of})
   */
  public void record(
      Long actorId, Collection<Long> targetIds, AdminAction action, Instant occurredAt) {
    if (targetIds.isEmpty()) {
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      /*
       * 여기서 끊는다. 변경 트랜잭션 안에서 부르면 기록 실패가 조작을 되돌리는데,
       * 그것은 조용히 어긋나는 종류라 나중에 알아채기 어렵다 (2-2 §2-2-7).
       */
      throw new IllegalStateException("조작 이력은 변경이 커밋된 뒤에 남겨야 한다 (spec 2-2 §2-2-7).");
    }

    List<Long> targets = targetIds.stream().distinct().sorted().toList();
    List<AdminActionLog> entries =
        targets.stream()
            .map(targetId -> AdminActionLog.of(actorId, targetId, action, occurredAt))
            .toList();
    try {
      transaction.executeWithoutResult(ignored -> logs.saveAll(entries));
    } catch (RuntimeException e) {
      /*
       * 삼키되 조용히 넘기지 않는다. 이력이 비면 "누가 누구를 정지했나"에 답할 수 없게 되므로
       * 그 사실 자체가 운영 정보다.
       *
       * 대상 id를 반드시 남긴다. 저장이 실패하면 이 로그가 남은 유일한 단서이고, 누구에게
       * 일어난 일인지 없으면 나중에 보정할 수도 없다.
       */
      log.error(
          "조작 이력을 남기지 못했다: actorId={} action={} occurredAt={} targetIds={}",
          actorId,
          action,
          occurredAt,
          targets,
          e);
    }
  }

  /**
   * 서로 다른 action을 가진 성공 전이를 target id 오름차순으로 한 번에 기록한다 (#313).
   *
   * <p>기존 동일 action 배치 API는 그대로 두어 하위 호환을 유지한다.
   */
  public void record(Long actorId, Collection<ActionEntry> actionEntries, Instant occurredAt) {
    if (actionEntries.isEmpty()) {
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("조작 이력은 변경이 커밋된 뒤에 남겨야 한다 (spec 2-2 §2-2-7).");
    }

    List<ActionEntry> ordered =
        actionEntries.stream().sorted(Comparator.comparing(ActionEntry::targetId)).toList();
    List<AdminActionLog> entries =
        ordered.stream()
            .map(entry -> AdminActionLog.of(actorId, entry.targetId(), entry.action(), occurredAt))
            .toList();
    try {
      transaction.executeWithoutResult(ignored -> logs.saveAll(entries));
    } catch (RuntimeException e) {
      log.error(
          "조작 이력을 남기지 못했다: actorId={} occurredAt={} entries={}", actorId, occurredAt, ordered, e);
    }
  }
}
