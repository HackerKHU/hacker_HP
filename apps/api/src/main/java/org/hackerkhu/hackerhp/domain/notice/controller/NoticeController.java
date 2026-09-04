package org.hackerkhu.hackerhp.domain.notice.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.hackerkhu.hackerhp.domain.notice.dto.NoticeRequest;
import org.hackerkhu.hackerhp.domain.notice.dto.NoticeResponse;
import org.hackerkhu.hackerhp.domain.notice.service.NoticeLikeService;
import org.hackerkhu.hackerhp.domain.notice.service.NoticeService;
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
 * 공지 조회·등록·수정·삭제·고정 토글 (spec 3-2 §3-2-5).
 *
 * <p><b>조회는 {@code isAuthenticated()}, 쓰기는 {@code hasRole('ADMIN')}만 적는다.</b> 매트릭스의 {@code ACTIVE}
 * 조건은 {@code AccountStatusFilter}가 인가보다 먼저 보장하므로 여기서 다시 적지 않는다 — 같은 규칙을 두 곳에 두면 한쪽만 고쳐진다 ({@code
 * AdminUserController}와 동일한 관례).
 */
@Tag(name = "공지", description = "고정 공지가 항상 최상단에 온다. 조회는 ACTIVE, 등록·수정·삭제는 ADMIN 전용")
@RestController
@RequestMapping("/api/v1/notices")
public class NoticeController {

  private final NoticeService noticeService;
  private final NoticeLikeService noticeLikeService;

  public NoticeController(NoticeService noticeService, NoticeLikeService noticeLikeService) {
    this.noticeService = noticeService;
    this.noticeLikeService = noticeLikeService;
  }

  @Operation(
      summary = "공지 목록 조회",
      description =
          """
          페이지네이션을 지원한다. 정렬은 항상 `is_pinned DESC, created_at DESC`로 고정되며
          클라이언트가 바꿀 수 없다.

          각 항목은 `likeCount`(전체 좋아요 수)와 `likedByMe`(내가 눌렀는지)를 담는다.
          """)
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  public PagedModel<NoticeResponse> list(
      @AuthenticationPrincipal Long viewerId,
      @RequestParam(defaultValue = "false") boolean liked,
      @ParameterObject Pageable pageable) {
    return new PagedModel<>(noticeService.list(pageable, viewerId, liked));
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
  public NoticeResponse get(@AuthenticationPrincipal Long viewerId, @PathVariable Long id) {
    return noticeService.get(id, viewerId);
  }

  @Operation(summary = "공지 등록", description = "작성자는 요청 본문이 아니라 인증 주체로 정한다.")
  @ApiResponse(responseCode = "201", description = "등록됨. 본문은 저장된 공지")
  @ApiResponse(
      responseCode = "400",
      description = "`VALIDATION_ERROR` — 제목·내용이 비었거나 제목이 200자를 넘었다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "403",
      description = "`FORBIDDEN` — `ADMIN`이 아니다",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.CREATED)
  public NoticeResponse create(
      @AuthenticationPrincipal Long userId, @Valid @RequestBody NoticeRequest request) {
    return noticeService.create(userId, request);
  }

  @Operation(summary = "공지 수정")
  @ApiResponse(responseCode = "200", description = "수정됨. 본문은 저장된 공지")
  @ApiResponse(
      responseCode = "404",
      description = "`NOT_FOUND` — 없는 공지",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PatchMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public NoticeResponse update(
      @AuthenticationPrincipal Long viewerId,
      @PathVariable Long id,
      @Valid @RequestBody NoticeRequest request) {
    return noticeService.update(id, request, viewerId);
  }

  @Operation(summary = "공지 삭제")
  @ApiResponse(responseCode = "204", description = "삭제됨")
  @ApiResponse(
      responseCode = "404",
      description = "`NOT_FOUND` — 없는 공지",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable Long id) {
    noticeService.delete(id);
  }

  @Operation(summary = "공지 고정 토글", description = "고정된 공지는 해제하고, 해제된 공지는 고정한다. 고정 개수 상한은 없다.")
  @ApiResponse(responseCode = "200", description = "토글됨. 본문은 저장된 공지")
  @ApiResponse(
      responseCode = "404",
      description = "`NOT_FOUND` — 없는 공지",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PatchMapping("/{id}/pin")
  @PreAuthorize("hasRole('ADMIN')")
  public NoticeResponse togglePin(@AuthenticationPrincipal Long viewerId, @PathVariable Long id) {
    return noticeService.togglePin(id, viewerId);
  }

  @Operation(
      summary = "공지 좋아요",
      description =
          """
          **이미 눌렀어도 성공이다.** 목록과 상세에서 각각 누르거나 두 번 누르는 일은 흔한데,
          그때 오류를 주면 사용자에게 의미 없는 안내를 띄워야 한다.

          **토글이 아니다.** 같은 요청이 상태를 뒤집으면 재시도가 방금 누른 것을 조용히 뗀다 —
          화면은 응답의 `likedByMe`를 보고 누를지 뗄지 고른다.
          """)
  @ApiResponse(responseCode = "204", description = "눌렸다 (이미 눌러져 있던 경우 포함)")
  @ApiResponse(
      responseCode = "404",
      description = "`NOT_FOUND` — 없는 공지",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping("/{id}/like")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("isAuthenticated()")
  public void like(@AuthenticationPrincipal Long viewerId, @PathVariable Long id) {
    noticeLikeService.add(viewerId, id);
  }

  @Operation(
      summary = "공지 좋아요 취소",
      description =
          """
          **눌러져 있지 않아도 성공이다.** 없는 공지여도 `404`를 주지 않는다 — 공지가 지워지면
          좋아요도 함께 사라지므로 뗄 것이 이미 없고, 오류를 주면 화면이 지울 수 없는 표시를
          들고 있게 된다.
          """)
  @ApiResponse(responseCode = "204", description = "떼졌다 (눌러져 있지 않던 경우 포함)")
  @DeleteMapping("/{id}/like")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("isAuthenticated()")
  public void unlike(@AuthenticationPrincipal Long viewerId, @PathVariable Long id) {
    noticeLikeService.remove(viewerId, id);
  }
}
