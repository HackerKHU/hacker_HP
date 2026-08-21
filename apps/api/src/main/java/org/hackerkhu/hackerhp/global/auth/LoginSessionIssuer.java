package org.hackerkhu.hackerhp.global.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 로그인 세션을 발급하고 응답을 내보낸다 (spec 3-1 §3-1-5, #127).
 *
 * <p><b>세션이 저장소에 들어가는 것까지가 계정 행을 잠근 채 일어난다.</b> 그 하나가 이 클래스의 존재 이유다.
 *
 * <p>예전에는 계정을 읽어 세션을 채우고 끝냈는데, 그 세션이 DB에 쓰이는 것은 요청이 끝날 때 {@code SessionRepositoryFilter}가 커밋하는
 * 시점이었다. 그 사이에 관리자가 이 사람을 승인·정지하면 {@link SessionSynchronizer}의 조회가 <b>아직 없는 세션을 놓치고</b>, 뒤이어 저장된
 * 세션이 옛 값을 들고 만료(30분)까지 남았다 — 정지된 사람이 계속 이용하는 상태다.
 *
 * <p><b>한 번 더 읽는 것으로는 닫히지 않는다.</b> 창의 끝은 "다시 읽는 시점"이 아니라 "세션이 실제로 저장되는 시점"이다. <b>저장한 뒤에 대조하는 것으로도
 * 부족하다</b> — 응답이 먼저 나가면 대조가 끝나기 전에 그 쿠키로 요청이 도착할 수 있다 (#182 리뷰).
 *
 * <p>그래서 <b>읽기부터 저장까지를 계정 행 잠금 안에 넣는다.</b> 상태를 바꾸는 쪽도 같은 행을 잠그고, 세션 반영은 커밋한 뒤에 하므로(§3-1-5) 두 순서 중
 * 어느 쪽이든 결과가 하나다.
 *
 * <ul>
 *   <li>로그인이 먼저 잠그면 — 상태 변경은 기다렸다가 커밋하고, 그 뒤의 반영이 <b>이미 저장된</b> 세션을 찾는다.
 *   <li>상태 변경이 먼저 잠그면 — 로그인이 기다렸다가 <b>바뀐 값</b>을 읽어 세션에 담는다.
 * </ul>
 */
@Component
public class LoginSessionIssuer {

  private static final Logger log = LoggerFactory.getLogger(LoginSessionIssuer.class);

  private final UserRepository userRepository;
  private final TransactionTemplate serialize;

  public LoginSessionIssuer(
      UserRepository userRepository, PlatformTransactionManager transactionManager) {
    this.userRepository = userRepository;
    this.serialize = new TransactionTemplate(transactionManager);
  }

  /**
   * 세션을 발급하고 {@code redirectTo}로 되돌린다.
   *
   * <p><b>되돌리는 것까지 여기서 하는 이유</b> — 응답을 내보내는 순간이 곧 세션이 저장되는 순간이기 때문이다. {@code
   * SessionRepositoryFilter}가 응답 커밋을 받아 세션을 쓰므로, 그 호출이 잠금 안에 있어야 "읽기부터 저장까지가 한 연산"이 성립한다. 밖으로 빼면
   * 잠금이 먼저 풀려 창이 도로 열린다.
   *
   * @return 발급했으면 {@code true}. {@code false}면 <b>세션도 응답도 만들지 않았다</b> — 부르는 쪽이 실패를 알려야 한다.
   */
  public boolean issue(
      HttpServletRequest request, HttpServletResponse response, Long userId, String redirectTo) {
    try {
      boolean ran =
          NestedConnections.run(
              () ->
                  serialize.executeWithoutResult(
                      ignored -> publish(request, response, userId, redirectTo)));
      if (!ran) {
        log.error("로그인 세션 발급이 중단됐다: userId={}", userId);
      }
    } catch (RuntimeException e) {
      /*
       * 트랜잭션을 열지 못했거나, 잠금·조회가 터졌거나, 세션 저장이 실패했다. 그대로 올리면
       * 콜백에 500이 남는다 — 이 경로는 브라우저 전체가 이동한 흐름이라 계약(3-2 §3-2-3)이
       * 리다이렉트를 요구한다. 아래에서 발급 여부를 보고 실패로 알린다.
       */
      log.error("로그인 세션 발급이 실패했다: userId={}", userId, e);
    }

    /*
     * 발급했는가 = 응답이 나갔는가.
     *
     * 세션이 저장소에 쓰이는 것이 곧 응답을 내보내는 호출이므로 둘은 같은 사건이다.
     * 나갔으면 세션도 저장된 뒤라 되돌릴 수 없고 되돌릴 이유도 없다. 나가지 않았으면
     * 무엇이 어긋났든 아무것도 만들어지지 않았다.
     */
    return response.isCommitted();
  }

  private void publish(
      HttpServletRequest request, HttpServletResponse response, Long userId, String redirectTo) {
    /*
     * 잠근 채 읽는다. 넘겨받은 값을 쓰면 그것이 이미 지난 값일 수 있다 — SessionSynchronizer가
     * id만 받아 직접 읽는 것과 같은 이유다 (3-1 §3-1-5 MUST).
     */
    User current = userRepository.findByIdForUpdate(userId).orElse(null);
    if (current == null) {
      /*
       * 콜백이 계정을 읽은 뒤, 여기서 잠그기 전에 사라졌다. 세션을 만들기 전이라
       * 거둬들일 것도 없다 — 응답을 내보내지 않고 끝내면 부르는 쪽이 실패로 알린다.
       */
      log.warn("로그인 도중 계정이 사라졌다 — 세션을 발급하지 않는다: userId={}", userId);
      return;
    }

    /*
     * 인가 요청이 쓰던 세션은 버린다. 구글로 보내는 단계에서
     * HttpSessionOAuth2AuthorizationRequestRepository가 만든 것이 콜백 시점에 아직 살아 있다.
     * 그대로 두면 로그인과 무관한 행이 남고, 새로 만들면 로그인 전후로 세션 id가 바뀌어
     * 세션 고정 보호도 함께 이룬다.
     */
    HttpSession previous = request.getSession(false);
    if (previous != null) {
      previous.invalidate();
    }

    AuthSession.store(request.getSession(true), current);

    try {
      // 필터가 이 호출을 받아 세션을 저장한다. 아직 잠금 안이다.
      response.sendRedirect(redirectTo);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
