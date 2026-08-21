package org.hackerkhu.hackerhp.domain.auth.repository;

import java.time.Instant;
import org.hackerkhu.hackerhp.domain.auth.entity.BootstrapAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BootstrapAttemptRepository extends JpaRepository<BootstrapAttempt, Long> {

  long countByAccountIdAndCreatedAtAfter(Long accountId, Instant since);

  long countByCreatedAtAfter(Instant since);

  /** 승격에 성공하면 그 계정의 실패를 지운다 — 토큰을 몇 번 잘못 붙여넣고 성공하는 것은 흔한 일이다. */
  void deleteByAccountId(Long accountId);

  /** 창을 벗어난 것은 판단에 쓰이지 않는다. 남겨 둘 이유가 없다. */
  void deleteByCreatedAtBefore(Instant threshold);
}
