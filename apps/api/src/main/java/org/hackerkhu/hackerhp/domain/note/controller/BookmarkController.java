package org.hackerkhu.hackerhp.domain.note.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.hackerkhu.hackerhp.domain.note.dto.NoteSummaryResponse;
import org.hackerkhu.hackerhp.domain.note.service.NoteQueryService;
import org.hackerkhu.hackerhp.global.error.ErrorResponse;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 즐겨찾기 목록 (spec 2-1 §2-1-5, 3-2 §3-2-4).
 *
 * <p>추가·해제는 자료에 붙는 조작이라 {@code NoteController}에 있다. 목록만 여기 있는 이유는 <b>경로가 자료가 아니라 나에게 매달리기 때문이다</b>
 * ({@code GET /bookmarks}).
 */
@Tag(name = "즐겨찾기", description = "다시 볼 자료를 모아 본다. ACTIVE 전용")
@RestController
@RequestMapping("/api/v1/bookmarks")
public class BookmarkController {

  private final NoteQueryService noteQueryService;

  public BookmarkController(NoteQueryService noteQueryService) {
    this.noteQueryService = noteQueryService;
  }

  @Operation(
      summary = "내 즐겨찾기 목록",
      description =
          """
          **내 것만 나온다.** 응답은 자료 목록과 같은 형태라 화면이 같은 카드를 그린다.

          **정렬은 내가 표시한 순서(최신)** 다 — 자료의 등록 시각이 아니다. 이 화면의 기준은
          "언제 올라온 자료인가"가 아니라 "언제 내가 담았나"다.

          검색·필터는 받지 않는다. 이미 본인이 추린 목록이다.
          """)
  @ApiResponse(responseCode = "200", description = "조회 성공")
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
          "`SUSPENDED` — 정지된 계정 · `PENDING_APPROVAL` — 승인 대기 계정 · `INACTIVE` — **이번 학기 비활동 부원**",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  public PagedModel<NoteSummaryResponse> list(
      @AuthenticationPrincipal Long viewerId, @ParameterObject Pageable pageable) {
    return new PagedModel<>(noteQueryService.myBookmarks(viewerId, pageable));
  }
}
