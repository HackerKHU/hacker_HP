package org.hackerkhu.hackerhp.domain.notice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.hackerkhu.hackerhp.domain.notice.dto.NoticeResponse;
import org.hackerkhu.hackerhp.domain.notice.service.NoticeService;
import org.hackerkhu.hackerhp.global.error.ErrorResponse;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공지 조회 (spec 3-2 §3-2-5). 등록·수정·삭제·고정 토글은 ADMIN 전용 API(#33)가 맡는다.
 *
 * <p><b>권한은 {@code isAuthenticated()}만 적는다.</b> 매트릭스의 {@code ACTIVE} 조건은 {@code
 * AccountStatusFilter}가 인가보다 먼저 보장하므로 여기서 다시 적지 않는다 — 같은 규칙을 두 곳에 두면 한쪽만 고쳐진다. Role 제한은 없다 — {@code
 * USER}·{@code ADMIN} 모두 읽을 수 있다.
 */
@Tag(name = "공지", description = "고정 공지가 항상 최상단에 온다")
@RestController
@RequestMapping("/api/v1/notices")
public class NoticeController {

  private final NoticeService noticeService;

  public NoticeController(NoticeService noticeService) {
    this.noticeService = noticeService;
  }

  @Operation(
      summary = "공지 목록 조회",
      description =
          """
          페이지네이션을 지원한다. 정렬은 항상 `is_pinned DESC, created_at DESC`로 고정되며
          클라이언트가 바꿀 수 없다.
          """)
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  public PagedModel<NoticeResponse> list(@ParameterObject Pageable pageable) {
    return new PagedModel<>(noticeService.list(pageable));
  }

  @Operation(summary = "공지 상세 조회")
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @ApiResponse(
      responseCode = "404",
      description = "`NOT_FOUND` — 없는 공지",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping("/{id}")
  @PreAuthorize("isAuthenticated()")
  public NoticeResponse get(@PathVariable Long id) {
    return noticeService.get(id);
  }
}
