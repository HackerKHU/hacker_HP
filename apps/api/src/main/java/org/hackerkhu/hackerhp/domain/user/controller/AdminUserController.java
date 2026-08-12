package org.hackerkhu.hackerhp.domain.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.hackerkhu.hackerhp.domain.user.dto.AdminUserResponse;
import org.hackerkhu.hackerhp.domain.user.dto.AdminUserSearch;
import org.hackerkhu.hackerhp.domain.user.dto.ApproveRequest;
import org.hackerkhu.hackerhp.domain.user.dto.ApproveResponse;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.service.AdminUserApprovalService;
import org.hackerkhu.hackerhp.domain.user.service.AdminUserService;
import org.hackerkhu.hackerhp.global.error.ErrorResponse;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 관리 (spec 3-2 §3-2-6). 지금은 목록 조회와 일괄 승인이다 — 상태 변경은 #31, 거부·제거·권한 변경은 #58이다.
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
  private final AdminUserApprovalService adminUserApprovalService;

  public AdminUserController(
      AdminUserService adminUserService, AdminUserApprovalService adminUserApprovalService) {
    this.adminUserService = adminUserService;
    this.adminUserApprovalService = adminUserApprovalService;
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
  public ApproveResponse approve(@Valid @RequestBody ApproveRequest request) {
    return adminUserApprovalService.approve(request.userIds());
  }
}
