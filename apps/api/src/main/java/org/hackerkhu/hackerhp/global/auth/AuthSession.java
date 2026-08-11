package org.hackerkhu.hackerhp.global.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;

/**
 * 서버 세션에 담는 인가 상태 (spec 3-1 §3-1-5).
 *
 * <p>세션은 RDS에 있고, 관리자가 값을 바꾸면 <b>그 회원의 기존 세션에도 즉시 반영된다</b>(#85). 그래서 {@code role}·{@code status}는
 * 토큰이 아니라 여기 있어야 한다.
 *
 * <p>사용자 id를 함께 두는 이유는 <b>매 요청 JWT의 {@code sub}와 대조하기 위해서다</b> (MUST). 이 대조가 없으면 A의 토큰과 B의 세션을 함께
 * 보내 A의 신원으로 B의 권한을 쓸 수 있다 (T-29).
 */
public final class AuthSession {

  /** 속성 이름을 공개해 두는 것은 테스트가 세션을 직접 만들어야 하기 때문이다. 문자열을 두 곳에 적지 않는다. */
  public static final String USER_ID = "auth.userId";

  public static final String ROLE = "auth.role";
  public static final String STATUS = "auth.status";

  private AuthSession() {}

  /** 로그인 성공 시 세션을 채운다. */
  public static void store(HttpSession session, User user) {
    session.setAttribute(USER_ID, user.getId());
    session.setAttribute(ROLE, user.getRole());
    session.setAttribute(STATUS, user.getStatus());
  }

  public static Optional<Long> userId(HttpSession session) {
    return attribute(session, USER_ID, Long.class);
  }

  public static Optional<Role> role(HttpSession session) {
    return attribute(session, ROLE, Role.class);
  }

  public static Optional<Status> status(HttpSession session) {
    return attribute(session, STATUS, Status.class);
  }

  /** 이미 있는 세션만 본다. 없는데 만들지 않는다 — 비로그인 방문자마다 세션 행이 쌓이면 안 된다. */
  public static Optional<HttpSession> existing(HttpServletRequest request) {
    return Optional.ofNullable(request.getSession(false));
  }

  private static <T> Optional<T> attribute(HttpSession session, String name, Class<T> type) {
    Object value = session.getAttribute(name);
    return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
  }
}
