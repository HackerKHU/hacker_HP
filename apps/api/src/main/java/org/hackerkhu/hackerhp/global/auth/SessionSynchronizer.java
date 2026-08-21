package org.hackerkhu.hackerhp.global.auth;

import java.util.Collection;
import java.util.Map;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 바뀐 {@code role}·{@code status}를 <b>그 사람의 기존 세션에</b> 반영한다 (spec 3-1 §3-1-5 MUST, #85).
 *
 * <p>DB만 바꾸고 세션을 그대로 두면 <b>정지해도 계속 쓰고, 승인해도 계속 막힌다.</b> 인가는 매 요청 세션 값으로 판단하기 때문이다 ({@link
 * JwtSessionAuthenticationFilter}).
 *
 * <p><b>지우지 않고 갱신한다</b> (3-1 §3-1-5 MUST). 세션을 지우면 다음 요청이 {@code 401}이 되어 클라이언트가 정지인지 단순 만료인지 구별하지
 * 못하고, 정지 직후 그 화면에서 안내를 띄울 수 없다 (T-32는 {@code 403 SUSPENDED}를 요구한다). 승인·권한 변경은 둘 다 허용되지만 경로를 하나로 두면
 * 세 경우가 같은 코드를 밟는다.
 *
 * <p><b>바뀐 값을 넘겨받지 않고 id만 받는다.</b> 무엇을 쓸지는 여기서 잠근 뒤 직접 읽는다 — 넘겨받은 값은 이미 지난 것일 수 있고, 앞뒤 갱신이 엇갈리면 옛
 * 값이 새 값을 덮는다.
 */
@Component
public class SessionSynchronizer {

  private static final Logger log = LoggerFactory.getLogger(SessionSynchronizer.class);

  private final FindByIndexNameSessionRepository<Session> sessions;
  private final UserRepository userRepository;

  /**
   * 한 사람의 세션 갱신을 <b>인스턴스 사이에서도</b> 한 줄로 세우는 트랜잭션.
   *
   * <p>전파는 기본값이다. 이 클래스는 <b>바깥 트랜잭션이 끝난 뒤</b> 불리므로 새로 열 것이 없고, {@code REQUIRES_NEW}로 겹쳐 열면 아직 반납되지
   * 않은 커넥션 위에 하나를 더 잡는다.
   */
  private final TransactionTemplate serialize;

  /**
   * 저장소의 실제 세션 타입({@code JdbcSession})은 공개되어 있지 않아 와일드카드로 주입받고 여기서 좁힌다. {@code save(S)}가 {@code
   * Session}을 받으려면 타입이 정해져 있어야 한다.
   */
  @SuppressWarnings("unchecked")
  public SessionSynchronizer(
      FindByIndexNameSessionRepository<? extends Session> sessions,
      UserRepository userRepository,
      PlatformTransactionManager transactionManager) {
    this.sessions = (FindByIndexNameSessionRepository<Session>) sessions;
    this.userRepository = userRepository;
    this.serialize = new TransactionTemplate(transactionManager);
  }

  /**
   * 그 사람들의 세션을 <b>지금 DB 값으로</b> 맞춘다.
   *
   * <p><b>변경이 커밋된 뒤에 부른다</b> (MUST). 변경 트랜잭션 안에서 부르면 되돌아간 변경이 세션에만 남고, 커밋 콜백에서 부르면 아직 반납되지 않은 커넥션
   * 위에 커넥션을 겹쳐 잡는다. 잘못된 자리에서 부르면 <b>조용히</b> 어긋나므로 여기서 끊는다.
   */
  public void refresh(Collection<Long> userIds) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("세션 반영은 변경이 커밋된 뒤에 불러야 한다 (spec 3-1 §3-1-5).");
    }
    userIds.stream().distinct().sorted().forEach(this::refresh);
  }

  /**
   * 그 사람의 세션을 <b>전부</b> 갱신한다.
   *
   * <p>한 사람이 PC와 휴대폰에 각각 로그인해 있을 수 있다. 하나라도 남기면 <b>정지된 사람이 그 브라우저로 계속 쓴다.</b>
   *
   * <p><b>계정을 읽는 것부터 세션에 쓰는 것까지가 한 연산이다.</b> 그 사람의 계정 행을 잠근 채 읽고 쓰므로, 동시에 도착한 갱신은 기다렸다가 <b>같은 최신
   * 값</b>을 보게 된다 — 읽어 둔 값을 들고 와서 쓰는 방식이면 앞뒤가 엇갈릴 때 옛 값이 새 값을 덮는다.
   *
   * <p>계정 행을 고른 이유는 <b>상태를 바꾸는 트랜잭션이 이미 그 행을 잠그기 때문이다</b> — 갱신과 다음 변경도 자연히 순서가 선다. 세션 저장은 다른 테이블이라
   * 이 잠금과 얽히지 않는다.
   */
  private void refresh(Long userId) {
    try {
      boolean ran =
          NestedConnections.run(
              () -> serialize.executeWithoutResult(ignored -> refreshLocked(userId)));
      if (!ran) {
        log.error("세션 갱신이 중단됐다: userId={}", userId);
      }
    } catch (RuntimeException e) {
      /*
       * 여기서 던지면 이미 커밋된 변경까지 실패한 것처럼 보인다. 세션은 옛 값으로 남고
       * 만료(30분)까지 기다리면 되므로, 알리고 넘어간다 — 다만 정지가 늦어지는 것이므로
       * 조용히 삼키지 않고 error로 남긴다. 관리자가 같은 요청을 다시 보내면 복구된다.
       */
      log.error("세션 갱신 실패: userId={}", userId, e);
    }
  }

  private void refreshLocked(Long userId) {
    User user = userRepository.findByIdForUpdate(userId).orElse(null);
    Map<String, Session> found = sessions.findByPrincipalName(String.valueOf(userId));

    if (user == null) {
      /*
       * 계정이 사라졌다. 세션도 함께 지운다 (2-2 §2-2-4 MUST).
       *
       * 남겨 두면 계정 없는 사람이 만료까지 인증된다 — JwtSessionAuthenticationFilter는
       * 토큰의 id와 세션 값만 대조하고 users를 다시 읽지 않는다(3-3 결정 12). "계정이 없으니
       * 어차피 인증되지 않는다"는 성립하지 않는다.
       */
      found.keySet().forEach(sessions::deleteById);
      log.warn("계정이 없어 세션을 폐기했다: userId={} 세션 {}개", userId, found.size());
      return;
    }

    found.values().forEach(session -> save(session, user));
    log.info(
        "세션 갱신: userId={} status={} role={} 세션 {}개",
        userId,
        user.getStatus(),
        user.getRole(),
        found.size());
  }

  /**
   * <b>더 낮은 버전으로는 덮어쓰지 않는다.</b>
   *
   * <p>잠근 채 읽은 값만 쓰므로 이 비교에 걸릴 일은 드물다. 그래도 남겨 두는 것은 <b>세션에 값을 넣는 경로가 여기 하나가 아니기 때문이다</b> — 로그인도 세션을
   * 채우고, 그 값이 더 새 것일 수 있다 (#127).
   *
   * <p>같은 버전은 덮어쓴다. 상태가 바뀌지 않은 재요청이 <b>갱신 실패의 복구 수단</b>이기 때문이다.
   */
  private void save(Session session, User user) {
    Long current = AuthSession.version(session).orElse(null);
    if (current != null && user.getVersion() != null && user.getVersion() < current) {
      log.info(
          "세션 갱신 건너뜀 — 더 새 값이 이미 반영돼 있다: userId={} 읽음={} 세션={}",
          user.getId(),
          user.getVersion(),
          current);
      return;
    }
    AuthSession.store(session, user);
    sessions.save(session);
  }
}
