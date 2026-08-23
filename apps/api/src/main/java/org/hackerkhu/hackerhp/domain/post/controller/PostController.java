package org.hackerkhu.hackerhp.domain.post.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.hackerkhu.hackerhp.domain.post.dto.PostCreateRequest;
import org.hackerkhu.hackerhp.domain.post.dto.PostDetailResponse;
import org.hackerkhu.hackerhp.domain.post.dto.PostSummaryResponse;
import org.hackerkhu.hackerhp.domain.post.service.PostService;
import org.hackerkhu.hackerhp.global.error.ErrorResponse;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자유 게시판 (spec 2-1 §2-1-8, 3-2 §3-2-5).
 *
 * <p><b>{@code isAuthenticated()}만 적는다.</b> 매트릭스의 {@code ACTIVE} 조건은 {@code AccountStatusFilter}가
 * 인가보다 먼저 보장한다 — 같은 규칙을 두 곳에 두면 한쪽만 고쳐진다 ({@code NoteController}·{@code NoticeController}와 같은 관례).
 *
 * <p><b>수정·삭제는 없다</b> (3-3 결정 16). 작성자 수정은 #256, 관리자 삭제는 #238이다.
 */
@Tag(name = "자유 게시판", description = "부원끼리 글을 올리고 읽는다. ACTIVE 전용")
@RestController
@RequestMapping("/api/v1/posts")
public class PostController {

  private final PostService postService;

  public PostController(PostService postService) {
    this.postService = postService;
  }

  @Operation(
      summary = "게시글 목록",
      description =
          """
          최신순 고정이다. **정렬 파라미터를 받지 않는다** — `sort`를 보내도 무시된다.

          정렬 기준은 `createdAt DESC, id DESC`이며 **마지막 기준이 `id`인 것이 핵심이다.**
          같은 시각에 올라온 글이 있으면 그것이 없을 때 페이지를 넘길 때마다 배치가 달라져
          **같은 글이 두 번 보이거나 아예 빠진다.**

          **본문은 담지 않는다.** 상세에서만 준다 — 본문 상한이 10,000자라 20건이면
          그것만으로 응답이 200KB가 된다.
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
      description = "`SUSPENDED` — 정지된 계정 · `PENDING_APPROVAL` — 승인 대기 계정",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  public PagedModel<PostSummaryResponse> list(@ParameterObject Pageable pageable) {
    return new PagedModel<>(postService.list(pageable));
  }

  @Operation(
      summary = "게시글 상세",
      description =
          """
          목록에 `content`와 `updatedAt`이 더해진다.

          **본문은 받은 그대로 나간다.** 서버가 정화하거나 마크다운으로 해석하지 않는다 —
          화면이 텍스트 노드로 그리는 것까지가 이 계약의 일부다.
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
      description = "`SUSPENDED` · `PENDING_APPROVAL`",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @ApiResponse(
      responseCode = "404",
      description = "`NOT_FOUND` — 없는 게시글",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @GetMapping("/{id}")
  @PreAuthorize("isAuthenticated()")
  public PostDetailResponse get(@PathVariable Long id) {
    return postService.get(id);
  }

  /**
   * 글 등록.
   *
   * <p><b>작성자는 인증 주체로만 정한다</b> (MUST). 본문으로 받으면 다른 사람 이름으로 글을 올릴 수 있다.
   */
  @Operation(
      summary = "게시글 등록",
      description =
          """
          제목과 본문을 받는다. **작성자는 로그인한 사람이다** — 본문으로 받지 않는다.

          **본문은 평문으로 저장된다.** HTML이나 마크다운을 넣어도 해석되지 않고 글자
          그대로 남는다 — 전 부원이 쓰는 자리라 서식을 허용하면 그 입력이 다른 부원의
          브라우저에서 실행될 수 있는 표면이 된다 (3-3 결정 16).

          **수정·삭제는 없다.** 올린 글은 지울 수 없다.
          """)
  @ApiResponse(responseCode = "201", description = "등록됨. 본문은 저장된 글이다")
  @ApiResponse(
      responseCode = "400",
      description = "`VALIDATION_ERROR` — 제목·본문이 비었거나(공백뿐인 경우 포함) 상한(200자 / 10,000자)을 넘었다",
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
      description = "CSRF 토큰이 없다 · `SUSPENDED` · `PENDING_APPROVAL`",
      content =
          @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = ErrorResponse.class)))
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("isAuthenticated()")
  public PostDetailResponse write(
      @AuthenticationPrincipal Long authorId, @Valid @RequestBody PostCreateRequest request) {
    return postService.write(authorId, request);
  }
}
