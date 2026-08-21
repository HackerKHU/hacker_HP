package org.hackerkhu.hackerhp.domain.auth.service;

import java.time.Duration;
import java.time.Instant;
import org.hackerkhu.hackerhp.domain.auth.entity.BootstrapAttempt;
import org.hackerkhu.hackerhp.domain.auth.repository.BootstrapAttemptRepository;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 승격 시도 횟수를 제한한다 (spec 3-3 결정 11, #144).
 *
 * <p><b>이 경로가 열리는 시점이 문제였다.</b> 방어선은 "활성 관리자 0명" 하나인데, 그 조건이 성립하는 순간 — 최초 배포 직후, 관리자 사고 복구 중 — 은
 * 정확히 <b>아무도 막을 수 없는 시점</b>이다. 그때 토큰을 무제한으로 추측할 수 있었다.
 *
 * <p><b>계정과 전체를 함께 센다.</b> 계정만 세면 계정을 갈아타며 두드릴 수 있다. IP는 세지 않는다 — 브라우저가 Vercel 프록시를 거쳐 도착하므로(
 * {@code docs/ops/deployment.md}) 여기 보이는 주소는 프록시의 것이고, {@code X-Forwarded-For}는 우리가 통제하지 않는 구간을 지나며
 * 요청자가 값을 넣을 수 있다. <b>믿을 수 없는 값으로 나누면 버킷만 늘어나 제한이 사라진다.</b>
 *
 * <p>전체 상한이 그 자리를 메운다. 이 경로는 <b>한 사람이 한 번 부르는 것이 정상</b>이라 전체를 세도 정상 절차가 걸리지 않는다.
 *
 * <p><b>잠긴 것도 같은 {@code 403}이다</b> (3-2 §3-2-3 MUST). 응답이 달라지면 잠기는 시점을 재서 <b>토큰이 맞았는지를 역으로 알아낼 수
 * 있다.</b>
 */
@Service
public class BootstrapAttemptLimiter {

  private static final Logger log = LoggerFactory.getLogger(BootstrapAttemptLimiter.class);

  /**
   * 세는 창.
   *
   * <p>사고 대응 중에 스스로 잠기는 일이 생길 수 있으므로 <b>기다려서 풀릴 만큼 짧아야 한다.</b> 별도 해제 수단을 두지 않는 대신 15분으로 잡았다 — 해제
   * 스위치는 그 자체가 새 공격 표면이고, RDS가 프라이빗이라 DB에서 직접 지우는 것은 15분보다 오래 걸린다.
   */
  static final Duration WINDOW = Duration.ofMinutes(15);

  /** 한 계정이 창 안에서 실패할 수 있는 횟수. 정상 절차는 1회라 넉넉하다. */
  static final int PER_ACCOUNT_LIMIT = 5;

  /**
   * 창 안에서 이 경로 전체가 허용하는 실패 횟수.
   *
   * <p>계정을 갈아타며 두드리는 것을 막는다. 계정별 상한보다 넉넉해서, 운영자가 혼자 실수하는 동안에는 여기 닿지 않는다.
   */
  static final int GLOBAL_LIMIT = 20;

  private final BootstrapAttemptRepository attempts;
  private final TransactionTemplate transaction;

  public BootstrapAttemptLimiter(
      BootstrapAttemptRepository attempts, PlatformTransactionManager transactionManager) {
    this.attempts = attempts;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  /**
   * 잠겨 있으면 거절한다. <b>다른 거절과 구별되지 않는다.</b>
   *
   * <p>진짜 사유는 로그에만 남긴다 — 잠금은 운영자가 알아야 하는 상태이므로 {@code warn}이다.
   */
  public void requireAllowed(Long accountId) {
    Instant since = Instant.now().minus(WINDOW);

    if (attempts.countByAccountIdAndCreatedAtAfter(accountId, since) >= PER_ACCOUNT_LIMIT) {
      log.warn("관리자 승격이 계정 단위로 잠겼다: userId={} 창={}분", accountId, WINDOW.toMinutes());
      throw rejected();
    }
    if (attempts.countByCreatedAtAfter(since) >= GLOBAL_LIMIT) {
      log.warn("관리자 승격이 전체 단위로 잠겼다: 요청한 userId={} 창={}분", accountId, WINDOW.toMinutes());
      throw rejected();
    }
  }

  /**
   * 실패를 센다.
   *
   * <p><b>승격 트랜잭션 밖에서 부른다</b> (MUST). 거절은 예외로 나가고 그 트랜잭션은 되돌아가므로, 안에서 세면 <b>기록도 함께 사라져 아무것도 세지지
   * 않는다.</b>
   *
   * <p>세는 데 실패해도 거절 자체는 그대로 나간다. 여기서 예외를 덧씌우면 계약이 정한 응답이 바뀐다.
   */
  public void recordFailure(Long accountId) {
    try {
      transaction.executeWithoutResult(
          ignored -> {
            Instant now = Instant.now();
            attempts.save(BootstrapAttempt.failedAt(accountId, now));
            // 창을 벗어난 것은 판단에 쓰이지 않는다. 이 경로는 드물게 불리므로 여기서 정리해도 싸다.
            attempts.deleteByCreatedAtBefore(now.minus(WINDOW));
          });
    } catch (RuntimeException e) {
      log.error("승격 실패 시도를 세지 못했다: userId={}", accountId, e);
    }
  }

  /**
   * 승격에 성공하면 그 계정의 실패를 지운다.
   *
   * <p><b>토큰을 몇 번 잘못 붙여넣고 성공하는 것은 흔한 일이다.</b> 남겨 두면 바로 다음에 마지막 관리자 사고가 났을 때 복구가 막힌다. 성공 자체는 {@code
   * admin_actions}에 남으므로 잃는 기록이 없다 (#143).
   */
  public void clear(Long accountId) {
    try {
      transaction.executeWithoutResult(ignored -> attempts.deleteByAccountId(accountId));
    } catch (RuntimeException e) {
      log.error("승격 성공 뒤 실패 기록을 지우지 못했다: userId={}", accountId, e);
    }
  }

  private static BusinessException rejected() {
    // 다른 거절과 같은 코드·문구다 (AdminBootstrapService.reject와 맞춘다).
    return new BusinessException(ErrorCode.FORBIDDEN, "승격할 수 없습니다.");
  }
}
