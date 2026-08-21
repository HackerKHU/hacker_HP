package org.hackerkhu.hackerhp.domain.note.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.hackerkhu.hackerhp.domain.note.dto.NoteDetailResponse;
import org.hackerkhu.hackerhp.domain.note.dto.NoteFilterOptions;
import org.hackerkhu.hackerhp.domain.note.dto.NoteSearch;
import org.hackerkhu.hackerhp.domain.note.dto.NoteSort;
import org.hackerkhu.hackerhp.domain.note.dto.NoteSummaryResponse;
import org.hackerkhu.hackerhp.domain.note.service.NoteQueryService;
import org.hackerkhu.hackerhp.global.error.ErrorResponse;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자료 목록·검색·필터·상세 (spec 2-1 §2-1-1, 3-2 §3-2-4).
 *
 * <p><b>{@code isAuthenticated()}만 적는다.</b> 매트릭스의 {@code ACTIVE} 조건은 {@code AccountStatusFilter}가
 * 인가보다 먼저 보장한다 — 같은 규칙을 두 곳에 두면 한쪽만 고쳐진다 ({@code NoticeController}와 같은 관례).
 *
 * <p>등록·수정·삭제와 다운로드 URL 발급은 여기 없다 (#53·#54·#55).
 */
@Tag(name = "자료", description = "쌓인 정리본을 찾는다. 조회는 ACTIVE 전용")
@RestController
@RequestMapping("/api/v1/notes")
public class NoteController {

  private final NoteQueryService noteQueryService;

  public NoteController(NoteQueryService noteQueryService) {
    this.noteQueryService = noteQueryService;
  }

  @Operation(
      summary = "자료 목록·검색·필터",
      description =
          """
          **검색어와 필터는 AND로 함께 걸린다.** `q`는 제목·과목명·교수명을 한 번에 찾는
          부분 일치이며 대소문자를 가리지 않는다.

          `sort`는 `latest`(기본)와 `title`만 받는다. 그 밖의 값은 기본값으로 본다 —
          화면이 조합해 보내는 값이라 `400`으로 막지 않는다.

          `category=SUBJECT&examType=MIDTERM`처럼 있을 수 없는 조합은 **오류가 아니라
          결과 0건**이다. 필터를 조합하는 순간마다 `400`을 받게 하지 않는다.
          """)
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  public PagedModel<NoteSummaryResponse> list(
      @ParameterObject NoteSearch search,
      @RequestParam(required = false) String sort,
      @ParameterObject Pageable pageable) {
    return new PagedModel<>(noteQueryService.list(search, NoteSort.from(sort), pageable));
  }

  @Operation(
      summary = "필터 옵션",
      description =
          """
          **실제 등록된 값에서 만든다.** 목록에 없는 과목을 고를 수 있으면 결과가 늘 0건이고,
          등록된 과목이 빠지면 찾을 방법이 사라진다.

          학기·시험 구분은 값이 고정이라 담지 않는다.
          """)
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @GetMapping("/filters")
  @PreAuthorize("isAuthenticated()")
  public NoteFilterOptions filters() {
    return noteQueryService.filters();
  }

  @Operation(
      summary = "자료 상세",
      description =
          """
          목록과 달리 **딸린 파일 목록**을 함께 준다. 각 파일의 `id`로 다운로드 URL을 요청한다 (#55).

          **S3 키는 담지 않는다** — 버킷이 비공개라 키를 알아도 열 수 없다.
          """)
  @ApiResponse(responseCode = "200", description = "조회 성공")
  @ApiResponse(
      responseCode = "404",
      description = "`NOT_FOUND` — 없는 자료",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping("/{id}")
  @PreAuthorize("isAuthenticated()")
  public NoteDetailResponse get(@PathVariable Long id) {
    return noteQueryService.get(id);
  }
}
