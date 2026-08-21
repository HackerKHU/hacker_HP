package org.hackerkhu.hackerhp.global.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;

/**
 * 로그인 세션을 발급한다 (spec 3-1 §3-1-5, #127).
 *
 * <p><b>세션이 저장소에 들어간 뒤에 계정을 다시 본다.</b> 그 순서가 이 클래스의 존재 이유다.
 *
 * <p>예전에는 계정을 읽어 세션을 채우고 끝냈는데, 그 세션이 DB에 쓰이는 것은 요청이 끝날 때 {@code SessionRepositoryFilter}가 커밋하는
 * 시점이다. 그 사이에 관리자가 이 사람을 승인·정지하면 {@link SessionSynchronizer}의 조회가 <b>아직 없는 세션을 놓치고</b>, 뒤이어 저장된 세션이
 * 옛 값을 들고 만료(30분)까지 남았다 — 정지된 사람이 계속 이용하는 상태다.
 *
 * <p><b>핸들러 안에서 한 번 더 읽는 것으로는 닫히지 않는다.</b> 창의 끝은 "다시 읽는 시점"이 아니라 "세션이 실제로 저장되는 시점"이라, 조회를 늘리면 창이 조금
 * 줄 뿐이다. 저장된 뒤에 대조해야 <b>두 순서 중 어느 쪽이든</b> 잡힌다 — 변경이 먼저면 대조가 보고, 나중이면 동기화 조회가 <b>이미 저장된</b> 세션을 찾는다.
 */
@Component
public class LoginSessionIssuer {

  private static final Logger log = LoggerFactory.getLogger(LoginSessionIssuer.class);

  private final SessionRepository<? extends Session> sessions;
  private final SessionSynchronizer synchronizer;

  public LoginSessionIssuer(
      SessionRepository<? extends Session> sessions, SessionSynchronizer synchronizer) {
    this.sessions = sessions;
    this.synchronizer = synchronizer;
  }

  /**
   * 이 요청에 새 세션을 붙이고 인가 상태를 채운다. <b>저장은 아직이다</b> — 응답이 나갈 때 필터가 한다.
   *
   * <p><b>인가 요청이 쓰던 세션은 버린다.</b> 구글로 보내는 단계에서 {@code
   * HttpSessionOAuth2AuthorizationRequestRepository}가 만든 것이 콜백 시점에 아직 살아 있다. 그대로 두면 로그인과 무관한 행이 남고,
   * 새로 만들면 <b>로그인 전후로 세션 id가 바뀌어 세션 고정 보호</b>도 함께 이룬다.
   */
  public HttpSession open(HttpServletRequest request, User user) {
    HttpSession previous = request.getSession(false);
    if (previous != null) {
      // 필터가 저장소에서도 지운다. 남기면 인가 요청용 세션이 유령으로 남는다.
      previous.invalidate();
    }

    HttpSession session = request.getSession(true);
    AuthSession.store(session, user);
    return session;
  }

  /**
   * <b>저장된 세션을 지금 DB 값과 맞춘다.</b> 응답을 내보낸 뒤에 부른다 — 그때라야 세션이 저장소에 있다.
   *
   * <p>맞추는 일 자체는 {@link SessionSynchronizer}가 한다. 관리자가 상태를 바꿨을 때와 <b>같은 경로</b>여야 규칙이 갈리지 않는다 — 계정
   * 행을 잠근 채 읽고 쓰므로 상태 변경과 순서가 서고, 더 낮은 버전으로는 덮어쓰지 않는다. 계정이 사라졌으면 그쪽이 세션까지 지운다.
   *
   * <p><b>맞추지 못하면 세션을 거둬들인다.</b> 대조하지 못한 세션을 남기면 이 창이 그대로 열려 있는 것과 같다. 그 사람의 다음 요청은 {@code 401}이 되고
   * 화면은 로그인으로 되돌린다 — 응답은 이미 나갔으므로 로그인 실패를 알리는 수단이 이것뿐이다. 로그인은 다시 하면 되는 조작이다.
   */
  public void settle(String sessionId, Long userId) {
    if (synchronizer.refresh(userId)) {
      return;
    }
    log.error("로그인 세션을 대조하지 못했다 — 세션을 거둬들인다: userId={} sessionId={}", userId, sessionId);
    discard(sessionId, userId);
  }

  private void discard(String sessionId, Long userId) {
    try {
      sessions.deleteById(sessionId);
    } catch (RuntimeException e) {
      // 여기까지 실패하면 남길 수 있는 것은 기록뿐이다. 세션은 만료(30분)까지 남는다.
      log.error("로그인 세션을 거둬들이지 못했다: userId={} sessionId={}", userId, sessionId, e);
    }
  }
}
