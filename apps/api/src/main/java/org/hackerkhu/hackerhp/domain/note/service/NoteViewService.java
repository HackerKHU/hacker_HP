package org.hackerkhu.hackerhp.domain.note.service;

import org.hackerkhu.hackerhp.domain.note.dto.NoteDetailResponse;
import org.hackerkhu.hackerhp.domain.note.repository.NoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 자료 상세를 읽고 <b>조회수를 올린다</b> (spec 3-2 §3-2-4, #245).
 *
 * <p><b>{@link NoteQueryService}에 둘 수 없다.</b> 그 클래스는 통째로 {@code @Transactional(readOnly = true)}라
 * 안에서 {@code UPDATE}를 하면 PostgreSQL이 <i>cannot execute UPDATE in a read-only transaction</i>으로
 * 거절한다. 읽기 전용을 푸는 것도 답이 아니다 — 같은 트랜잭션에 두면 <b>증가 실패가 조회까지 되돌린다.</b>
 *
 * <p>그래서 순서가 이렇다.
 *
 * <ol>
 *   <li>조회 트랜잭션에서 자료를 읽는다. 없으면 여기서 {@code 404}이고 <b>아무것도 올리지 않는다</b>
 *   <li>그 트랜잭션이 <b>끝난 뒤</b> 별도 트랜잭션에서 조회수를 올린다
 *   <li>올렸으면 응답의 숫자에 그 1을 반영한다
 * </ol>
 *
 * <p><b>세는 것이 읽는 것의 조건이 아니다</b> (MUST). 증가에 실패해도 {@code 200}이고 자료는 그대로 나간다 — 숫자 하나 때문에 자료가 안 열리면
 * 그것이 훨씬 큰 고장이다. 그때 응답은 <b>증가 전 값</b>이다: 없는 증가를 응답에서만 더하면 DB에 없는 숫자가 나가 직후 목록과 어긋난다.
 *
 * <p>이 모양은 {@code AdminActionRecorder}와 같다 (2-2 §2-2-7). <b>곁다리 기록이 본 작업을 되돌리면 안 된다</b>는 같은 원칙이다.
 */
@Service
public class NoteViewService {

  private static final Logger log = LoggerFactory.getLogger(NoteViewService.class);

  private final NoteQueryService notes;
  private final NoteRepository repository;
  private final TransactionTemplate transaction;

  public NoteViewService(
      NoteQueryService notes,
      NoteRepository repository,
      PlatformTransactionManager transactionManager) {
    this.notes = notes;
    this.repository = repository;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  /**
   * 상세를 돌려주고 조회수를 1 올린다.
   *
   * @return 응답의 {@code viewCount}는 <b>이 조회를 반영한 값</b>이다. 증가에 실패했으면 증가 전 값이다
   */
  public NoteDetailResponse read(Long viewerId, Long id) {
    NoteDetailResponse detail = notes.get(viewerId, id);
    return increase(id) ? detail.counted() : detail;
  }

  /**
   * 조회수를 올린다. <b>실패를 삼킨다.</b>
   *
   * @return 실제로 올랐으면 {@code true}
   */
  private boolean increase(Long id) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      /*
       * 여기서 끊는다. 조회 트랜잭션 안에서 올리면 (a) 읽기 전용이라 거절당하거나
       * (b) 증가 실패가 조회를 되돌린다. 둘 다 계약 위반인데 조용히 어긋나는 종류라,
       * 나중에 "가끔 자료가 안 열린다"로만 드러난다 (3-2 §3-2-4 MUST).
       */
      throw new IllegalStateException("조회수는 조회 트랜잭션 밖에서 올려야 한다 (spec 3-2 §3-2-4).");
    }
    try {
      Boolean counted = transaction.execute(ignored -> repository.increaseViewCount(id) > 0);
      return Boolean.TRUE.equals(counted);
    } catch (RuntimeException e) {
      /*
       * 삼키되 조용히 넘기지 않는다. 조회수가 안 오르는 것은 화면에 드러나지 않아
       * 이 로그가 남는 유일한 단서다. 자료 id를 반드시 남긴다.
       */
      log.warn("자료 조회수를 올리지 못했다: noteId={}", id, e);
      return false;
    }
  }
}
