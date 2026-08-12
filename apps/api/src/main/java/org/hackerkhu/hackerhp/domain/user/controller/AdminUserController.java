package org.hackerkhu.hackerhp.domain.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.hackerkhu.hackerhp.domain.user.dto.AdminUserResponse;
import org.hackerkhu.hackerhp.domain.user.dto.AdminUserSearch;
import org.hackerkhu.hackerhp.domain.user.entity.Role;
import org.hackerkhu.hackerhp.domain.user.entity.Status;
import org.hackerkhu.hackerhp.domain.user.service.AdminUserService;
import org.hackerkhu.hackerhp.global.error.ErrorResponse;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 관리 (spec 3-2 §3-2-6). 지금은 목록 조회 하나다 — 승인은 #30, 상태 변경은 #31이다.
 *
 * <p><b>권한은 {@code hasRole('ADMIN')}만 적는다.</b> 매트릭스의 {@code ADMIN} 열은 "{@code ADMIN}이면서 {@code
 * ACTIVE}"지만 {@code ACTIVE} 조건은 {@code AccountStatusFilter}가 인가보다 먼저 보장한다 — 같은 규칙을 두 곳에 두면 한쪽만 고쳐진다
 * (T-147).
 *
 * <p>{@code /api/v1/admin/**}에는 필터 체인에도 같은 규칙이 걸려 있다. 본문을 읽기 전에 끊어야 권한 부족이 {@code 400}으로 둔갑하지 않는다
 * (T-148).
 */
@Tag(name = "회원 관리", description = "관리자 전용. 누가 있는지 검색·필터로 확인한다")
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

  private final AdminUserService adminUserService;

  public AdminUserController(AdminUserService adminUserService) {
    this.adminUserService = adminUserService;
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
      responseCode = "403",
      description = "`FORBIDDEN` — `ADMIN`이 아니다 (T-05)",
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
}
