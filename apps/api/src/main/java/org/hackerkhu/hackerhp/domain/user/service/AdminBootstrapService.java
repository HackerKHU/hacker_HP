package org.hackerkhu.hackerhp.domain.user.service;

import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.global.auth.SessionSynchronizer;
import org.hackerkhu.hackerhp.global.config.BootstrapProperties;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 최초 관리자 승격 (spec 3-3 결정 11).
 *
 * <p><b>이것이 없으면 관리자가 한 명도 없어 아무도 가입을 승인할 수 없다.</b> 첫 가입자가 계속 {@code PENDING}으로 남고, 승인해 줄 사람이 없다.
 *
 * <p><b>마지막 관리자 사고의 복구 경로도 겸한다</b> (2-2 §2-2-7). 그래서 영구히 열려 있고, "활성 관리자가 0명"이 그 방어선이다 — 관리자가 정상적으로
 * 있는 동안 이 경로는 아무 일도 하지 않는다.
 */
@Service
public class AdminBootstrapService {

  private static final Logger log = LoggerFactory.getLogger(AdminBootstrapService.class);

  private final UserRepository userRepository;
  private final BootstrapProperties bootstrap;
  private final SessionSynchronizer sessionSynchronizer;
  private final TransactionTemplate transaction;

  public AdminBootstrapService(
      UserRepository userRepository,
      BootstrapProperties bootstrap,
      SessionSynchronizer sessionSynchronizer,
      PlatformTransactionManager transactionManager) {
    this.userRepository = userRepository;
    this.bootstrap = bootstrap;
    this.sessionSynchronizer = sessionSynchronizer;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  /**
   * 넷을 <b>모두</b> 통과해야 승격한다 (결정 11).
   *
   * <ol>
   *   <li>활성 관리자가 0명
   *   <li>요청자의 이메일이 설정값과 일치
   *   <li>본문의 토큰이 설정값과 일치 (상수 시간 비교)
   *   <li>신청서를 제출했다 ({@code applied_at IS NOT NULL})
   * </ol>
   *
   * <p>4번은 결정 13이 덧붙인 전제다. 신청 API는 {@code PENDING} 전용이라, 신청 없이 곧장 {@code ACTIVE ADMIN}이 되면 <b>학번을
   * 채울 방법이 영영 없어진다</b> (T-20).
   */
  public void promote(Long requesterId, String token) {
    transaction.executeWithoutResult(ignored -> apply(requesterId, token));
    // 승격은 role·status를 바꾼다. 반영하지 않으면 본인이 재로그인해야 관리자 화면이 열린다.
    sessionSynchronizer.refresh(List.of(requesterId));
  }

  private void apply(Long requesterId, String token) {
    if (!bootstrap.configured()) {
      // 설정이 없으면 이 경로는 닫힌다. 응답은 다른 거절과 같아 설정 여부조차 드러나지 않는다.
      reject(requesterId, "부트스트랩 설정이 없다");
    }

    /*
     * 활성 관리자 수를 세는 동안 그 행들이 바뀌면 안 된다 (2-2 §2-2-7의 원자성 요구).
     * 잠금 순서는 저장소 전체에서 하나다 — users 행은 id 오름차순.
     */
    SortedSet<Long> ids = new TreeSet<>(List.of(requesterId));
    ids.addAll(userRepository.findIdsByRoleAndStatus(Role.ADMIN, Status.ACTIVE));
    ids.forEach(id -> userRepository.findByIdForUpdate(id));

    User requester =
        userRepository
            .findById(requesterId)
            // 세션은 살아 있는데 계정이 사라졌다. 인증이 성립할 수 없는 상태다.
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));

    if (userRepository.countByRoleAndStatus(Role.ADMIN, Status.ACTIVE) > 0) {
      reject(requesterId, "활성 관리자가 이미 있다");
    }
    if (!bootstrap.matchesEmail(requester.getEmail())) {
      reject(requesterId, "이메일이 설정값과 다르다");
    }
    if (!bootstrap.matchesToken(token)) {
      reject(requesterId, "토큰이 일치하지 않는다");
    }
    if (requester.getStatus() == Status.SUSPENDED) {
      reject(requesterId, "정지된 계정이다");
    }
    if (requester.getAppliedAt() == null) {
      reject(requesterId, "신청서를 제출하지 않았다");
    }

    /*
     * 이미 승인된 회원이면 role만 바꾼다. approve()를 다시 부르면 approved_at이 오늘로 덮여
     * 실제 승인일이 사라진다 — 복구 경로로 부를 때 그렇게 된다 (2-2 §2-2-7).
     */
    if (requester.getStatus() == Status.PENDING) {
      requester.approve();
    }
    requester.promoteToAdmin();

    log.warn("최초 관리자 승격: userId={} email={} — 활성 관리자가 0명이었다", requesterId, requester.getEmail());
  }

  /**
   * <b>거절 사유를 가르지 않는다.</b>
   *
   * <p>사유마다 다른 응답을 주면 "이메일은 맞았고 토큰만 틀렸다"를 알아낼 수 있어 무차별 대입의 탐색 공간이 줄어든다. 설정이 없는 상태조차 같은 응답이라 이 경로가
   * 열려 있는지도 드러나지 않는다. 진짜 사유는 로그에만 남는다.
   *
   * <p><b>토큰은 로그에 남기지 않는다.</b>
   */
  private static void reject(Long requesterId, String reason) {
    log.warn("관리자 승격 거절: userId={} 사유={}", requesterId, reason);
    throw new BusinessException(ErrorCode.FORBIDDEN, "승격할 수 없습니다.");
  }
}
