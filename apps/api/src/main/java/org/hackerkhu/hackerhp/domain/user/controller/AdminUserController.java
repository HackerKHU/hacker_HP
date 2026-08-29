package org.hackerkhu.hackerhp.domain.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.hackerkhu.hackerhp.domain.user.dto.AdminUserResponse;
import org.hackerkhu.hackerhp.domain.user.dto.AdminUserSearch;
import org.hackerkhu.hackerhp.domain.user.dto.ApproveRequest;
import org.hackerkhu.hackerhp.domain.user.dto.ApproveResponse;
import org.hackerkhu.hackerhp.domain.user.dto.ContentSummaryResponse;
import org.hackerkhu.hackerhp.domain.user.dto.DeactivateResponse;
import org.hackerkhu.hackerhp.domain.user.dto.ReactivateRequest;
import org.hackerkhu.hackerhp.domain.user.dto.ReactivateResponse;
import org.hackerkhu.hackerhp.domain.user.dto.RejectRequest;
import org.hackerkhu.hackerhp.domain.user.dto.RejectResponse;
import org.hackerkhu.hackerhp.domain.user.dto.RoleChangeRequest;
import org.hackerkhu.hackerhp.domain.user.dto.StatusChangeRequest;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.service.AdminUserApprovalService;
import org.hackerkhu.hackerhp.domain.user.service.AdminUserRejectService;
import org.hackerkhu.hackerhp.domain.user.service.AdminUserRoleService;
import org.hackerkhu.hackerhp.domain.user.service.AdminUserService;
import org.hackerkhu.hackerhp.domain.user.service.AdminUserStatusService;
import org.hackerkhu.hackerhp.domain.user.service.SemesterTransitionService;
import org.hackerkhu.hackerhp.domain.user.service.UserContentSummaryService;
import org.hackerkhu.hackerhp.domain.user.service.UserRemovalService;
import org.hackerkhu.hackerhp.global.auth.AccessTokenCookie;
import org.hackerkhu.hackerhp.global.error.ErrorResponse;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 관리 (spec 3-2 §3-2-6). 목록 조회·일괄 승인·거부, 상태 변경, 권한 변경, 제거다.
 *
 * <p><b>권한은 {@code hasRole('ADMIN')}만 적는다.</b> 매트릭스의 {@code ADMIN} 열은 "{@code ADMIN}이면서 {@code
 * ACTIVE}"지만 {@code ACTIVE} 조건은 {@code AccountStatusFilter}가 인가보다 먼저 보장한다 — 같은 규칙을 두 곳에 두면 한쪽만 고쳐진다
 * (T-147).
 *
 * <p>{@code /api/v1/admin/**}에는 필터 체인에도 같은 규칙이 걸려 있다. 본문을 읽기 전에 끊어야 권한 부족이 {@code 400}으로 둔갑하지 않는다
 * (T-148).
 *
 * <p><b>거절은 세 갈래다.</b> {@code AccountStatusFilter}가 인가보다 먼저 {@code SUSPENDED}·{@code
 * PENDING_APPROVAL}을 각각의 코드로 막고, 권한이 모자란 경우만 {@code FORBIDDEN}이다 (§3-2-7). 셋 다 {@code 403}이라 <b>화면이
 * 가르는 근거는 코드뿐이므로</b> 명세에도 셋을 적는다.
 */
@Tag(name = "회원 관리", description = "관리자 전용. 누가 있는지 확인하고 쌓인 가입 신청을 한 번에 처리한다")
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

  private final AdminUserService adminUserService;
  private final UserContentSummaryService userContentSummaryService;
  private final SemesterTransitionService semesterTransitionService;
  private final AdminUserApprovalService adminUserApprovalService;
  private final AdminUserStatusService adminUserStatusService;
  private final AdminUserRejectService adminUserRejectService;
  private final AdminUserRoleService adminUserRoleService;
  private final UserRemovalService userRemovalService;
  private final AccessTokenCookie accessTokenCookie;

  public AdminUserController(
      AdminUserService adminUserService,
      UserContentSummaryService userContentSummaryService,
      SemesterTransitionService semesterTransitionService,
      AdminUserApprovalService adminUserApprovalService,
      AdminUserStatusService adminUserStatusService,
      AdminUserRejectService adminUserRejectService,
      AdminUserRoleService adminUserRoleService,
      UserRemovalService userRemovalService,
      AccessTokenCookie accessTokenCookie) {
    this.adminUserService = adminUserService;
    this.userContentSummaryService = userContentSummaryService;
    this.semesterTransitionService = semesterTransitionService;
    this.adminUserApprovalService = adminUserApprovalService;
    this.adminUserStatusService = adminUserStatusService;
    this.adminUserRejectService = adminUserRejectService;
    this.adminUserRoleService = adminUserRoleService;
    this.userRemovalService = userRemovalService;
    this.accessTokenCookie = accessTokenCookie;
  }

  @Operation(
      summary = "회원 목록 조회",
      description =
          """
          검색·필터·정렬·페이지네이션을 지원한다.

          **`sort`는 `name`·`studentNo`·`appliedAt`만 받는다.** 그 밖의 값은 조용히 무시하지 않고
          `400 VALIDATION_ERROR`로 거절한다 — 무시하면 관리자가 정렬된 줄 알고 틀린 순서의
          명단을 신뢰한다. 보내지 않으면 **가입 신청일 최신순**이고, 신청하지 않은 계정은 뒤로 간다.

          **`status=PENDING`만으로는 승인 대기를 고를 수 없다.** 구글 로그인만 해보고 신청서를
          내지 않은 계정도 `PENDING`이기 때문이다. 승인 대상 집합은
          `status=PENDING&applied=true`다.
          """)
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @ApiResponse(
      responseCode = "400",
      description = "`VALIDATION_ERROR` — 정렬할 수 없는 필드이거나 `status`·`role` 값이 잘못됐다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "`UNAUTHENTICATED` — 쿠키 두 개가 함께 있어야 한다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description =
          "`FORBIDDEN` — `ADMIN`이 아니다 (T-05) · `SUSPENDED` — 정지된 계정 · `PENDING_APPROVAL` — 승인 대기 계정",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping
  @PreAuthorize("hasRole('ADMIN')")
  public PagedModel<AdminUserResponse> list(
      @Parameter(description = "상태 필터") @RequestParam(required = false) Status status,
      @Parameter(description = "권한 필터") @RequestParam(required = false) Role role,
      @Parameter(description = "이름·학번·이메일 통합 검색. 대소문자를 가리지 않는 부분 일치")
          @RequestParam(required = false)
          String q,
      @Parameter(description = "신청서 제출 여부. `status=PENDING&applied=true`가 승인 대상이다")
          @RequestParam(required = false)
          Boolean applied,
      @ParameterObject Pageable pageable) {
    return new PagedModel<>(
        adminUserService.search(new AdminUserSearch(status, role, q, applied), pageable));
  }

  /**
   * 가입 일괄 승인 (spec 2-2 §2-2-2).
   *
   * <p><b>일부가 실패해도 {@code 200}이다.</b> 실패는 예외가 아니라 결과로 돌려준다 — 한 건 때문에 되돌리면 성공한 승인까지 사라진다. 화면은 성공·실패
   * 건수를 안내해야 한다 (2-2 §2-2-2 MUST).
   */
  @Operation(
      summary = "가입 일괄 승인",
      description =
          """
          고른 계정을 한 번에 `PENDING` → `ACTIVE`로 바꾸고 승인일시를 기록한다.

          **승인 대상은 신청서를 낸 `PENDING`뿐이다.** 목록에서 걸렀더라도 이 API를 직접
          부르는 경로가 남아 있으므로 서버가 다시 확인한다 — 신청하지 않은 계정을 승인하면
          학번이 빈 `ACTIVE`가 만들어지는데, 신청 API는 `PENDING` 전용이라 나중에 채울
          방법이 없다.

          **일부가 실패해도 상태 코드는 `200`이다.** 실패한 건은 `failed`에 사유와 함께 담기고
          그 계정의 상태는 바뀌지 않는다.
          """)
  @ApiResponse(responseCode = "200", description = "처리됨. 일부 실패가 섞여 있을 수 있다")
  @ApiResponse(
      responseCode = "400",
      description = "`VALIDATION_ERROR` — 선택이 비었거나 100명을 넘었다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "`UNAUTHENTICATED` — 쿠키 두 개가 함께 있어야 한다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description =
          "`FORBIDDEN` — `ADMIN`이 아니거나 CSRF 토큰이 없다 · `SUSPENDED` — 정지된 계정 · `PENDING_APPROVAL` — 승인 대기 계정",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping("/approve")
  @PreAuthorize("hasRole('ADMIN')")
  public ApproveResponse approve(
      @AuthenticationPrincipal Long requesterId, @Valid @RequestBody ApproveRequest request) {
    return adminUserApprovalService.approve(requesterId, request.userIds());
  }

  /**
   * 학기 전환 — 일괄 비활성화 (spec 2-2 §2-2-3, #230).
   *
   * <p><b>본문을 받지 않는다</b> (MUST). 대상은 {@code role = 'USER' AND status = 'ACTIVE'}인 전원으로 서버가 정한다 —
   * id를 받으면 100개 상한에 걸려 학기 전환이 페이지 수만큼 쪼개지고, <b>한 페이지를 빠뜨려도 아무도 모른다.</b>
   *
   * <p><b>누르기 전에 대상 건수를 보여준다</b> (MUST). 조건으로 고르므로 관리자는 목록에서 누가 바뀌는지 볼 수 없다. 건수는 {@code GET
   * /admin/users?status=ACTIVE&role=USER&size=1}의 {@code page.totalElements}로 얻는다 — 미리보기 전용 API를 두지
   * 않는다.
   */
  @Operation(
      summary = "학기 전환 — 일괄 비활성화",
      description =
          """
          `ACTIVE`인 일반 부원 **전원**을 `INACTIVE`로 내린다. **본문을 받지 않는다** — 대상을
          서버가 정한다.

          `ADMIN`·`SUSPENDED`·`PENDING`은 휩쓸리지 않는다. 정지된 계정을 `INACTIVE`로 바꾸면
          **정지가 풀리기** 때문이다.

          **응답은 실제로 바뀐 id다.** 이미 비활동이던 사람은 들어가지 않는다. 잘못 눌렀으면
          이 배열을 그대로 복구에 넣는다 — 응답을 잃어도 `deactivatedAt`으로 직전 배치를
          고를 수 있다.

          **멱등하다.** 두 번째는 빈 배열이다.
          """)
  @ApiResponse(responseCode = "200", description = "내려간 계정의 id")
  @ApiResponse(
      responseCode = "401",
      description = "`UNAUTHENTICATED`",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description =
          "`FORBIDDEN` — 관리자가 아니거나 CSRF 토큰이 없다 · `SUSPENDED` — 정지된 계정 · `PENDING_APPROVAL` — 승인 대기 계정",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "500",
      description = "`INTERNAL_ERROR` — 비활성화가 세션에 반영되지 않았다. **변경은 되돌리지 않으므로 같은 요청을 다시 보내면 된다**",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping("/deactivate")
  @PreAuthorize("hasRole('ADMIN')")
  public DeactivateResponse deactivate(@AuthenticationPrincipal Long requesterId) {
    return semesterTransitionService.deactivate(requesterId);
  }

  /**
   * 학기 전환 — 일괄 복구 (spec 2-2 §2-2-3, #230).
   *
   * <p><b>비활성화와 모양이 다르다.</b> 올라올 사람은 매번 다르므로 id 목록을 받는다 — 조건으로 전원을 올리면 비활성화가 무의미해진다.
   */
  @Operation(
      summary = "학기 전환 — 일괄 복구",
      description =
          """
          고른 `INACTIVE` 계정을 `ACTIVE`로 되돌린다. **되돌리는 길은 이것 하나뿐이다** —
          비활성화에 별도의 취소를 두지 않는다.

          일부가 실패해도 `200`이고 **실패가 성공을 되돌리지 않는다.** 정지된 계정은
          `NOT_INACTIVE`로 집계된다 — 이 경로로 정지를 풀 수 없다.
          """)
  @ApiResponse(responseCode = "200", description = "복구 결과. 일부 실패해도 200이다")
  @ApiResponse(
      responseCode = "401",
      description = "`UNAUTHENTICATED`",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "400",
      description = "`VALIDATION_ERROR` — 빈 배열이거나 100개를 넘었다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description =
          "`FORBIDDEN` — 관리자가 아니거나 CSRF 토큰이 없다 · `SUSPENDED` — 정지된 계정 · `PENDING_APPROVAL` — 승인 대기 계정",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping("/reactivate")
  @PreAuthorize("hasRole('ADMIN')")
  public ReactivateResponse reactivate(
      @AuthenticationPrincipal Long requesterId, @Valid @RequestBody ReactivateRequest request) {
    return semesterTransitionService.reactivate(requesterId, request.userIds());
  }

  /**
   * 회원 상태 변경 — 정지와 해제 (spec 2-2 §2-2-3).
   *
   * <p><b>정지는 즉시 차단이다</b> (MUST). 이미 로그인해 있는 세션도 다음 요청에서 막힌다 (T-32).
   *
   * <p>요청자를 받는 이유는 <b>마지막 활성 관리자가 자기 자신을 정지하는 것을 막기 위해서다</b> (§2-2-7 MUST). 화면은 활성 관리자가 몇 명인지 모르므로
   * 이 판단을 하지 않는다 (T-80).
   */
  @Operation(
      summary = "회원 상태 변경",
      description =
          """
          `ACTIVE` ↔ `SUSPENDED`. 갱신된 회원을 돌려준다.

          **정지는 즉시 차단이다.** 이미 로그인해 있는 세션도 다음 요청부터 `403 SUSPENDED`가
          된다 — 세션을 지우지 않고 갱신하므로 `401`이 아니다.

          **승인 대기(`PENDING`) 계정은 이 경로의 대상이 아니다.** 승인은
          `POST /admin/users/approve`가 한다.

          **이미 그 상태면 아무것도 하지 않고 현재 상태를 돌려준다.**
          """)
  @ApiResponse(responseCode = "200", description = "변경됨. 본문은 갱신된 회원이다")
  @ApiResponse(
      responseCode = "400",
      description = "`VALIDATION_ERROR` — `status`가 없거나 `ACTIVE`·`SUSPENDED`가 아니거나, 대상이 승인 대기 계정이다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "`UNAUTHENTICATED` — 쿠키 두 개가 함께 있어야 한다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description =
          "`FORBIDDEN` — `ADMIN`이 아니거나, CSRF 토큰이 없거나, **마지막 활성 관리자가 자기 자신을 정지하려 했다** · `SUSPENDED` — 정지된 계정 · `PENDING_APPROVAL` — 승인 대기 계정",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "`NOT_FOUND` — 그 id의 회원이 없다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "500",
      description =
          "`INTERNAL_ERROR` — **정지가 세션에 반영되지 않았다.** 상태는 이미 바뀌었으므로 같은 요청을 다시 보내면 복구된다 (2-2 §2-2-5)",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PatchMapping("/{id}/status")
  @PreAuthorize("hasRole('ADMIN')")
  public AdminUserResponse changeStatus(
      @AuthenticationPrincipal Long requesterId,
      @PathVariable Long id,
      @Valid @RequestBody StatusChangeRequest request) {
    return adminUserStatusService.change(requesterId, id, request.status());
  }

  /**
   * 가입 일괄 거부 (spec 2-2 §2-2-2).
   *
   * <p><b>계정 레코드를 지운다.</b> 별도 상태를 두지 않으므로 거부된 사람은 같은 이메일로 재신청할 수 있다 — 상태로 남기면 그 계정이 UNIQUE를 붙잡아 다시
   * 가입할 수 없다.
   */
  @Operation(
      summary = "가입 일괄 거부",
      description =
          """
          고른 신청을 한 번에 지운다. **일부가 실패해도 `200`이다** — 한 건 때문에 되돌리면
          성공한 거부까지 사라진다. 화면은 성공·실패 건수를 안내해야 한다.

          **대상은 `PENDING`뿐이다.** 이용 중인 회원을 이 경로로 지우면 "제거"가 되는데,
          그쪽은 세션 폐기·정지 선행 같은 규칙이 따로 붙는다 (2-2 §2-2-4). 그 건은
          `NOT_PENDING`으로 집계한다.
          """)
  @ApiResponse(responseCode = "200", description = "처리됨 (일부 실패 포함)")
  @ApiResponse(
      responseCode = "400",
      description = "`VALIDATION_ERROR` — 빈 목록이거나 100개를 넘었다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "`UNAUTHENTICATED` — 쿠키 두 개가 함께 있어야 한다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description =
          "`FORBIDDEN` — `ADMIN`이 아니거나 CSRF 토큰이 없다 · `SUSPENDED` — 정지된 계정 · `PENDING_APPROVAL` — 승인 대기 계정",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping("/reject")
  @PreAuthorize("hasRole('ADMIN')")
  public RejectResponse reject(
      @AuthenticationPrincipal Long requesterId, @Valid @RequestBody RejectRequest request) {
    return adminUserRejectService.reject(requesterId, request.userIds());
  }

  /**
   * 관리자 권한 부여·회수 (spec 2-2 §2-2-5).
   *
   * <p><b>Role만 바꾼다. Status는 건드리지 않는다.</b>
   */
  @Operation(
      summary = "관리자 권한 부여·회수",
      description =
          """
          `USER` ↔ `ADMIN`. **뒤집는 것이 아니라 원하는 권한을 말한다** — 화면이 들고 있는 값이
          낡았을 때 의도와 반대로 바뀌지 않는다.

          **회수 뒤에 활성 관리자가 한 명도 남지 않으면 `403`이다** (2-2 §2-2-7).
          자기 대상인지와 무관하다 — 관리자가 둘일 때 서로의 권한을 동시에 회수하면 두 요청
          모두 자기 검사에 걸리지 않고 0명이 된다.

          권한이 회수되면 **그 사람의 기존 세션에도 즉시 반영된다** (T-34).
          """)
  @ApiResponse(responseCode = "200", description = "변경됨 (이미 그 권한이던 경우 포함)")
  @ApiResponse(
      responseCode = "400",
      description = "`VALIDATION_ERROR` — 승인 대기 중인 계정이다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "401",
      description = "`UNAUTHENTICATED` — 쿠키 두 개가 함께 있어야 한다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description =
          "`FORBIDDEN` — `ADMIN`이 아니거나, CSRF 토큰이 없거나, **회수 뒤에 활성 관리자가 남지 않는다** · `SUSPENDED` — 정지된 계정 · `PENDING_APPROVAL` — 승인 대기 계정",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "`NOT_FOUND` — 그 id의 회원이 없다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "500",
      description =
          "`INTERNAL_ERROR` — **권한 회수가 세션에 반영되지 않았다.** 권한은 이미 바뀌었으므로 같은 요청을 다시 보내면 복구된다 (2-2 §2-2-5)",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PatchMapping("/{id}/role")
  @PreAuthorize("hasRole('ADMIN')")
  public AdminUserResponse changeRole(
      @AuthenticationPrincipal Long requesterId,
      @PathVariable Long id,
      @Valid @RequestBody RoleChangeRequest request) {
    return adminUserRoleService.change(requesterId, id, request.role());
  }

  /**
   * 제거 확인 창이 <b>"무엇이 남는지"</b>를 보여주기 위해 쓴다 (spec 2-2 §2-2-4 MUST).
   *
   * <p><b>콘텐츠 종류가 늘면 이 설명도 늘어야 한다</b> (#236 리뷰). Swagger를 보고 제거 영향을 판단하는 사람이 빠진 종류를 알 길이 없다.
   */
  @Operation(
      summary = "제거 시 남을 콘텐츠 건수",
      description =
          """
          그 회원이 남길 자료·공지·활동사진·게시글의 건수다. **네 값을 항상 담는다** — `0`을 빼면
          화면이 "없음"과 "모름"을 가르지 못한다.

          **확인 창을 여는 시점의 참고치이지 제거의 조건이 아니다.** 그 사이 건수가 바뀌어도
          제거는 그대로 진행한다.
          """)
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @ApiResponse(
      responseCode = "401",
      description = "`UNAUTHENTICATED` — 쿠키 두 개가 함께 있어야 한다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description =
          "`FORBIDDEN` — `ADMIN`이 아니다 · `SUSPENDED` — 정지된 계정 · `PENDING_APPROVAL` — 승인 대기 계정",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "`NOT_FOUND` — 그 id의 회원이 없다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping("/{id}/content-summary")
  @PreAuthorize("hasRole('ADMIN')")
  public ContentSummaryResponse contentSummary(@PathVariable Long id) {
    return userContentSummaryService.of(id);
  }

  /**
   * 회원 제거 (spec 2-2 §2-2-4).
   *
   * <p><b>본인을 지웠으면 지금 요청의 세션과 토큰까지 끝낸다</b> (MUST). 저장소에서 세션 행을 지워도, 이 요청에 붙어 있는 세션은 응답을 내보낼 때 다시
   * 저장되어 <b>방금 지운 {@code ADMIN} 세션이 되살아난다.</b>
   */
  @Operation(
      summary = "회원 제거",
      description =
          """
          계정을 지운다. 그 사람이 올린 **자료·공지·활동사진·게시글은 남고** 작성자 표시만
          "탈퇴한 회원"으로 바뀐다. 즐겨찾기는 함께 사라진다 (2-2 §2-2-4).

          **지우기 전에 정지를 먼저 확정한다.** 세션 폐기는 계정이 사라진 뒤라 실패해도
          되돌릴 수 없는데, 정지가 먼저면 어느 지점에서 실패하든 이미 막혀 있다.

          **제거 뒤에 활성 관리자가 한 명도 남지 않으면 `403`이다** (2-2 §2-2-7).
          """)
  @ApiResponse(responseCode = "204", description = "제거됨")
  @ApiResponse(
      responseCode = "401",
      description = "`UNAUTHENTICATED` — 쿠키 두 개가 함께 있어야 한다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description =
          "`FORBIDDEN` — `ADMIN`이 아니거나, CSRF 토큰이 없거나, **제거 뒤에 활성 관리자가 남지 않는다** · `SUSPENDED` — 정지된 계정 · `PENDING_APPROVAL` — 승인 대기 계정",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "`NOT_FOUND` — 그 id의 회원이 없다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "409",
      description = "`CONCURRENT_CHANGE` — 다른 관리자가 그 사이에 대상을 다시 `ACTIVE`로 돌렸다. **지우지 않고 멈춘다**",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "500",
      description =
          "`INTERNAL_ERROR` — 정지가 세션에 반영되지 않아 제거를 멈췄다. **대상은 정지된 채로 남으므로 같은 요청을 다시 보내면 된다**",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('ADMIN')")
  public void remove(
      @AuthenticationPrincipal Long requesterId,
      @PathVariable Long id,
      HttpServletRequest httpRequest,
      HttpServletResponse httpResponse) {
    if (!userRemovalService.remove(requesterId, id)) {
      return;
    }
    /*
     * 본인을 지웠다. 저장소의 세션 행은 이미 사라졌지만, 이 요청에 붙어 있는 세션은
     * 응답을 내보낼 때 다시 저장된다 — 방금 지운 ADMIN 세션이 되살아나 만료까지 남는다
     * (2-2 §2-2-4 MUST). 로그아웃과 같은 처리를 한다.
     */
    HttpSession session = httpRequest.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    accessTokenCookie.clear(httpResponse);
  }
}
