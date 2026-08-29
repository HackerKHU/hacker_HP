package org.hackerkhu.hackerhp.domain.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.hackerkhu.hackerhp.domain.auth.dto.ApplicationRequest;
import org.hackerkhu.hackerhp.domain.auth.dto.BootstrapRequest;
import org.hackerkhu.hackerhp.domain.auth.dto.MeResponse;
import org.hackerkhu.hackerhp.domain.user.dto.ContentSummaryResponse;
import org.hackerkhu.hackerhp.domain.user.repository.UserRepository;
import org.hackerkhu.hackerhp.domain.user.service.AdminBootstrapService;
import org.hackerkhu.hackerhp.domain.user.service.UserApplicationService;
import org.hackerkhu.hackerhp.domain.user.service.UserContentSummaryService;
import org.hackerkhu.hackerhp.domain.user.service.UserRemovalService;
import org.hackerkhu.hackerhp.global.auth.AccessTokenCookie;
import org.hackerkhu.hackerhp.global.auth.PublicApi;
import org.hackerkhu.hackerhp.global.error.ErrorResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인한 뒤의 신원 조회와 로그아웃 (spec 3-2 §3-2-3).
 *
 * <p>오류 응답은 전부 {@code { "code", "message" }}다 (5-TESTING §5-4). 그래서 각 오류 응답에 {@link ErrorResponse}
 * 스키마를 붙인다 — {@code @Content}만 적으면 <b>본문이 없는 응답</b>으로 명세되어 화면과 코드 생성기가 무엇을 받을지 알 수 없다. 401은 필터의
 * {@code ErrorResponseWriter}가, 나머지는 {@code GlobalExceptionHandler}가 같은 형태로 낸다.
 */
