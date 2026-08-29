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
import org.hackerkhu.hackerhp.domain.user.dto.DeactivateResponse;
import org.hackerkhu.hackerhp.domain.user.dto.ReactivateResponse;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
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
 * 학기 전환 — 일괄 비활성화와 복구 (spec 2-2 §2-2-3, 3-2 §3-2-6, #228 #230).
 *
 * <p>내리는 쪽은 본문이 없으면 기존대로 조건 전원, id가 있으면 선택 회원만 처리한다. 올리는 쪽은 언제나 id 목록이다.
 *
 * <p><b>대상에서 빼는 것</b> (MUST): {@code ADMIN}(운영자가 자기 손으로 자기 자료 접근을 끊는다), {@code SUSPENDED}(정지가 풀린다 —
 * 비활동은 자료 말고 다 되기 때문이다), {@code PENDING}(승인 절차를 건너뛴다).
 */
@Service
public class SemesterTransitionService {

  private static final Logger log = LoggerFactory.getLogger(SemesterTransitionService.class);

  /**
   * 세션을 다시 맞출 상태 — <b>바뀐 사람보다 넓다.</b>
   *
   * <p>이유는 {@link UserRepository#findIdsByRoleAndStatusIn}에 있다: 재요청이 복구 수단이려면 이미 내려간 사람도 대상에 남아야
   * 한다.
   */
  private static final List<Status> REFRESH_TARGETS = List.of(Status.ACTIVE, Status.INACTIVE);

  private final UserRepository userRepository;
  private final SessionSynchronizer sessionSynchronizer;
  private final AdminActionRecorder recorder;
  private final TransactionTemplate transaction;

  public SemesterTransitionService(
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
   * {@code ACTIVE}인 일반 부원 <b>전원</b>을 내린다.
   *
   * <p><b>차단이 강해지는 변경이다</b> (2-2 §2-2-5 MUST). 세션에 닿지 않으면 성공으로 답하지 않고, <b>변경은 되돌리지 않는다</b> — 같은 요청을
   * 다시 보내는 것이 복구 수단이다.
   */
  public DeactivateResponse deactivate(Long requesterId) {
    return deactivate(requesterId, null);
  }

  /**
   * {@code userIds}가 비어 있으면 전원, 값이 있으면 고른 회원만 내린다.
   *
   * <p><b>차단이 강해지는 변경이다</b> (2-2 §2-2-5 MUST). 세션에 닿지 않으면 성공으로 답하지 않고, <b>변경은 되돌리지 않는다</b> — 같은 요청을
   * 다시 보내는 것이 복구 수단이다.
   */
  public DeactivateResponse deactivate(Long requesterId, List<Long> userIds) {
    boolean selected = userIds != null && !userIds.isEmpty();
    List<Long> targets = selected ? new ArrayList<>(new LinkedHashSet<>(userIds)) : List.of();
    Applied applied =
        transaction.execute(
            ignored ->
                selected
                    ? applySelectedDeactivation(requesterId, targets)
                    : applyDeactivation(requesterId));

    /*
     * 세션 반영은 커밋 뒤다 (3-1 §3-1-5). 대상이 바뀐 사람보다 넓은 이유는 REFRESH_TARGETS에
     * 있다 — 이미 INACTIVE인 사람이 빠지면 재요청이 복구 수단이 되지 못한다.
     *
     * 반영 대상을 커밋 뒤에 다시 읽는다. 방금 바뀐 사람들이 INACTIVE로 보여야 하기 때문이다.
     */
    List<Long> toRefresh =
        selected
            ? userRepository.findIdsByIdInAndRoleAndStatusIn(
                targets, Role.USER, SemesterTransitionService.REFRESH_TARGETS)
            : userRepository.findIdsByRoleAndStatusIn(
                Role.USER, SemesterTransitionService.REFRESH_TARGETS);
    boolean reflected = sessionSynchronizer.refreshReporting(toRefresh);

    /*
     * 이력은 세션 반영보다 뒤다 (§2-2-7). 아무것도 바꾸지 않은 재요청은 남기지 않는다 —
     * 빈 목록이면 recorder가 그대로 지나간다.
     */
    recorder.record(requesterId, applied.changed(), AdminAction.DEACTIVATE, applied.occurredAt());

    /*
     * "반영 {}명"으로 적지 않는다. refreshReporting은 한 명이라도 못 닿으면 false를 주는데
     * 대상 수를 그대로 적으면 실패한 요청의 로그가 전원 반영된 것처럼 보인다 — 그때가
     * 바로 누가 아직 옛 권한으로 자료를 받아 가는지 찾아야 하는 순간이다.
     */
    log.warn(
        "학기 전환 — 비활성화: requesterId={} 선택 경로 {} / 내려간 {}명 / 실패 {}건 / 세션 반영 대상 {}명 / 전원 반영 {}",
        requesterId,
        selected,
        applied.changed().size(),
        applied.failed().size(),
        toRefresh.size(),
        reflected);

    // 이력을 남긴 뒤에 던진다. 변경은 이미 커밋됐으므로 여기서 빠져나가면 기록만 사라진다.
    if (!reflected) {
      throw SessionSynchronizer.notReflected("비활성화", requesterId);
    }
    return selected
        ? new DeactivateResponse(applied.changed(), applied.failed())
        : new DeactivateResponse(applied.changed());
  }

  /** 바뀐 id와 그 시각. 시각은 잠근 채 잡아야 이력의 "언제"가 실제 순서를 따른다 (§2-2-7). */
  private record Applied(
      List<Long> changed, List<DeactivateResponse.Failure> failed, Instant occurredAt) {}

  /**
   * <b>세는 것과 바꾸는 것이 한 연산이어야 한다</b> (spec 3-2 §3-2-6 MUST).
   *
   * <p>후보를 <b>잠그지 않고</b> 훑어 id를 모으고, 요청자와 합쳐 <b>오름차순으로</b> 하나씩 잠근 뒤, <b>잠근 값으로 다시 판단한다.</b> 동시에 도착한
   * 두 요청 중 뒤엣것은 앞엣것이 커밋될 때까지 기다렸다가 <b>이미 {@code INACTIVE}가 된 값</b>을 보므로, 양쪽 응답에 같은 id가 담기는 일이 없다.
   *
   * <p><b>잠금 순서가 저장소 전체에서 하나여야 한다</b> ({@code apps/api/AGENTS.md}). 범위째 바꾸는 한 문장은 스캔 순서대로 행을 잠그는데,
   * 상태 변경·일괄 승인은 <b>id 오름차순</b>으로 잠근다 — 관리자 A가 전환을 도는 사이 다른 관리자가 A보다 id가 작은 회원을 정지시키면 <b>두 트랜잭션이
   * 엇갈린 순서로 같은 행들을 원해 교착한다.</b>
   *
   * <p>엔티티를 거치므로 {@link User#deactivate(Instant)}가 {@code deactivated_at}까지 함께 세우고, 낙관적 잠금({@code
   * version})도 그대로 걸린다 — 네이티브 갱신은 그 둘을 손으로 재현해야 했다.
   */
  private Applied applyDeactivation(Long requesterId) {
    List<Long> candidates = userRepository.findIdsByRoleAndStatus(Role.USER, Status.ACTIVE);
    SortedSet<Long> toLock =
        new TreeSet<>(Stream.concat(Stream.of(requesterId), candidates.stream()).toList());

    Map<Long, User> locked = new LinkedHashMap<>();
    toLock.forEach(
        id -> userRepository.findByIdForUpdate(id).ifPresent(user -> locked.put(id, user)));

    /*
     * 요청자의 권한을 잠근 뒤 다시 확인한다 (MUST). 인가는 세션 값으로 이루어지므로, 여기까지
     * 오는 사이에 다른 관리자가 요청자를 정지시켰을 수 있다.
     */
    RequesterCheck.requireActiveAdmin(locked.get(requesterId), requesterId);

    // 잠근 채로 잡는다. 한 배치가 같은 값을 가져야 "직전 배치"를 고를 수 있다.
    Instant occurredAt = Instant.now();
    List<Long> changed = new ArrayList<>();
    for (Long candidateId : candidates.stream().sorted().toList()) {
      User target = locked.get(candidateId);
      /*
       * 훑은 뒤 잠그기 전에 바뀌었을 수 있다 — 그 사이 정지됐거나, 다른 관리자의 전환이
       * 먼저 커밋돼 이미 INACTIVE일 수 있다. 잠근 값으로 다시 본다.
       */
      if (target == null || target.getRole() != Role.USER || target.getStatus() != Status.ACTIVE) {
        continue;
      }
      target.deactivate(occurredAt);
      changed.add(candidateId);
    }
    return new Applied(changed, List.of(), occurredAt);
  }

  /** 선택 id의 첫 등장 순서로 처리하되, 요청자와 대상 행은 오름차순으로 잠근 뒤 최신 값으로 성공과 부분 실패를 가른다. */
  private Applied applySelectedDeactivation(Long requesterId, List<Long> targets) {
    SortedSet<Long> toLock =
        new TreeSet<>(Stream.concat(Stream.of(requesterId), targets.stream()).toList());
    Map<Long, User> locked = new LinkedHashMap<>();
    toLock.forEach(
        id -> userRepository.findByIdForUpdate(id).ifPresent(user -> locked.put(id, user)));

    RequesterCheck.requireActiveAdmin(locked.get(requesterId), requesterId);

    Instant occurredAt = Instant.now();
    List<Long> changed = new ArrayList<>();
    List<DeactivateResponse.Failure> failed = new ArrayList<>();
    for (Long targetId : targets) {
      User target = locked.get(targetId);
      if (target == null) {
        failed.add(new DeactivateResponse.Failure(targetId, DeactivateResponse.Reason.NOT_FOUND));
        continue;
      }
      if (target.getRole() != Role.USER || target.getStatus() != Status.ACTIVE) {
        failed.add(
            new DeactivateResponse.Failure(targetId, DeactivateResponse.Reason.NOT_ACTIVE_USER));
        continue;
      }
      target.deactivate(occurredAt);
      changed.add(targetId);
    }
    return new Applied(changed, failed, occurredAt);
  }

  /**
   * 고른 사람을 {@code ACTIVE}로 되돌린다.
   *
   * <p><b>완화되는 변경이라 세션 반영 실패를 성공으로 답한다</b> (2-2 §2-2-5). 늦게 닿아도 그 사람이 아직 자료를 못 보는 것뿐이다.
   *
   * <p>일부가 실패해도 {@code 200}이다 — 한 건 때문에 되돌리면 <b>성공한 복구까지 사라진다.</b>
   */
  public ReactivateResponse reactivate(Long requesterId, List<Long> userIds) {
    Restored restored = transaction.execute(ignored -> applyRestoration(requesterId, userIds));

    sessionSynchronizer.refresh(restored.response().reactivated());
    recorder.record(
        requesterId,
        restored.response().reactivated(),
        AdminAction.REACTIVATE,
        restored.occurredAt());

    log.info(
        "학기 전환 — 복구: requesterId={} 올라간 {}명 / 실패 {}건",
        requesterId,
        restored.response().reactivated().size(),
        restored.response().failed().size());
    return restored.response();
  }

  private record Restored(ReactivateResponse response, Instant occurredAt) {}

  private Restored applyRestoration(Long requesterId, List<Long> userIds) {
    List<Long> targets = userIds.stream().distinct().sorted().toList();
    /*
     * 요청자와 대상을 함께, id 오름차순으로 잠근다. 순서가 저장소 전체에서 하나여야 두
     * 트랜잭션이 엇갈린 순서로 같은 행들을 원하는 일이 없다 (일괄 승인과 같은 규칙).
     */
    SortedSet<Long> toLock =
        new TreeSet<>(Stream.concat(Stream.of(requesterId), targets.stream()).toList());
    Map<Long, User> locked = new LinkedHashMap<>();
    toLock.forEach(
        id -> userRepository.findByIdForUpdate(id).ifPresent(user -> locked.put(id, user)));

    RequesterCheck.requireActiveAdmin(locked.get(requesterId), requesterId);

    Instant occurredAt = Instant.now();
    List<Long> reactivated = new ArrayList<>();
    List<ReactivateResponse.Failure> failed = new ArrayList<>();
    for (Long targetId : targets) {
      User target = locked.get(targetId);
      if (target == null) {
        failed.add(new ReactivateResponse.Failure(targetId, ReactivateResponse.Reason.NOT_FOUND));
        continue;
      }
      if (target.getStatus() != Status.INACTIVE) {
        failed.add(
            new ReactivateResponse.Failure(targetId, ReactivateResponse.Reason.NOT_INACTIVE));
        continue;
      }
      target.restore();
      reactivated.add(targetId);
    }
    return new Restored(new ReactivateResponse(reactivated, failed), occurredAt);
  }
}
