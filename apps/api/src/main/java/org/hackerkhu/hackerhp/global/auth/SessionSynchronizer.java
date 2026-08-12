package org.hackerkhu.hackerhp.global.auth;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
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
 */
@Component
public class SessionSynchronizer {

  private static final Logger log = LoggerFactory.getLogger(SessionSynchronizer.class);

  private final FindByIndexNameSessionRepository<Session> sessions;
  private final UserRepository userRepository;

  /**
   * 한 사람의 세션 갱신을 <b>인스턴스 사이에서도</b> 한 줄로 세우는 트랜잭션.
   *
   * <p>{@code afterCommit}에는 트랜잭션이 없으므로 여기서 새로 연다. 세션 저장소도 자기 트랜잭션을 따로 열지만(둘 다 {@code
   * REQUIRES_NEW}) 서로 다른 테이블을 만져 얽히지 않는다.
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
    this.serialize.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * <b>DB 커밋이 끝난 뒤에</b> 세션을 갱신한다.
   *
   * <p>세션 저장소는 자기 트랜잭션으로 커밋한다. 변경 트랜잭션 안에서 그냥 부르면 <b>DB가 되돌아가도 세션만 새 값으로 남는다</b> — 롤백된 정지 때문에 멀쩡한
   * 회원이 막히는 쪽이 훨씬 나쁘다. 반대로 갱신이 실패하면 세션은 옛 값(권한이 더 좁은 쪽)으로 남고 만료까지 기다리면 된다.
   *
   * <p><b>값은 지금 읽어 둔다.</b> 커밋 뒤에는 영속성 컨텍스트가 닫혀 있어 엔티티를 다시 읽을 수 없고, 그 자리에서 새 트랜잭션을 여는 것은 커밋 직후의 정리
   * 단계와 얽힌다.
   *
   * <p><b>여기에 트랜잭션을 덧대지 않는다.</b> {@code afterCommit}은 바깥 트랜잭션의 자원이 정리되기 전에 돌지만, 세션 저장소는 자기 템플릿을
   * {@code PROPAGATION_REQUIRES_NEW}로 열어 저장한다 ({@code
   * JdbcHttpSessionConfiguration#createTransactionTemplate}) — 이미 커밋된 트랜잭션에 얹히지 않고 새 커넥션에서 따로 커밋한다.
   * 여기서 {@code REQUIRES_NEW}를 한 겹 더 두르면 커넥션만 하나 더 잡는다.
   *
   * <p>그 전제가 깨지면 <b>승인은 되는데 세션만 옛 값으로 남는다.</b> T-33이 실제 API와 실제 저장소로 그 경로를 밟으므로, 라이브러리가 전파 방식을 바꾸면
   * 그 테스트가 먼저 깨진다.
   */
  public void refreshAfterCommit(Collection<User> users) {
    List<Snapshot> snapshots = users.stream().map(Snapshot::of).toList();
    if (snapshots.isEmpty()) {
      return;
    }
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      refresh(snapshots);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            refresh(snapshots);
          }
        });
  }

  private void refresh(List<Snapshot> snapshots) {
    snapshots.forEach(this::refresh);
  }

  /**
   * 그 사람의 세션을 <b>전부</b> 갱신한다.
   *
   * <p>한 사람이 PC와 휴대폰에 각각 로그인해 있을 수 있다. 하나라도 남기면 <b>정지된 사람이 그 브라우저로 계속 쓴다.</b>
   *
   * <p><b>읽고-비교하고-저장하는 동안 그 사람의 계정 행을 잡고 있는다.</b> 버전 비교만으로는 부족하다 — 앞뒤 콜백이 (인스턴스가 달라도) 같은 옛 세션을 함께
   * 읽으면 둘 다 비교를 통과하고, 나중에 저장하는 쪽이 낮은 버전이면 새 값이 도로 덮인다. 이 잠금이 그 구간 전체를 한 줄로 세운다.
   *
   * <p>계정 행을 고르는 이유는 <b>상태를 바꾸는 트랜잭션이 이미 그 행을 잠그기 때문이다</b> — 갱신과 다음 변경도 자연히 순서가 선다. 세션 저장은 다른 테이블이라
   * 이 잠금과 얽히지 않는다.
   */
  private void refresh(Snapshot snapshot) {
    try {
      serialize.executeWithoutResult(ignored -> refreshLocked(snapshot));
    } catch (RuntimeException e) {
      /*
       * 여기서 던지면 이미 커밋된 변경까지 실패한 것처럼 보인다. 세션은 옛 값으로 남고
       * 만료(30분)까지 기다리면 되므로, 알리고 넘어간다 — 다만 정지가 늦어지는 것이므로
       * 조용히 삼키지 않고 error로 남긴다.
       */
      log.error("세션 갱신 실패: userId={}", snapshot.userId(), e);
    }
  }

  private void refreshLocked(Snapshot snapshot) {
    // 계정이 사라졌으면 잠글 것이 없다. 남은 세션은 정리해 두는 편이 낫다.
    userRepository.findByIdForUpdate(snapshot.userId());

    Map<String, Session> found = sessions.findByPrincipalName(String.valueOf(snapshot.userId()));
    found.values().forEach(session -> save(session, snapshot));
    log.info(
        "세션 갱신: userId={} status={} role={} 세션 {}개",
        snapshot.userId(),
        snapshot.status(),
        snapshot.role(),
        found.size());
  }

  /**
   * <b>더 낮은 버전으로는 덮어쓰지 않는다.</b>
   *
   * <p>행 잠금은 DB 변경만 직렬화한다 — 커밋과 함께 잠금이 풀리고 세션 저장은 그 뒤에 각자 일어나므로, <b>순서가 뒤집힐 수 있다.</b> 해제가 먼저 커밋된 뒤
   * 세션 저장이 늦어지고 그 사이 정지가 커밋·저장까지 마치면, 뒤늦게 도착한 해제가 세션을 {@code ACTIVE}로 되돌려 <b>정지된 사람이 계속 이용하게
   * 된다.</b>
   *
   * <p>버전은 계정 행의 낙관적 잠금 값이라 <b>변경 순서를 그대로 따른다</b> — 나중 변경은 반드시 더 큰 값을 읽는다(앞의 변경이 커밋될 때까지 행 잠금에
   * 막히므로). 인스턴스가 여럿이어도 통한다.
   *
   * <p>같은 버전은 덮어쓴다. 상태가 바뀌지 않은 재요청이 <b>갱신 실패의 복구 수단</b>이기 때문이다.
   */
  private void save(Session session, Snapshot snapshot) {
    Long current = AuthSession.version(session).orElse(null);
    if (current != null && snapshot.version() != null && snapshot.version() < current) {
      log.info(
          "세션 갱신 건너뜀 — 더 새 값이 이미 반영돼 있다: userId={} 도착={} 세션={}",
          snapshot.userId(),
          snapshot.version(),
          current);
      return;
    }
    AuthSession.store(
        session, snapshot.userId(), snapshot.role(), snapshot.status(), snapshot.version());
    sessions.save(session);
  }

  /** 커밋 전에 읽어 둔 값. 세션에 옮겨 적을 것은 이것뿐이다. */
  private record Snapshot(Long userId, Role role, Status status, Long version) {
    static Snapshot of(User user) {
      return new Snapshot(user.getId(), user.getRole(), user.getStatus(), user.getVersion());
    }
  }
}