@Tag(name = "인증", description = "신원 조회·로그아웃·신청서 제출. 로그인 자체는 구글 OAuth 리다이렉트다")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private final UserRepository userRepository;
  private final AccessTokenCookie accessTokenCookie;
  private final CsrfTokenRepository csrfTokenRepository;
  private final UserApplicationService userApplicationService;
  private final AdminBootstrapService adminBootstrapService;
  private final UserContentSummaryService userContentSummaryService;
  private final UserRemovalService userRemovalService;

  public AuthController(
      UserRepository userRepository,
      AccessTokenCookie accessTokenCookie,
      CsrfTokenRepository csrfTokenRepository,
      UserApplicationService userApplicationService,
      AdminBootstrapService adminBootstrapService,
      UserContentSummaryService userContentSummaryService,
      UserRemovalService userRemovalService) {
    this.userRepository = userRepository;
    this.accessTokenCookie = accessTokenCookie;
    this.csrfTokenRepository = csrfTokenRepository;
    this.userApplicationService = userApplicationService;
    this.adminBootstrapService = adminBootstrapService;
    this.userContentSummaryService = userContentSummaryService;
    this.userRemovalService = userRemovalService;
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
  @Operation(
      summary = "CSRF 토큰 발급",
      description =
          """
          `XSRF-TOKEN` 쿠키만 내려준다. 본문은 없다.

          상태를 바꾸는 요청은 이 값을 `X-XSRF-TOKEN` 헤더에 실어야 한다. 쿠키가
          `httpOnly`가 아닌 이유가 그것이다 — 화면이 읽어 헤더에 넣는다.
          """)
  @ApiResponse(responseCode = "204", description = "쿠키 발급됨")
  @PublicApi(reason = "세션도 토큰도 없는 최초 진입에 필요하다 (3-2 §3-2-3 MUST)")
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
   *
   * <p><b>비로그인에게도 열려 있다</b> (#190). 화면은 랜딩을 포함해 최초 렌더마다 이것을 부르므로, 막으면 <b>비로그인 방문자마다 실패 응답이 하나씩
   * 남는다</b> — 브라우저가 콘솔에 남기는 줄은 앱이 지울 수 없고, 진짜 오류가 그 사이에 묻힌다.
   *
   * <p><b>여는 것은 "세션이 있는가"라는 답뿐이다.</b> 비로그인에게는 {@code 204}가 나가고 계정 정보는 한 줄도 실리지 않는다. 로그인 사용자의 응답은
   * 그대로다 — 그래서 {@code 200}에 무엇이 담기는지는 바뀌지 않았다.
   */
  @Operation(
      summary = "내 정보 조회",
      description =
          """
          `role`·`status`와 신청 여부를 돌려준다. 화면은 이 값으로 갈 곳을 정한다.

          **신청 여부는 `appliedAt`으로 판단한다.** 값이 있으면 제출한 것이다 — 같은 사실을
          알려주는 별도 필드를 두지 않는다.
          """)
  @ApiResponse(responseCode = "200", description = "로그인 상태 — 내 정보")
  @ApiResponse(responseCode = "204", description = "세션이 없다. **본문이 없고 오류가 아니다**")
  @GetMapping("/me")
  @PublicApi(reason = "최초 진입의 세션 확인. 비로그인에게는 204만 나간다 (#190)")
  public ResponseEntity<MeResponse> me(@AuthenticationPrincipal Long userId) {
    if (userId == null) {
      return ResponseEntity.noContent().build();
    }
    return userRepository
        .findById(userId)
        .map(MeResponse::from)
        .map(ResponseEntity::ok)
        /*
         * 세션은 살아 있는데 계정이 사라졌다. 여기서도 204다 — 화면에게 그 상태는
         * "로그인되어 있지 않다"와 같고, 실제로 다음 요청부터 인증이 성립하지 않는다.
         * 401을 주면 이 경로만 다시 실패 응답을 남기게 된다.
         */
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  /**
   * 신청서 제출·수정 (spec 3-1 §3-1-4 ②).
   *
   * <p><b>{@code PENDING} 전용이다</b> (권한 매트릭스 §3-1-3). 신청 전 계정도 포함해야 한다 — 막으면 아무도 신청서를 낼 수 없다. {@code
   * ACTIVE}가 부르면 {@code 403 FORBIDDEN}이다 (T-50): 승인 후에는 이 경로로 학번을 바꿀 수 없다.
   *
   * <p>본문은 돌려주지 않는다. 화면은 저장을 확인한 뒤 {@code GET /auth/me}로 새 상태를 받는다.
   */
  @Operation(
      summary = "신청서 제출·수정",
      description =
          """
          승인 심사에 필요한 학번·학과를 받는다. 구글이 학번을 주지 않아 따로 받는 단계다.

          **이름은 받지 않는다.** 구글 계정에 저장된 이름을 그대로 쓴다 — 본문에 `name`을 담아도
          무시된다. 화면은 그 값을 읽기 전용으로 보여준다.

          **`PENDING` 전용이다.** 승인 후에는 이 경로로 학번을 바꿀 수 없다. 승인 전까지는
          다시 제출해 고칠 수 있다.
          """)
  @ApiResponse(responseCode = "204", description = "저장됨. 화면은 `GET /auth/me`로 새 상태를 받는다")
  @ApiResponse(
      responseCode = "400",
      description = "`VALIDATION_ERROR` — 공백이거나 컬럼 길이를 넘었다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description = "`FORBIDDEN` — `PENDING`이 아니거나 CSRF 토큰이 없다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "`DUPLICATE_STUDENT_NO` — 다른 계정이 그 학번을 쓰고 있다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping("/application")
  @PreAuthorize("hasAuthority('STATUS_PENDING')")
  public ResponseEntity<Void> submitApplication(
      @AuthenticationPrincipal Long userId, @Valid @RequestBody ApplicationRequest request) {
    userApplicationService.submit(userId, request.studentNo(), request.department());
    return ResponseEntity.noContent().build();
  }

  /**
   * 최초 관리자 승격 (spec 3-3 결정 11).
   *
   * <p><b>이것이 없으면 관리자가 한 명도 없어 아무도 가입을 승인할 수 없다.</b> 마지막 관리자 사고의 복구 경로도 겸한다 (2-2 §2-2-7).
   *
   * <p><b>거절 사유를 가르지 않는다.</b> 어떤 조건에서 막혔든 같은 응답이다 — 사유가 갈리면 "이메일은 맞았고 토큰만 틀렸다"를 알아낼 수 있다.
   */
  @Operation(
      summary = "최초 관리자 승격",
      description =
          """
          **활성 관리자가 한 명도 없을 때만** 동작한다. 넷을 모두 통과해야 본인이 `ADMIN`이 된다 —
          활성 관리자 0명, 이메일 일치, 토큰 일치, **신청서 제출 완료**.

          신청서 조건이 있는 이유는 신청 API가 `PENDING` 전용이기 때문이다. 신청 없이 곧장
          관리자가 되면 학번을 채울 방법이 영영 없어진다.

          **어떤 이유로 막혔는지 알려주지 않는다.** 설정되지 않은 서버에서도 같은 응답이 나간다.

          토큰 값은 SSM(`ADMIN_BOOTSTRAP_TOKEN`)에 있다. 호출 절차는
          `docs/ops/runbook.md`에 있다.
          """)
  @ApiResponse(responseCode = "204", description = "승격됨. 다음 요청부터 관리자다")
  @ApiResponse(
      responseCode = "400",
      description = "`VALIDATION_ERROR` — `token`이 비었다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "`UNAUTHENTICATED` — 로그인해야 부를 수 있다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description = "`FORBIDDEN` — 조건 하나라도 어긋났거나 CSRF 토큰이 없다. **어느 조건인지는 알려주지 않는다**",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping("/bootstrap-admin")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Void> bootstrapAdmin(
      @AuthenticationPrincipal Long requesterId, @Valid @RequestBody BootstrapRequest request) {
    adminBootstrapService.promote(requesterId, request.token());
    return ResponseEntity.noContent().build();
  }

  /**
   * <b>로그아웃은 세션을 지우는 것으로 성립한다</b> (spec 3-1 §3-1-5 MUST). 세션이 사라지면 쿠키에 남은 토큰은 더 이상 인증에 쓰이지 못한다
   * (T-30).
   *
   * <p>그래도 토큰 쿠키를 함께 버린다. 브라우저에 쓸 수 없는 값을 남겨 둘 이유가 없다.
   */
  @Operation(
      summary = "로그아웃",
      description =
          """
          세션을 지운다. **세션이 사라지면 쿠키에 남은 신원 토큰은 더 이상 인증에 쓰이지 못한다.**
          """)
  @ApiResponse(responseCode = "204", description = "로그아웃됨")
  @ApiResponse(
      responseCode = "401",
      description = "`UNAUTHENTICATED`",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
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

  /**
   * 탈퇴하면 <b>무엇이 남는지</b> (spec 2-2 §2-2-4 MUST, #223).
   *
   * <p><b>관리자용 경로를 열어 쓰지 않는다.</b> 그쪽은 {@code {id}}를 받으므로 열면 <b>남의 콘텐츠 건수를 세어 볼 수 있다.</b> 여기는 대상이
   * 언제나 요청자 자신이라 id를 받지 않는다.
   *
   * <p><b>{@code PENDING}도 부른다.</b> 건수는 전부 {@code 0}이겠지만 화면이 그것을 확인하고 창을 그린다 — 상태에 따라 확인 창의 모양이 갈리면
   * 그 분기가 두 벌이 된다.
   */
  @Operation(
      summary = "탈퇴하면 남을 내 콘텐츠 건수",
      description =
          """
          탈퇴 확인 창이 쓰는 값이다. **네 값을 항상 담는다** — `0`을 빼면 화면이 "없음"과
          "모름"을 가르지 못한다.

          계정이 사라지고 작성자 관계가 끊기면 **본인도 운영자도 그 콘텐츠를 다시 찾을 수
          없다.** 그래서 지우기 전에 남을 것을 보여준다.
          """)
  @ApiResponse(responseCode = "200", description = "자료·공지·활동사진·게시글 건수")
  @ApiResponse(
      responseCode = "401",
      description = "`UNAUTHENTICATED`",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description = "`SUSPENDED` — 정지된 계정은 이 경로에 닿지 못한다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping("/me/content-summary")
  @PreAuthorize("isAuthenticated()")
  public ContentSummaryResponse myContentSummary(@AuthenticationPrincipal Long userId) {
    return userContentSummaryService.of(userId);
  }

  /**
   * 회원 탈퇴 (spec 2-2 §2-2-4 "본인 탈퇴", #223).
   *
   * <p><b>처리는 관리자의 회원 제거와 같다</b> — 자료·공지·활동사진·게시글은 남고 작성자가 "탈퇴한 회원"이 되며, 즐겨찾기는 함께 사라진다. 규칙은 {@link
   * org.hackerkhu.hackerhp.domain.user.service.UserRemovalService}에 한 벌만 있다.
   *
   * <p><b>{@code PENDING}도 부를 수 있다</b> (MUST). 막으면 구글 로그인만 해보고 신청서를 내지 않은 사람이 <b>자기 계정을 지울 방법이
   * 없다</b> — 그 계정은 승인 목록에 뜨지 않아 관리자 눈에도 띄지 않는다.
   *
   * <p><b>{@code SUSPENDED}는 {@code AccountStatusFilter}가 먼저 막는다.</b> 탈퇴를 위한 예외를 뚫지 않는다 — 열면 정지된
   * 사람이 탈퇴 후 재가입으로 정지를 지운다.
   */
  @Operation(
      summary = "회원 탈퇴",
      description =
          """
          내 계정을 지운다. **자료·공지·활동사진·게시글은 남고** 작성자 표시가 "탈퇴한 회원"으로
          바뀐다. 즐겨찾기는 함께 사라진다.

          **같은 구글 계정으로 다시 가입할 수 있다** — 계정 레코드를 지우므로 붙잡는 행이 남지
          않는다.

          응답이 **세션과 신원 토큰 쿠키를 함께 버린다.** 저장소의 세션 행만 지우면 이 요청에
          붙어 있는 세션이 응답을 내보낼 때 되살아난다.
          """)
  @ApiResponse(responseCode = "204", description = "탈퇴됨")
  @ApiResponse(
      responseCode = "401",
      description = "`UNAUTHENTICATED`",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description = "`FORBIDDEN` — **마지막 활성 관리자는 탈퇴할 수 없다.** / `SUSPENDED` — 정지된 계정은 이 경로에 닿지 못한다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "`CONCURRENT_CHANGE` — 지우기 직전에 관리자가 계정을 되살렸다. **다시 시도하면 된다**",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "500",
      description =
          "`INTERNAL_ERROR` — 정지가 세션에 반영되지 않아 탈퇴를 멈췄다. **계정이 정지된 채로 남아 본인은 이어갈 수 없으므로 운영자가 이어받는다**",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping("/me")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<Void> withdraw(
      @AuthenticationPrincipal Long userId,
      HttpServletRequest request,
      HttpServletResponse response) {
    userRemovalService.withdraw(userId);
    /*
     * 본인을 지웠다. 저장소의 세션 행은 이미 사라졌지만, 이 요청에 붙어 있는 세션은 응답을
     * 내보낼 때 다시 저장된다 — 방금 지운 세션이 되살아나 만료까지 남는다 (2-2 §2-2-4 MUST).
     * 로그아웃과 같은 처리다.
     */
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    accessTokenCookie.clear(response);
    return ResponseEntity.noContent().build();
  }
}
