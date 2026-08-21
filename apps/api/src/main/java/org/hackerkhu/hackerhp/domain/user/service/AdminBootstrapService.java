package org.hackerkhu.hackerhp.domain.user.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import org.hackerkhu.hackerhp.domain.audit.entity.AdminAction;
import org.hackerkhu.hackerhp.domain.audit.service.AdminActionRecorder;
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
  private final AdminActionRecorder recorder;
  private final TransactionTemplate transaction;

  public AdminBootstrapService(
      UserRepository userRepository,
      BootstrapProperties bootstrap,
      SessionSynchronizer sessionSynchronizer,
      AdminActionRecorder recorder,
      PlatformTransactionManager transactionManager) {
    this.userRepository = userRepository;
    this.bootstrap = bootstrap;
    this.sessionSynchronizer = sessionSynchronizer;
    this.recorder = recorder;
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
    boolean[] promoted = {false};
    transaction.executeWithoutResult(ignored -> promoted[0] = apply(requesterId, token));
    /*
     * 승격은 role·status를 바꾼다. 반영하지 않으면 본인이 재로그인해야 관리자 화면이 열린다.
     *
     * 이 갱신이 실패해도 예외로 올라오지 않으므로, 같은 요청을 다시 보내는 것이 복구 수단이다.
     * 그래서 이미 승격된 계정의 재요청도 여기까지 온다 (apply 참고).
     */
    sessionSynchronizer.refresh(List.of(requesterId));

    /*
     * 승격은 스스로에게 하는 조작이라 actor와 target이 같다. 그래도 남긴다 — "관리자가 언제
     * 어떻게 생겼는가"가 이 경로의 유일한 기록이고, 상시 열려 있는 문이라 더 그렇다 (§2-2-7).
     *
     * 이미 승격된 계정의 재요청은 남기지 않는다. 아무것도 바뀌지 않았다.
     * 토큰은 넣지 않는다 — 시크릿이다.
     */
    if (promoted[0]) {
      recorder.record(requesterId, requesterId, AdminAction.PROMOTE_ADMIN);
    }
  }

  private boolean apply(Long requesterId, String token) {
    if (!bootstrap.configured()) {
      // 설정이 없으면 이 경로는 닫힌다. 응답은 다른 거절과 같아 설정 여부조차 드러나지 않는다.
      reject(requesterId, "부트스트랩 설정이 없다");
    }

    /*
     * 자격부터 본다 — 잠그기 전에.
     *
     * 이 경로는 로그인만 하면 누구나 부를 수 있다. 자격을 보기 전에 관리자 행을 잠그면,
     * 자격 없는 사람이 반복 호출하는 것만으로 관리자 행 잠금을 계속 점유해 승인·정지 같은
     * 정상 작업을 대기시킬 수 있다.
     */
    User candidate =
        userRepository
            .findById(requesterId)
            // 세션은 살아 있는데 계정이 사라졌다. 인증이 성립할 수 없는 상태다.
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
    requireCredentials(requesterId, candidate.getEmail(), token);

    /*
     * 여기부터 잠근다. 활성 관리자 수를 세는 동안 그 행들이 바뀌면 안 된다 (2-2 §2-2-7의
     * 원자성 요구). 잠금 순서는 저장소 전체에서 하나다 — users 행은 id 오름차순.
     *
     * 요청자만 잠그면 부족하다. users.email의 UNIQUE는 대소문자를 구분하는데 이 경로는
     * 구분하지 않고 견주므로, 자격을 만족하는 행이 둘 이상일 수 있다 — 그 둘이 동시에
     * 호출하면 각자 자기 행만 잠근 채 "활성 관리자 0명"을 함께 보고 둘 다 관리자가 된다.
     * 그래서 같은 이메일을 쓰는 계정을 전부 잠가 이 경로의 요청들을 한 줄로 세운다.
     */
    SortedSet<Long> ids = new TreeSet<>(List.of(requesterId));
    ids.addAll(userRepository.findIdsByEmailIgnoreCase(bootstrap.email().trim()));
    ids.addAll(userRepository.findIdsByRoleAndStatus(Role.ADMIN, Status.ACTIVE));
    Map<Long, User> locked = new LinkedHashMap<>();
    ids.forEach(id -> userRepository.findByIdForUpdate(id).ifPresent(user -> locked.put(id, user)));

    User requester = locked.get(requesterId);
    if (requester == null) {
      throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
    // 잠금을 기다리는 사이에 바뀌었을 수 있다. 잠근 값으로 다시 본다.
    requireCredentials(requesterId, requester.getEmail(), token);

    if (requester.getStatus() == Status.SUSPENDED) {
      reject(requesterId, "정지된 계정이다");
    }
    if (requester.getAppliedAt() == null) {
      reject(requesterId, "신청서를 제출하지 않았다");
    }

    /*
     * 본인이 이미 활성 관리자면 아무것도 바꾸지 않고 통과한다.
     *
     * 승격은 커밋됐는데 세션 갱신이 실패하는 경우가 있다(그 실패는 예외로 올리지 않는다).
     * 그러면 같은 요청을 다시 보내는 것이 유일한 복구 수단인데, 여기서 "활성 관리자가 이미
     * 있다"로 거절하면 그 재시도가 막힌다 — 본인은 재로그인 전까지 관리자 화면을 못 연다.
     *
     * 이메일·토큰을 이미 확인했으므로 여는 문이 넓어지지 않는다.
     */
    if (requester.getRole() == Role.ADMIN && requester.getStatus() == Status.ACTIVE) {
      log.info("이미 승격된 계정의 재요청 — 세션만 다시 맞춘다: userId={}", requesterId);
      return false;
    }

    if (userRepository.countByRoleAndStatus(Role.ADMIN, Status.ACTIVE) > 0) {
      reject(requesterId, "활성 관리자가 이미 있다");
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
    return true;
  }

  private void requireCredentials(Long requesterId, String email, String token) {
    if (!bootstrap.matchesEmail(email)) {
      reject(requesterId, "이메일이 설정값과 다르다");
    }
    if (!bootstrap.matchesToken(token)) {
      reject(requesterId, "토큰이 일치하지 않는다");
    }
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
