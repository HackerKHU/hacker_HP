package org.hackerkhu.hackerhp.global.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 매 요청 <b>JWT와 세션이 같은 사용자의 것인지</b> 확인하고, 맞을 때만 인증을 세운다 (spec 3-1 §3-1-5 MUST).
 *
 * <p>둘 중 하나만으로는 통과하지 못한다.
 *
 * <ul>
 *   <li>토큰만 있고 세션이 없다 — 로그아웃했거나 만료됐다. 서명이 유효해도 거부한다 (T-30). 세션을 지우는 것만으로 로그아웃과 강제 차단이 성립하는 것이 이 설계의
 *       핵심이다 (3-3 결정 12)
 *   <li>세션만 있고 토큰이 없다 — 거부한다 (T-31)
 *   <li>둘 다 있으나 사용자가 다르다 — 거부하고 <b>양쪽을 폐기한다</b> (T-29)
 * </ul>
 *
 * <p><b>{@code SecurityContext}를 세션에서 복원하지 않는다.</b> 복원하면 세션만으로 인증이 성립해 T-31이 새고, 이 필터의 대조가 무의미해진다.
 * {@code SecurityConfig}가 저장소를 비워 둔 이유다.
 *
 * <p><b>{@code @Component}를 붙이지 않는다.</b> 붙이면 Spring Boot가 이것을 서블릿 필터로도 자동 등록해 시큐리티 체인보다 <b>먼저</b> 한
 * 번 실행한다. 거기서 세운 인증은 {@code SecurityContextHolderFilter}가 빈 컨텍스트로 덮어쓰고, 정작 체인 안의 실행은 {@code
 * OncePerRequestFilter}가 "이미 돌았다"며 건너뛴다 — 로그인한 요청까지 전부 401이 된다. {@code SecurityConfig}가 직접 만들어 체인에만
 * 넣는다.
 */
public class JwtSessionAuthenticationFilter extends OncePerRequestFilter {

  private final JwtProvider jwtProvider;
  private final AccessTokenCookie accessTokenCookie;

  public JwtSessionAuthenticationFilter(
      JwtProvider jwtProvider, AccessTokenCookie accessTokenCookie) {
    this.jwtProvider = jwtProvider;
    this.accessTokenCookie = accessTokenCookie;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    authenticate(request, response);
    chain.doFilter(request, response);
  }

  private void authenticate(HttpServletRequest request, HttpServletResponse response) {
    Optional<String> token = accessTokenCookie.read(request);
    Optional<HttpSession> session = AuthSession.existing(request);

    if (token.isEmpty()) {
      // 세션만 있는 경우다. 로그인 흐름 중(인가 요청 저장)일 수도 있으므로 세션을 건드리지 않는다.
      return;
    }

    Optional<Long> tokenUserId = jwtProvider.readUserId(token.get());
    Optional<Long> sessionUserId = session.flatMap(AuthSession::userId);

    if (tokenUserId.isEmpty() || sessionUserId.isEmpty()) {
      // 위조·만료된 토큰이거나, 세션이 사라진 뒤 남은 토큰이다. 들고 있어 봐야 쓸 데가 없다.
      accessTokenCookie.clear(response);
      return;
    }

    if (!tokenUserId.get().equals(sessionUserId.get())) {
      /*
       * A의 토큰과 B의 세션을 함께 보낸 경우다 (T-29). 정상적인 브라우저에서는 나올 수 없는 조합이므로
       * 양쪽을 모두 버린다 — 한쪽만 버리면 남은 쪽으로 계속 시도할 수 있다.
       */
      session.ifPresent(HttpSession::invalidate);
      accessTokenCookie.clear(response);
      return;
    }

    Optional<Role> role = session.flatMap(AuthSession::role);
    Optional<Status> status = session.flatMap(AuthSession::status);
    if (role.isEmpty() || status.isEmpty()) {
      // 로그인 성공 처리는 셋을 함께 넣는다. 하나라도 없으면 이 세션은 신뢰할 수 없다.
      return;
    }

    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                sessionUserId.get(), null, authorities(role.get(), status.get())));
  }

  /**
   * 권한 매트릭스(spec 3-1 §3-1-3)는 <b>Role과 Status를 함께</b> 본다. 신청서 제출은 {@code PENDING}만, 공지 등록은 {@code
   * ACTIVE}인 {@code ADMIN}만이다.
   *
   * <p>둘을 모두 권한으로 내보내야 {@code @PreAuthorize}가 그 표를 그대로 옮겨 적을 수 있다. Status를 서비스 안에서 따로 검사하면 매트릭스와
   * 코드가 갈라지고, 검사를 빠뜨린 API가 조용히 열린다.
   */
  private static List<SimpleGrantedAuthority> authorities(Role role, Status status) {
    return List.of(
        new SimpleGrantedAuthority("ROLE_" + role.name()),
        new SimpleGrantedAuthority("STATUS_" + status.name()));
  }
}
