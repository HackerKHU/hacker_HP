package org.hackerkhu.hackerhp.domain.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.hackerkhu.hackerhp.domain.auth.dto.MeResponse;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.global.auth.AccessTokenCookie;
import org.hackerkhu.hackerhp.global.error.BusinessException;
import org.hackerkhu.hackerhp.global.error.ErrorCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로그인한 뒤의 신원 조회와 로그아웃 (spec 3-2 §3-2-3). */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private final UserRepository userRepository;
  private final AccessTokenCookie accessTokenCookie;

  public AuthController(UserRepository userRepository, AccessTokenCookie accessTokenCookie) {
    this.userRepository = userRepository;
    this.accessTokenCookie = accessTokenCookie;
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
