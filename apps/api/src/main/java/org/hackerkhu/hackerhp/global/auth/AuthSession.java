package org.hackerkhu.hackerhp.global.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import java.util.function.BiConsumer;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.entity.User;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

/**
 * 서버 세션에 담는 인가 상태 (spec 3-1 §3-1-5).
 *
 * <p>세션은 RDS에 있고, 관리자가 값을 바꾸면 <b>그 회원의 기존 세션에도 즉시 반영된다</b> ({@link SessionSynchronizer}). 그래서
 * {@code role}·{@code status}는 토큰이 아니라 여기 있어야 한다.
 *
 * <p>사용자 id를 함께 두는 이유는 <b>매 요청 JWT의 {@code sub}와 대조하기 위해서다</b> (MUST). 이 대조가 없으면 A의 토큰과 B의 세션을 함께
 * 보내 A의 신원으로 B의 권한을 쓸 수 있다 (T-29).
 */
public final class AuthSession {

  /** 속성 이름을 공개해 두는 것은 테스트가 세션을 직접 만들어야 하기 때문이다. 문자열을 두 곳에 적지 않는다. */
  public static final String USER_ID = "auth.userId";

  public static final String ROLE = "auth.role";
  public static final String STATUS = "auth.status";

  /**
   * 이 세션 값이 <b>어느 시점의 계정에서 왔는지</b>. 계정 행의 낙관적 잠금 버전이다.
   *
   * <p>상태 변경은 커밋 순서대로 직렬화되지만 <b>세션 저장까지 그 순서가 이어지지는 않는다</b> — 커밋과 함께 행 잠금이 풀리고, 세션 저장은 그 뒤에 각자 일어나기
   * 때문이다. 해제와 정지가 잇따르면 늦게 도착한 <b>옛 값이 새 값을 덮어</b> 정지된 사람이 계속 이용할 수 있다.
   *
   * <p>그래서 세션에 버전을 함께 두고, <b>더 낮은 버전으로는 덮어쓰지 않는다</b> ({@code SessionSynchronizer}). 인스턴스가 여럿이어도 통하는
   * 방법이다 — 값이 DB에서 오기 때문이다.
   */
  public static final String VERSION = "auth.version";

  /**
   * 세션을 <b>사용자로 찾기 위한</b> 색인 (#85).
   *
   * <p>Spring Session이 정한 이름이다. 이 속성에 값을 넣으면 {@code JdbcIndexedSessionRepository}가 저장할 때 그것을 {@code
   * SPRING_SESSION.PRINCIPAL_NAME} 컬럼에 옮겨 적고, 그 컬럼에는 인덱스가 걸려 있다 ({@code V2__session.sql}).
   *
   * <p><b>채우지 않으면 남의 세션을 찾을 방법이 아예 없다.</b> 우리는 {@code SecurityContext}를 세션에 저장하지 않으므로(3-1 §3-1-5) 이
   * 컬럼을 대신 채워 줄 것이 없고, 나머지 속성은 {@code BYTEA}로 직렬화되어 값으로 조회할 수 없다.
   *
   * <p><b>이메일이 아니라 숫자 id를 넣는다.</b> 세션 테이블에 개인정보를 늘릴 이유가 없다.
   */
  public static final String PRINCIPAL_NAME =
      FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME;

  private AuthSession() {}

  /** 로그인 성공 시 세션을 채운다. */
  public static void store(HttpSession session, User user) {
    write(session::setAttribute, user.getId(), user.getRole(), user.getStatus(), user.getVersion());
  }

  /** 저장소를 통해 직접 다루는 세션. 관리자가 상태를 바꿨을 때 갱신하는 경로다 (#85). */
  public static void store(Session session, User user) {
    write(session::setAttribute, user.getId(), user.getRole(), user.getStatus(), user.getVersion());
  }

  public static void store(Session session, Long userId, Role role, Status status, Long version) {
    write(session::setAttribute, userId, role, status, version);
  }

  /** 이 세션이 들고 있는 계정 버전. 값이 없으면 버전을 심기 전에 만들어진 세션이다. */
  public static Optional<Long> version(Session session) {
    Object value = session.getAttribute(VERSION);
    return value instanceof Long stored ? Optional.of(stored) : Optional.empty();
  }

  /**
   * {@link HttpSession}과 {@link Session}은 상속 관계가 없지만 쓰는 내용은 같아야 한다.
   *
   * <p>한쪽에만 {@link #PRINCIPAL_NAME}을 넣으면 <b>그 경로로 만들어진 세션만 조용히 찾을 수 없게 된다</b> — 정지가 반영되지 않는데 어디서도
   * 오류가 나지 않는다.
   */
  private static void write(
      BiConsumer<String, Object> attributes, Long userId, Role role, Status status, Long version) {
    attributes.accept(USER_ID, userId);
    attributes.accept(ROLE, role);
    attributes.accept(STATUS, status);
    attributes.accept(VERSION, version);
    attributes.accept(PRINCIPAL_NAME, String.valueOf(userId));
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
