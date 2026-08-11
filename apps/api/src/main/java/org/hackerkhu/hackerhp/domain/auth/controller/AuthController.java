package org.hackerkhu.hackerhp.domain.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.hackerkhu.hackerhp.domain.auth.dto.ApplicationRequest;
import org.hackerkhu.hackerhp.domain.auth.dto.MeResponse;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.domain.user.service.UserApplicationService;
import org.hackerkhu.hackerhp.global.auth.AccessTokenCookie;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로그인한 뒤의 신원 조회와 로그아웃 (spec 3-2 §3-2-3). */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private final UserRepository userRepository;
  private final AccessTokenCookie accessTokenCookie;
  private final CsrfTokenRepository csrfTokenRepository;
  private final UserApplicationService userApplicationService;

  public AuthController(
      UserRepository userRepository,
      AccessTokenCookie accessTokenCookie,
      CsrfTokenRepository csrfTokenRepository,
      UserApplicationService userApplicationService) {
    this.userRepository = userRepository;
    this.accessTokenCookie = accessTokenCookie;
    this.csrfTokenRepository = csrfTokenRepository;
    this.userApplicationService = userApplicationService;
  }

  /**
   * CSRF 토큰을 쿠키로 발급한다 (spec 3-2 §3-2-3 MUST). 본문은 없다.
   *
   * <p>세션도 토큰도 없는 최초 진입에 필요해 <b>비로그인으로 접근할 수 있다.</b> 화면은 첫 상태 변경 요청 전에 이것을 부르고, 실패하면 그 요청 자체를 보내지
   * 않는다 ({@code apps/web/src/api/client.ts}) — 이 경로가 없으면 로그아웃 버튼조차 동작하지 않는다.
   *
   * <p><b>저장소에 직접 쓴다.</b> {@code CsrfToken}을 인자로 받아 읽기만 하는 방식은 이미 발급된 토큰이 있으면 쿠키를 다시 내려주지 않아, 쿠키를
   * 잃은 브라우저가 영영 토큰을 받지 못한다. 이 경로의 목적은 <b>호출하면 반드시 쿠키가 생기는 것</b>이다.
   */
  @GetMapping("/csrf")
  public ResponseEntity<Void> csrf(HttpServletRequest request, HttpServletResponse response) {
    CsrfToken token = csrfTokenRepository.loadToken(request);
    if (token == null) {
      token = csrfTokenRepository.generateToken(request);
    }
    csrfTokenRepository.saveToken(token, request, response);
    return ResponseEntity.noContent().build();
  }

  /**
   * 신원 조회 경로를 하나로 유지한다 (spec 3-2 §3-2-3). 로그인 직후에도, 새로고침으로 세션을 복구할 때도 화면은 이것을 부른다.
   *
   * <p><b>세션이 아니라 DB에서 읽는다.</b> 관리자가 방금 바꾼 학번·이름·승인일시가 세션에는 없다. 세션이 들고 있는 것은 매 요청 권한 판단에 필요한 {@code
   * role}·{@code status}뿐이다.
   */
  @GetMapping("/me")
  @PreAuthorize("isAuthenticated()")
  public MeResponse me(@AuthenticationPrincipal Long userId) {
    return userRepository
        .findById(userId)
        .map(MeResponse::from)
        // 세션은 살아 있는데 계정이 사라졌다. 인증이 성립할 수 없는 상태다.
        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
  }

  /**
   * 신청서 제출·수정 (spec 3-1 §3-1-4 ②).
   *
   * <p><b>{@code PENDING} 전용이다</b> (권한 매트릭스 §3-1-3). 신청 전 계정도 포함해야 한다 — 막으면 아무도 신청서를 낼 수 없다. {@code
   * ACTIVE}가 부르면 {@code 403 FORBIDDEN}이다 (T-50): 승인 후에는 이 경로로 학번을 바꿀 수 없다.
   *
   * <p>본문은 돌려주지 않는다. 화면은 저장을 확인한 뒤 {@code GET /auth/me}로 새 상태를 받는다.
   */
  @PostMapping("/application")
  @PreAuthorize("hasAuthority('STATUS_PENDING')")
  public ResponseEntity<Void> submitApplication(
      @AuthenticationPrincipal Long userId, @Valid @RequestBody ApplicationRequest request) {
    userApplicationService.submit(userId, request.studentNo(), request.name());
    return ResponseEntity.noContent().build();
  }

  /**
   * <b>로그아웃은 세션을 지우는 것으로 성립한다</b> (spec 3-1 §3-1-5 MUST). 세션이 사라지면 쿠키에 남은 토큰은 더 이상 인증에 쓰이지 못한다
   * (T-30).
   *
   * <p>그래도 토큰 쿠키를 함께 버린다. 브라우저에 쓸 수 없는 값을 남겨 둘 이유가 없다.
   */
  @PostMapping("/logout")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    accessTokenCookie.clear(response);
    return ResponseEntity.noContent().build();
  }
}
