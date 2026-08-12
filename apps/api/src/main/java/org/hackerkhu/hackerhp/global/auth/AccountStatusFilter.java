package org.hackerkhu.hackerhp.global.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.hackerkhu.hackerhp.global.error.ErrorResponseWriter;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 승인 대기·정지 계정을 인가 <b>앞에서</b> 막는다 (spec 3-1 §3-1-2).
 *
 * <p><b>왜 인가에 맡기지 않는가.</b> {@code @PreAuthorize}가 거부하면 {@code AccessDeniedException} 하나만 나와 전부
 * {@code 403 FORBIDDEN}이 된다. 그런데 계약은 세 코드를 구분한다 — 승인 대기는 {@code PENDING_APPROVAL}(T-02), 정지는 {@code
 * SUSPENDED}(T-32), 권한 부족은 {@code FORBIDDEN}(T-04·T-05). <b>코드가 뭉개지면 화면이 안내를 고르지 못한다</b> — 셋 다 같은
 * 상태 코드를 쓰므로 화면이 가르는 근거는 코드뿐이다 (T-116).
 *
 * <p>거절 사유를 여기서 정하고, {@code @PreAuthorize}는 role 판단에 집중한다.
 */
public class AccountStatusFilter extends OncePerRequestFilter {

  /**
   * 상태와 무관하게 통과시키는 경로.
   *
   * <p><b>여기 없는 것은 전부 막힌다.</b> 목록으로 여는 쪽을 고른 이유는, 인증 영역에 API가 늘어날 때 새 경로가 <b>기본적으로 막히게</b> 하기 위해서다
   * — {@code /auth/**}를 통째로 열면 나중에 들어올 {@code POST /auth/bootstrap-admin}(#89) 같은 것이 의도치 않게 열린다.
   *
   * <p>각 항목이 없으면 무엇이 깨지는지:
   *
   * <ul>
   *   <li>{@code POST /auth/application} — <b>아무도 신청서를 낼 수 없다.</b> 신청 전 계정도 {@code PENDING}이다
   *       (§3-1-2 MUST)
   *   <li>{@code GET /auth/me} — 화면이 상태를 몰라 신청 폼도 정지 안내도 띄우지 못한다. <b>정지된 사람에게도 열어야 한다</b> — 막으면
   *       {@code 401}로 보여 "로그아웃됨"과 구별되지 않는다 (3-3 결정 12)
   *   <li>{@code POST /auth/logout} — 정지된 사람이 안내 화면에서 나갈 방법이 없다
   *   <li>{@code GET /auth/csrf} — 위 두 쓰기 요청에 필요한 토큰을 받지 못한다
   * </ul>
   */
  private static final RequestMatcher ALWAYS_OPEN =
      new OrRequestMatcher(
          List.of(
              matcher(null, "/actuator/**"),
              matcher(null, "/api/v1/oauth2/authorization/**"),
              matcher(null, "/api/v1/login/oauth2/code/**"),
              matcher(HttpMethod.GET, "/api/v1/auth/csrf"),
              matcher(HttpMethod.GET, "/api/v1/auth/me"),
              matcher(HttpMethod.POST, "/api/v1/auth/application"),
              matcher(HttpMethod.POST, "/api/v1/auth/logout")));

  private final ErrorResponseWriter errorResponseWriter;

  public AccountStatusFilter(ErrorResponseWriter errorResponseWriter) {
    this.errorResponseWriter = errorResponseWriter;
  }

  private static RequestMatcher matcher(HttpMethod method, String pattern) {
    return PathPatternRequestMatcher.withDefaults().matcher(method, pattern);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<ErrorCode> blocked = blockedReason(request);
    if (blocked.isEmpty()) {
      chain.doFilter(request, response);
      return;
    }
    errorResponseWriter.write(response, blocked.get());
  }

  private Optional<ErrorCode> blockedReason(HttpServletRequest request) {
    if (ALWAYS_OPEN.matches(request)) {
      return Optional.empty();
    }

    /*
     * 인증이 성립하지 않은 요청은 건드리지 않는다. 비로그인은 401 UNAUTHENTICATED로 끝나야 하고,
     * 그 판단은 인가 계층의 몫이다 — 여기서 403을 내면 "로그인하면 되는 상황"이 "권한이 없는 상황"으로
     * 보인다.
     */
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      return Optional.empty();
    }

    /*
     * 상태는 세션에서 온다. 관리자가 값을 바꾸면 그 회원의 기존 세션에도 반영되므로(#85),
     * 여기서 DB를 다시 읽지 않아도 다음 요청부터 새 값이 적용된다 (3-1 §3-1-5 MUST).
     */
    if (hasStatus(authentication, Status.SUSPENDED)) {
      return Optional.of(ErrorCode.SUSPENDED);
    }
    if (hasStatus(authentication, Status.PENDING)) {
      return Optional.of(ErrorCode.PENDING_APPROVAL);
    }
    return Optional.empty();
  }

  private static boolean hasStatus(Authentication authentication, Status status) {
    String authority = "STATUS_" + status.name();
    return authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .anyMatch(authority::equals);
  }
}
